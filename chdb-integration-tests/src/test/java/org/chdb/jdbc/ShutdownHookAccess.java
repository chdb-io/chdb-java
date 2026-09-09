package org.chdb.jdbc;

import java.sql.Connection;

/**
 * Lets the integration tests drive the shutdown hook's claim on a connection.
 *
 * <h2>Why this class is here and shaped like this</h2>
 * The claim is the whole of the fix for issue #22 defect 1, and both of its outcomes need
 * asserting: the hook takes an idle connection, and it does not take one with a statement
 * executing. Neither is observable from outside the process. A forked-JVM test sees only an
 * exit code, and the exit code is the same either way — the difference is whether the engine
 * aborts, which is a race, and whether an application thread is refused, which happens in a
 * window a test cannot aim at. Driving the claim directly is the only way to make either
 * deterministic.
 *
 * <p>It lives in {@code org.chdb.jdbc} in <em>this</em> module's test sources, rather than as a
 * public method on the driver, because the driver's published surface should not grow a
 * "close this connection out from under its own threads" entry point for the sake of a test.
 * The integration tests are on the classpath and the driver has no {@code module-info}, so a
 * test-only class in the same package reaches the package-private methods the drain uses —
 * {@code ChdbConnection.claimForShutdownClose()} and nothing else. If the driver ever becomes a
 * named module this stops compiling, which is the right outcome: it should then be reconsidered
 * rather than worked around.
 *
 * <p>Nothing here is reachable from application code, and nothing in {@code chdb-jdbc} refers
 * to it.
 */
public final class ShutdownHookAccess {

    private ShutdownHookAccess() {
    }

    /**
     * Exactly what {@code ShutdownCleanup}'s drain does to decide whether it may close a
     * connection.
     *
     * @return whether the hook would have taken this connection
     */
    public static boolean claim(Connection connection) {
        return ((ChdbConnection) connection).claimForShutdownClose();
    }

    /** Whether a claim has succeeded on this connection. */
    public static boolean isClaimed(Connection connection) {
        return ((ChdbConnection) connection).isClaimedForShutdownClose();
    }

    /**
     * How many threads the connection thinks are inside a statement-start call.
     *
     * <p>Here because {@link #claim} is terminal and so can only answer "was the gate free?"
     * once per connection, which is no use for checking that a statement that <em>failed</em>
     * put the gate back on its way out.
     */
    public static int inFlight(Connection connection) {
        return ((ChdbConnection) connection).executionsInFlight();
    }
}
