package org.chdb.arrow;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.arrow.c.ArrowArrayStream;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.TransferPair;
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
    private Map<Long, Dictionary> ownedDictionaries;

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
                // Successful import moves the callback. On failure release the
                // still-owned native batch before closing the struct buffer.
                return Data.importArrayStream(allocator, stream, false);
            } catch (SQLException e) {
                throw new IOException("Failed to fetch an Arrow batch", e);
            } finally {
                stream.release();
            }
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
    protected void initialize() throws IOException {
        super.initialize();
        // ArrowReader builds an unmodifiable dictionary map from its schema.
        // Each native fetch imports a fresh dictionary, so this reader must own
        // and replace the values while retaining stable dictionary IDs.
        ownedDictionaries = new HashMap<>(dictionaries);
        // readSchema() receives an already-converted index schema from current.
        // super.initialize() converts it again and creates index-typed dictionary
        // placeholders. Replace them with independent empty vectors of the actual
        // value types before anyone exports this reader's schema, even before load.
        for (Map.Entry<Long, Dictionary> entry : current.getDictionaryVectors().entrySet()) {
            FieldVector values = entry.getValue().getVector().getField().createVector(allocator);
            Dictionary previous = ownedDictionaries.put(entry.getKey(),
                    new Dictionary(values, entry.getValue().getEncoding()));
            if (previous != null) {
                previous.getVector().close();
            }
        }
        dictionaries = Collections.unmodifiableMap(ownedDictionaries);
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
            // VectorUnloader only transfers the record's index vectors. Move the
            // accompanying dictionary values into this reader before closing the
            // imported per-fetch reader; its dictionary vectors are otherwise lost.
            for (Map.Entry<Long, Dictionary> entry : current.getDictionaryVectors().entrySet()) {
                Dictionary previous = ownedDictionaries.get(entry.getKey());
                if (previous == null) {
                    throw new IOException("Unknown Arrow dictionary id " + entry.getKey());
                }
                Dictionary incoming = entry.getValue();
                TransferPair pair = incoming.getVector().getTransferPair(allocator);
                FieldVector movedVector = (FieldVector) pair.getTo();
                try {
                    pair.transfer();
                } catch (RuntimeException e) {
                    movedVector.close();
                    throw e;
                }
                Dictionary moved = new Dictionary(movedVector, incoming.getEncoding());
                ownedDictionaries.put(entry.getKey(), moved);
                previous.getVector().close();
            }
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
