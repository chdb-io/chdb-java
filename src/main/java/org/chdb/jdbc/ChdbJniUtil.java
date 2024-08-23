package org.chdb.jdbc;

public class ChdbJniUtil {
  static {
    System.loadLibrary("chdbjni");
  }

  public static native LocalResultV2 executeQuery(String query);
//  public static native String executeQuery(String query);

//  public static void main(String[] args) {
//    String query = "SELECT 1";
//    System.out.println(executeQuery(query));
//  }

}

