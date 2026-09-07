package org.chdb.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The {@code chdb-native-<platform>} JAR on the classpath, if there is one.
 *
 * <p>Reads the layout from work plan section 4.2:
 *
 * <pre>
 * META-INF/chdb/native/&lt;os&gt;/&lt;arch&gt;/libchdb.so
 * META-INF/chdb/native/&lt;os&gt;/&lt;arch&gt;/libchdb_java_jni.{so,dylib}
 * META-INF/chdb/native/&lt;os&gt;/&lt;arch&gt;/manifest.properties
 * META-INF/chdb/native/&lt;os&gt;/&lt;arch&gt;/sha256sums.txt
 * </pre>
 *
 * <p>Resources are read through {@code NativePackage.class.getClassLoader()} rather than the
 * thread context ClassLoader. The libraries must come from the same ClassLoader that defines
 * {@link ChdbNative}, because that is the loader the JVM will consider their owner (work plan
 * section 3.5); resolving them through a context loader could unpack a package the owner
 * cannot see.
 */
final class NativePackage {

    private final Platform platform;
    private final Map<String, String> manifest;
    private final Map<String, String> checksums;

    private NativePackage(Platform platform, Map<String, String> manifest, Map<String, String> checksums) {
        this.platform = platform;
        this.manifest = Collections.unmodifiableMap(manifest);
        this.checksums = Collections.unmodifiableMap(checksums);
    }

    /** The platform package on the classpath, or null if none is present. */
    static NativePackage find(Platform platform) {
        String manifestResource = platform.resourcePrefix() + "/manifest.properties";
        URL manifestUrl = loader().getResource(manifestResource);
        if (manifestUrl == null) {
            return null;
        }

        Map<String, String> manifest = new LinkedHashMap<>();
        try (InputStream in = manifestUrl.openStream()) {
            Properties properties = new Properties();
            properties.load(in);
            for (String name : properties.stringPropertyNames()) {
                manifest.put(name, properties.getProperty(name));
            }
        } catch (IOException e) {
            throw new ChdbNativeException(
                    "Found a chDB platform package at " + manifestUrl + " but could not read its"
                            + " manifest.properties: " + e,
                    e);
        }

        Map<String, String> checksums = readChecksums(platform);
        return new NativePackage(platform, manifest, checksums);
    }

    /**
     * Parses {@code sha256sums.txt}, which is in {@code sha256sum(1)} format: a hex digest,
     * whitespace, then the file name.
     */
    private static Map<String, String> readChecksums(Platform platform) {
        Map<String, String> checksums = new LinkedHashMap<>();
        String resource = platform.resourcePrefix() + "/sha256sums.txt";
        URL url = loader().getResource(resource);
        if (url == null) {
            throw new ChdbNativeException(
                    "The chDB platform package for " + platform.id() + " has no " + resource
                            + ". Every published native package carries checksums for the libraries"
                            + " it ships; a package without them cannot be verified and is not used.");
        }
        try (InputStream in = url.openStream()) {
            String text = readAll(in);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // Split on the first run of whitespace. sha256sum writes "<digest>  <name>";
                // the second space is a binary-mode marker, not part of the name.
                int split = indexOfWhitespace(trimmed);
                if (split < 0) {
                    continue;
                }
                String digest = trimmed.substring(0, split);
                String name = trimmed.substring(split).trim();
                if (name.startsWith("*")) {
                    name = name.substring(1);
                }
                // Names may be written with a path prefix; only the file name is meaningful here.
                int slash = name.lastIndexOf('/');
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                checksums.put(name, digest.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (IOException e) {
            throw new ChdbNativeException("Could not read " + resource + ": " + e, e);
        }
        return checksums;
    }

    private static int indexOfWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String readAll(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static ClassLoader loader() {
        ClassLoader classLoader = NativePackage.class.getClassLoader();
        // Null means the bootstrap loader, which happens when the driver is on the boot
        // classpath; the system loader is where its resources would be in that case.
        return classLoader == null ? ClassLoader.getSystemClassLoader() : classLoader;
    }

    Map<String, String> manifest() {
        return manifest;
    }

    /** Expected SHA-256 of one shipped file, or null if the package does not list it. */
    String sha256(String fileName) {
        return checksums.get(fileName);
    }

    String engineVersion() {
        String version = manifest.get("engine.version");
        if (version == null) {
            throw new ChdbNativeException(
                    "The chDB platform package for " + platform.id() + " has no engine.version in"
                            + " manifest.properties, so the driver cannot check that the engine it"
                            + " loads is the one this binding was tested against.");
        }
        return version;
    }

    /**
     * A digest identifying this exact pair of libraries, used as the unpack directory name.
     *
     * <p>Derived from the package's recorded checksums rather than by hashing ~350 MB of
     * library on every JVM start. That is sound because the recorded checksums are what the
     * unpacked files are verified against: if the JAR's bytes change, the recorded checksums
     * change with them, and the directory name changes too.
     */
    String contentDigest() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform", e);
        }
        digest.update(engineVersion().getBytes(StandardCharsets.UTF_8));
        for (String name : new String[] {platform.engineLibraryName(), platform.jniLibraryName()}) {
            String sum = checksums.get(name);
            if (sum == null) {
                throw new ChdbNativeException(
                        "The chDB platform package for " + platform.id() + " ships " + name
                                + " without a checksum in sha256sums.txt. Unverifiable native"
                                + " libraries are not loaded.");
            }
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update(sum.getBytes(StandardCharsets.UTF_8));
        }
        return NativeLibraryLoader.toHex(digest.digest());
    }

    /** Opens one shipped file for reading. */
    InputStream open(String fileName) throws IOException {
        String resource = platform.resourcePrefix() + "/" + fileName;
        InputStream in = loader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException(
                    "the chdb-native-" + platform.id() + " JAR does not contain " + resource);
        }
        return in;
    }
}
