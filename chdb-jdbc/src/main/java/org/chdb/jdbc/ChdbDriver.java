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

        // These three configured the Arrow export the driver used to read results through.
        // It now reads RowBinaryWithNamesAndTypes, where the engine names every type as it
        // declared it, so none of them does anything.
        //
        // Reported rather than dropped, and still swallowed rather than forwarded: a property
        // the driver stops recognising would be passed to the engine as --<key>=<value> and
        // fail the connection on an unknown setting, which is a worse answer than "accepted and
        // ignored" for a URL somebody already has. docs/unsupported.md lists them.
        for (String inert :
                new String[] {
                    ChdbUrl.PROP_LOW_CARDINALITY_AS_DICTIONARY,
                    ChdbUrl.PROP_UNSUPPORTED_AS_BINARY,
                    ChdbUrl.PROP_STRING_AS_STRING
                }) {
            DriverPropertyInfo property =
                    new DriverPropertyInfo(inert, valueOf(info, inert, ""));
            property.description =
                    "Accepted and ignored. It configured the Arrow export the driver read results"
                            + " through before it moved to RowBinaryWithNamesAndTypes, where the"
                            + " engine declares every type and there is nothing to configure.";
            property.choices = new String[] {"true", "false"};
            properties.add(property);
        }

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
