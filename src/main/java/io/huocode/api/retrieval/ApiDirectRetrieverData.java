package io.huocode.api.retrieval;

import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import java.util.List;
import java.util.Map;

public record ApiDirectRetrieverData(
    String sha,
    List<String> files,
    Map<String, String> contents,
    Map<String, Churn> churnByPath,
    AnalysisWindowData window) {}
