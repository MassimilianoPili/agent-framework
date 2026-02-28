package com.agentframework.orchestrator.service;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.repository.PlanSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Creates and restores plan snapshots (Memento pattern).
 *
 * <p>A snapshot captures the full state of a plan and all its items
 * as a JSON document, enabling checkpoint/restore for crash recovery
 * and debugging.</p>
 */
@Service
public class PlanSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(PlanSnapshotService.class);

    private final PlanRepository planRepository;
    private final PlanItemRepository planItemRepository;
    private final PlanSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public PlanSnapshotService(PlanRepository planRepository,
                               PlanItemRepository planItemRepository,
                               PlanSnapshotRepository snapshotRepository,
                               ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.planItemRepository = planItemRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a snapshot of the plan's current state.
     */
    @Transactional
    public PlanSnapshot snapshot(UUID planId, String label) {
        Plan plan = planRepository.findById(planId)
            .orElseThrow(() -> new IllegalStateException("Plan not found: " + planId));

        List<PlanItem> items = planItemRepository.findByPlanId(planId);

        String planData = serialize(plan, items);

        PlanSnapshot snapshot = new PlanSnapshot(UUID.randomUUID(), plan, label, planData);
        snapshot = snapshotRepository.save(snapshot);

        log.info("Snapshot '{}' created for plan {} ({} items)", label, planId, items.size());
        return snapshot;
    }

    /**
     * Restores a plan to the state captured in the given snapshot.
     * Resets item statuses, results, and failure reasons to their snapshot values.
     */
    @Transactional
    public void restore(UUID snapshotId) {
        PlanSnapshot snapshot = snapshotRepository.findById(snapshotId)
            .orElseThrow(() -> new IllegalStateException("Snapshot not found: " + snapshotId));

        try {
            var root = objectMapper.readTree(snapshot.getPlanData());
            UUID planId = UUID.fromString(root.get("planId").asText());
            String planStatus = root.get("status").asText();

            Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("Plan not found: " + planId));

            plan.transitionTo(PlanStatus.valueOf(planStatus));
            planRepository.save(plan);

            var itemsNode = root.get("items");
            if (itemsNode != null && itemsNode.isArray()) {
                for (var itemNode : itemsNode) {
                    UUID itemId = UUID.fromString(itemNode.get("id").asText());
                    PlanItem item = planItemRepository.findById(itemId).orElse(null);
                    if (item == null) continue;

                    ItemStatus targetStatus = ItemStatus.valueOf(itemNode.get("status").asText());
                    // Force-set status for restore (bypass state machine)
                    item.transitionTo(targetStatus);

                    item.setResult(itemNode.has("result") && !itemNode.get("result").isNull()
                            ? itemNode.get("result").asText() : null);
                    item.setFailureReason(itemNode.has("failureReason") && !itemNode.get("failureReason").isNull()
                            ? itemNode.get("failureReason").asText() : null);

                    planItemRepository.save(item);
                }
            }

            log.info("Plan {} restored from snapshot '{}' (id={})",
                     planId, snapshot.getLabel(), snapshotId);

        } catch (Exception e) {
            throw new RuntimeException("Failed to restore snapshot " + snapshotId, e);
        }
    }

    /**
     * Lists all snapshots for a plan.
     */
    @Transactional(readOnly = true)
    public List<PlanSnapshot> listSnapshots(UUID planId) {
        return snapshotRepository.findByPlanIdOrderByCreatedAtAsc(planId);
    }

    private String serialize(Plan plan, List<PlanItem> items) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("planId", plan.getId().toString());
            data.put("status", plan.getStatus().name());
            data.put("spec", plan.getSpec());

            List<Map<String, Object>> itemData = items.stream()
                .map(item -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", item.getId().toString());
                    m.put("taskKey", item.getTaskKey());
                    m.put("status", item.getStatus().name());
                    m.put("workerType", item.getWorkerType().name());
                    m.put("workerProfile", item.getWorkerProfile());
                    m.put("result", item.getResult());
                    m.put("failureReason", item.getFailureReason());
                    return m;
                })
                .collect(Collectors.toList());

            data.put("items", itemData);
            return objectMapper.writeValueAsString(data);

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize plan " + plan.getId(), e);
        }
    }
}
