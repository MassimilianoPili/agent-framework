package com.agentframework.orchestrator.analytics;

/**
 * Attribution of a single causal factor to a task outcome.
 *
 * <p>Compares observational P(success|factor=value) with interventional
 * P(success|do(factor=value)) to identify confounding and quantify
 * the true causal contribution of each factor.</p>
 *
 * @param factor             causal variable name (e.g. "worker_elo", "context_quality")
 * @param observationalP     P(success | factor = observed_value)
 * @param interventionalP    P(success | do(factor = observed_value))
 * @param causalContribution interventionalP − baselineP (positive = helpful, negative = harmful)
 * @param confounded         true if |observationalP − interventionalP| &gt; 0.1
 *
 * @see CausalDag
 * @see <a href="https://doi.org/10.1017/CBO9780511803161">
 *     Pearl (2009), Causality, 2nd ed.</a>
 */
public record CausalAttribution(
        String factor,
        double observationalP,
        double interventionalP,
        double causalContribution,
        boolean confounded
) {}
