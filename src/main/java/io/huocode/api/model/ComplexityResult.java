package io.huocode.api.model;

import java.util.List;
import java.util.Map;

public record ComplexityResult(
    Map<String, Integer> complexityByPath, List<String> parseErrorPaths) {}
