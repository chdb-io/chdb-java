package org.chdb.arrow;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
 * Experimental Arrow session. It owns a dedicated JDBC-managed connection, not the caller's
 * allocator or the returned reader. It does not share another JDBC connection's session state.
 */
public final class ArrowSession implements AutoCloseable {
    private ChdbConnection connection;
    private final Set<RegisteredArrowSource> sources = new HashSet<>();
    private final Set<ArrowQuery> queries = new HashSet<>();
    private final AutoCloseable connectionCloseHook = this::closeBeforeConnection;

    private ArrowSession(ChdbConnection connection) {
        this.connection = connection;
        connection.registerArrowCloseHook(connectionCloseHook);
    }

    /** Opens a dedicated connection under the JDBC driver's storage and shutdown rules. */
    public static ArrowSession open(String jdbcUrl) throws SQLException {
        Connection connection = DriverManager.getConnection(Objects.requireNonNull(jdbcUrl));
        try {
            return new ArrowSession(connection.unwrap(ChdbConnection.class));
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** Opens a dedicated connection to chDB's process-wide in-memory database. */
    public static ArrowSession openInMemory() throws SQLException {
        return open("jdbc:chdb::memory:");
    }

    /**
     * Registers the current batch of a caller-owned root. Do not close or modify the root
     * until the returned source is closed. Its reader adapter is owned by the registration;
     * the root itself remains the caller's responsibility.
     */
    public RegisteredArrowSource registerBatch(VectorSchemaRoot root, BufferAllocator allocator)
            throws IOException, SQLException {
        Objects.requireNonNull(root, "root");
        return registerOwnedReader(new SingleBatchReader(allocator, root), allocator);
    }

    private static final class SingleBatchReader extends ArrowReader {
        private final VectorSchemaRoot source;
        private boolean consumed;

        SingleBatchReader(BufferAllocator allocator, VectorSchemaRoot source) {
            super(Objects.requireNonNull(allocator, "allocator"));
            this.source = source;
        }

        @Override protected Schema readSchema() { return source.getSchema(); }
        @Override public long bytesRead() { return 0; }
        @Override protected void closeReadSource() { /* Caller owns the root. */ }

        @Override
        public boolean loadNextBatch() throws IOException {
            if (consumed) {
                return false;
            }
            prepareLoadNextBatch();
            try (ArrowRecordBatch batch = new VectorUnloader(source).getRecordBatch()) {
                loadRecordBatch(batch);
            }
            consumed = true;
            return true;
        }
    }

    /**
     * Registers an in-memory, one-shot Arrow reader for SQL scans. This method transfers
     * ownership of {@code reader} once an open session begins registration, including if
     * native registration fails. If the session is already closed, ownership stays with the caller.
     * The caller keeps the allocator alive through {@link RegisteredArrowSource#close()}.
     * A returned source is only for queries on this session; repeated scans are unsupported.
     */
    public synchronized RegisteredArrowSource registerOwnedReader(
            ArrowReader reader, BufferAllocator allocator) throws IOException, SQLException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(allocator, "allocator");
        if (connection == null) {
            throw new IllegalStateException("Arrow session is closed");
        }
        requireNoActiveQuery();
        ArrowArrayStream stream;
        try {
            stream = ArrowArrayStream.allocateNew(allocator);
        } catch (RuntimeException e) {
            try {
                reader.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        try {
            Data.exportArrayStream(allocator, reader, stream);
            // UUID uses only ASCII characters safe inside the SQL string literal below.
            String name = "chdb_java_" + UUID.randomUUID().toString().replace("-", "");
            RegisteredArrowSource source = new RegisteredArrowSource(this, stream, name);
            sources.add(source);
            try {
                connection.registerArrowStreamNative(name, stream.memoryAddress());
            } catch (SQLException | RuntimeException e) {
                sources.remove(source);
                throw e;
            }
            return source;
        } catch (SQLException | RuntimeException e) {
            try {
                // A failed registration still owns the exported C callback.
                stream.release();
            } catch (RuntimeException closeError) {
                e.addSuppressed(closeError);
            } finally {
                try {
                    stream.close();
                } catch (RuntimeException closeError) {
                    e.addSuppressed(closeError);
                }
            }
            // On an export failure Arrow's exporter closes the reader; otherwise
            // the release callback above does. Do not close it a second time.
            throw e;
        }
    }

    synchronized void unregister(RegisteredArrowSource source) throws IOException, SQLException {
        if (!sources.contains(source)) {
            return;
        }
        // An arbitrary SQL query may refer to any registered name. Conservatively finish
        // the only active query before unregistering a source; no SQL parsing is involved.
        closeQueries();
        // A failed unregister leaves the borrowed C pointer live in core. Keep the stream,
        // reader and session alive so the caller can retry rather than free under the registry.
        connection.unregisterArrowStreamNative(source.name());
        sources.remove(source);
        source.releaseOwnedResources();
    }

    /**
     * Returns the materialized SQL result through Arrow C Stream, without IPC serialization.
     * The native query runs to completion before this method returns. Close the reader before
     * closing its allocator; retaining a batch beyond the next load requires a copy/transfer.
     *
     * @throws IOException if Arrow Java cannot import the returned C stream
     */
    public synchronized ArrowReader queryArrowMaterialized(String sql, BufferAllocator allocator)
            throws IOException, SQLException {
        return queryArrowMaterialized(sql, allocator, ArrowOptions.DEFAULT);
    }

    /** Materialized output with explicit core Arrow type mapping choices. */
    public synchronized ArrowReader queryArrowMaterialized(
            String sql, BufferAllocator allocator, ArrowOptions options)
            throws IOException, SQLException {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(options, "options");
        if (connection == null) {
            throw new IllegalStateException("Arrow session is closed");
        }
        requireNoActiveQuery();
        try (ArrowArrayStream stream = ArrowArrayStream.allocateNew(allocator)) {
            try {
                connection.queryArrowMaterializedNative(sql, stream.memoryAddress(), options.nativeFlags());
                // Keep this struct open for the finally block. A successful import
                // moves the callback and marks it released; a failed import does not.
                return Data.importArrayStream(allocator, stream, false);
            } finally {
                // If import fails before the move, the native result still belongs here.
                stream.release();
            }
        }
    }

    /**
     * Starts a lazy typed Arrow query. Its reader holds this connection's statement slot
     * through EOF, cancel or close. Close it before issuing another query on this session.
     */
    public synchronized ArrowQuery queryArrow(String sql, BufferAllocator allocator)
            throws SQLException {
        return queryArrow(sql, allocator, ArrowOptions.DEFAULT);
    }

    /** Incremental output with explicit core Arrow type mapping choices. */
    public synchronized ArrowQuery queryArrow(
            String sql, BufferAllocator allocator, ArrowOptions options) throws SQLException {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(options, "options");
        if (connection == null) {
            throw new IllegalStateException("Arrow session is closed");
        }
        requireNoActiveQuery();
        ArrowQuery query = connection.arrowStreamOpenNative(
                sql, options.nativeFlags(), handle -> new ArrowQuery(connection, allocator, handle));
        queries.add(query);
        return query;
    }

    /**
     * Inserts a one-shot reader into an existing table without Arrow IPC. Only simple ASCII
     * table or database.table names are accepted; core receives no arbitrary settings text.
     * All source columns must be named uniquely, so core cannot silently fall back to
     * positional insertion. Once an open session has accepted the call (and has no active
     * query), it owns and closes the reader even if validation or the insert fails. A closed
     * session or active query leaves ownership with the caller. Like ordinary chDB inserts,
     * this is not transactional.
     *
     * @return the core-reported rows written
     */
    public synchronized long insertOwnedReader(
            String tableName, ArrowReader reader, BufferAllocator allocator)
            throws IOException, SQLException {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(allocator, "allocator");
        if (connection == null) {
            throw new IllegalStateException("Arrow session is closed");
        }
        requireNoActiveQuery();
        try (ArrowReader owned = reader) {
            if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
                throw new IllegalArgumentException("Expected a simple table or database.table name");
            }
            Set<String> columns = new HashSet<>();
            for (org.apache.arrow.vector.types.pojo.Field field : owned.getVectorSchemaRoot()
                    .getSchema().getFields()) {
                if (field.getName() == null || field.getName().isEmpty()
                        || !columns.add(field.getName())) {
                    throw new IllegalArgumentException("Arrow insert requires distinct, named columns");
                }
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("Arrow insert needs at least one column");
            }
            try (ArrowArrayStream stream = ArrowArrayStream.allocateNew(allocator)) {
                Data.exportArrayStream(allocator, owned, stream);
                return connection.insertArrowStreamNative(tableName, stream.memoryAddress());
            }
        }
    }

    /** Inserts one caller-owned batch; the root remains valid after the call returns. */
    public long insertBatch(String tableName, VectorSchemaRoot root, BufferAllocator allocator)
            throws IOException, SQLException {
        Objects.requireNonNull(root, "root");
        return insertOwnedReader(tableName, new SingleBatchReader(allocator, root), allocator);
    }

    private void requireNoActiveQuery() {
        queries.removeIf(ArrowQuery::isFinished);
        if (!queries.isEmpty()) {
            throw new IllegalStateException("Close the active Arrow query before using this session again");
        }
    }

    private void closeQueries() throws IOException {
        for (ArrowQuery query : new ArrayList<>(queries)) {
            query.close();
        }
        queries.clear();
    }

    private synchronized void closeBeforeConnection() throws IOException {
        closeQueries();
        IOException failure = null;
        for (RegisteredArrowSource source : new ArrayList<>(sources)) {
            try {
                // The JDBC close gate is already shut. Bypass the ordinary execution-start
                // check while the native connection still exists, then release the borrow.
                connection.unregisterArrowStreamOnClose(source.name());
                sources.remove(source);
                source.releaseOwnedResources();
            } catch (IOException | RuntimeException e) {
                IOException wrapped = new IOException("Failed to unregister Arrow source on close", e);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        connection = null;
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws SQLException, IOException {
        ChdbConnection current;
        synchronized (this) {
            if (connection == null) {
                return;
            }
            closeQueries();
            for (RegisteredArrowSource source : new ArrayList<>(sources)) {
                source.close();
            }
            current = connection;
            connection = null;
            current.unregisterArrowCloseHook(connectionCloseHook);
        }
        // JDBC close holds closeLock and may call the synchronized close hook from a
        // snapshot. Never wait for closeLock while holding this session's monitor.
        current.close();
    }
}
