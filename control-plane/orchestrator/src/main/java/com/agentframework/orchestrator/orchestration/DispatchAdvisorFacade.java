package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.analytics.ActiveInferenceService;
import com.agentframework.orchestrator.analytics.InformationForagingService;
import com.agentframework.orchestrator.analytics.ReflectiveDispatchService;
import com.agentframework.orchestrator.analytics.StigmergyCoordinator;
import com.agentframework.orchestrator.analytics.SuperrationalityService;
import com.agentframework.orchestrator.analytics.ThompsonSamplingSelector;
import com.agentframework.orchestrator.analytics.metalearning.PlanArchetypeRegistry;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Facade that aggregates dispatch-enhancement analytics into a single advisory call.
 *
 * <p>Wraps 7 dispatch-related analytics services + 1 plan archetype service,
 * shielding {@link OrchestrationService} from 8 additional constructor parameters.
 * Each service is {@code @Nullable} (disabled by default via {@code @ConditionalOnProperty}).
 * Failures are caught and logged — the facade never throws.</p>
 *
 * <p>Called once per dispatch cycle in {@code dispatchReadyItems()}, between
 * market-making prioritization and global assignment solving.</p>
 */
@Component
public class DispatchAdvisorFacade {

    private static final Logger log = LoggerFactory.getLogger(DispatchAdvisorFacade.class);

    private final @Nullable ActiveInferenceService activeInferenceService;
    private final @Nullable ReflectiveDispatchService reflectiveDispatchService;
    private final @Nullable ThompsonSamplingSelector thompsonSamplingSelector;
    private final @Nullable InformationForagingService informationForagingService;
    private final @Nullable StigmergyCoordinator stigmergyCoordinator;
    private final @Nullable SuperrationalityService superrationalityService;
    private final @Nullable PlanArchetypeRegistry planArchetypeRegistry;

    public DispatchAdvisorFacade(
            @Nullable ActiveInferenceService activeInferenceService,
            @Nullable ReflectiveDispatchService reflectiveDispatchService,
            @Nullable ThompsonSamplingSelector thompsonSamplingSelector,
            @Nullable InformationForagingService informationForagingService,
            @Nullable StigmergyCoordinator stigmergyCoordinator,
            @Nullable SuperrationalityService superrationalityService,
            @Nullable PlanArchetypeRegistry planArchetypeRegistry) {
        this.activeInferenceService = activeInferenceService;
        this.reflectiveDispatchService = reflectiveDispatchService;
        this.thompsonSamplingSelector = thompsonSamplingSelector;
        this.informationForagingService = informationForagingService;
        this.stigmergyCoordinator = stigmergyCoordinator;
        this.superrationalityService = superrationalityService;
        this.planArchetypeRegistry = planArchetypeRegistry;
    }

    /**
     * Runs all enabled dispatch advisory analytics for the current dispatch cycle.
     * Each advisory is fire-and-forget (logged, not blocking).
     *
     * @param dispatchableItems items about to be dispatched
     * @param plan              the current plan
     * @return aggregated advice record (informational only)
     */
    public DispatchAdvice advise(List<PlanItem> dispatchableItems, Plan plan) {
        List<String> advisories = new ArrayList<>();

        // 1. Active Inference: free energy minimization per worker type
        if (activeInferenceService != null) {
            for (PlanItem item : dispatchableItems) {
                runSafely("activeInference", () -> {
                    var report = activeInferenceService.computeFreeEnergy(item.getWorkerType().name());
                    if (report != null) {
                        advisories.add(String.format("ActiveInference[%s]: freeEnergy reported", item.getTaskKey()));
                        log.debug("Active inference for {}: free energy computed for {}",
                                  item.getTaskKey(), item.getWorkerType());
                    }
                });
            }
        }

        // 2. Reflective Dispatch: FDT-optimal profile suggestion
        if (reflectiveDispatchService != null) {
            for (PlanItem item : dispatchableItems) {
                runSafely("reflectiveDispatch", () -> {
                    String embeddingText = item.getDescription() != null ? item.getDescription() : item.getTaskKey();
                    var report = reflectiveDispatchService.computeReflectivePolicy(
                            embeddingText, item.getWorkerType().name());
                    if (report != null) {
                        advisories.add(String.format("Reflective[%s]: FDT policy computed", item.getTaskKey()));
                        log.debug("Reflective dispatch for {}: FDT policy computed", item.getTaskKey());
                    }
                });
            }
        }

        // 3. Thompson Sampling: exploration signal
        if (thompsonSamplingSelector != null && !dispatchableItems.isEmpty()) {
            runSafely("thompsonSampling", () -> {
                List<String> candidates = dispatchableItems.stream()
                        .map(i -> i.getWorkerType().name())
                        .distinct()
                        .collect(Collectors.toList());
                var tsResult = thompsonSamplingSelector.sample(candidates);
                if (tsResult != null) {
                    advisories.add(String.format("ThompsonSampling: selected=%s", tsResult.selectedWorkerType()));
                    log.debug("Thompson sampling: selected={}", tsResult.selectedWorkerType());
                }
            });
        }

        // 4. Information Foraging: optimal foraging priority
        if (informationForagingService != null && !dispatchableItems.isEmpty()) {
            runSafely("informationForaging", () -> {
                List<InformationForagingService.ForagingPatch> patches = dispatchableItems.stream()
                        .map(item -> new InformationForagingService.ForagingPatch(
                                item.getTaskKey(),
                                item.getProcessScore() != null ? item.getProcessScore().doubleValue() : 0.5,
                                1.0, // unit travel cost
                                1))  // single chunk per task
                        .collect(Collectors.toList());
                var report = informationForagingService.forage(patches);
                if (report != null) {
                    advisories.add(String.format("Foraging: %d patches evaluated", patches.size()));
                    log.debug("Information foraging: {} patches evaluated", patches.size());
                }
            });
        }

        // 5. Stigmergy: pheromone trail coordination
        if (stigmergyCoordinator != null) {
            runSafely("stigmergy", () -> {
                var report = stigmergyCoordinator.analyse();
                if (report != null) {
                    advisories.add("Stigmergy: trail analysis complete");
                    log.debug("Stigmergy coordination: trail analysis complete");
                }
            });
        }

        // 6. Superrationality: game-theoretic equilibrium
        if (superrationalityService != null) {
            runSafely("superrationality", () -> {
                var report = superrationalityService.compute();
                if (report != null) {
                    advisories.add("Superrationality: equilibrium computed");
                    log.debug("Superrationality: equilibrium computed");
                }
            });
        }

        return new DispatchAdvice(advisories, advisories.size());
    }

    /**
     * Finds similar plan archetypes for a given spec (called during plan creation).
     *
     * @param spec         the plan specification text
     * @param maxResults   maximum number of matches
     * @return list of archetype matches, or empty list if service unavailable
     */
    public List<?> findSimilarArchetypes(String spec, int maxResults) {
        if (planArchetypeRegistry == null) return List.of();
        try {
            return planArchetypeRegistry.findSimilar(spec, maxResults);
        } catch (Exception e) {
            log.debug("Plan archetype lookup failed (non-blocking): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Registers a completed plan as an archetype for future reference.
     */
    public void registerArchetype(Plan plan) {
        if (planArchetypeRegistry == null) return;
        try {
            planArchetypeRegistry.registerArchetype(plan);
            log.debug("Registered plan archetype for plan {}", plan.getId());
        } catch (Exception e) {
            log.debug("Plan archetype registration failed (non-blocking): {}", e.getMessage());
        }
    }

    /**
     * Registers a failed plan as a contrastive archetype (what NOT to do).
     */
    public void registerFailedArchetype(Plan plan) {
        if (planArchetypeRegistry == null) return;
        try {
            planArchetypeRegistry.registerFailedArchetype(plan);
            log.debug("Registered failed plan archetype for plan {}", plan.getId());
        } catch (Exception e) {
            log.debug("Failed plan archetype registration failed (non-blocking): {}", e.getMessage());
        }
    }

    private void runSafely(String label, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("Dispatch advisory '{}' failed (non-blocking): {}", label, e.getMessage());
        }
    }

    /**
     * Aggregated dispatch advice (informational only, does not drive decisions).
     *
     * @param advisories list of human-readable advisory messages
     * @param count      number of advisories produced
     */
    public record DispatchAdvice(List<String> advisories, int count) {}
}
