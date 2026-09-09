package org.chdb.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Which of the four supported platforms this JVM is running on.
 *
 * <p>V1 ships glibc Linux and macOS on x86_64 and aarch64 (work plan section 2.1). Anything
 * else -- Windows, musl, 32-bit, other architectures -- is reported as unsupported before
 * any library is loaded, with the detected triple in the message, because "UnsatisfiedLinkError"
 * from a missing platform package tells a user nothing about why.
 */
public final class Platform {

    /** Platform ids, matching the {@code chdb-native-*} artifact names and engine.properties keys. */
    public static final String MACOS_AARCH64 = "macos-aarch64";
    public static final String MACOS_X86_64 = "macos-x86_64";
    public static final String LINUX_X86_64_GNU = "linux-x86_64-gnu";
    public static final String LINUX_AARCH64_GNU = "linux-aarch64-gnu";

    /**
     * Every platform id V1 ships. Only used for diagnostics -- {@link #current()} answers the
     * question that matters -- but a message that can say "the package you declared is for
     * another machine" needs to know what the other machines are called.
     */
    static final String[] ALL_IDS = {MACOS_AARCH64, MACOS_X86_64, LINUX_X86_64_GNU, LINUX_AARCH64_GNU};

    private final String id;
    private final String os;
    private final String arch;
    private final String libc;
    private final String engineLibraryName;
    private final String jniLibraryName;

    private Platform(
            String id, String os, String arch, String libc, String engineLibraryName, String jniLibraryName) {
        this.id = id;
        this.os = os;
        this.arch = arch;
        this.libc = libc;
        this.engineLibraryName = engineLibraryName;
        this.jniLibraryName = jniLibraryName;
    }

    /** Platform id, e.g. {@code "linux-x86_64-gnu"}. */
    public String id() {
        return id;
    }

    /** Normalized OS name: {@code "linux"} or {@code "macos"}. */
    public String os() {
        return os;
    }

    /** Normalized CPU name: {@code "x86_64"} or {@code "aarch64"}. */
    public String arch() {
        return arch;
    }

    /** {@code "gnu"} on Linux, {@code "system"} on macOS. */
    public String libc() {
        return libc;
    }

    /**
     * File name of the chDB engine library on this platform.
     *
     * <p>{@code libchdb.so} on macOS too, not {@code libchdb.dylib}: that is the name chDB
     * Core builds and ships in its macOS release tarball, and it is a valid Mach-O library
     * under that name. Renaming it would mean rewriting the {@code @rpath/libchdb.so}
     * install name the shim links against, for no gain -- {@code System.load} takes a path,
     * not a library name, so the extension is not load-bearing.
     */
    public String engineLibraryName() {
        return engineLibraryName;
    }

    /** File name of the JNI shim on this platform. */
    public String jniLibraryName() {
        return jniLibraryName;
    }

    /** Resource path prefix inside a {@code chdb-native-*} JAR (work plan section 4.2). */
    public String resourcePrefix() {
        return "META-INF/chdb/native/" + os + "/" + arch;
    }

    /**
     * The same prefix, for a platform this JVM is not running on.
     *
     * <p>There is no {@code Platform} instance for another machine, on purpose: one exists only
     * for the detected triple, so nothing can accidentally load a package for the wrong
     * architecture. Diagnostics still need to look for those packages on the classpath, which
     * is what this is for.
     *
     * @throws IllegalArgumentException if the id is not one of {@link #ALL_IDS}
     */
    static String resourcePrefixFor(String platformId) {
        switch (platformId) {
            case MACOS_AARCH64:
                return "META-INF/chdb/native/macos/aarch64";
            case MACOS_X86_64:
                return "META-INF/chdb/native/macos/x86_64";
            case LINUX_X86_64_GNU:
                return "META-INF/chdb/native/linux/x86_64";
            case LINUX_AARCH64_GNU:
                return "META-INF/chdb/native/linux/aarch64";
            default:
                throw new IllegalArgumentException("not a V1 platform id: " + platformId);
        }
    }

    @Override
    public String toString() {
        return id + " (os=" + os + " arch=" + arch + " libc=" + libc + ")";
    }

    // ------------------------------------------------------------------ detection

    private static final class Holder {
        // Detected once. The answer cannot change while the process runs, and the musl probe
        // below reads files, which is not something to repeat per connection.
        static final Object RESULT = detect();
    }

    /**
     * The current platform.
     *
     * @throws UnsupportedPlatformException if this OS, CPU or libc has no V1 native package
     */
    public static Platform current() {
        Object result = Holder.RESULT;
        if (result instanceof Platform) {
            return (Platform) result;
        }
        // Rethrown rather than cached-and-thrown-once, so every caller gets the diagnosis
        // instead of only the first one.
        throw new UnsupportedPlatformException(((UnsupportedPlatformException) result).getMessage());
    }

    private static Object detect() {
        String rawOs = System.getProperty("os.name", "");
        String rawArch = System.getProperty("os.arch", "");
        String os = normalizeOs(rawOs);
        String arch = normalizeArch(rawArch);

        if (os == null || arch == null) {
            return new UnsupportedPlatformException(unsupportedMessage(rawOs, rawArch, null));
        }

        if ("linux".equals(os)) {
            if (isMusl()) {
                return new UnsupportedPlatformException(
                        "chDB has no V1 native package for musl libc (detected os.name=\""
                                + rawOs
                                + "\" os.arch=\""
                                + rawArch
                                + "\", musl). V1 ships glibc Linux packages only; Alpine and other"
                                + " musl distributions are out of scope. Options: run on a glibc"
                                + " image (for example a -slim Debian or Ubuntu base), or build"
                                + " libchdb and the JNI shim for musl yourself and point"
                                + " -Dchdb.library.path at the directory holding both.");
            }
            return "x86_64".equals(arch)
                    ? new Platform(LINUX_X86_64_GNU, os, arch, "gnu", "libchdb.so", "libchdb_java_jni.so")
                    : new Platform(LINUX_AARCH64_GNU, os, arch, "gnu", "libchdb.so", "libchdb_java_jni.so");
        }

        return "x86_64".equals(arch)
                ? new Platform(MACOS_X86_64, os, arch, "system", "libchdb.so", "libchdb_java_jni.dylib")
                : new Platform(MACOS_AARCH64, os, arch, "system", "libchdb.so", "libchdb_java_jni.dylib");
    }

    private static String unsupportedMessage(String rawOs, String rawArch, String libc) {
        return "chDB has no V1 native package for this platform (os.name=\""
                + rawOs
                + "\" os.arch=\""
                + rawArch
                + "\""
                + (libc == null ? "" : " libc=" + libc)
                + "). V1 supports Linux x86_64/aarch64 with glibc and macOS x86_64/arm64."
                + " Windows, musl Linux and 32-bit platforms are out of scope for V1.";
    }

    /** Maps {@code os.name} onto {@code linux}/{@code macos}, or null if unsupported. */
    static String normalizeOs(String rawOs) {
        String value = rawOs.toLowerCase(Locale.ROOT);
        if (value.startsWith("linux")) {
            return "linux";
        }
        if (value.startsWith("mac") || value.startsWith("darwin") || value.startsWith("osx")) {
            return "macos";
        }
        return null;
    }

    /**
     * Maps {@code os.arch} onto {@code x86_64}/{@code aarch64}, or null if unsupported.
     *
     * <p>The same CPU has several names across JVMs and OSes: HotSpot reports {@code amd64}
     * on Linux and {@code x86_64} on macOS, and {@code aarch64} on Linux against {@code
     * aarch64} on macOS, while some JVMs say {@code arm64}. Normalizing here keeps the rest
     * of the loader from having to know that.
     */
    static String normalizeArch(String rawArch) {
        String value = rawArch.toLowerCase(Locale.ROOT);
        switch (value) {
            case "x86_64":
            case "x86-64":
            case "amd64":
            case "em64t":
                return "x86_64";
            case "aarch64":
            case "arm64":
            case "aarch64_be":
                return "aarch64";
            default:
                return null;
        }
    }

    /**
     * Whether this Linux uses musl rather than glibc.
     *
     * <p>Two probes, cheapest first. {@code /proc/self/maps} names every mapped library, so
     * a {@code ld-musl} or {@code libc.musl} mapping is conclusive and costs one read of a
     * pseudo-file. If that is unavailable -- a hardened container can hide it -- fall back
     * to asking {@code ldd}, whose musl build prints "musl libc" on stderr.
     *
     * <p>Neither probe firing is read as glibc. That is the right default: a false "musl"
     * would refuse to run on a supported platform, while a false "glibc" surfaces later as
     * a link error that names the missing symbol.
     */
    static boolean isMusl() {
        Path maps = Paths.get("/proc/self/maps");
        if (Files.isReadable(maps)) {
            try {
                for (String line : Files.readAllLines(maps, StandardCharsets.ISO_8859_1)) {
                    if (line.contains("ld-musl") || line.contains("libc.musl")) {
                        return true;
                    }
                }
                return false;
            } catch (IOException | RuntimeException ignored) {
                // Fall through to the ldd probe.
            }
        }
        return isMuslViaLdd();
    }

    private static boolean isMuslViaLdd() {
        Process process = null;
        try {
            process = new ProcessBuilder("ldd", "--version").redirectErrorStream(true).start();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase(Locale.ROOT).contains("musl")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
