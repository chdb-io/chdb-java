package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The one-runtime-per-JVM rule, exercised (work plan section 3.5 and 5.10).
 *
 * <p>A JVM holds one copy of a JNI library and it belongs to the ClassLoader that loaded it.
 * The driver's documentation and its error message both describe what follows; until now
 * nothing had run it. Both halves are asserted here: children sharing a parent's runtime work,
 * and two isolated loaders fail with a diagnosis rather than a bare {@code UnsatisfiedLinkError}
 * or a crash.
 *
 * <p>Every case forks a JVM. The driver is already loaded in the test JVM by the surefire
 * classpath, so a second loader here could never be the first — and the rule is about which
 * loader gets there first.
 */
class ClassLoaderIT extends NativeTestBase {

    /** The classpath entries holding the driver, separated from everything else. */
    private static List<URL> driverClasspath() throws IOException {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            // chdb-jdbc's classes or jar; nothing else has to be isolated for this.
            if (entry.contains("chdb-jdbc")) {
                urls.add(Paths.get(entry).toUri().toURL());
            }
        }
        return urls;
    }

    private static ForkResult fork(String mainClass, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dchdb.library.path=" + System.getProperty("chdb.library.path"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(Arrays.asList(args));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
        assertTrue(process.waitFor(3, TimeUnit.MINUTES), "the forked JVM did not exit");
        return new ForkResult(process.exitValue(), output);
    }

    private static final class ForkResult {
        final int exitCode;
        final String output;

        ForkResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * A loader that refuses to delegate the driver's classes to its parent, so each instance
     * defines its own copy — a webapp or plugin ClassLoader, in miniature.
     */
    public static final class IsolatingLoader extends URLClassLoader {
        IsolatingLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.chdb.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> already = findLoadedClass(name);
                    if (already == null) {
                        already = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(already);
                    }
                    return already;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    /** Loads the driver in two isolated loaders and reports what the second one got. */
    public static final class TwoIsolatedLoaders {
        public static void main(String[] args) throws Exception {
            List<URL> urls = new ArrayList<>();
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                if (entry.contains("chdb-jdbc")) {
                    urls.add(Paths.get(entry).toUri().toURL());
                }
            }
            URL[] array = urls.toArray(new URL[0]);
            // Parent is the platform loader, so nothing chdb comes from above.
            ClassLoader parent = ClassLoader.getPlatformClassLoader();

            System.out.println("first loader:");
            System.out.println("  " + connectIn(new IsolatingLoader(array, parent)));
            System.out.println("second loader:");
            System.out.println("  " + connectIn(new IsolatingLoader(array, parent)));
        }

        private static String connectIn(ClassLoader loader) {
            try {
                Class<?> driver = loader.loadClass("org.chdb.jdbc.ChdbDriver");
                // Not DriverManager: it would find the driver already registered by another
                // loader and defeat the isolation this is testing.
                Object instance = driver.getDeclaredConstructor().newInstance();
                Method connect = driver.getMethod("connect", String.class, java.util.Properties.class);
                Object connection = connect.invoke(instance, "jdbc:chdb::memory:", null);
                return connection != null ? "CONNECTED" : "returned null";
            } catch (Throwable t) {
                // The whole chain, not just the root. The driver's diagnosis is the wrapper's
                // message and the UnsatisfiedLinkError is its cause, so unwrapping to the root
                // throws away the part worth reading -- which is also what a logger configured
                // to print only the root cause would do.
                StringBuilder out = new StringBuilder("FAILED");
                for (Throwable e = t; e != null; e = e.getCause() == e ? null : e.getCause()) {
                    out.append(' ').append(e.getClass().getSimpleName()).append(": ")
                            .append(String.valueOf(e.getMessage()).replace("\n", "\n  "));
                }
                return out.toString();
            }
        }
    }

    @Test
    @DisplayName("a second isolated ClassLoader fails with a diagnosis, and the JVM survives")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void twoIsolatedLoadersFailFast() throws Exception {
        ForkResult result = fork(TwoIsolatedLoaders.class.getName());

        // Surviving is half the assertion: the failure mode being guarded against is a JVM that
        // dies, or one that maps a second 350 MB engine.
        assertEquals(0, result.exitCode, "the JVM must survive the refusal:\n" + result.output);
        assertTrue(result.output.contains("first loader:"), result.output);

        int split = result.output.indexOf("second loader:");
        assertTrue(split > 0, result.output);
        String first = result.output.substring(0, split);
        String second = result.output.substring(split);
        assertTrue(first.contains("CONNECTED"), "the first loader should succeed:\n" + first);

        // The second must be refused, and the message has to be the one the driver writes --
        // naming the owner and what to do -- rather than the JVM's bare UnsatisfiedLinkError.
        assertTrue(second.contains("FAILED"), "the second loader should be refused:\n" + second);
        assertTrue(
                second.contains("already loaded in a different ClassLoader"),
                "the refusal should name the cause in the driver's own words:\n" + second);
        assertTrue(
                second.contains("Already loaded by:"),
                "the refusal should name the owner:\n" + second);
        assertTrue(
                second.contains("parent of every user of chDB"),
                "the refusal should say how to fix it:\n" + second);
        assertTrue(
                second.contains("Tomcat") && second.contains("Spark") && second.contains("Flink"),
                "the refusal should name the containers this happens in:\n" + second);
    }

    /** Two children of one parent, both reaching the driver through delegation. */
    public static final class TwoChildrenOneParent {
        public static void main(String[] args) throws Exception {
            List<URL> urls = new ArrayList<>();
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                if (entry.contains("chdb-jdbc")) {
                    urls.add(Paths.get(entry).toUri().toURL());
                }
            }
            // The parent holds the driver; the children hold nothing and delegate upward, which
            // is the supported deployment: chdb-jdbc in Tomcat's lib/, Spark's driver classpath
            // or Flink's lib/, and nothing in the applications.
            ClassLoader parent =
                    new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
            URLClassLoader childA = new URLClassLoader(new URL[0], parent);
            URLClassLoader childB = new URLClassLoader(new URL[0], parent);

            System.out.println("childA: " + queryIn(childA));
            System.out.println("childB: " + queryIn(childB));
            System.out.println(
                    "same class from both children: "
                            + (childA.loadClass("org.chdb.jdbc.ChdbDriver")
                                    == childB.loadClass("org.chdb.jdbc.ChdbDriver")));
        }

        private static String queryIn(ClassLoader loader) {
            try {
                Class<?> driver = loader.loadClass("org.chdb.jdbc.ChdbDriver");
                Object instance = driver.getDeclaredConstructor().newInstance();
                Method connect = driver.getMethod("connect", String.class, java.util.Properties.class);
                Object connection = connect.invoke(instance, "jdbc:chdb::memory:", null);
                Method createStatement = connection.getClass().getMethod("createStatement");
                createStatement.setAccessible(true);
                Object statement = createStatement.invoke(connection);
                Method executeQuery = statement.getClass().getMethod("executeQuery", String.class);
                executeQuery.setAccessible(true);
                Object rs = executeQuery.invoke(statement, "SELECT 1");
                Method next = rs.getClass().getMethod("next");
                next.setAccessible(true);
                Object hasRow = next.invoke(rs);
                Method closeRs = rs.getClass().getMethod("close");
                closeRs.setAccessible(true);
                closeRs.invoke(rs);
                Method closeConn = connection.getClass().getMethod("close");
                closeConn.setAccessible(true);
                closeConn.invoke(connection);
                return "queried, hasRow=" + hasRow;
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                return "FAILED " + root.getClass().getSimpleName() + ": " + root.getMessage();
            }
        }
    }

    @Test
    @DisplayName("two child ClassLoaders share one runtime through their common parent")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void twoChildrenShareTheParentsRuntime() throws Exception {
        ForkResult result = fork(TwoChildrenOneParent.class.getName());

        assertEquals(0, result.exitCode, result.output);
        assertTrue(
                result.output.contains("childA: queried, hasRow=true"),
                "the first child should work:\n" + result.output);
        assertTrue(
                result.output.contains("childB: queried, hasRow=true"),
                "the second child should work through the same runtime:\n" + result.output);
        assertTrue(
                result.output.contains("same class from both children: true"),
                "both children must resolve to the parent's class, or they are not sharing:\n"
                        + result.output);
    }

    @Test
    @DisplayName("no second copy of the engine is unpacked when a loader is refused")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void refusalDoesNotUnpackASecondEngine() throws Exception {
        // The tempting workaround for the ClassLoader limit is to copy the library to a fresh
        // path per loader. At ~350 MB per copy that is what the driver must not do, so the
        // count of unpacked directories has to be unchanged by a refusal.
        Path cacheRoot = Paths.get(System.getProperty("java.io.tmpdir"), "chdb-java");
        long before = countDirectories(cacheRoot);
        ForkResult result = fork(TwoIsolatedLoaders.class.getName());
        assertEquals(0, result.exitCode, result.output);
        long after = countDirectories(cacheRoot);
        assertEquals(before, after, "a refused loader must not leave a second unpacked engine");
    }

    private static long countDirectories(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory).collect(Collectors.toList()).size();
        }
    }
}
