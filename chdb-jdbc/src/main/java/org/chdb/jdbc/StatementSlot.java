package org.chdb.jdbc;

import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets one statement at a time run on a chDB connection.
 *
 * <h2>Why a lock around execute is not enough</h2>
 * The engine is explicit that a connection runs one statement at a time, and that fetches on a
 * streaming handle must be serialized by the caller (chdb.h, {@code chdb_stream_fetch_result}).
 * A lock held only for the duration of {@code executeQuery} does not achieve that: the fetches
 * happen later, in {@code ResultSet.next()}. Two threads would each open a stream, and their
 * interleaved fetches would meet an engine that has one streaming query per connection --
 * which surfaces as {@code chdb_stream_fetch_arrow failed} on whichever thread loses.
 *
 * <p>So the slot is held from the start of execution until the result set is closed or
 * exhausted. A statement with no result set holds it only for its own duration, since there is
 * nothing left running when {@code chdb_query_n} returns.
 *
 * <h2>Waiting rather than refusing</h2>
 * A second thread's execute waits. Work plan section 5.8 allows either serializing or
 * rejecting, and waiting is what makes an ordinary connection pool safe without the pool
 * having to know anything: each pooled Connection is used by one thread at a time, so the
 * wait never happens, and a pool that hands the same Connection to two threads gets correct
 * results rather than a corrupted engine.
 *
 * <h2>Except for the one case waiting cannot fix</h2>
 * A thread that opens a second result set without closing the first would wait for itself
 * forever. That is a caller bug, and a deadlock is the least useful way to report one, so
 * re-entry from the holding thread raises an exception naming the statement still open.
 */
final class StatementSlot {

    /** Fair, so a thread cannot be starved by a stream of short statements from others. */
    private final Semaphore permit = new Semaphore(1, true);

    /** Who holds the slot and what they are running, for the re-entry message. */
    private final AtomicReference<Holder> holder = new AtomicReference<>(null);

    private static final class Holder {
        final Thread thread;
        final String sql;

        Holder(Thread thread, String sql) {
            this.thread = thread;
            this.sql = sql;
        }
    }

    /**
     * Takes the slot, waiting for whoever has it.
     *
     * @param sql the statement about to run, quoted back if another caller has to be told about
     *     it
     * @throws SQLException if this thread already holds the slot, or the wait is interrupted
     */
    void acquire(String sql) throws SQLException {
        Holder current = holder.get();
        if (current != null && current.thread == Thread.currentThread()) {
            throw new SQLException(
                    "This Connection already has a statement in progress on this thread, and chDB"
                            + " runs one statement per connection at a time.\n\n  still open: "
                            + abbreviate(current.sql)
                            + "\n  attempted : "
                            + abbreviate(sql)
                            + "\n\nClose the open ResultSet before running another statement on this"
                            + " Connection, or use a second Connection (they may share the same"
                            + " storage path) so the two can run side by side.",
                    "25000");
        }

        try {
            permit.acquire();
        } catch (InterruptedException e) {
            // Restored because swallowing it would strand a caller that is being shut down.
            Thread.currentThread().interrupt();
            throw new SQLException(
                    "Interrupted while waiting for the Connection to become free", "70100", e);
        }
        holder.set(new Holder(Thread.currentThread(), sql));
    }

    /**
     * Releases the slot. Idempotent, and callable from a thread other than the one that took
     * it -- {@code Connection.close()} from another thread has to be able to free it.
     */
    void release() {
        if (holder.getAndSet(null) != null) {
            permit.release();
        }
    }

    /** Whether a statement is in progress. For diagnostics and tests. */
    boolean isHeld() {
        return holder.get() != null;
    }

    private static String abbreviate(String sql) {
        if (sql == null) {
            return "(unknown)";
        }
        String collapsed = sql.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 100 ? collapsed : collapsed.substring(0, 97) + "...";
    }
}
