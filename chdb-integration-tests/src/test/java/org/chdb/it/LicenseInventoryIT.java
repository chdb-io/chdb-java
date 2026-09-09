package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Keeps the redistributed engine's licence inventory honest.
 *
 * <p>Each platform package ships a ~326 MB {@code libchdb.so} built from the ClickHouse tree,
 * which carries several hundred third-party components. Redistributing them means shipping
 * their notices, and the list has to be the real one.
 *
 * <p>The list comes from the engine itself. ClickHouse generates {@code system.licenses} at
 * build time from {@code contrib}, so querying the exact binary we ship is the authoritative
 * answer — strictly better than scanning a source tree, which reports the licences of a
 * checkout rather than of an artifact, and better than guessing from filenames, which
 * miscounts every dual-licensed component. It scans as GPL when the permissive half is what
 * applies.
 *
 * <p>What this test protects is the {@code licenses/} file being a stale copy. An engine bump
 * that adds, removes or relicenses a component fails here, which is the point: that is exactly
 * when a human needs to look.
 */
class LicenseInventoryIT extends NativeTestBase {

    /** The engine version under test, set by the failsafe configuration from the POM. */
    private static final String ENGINE_VERSION = System.getProperty("chdb.it.engine.version");

    private static Path inventoryPath() {
        String name = "engine-third-party-" + ENGINE_VERSION + ".tsv";
        // The working directory is not the same everywhere this suite runs: failsafe uses the
        // module directory, while the sanitizer and oldest-platform jobs drive the JUnit
        // console launcher from the repository root. Walking up for the file works from either
        // without a system property that every call site would have to remember to set.
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("licenses").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        // Not found. Returned anyway so the assertion below can name what it looked for.
        return Paths.get("licenses", name).toAbsolutePath();
    }

    /** {@code library<TAB>licence}, distinct and sorted, exactly as the committed file stores it. */
    private static List<String> queryInventory() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT DISTINCT library_name, license_type FROM system.licenses"
                                        + " ORDER BY lower(library_name), license_type")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + "\t" + rs.getString(2));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("the committed licence inventory matches the engine we ship")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void inventoryMatchesTheEngine() throws Exception {
        Path path = inventoryPath();
        assertTrue(
                Files.isRegularFile(path),
                "no licence inventory for engine "
                        + ENGINE_VERSION
                        + " at "
                        + path.toAbsolutePath().normalize()
                        + ". Regenerate it: see docs/publishing.md.");

        List<String> committed = Files.readAllLines(path, StandardCharsets.UTF_8);
        committed.removeIf(String::isEmpty);
        List<String> live = queryInventory();

        Set<String> added = new TreeSet<>(live);
        added.removeAll(committed);
        Set<String> removed = new TreeSet<>(committed);
        removed.removeAll(live);

        // Reported as a diff rather than as "expected 968 got 971": whoever sees this failure
        // needs to know which components changed so they can decide whether the notice set
        // still covers them.
        assertTrue(
                added.isEmpty() && removed.isEmpty(),
                "the licence inventory is out of date for engine "
                        + ENGINE_VERSION
                        + ".\n  in the engine but not committed: "
                        + added
                        + "\n  committed but not in the engine: "
                        + removed
                        + "\nRegenerate and review before releasing: see docs/publishing.md.");
        assertEquals(committed, live, "the inventory differs in order");
    }

    @Test
    @DisplayName("every component under a copyleft licence is one we have accounted for")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void copyleftComponentsAreTheKnownSet() throws Exception {
        // Six components carry a copyleft licence with no permissive alternative. They are the
        // whole of what a redistribution review has to reach a position on, so a new one
        // appearing must not go unnoticed.
        //
        // Deliberately not a count or a substring match on "GPL". Several components are dual
        // licensed with the permissive half in force -- ittapi is "GPL-2.0-only OR
        // BSD-3-Clause", r-efi is "MIT OR Apache-2.0 OR LGPL-2.1-or-later" -- and a test that
        // flagged those would be noise that gets muted.
        Set<String> expected =
                new LinkedHashSet<>(
                        java.util.Arrays.asList(
                                "lemmagen-c", "libgsasl", "libssh", "mariadb-connector-c",
                                "numactl", "xz"));

        Set<String> actual = new TreeSet<>();
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT DISTINCT library_name FROM system.licenses"
                                        + " WHERE license_type IN ('LGPL', 'GPL')"
                                        + " ORDER BY library_name")) {
            while (rs.next()) {
                actual.add(rs.getString(1));
            }
        }

        assertEquals(
                new TreeSet<>(expected),
                actual,
                "the set of copyleft-only components in the engine has changed. Anything added"
                        + " here needs a redistribution decision before the next release; see"
                        + " docs/publishing.md.");
        assertFalse(actual.isEmpty(), "the query returned nothing, so it is not testing anything");
    }
}
