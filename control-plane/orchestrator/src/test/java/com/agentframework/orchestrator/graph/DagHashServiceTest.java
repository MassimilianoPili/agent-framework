package com.agentframework.orchestrator.graph;

import com.agentframework.common.util.HashUtil;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DagHashService} (#45 — Merkle Tree per DAG Verification).
 */
@DisplayName("DagHashService (#45) — Merkle DAG hashing")
class DagHashServiceTest {

    private DagHashService service;

    @BeforeEach
    void setUp() {
        service = new DagHashService();
    }

    // ── recomputeHashes ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("recomputeHashes")
    class RecomputeTests {

        @Test
        @DisplayName("single item with no deps — dagHash = SHA-256(taskKey|workerType|title|)")
        void singleItem_noDeps() {
            Plan plan = plan(item("X", WorkerType.BE, "Build API", List.of()));

            service.recomputeHashes(plan);

            PlanItem x = plan.getItems().get(0);
            String expected = HashUtil.sha256("X|BE|Build API|");
            assertThat(x.getDagHash()).isEqualTo(expected);
            // X is the only node and also the only sink → merkleRoot = SHA-256(X.dagHash)
            assertThat(plan.getMerkleRoot()).isEqualTo(HashUtil.sha256(expected));
        }

        @Test
        @DisplayName("linear chain A→B→C — hash cascading, only C is sink")
        void linearChain() {
            Plan plan = plan(
                item("A", WorkerType.BE, "Task A", List.of()),
                item("B", WorkerType.FE, "Task B", List.of("A")),
                item("C", WorkerType.REVIEW, "Task C", List.of("B"))
            );

            service.recomputeHashes(plan);

            PlanItem a = byKey(plan, "A");
            PlanItem b = byKey(plan, "B");
            PlanItem c = byKey(plan, "C");

            // A has no predecessors
            assertThat(a.getDagHash()).isEqualTo(HashUtil.sha256("A|BE|Task A|"));
            // B depends on A
            assertThat(b.getDagHash()).isEqualTo(HashUtil.sha256("B|FE|Task B|" + a.getDagHash()));
            // C depends on B
            assertThat(c.getDagHash()).isEqualTo(HashUtil.sha256("C|REVIEW|Task C|" + b.getDagHash()));
            // Only C is a sink
            assertThat(plan.getMerkleRoot()).isEqualTo(HashUtil.sha256(c.getDagHash()));
        }

        @Test
        @DisplayName("diamond DAG: A→{B,C}→D — D includes sorted(B.hash,C.hash)")
        void diamondDag() {
            Plan plan = plan(
                item("A", WorkerType.BE, "Root", List.of()),
                item("B", WorkerType.FE, "Left", List.of("A")),
                item("C", WorkerType.BE, "Right", List.of("A")),
                item("D", WorkerType.REVIEW, "Merge", List.of("B", "C"))
            );

            service.recomputeHashes(plan);

            PlanItem b = byKey(plan, "B");
            PlanItem c = byKey(plan, "C");
            PlanItem d = byKey(plan, "D");

            // D's dep hashes sorted
            String sortedDepHashes = b.getDagHash().compareTo(c.getDagHash()) <= 0
                    ? b.getDagHash() + "," + c.getDagHash()
                    : c.getDagHash() + "," + b.getDagHash();

            assertThat(d.getDagHash()).isEqualTo(
                    HashUtil.sha256("D|REVIEW|Merge|" + sortedDepHashes));
            // Only D is a sink
            assertThat(plan.getMerkleRoot()).isEqualTo(HashUtil.sha256(d.getDagHash()));
        }

        @Test
        @DisplayName("multiple sinks — merkleRoot = SHA-256(sorted sink hashes)")
        void multipleSinks() {
            Plan plan = plan(
                item("A", WorkerType.BE, "Task A", List.of()),
                item("B", WorkerType.FE, "Task B", List.of()),
                item("C", WorkerType.REVIEW, "Task C", List.of())
            );

            service.recomputeHashes(plan);

            PlanItem a = byKey(plan, "A");
            PlanItem b = byKey(plan, "B");
            PlanItem c = byKey(plan, "C");

            // All three are sinks (no one depends on them)
            List<String> sinkHashes = new ArrayList<>(List.of(
                    a.getDagHash(), b.getDagHash(), c.getDagHash()));
            Collections.sort(sinkHashes);

            assertThat(plan.getMerkleRoot()).isEqualTo(
                    HashUtil.sha256(String.join(",", sinkHashes)));
        }

        @Test
        @DisplayName("empty plan — merkleRoot is null, no error")
        void emptyPlan() {
            Plan plan = new Plan(UUID.randomUUID(), "empty");

            service.recomputeHashes(plan);

            assertThat(plan.getMerkleRoot()).isNull();
        }

        @Test
        @DisplayName("deterministic — double call produces identical result")
        void deterministic() {
            Plan plan = plan(
                item("A", WorkerType.BE, "X", List.of()),
                item("B", WorkerType.FE, "Y", List.of("A"))
            );

            service.recomputeHashes(plan);
            String firstRoot = plan.getMerkleRoot();
            String firstHashA = byKey(plan, "A").getDagHash();
            String firstHashB = byKey(plan, "B").getDagHash();

            service.recomputeHashes(plan);

            assertThat(plan.getMerkleRoot()).isEqualTo(firstRoot);
            assertThat(byKey(plan, "A").getDagHash()).isEqualTo(firstHashA);
            assertThat(byKey(plan, "B").getDagHash()).isEqualTo(firstHashB);
        }

        @Test
        @DisplayName("order-independent — same DAG, different List order → same merkleRoot")
        void orderIndependent() {
            // Build same DAG with items in different order
            Plan plan1 = plan(
                item("A", WorkerType.BE, "X", List.of()),
                item("B", WorkerType.FE, "Y", List.of("A")),
                item("C", WorkerType.REVIEW, "Z", List.of("A"))
            );
            Plan plan2 = plan(
                item("C", WorkerType.REVIEW, "Z", List.of("A")),
                item("A", WorkerType.BE, "X", List.of()),
                item("B", WorkerType.FE, "Y", List.of("A"))
            );

            service.recomputeHashes(plan1);
            service.recomputeHashes(plan2);

            assertThat(plan1.getMerkleRoot()).isEqualTo(plan2.getMerkleRoot());
            assertThat(byKey(plan1, "A").getDagHash()).isEqualTo(byKey(plan2, "A").getDagHash());
            assertThat(byKey(plan1, "B").getDagHash()).isEqualTo(byKey(plan2, "B").getDagHash());
            assertThat(byKey(plan1, "C").getDagHash()).isEqualTo(byKey(plan2, "C").getDagHash());
        }
    }

    // ── verify ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verify")
    class VerifyTests {

        @Test
        @DisplayName("freshly computed plan verifies as valid")
        void verify_valid() {
            Plan plan = plan(
                item("A", WorkerType.BE, "X", List.of()),
                item("B", WorkerType.FE, "Y", List.of("A"))
            );
            service.recomputeHashes(plan);

            DagVerificationResult result = service.verify(plan);

            assertThat(result.valid()).isTrue();
            assertThat(result.mismatchedTaskKeys()).isEmpty();
            assertThat(result.computedMerkleRoot()).isEqualTo(result.storedMerkleRoot());
            assertThat(result.totalItems()).isEqualTo(2);
        }

        @Test
        @DisplayName("tampered dagHash detected as invalid")
        void verify_tampered_dagHash() {
            Plan plan = plan(
                item("A", WorkerType.BE, "X", List.of()),
                item("B", WorkerType.FE, "Y", List.of("A"))
            );
            service.recomputeHashes(plan);

            // Tamper with A's hash
            byKey(plan, "A").setDagHash("0000000000000000000000000000000000000000000000000000000000000000");

            DagVerificationResult result = service.verify(plan);

            assertThat(result.valid()).isFalse();
            assertThat(result.mismatchedTaskKeys()).contains("A");
            // B's stored hash is still the original correct hash, and recomputed B
            // (based on recomputed correct A) matches it — so only A mismatches
            assertThat(result.mismatchedTaskKeys()).doesNotContain("B");
        }

        @Test
        @DisplayName("tampered merkleRoot detected as invalid")
        void verify_tampered_merkleRoot() {
            Plan plan = plan(
                item("A", WorkerType.BE, "X", List.of())
            );
            service.recomputeHashes(plan);

            // Tamper with merkle root only
            plan.setMerkleRoot("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

            DagVerificationResult result = service.verify(plan);

            assertThat(result.valid()).isFalse();
            // dagHash itself is correct, only the root differs
            assertThat(result.mismatchedTaskKeys()).isEmpty();
            assertThat(result.storedMerkleRoot()).isEqualTo(
                    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        }

        @Test
        @DisplayName("verify does not mutate plan — hashes restored after verification")
        void verify_doesNotMutatePlan() {
            Plan plan = plan(
                item("A", WorkerType.BE, "X", List.of())
            );
            service.recomputeHashes(plan);
            String originalHash = byKey(plan, "A").getDagHash();
            String originalRoot = plan.getMerkleRoot();

            // Tamper
            byKey(plan, "A").setDagHash("tampered");
            plan.setMerkleRoot("tampered_root");

            service.verify(plan);

            // Should be restored to tampered values (not recomputed values)
            assertThat(byKey(plan, "A").getDagHash()).isEqualTo("tampered");
            assertThat(plan.getMerkleRoot()).isEqualTo("tampered_root");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Plan plan(PlanItem... items) {
        Plan plan = new Plan(UUID.randomUUID(), "test");
        for (PlanItem item : items) {
            plan.addItem(item);
        }
        return plan;
    }

    private static PlanItem item(String taskKey, WorkerType workerType, String title, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), 0, taskKey, title,
                "Desc", workerType, workerType.name().toLowerCase(), deps, List.of());
    }

    private static PlanItem byKey(Plan plan, String taskKey) {
        return plan.getItems().stream()
                .filter(i -> i.getTaskKey().equals(taskKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No item with key " + taskKey));
    }
}
