package org.chdb.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records which ClassLoader owns this JVM's one copy of the chDB native runtime.
 *
 * <p>A JNI library belongs to the ClassLoader that loaded it. A second, isolated ClassLoader
 * calling {@code System.load} on the same file gets {@code UnsatisfiedLinkError: ... already
 * loaded in another classloader} -- correct, but useless as a diagnosis, and the usual
 * reaction (copy the library to a fresh path so each loader gets its own) would put a second
 * 350 MB engine in memory and is explicitly out of scope for V1 (work plan section 3.5).
 *
 * <p>So the owner is published where a later loader can find it, and the later loader gets
 * told what to do instead. The marker is deliberately made of strings only: it is read across
 * a ClassLoader boundary, and a marker holding a {@code Class} or a {@code ClassLoader}
 * reference would both fail to load in the reader and pin the writer's loader in memory.
 *
 * <p>This is a diagnostic, not a safety mechanism. The JNI and OS loaders remain the real
 * boundary; all this does is turn their error into an actionable one.
 */
final class NativeOwner {

    /**
     * System property the marker is published under.
     *
     * <p>System properties are the only store reachable from every ClassLoader in a JVM
     * without either loader being able to see the other's classes.
     */
    private static final String MARKER_PROPERTY = "org.chdb.native.owner";

    private NativeOwner() {
    }

    /**
     * Notes that this ClassLoader is about to load the native runtime.
     *
     * <p>Called before loading, so that a conflict is visible from both sides: the incumbent
     * is already published when the second loader arrives.
     */
    static void claim(Platform platform) {
        String existing = System.getProperty(MARKER_PROPERTY);
        if (existing == null) {
            return;
        }
        Map<String, String> marker = parse(existing);
        String owner = marker.get("owner");
        if (owner != null && owner.equals(describeLoader())) {
            return;  // Same loader, second call. ensureLoaded() handles the idempotence.
        }
        // Not an error yet: the incumbent may be a parent loader whose classes this loader
        // can see through delegation, in which case this code is not even running. Reaching
        // here from a different loader means delegation did not happen, and System.load will
        // fail -- with the message classLoaderConflictMessage() supplies.
        if (platform != null && !platform.id().equals(marker.get("platform"))) {
            throw new ChdbNativeException(
                    "The chDB native runtime in this JVM was loaded for platform "
                            + marker.get("platform")
                            + " but this ClassLoader detects "
                            + platform.id()
                            + ". A single JVM cannot host two platform packages.");
        }
    }

    /** Publishes what was loaded, for a later loader to find. */
    static void publish(NativeLibraryLoader.LoadedRuntime runtime) {
        Map<String, String> marker = new LinkedHashMap<>();
        marker.put("owner", describeLoader());
        marker.put("platform", runtime.platform().id());
        marker.put("engine.version", runtime.engineVersion());
        marker.put("jni.abi.version", Integer.toString(runtime.jniAbiVersion()));
        marker.put("source", runtime.source());
        marker.put("engine.path", String.valueOf(runtime.enginePath()));
        marker.put("jni.path", String.valueOf(runtime.jniPath()));
        System.setProperty(MARKER_PROPERTY, format(marker));
    }

    /** What was already loaded, or an empty map if nothing has been. */
    static Map<String, String> incumbent() {
        String existing = System.getProperty(MARKER_PROPERTY);
        return existing == null ? Collections.<String, String>emptyMap() : parse(existing);
    }

    /**
     * The message for "this library is already loaded in another ClassLoader": what happened,
     * who has it, and the two ways out.
     */
    static String classLoaderConflictMessage(java.nio.file.Path library) {
        Map<String, String> marker = incumbent();
        StringBuilder message = new StringBuilder();
        message.append("The chDB native runtime is already loaded in a different ClassLoader, so this")
                .append(" ClassLoader cannot load ")
                .append(library)
                .append(" as well. A JVM holds one copy of a JNI library and it belongs to whichever")
                .append(" ClassLoader loaded it.\n\n");

        message.append("Already loaded by:\n");
        if (marker.isEmpty()) {
            message.append("  (no chDB owner marker found -- the incumbent copy was loaded by")
                    .append(" something other than this driver)\n");
        } else {
            for (Map.Entry<String, String> entry : marker.entrySet()) {
                message.append("  ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
            }
        }
        message.append("Loading from:\n  ").append(describeLoader()).append('\n');

        message.append("\nFix it one of these ways:\n")
                .append("  1. Move chdb-jdbc and the chdb-native-* package to a ClassLoader that is a")
                .append(" parent of every user of chDB, and remove them from the child ClassLoaders.")
                .append(" Parent delegation then gives every child the one runtime.\n")
                .append("       Tomcat: $CATALINA_HOME/lib rather than WEB-INF/lib\n")
                .append("       Spark:  --driver-class-path / spark.executor.extraClassPath, not --jars\n")
                .append("       Flink:  lib/ rather than the job JAR\n")
                .append("  2. Run the second application in its own JVM.\n")
                .append("\nchDB V1 does not support loading a second copy of the engine into an isolated")
                .append(" ClassLoader: the engine is ~350 MB and copying it per loader would multiply")
                .append(" that, so the driver refuses rather than doing it silently.");
        return message.toString();
    }

    /**
     * A stable, string-only description of the ClassLoader running this code.
     *
     * <p>Identity hash rather than {@code toString()} alone: two child loaders of the same
     * container class often print identically, and telling them apart is the whole point.
     */
    private static String describeLoader() {
        ClassLoader classLoader = NativeOwner.class.getClassLoader();
        if (classLoader == null) {
            return "bootstrap";
        }
        return classLoader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(classLoader));
    }

    // The marker is one property value, so it is encoded as key=value pairs joined by "|".
    // Neither keys nor values can contain "|": they are loader descriptions, platform ids,
    // versions and filesystem paths.

    private static String format(Map<String, String> marker) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : marker.entrySet()) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    private static Map<String, String> parse(String value) {
        Map<String, String> marker = new LinkedHashMap<>();
        for (String part : value.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                marker.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return marker;
    }
}
