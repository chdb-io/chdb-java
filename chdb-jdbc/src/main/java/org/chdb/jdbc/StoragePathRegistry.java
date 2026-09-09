package org.chdb.jdbc;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Enforces the engine's one-storage-path-per-process rule at the JDBC layer.
 *
 * <p>chDB binds a storage path when the first connection opens and keeps it until the last
 * one closes. Any number of connections may share that path; a connection asking for a
 * different one cannot be served (work plan section 5.7).
 *
 * <p>Left to the engine, that shows up as {@code chdb_connect} returning NULL, which tells a
 * caller nothing. Tracking it here means the error names the bound path, the requested path,
 * how many connections are holding it open, and what to do -- which matters most for the two
 * cases people actually hit: a connection pool configured with two JDBC URLs, and a test
 * suite whose previous test left a Connection open.
 *
 * <p>Static state, on purpose: the constraint is per process, and the engine is one native
 * library shared by every ClassLoader in the JVM (section 3.5). Making this per-instance
 * would let two pools each believe they owned the path.
 */
final class StoragePathRegistry {

    private static final Object LOCK = new Object();

    // The path currently bound, or null when no connection is open: the absolute normalized
    // storage path, or ":memory:". Also the form messages print, which is why there is no
    // second display field -- reading one off ChdbUrl.storagePath() would NPE for a path this
    // JVM's sun.jnu.encoding cannot hold, which has no Path but does have a key.
    private static String boundKey;
    private static int openConnections;
    // URLs of the connections holding the binding, for the conflict message. A set because
    // a pool opens many connections from one URL and listing it once is what is useful.
    private static final Set<String> boundUrls = new LinkedHashSet<>();

    private StoragePathRegistry() {
    }

    /**
     * Reserves the storage path for a connection that is about to open.
     *
     * <p>Call before {@code chdb_connect}, and {@link #release(ChdbUrl)} once the connection
     * closes. A failed connect must release too, or the path stays pinned -- {@code
     * ChdbConnection}'s constructor does that in a catch block.
     *
     * @throws SQLException if a different storage path is already bound in this JVM
     */
    static void acquire(ChdbUrl url) throws SQLException {
        String key = url.registryKey();
        synchronized (LOCK) {
            if (openConnections > 0 && !key.equals(boundKey)) {
                throw new SQLNonTransientConnectionException(conflictMessage(key), "08004");
            }
            boundKey = key;
            openConnections++;
            boundUrls.add(url.url());
        }
    }

    /** Releases one connection's hold. The path is free to change once the count reaches zero. */
    static void release(ChdbUrl url) {
        synchronized (LOCK) {
            if (openConnections == 0) {
                // A double close. Idempotent close is a JDBC requirement, so this is not an
                // error; ignoring it is what keeps the count from going negative and
                // corrupting a later acquire.
                return;
            }
            openConnections--;
            if (openConnections == 0) {
                boundKey = null;
                boundUrls.clear();
            }
        }
    }

    /** Connections currently open in this JVM. Used by tests to assert a clean teardown. */
    static int openConnectionCount() {
        synchronized (LOCK) {
            return openConnections;
        }
    }

    /** The bound storage path, or null if none. */
    static String boundPath() {
        synchronized (LOCK) {
            return boundKey;
        }
    }

    private static String conflictMessage(String requestedKey) {
        StringBuilder message = new StringBuilder();
        message.append("chDB is already using a different storage path in this JVM.\n\n")
                .append("  currently bound : ")
                .append(boundKey)
                .append('\n')
                .append("  requested       : ")
                .append(requestedKey)
                .append('\n')
                .append("  open connections: ")
                .append(openConnections)
                .append('\n');
        if (!boundUrls.isEmpty()) {
            message.append("  held by URL(s)  : ").append(String.join(", ", boundUrls)).append('\n');
        }
        message.append("\nThe embedded engine binds one storage path per process and keeps it until the")
                .append(" last connection closes. Any number of connections may share the bound path;")
                .append(" a second path is not possible in the same JVM.\n\nFix it one of these ways:\n")
                .append("  1. Use one storage path per JVM. Check that every DataSource, connection")
                .append(" pool and test fixture uses the same jdbc:chdb: URL.\n")
                .append("  2. Close every open Connection first, then connect to the new path.\n")
                .append("  3. Run the second storage path in its own JVM.\n");
        if (!requestedKey.equals(ChdbUrl.MEMORY) && ChdbUrl.MEMORY.equals(boundKey)) {
            message.append("\nNote: an in-memory connection counts as a bound path. A jdbc:chdb: URL with")
                    .append(" no path means ")
                    .append(ChdbUrl.MEMORY)
                    .append(", which is easy to open by accident.\n");
        }
        return message.toString();
    }
}
