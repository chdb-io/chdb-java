package org.chdb.jdbc;

import java.nio.ByteBuffer;

public class LocalResultV2 {
  private ByteBuffer buf;
  private long rowsRead;
  private long bytesRead;
  private double elapsed;
  private String errorMessage;

  public LocalResultV2() {
  }

  public LocalResultV2(ByteBuffer buf, long rowsRead, long bytesRead, double elapsed, String errorMessage) {
    this.buf = buf;
    this.rowsRead = rowsRead;
    this.bytesRead = bytesRead;
    this.elapsed = elapsed;
    this.errorMessage = errorMessage;
  }

  public ByteBuffer getBuf() {
    return buf;
  }

  public long getRowsRead() {
    return rowsRead;
  }

  public long getBytesRead() {
    return bytesRead;
  }

  public double getElapsed() {
    return elapsed;
  }

  public String getErrorMessage() {
    return errorMessage;
  }
}
