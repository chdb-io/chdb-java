import org.chdb.jdbc.ChdbJniUtil;
import org.chdb.jdbc.LocalResultV2;

public class ChdbJniUtilTest {

  public static void main(String[] args) {

    String query = "SELECT * from system.numbers limit 10";
    LocalResultV2 result = ChdbJniUtil.executeQuery(query);
    System.out.println("Rows Read: " + result.getRowsRead());
    System.out.println("Bytes Read: " + result.getBytesRead());
    System.out.println("Elapsed: " + result.getElapsed());
    System.out.println("Error Message: " + result.getErrorMessage());
  }
}
