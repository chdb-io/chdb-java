package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.shardingsphere.database.connector.core.exception.UnrecognizedDatabaseURLException;
import org.apache.shardingsphere.database.connector.core.jdbcurl.parser.StandardJdbcUrlParser;
import org.apache.shardingsphere.database.connector.core.type.DatabaseType;
import org.apache.shardingsphere.database.connector.core.type.DatabaseTypeFactory;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Why ShardingSphere 5.5.3 cannot be put in front of this driver (issue #11).
 *
 * <p>Issue #11 wants ShardingSphere covered because it opens and manages many connections
 * itself, which puts the storage-path registry under an owner other than the application. That
 * turned out not to be testable, and the reason is worth a test of its own rather than a line in
 * a report, because it is upstream's and it is one line from being fixed.
 *
 * <p>ShardingSphere already knows about chDB: {@code ClickHouseDatabaseType} in 5.5.3 lists
 * {@code jdbc:chdb} among its JDBC URL prefixes, alongside {@code jdbc:clickhouse:} and
 * {@code jdbc:ch:}, so a chDB URL is routed to its ClickHouse dialect without being told to.
 * But every {@code DatabaseType} then parses the URL through
 * {@link StandardJdbcUrlParser}, which requires the {@code //authority} component of a
 * client/server URL to be present, and refuses the URL outright when it is not. An embedded
 * engine's URL has no host and no port, so no chDB URL of any form can get through --
 * {@code :memory:} and a filesystem storage path fail identically.
 *
 * <p>This is not something the driver can work around. Inventing a {@code //localhost/} in the
 * URL to satisfy the parser would make the text after it the storage path, so the driver would
 * open a database in a directory named after a host that is not there.
 *
 * <p>What is therefore not covered, and would be if this were fixed: ShardingSphere's own
 * connection management against the one-storage-path-per-JVM rule. The nearest thing that is
 * covered is {@code HikariPoolIT} -- ShardingSphere's pools are HikariCP, and the registry
 * behaviour under pool churn is what that test exercises.
 */
class ShardingSphereIT extends NativeTestBase {

    /** The URL forms this driver accepts, per {@code org.chdb.jdbc.ChdbUrl}. */
    private static final String[] CHDB_URLS = {
        "jdbc:chdb:", "jdbc:chdb::memory:", "jdbc:chdb:/tmp/chdb-store",
    };

    @Test
    @DisplayName("ShardingSphere routes jdbc:chdb to its ClickHouse dialect")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void chdbIsARecognisedClickHousePrefix() {
        DatabaseType clickHouse = DatabaseTypeFactory.get("jdbc:clickhouse://localhost:8123/x");
        assertEquals("ClickHouse", clickHouse.getType());
        Collection<String> prefixes = clickHouse.getJdbcUrlPrefixes();
        assertTrue(
                prefixes.contains("jdbc:chdb"),
                "ShardingSphere 5.5.3 used to claim jdbc:chdb; it now claims " + prefixes);
        // And the routing actually happens for a chDB URL, so the failure below is inside the
        // ClickHouse connector rather than a "no dialect matched" fallback.
        assertEquals("ClickHouse", DatabaseTypeFactory.get("jdbc:chdb::memory:").getType());
    }

    @Test
    @DisplayName("ShardingSphere's URL parser refuses every chDB URL, for want of an authority")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void everyChdbUrlIsRefusedByTheUrlParser() {
        StandardJdbcUrlParser parser = new StandardJdbcUrlParser();
        for (String url : CHDB_URLS) {
            // The blocker, asserted per URL form so it is clear this is not about :memory:.
            assertThrows(
                    UnrecognizedDatabaseURLException.class, () -> parser.parse(url, 0), url);
        }
        // The same parser on a client/server URL, to show what it wants: the //authority group
        // has to be there. This is the assertion that will start failing when ShardingSphere
        // learns to parse an embedded URL, which is the point of keeping the test.
        assertNotNull(parser.parse("jdbc:clickhouse://localhost:8123/default", 8123));
        assertNotNull(parser.parse("jdbc:chdb://localhost/store", 0));
    }

    @Test
    @DisplayName("neither ShardingSphere entry point can build a data source over the driver")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void noConfigurationRouteAroundIt() throws Exception {
        // The programmatic factory, which is the least opinionated way in: an already-built
        // HikariCP pool over the driver, no rules, no sharding. ShardingSphere still reflects
        // the URL out of the pool to decide the storage type, so it fails in the same place as
        // a YAML configuration would -- which is why there is no shardingsphere yaml in this
        // module's test resources.
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MEMORY_URL);
        config.setDriverClassName("org.chdb.jdbc.ChdbDriver");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(20));
        config.setInitializationFailTimeout(TimeUnit.SECONDS.toMillis(20));

        try (HikariDataSource pool = new HikariDataSource(config)) {
            Map<String, DataSource> dataSources = new HashMap<>();
            dataSources.put("ds_0", pool);
            UnrecognizedDatabaseURLException e =
                    assertThrows(
                            UnrecognizedDatabaseURLException.class,
                            () ->
                                    ShardingSphereDataSourceFactory.createDataSource(
                                            "chdb_probe",
                                            null,
                                            dataSources,
                                            Collections.emptyList(),
                                            new Properties()));
            assertTrue(String.valueOf(e.getMessage()).contains(MEMORY_URL), e.getMessage());
        }
    }
}
