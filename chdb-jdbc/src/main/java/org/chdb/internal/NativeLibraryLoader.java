package org.chdb.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Finds, unpacks and loads {@code libchdb} and the JNI shim, in that order, exactly once per
 * JVM.
 *
 * <h2>Where the libraries come from</h2>
 * In priority order (work plan section 5.4):
 *
 * <ol>
 *   <li>{@code -Dchdb.library.path=<dir>} -- a directory holding both libraries. Used as-is,
 *       with no copying and no checksum gate: the operator chose these bytes.
 *   <li>the {@code chdb-native-<platform>} JAR on the classpath, unpacked into a
 *       content-addressed cache directory.
 *   <li>{@code java.library.path}, via {@link System#loadLibrary(String)}.
 * </ol>
 *
 * Nothing here reaches the network. A missing platform package is a configuration error with
 * a Maven coordinate in the message, not something to download at runtime (section 2.3).
 *
 * <h2>Why the order matters</h2>
 * The shim records a dependency on {@code @rpath/libchdb.so} resolved through {@code
 * @loader_path}/{@code $ORIGIN}. Loading {@code libchdb} first by absolute path puts it in
 * the process's global symbol namespace, so the shim's dependency is already satisfied when
 * it is opened -- and {@code chdb_classify_query_n}, resolved by {@code dlsym(RTLD_DEFAULT)},
 * is findable.
 *
 * <h2>One owner ClassLoader</h2>
 * A JVM can hold one copy of a JNI library, and it belongs to the ClassLoader that loaded it
 * (section 3.5). {@link NativeOwner} publishes who that was, so a second, isolated
 * ClassLoader gets an explanation and a fix instead of a bare {@link UnsatisfiedLinkError}.
 */
public final class NativeLibraryLoader {

    /** Directory holding a JNI shim and a libchdb to use instead of the packaged pair. */
    public static final String PROP_LIBRARY_PATH = "chdb.library.path";

    /** Root of the content-addressed unpack cache. Defaults to {@code $TMPDIR/chdb-java}. */
    public static final String PROP_CACHE_DIR = "chdb.cache.dir";

    /** Legacy alias for {@link #PROP_CACHE_DIR}, honoured for continuity with other chDB bindings. */
    public static final String PROP_TMPDIR = "chdb.tmpdir";

    /** Skips the engine-version equality check. Diagnostic only; never for production. */
    public static final String PROP_SKIP_VERSION_CHECK = "chdb.skipEngineVersionCheck";

    /**
     * Re-verifies an already-unpacked cache directory against its checksums before loading.
     *
     * <p>Off by default. The checksums are verified when the libraries are unpacked, so the
     * only thing this catches is a cache that was corrupted or altered afterwards -- and
     * catching that costs a SHA-256 pass over ~350 MB on every JVM start, roughly a second.
     * That is the wrong default for a driver whose warm start is otherwise a tenth of a
     * second, and the right setting for a deployment where the cache lives somewhere less
     * trusted than the application itself.
     */
    public static final String PROP_VERIFY_CACHE = "chdb.verifyCache";

    private static final Object LOCK = new Object();
    private static volatile LoadedRuntime runtime;
    private static volatile RuntimeException failure;

    private NativeLibraryLoader() {
    }

    /** What was loaded and from where, for diagnostics and error messages. */
    public static final class LoadedRuntime {
        private final Platform platform;
        private final Path enginePath;
        private final Path jniPath;
        private final String source;
        private final Map<String, String> manifest;
        private final String engineVersion;
        private final int jniAbiVersion;

        LoadedRuntime(
                Platform platform,
                Path enginePath,
                Path jniPath,
                String source,
                Map<String, String> manifest,
                String engineVersion,
                int jniAbiVersion) {
            this.platform = platform;
            this.enginePath = enginePath;
            this.jniPath = jniPath;
            this.source = source;
            this.manifest = manifest;
            this.engineVersion = engineVersion;
            this.jniAbiVersion = jniAbiVersion;
        }

        public Platform platform() {
            return platform;
        }

        /** Absolute path of the loaded {@code libchdb}, or null when loaded off java.library.path. */
        public Path enginePath() {
            return enginePath;
        }

        /** Absolute path of the loaded JNI shim, or null when loaded off java.library.path. */
        public Path jniPath() {
            return jniPath;
        }

        /** How it was found: {@code "chdb.library.path"}, {@code "native-jar"} or {@code "java.library.path"}. */
        public String source() {
            return source;
        }

        /** {@code manifest.properties} from the native JAR, empty for the other two sources. */
        public Map<String, String> manifest() {
            return manifest;
        }

        /** {@code chdb_version()} of the loaded engine. */
        public String engineVersion() {
            return engineVersion;
        }

        public int jniAbiVersion() {
            return jniAbiVersion;
        }

        @Override
        public String toString() {
            return "chDB native runtime: platform="
                    + platform.id()
                    + " source="
                    + source
                    + " engine="
                    + engineVersion
                    + " jniAbi="
                    + jniAbiVersion
                    + " enginePath="
                    + enginePath
                    + " jniPath="
                    + jniPath;
        }
    }

    /**
     * Loads the native runtime if it is not loaded yet.
     *
     * <p>Idempotent, and a failure is sticky: the first attempt's diagnosis is rethrown to
     * every later caller rather than retried. Retrying is never useful here -- nothing that
     * makes loading fail (missing package, wrong platform, noexec cache, ABI mismatch) is
     * transient -- and retrying would replace a precise first error with a confusing second.
     */
    public static void ensureLoaded() {
        if (runtime != null) {
            return;
        }
        RuntimeException earlier = failure;
        if (earlier != null) {
            throw earlier;
        }
        synchronized (LOCK) {
            if (runtime != null) {
                return;
            }
            if (failure != null) {
                throw failure;
            }
            try {
                runtime = load();
            } catch (RuntimeException e) {
                failure = e;
                throw e;
            } catch (Error e) {
                // UnsatisfiedLinkError and friends. Wrapped so that the sticky-failure path
                // has something to rethrow, with the original kept as the cause.
                failure = new ChdbNativeException(e.getMessage(), e);
                throw failure;
            }
        }
    }

    /** The loaded runtime, loading it first if necessary. */
    public static LoadedRuntime loadedRuntime() {
        ensureLoaded();
        return runtime;
    }

    // ------------------------------------------------------------------ loading

    private static LoadedRuntime load() {
        Platform platform = Platform.current();
        NativeOwner.claim(platform);

        String override = trimmedProperty(PROP_LIBRARY_PATH);
        LoadedRuntime loaded;
        if (override != null) {
            loaded = loadFromDirectory(platform, Paths.get(override));
        } else {
            NativePackage pkg = NativePackage.find(platform);
            loaded = pkg != null ? loadFromPackage(platform, pkg) : loadFromJavaLibraryPath(platform);
        }

        NativeOwner.publish(loaded);

        // Opt out of chDB's signal handlers exactly once per process, here rather than in
        // ChdbConnection's constructor. The flag chdb_set_signal_handlers_enabled(0) sets is
        // process-wide and sticky, so one call is all the opt-out needs -- but each call also
        // resets the JVM's SIGSEGV/SIGBUS/SIGILL/SIGFPE handlers to SIG_DFL for the few
        // microseconds until the shim's SignalGuard puts them back, and in that window any
        // other thread taking one of those signals is killed with no handler and no hs_err
        // report. Calling it per connection paid that window twice per Connection.open()
        // instead of twice per JVM; see issue #14.
        //
        // Safe to call native methods here: verify() above already does, re-entering
        // ChdbNative's initializer on this thread (JLS 12.4.2).
        ChdbNative.protectHostSignalHandlers();
        return loaded;
    }

    private static LoadedRuntime loadFromDirectory(Platform platform, Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new ChdbNativeException(
                    "-D" + PROP_LIBRARY_PATH + "=" + dir + " is not a directory. It must be a"
                            + " directory holding both " + platform.engineLibraryName() + " and "
                            + platform.jniLibraryName() + ".");
        }
        Path engine = dir.resolve(platform.engineLibraryName());
        Path jni = dir.resolve(platform.jniLibraryName());
        for (Path required : new Path[] {engine, jni}) {
            if (!Files.isRegularFile(required)) {
                throw new ChdbNativeException(
                        "-D" + PROP_LIBRARY_PATH + "=" + dir + " does not contain " + required.getFileName()
                                + ". The directory must hold both the engine ("
                                + platform.engineLibraryName() + ") and the JNI shim ("
                                + platform.jniLibraryName() + ") for " + platform.id()
                                + "; the shim resolves the engine next to itself.");
            }
        }
        // No checksum gate on this path on purpose: there is nothing to compare against. An
        // operator pointing at a directory has taken responsibility for those bytes, and the
        // engine-version check below still catches a mismatched pair.
        return loadPair(platform, engine, jni, "chdb.library.path", new LinkedHashMap<String, String>());
    }

    private static LoadedRuntime loadFromPackage(Platform platform, NativePackage pkg) {
        Path cacheDir = unpack(platform, pkg);
        return loadPair(
                platform,
                cacheDir.resolve(platform.engineLibraryName()),
                cacheDir.resolve(platform.jniLibraryName()),
                "native-jar",
                pkg.manifest());
    }

    private static LoadedRuntime loadFromJavaLibraryPath(Platform platform) {
        // Last resort, and the only path that does not know where its libraries came from.
        // Useful for a development tree or a distribution that installs the libraries
        // system-wide; the error below is what a normal application actually hits.
        try {
            System.loadLibrary("chdb");
            System.loadLibrary("chdb_java_jni");
        } catch (UnsatisfiedLinkError e) {
            throw new ChdbNativeException(
                    "No chDB native runtime for "
                            + platform
                            + ".\n"
                            + "Tried, in order:\n"
                            + "  1. -D" + PROP_LIBRARY_PATH + " (not set)\n"
                            + "  2. a chdb-native-" + platform.id() + " JAR on the classpath (not found)\n"
                            + "  3. java.library.path (" + System.getProperty("java.library.path") + ")\n"
                            + "\nAdd the platform package for this machine:\n"
                            + "  <dependency>\n"
                            + "    <groupId>org.chdb</groupId>\n"
                            + "    <artifactId>chdb-native-" + platform.id() + "</artifactId>\n"
                            + "    <version>${chdb.version}</version>\n"
                            + "  </dependency>\n"
                            + "or point -D" + PROP_LIBRARY_PATH + " at a directory holding "
                            + platform.engineLibraryName() + " and " + platform.jniLibraryName() + ".",
                    e);
        }
        return verify(platform, null, null, "java.library.path", new LinkedHashMap<String, String>());
    }

    private static LoadedRuntime loadPair(
            Platform platform, Path engine, Path jni, String source, Map<String, String> manifest) {
        // Engine first: the shim's dependency on @rpath/libchdb.so is satisfied from the
        // global namespace once the engine is open, and dlsym(RTLD_DEFAULT) can then see the
        // optional entry points.
        loadOne(engine, "chDB engine", platform, source);
        loadOne(jni, "chDB JNI shim", platform, source);
        return verify(platform, engine, jni, source, manifest);
    }

    private static void loadOne(Path library, String what, Platform platform, String source) {
        try {
            System.load(library.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("already loaded in another classloader")) {
                throw new ChdbNativeException(NativeOwner.classLoaderConflictMessage(library), e);
            }
            throw new ChdbNativeException(
                    "Failed to load the "
                            + what
                            + " from "
                            + library
                            + " (platform "
                            + platform.id()
                            + ", source "
                            + source
                            + "): "
                            + message
                            + "\n"
                            + noexecHint(library),
                    e);
        }
    }

    private static String noexecHint(Path library) {
        return "If the path above is under a temporary directory, check that the filesystem"
                + " allows mapping executable pages: a /tmp mounted noexec, or a read-only"
                + " container filesystem, makes a correct library unloadable. Point -D"
                + PROP_CACHE_DIR
                + " at a writable directory on an exec-permitting filesystem, or unpack the"
                + " libraries yourself and use -D"
                + PROP_LIBRARY_PATH
                + ".\nIf the library was unpacked successfully before and only now fails to"
                + " load, the cached copy may have been corrupted since. Delete the directory"
                + " above, or run with -D"
                + PROP_VERIFY_CACHE
                + "=true to have the driver check it against its checksums on every start.";
    }

    /**
     * Checks that the three things that must agree, do: the shim's ABI, the engine's version
     * and the version the shim was built against.
     *
     * <p>Runs before any query, because a mismatched pair does not fail cleanly -- it fails
     * as a struct-layout disagreement somewhere inside a later call (work plan section 2.1).
     */
    private static LoadedRuntime verify(
            Platform platform, Path engine, Path jni, String source, Map<String, String> manifest) {
        int shimAbi;
        String engineVersion;
        String buildInfo;
        try {
            shimAbi = ChdbNative.jniAbiVersion();
            engineVersion = ChdbNative.engineVersion();
            buildInfo = ChdbNative.shimBuildInfo();
        } catch (UnsatisfiedLinkError e) {
            throw new ChdbNativeException(
                    "The chDB JNI shim loaded from "
                            + jni
                            + " does not export the entry points this driver needs. That means the"
                            + " shim and the chdb-jdbc JAR come from different releases. Use a"
                            + " chdb-native-"
                            + platform.id()
                            + " package with the same version as chdb-jdbc, or import org.chdb:chdb-bom"
                            + " to keep them aligned.",
                    e);
        }

        if (shimAbi != ChdbNative.JNI_ABI_VERSION) {
            throw new ChdbNativeException(
                    "chDB JNI ABI mismatch: chdb-jdbc expects ABI "
                            + ChdbNative.JNI_ABI_VERSION
                            + " but the shim at "
                            + jni
                            + " reports ABI "
                            + shimAbi
                            + ". These come from different releases; align the versions of chdb-jdbc"
                            + " and chdb-native-"
                            + platform.id()
                            + " (org.chdb:chdb-bom does this for you).");
        }

        String expectedEngine = manifest.get("engine.version");
        if (expectedEngine == null) {
            expectedEngine = parseBuildInfo(buildInfo, "shim.expected.engine.version");
        }
        if (expectedEngine != null
                && !expectedEngine.equals(engineVersion)
                && !Boolean.getBoolean(PROP_SKIP_VERSION_CHECK)) {
            throw new ChdbNativeException(
                    "chDB engine version mismatch: this binding is built and tested against engine "
                            + expectedEngine
                            + ", but the libchdb loaded from "
                            + engine
                            + " reports "
                            + engineVersion
                            + ". The C ABI is pinned per release, so a different engine is not"
                            + " supported. Use the matching chdb-native-"
                            + platform.id()
                            + " package, or set -D"
                            + PROP_SKIP_VERSION_CHECK
                            + "=true to proceed unsupported for diagnosis.");
        }

        return new LoadedRuntime(platform, engine, jni, source, manifest, engineVersion, shimAbi);
    }

    private static String parseBuildInfo(String buildInfo, String key) {
        if (buildInfo == null) {
            return null;
        }
        for (String line : buildInfo.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).equals(key)) {
                String value = line.substring(eq + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ unpacking

    /**
     * Unpacks the platform package's two libraries into a content-addressed directory and
     * returns it.
     *
     * <p>Content-addressed by the SHA-256 of the pair, so the same bytes always land at the
     * same absolute path -- which is what lets several ClassLoaders and several JVMs share
     * one copy instead of each making a private one (work plan sections 3.5 and 5.4).
     *
     * <p>Writes go to a temporary name and are then atomically renamed, and the whole
     * directory is built under an exclusive cross-process file lock. Between them, a JVM
     * starting while another is unpacking either waits or sees a complete directory; it
     * cannot see a half-written shared library.
     */
    private static Path unpack(Platform platform, NativePackage pkg) {
        Path root = cacheRoot();
        String digest = pkg.contentDigest();
        Path target = root.resolve(pkg.engineVersion() + "-" + platform.id() + "-" + digest.substring(0, 16));

        Path marker = target.resolve(".complete");
        if (Files.isRegularFile(marker)) {
            if (Boolean.getBoolean(PROP_VERIFY_CACHE)) {
                verifyCachedLibraries(platform, pkg, target);
            }
            return target;
        }

        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ChdbNativeException(
                    "Cannot create the chDB native cache directory "
                            + root
                            + ": "
                            + e
                            + "\nPoint -D"
                            + PROP_CACHE_DIR
                            + " at a writable directory, or unpack the libraries yourself and use -D"
                            + PROP_LIBRARY_PATH
                            + ".",
                    e);
        }

        Path lockFile = root.resolve(target.getFileName() + ".lock");
        try (FileChannel channel =
                        FileChannel.open(
                                lockFile,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.READ);
                FileLock lock = channel.lock()) {
            // Re-check under the lock: another JVM may have finished while we waited.
            if (Files.isRegularFile(marker)) {
                return target;
            }
            if (lock == null) {
                throw new ChdbNativeException("Could not acquire the chDB unpack lock at " + lockFile);
            }

            Files.createDirectories(target);
            extract(pkg, platform, target, platform.engineLibraryName());
            extract(pkg, platform, target, platform.jniLibraryName());
            writeManifest(pkg, target);

            // Written last, and only after both libraries have been renamed into place, so
            // its presence is what proves the directory is usable.
            Files.write(
                    marker,
                    (pkg.engineVersion() + "\n" + digest + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return target;
        } catch (IOException e) {
            throw new ChdbNativeException(
                    "Failed to unpack the chDB native libraries for "
                            + platform.id()
                            + " into "
                            + target
                            + ": "
                            + e
                            + "\nPoint -D"
                            + PROP_CACHE_DIR
                            + " at a writable directory with enough space (the engine is ~350 MB"
                            + " unpacked), or use -D"
                            + PROP_LIBRARY_PATH
                            + ".",
                    e);
        }
    }

    /** Re-checks an unpacked cache directory against the package's checksums. */
    private static void verifyCachedLibraries(Platform platform, NativePackage pkg, Path target) {
        for (String name : new String[] {platform.engineLibraryName(), platform.jniLibraryName()}) {
            String expected = pkg.sha256(name);
            if (expected == null) {
                continue;
            }
            Path file = target.resolve(name);
            try {
                String actual = sha256(file);
                if (!expected.equals(actual)) {
                    throw new ChdbNativeException(
                            "The unpacked chDB library "
                                    + file
                                    + " no longer matches the checksum its package declares"
                                    + " (expected "
                                    + expected
                                    + ", found "
                                    + actual
                                    + "). It was corrupted or altered after it was unpacked."
                                    + " Delete the directory and let the driver unpack it again.");
                }
            } catch (IOException e) {
                throw new ChdbNativeException("Cannot read " + file + " to verify it: " + e, e);
            }
        }
    }

    private static void extract(NativePackage pkg, Platform platform, Path target, String name)
            throws IOException {
        Path finalPath = target.resolve(name);
        String expected = pkg.sha256(name);

        if (Files.isRegularFile(finalPath) && expected != null && expected.equals(sha256(finalPath))) {
            return;
        }

        Path temp = Files.createTempFile(target, name + ".", ".part");
        try {
            try (InputStream in = pkg.open(name)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            if (expected != null) {
                String actual = sha256(temp);
                if (!expected.equals(actual)) {
                    throw new IOException(
                            "checksum mismatch for "
                                    + name
                                    + " unpacked from the chdb-native-"
                                    + platform.id()
                                    + " JAR: sha256sums.txt says "
                                    + expected
                                    + " but the unpacked file is "
                                    + actual
                                    + ". The JAR is corrupt or was tampered with; do not use it.");
                }
            }

            setExecutablePermissions(temp);
            // Atomic where the filesystem supports it, so a concurrent reader never observes
            // a partially written library under its real name.
            try {
                Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void setExecutablePermissions(Path path) throws IOException {
        try {
            // Owner-writable, everyone-readable-and-executable: several JVMs run by different
            // users share the cache, and a shared library needs the exec bit to be mapped.
            Set<PosixFilePermission> permissions =
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX filesystem. Both supported platforms are, so this is defensive.
        }
    }

    private static void writeManifest(NativePackage pkg, Path target) throws IOException {
        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : pkg.manifest().entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue());
        }
        try (java.io.OutputStream out =
                Files.newOutputStream(
                        target.resolve("manifest.properties"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            properties.store(out, "Unpacked by chdb-jdbc from the platform native JAR");
        }
    }

    static Path cacheRoot() {
        String configured = trimmedProperty(PROP_CACHE_DIR);
        if (configured == null) {
            configured = trimmedProperty(PROP_TMPDIR);
        }
        if (configured != null) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "/tmp"), "chdb-java");
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static String trimmedProperty(String key) {
        String value = System.getProperty(key);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /** Rethrows an {@link IOException} as unchecked, for use inside stream pipelines. */
    static UncheckedIOException unchecked(IOException e) {
        return new UncheckedIOException(e);
    }
}
