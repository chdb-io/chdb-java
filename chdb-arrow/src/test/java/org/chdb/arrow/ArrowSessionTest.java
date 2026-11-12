package org.chdb.arrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.chdb.jdbc.ChdbConnection;
import org.chdb.internal.ChdbNative;

/** Run with a matching locally built libchdb + JNI shim via -Dchdb.library.path. */
@EnabledIfSystemProperty(named = "chdb.arrow.it", matches = "true")
class ArrowSessionTest {
    @AfterEach
    void allNativeArrowQueriesAreReleased() {
        assertEquals(0L, ChdbNative.openHandleCount(ChdbNative.KIND_ARROW_STREAM));
    }

    private static class OneBatchReader extends ArrowReader {
        private final VectorSchemaRoot source;
        private boolean consumed;

        OneBatchReader(RootAllocator allocator, VectorSchemaRoot source) {
            super(allocator);
            this.source = source;
        }

        @Override protected Schema readSchema() { return source.getSchema(); }
        @Override public long bytesRead() { return 0; }
        @Override protected void closeReadSource() { /* The source root belongs to the test. */ }

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

    private static final class MultipleBatchReader extends ArrowReader {
        private final List<VectorSchemaRoot> batches;
        private int next;

        MultipleBatchReader(RootAllocator allocator, VectorSchemaRoot... batches) {
            super(allocator);
            this.batches = Arrays.asList(batches);
        }

        @Override protected Schema readSchema() { return batches.get(0).getSchema(); }
        @Override public long bytesRead() { return 0; }
        @Override protected void closeReadSource() { /* Caller owns the roots. */ }

        @Override
        public boolean loadNextBatch() throws IOException {
            if (next == batches.size()) {
                return false;
            }
            prepareLoadNextBatch();
            try (ArrowRecordBatch batch = new VectorUnloader(batches.get(next++)).getRecordBatch()) {
                loadRecordBatch(batch);
            }
            return true;
        }

        int batchesProduced() { return next; }
    }

    private static VectorSchemaRoot idBatch(RootAllocator allocator, long... ids) {
        BigIntVector vector = new BigIntVector("id", allocator);
        vector.allocateNew(ids.length);
        for (int i = 0; i < ids.length; i++) {
            vector.setSafe(i, ids[i]);
        }
        vector.setValueCount(ids.length);
        VectorSchemaRoot root = VectorSchemaRoot.of(vector);
        root.setRowCount(ids.length);
        return root;
    }

    @Test
    void readerInputConsumesSeveralBatchesOnlyWhenQueried() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory();
                VectorSchemaRoot first = idBatch(allocator, 1, 2);
                VectorSchemaRoot second = idBatch(allocator, 3, 4, 5)) {
            MultipleBatchReader reader = new MultipleBatchReader(allocator, first, second);
            try (RegisteredArrowSource source = session.registerOwnedReader(reader, allocator)) {
                assertEquals(0, reader.batchesProduced(), "registration must not read all input");
                try (ArrowReader result = session.queryArrowMaterialized(
                        "SELECT sum(id) AS total, count() AS n FROM " + source.fromSql(), allocator)) {
                    assertTrue(result.loadNextBatch());
                    assertEquals(15L,
                            ((BigIntVector) result.getVectorSchemaRoot().getVector("total")).get(0));
                    assertEquals(5L,
                            ((Number) result.getVectorSchemaRoot().getVector("n").getObject(0))
                                    .longValue());
                }
                assertEquals(2, reader.batchesProduced());
            }
            assertEquals(5L, ((BigIntVector) second.getVector("id")).get(2));
        }
    }

    @Test
    void inputCallbackFailureReleasesRegistrationAndConnectionSlot() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory();
                VectorSchemaRoot root = idBatch(allocator, 7)) {
            OneBatchReader failing = new OneBatchReader(allocator, root) {
                @Override public boolean loadNextBatch() throws IOException {
                    throw new IOException("deliberate input callback failure");
                }
            };
            try (RegisteredArrowSource source = session.registerOwnedReader(failing, allocator)) {
                assertThrows(SQLException.class, () -> session.queryArrowMaterialized(
                        "SELECT id FROM " + source.fromSql(), allocator));
            }
            try (ArrowReader next = session.queryArrowMaterialized("SELECT toInt64(17) AS id", allocator)) {
                assertTrue(next.loadNextBatch());
                assertEquals(17L, ((BigIntVector) next.getVectorSchemaRoot().getVector("id")).get(0));
            }
        }
    }

    @Test
    void closeFalseStillReleasesLazyNativeHandle() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            ArrowQuery query = session.queryArrow("SELECT number FROM numbers(200000)", allocator);
            assertTrue(query.loadNextBatch());
            query.close(false); // ArrowReader exposes this overload to callers.
            assertEquals(0L, ChdbNative.openHandleCount(ChdbNative.KIND_ARROW_STREAM));
            try (ArrowReader next = session.queryArrowMaterialized("SELECT toInt64(19) AS id", allocator)) {
                assertTrue(next.loadNextBatch());
            }
        }
    }

    @Test
    void registeredReaderCanBeScannedWithoutIpc() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            BigIntVector values = new BigIntVector("id", allocator);
            values.allocateNew(2);
            values.setSafe(0, 7);
            values.setSafe(1, 9);
            values.setValueCount(2);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(values)) {
                root.setRowCount(2);
                try (RegisteredArrowSource source = session.registerOwnedReader(
                        new OneBatchReader(allocator, root), allocator)) {
                    try (ArrowReader result = session.queryArrowMaterialized(
                            "SELECT sum(id) AS total FROM " + source.fromSql(), allocator)) {
                        assertTrue(result.loadNextBatch());
                        assertEquals(16L,
                                ((BigIntVector) result.getVectorSchemaRoot().getVector("total")).get(0));
                        assertFalse(result.loadNextBatch());
                    }
                }
            }
        }
    }

    @Test
    void batchAdapterLeavesCallerRootOpen() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            BigIntVector values = new BigIntVector("id", allocator);
            values.allocateNew(1);
            values.setSafe(0, 23);
            values.setValueCount(1);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(values)) {
                root.setRowCount(1);
                try (RegisteredArrowSource source = session.registerBatch(root, allocator);
                        ArrowReader result = session.queryArrowMaterialized(
                                "SELECT id FROM " + source.fromSql(), allocator)) {
                    assertTrue(result.loadNextBatch());
                    assertEquals(23L,
                            ((BigIntVector) result.getVectorSchemaRoot().getVector("id")).get(0));
                }
                assertEquals(23L, values.get(0));
            }
        }
    }

    @Test
    void closeDuringScanWaitsForBorrowedReader() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch continueReading = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            BigIntVector values = new BigIntVector("id", allocator);
            values.allocateNew(1);
            values.setSafe(0, 31);
            values.setValueCount(1);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(values)) {
                root.setRowCount(1);
                OneBatchReader reader = new OneBatchReader(allocator, root) {
                    @Override
                    public boolean loadNextBatch() throws IOException {
                        reading.countDown();
                        try {
                            if (!continueReading.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("test reader timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("test reader interrupted", e);
                        }
                        return super.loadNextBatch();
                    }
                };
                try (RegisteredArrowSource source = session.registerOwnedReader(reader, allocator)) {
                    Future<Long> query = executor.submit(() -> {
                        try (ArrowReader result = session.queryArrowMaterialized(
                                "SELECT id FROM " + source.fromSql(), allocator)) {
                            assertTrue(result.loadNextBatch());
                            return ((BigIntVector) result.getVectorSchemaRoot().getVector("id")).get(0);
                        }
                    });
                    assertTrue(reading.await(10, TimeUnit.SECONDS));
                    Future<?> close = executor.submit(() -> {
                        source.close();
                        return null;
                    });
                    try {
                        assertThrows(TimeoutException.class, () -> close.get(150, TimeUnit.MILLISECONDS));
                    } finally {
                        continueReading.countDown();
                    }
                    assertEquals(31L, query.get(10, TimeUnit.SECONDS));
                    close.get(10, TimeUnit.SECONDS);
                } finally {
                    continueReading.countDown();
                }
            }
        } finally {
            continueReading.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeDuringLazyFetchWaitsBeforeUnregistering() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch continueReading = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            BigIntVector values = new BigIntVector("id", allocator);
            values.allocateNew(1);
            values.setSafe(0, 37);
            values.setValueCount(1);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(values)) {
                root.setRowCount(1);
                OneBatchReader reader = new OneBatchReader(allocator, root) {
                    @Override public boolean loadNextBatch() throws IOException {
                        reading.countDown();
                        try {
                            if (!continueReading.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("lazy reader timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("lazy reader interrupted", e);
                        }
                        return super.loadNextBatch();
                    }
                };
                try (RegisteredArrowSource source = session.registerOwnedReader(reader, allocator)) {
                    ArrowQuery query = session.queryArrow("SELECT id FROM " + source.fromSql(), allocator);
                    Future<Long> fetch = executor.submit(() -> {
                        assertTrue(query.loadNextBatch());
                        return ((BigIntVector) query.getVectorSchemaRoot().getVector("id")).get(0);
                    });
                    assertTrue(reading.await(10, TimeUnit.SECONDS));
                    Future<?> close = executor.submit(() -> {
                        source.close();
                        return null;
                    });
                    try {
                        assertThrows(TimeoutException.class, () -> close.get(150, TimeUnit.MILLISECONDS));
                    } finally {
                        continueReading.countDown();
                    }
                    assertEquals(37L, fetch.get(10, TimeUnit.SECONDS));
                    close.get(10, TimeUnit.SECONDS);
                } finally {
                    continueReading.countDown();
                }
            }
        } finally {
            continueReading.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void materializedQueryPreservesValuesNullAndUtf8() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory();
                ArrowReader reader = session.queryArrowMaterialized(
                        "SELECT toInt64(number + 10) AS id, "
                                + "if(number = 0, CAST(NULL, 'Nullable(String)'), "
                                + "CAST('你好', 'Nullable(String)')) AS name FROM numbers(2)",
                        allocator)) {
            assertTrue(reader.loadNextBatch());
            assertEquals(2, reader.getVectorSchemaRoot().getRowCount());
            BigIntVector ids = (BigIntVector) reader.getVectorSchemaRoot().getVector("id");
            VarCharVector names = (VarCharVector) reader.getVectorSchemaRoot().getVector("name");
            assertEquals(10L, ids.get(0));
            assertEquals(11L, ids.get(1));
            assertTrue(names.isNull(0));
            assertEquals("你好", names.getObject(1).toString());
            assertFalse(reader.loadNextBatch());
        }
    }

    @Test
    void emptyResultHasSchemaAndErrorsDoNotPoisonConnection() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            assertThrows(SQLException.class,
                    () -> session.queryArrowMaterialized("SELECT * FROM missing_arrow_table", allocator));
            try (ArrowReader reader = session.queryArrowMaterialized(
                    "SELECT toInt64(1) AS id WHERE 0", allocator)) {
                assertEquals("id", reader.getVectorSchemaRoot().getSchema().getFields().get(0).getName());
                assertFalse(reader.loadNextBatch());
            }
        }
    }

    @Test
    void materializedStreamOutlivesTheSession() throws Exception {
        try (RootAllocator allocator = new RootAllocator()) {
            ArrowReader reader;
            try (ArrowSession session = ArrowSession.openInMemory()) {
                reader = session.queryArrowMaterialized("SELECT toInt64(42) AS id", allocator);
            }
            try (ArrowReader owned = reader) {
                assertTrue(owned.loadNextBatch());
                assertEquals(42L,
                        ((BigIntVector) owned.getVectorSchemaRoot().getVector("id")).get(0));
                assertFalse(owned.loadNextBatch());
            }
        }
    }

    @Test
    void streamedQueryReadsMultipleBatchesWithoutMaterializingFirst() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory();
                ArrowQuery query = session.queryArrow(
                        "SELECT number AS id FROM numbers(200000)", allocator)) {
            assertEquals("id", query.getVectorSchemaRoot().getSchema().getFields().get(0).getName());
            int batches = 0;
            int rows = 0;
            while (query.loadNextBatch()) {
                rows += query.getVectorSchemaRoot().getRowCount();
                batches++;
            }
            assertEquals(200000, rows);
            assertTrue(batches > 1, "expected more than one core fetch");
            assertFalse(query.loadNextBatch());
        }
    }

    @Test
    void streamedEmptyResultRetainsSchemaAndPartialCloseFreesConnection() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            try (ArrowQuery empty = session.queryArrow(
                    "SELECT toInt64(1) AS id WHERE 0", allocator)) {
                assertEquals("id", empty.getVectorSchemaRoot().getSchema().getFields().get(0).getName());
                assertFalse(empty.loadNextBatch());
            }
            try (ArrowQuery partial = session.queryArrow(
                    "SELECT number FROM numbers(200000)", allocator)) {
                assertTrue(partial.loadNextBatch());
            }
            try (ArrowReader next = session.queryArrowMaterialized("SELECT toInt64(8) AS id", allocator)) {
                assertTrue(next.loadNextBatch());
                assertEquals(8L, ((BigIntVector) next.getVectorSchemaRoot().getVector("id")).get(0));
            }
        }
    }

    @Test
    void closingSourceAlsoFinishesLazyQueryBeforeUnregister() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            BigIntVector values = new BigIntVector("id", allocator);
            values.allocateNew(1);
            values.setSafe(0, 41);
            values.setValueCount(1);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(values)) {
                root.setRowCount(1);
                try (RegisteredArrowSource source = session.registerBatch(root, allocator)) {
                    ArrowQuery query = session.queryArrow("SELECT id FROM " + source.fromSql(), allocator);
                    assertTrue(query.loadNextBatch());
                    source.close(); // The query is still open, so this must end it first.
                    try (ArrowReader next = session.queryArrowMaterialized(
                            "SELECT toInt64(9) AS id", allocator)) {
                        assertTrue(next.loadNextBatch());
                    }
                }
            }
        }
    }

    @Test
    void cancelAndFailedOpenReleaseTheStatementSlot() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            try (ArrowQuery failed = session.queryArrow(
                    "SELECT * FROM missing_arrow_stream_table", allocator)) {
                // Streaming init can defer SQL execution/error until the first fetch.
                assertThrows(IOException.class, failed::loadNextBatch);
            }
            try (ArrowQuery query = session.queryArrow(
                    "SELECT number FROM numbers(200000)", allocator)) {
                assertTrue(query.loadNextBatch());
                query.cancel();
            }
            try (ArrowReader next = session.queryArrowMaterialized("SELECT toInt64(12) AS id", allocator)) {
                assertTrue(next.loadNextBatch());
                assertEquals(12L, ((BigIntVector) next.getVectorSchemaRoot().getVector("id")).get(0));
            }
        }
    }

    @Test
    void insertBatchMapsNamedColumnsAndPreservesCallerRoot() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                Connection ddl = DriverManager.getConnection("jdbc:chdb::memory:");
                ArrowSession session = ArrowSession.openInMemory()) {
            try (Statement statement = ddl.createStatement()) {
                statement.execute("CREATE TABLE arrow_java_insert_test (id Int64, name Nullable(String)) "
                        + "ENGINE=MergeTree ORDER BY id");
            }
            VarCharVector names = new VarCharVector("name", allocator);
            BigIntVector ids = new BigIntVector("id", allocator);
            names.allocateNew();
            ids.allocateNew(2);
            names.setNull(0);
            names.setSafe(1, "你好".getBytes(StandardCharsets.UTF_8));
            ids.setSafe(0, 1);
            ids.setSafe(1, 2);
            names.setValueCount(2);
            ids.setValueCount(2);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(names, ids)) {
                root.setRowCount(2);
                assertEquals(2L, session.insertBatch("arrow_java_insert_test", root, allocator));
                assertEquals(2L, ids.get(1));
                assertThrows(IllegalArgumentException.class,
                        () -> session.insertBatch("arrow_java_insert_test;DROP TABLE x", root, allocator));
            }
            try (ArrowReader result = session.queryArrowMaterialized(
                    "SELECT id, name FROM arrow_java_insert_test ORDER BY id", allocator)) {
                assertTrue(result.loadNextBatch());
                assertEquals(2, result.getVectorSchemaRoot().getRowCount());
                VarCharVector out = (VarCharVector) result.getVectorSchemaRoot().getVector("name");
                assertTrue(out.isNull(0));
                assertEquals("你好", out.getObject(1).toString());
            }
        }
    }

    @Test
    void failedInsertIntoMissingTableDoesNotConsumeCallerBatch() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory();
                VectorSchemaRoot root = idBatch(allocator, 61, 62)) {
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThrows(SQLException.class,
                        () -> session.insertBatch("missing_arrow_insert_destination", root, allocator));
                assertEquals(62L, ((BigIntVector) root.getVector("id")).get(1));
            }
            try (ArrowReader next = session.queryArrowMaterialized("SELECT toInt64(63) AS id", allocator)) {
                assertTrue(next.loadNextBatch());
            }
        }
    }

    @Test
    @Disabled("core 26.11.0-rc.3 ArrowStream inserts invalid String into Int64 as 0; "
            + "ordinary INSERT SELECT rejects it with CANNOT_PARSE_TEXT")
    void invalidInsertValueReportsErrorAndKeepsSessionUsable() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                Connection ddl = DriverManager.getConnection("jdbc:chdb::memory:");
                ArrowSession session = ArrowSession.openInMemory()) {
            try (Statement statement = ddl.createStatement()) {
                statement.execute("CREATE TABLE arrow_java_bad_insert_test (id Int64) "
                        + "ENGINE=MergeTree ORDER BY id");
            }
            VarCharVector ids = new VarCharVector("id", allocator);
            ids.allocateNew();
            ids.setSafe(0, "not-an-integer".getBytes(StandardCharsets.UTF_8));
            ids.setValueCount(1);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(ids)) {
                root.setRowCount(1);
                assertThrows(SQLException.class,
                        () -> session.insertBatch("arrow_java_bad_insert_test", root, allocator));
                assertEquals("not-an-integer", ids.getObject(0).toString());
            }
            try (ArrowReader next = session.queryArrowMaterialized("SELECT toInt64(71) AS id", allocator)) {
                assertTrue(next.loadNextBatch());
            }
        }
    }

    @Test
    void duplicateSourceColumnNamesAreRejectedBeforeNativeInsert() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            BigIntVector first = new BigIntVector("id", allocator);
            BigIntVector second = new BigIntVector("id", allocator);
            first.allocateNew(1);
            second.allocateNew(1);
            first.setSafe(0, 1);
            second.setSafe(0, 2);
            first.setValueCount(1);
            second.setValueCount(1);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(first, second)) {
                root.setRowCount(1);
                assertThrows(IllegalArgumentException.class,
                        () -> session.insertBatch("arrow_java_bad_insert_test", root, allocator));
                assertEquals(1L, first.get(0));
            }
        }
    }

    @Test
    void outputTypeOptionsAreExplicitForMaterializedAndStreamedQueries() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            String sql = "SELECT toString('abc') AS text, "
                    + "toDateTime('2020-01-02 03:04:05') AS dt, "
                    + "toDecimal64(12.34, 2) AS amount";
            try (ArrowReader defaults = session.queryArrowMaterialized(sql, allocator)) {
                Schema schema = defaults.getVectorSchemaRoot().getSchema();
                assertTrue(schema.getFields().get(0).getType() instanceof ArrowType.Utf8);
                assertTrue(schema.getFields().get(1).getType() instanceof ArrowType.Int);
                assertTrue(schema.getFields().get(2).getType() instanceof ArrowType.Decimal);
                assertTrue(defaults.loadNextBatch());
            }
            ArrowOptions binaryString = new ArrowOptions(false, false, false);
            try (ArrowReader binary = session.queryArrowMaterialized(sql, allocator, binaryString)) {
                assertTrue(binary.getVectorSchemaRoot().getSchema().getFields().get(0).getType()
                        instanceof ArrowType.Binary);
            }
            try (ArrowQuery streamed = session.queryArrow(sql, allocator, binaryString)) {
                assertTrue(streamed.getVectorSchemaRoot().getSchema().getFields().get(0).getType()
                        instanceof ArrowType.Binary);
                assertTrue(streamed.loadNextBatch());
            }
        }
    }

    @Test
    void dictionaryTimestampAndNestedTypesHaveObservableSchemas() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            String sql = "SELECT CAST('x', 'LowCardinality(String)') AS lc, "
                    + "toDateTime64('2020-01-02 03:04:05', 3, 'UTC') AS ts, [1,2] AS arr";
            try (ArrowReader defaults = session.queryArrowMaterialized(sql, allocator)) {
                Schema schema = defaults.getVectorSchemaRoot().getSchema();
                assertTrue(schema.getFields().get(0).getType() instanceof ArrowType.Utf8);
                assertTrue(schema.getFields().get(1).getType() instanceof ArrowType.Timestamp);
                assertTrue(schema.getFields().get(2).getType() instanceof ArrowType.List);
            }
            try (ArrowQuery dictionaries = session.queryArrow(
                    sql, allocator, new ArrowOptions(false, true, true))) {
                assertTrue(dictionaries.getVectorSchemaRoot().getSchema().getFields().get(0)
                        .getDictionary() != null);
                assertTrue(dictionaries.loadNextBatch());
            }
        }
    }

    @Test
    void dictionarySchemaIsStableAcrossStreamedBatches() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory();
                ArrowQuery query = session.queryArrow(
                        "SELECT toLowCardinality(if(number % 2 = 0, 'even', 'odd')) AS lc "
                                + "FROM numbers(200000)",
                        allocator, new ArrowOptions(false, true, true))) {
            Schema initial = query.getVectorSchemaRoot().getSchema();
            assertTrue(initial.getFields().get(0).getDictionary() != null);
            int batches = 0;
            int rows = 0;
            while (query.loadNextBatch()) {
                assertEquals(initial, query.getVectorSchemaRoot().getSchema());
                rows += query.getVectorSchemaRoot().getRowCount();
                batches++;
            }
            assertEquals(200000, rows);
            assertTrue(batches > 1);
        }
    }

    @Test
    void jdbcConnectionCloseCascadesToArrowQueryAndBorrowedSource() throws Exception {
        try (RootAllocator allocator = new RootAllocator();
                ArrowSession session = ArrowSession.openInMemory()) {
            BigIntVector values = new BigIntVector("id", allocator);
            values.allocateNew(1);
            values.setSafe(0, 53);
            values.setValueCount(1);
            try (VectorSchemaRoot root = VectorSchemaRoot.of(values)) {
                root.setRowCount(1);
                RegisteredArrowSource source = session.registerBatch(root, allocator);
                session.queryArrow("SELECT id FROM " + source.fromSql(), allocator);
                // Exercise JDBC's independent close/shutdown route, which cannot be invoked
                // through ArrowSession's public interface. The reflection is test-only.
                Field field = ArrowSession.class.getDeclaredField("connection");
                field.setAccessible(true);
                ((ChdbConnection) field.get(session)).close();
                assertThrows(IllegalStateException.class, source::fromSql);
                assertThrows(IllegalStateException.class,
                        () -> session.queryArrow("SELECT 1", allocator));
            }
        }
    }
}
