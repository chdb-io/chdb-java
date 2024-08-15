import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class ChdbJdbcTest {

  public static void main(String[] args) throws Exception {
    // Load the JDBC driver
    Class.forName("org.chdb.jdbc.ChdbDriver");

    // Establish the connection
    Connection conn = DriverManager.getConnection("jdbc:chdb");

    String query = "SELECT * from system.numbers limit 10";

    // Execute the query
    Statement st = conn.createStatement();
    ResultSet rs = st.executeQuery(query);

    while (rs.next()) {
      // Retrieve data by column index
      System.out.println("Column Value: " + rs.getString(1));
    }

    st.close();
    rs.close();
    conn.close();

  }
}
