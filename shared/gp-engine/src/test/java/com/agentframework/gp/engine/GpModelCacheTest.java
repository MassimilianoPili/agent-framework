package com.agentframework.gp.engine;

import com.agentframework.gp.math.CholeskyDecomposition;
import com.agentframework.gp.math.DenseMatrix;
import com.agentframework.gp.math.RbfKernel;
import com.agentframework.gp.model.GpPosterior;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class GpModelCacheTest {

    private GpPosterior dummyPosterior() {
        // Minimal valid posterior for cache testing
        var spd = new DenseMatrix(new double[][]{{2.0}});
        var chol = new CholeskyDecomposition(spd);
        return new GpPosterior(
                new double[]{0.5},
                chol,
                new float[][]{{1.0f}},
                0.5,
                new RbfKernel(1.0, 1.0)
        );
    }

    @Test
    void get_afterPut_returnsValue() {
        var cache = new GpModelCache(Duration.ofMinutes(5));
        var posterior = dummyPosterior();
        cache.put("BE:be-java", posterior);
        assertThat(cache.get("BE:be-java")).isSameAs(posterior);
    }

    @Test
    void get_missingKey_returnsNull() {
        var cache = new GpModelCache(Duration.ofMinutes(5));
        assertThat(cache.get("BE:be-go")).isNull();
    }

    @Test
    void get_afterExpiry_returnsNull() throws InterruptedException {
        var cache = new GpModelCache(Duration.ofMillis(50));
        cache.put("BE:be-java", dummyPosterior());
        Thread.sleep(100); // wait for expiry
        assertThat(cache.get("BE:be-java")).isNull();
    }

    @Test
    void invalidate_removesEntry() {
        var cache = new GpModelCache(Duration.ofMinutes(5));
        cache.put("BE:be-java", dummyPosterior());
        cache.invalidate("BE:be-java");
        assertThat(cache.get("BE:be-java")).isNull();
    }

    @Test
    void invalidateAll_clearsAll() {
        var cache = new GpModelCache(Duration.ofMinutes(5));
        cache.put("BE:be-java", dummyPosterior());
        cache.put("BE:be-go", dummyPosterior());
        cache.invalidateAll();
        assertThat(cache.size()).isZero();
    }

    @Test
    void cacheKey_formatsCorrectly() {
        assertThat(GpModelCache.cacheKey("BE", "be-java")).isEqualTo("BE:be-java");
    }
}
