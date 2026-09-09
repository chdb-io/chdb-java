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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    /**
     * Licence strings that name a copyleft family but offer a permissive alternative, so the
     * permissive half is the one in force.
     *
     * <p>An allowlist of exact strings rather than a pattern, because "does this disjunction
     * contain something permissive" is not a judgement a regular expression should be making on
     * our behalf. Each entry is a decision someone took, recorded where it can be re-read.
     */
    private static final Set<String> DUAL_LICENSED_WITH_PERMISSIVE_ALTERNATIVE =
            new TreeSet<>(
                    java.util.Arrays.asList(
                            // ittapi, ittapi-sys
                            "GPL-2.0-only OR BSD-3-Clause",
                            // r-efi, r-efi-alloc
                            "MIT OR Apache-2.0 OR LGPL-2.1-or-later"));

    /**
     * Everything in the engine under a copyleft licence with no permissive alternative.
     *
     * <p>These are the whole of what a redistribution review has to reach a position on. LGPL
     * carries obligations under static linking that it does not under dynamic; MPL-2.0 is
     * weaker, file-level copyleft, but it is still copyleft and still has no alternative half.
     */
    private static Map<String, Set<String>> knownCopyleftOnly() {
        Map<String, Set<String>> known = new TreeMap<>();
        known.put(
                "LGPL",
                new TreeSet<>(
                        java.util.Arrays.asList(
                                "lemmagen-c", "libgsasl", "libssh", "mariadb-connector-c",
                                "numactl", "xz")));
        known.put(
                "MPL-2.0",
                new TreeSet<>(
                        java.util.Arrays.asList(
                                "cbindgen", "defer-drop", "fortanix-sgx-abi",
                                "shuffling-allocator", "timer", "webpki-roots")));
        return known;
    }

    @Test
    @DisplayName("every component under a copyleft licence is one we have accounted for")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void copyleftComponentsAreTheKnownSet() throws Exception {
        // Matched broadly and then filtered, rather than queried for the two licence strings we
        // happen to know about. An earlier version asked for license_type IN ('LGPL', 'GPL'),
        // which silently ignored the six MPL-2.0 components already in the engine and would
        // have ignored an AGPL or EPL one arriving later -- in a test whose entire purpose is
        // that a new copyleft component cannot ship unnoticed.
        //
        // The pattern is not exhaustive and cannot be; a licence family nobody listed here
        // still slips through. What stops that is inventoryMatchesTheEngine, which fails on any
        // change at all. This test is what survives regenerating the inventory, which is the
        // step where a human is most likely to wave a diff through.
        Map<String, Set<String>> actual = new TreeMap<>();
        Set<String> allowlistSeen = new TreeSet<>();

        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT DISTINCT license_type, library_name FROM system.licenses"
                                    + " WHERE match(upper(license_type),"
                                    + " 'AGPL|LGPL|GPL|MPL|EPL|CDDL|CPL|OSL|SSPL|EUPL|CECILL|QPL')"
                                    + " ORDER BY license_type, library_name")) {
            while (rs.next()) {
                String licence = rs.getString(1);
                if (DUAL_LICENSED_WITH_PERMISSIVE_ALTERNATIVE.contains(licence)) {
                    allowlistSeen.add(licence);
                    continue;
                }
                actual.computeIfAbsent(licence, k -> new TreeSet<>()).add(rs.getString(2));
            }
        }

        assertEquals(
                knownCopyleftOnly(),
                actual,
                "the set of copyleft-only components in the engine has changed. Anything added"
                        + " here needs a redistribution decision before the next release, and"
                        + " anything removed means the shipped notice now overstates. See"
                        + " docs/publishing.md.");

        // A stale allowlist entry is a decision that no longer applies to anything, and leaving
        // it would quietly excuse a future component that happens to reuse the string.
        assertEquals(
                DUAL_LICENSED_WITH_PERMISSIVE_ALTERNATIVE,
                allowlistSeen,
                "the dual-licensed allowlist no longer matches what the engine contains");
    }
}
