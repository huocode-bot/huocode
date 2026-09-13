package io.huocode.api.model;

public record Churn(int commits, int authors, int linesAdded, int linesDeleted) {

  public int effectiveLines() {
    return linesAdded + linesDeleted;
  }

  public static final Churn ZERO = new Churn(0, 0, 0, 0);
}
