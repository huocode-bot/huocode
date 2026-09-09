package io.huocode.api.model;

public record Churn(int commits, int authors) {

  public static final Churn ZERO = new Churn(0, 0);
}
