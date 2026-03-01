package com.agentframework.gp.model;

import com.agentframework.gp.math.CholeskyDecomposition;
import com.agentframework.gp.math.RbfKernel;

/**
 * Cached fitted GP posterior.
 *
 * <p>Stores the pre-computed &alpha; vector = (K + &sigma;&sup2;&#8345;I)&supˆ;&sup1;(y - mean)
 * and the Cholesky factor L, ready for prediction on new test points.</p>
 *
 * <p>Immutable. Created by {@link com.agentframework.gp.engine.GaussianProcessEngine#fit},
 * consumed by {@link com.agentframework.gp.engine.GaussianProcessEngine#predict}.</p>
 *
 * @param alpha              (K + sigma_n^2 I)^{-1} (y - mean)
 * @param cholesky           L factor of (K + sigma_n^2 I)
 * @param trainingEmbeddings X (N x D) — needed for cross-kernel computation at prediction time
 * @param meanReward         prior mean (subtracted from y before fitting, added back at prediction)
 * @param kernel             kernel used for fitting (needed at prediction time)
 */
public record GpPosterior(
        double[] alpha,
        CholeskyDecomposition cholesky,
        float[][] trainingEmbeddings,
        double meanReward,
        RbfKernel kernel
) {}
