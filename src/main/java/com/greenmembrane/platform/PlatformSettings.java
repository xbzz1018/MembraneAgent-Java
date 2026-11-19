package com.greenmembrane.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record PlatformSettings(
    boolean demo,
    String demoPassword,
    String jwtSecret,
    String storage,
    String execution,
    String modelMode,
    String modelBaseUrl,
    String modelApiKey,
    String modelName,
    String queryAdapter,
    String reviewAdapter,
    String embeddingModel,
    int embeddingDimensions,
    boolean vectorEnabled,
    boolean localModel,
    String embeddingBaseUrl,
    String embeddingApiKey,
    String rerankerModel,
    String rerankerBaseUrl,
    String rerankerApiKey) {}
