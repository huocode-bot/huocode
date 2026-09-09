package io.huocode.api.model;

import java.util.Map;

public record RepoChurn(Map<String, Churn> churnByPath, AnalysisWindowData window) {}
