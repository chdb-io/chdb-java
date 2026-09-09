package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.chdb.internal.ChdbNativeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every SQLSTATE this driver throws, against the {@link SQLException} subclass JDBC 4 defines
 * for its class.
 *
 * <h2>Why this exists</h2>
 * The driver shipped SQLSTATE {@code 08003} on a plain {@link java.sql.SQLNonTransientException}
 * for a whole release cycle, and nothing noticed, because the tests all asserted the SQLSTATE
 * string and none asserted the type. A SQLSTATE and a type that disagree are worse than either
 * being wrong alone: a caller that reads the string and a caller that catches the subclass get
 * different answers from the same failure.
 *
 * <p>So there are two halves here. The first pins the types the exception factory produces,
 * directly. The second is a rule over the whole driver's source, which is the only half that
 * can see a <em>new</em> throw site — the point being that adding an error code should force
 * the question rather than allow it to be skipped.
 */
class SqlStateTypeTest {

    /**
     * SQLSTATE class to the subclass JDBC 4 names for it, from each subclass's own javadoc
     * ("thrown when the SQLState class value is ...").
     *
     * <p>Classes absent from this map have no JDBC subtype, so a plain {@code SQLException} or
     * a category type such as {@code SQLTransientException} is the most that can be said. The
     * rule below only checks the classes that are here.
     */
    private static final Map<String, Class<? extends SQLException>> BY_CLASS = new TreeMap<>();

    static {
        BY_CLASS.put("08", SQLNonTransientConnectionException.class);
        BY_CLASS.put("0A", SQLFeatureNotSupportedException.class);
        BY_CLASS.put("22", SQLDataException.class);
        BY_CLASS.put("42", SQLSyntaxErrorException.class);
    }

    // ---------------------------------------------------------------- the factory

    @Test
    @DisplayName("a statement refused because the shutdown hook owns the connection")
    void shuttingDownIsAConnectionException() {
        SQLException e = ChdbExceptions.shuttingDown();
        assertEquals("08003", e.getSQLState());
        assertTrue(
                e instanceof SQLNonTransientConnectionException,
                "SQLSTATE class 08 is a connection exception, and callers catch the subclass"
                        + " rather than reading the string; got " + e.getClass().getName());

        // The behaviour the type is for, written the way a caller writes it. Compiles only
        // because the declared type is right, and passes only because the thrown one is.
        try {
            throw ChdbExceptions.shuttingDown();
        } catch (SQLNonTransientConnectionException caught) {
            assertEquals("08003", caught.getSQLState());
        } catch (SQLException other) {
            fail("a caller catching SQLNonTransientConnectionException missed it: "
                    + other.getClass().getName());
        }
    }

    @Test
    @DisplayName("the platform-detection failure on the connect path")
    void unsupportedPlatformIsAConnectionException() {
        SQLException e =
                ChdbExceptions.wrap(
                        "Cannot connect",
                        new org.chdb.internal.UnsupportedPlatformException("no such platform"));
        assertEquals("08001", e.getSQLState());
        assertTrue(
                e instanceof SQLNonTransientConnectionException,
                "issue #7's path is class 08 too; got " + e.getClass().getName());
    }

    @Test
    @DisplayName("an unimplemented JDBC method")
    void notSupportedIsFeatureNotSupported() {
        SQLException e = ChdbExceptions.notSupported("Savepoints");
        assertEquals("0A000", e.getSQLState());
        assertTrue(e instanceof SQLFeatureNotSupportedException, e.getClass().getName());
    }

    @Test
    @DisplayName("a method called on a closed object")
    void closedIsNonTransient() {
        SQLException e = ChdbExceptions.closed("Connection");
        assertEquals("HY010", e.getSQLState());
        // No JDBC subtype for the HY vendor space, so SQLNonTransientException is the most
        // that can be claimed. Pinned so that it stays a deliberate choice.
        assertTrue(e instanceof java.sql.SQLNonTransientException, e.getClass().getName());
    }

    @Test
    @DisplayName("each engine error code the driver maps by hand")
    void engineErrorCodesMapToTheirTypes() {
        assertEngine(62, "42000", SQLSyntaxErrorException.class);
        assertEngine(47, "42000", SQLSyntaxErrorException.class);
        assertEngine(60, "42000", SQLSyntaxErrorException.class);
        assertEngine(81, "42000", SQLSyntaxErrorException.class);
        assertEngine(46, "42000", SQLSyntaxErrorException.class);
        assertEngine(53, "42804", SQLSyntaxErrorException.class);
        assertEngine(43, "42804", SQLSyntaxErrorException.class);
        assertEngine(159, "57014", SQLTimeoutException.class);
        assertEngine(241, "53200", java.sql.SQLTransientException.class);
        assertEngine(48, "0A000", SQLFeatureNotSupportedException.class);

        // 57014 is the one state thrown as two types, by error code rather than by state: a
        // timeout the driver armed is a SQLTimeoutException, and a cancel whose origin the
        // engine cannot report is not, because claiming "timeout" would tell a caller to retry
        // with a longer deadline. See ChdbExceptions.wrap.
        SQLException cancelled = engine(394);
        assertEquals("57014", cancelled.getSQLState());
        assertEquals(
                SQLException.class,
                cancelled.getClass(),
                "an engine-reported cancel must not claim to be a timeout");

        // An unrecognised code deliberately carries no SQLSTATE at all rather than a guessed
        // one, which is why the rule below has nothing to check for it.
        SQLException unknown = engine(999999);
        assertEquals(null, unknown.getSQLState());
        assertEquals(SQLException.class, unknown.getClass());
    }

    private static SQLException engine(int code) {
        return ChdbExceptions.wrap(
                "ctx", new ChdbNativeException("Code: " + code + ". DB::Exception: something"));
    }

    private static void assertEngine(int code, String state, Class<?> type) {
        SQLException e = engine(code);
        assertEquals(state, e.getSQLState(), "code " + code);
        assertEquals(code, e.getErrorCode(), "code " + code);
        assertTrue(
                type.isInstance(e),
                "engine code " + code + " (" + state + ") should be a " + type.getSimpleName()
                        + ", was " + e.getClass().getName());
    }

    // ---------------------------------------------------------------- the rule

    private static final Pattern CONSTRUCTION = Pattern.compile("new\\s+(SQL[A-Za-z]*Exception)\\s*\\(");
    private static final Pattern SQLSTATE = Pattern.compile("\"([0-9A-Z]{5})\"");

    @Test
    @DisplayName("every throw site in the driver agrees with its SQLSTATE class")
    void everyThrowSiteAgreesWithItsSqlState() throws IOException {
        Path root = mainSourceRoot();
        assertTrue(
                Files.isDirectory(root),
                "could not find the driver's sources to check; looked for " + root);

        List<String> wrong = new ArrayList<>();
        Map<String, TreeSet<String>> seen = new TreeMap<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(SqlStateTypeTest::isJava)::iterator) {
                String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = CONSTRUCTION.matcher(src);
                while (m.find()) {
                    String args = arguments(src, m.end() - 1);
                    Matcher s = SQLSTATE.matcher(args);
                    if (!s.find()) {
                        continue;
                    }
                    String state = s.group(1);
                    String thrown = m.group(1);
                    checked++;
                    if (!seen.containsKey(state)) {
                        seen.put(state, new TreeSet<String>());
                    }
                    seen.get(state).add(thrown);
                    Class<? extends SQLException> want = BY_CLASS.get(state.substring(0, 2));
                    if (want != null && !want.getSimpleName().equals(thrown)) {
                        wrong.add(
                                file.getFileName() + " line "
                                        + (1 + countNewlines(src, m.start()))
                                        + ": " + state + " thrown as " + thrown
                                        + ", but SQLSTATE class " + state.substring(0, 2)
                                        + " is " + want.getSimpleName());
                    }
                }
            }
        }

        // A sanity floor, so a refactor that moves every throw out of this tree cannot make
        // the rule vacuously true.
        assertTrue(checked > 40, "only found " + checked + " throw sites carrying a SQLSTATE");
        assertEquals(
                "",
                String.join("\n", wrong),
                "these throw sites disagree with the subclass JDBC 4 defines for their SQLSTATE"
                        + " class. Either use that subclass, or -- if it would mislead the"
                        + " caller -- pick a different SQLSTATE and say why in a comment.\n");

        // Printed rather than asserted: useful when adding an error code, and the exact set
        // changes with every new one, so pinning it here would only create churn.
        System.out.println("SQLSTATEs the driver throws, and as what:");
        for (Map.Entry<String, TreeSet<String>> e : seen.entrySet()) {
            System.out.println("  " + e.getKey() + "  " + String.join(" and ", e.getValue()));
        }
    }

    private static boolean isJava(Path p) {
        return p.getFileName().toString().endsWith(".java");
    }

    /** The balanced-paren argument list starting at {@code open}. */
    private static String arguments(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        return src.substring(open);
    }

    private static int countNewlines(String src, int end) {
        int n = 0;
        for (int i = 0; i < end; i++) {
            if (src.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /**
     * The driver's main sources, found by walking up rather than from a system property.
     *
     * <p>Surefire runs with the module directory as the working directory, but the sanitizer
     * and oldest-platform jobs drive JUnit from the repository root; {@code LicenseInventoryIT}
     * walks up for the same reason.
     */
    private static Path mainSourceRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("chdb-jdbc").resolve("src/main/java/org/chdb");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            candidate = dir.resolve("src/main/java/org/chdb");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return Paths.get("chdb-jdbc/src/main/java/org/chdb").toAbsolutePath();
    }

    @Test
    @DisplayName("the rule can actually fail")
    void theRuleIsNotVacuous() {
        // The rule is a loop over files, so the thing worth pinning is the comparison inside
        // it: that a mismatched pair is recognised as one. Checked directly, because a test
        // that only ever sees correct source cannot show it would notice incorrect source.
        assertEquals(
                SQLNonTransientConnectionException.class, BY_CLASS.get("08"), "class 08");
        assertEquals(SQLFeatureNotSupportedException.class, BY_CLASS.get("0A"), "class 0A");
        assertEquals(SQLDataException.class, BY_CLASS.get("22"), "class 22");
        assertEquals(SQLSyntaxErrorException.class, BY_CLASS.get("42"), "class 42");
        // And the classes deliberately left out, so removing one from the map is a visible
        // choice rather than a silent weakening of the rule.
        for (String noSubtype : new String[] {"07", "24", "25", "53", "57", "70", "HY"}) {
            assertTrue(
                    !BY_CLASS.containsKey(noSubtype),
                    "JDBC defines no subtype for SQLSTATE class " + noSubtype
                            + "; if that changed, the rule should start checking it");
        }
    }

    @Test
    @DisplayName("a SQLSTATE-bearing exception the driver throws is catchable as its subtype")
    void theSubtypeIsWhatCallersCatch() {
        // assertThrows with the subtype, which is the compile-and-run proof that the declared
        // return type is not hiding a narrower thrown one.
        assertThrows(SQLNonTransientConnectionException.class, () -> {
            throw ChdbExceptions.shuttingDown();
        });
        assertThrows(SQLFeatureNotSupportedException.class, () -> {
            throw ChdbExceptions.notSupported("Savepoints");
        });
    }
}
