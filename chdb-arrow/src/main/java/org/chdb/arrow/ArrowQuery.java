package org.chdb.arrow;

import java.io.IOException;
import java.sql.SQLException;
import org.apache.arrow.c.ArrowArrayStream;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Schema;
import org.chdb.jdbc.ChdbConnection;

/**
 * A typed incremental query. Each load pulls one core batch without IPC serialization.
 * The current root is valid until the next load or close. Cancel is serialized after any
 * in-flight fetch; it does not interrupt an engine call already running on this connection.
 */
public final class ArrowQuery extends ArrowReader {
    private final ChdbConnection connection;
    private final BufferAllocator allocator;
    private long handle;
    private ArrowReader current;
    private boolean exhausted;

    ArrowQuery(ChdbConnection connection, BufferAllocator allocator, long handle) {
        super(allocator);
        this.connection = connection;
        this.allocator = allocator;
        this.handle = handle;
    }

    synchronized boolean isFinished() {
        return handle == 0;
    }

    private ArrowReader fetchOne() throws IOException {
        try (ArrowArrayStream stream = ArrowArrayStream.allocateNew(allocator)) {
            try {
                connection.arrowStreamFetchNative(handle, stream.memoryAddress());
            } catch (SQLException e) {
                throw new IOException("Failed to fetch an Arrow batch", e);
            }
            // The imported reader owns the moved callback. The original struct is empty.
            return Data.importArrayStream(allocator, stream);
        }
    }

    @Override
    protected synchronized Schema readSchema() throws IOException {
        try {
            if (current == null) {
                current = fetchOne();
            }
            return current.getVectorSchemaRoot().getSchema();
        } catch (IOException | RuntimeException e) {
            try {
                closeReadSource();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    @Override
    public synchronized boolean loadNextBatch() throws IOException {
        if (exhausted) {
            return false;
        }
        try {
            prepareLoadNextBatch();
            if (current == null) {
                current = fetchOne();
            }
            if (!current.loadNextBatch()) {
                current.close();
                current = null;
                exhausted = true;
                releaseNative(false);
                return false;
            }
            VectorSchemaRoot batch = current.getVectorSchemaRoot();
            try (ArrowRecordBatch record = new VectorUnloader(batch).getRecordBatch()) {
                loadRecordBatch(record);
            }
            current.close();
            current = null;
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                closeReadSource();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    @Override
    public long bytesRead() {
        return 0; // C Data exchanges buffers, not a byte stream.
    }

    @Override
    public synchronized void close(boolean closeReadSource) throws IOException {
        // ArrowReader.close(boolean) closes its VectorSchemaRoot before calling our
        // closeReadSource(). Synchronize the whole operation against loadNextBatch(), not
        // just the latter callback, or a concurrent close can free the root mid-load.
        // The native result must never outlive this reader, including when a caller uses
        // ArrowReader's public close(false) overload.
        try {
            super.close(true);
        } finally {
            if (handle != 0) {
                closeReadSource();
            }
        }
    }

    /** Stops future batches and closes the reader. Waits for a concurrent fetch to finish. */
    public synchronized void cancel() throws IOException {
        close();
    }

    private void releaseNative(boolean cancel) throws IOException {
        if (handle == 0) {
            return;
        }
        long closing = handle;
        handle = 0;
        IOException failure = null;
        try {
            if (cancel) {
                connection.arrowStreamCancelNative(closing);
            }
        } catch (SQLException e) {
            failure = new IOException("Failed to cancel Arrow query", e);
        } finally {
            try {
                connection.arrowStreamCloseNative(closing, this);
            } catch (SQLException e) {
                if (failure == null) {
                    failure = new IOException("Failed to close Arrow query", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    protected synchronized void closeReadSource() throws IOException {
        IOException failure = null;
        try {
            if (current != null) {
                current.close();
                current = null;
            }
        } catch (IOException e) {
            failure = e;
        }
        exhausted = true;
        try {
            releaseNative(true);
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
