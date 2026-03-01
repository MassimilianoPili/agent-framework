package com.agentframework.gp.config;

import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.math.RbfKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the GP engine.
 *
 * <p>Activates when {@code gp.enabled=true} (default: false — opt-in).
 * Creates the {@link RbfKernel} and {@link GaussianProcessEngine} beans.</p>
 *
 * <p>No {@code @ComponentScan}: the gp-engine module has no {@code @Component} classes.
 * All beans are explicitly declared here.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "gp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GpProperties.class)
public class GpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GpAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public RbfKernel rbfKernel(GpProperties props) {
        var k = props.kernel();
        double sv = k != null ? k.signalVariance() : 1.0;
        double ls = k != null ? k.lengthScale() : 1.0;
        log.info("[GP] RBF kernel: signalVariance={}, lengthScale={}", sv, ls);
        return new RbfKernel(sv, ls);
    }

    @Bean
    @ConditionalOnMissingBean
    public GaussianProcessEngine gaussianProcessEngine(RbfKernel kernel, GpProperties props) {
        log.info("[GP] Engine: noiseVariance={}, maxTrainingSize={}", props.noiseVariance(), props.maxTrainingSize());
        return new GaussianProcessEngine(kernel, props.noiseVariance());
    }
}
