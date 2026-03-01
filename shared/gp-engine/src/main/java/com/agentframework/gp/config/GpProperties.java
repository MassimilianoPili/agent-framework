package com.agentframework.gp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the GP engine.
 * Bind from {@code gp.*} in application.yml.
 */
@ConfigurationProperties("gp")
public record GpProperties(
        @DefaultValue("false") boolean enabled,
        Kernel kernel,
        @DefaultValue("0.1") double noiseVariance,
        @DefaultValue("500") int maxTrainingSize,
        @DefaultValue("0.5") double defaultPriorMean,
        Cache cache
) {

    public record Kernel(
            @DefaultValue("1.0") double signalVariance,
            @DefaultValue("1.0") double lengthScale
    ) {}

    public record Cache(
            @DefaultValue("5") int ttlMinutes,
            @DefaultValue("true") boolean enabled
    ) {}
}
