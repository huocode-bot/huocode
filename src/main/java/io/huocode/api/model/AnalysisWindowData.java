package io.huocode.api.model;

import java.time.Instant;

public record AnalysisWindowData(
    int commitsAnalyzed, Instant oldestCommitDate, Instant newestCommitDate, int distinctAuthors) {}
