package org.chdb.jdbc;

public class ChdbJniUtil {
  static {
    System.loadLibrary("chdbjni");
  }

  public static LocalResultV2 executeQuery(String query) {
    return executeQuery(query, "CSV");
  }

  public static native LocalResultV2 executeQuery(String query, String format);

//  public static void main(String[] args) {
//    String query = "SELECT 1";
//    System.out.println(executeQuery(query));
//  }

}

