package io.huocode.api.file.hash;

import io.huocode.api.PojaGenerated;

@PojaGenerated
public record FileHash(FileHashAlgorithm algorithm, String value) {}
