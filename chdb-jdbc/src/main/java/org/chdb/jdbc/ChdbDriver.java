package org.chdb.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The chDB JDBC driver.
 *
 * <p>Registers itself with {@link DriverManager} both from a static initializer and through
 * {@code META-INF/services/java.sql.Driver}, so {@code DriverManager.getConnection(
 * "jdbc:chdb:...")} works without {@code Class.forName}.
 *
 * <p>Registration deliberately does not touch the native libraries. Loading them costs
 * hundreds of megabytes of mapped engine, and {@link DriverManager} instantiates every
 * driver on the classpath just to ask what URLs it accepts. The engine loads on the first
 * {@link #connect(String, Properties)} instead.
 */
public final class ChdbDriver implements Driver {

    /** Major version of this binding's public Java API, not of the engine. */
    public static final int MAJOR_VERSION = 1;

    /** Minor version of this binding's public Java API. */
    public static final int MINOR_VERSION = 0;

    static {
        try {
            DriverManager.registerDriver(new ChdbDriver());
        } catch (SQLException e) {
            // DriverManager wraps this initializer's failure in an unhelpful
            // ExceptionInInitializerError, so state plainly what went wrong.
            throw new IllegalStateException("Failed to register the chDB JDBC driver", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!ChdbUrl.accepts(url)) {
            // JDBC contract: return null rather than throwing, so DriverManager can offer the
            // URL to the next registered driver.
            return null;
        }
        return new ChdbConnection(ChdbUrl.parse(url, info));
    }

    @Override
    public boolean acceptsURL(String url) {
        return ChdbUrl.accepts(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        List<DriverPropertyInfo> properties = new ArrayList<>();

        DriverPropertyInfo lowCardinality =
                new DriverPropertyInfo(
                        ChdbUrl.PROP_LOW_CARDINALITY_AS_DICTIONARY,
                        valueOf(info, ChdbUrl.PROP_LOW_CARDINALITY_AS_DICTIONARY, "false"));
        lowCardinality.description =
                "Emit LowCardinality columns as Arrow dictionary arrays instead of materializing"
                        + " them to their base type. V1 cannot read dictionary-encoded columns, so"
                        + " turning this on makes them unreadable; it exists for diagnosis.";
        lowCardinality.choices = new String[] {"true", "false"};
        properties.add(lowCardinality);

        DriverPropertyInfo unsupportedAsBinary =
                new DriverPropertyInfo(
                        ChdbUrl.PROP_UNSUPPORTED_AS_BINARY,
                        valueOf(info, ChdbUrl.PROP_UNSUPPORTED_AS_BINARY, "false"));
        unsupportedAsBinary.description =
                "Degrade types with no faithful Arrow mapping (JSON, Dynamic, AggregateFunction) to"
                        + " binary instead of failing the query. The bytes are an engine-internal"
                        + " representation, so read them with getBytes() only.";
        unsupportedAsBinary.choices = new String[] {"true", "false"};
        properties.add(unsupportedAsBinary);

        DriverPropertyInfo stringAsString =
                new DriverPropertyInfo(
                        ChdbUrl.PROP_STRING_AS_STRING, valueOf(info, ChdbUrl.PROP_STRING_AS_STRING, "true"));
        stringAsString.description =
                "Emit String columns as Arrow utf8 (true) or Arrow binary (false). Leave it on"
                        + " unless a column holds bytes that are not valid UTF-8.";
        stringAsString.choices = new String[] {"true", "false"};
        properties.add(stringAsString);

        // Every other property is forwarded to the engine as --<key>=<value>, which is how a
        // ClickHouse setting is passed. There are hundreds and they vary by engine version, so
        // they are not enumerated here; DriverPropertyInfo has no way to say "and any setting".
        return properties.toArray(new DriverPropertyInfo[0]);
    }

    private static String valueOf(Properties info, String key, String fallback) {
        if (info == null) {
            return fallback;
        }
        String value = info.getProperty(key);
        return value == null ? fallback : value;
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    /**
     * False, and it will stay false.
     *
     * <p>JDBC compliance requires full SQL-92 entry level and the whole API surface. chDB is an
     * analytical engine with no transactions, no scrollable cursors and ClickHouse SQL rather
     * than SQL-92, so claiming compliance would be a false statement about the driver rather
     * than a formality (work plan section 2.3).
     */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException(
                "The chDB JDBC driver does not use java.util.logging", "0A000");
    }
}
