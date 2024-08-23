package org.chdb.jdbc;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

public class ChdbDriver implements Driver {
  static {
    try {
      DriverManager.registerDriver(new ChdbDriver());
    } catch (SQLException e) {
      throw new RuntimeException("Failed to register ChdbDriver", e);
    }
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      throw new SQLException(("Invalid URL: " + url + ". URL must start with jdbc:chdb"));
    }
    return new ChdbConnection(url, info);
  }

  @Override
  public boolean acceptsURL(String url) throws SQLException {
    return url != null && url.startsWith("jdbc:chdb");
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 1;
  }

  @Override
  public int getMinorVersion() {
    return 0;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger("org.chdb.jdbc");
  }
}
