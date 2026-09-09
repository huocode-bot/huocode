package io.huocode.api.model;

public record FileScore(
    String path,
    int complexityPercentile,
    int churnPercentile,
    int codeHealthScore,
    QuadrantKind quadrant) {}
