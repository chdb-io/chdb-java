package org.chdb.jdbc;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides, for one connection, whether it may be closed or a thread may start a statement on
 * it — never both.
 *
 * <p>Two closers ask. The shutdown hook takes it with {@link #closeToNewEntrants()} and skips
 * the connection if it loses, because the alternative is holding the JVM open for the length of
 * a query. {@code Connection.close()} takes it with {@link #closeToNewEntrantsWaiting()} and
 * waits, because a close cannot decline: it has to release the connection's streams, and it
 * cannot find one that is still being created. Both move one word with a compare-and-set.
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
 *
 * <p>That is enough for the closing side as well, and worth spelling out because it is the
 * whole argument. What a close needs is not "nobody is in the engine" but "every stream that
 * exists is reachable from the statement that owns it" — and a stream becomes reachable inside
 * the gated region, because {@code ChdbStatement.openStream()} assigns the result set before
 * {@code executeInternal()} returns and releases the gate. A thread that is only fetching has
 * long since published its result set, so the close finds and closes it.
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

    /**
     * Shuts the gate, waiting for any statement start already inside it to finish.
     *
     * <p>For {@code Connection.close()}, which unlike the shutdown hook cannot decline: a
     * connection that reports itself closed must have released its streams, and it cannot
     * find them all while a thread is still between "the shim registered my stream" and "my
     * statement knows about it". Once this returns, no statement can start and every stream
     * that exists on this connection has been published to the statement that owns it.
     *
     * <p>Waiting is not a new cost. {@code chdb_close_conn()} on a connection whose statement
     * is running blocks until that statement finishes anyway — measured at up to 79 s, see
     * {@link ShutdownCleanup} — so the close was going to wait for the same query either way.
     * What changes is that it now waits <em>before</em> taking the handle out of the shim's
     * registry rather than after, which is the part that made the wait unsafe.
     *
     * <p>{@code Thread.onSpinWait()} then a millisecond sleep, rather than a monitor: the two
     * sides of this gate are a compare-and-set precisely so that neither has to hold a lock
     * across an engine call, and a close is not on any hot path.
     *
     * @return {@code false} if the shutdown hook owns this connection, which also means no
     *     statement can start on it -- so the caller may close it either way
     */
    boolean closeToNewEntrantsWaiting() {
        // Held and re-applied at the end rather than re-applied inside the loop: an interrupt
        // flag set while sleeping would make every later sleep throw at once and turn this
        // into a spin. A close that abandoned the wait would close the connection under the
        // statement it was waiting for, which is the failure this exists to prevent.
        boolean interrupted = false;
        try {
            for (int spins = 0; ; spins++) {
                if (state.compareAndSet(IDLE, CLOSED_TO_NEW_ENTRANTS)) {
                    return true;
                }
                if (state.get() == CLOSED_TO_NEW_ENTRANTS) {
                    return false;
                }
                if (spins < 64) {
                    Thread.onSpinWait();
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
