# Roadmap Core — Items #19-#49

Questa sezione copre gli item core dell'evoluzione architetturale: roadmap base (#19-#26), blockchain-inspired (#30-#34), mathematical foundations (#35-#43), advanced mechanisms (#45-#49) e l'execution sandbox (#44).

→ Indice master: [PIANO.md](PIANO.md)

---

# Roadmap items #19-#26

## #19 ✅ — Retry manuale via DB `TO_DISPATCH` → [PIANO_HISTORY.md]

---

## #20 ✅ — Decisione modello LLM per task (planner) → [PIANO_HISTORY.md]

---

## #21 — Redis topic splitting per workerType

**Problema**: singolo stream `agent-tasks` con filtering client-side in `shouldProcess()`.
Ogni worker riceve TUTTI i messaggi.

**Soluzione futura**: un Redis Stream per workerType (`agent-tasks:BE`, `agent-tasks:FE`).
Flag `messaging.redis.topic-per-type: true/false` per backward compatibility.

**Priorita'**: bassa — overhead trascurabile con <10 worker types.

**Sforzo**: 1g. **Dipendenze**: nessuna.

---

## #22 ✅ — Orchestrator singleton (leader election) → [PIANO_HISTORY.md]

---

## #23 ✅ — Enrichment Pipeline Activation → [PIANO_HISTORY.md]

---

## #24 ✅ — Tool configurabili (L1 toolHints ✅ / L2 TOOL_MANAGER ✅)

L1: planner genera `toolHints` → fallback se HookPolicy assente. L2: `TOOL_MANAGER` worker dedicato (Haiku, read-only). `EnrichmentInjectorService` fan-out. Policy resolution: TM > HM > Static. 10 test. S15.

---

## #25 ✅ — mcp-bash-tool + mcp-python-tool → [PIANO_HISTORY.md]

---

## #26 ✅ — Cost tracking + auto-split (L1 ✅ / L2 ✅ S15)

L1: `CostEstimationModel` per task. L2: auto-split task costosi via planner callback. Commit: `b4abca2`.

---

## #27 ✅ — Centralizzazione nomi tool (ToolNames registry) → [PIANO_HISTORY.md]

---

## #28 ✅ — Monitoring Dashboard UI (real-time) → S14

4 pannelli: DAG Live (Mermaid.js), Event Stream (SSE), Worker Detail, Stats. 3 nuovi SSE event types (TOOL_CALL_START/END, TOKEN_UPDATE). Vanilla JS. Commit: `4f47cdf`.

---

## #29 ✅ — Worker Lifecycle Management (kill, singleton, JVM-per-type)

Opzione D (JVM-per-WorkerType). Phase 1: tutto in-process (`a09df71`). Phase 2: hybrid REST dispatch + HTTP callback (`9a4c580`). `WorkerRuntime`, `WorkerRuntimeController`, `WorkerDispatcher`, `CancellationToken`. `RedisTaskLockService` (SETNX + Lua release + heartbeat 60s). Dettagli: PIANO_HISTORY.md.

---

# Roadmap items #30-#34 — Blockchain-Inspired Enhancements ✅

Tutti implementati. Dettagli: [documentazione/03-mathematical-foundations.md](documentazione/03-mathematical-foundations.md).

## #30 ✅ — Hash Chain Tamper-Proof su `plan_event`

`HashChainVerifier`, SHA-256 chain, `GET /verify-integrity`. V27 migration. 12 test. S15.

## #31 ✅ — Verifiable Compute (Ed25519)

`Ed25519Signer`, `SignedResultEnvelope`, `WorkerSigningService` (worker-sdk), `SignatureVerificationService` + `WorkerKey` entity (orchestrator). V29 migration. TOFU key discovery. 12 test crypto + integration. S18-S19.

## #32 ✅ — Policy-as-Code Immutabile (Commitment Hash)

SHA-256 commitment hash su HookPolicy. `PolicyCommitmentService`, `PolicyVerificationInterceptor`. Commit: `e8ee221`. S18.

## #33 ✅ — Token Economics (Double-Entry Ledger)

`TokenLedger` entity, `TokenLedgerService` (debit/credit/creditShapley), `TokenLedgerResponse` DTO, V24 migration, Prometheus metriche, 25 test. S16.

## #34 ✅ — Federazione Multi-Server (Design Interfacce)

`FederationProtocol`, `FederationService`, `FederationController` + DP noise. 8 test. S16.

---

# Roadmap items #35-#43 — Mathematical Foundations ✅

## #35 ✅ — Context Quality Scoring (Teoria dell'Informazione)

`ContextQualityService`, file relevance + entropy proxy + KL divergence, 4° reward source (0.15). S15. Commit: `75c484b`.
Pesi configurabili via `ContextQualityProperties` (`gp.context-quality.weights.*`), default 0.45/0.30/0.25.

## #36 ✅ — Worker Pool Sizing (Queueing Theory)

`QueueAnalyzer` (Erlang C + Little's Law + CPM), `QueuingCapacityPlanner` (M/G/1 P-K), `CriticalPathCalculator`, `TropicalScheduler`. 15 test. S17.

## #37 ✅ — Adaptive Token Budget (PID Controller)

`PidBudgetController` (PID in-memory per planId×workerType), integrazione OrchestrationService. 10 test. S16. Commit: `4ee1d76`.

## #38 ✅ — State Machine Verification (LTL)

`LTLPolicyVerifier` (4 formulae LTLf: S1/S2 safety, L1/L2 liveness), `StateMachineVerifier` (BFS model checker). 10+2 test. S15.
Integrato in `checkPlanCompletion()`: event `LTL_VERIFICATION` appendato post-completion.

## #39 ✅ — Policy Lattice Composition (Teoria dei Reticoli)

`PolicyLattice` (meet-semilattice, TOP/BOTTOM, wildcard). 17 test. S15.

## #40 ✅ — Shapley Value per Reward Distribution

`ShapleyDagService` (Monte Carlo, Kahn's random topo-sort), V25 migration, `creditShapley()`. 14 test. S16. Commit: `b3c0d3d`.


---

## #41 ✅ — Topological Pattern Detection (Persistent Homology)

Coperto da #85 `PersistentHomologyService` (Vietoris-Rips + Union-Find β₀ barcodes). Dettagli: [documentazione/06-fase-9-10-research.md](documentazione/06-fase-9-10-research.md).

---

## #42 ✅ — Global Task Assignment (Hungarian Algorithm)

`HungarianAlgorithm` (Kuhn-Munkres O(n³)), `GlobalAssignmentSolver` (cost matrix da GP), `AssignmentResult` DTO. Config `global-assignment:`. 15 test. S16. Commit: `7800c9e`.

## #43 ✅ — Differential Privacy per Metriche Federate

`DifferentialPrivacyService` (Laplace + Gaussian noise), `PrivacyBudgetTracker` (epsilon accounting). 10 test. S16.

---

# Advanced Mechanisms (#45-#49) ✅

Tutti implementati in S21-S22. Commit: `84253d3`.

## #45 ✅ — Merkle Tree per DAG Verification

`MerkleDagVerifier`, `DagHashService`. SHA-256 per nodo, merkle root per sink nodes. V11 migration. 8 test.

## #46 ✅ — Verifiable Council Deliberation (Commit-Reveal)

`CommitRevealCouncil`, `CouncilCommitment`. Two-phase: commit (blinded hash) → reveal (verify). 7 test.

## #47 ✅ — Reputation Staking (Teoria dei Giochi)

`ReputationStakingService`, `StakeEscrow`. Stake ELO → reward multiplier. 6 test.

## #48 ✅ — Content-Addressable Storage per Artifact

`ContentAddressableStore` (SHA-256 keyed), `ArtifactDeduplicator`. Plan-scoped workspace. 9 test.

## #49 ✅ — Quadratic Voting per Council Weighting

`QuadraticVotingService`, `VotingBudget`. sqrt(credits) = votes, budget depletion. 8 test.

---

# Execution Sandbox (#44)

---

## #44 — Execution Sandbox Containerizzato (Framework + Worker Isolation)

> Design completo: [docs/architecture/execution-sandbox-design.md](docs/architecture/execution-sandbox-design.md)

Dual-layer: framework containerizzato (Docker Compose) + sandbox effimeri per compilazione/test.
8 immagini sandbox pre-built (Java, COBOL, Go, Python, Node, Rust, C++, .NET).
Difesa in profondita': 8 livelli (network none, read-only, non-root, memory limit, timeout, volume :ro, no socket, seccomp).

**Sforzo**: 3g. **Dipendenze**: #29 (Worker Lifecycle), #25 (mcp-bash-tool).
