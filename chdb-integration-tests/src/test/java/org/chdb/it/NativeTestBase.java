package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.chdb.internal.ChdbNative;
import org.junit.jupiter.api.AfterEach;

/**
 * Shared setup for tests that drive a real engine.
 *
 * <p>The important part is {@link #assertNoLeakedHandles()}: work plan section 5.11 requires
 * every native handle to be released by the end of every test, success or failure. Asserting it
 * after each test is what makes a leak show up in the test that caused it rather than as a
 * mysterious drift much later in the run.
 */
abstract class NativeTestBase {

    /** In-memory database. Shared per process, which is why tests use distinct table names. */
    static final String MEMORY_URL = "jdbc:chdb::memory:";

    static Connection openMemory() throws SQLException {
        return DriverManager.getConnection(MEMORY_URL);
    }

    @AfterEach
    void assertNoLeakedHandles() {
        assertEquals(0, ChdbNative.openHandleCount(ChdbNative.KIND_STREAM), "leaked stream handles");
        assertEquals(0, ChdbNative.openHandleCount(ChdbNative.KIND_RESULT), "leaked result handles");
        assertEquals(
                0, ChdbNative.openHandleCount(ChdbNative.KIND_CONNECTION), "leaked connection handles");
    }
}
