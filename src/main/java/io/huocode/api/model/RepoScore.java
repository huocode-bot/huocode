package io.huocode.api.model;

import java.util.List;
import java.util.Map;

public record RepoScore(
    boolean relativeScoringEnabled,
    int repoHealthScore,
    Map<QuadrantKind, Integer> quadrantCounts,
    int unanalyzedCount,
    List<FileScore> top5,
    List<LimitationDetail> limitations,
    Map<String, FileScore> scoresByPath) {}
