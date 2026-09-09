package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The one invariant that keeps the shutdown hook off a connection the engine is working on:
 * an entrant and the hook's claim can never both hold the gate.
 *
 * <p>Tested here rather than through a real connection because the invariant is the whole of
 * the mechanism and needs no engine to exercise — which also makes the interesting cases
 * deterministic instead of dependent on hitting a window. The end-to-end consequence is
 * {@code ProcessLifecycleIT.exitWithQueriesInFlightIsPrompt}, and the refusal a caller sees is
 * {@code ProcessLifecycleIT.aClaimedConnectionRefusesNewStatements}.
 */
class ExecutionGateTest {

    @Test
    @DisplayName("an idle gate lets either side in")
    void idleGateAdmitsEither() {
        assertTrue(new ExecutionGate().enter(), "an idle gate must admit a statement");
        assertTrue(
                new ExecutionGate().closeToNewEntrants(),
                "an idle gate must let the hook claim the connection");
    }

    @Test
    @DisplayName("the hook cannot claim a connection with a statement starting on it")
    void aClaimLosesToAnEntrant() {
        // The case that aborts the engine if it gets through: 12 runs in 40 on 26.7.2-rc.2.
        // Deterministic here, because the entrant is simply still inside the gate -- which is
        // exactly the state a thread parked inside chdb_query_n is in.
        ExecutionGate gate = new ExecutionGate();
        assertTrue(gate.enter());

        assertFalse(gate.closeToNewEntrants(), "the hook must not take a busy connection");
        assertFalse(gate.isClosedToNewEntrants());
        assertEquals(1, gate.inFlight());

        // And it becomes claimable the moment the statement is done, which is what makes the
        // drain's next pass worth taking.
        gate.exit();
        assertEquals(0, gate.inFlight());
        assertTrue(gate.closeToNewEntrants());
    }

    @Test
    @DisplayName("every entrant has to leave before the hook can claim")
    void allEntrantsMustLeaveFirst() {
        // Two entrants is not hypothetical: metadata queries and application statements run on
        // the same connection, and the count has to be a count rather than a flag for that.
        ExecutionGate gate = new ExecutionGate();
        assertTrue(gate.enter());
        assertTrue(gate.enter());
        assertEquals(2, gate.inFlight());

        gate.exit();
        assertFalse(gate.closeToNewEntrants(), "one entrant is still inside");
        gate.exit();
        assertTrue(gate.closeToNewEntrants());
    }

    @Test
    @DisplayName("a claimed connection refuses new statements, permanently")
    void aClaimedGateRefusesEntrants() {
        ExecutionGate gate = new ExecutionGate();
        assertTrue(gate.closeToNewEntrants());

        assertFalse(gate.enter(), "the hook owns this connection; nothing may reach the engine");
        assertFalse(gate.enter(), "and it does not become available again");
        assertTrue(gate.isClosedToNewEntrants());
        assertEquals(0, gate.inFlight());
        assertFalse(gate.closeToNewEntrants(), "a second claim is not a fresh one");
    }

    @Test
    @DisplayName("an unpaired exit cannot drive the gate into the claimed state")
    void exitDoesNotUnderflow() {
        // Worth pinning because the consequence is silent and total: a state driven to -1 by a
        // stray exit would read as "claimed" and lock the connection out of the engine for the
        // rest of the process, with no failure anywhere near the cause.
        ExecutionGate gate = new ExecutionGate();
        gate.exit();
        gate.exit();

        assertFalse(gate.isClosedToNewEntrants());
        assertEquals(0, gate.inFlight());
        assertTrue(gate.enter(), "the gate must still admit statements");
    }

    @Test
    @DisplayName("under contention, no statement ever starts after the hook has claimed")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void noEntrantWinsAfterTheClaim() throws Exception {
        // The assertion is exact; the coverage is probabilistic. What this adds over the tests
        // above is that it drives the compare-and-set loops concurrently, which is the only way
        // to catch an ordering mistake in them -- but a run that happens not to interleave
        // still passes, so it is a net, not the proof. The proof that the window is closed at
        // all is that both sides move one word and neither reads it and then acts.
        int threads = 8;
        int gates = 2000;
        AtomicLong entriesAfterClaim = new AtomicLong();
        AtomicLong claimsWon = new AtomicLong();
        AtomicLong entriesWon = new AtomicLong();

        for (int round = 0; round < gates; round++) {
            ExecutionGate gate = new ExecutionGate();
            AtomicBoolean claimed = new AtomicBoolean(false);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads + 1);
            AtomicInteger held = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                Thread entrant =
                        new Thread(
                                () -> {
                                    try {
                                        go.await();
                                        if (gate.enter()) {
                                            entriesWon.incrementAndGet();
                                            // The check that matters: if the hook has already
                                            // decided it owns this connection, no entrant may
                                            // be inside the engine.
                                            if (claimed.get()) {
                                                entriesAfterClaim.incrementAndGet();
                                            }
                                            held.incrementAndGet();
                                            gate.exit();
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    } finally {
                                        done.countDown();
                                    }
                                },
                                "gate-entrant-" + i);
                entrant.setDaemon(true);
                entrant.start();
            }

            Thread hook =
                    new Thread(
                            () -> {
                                try {
                                    go.await();
                                    if (gate.closeToNewEntrants()) {
                                        claimed.set(true);
                                        claimsWon.incrementAndGet();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    done.countDown();
                                }
                            },
                            "gate-hook");
            hook.setDaemon(true);
            hook.start();

            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "a gate round did not finish");

            // Once claimed, the gate never admits anyone again, so the count is final here.
            if (claimed.get()) {
                assertEquals(
                        0,
                        gate.inFlight(),
                        "a claimed gate must have nobody inside it");
            }
        }

        assertEquals(
                0,
                entriesAfterClaim.get(),
                "a statement started on a connection the shutdown hook had already claimed;"
                        + " that is the state the engine aborts on");
        // Reported rather than asserted: the split depends on scheduling, and a run where the
        // hook always lost would still be correct -- it would just not have tested much.
        System.out.println(
                "gate contention: " + claimsWon.get() + " claims and " + entriesWon.get()
                        + " statements won across " + gates + " gates");
        assertTrue(claimsWon.get() > 0, "the hook never won a single gate; the test raced badly");
        assertTrue(entriesWon.get() > 0, "no statement ever won a gate; the test raced badly");
    }
}
