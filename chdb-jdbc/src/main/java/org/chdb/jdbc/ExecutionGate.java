package org.chdb.jdbc;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides, for one connection, whether the shutdown hook may close it or an application thread
 * may start a statement on it — never both.
 *
 * <h2>Why this is not a counter and a check</h2>
 * The two questions look independent and are not. The hook must not close a connection whose
 * statement is executing: measured on engine 26.7.2-rc.2, that aborts the process (21 runs in
 * 60) or blocks the JVM for the length of the query (up to 79 s). See {@link ShutdownCleanup}.
 *
 * <p>A count of executions in flight, read by the hook before it closes, does not achieve that.
 * A shutdown hook runs concurrently with application threads — the JVM stops nothing first — so
 * "read zero, then close" leaves a window in which a thread starts a statement between the read
 * and the close, which puts the connection back in exactly the state that aborts. The window is
 * narrow, not theoretical: a service taking SIGTERM with requests arriving is the ordinary case.
 *
 * <p>So the count and the closing decision are one word, and both sides move it with a
 * compare-and-set rather than looking and then acting:
 *
 * <pre>
 *   state &gt; 0    that many threads are inside a native statement-start call
 *   state == 0    idle; either side may take it
 *   state == -1   closed to new entrants; the hook has it and will not give it back
 * </pre>
 *
 * {@link #closeToNewEntrants()} succeeds only from exactly {@code 0}, and {@link #enter()}
 * succeeds only from {@code 0} or above. Whichever compare-and-set lands first wins, and the
 * loser is told so — the hook skips the connection and looks again next pass, or the
 * application thread gets an exception naming the shutdown.
 *
 * <h2>Why a state word and not a lock</h2>
 * A lock would have to be held across {@code chdb_close_conn()} to be worth anything, and that
 * call blocks until the connection's query finishes — 79 s in the worst case measured. Every
 * application thread reaching {@link #enter()} would then block for that same duration, which
 * is a worse failure than the one being fixed. A state word needs no such span: once the hook
 * has closed the gate, nothing new can enter, so the hook can close the connection outside any
 * lock at all and the exclusion still holds.
 *
 * <p>It also keeps the cost off the hot path. {@link #enter()} is a compare-and-set on an
 * uncontended {@code AtomicInteger} — the same order of cost as the plain increment it replaces
 * — and it never waits for the hook's registry monitor.
 *
 * <h2>What it deliberately does not gate</h2>
 * Fetches. A thread inside {@code chdb_stream_fetch_result} is also inside the engine, but
 * closing that connection is the whole purpose of the hook (a leaked streaming result set) and
 * is measured clean: exit 0 over eight runs, against 134 over four with no hook. Only starting
 * a statement is gated.
 */
final class ExecutionGate {

    /** Nothing running, nobody closing. The only state either side may take. */
    private static final int IDLE = 0;

    /** The shutdown hook has this connection. Terminal: it is about to be closed. */
    private static final int CLOSED_TO_NEW_ENTRANTS = -1;

    private final AtomicInteger state = new AtomicInteger(IDLE);

    /**
     * Registers a thread as being about to enter a native statement-start call.
     *
     * @return {@code false} if the shutdown hook has taken this connection, in which case the
     *     caller must not touch the engine and must not call {@link #exit()}
     */
    boolean enter() {
        while (true) {
            int current = state.get();
            if (current == CLOSED_TO_NEW_ENTRANTS) {
                return false;
            }
            if (state.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Pairs with a {@link #enter()} that returned {@code true}. */
    void exit() {
        while (true) {
            int current = state.get();
            if (current <= IDLE) {
                // Unreachable if every enter() is paired exactly once, and cheaper to ignore
                // than to reason about: a state driven negative would let the next enter()
                // walk up to CLOSED_TO_NEW_ENTRANTS and lock the connection out for good.
                return;
            }
            if (state.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    /**
     * Claims the connection for the shutdown hook, if no statement is starting on it.
     *
     * <p>Succeeds only from {@link #IDLE}, so a thread already inside the engine keeps the
     * connection and the hook is told to leave it alone. Once this returns {@code true} no
     * {@link #enter()} can succeed, which is what lets the caller close the connection without
     * holding anything.
     *
     * @return whether the caller now owns this connection
     */
    boolean closeToNewEntrants() {
        return state.compareAndSet(IDLE, CLOSED_TO_NEW_ENTRANTS);
    }

    /** Whether {@link #closeToNewEntrants()} has succeeded. For diagnostics and tests. */
    boolean isClosedToNewEntrants() {
        return state.get() == CLOSED_TO_NEW_ENTRANTS;
    }

    /** How many threads are inside a statement-start call. For diagnostics and tests. */
    int inFlight() {
        int current = state.get();
        return current == CLOSED_TO_NEW_ENTRANTS ? 0 : current;
    }
}
