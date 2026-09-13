package io.huocode.api.model;

public record FileMeasurement(
    String path,
    FileStatusKind status,
    String unsupportedLanguage,
    FileErrorKind errorCode,
    int complexity,
    Churn churn,
    boolean isTest,
    int linesOfCode) {}
