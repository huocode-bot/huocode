package io.huocode.api.retrieval;

import io.huocode.api.model.RepoChurn;
import java.util.List;

public record CloneRetrieverData(String sha, List<String> files, RepoChurn churn) {}
