# Fase 20 — Execution Grounding & Adaptive Evolution (#177-#186)

> Il framework produce codice come output testuale senza poterlo compilare, testare o eseguire.
> Ogni piano parte da zero senza transfer learning. Fase 20 ground il framework nella realtà
> (bash → git safety → compile-test-fix) e lo rende adattivo (cross-plan learning, self-improvement,
> project lifecycle). Sblocca #25 (mcp-bash-tool), P16 e P18.

→ Indice master: [PIANO.md](PIANO.md) | Sintesi ricerca: [PIANO_FASE_20.md](PIANO_FASE_20.md)
→ Research reports: `docs/research/execution-runtime-177.md` ... `compile-test-fix-186.md`

---

## Sub-fase 20a — Execution Grounding (7.5g)

Catena: #177 → #185 → #186

---

### 177. Execution Runtime Orchestrator

**Sforzo**: 3.0g | **Valore**: Molto alto | **Tier**: 0

**Problema**: I worker producono codice come testo senza poterlo compilare, testare o eseguire. Non esiste accesso a shell execution. #25 (mcp-bash-tool) ha progettato l'interfaccia MCP, #44 il container isolation, #135 il SandboxExecutionService. Manca il **layer di integrazione**.

**Soluzione**: `ExecutionRuntimeOrchestrator` connette i tre predecessori: pre-warm pool di container, output parsing strutturato, resource accounting, timeout escalation, safety integration con #185.

**Approccio** (post-ricerca S29):

- **Container pre-warm pool**: 8 immagini language-specific (Java, Go, Python, Node, Rust, C++, .NET, COBOL) con pool configurabile per immagine. LRU eviction e health monitoring. Pool statico validato dalla letteratura serverless (Lin & Glikson 2019, RainbowCake ASPLOS 2024). Considerare layer-wise caching se l'impronta memoria è critica
- **Output parser a 3 livelli** (raccomandazione da ricerca): (1) exit code → successo/fallimento binario; (2) error classification → tipo (compilation, runtime, test failure, timeout); (3) error details → messaggio, file, linea, stack trace troncato
- **Resource accounting**: CPU-seconds, memory-peak, I/O bytes per esecuzione via cgroups v2 (raccomandazione OpenHands, ICLR 2025). Feeding in #160 (CostAccounting)
- **Timeout escalation**: SIGTERM al limite configurato, SIGKILL a 1.5x, cattura parziale risultato. Pattern validato da OpenDev (Bui 2026): `os.killpg()` → 5s grace → SIGKILL
- **Safety integration**: ogni comando passa attraverso command classifier (#185) prima dell'esecuzione. Gap colmato: nessun sistema esistente implementa approval gates per comandi distruttivi — #177+#185 sono i primi
- **Network isolation**: container senza accesso rete di default, whitelist per API necessarie (best practice emergente)

```java
public record ExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    ErrorClassification errorType, // COMPILATION, RUNTIME, TEST_FAILURE, TIMEOUT, NONE
    List<StructuredError> errors,  // file, line, message, stackTrace
    TestResult testResult,         // passed, failed, errors, coverage
    ResourceUsage resources        // cpuSeconds, memoryPeakMB, ioBytes
) {}

@Service
public class ExecutionRuntimeOrchestrator {
    // Pool management
    ContainerPool getOrCreatePool(LanguageImage image);
    void warmPool(LanguageImage image, int count);

    // Execution
    ExecutionResult execute(ExecutionRequest request, SafetyPolicy policy);
    ExecutionResult executeWithTimeout(ExecutionRequest request, Duration soft, Duration hard);

    // Resource accounting
    ResourceUsage getSessionUsage(UUID sessionId);
}
```

```yaml
execution:
  runtime:
    pool:
      default-size: 2          # container pre-riscaldati per immagine
      max-size: 8
      eviction: LRU
    timeout:
      soft: 60s
      hard: 90s                # 1.5x soft
    resources:
      max-cpu-seconds: 300
      max-memory-mb: 512
      max-io-bytes: 104857600  # 100MB
    network:
      default: DENY
      whitelist: []            # endpoint permessi
```

**Correzioni dalla ricerca**:
- ChatDev citazioni: ~601 (non ~464, +29%)
- Bui (2026): singolo autore (non "et al."). Titolo completo: "Building AI Coding Agents for the Terminal: Scaffolding, Harness, Context Engineering, and Lessons Learned"
- Nessun sistema implementa approval gates per comandi distruttivi — gap originale di #177+#185

**Paper di riferimento** (validati S29):
- Jimenez et al. "SWE-bench" (ICLR 2024, ~1667 cit) — execution-based evaluation
- Yang et al. "SWE-Agent" (NeurIPS 2024, ~792 cit) — ACI design per sandboxed execution
- Qian et al. "ChatDev" (ACL 2024, ~601 cit) — multi-agent execution orchestration
- Wang et al. "OpenHands" (ICLR 2025, ~436 cit) — reference per sandbox Docker + cgroups v2
- Bui "Building AI Coding Agents for the Terminal" (arXiv 2026) — timeout escalation, dual-agent

**File** (da creare/modificare):
- `control-plane/orchestrator/.../runtime/ExecutionRuntimeOrchestrator.java` (NEW)
- `control-plane/orchestrator/.../runtime/ContainerPool.java` (NEW)
- `control-plane/orchestrator/.../runtime/OutputParser.java` (NEW)
- `control-plane/orchestrator/.../runtime/ExecutionResult.java` (NEW — record)
- `control-plane/orchestrator/.../runtime/ResourceAccountingService.java` (NEW)
- `shared/rag-engine/.../SandboxExecutionService.java` (MOD — integrazione con pool)
- `config/execution-runtime.yml` (NEW)

**DB**: `V{N}__execution_runtime.sql`
- `execution_sessions` (id, plan_id, item_id, language, started_at, finished_at, exit_code, resource_usage JSONB)
- `execution_errors` (id, session_id, error_type, file, line, message, stack_trace)

**Dipendenze**: #25 (mcp-bash-tool), #44 (Execution Sandbox), #135 (SandboxExecutionService), #29 (Worker Lifecycle), #148 (WorkerWorkspaceManager)

**Test strategy**: Unit test per OutputParser con fixture di output reali (javac, pytest, jest). Integration test con container Docker locale. Benchmark cold-start vs warm pool (target: <100ms warm).

---

### 185. Git Safety Protocol Enforcer

**Sforzo**: 2.0g | **Valore**: Alto | **Tier**: 0

**Problema**: Con l'accesso bash da #177, i worker possono invocare comandi git. Nessun meccanismo impedisce `git reset --hard`, `git push --force`, `git rebase`, `git clean -f`. Solo il 17% degli agenti resiste senza safety layer (Shan 2026).

**Soluzione**: `GitSafetyProtocolService` — enforcement deterministico con classificazione a 3 livelli, branch protection, commit hygiene, e **sequence analysis** (gap critico dalla ricerca).

**Approccio** (post-ricerca S29):

- **Command classifier a 3 livelli**: SAFE (status, log, diff, branch --list), MODERATE (add, commit, push, checkout), DANGEROUS (reset --hard, push --force, rebase, clean -f, branch -D). Escalation configurabile. Pattern allineato con AgentSpec (Wang, Poskitt & Sun, ICSE 2026)
- **Sequence analyzer** (nuovo — da raccomandazione STAC): comandi innocui in isolamento possono formare catene pericolose (ASR >90% su agenti SOTA). Analisi del rischio cumulativo di sequenze git nella stessa sessione, non solo singoli comandi
- **Branch protection**: blocco push a main/master/release/* direttamente. Force-push bloccato globalmente. Merge-only strategy
- **Commit hygiene**: verifica pattern commit message, scan secret su file staged (riuso #167 SecretScanner), blocco file binari > limite
- **Pre-tool-use hook**: intercetta ogni `bash_execute` e git MCP tool call. Matching regex + analisi contestuale (non solo pattern matching — insufficiente da solo per la ricerca)
- **Worktree isolation**: ogni worker opera su proprio worktree (#148). Merge conflicts via #163

```java
public enum CommandRisk { SAFE, MODERATE, DANGEROUS }

public record GitCommand(String raw, CommandRisk risk, String operation, List<String> flags) {}

@Service
public class GitSafetyProtocolService {
    // Classification
    CommandRisk classify(String command);
    SequenceRisk analyzeSequence(List<GitCommand> sessionHistory); // NEW da STAC

    // Enforcement
    SafetyVerdict evaluate(GitCommand cmd, WorkerContext ctx);
    SafetyVerdict evaluateInContext(GitCommand cmd, List<GitCommand> history); // contextual

    // Branch protection
    boolean isBranchProtected(String branchName);
    boolean isForceOperationAllowed(WorkerContext ctx); // sempre false di default

    // Commit hygiene
    CommitScanResult scanStagedFiles(Path worktree);
}

public record SafetyVerdict(
    boolean allowed,
    CommandRisk risk,
    String reason,
    boolean requiresApproval  // per MODERATE con humanApprovalRequired
) {}
```

```yaml
git-safety:
  classification:
    safe: [status, log, diff, "branch --list", show, shortlog, tag -l]
    moderate: [add, commit, push, checkout, merge, "branch -m"]
    dangerous: ["reset --hard", "push --force", "push -f", rebase, "clean -f", "branch -D", "checkout -- ."]
  branch-protection:
    protected-patterns: ["main", "master", "release/*", "hotfix/*"]
    force-push: NEVER    # override solo con HookPolicy esplicita
  commit:
    message-pattern: "^(feat|fix|refactor|docs|test|chore)\\(.*\\):.*"
    max-binary-size: 1MB
    secret-scan: true    # riuso #167
  sequence:
    window-size: 20      # comandi da analizzare per rischio cumulativo
    risk-threshold: 0.7  # sopra = blocco con spiegazione
```

**Correzioni dalla ricerca**:
- AgentSpec autori: **Wang, Poskitt & Sun** (non "Zhou et al.") — ICSE 2026
- ToolEmu titolo ufficiale: "Identifying the Risks of LM Agents with an LM-Emulated Sandbox" (senza "ToolEmu" nel titolo)
- "Predicting Faults" autori: **Kim & Zimmermann** ICSE 2007 (non Bird ICSE 2004)
- Pattern matching singolo insufficiente — STAC dimostra necessità di sequence analysis

**Paper di riferimento** (validati S29):
- Ruan et al. "Identifying the Risks of LM Agents..." (ICLR 2024, ~234 cit) — safety evaluation, 23.9% failure rate
- Wang, Poskitt & Sun "AgentSpec" (ICSE 2026, ~33 cit) — DSL per runtime enforcement
- Li et al. "STAC" (2025) — catene di comandi innocui → attacchi (ASR >90%)
- Mou et al. "ToolSafe" (2026) — guardrail proattivo step-level
- Luo et al. "AGrail" (ACL 2025) — guardrail adattivo con safety check generation

**File** (da creare/modificare):
- `control-plane/orchestrator/.../safety/GitSafetyProtocolService.java` (NEW)
- `control-plane/orchestrator/.../safety/CommandClassifier.java` (NEW)
- `control-plane/orchestrator/.../safety/SequenceAnalyzer.java` (NEW)
- `control-plane/orchestrator/.../safety/BranchProtectionPolicy.java` (NEW)
- `.claude/hooks/git-safety-hook.sh` (NEW — pre-tool-use enforcement)
- `config/git-safety.yml` (NEW)

**DB**: `V{N}__git_safety.sql`
- `git_command_log` (id, session_id, raw_command, risk_level, verdict, reason, timestamp)
- `git_sequence_analysis` (id, session_id, sequence_hash, cumulative_risk, blocked, timestamp)

**Dipendenze**: #177 (ExecutionRuntimeOrchestrator), #25 (mcp-bash-tool), #167 (SecretScanner), #148 (WorkerWorkspaceManager), #163 (ConflictResolutionArbiter)

**Test strategy**: Unit test per classifier con matrice comandi×rischio. Fuzzing di comandi git per verificare copertura. Test sequence analyzer con catene STAC-style. Integration test con worktree reali.

---

### 186. Compile-Test-Fix Verification Loop

**Sforzo**: 2.5g | **Valore**: Molto alto | **Tier**: 0

**Problema**: Il framework non verifica se il codice generato compila o supera i test. La validazione è puramente LLM review (#120, #154). Con #177 che fornisce execution, #186 chiude il loop: compila → testa → se fallisce, feedback strutturato al worker.

**Soluzione**: `CompileTestFixLoopService` — loop 3-fase (compile → test → fix) con **2-3 iterazioni ottimali** (4 fonti indipendenti convergono), mixed feedback (compilatore + test), e behavior preservation per refactoring.

**Approccio** (post-ricerca S29):

- **3-phase loop**: (1) compile — cattura errori strutturati; (2) test — esecuzione test suite + nuovi test dal worker; (3) fix — errori strutturati come feedback al worker (Self-Debug pattern, non Self-Refine — feedback esterno >> self-feedback di 9.4-18.9 punti)
- **Iterazioni**: default 3, hard cap 5. 4 fonti convergono su 2-3 come ottimale. **Security degradation**: +37.6% vulnerabilità critiche dopo 5 iterazioni (Shukla et al.) — il hard cap è essenziale
- **Mixed feedback** (raccomandazione ricerca): combinare errori compilatore + test failure massimizza il miglioramento. Test feedback > compiler feedback da solo
- **Task-type gate**: code tasks → compile-test-fix loop; reasoning/design → skip a review; infrastructure → dry-run
- **Test result parsing**: JUnit XML, pytest JSON, Jest JSON → `TestResult {passed, failed, errors, coverage}`. Feeding in PRM (#127) e `task_outcomes` per GP training
- **Behavior preservation**: per refactoring (#172), confronto test pre/post. Se test falliscono post-semplificazione, rollback automatico. Critico: 19-35% dei refactoring LLM altera la semantica, 21% sfugge alle test suite
- **Repair@k metric** (da FeedbackEval): misura il tasso di riparazione cumulativo su k iterazioni

```java
public record CompileTestFixResult(
    int iterationsUsed,
    boolean compilationSuccess,
    TestResult finalTestResult,
    List<IterationLog> iterationHistory,
    BehaviorPreservation behaviorCheck  // solo per refactoring
) {}

@Service
public class CompileTestFixLoopService {
    // Main loop
    CompileTestFixResult executeLoop(
        ExecutionRequest request,
        CompileTestFixConfig config,
        WorkerFeedbackChannel feedbackChannel
    );

    // Phase executors
    CompilationResult compile(ExecutionRequest request);
    TestResult runTests(ExecutionRequest request, TestSuiteConfig suite);
    WorkerFeedback buildFeedback(CompilationResult comp, TestResult test); // mixed feedback

    // Behavior preservation
    BehaviorPreservation comparePrePost(TestResult pre, TestResult post);
    void rollbackIfRegression(ExecutionRequest request, BehaviorPreservation check);
}

public record CompileTestFixConfig(
    int maxIterations,     // default: 3, hard cap: 5
    boolean mixedFeedback, // compiler + test (default: true)
    TaskType taskType,     // CODE, REASONING, INFRASTRUCTURE
    boolean behaviorPreservation // per refactoring
) {}
```

```yaml
compile-test-fix:
  loop:
    max-iterations: 3       # ottimale da ricerca (2-3)
    hard-cap: 5              # mai superare — security degradation
    mixed-feedback: true     # compiler + test combinati
  task-type-gate:
    CODE: FULL_LOOP          # compile → test → fix
    REASONING: SKIP          # solo review
    INFRASTRUCTURE: DRY_RUN  # verifica senza esecuzione
  behavior-preservation:
    enabled: true
    rollback-on-regression: true
    test-comparison: STRICT  # tutti i test pre devono passare post
  test-parsing:
    formats: [JUNIT_XML, PYTEST_JSON, JEST_JSON]
```

**Correzioni dalla ricerca**:
- Self-Refine citazioni: ~2961 (non ~1000, +196%) — sottostimato di quasi 3x
- Self-Debug (Chen et al., ICLR 2024) è il pattern più appropriato di Self-Refine per questo use case (feedback esterno vs self-feedback)
- LLMLOOP venue: ICSME 2025 **Tool Demonstration track** (non main track)
- Dristi & Dwyer titolo: "A Differential Fuzzing-Based Evaluation of Functional Equivalence in LLM-Generated Code Refactorings" (non abbreviato)
- Iterazioni ottimali: 2-3 default, 5 hard cap (convergenza 4 fonti indipendenti)

**Paper di riferimento** (validati S29):
- Madaan et al. "Self-Refine" (NeurIPS 2023, ~2961 cit) — framework refinement iterativo
- Chen et al. "Self-Debug" (ICLR 2024) — feedback esterno >> self-feedback
- Chen et al. "MAgICoRe" (EMNLP 2025, ~37 cit) — +4% a metà costo
- Olausson et al. "Is Self-Repair a Silver Bullet..." (ICLR 2024) — bottleneck self-repair
- Ishibashi & Nishimura "RepairAgent" (ICSE 2025, ~262 cit) — FSM reference architecture
- Shukla et al. (2025) — +37.6% vulnerabilità critiche dopo 5 iterazioni

**File** (da creare/modificare):
- `control-plane/orchestrator/.../verification/CompileTestFixLoopService.java` (NEW)
- `control-plane/orchestrator/.../verification/TestResultParser.java` (NEW)
- `control-plane/orchestrator/.../verification/BehaviorPreservationChecker.java` (NEW)
- `control-plane/orchestrator/.../verification/CompileTestFixConfig.java` (NEW — record)
- `control-plane/orchestrator/.../pipeline/ValidationPipeline.java` (MOD — aggiunta stage "compile-test")

**DB**: `V{N}__compile_test_fix.sql`
- `compile_test_iterations` (id, session_id, iteration_number, compilation_ok, tests_passed, tests_failed, feedback_sent, timestamp)
- `behavior_preservation_checks` (id, session_id, pre_test_result JSONB, post_test_result JSONB, regression_detected, rollback_applied)

**Dipendenze**: #177 (ExecutionRuntimeOrchestrator), #120 (SelfRefineLoop), #127 (PRM), #154 (ValidationPipeline), #172 (CodeSimplifier)

**Test strategy**: Unit test per TestResultParser con fixture JUnit/pytest/Jest reali. Integration test del loop con progetto Java minimale (errore di compilazione → fix → successo). Test behavior preservation con refactoring intenzionalmente rotto. Verifica hard cap: iniettare errore non-risolvibile e verificare stop a 5 iterazioni.

---

## Sub-fase 20b — Intelligence & Learning (6.0g)

Catena: #178 → #182

---

### 178. Cross-Plan Knowledge Transfer Engine

**Sforzo**: 3.0g | **Valore**: Alto | **Tier**: 1

**Problema**: Ogni piano esegue indipendentemente. Il GP engine impara la selezione worker, ma prompt templates, decisioni architetturali, e pattern di errore non vengono trasferiti tra piani.

**Soluzione**: `CrossPlanKnowledgeEngine` — 3 canali di transfer (prompt refinements, error pattern library, architectural decision cache) con pattern **ExpeL/Reflexion** (non ETO — ETO richiede fine-tuning dei pesi, incompatibile con LLM API-based).

**Approccio** (post-ricerca S29):

- **Prompt refinements**: varianti di prompt che producono risultati migliori (PRM #127) per archetype → memorizzate con contesto. Retrieval: nuovo piano → archetype matching (#131) → prompt migliore
- **Error pattern library**: catalogo errori ricorrenti con strategia di risoluzione. Pattern **Reflexion** (NeurIPS 2023, ~1500+ cit): riflessioni verbali sui fallimenti accumulate in episodic memory, zero parametric updates. Triple contestuali: (contesto, errore, correzione, confidence)
- **Architectural decision cache**: ADR-like decisions dal Council (#90) memorizzate in AGE `task_graph` con edge types `REFINED_BY`, `RESOLVED_BY`, `DECIDED_BY`
- **Experiential learning** (pattern **ExpeL**, AAAI 2024): estrarre insight generalizzati dalle traiettorie (non le traiettorie intere). ~300+ cit. Transfer learning tra task diversi dimostrato
- **Transfer confidence**: non GP posterior (O(n³), non scala) → usare **CoPS** (Yang 2024) con regret bounds, o sparse GP. Solo knowledge con `source_similarity > threshold` e `historical_success > baseline`
- **Memoria procedurale** (ispirazione **MACLA**, AAMAS 2026): 2851 traiettorie compresse in 187 procedure riusabili, 2800x più veloce del fine-tuning

```java
@Service
public class CrossPlanKnowledgeEngine {
    // Prompt refinement channel
    void recordPromptOutcome(UUID planId, String workerType, String promptVariant, double prmScore);
    Optional<String> getBestPrompt(String archetype, String workerType);

    // Error pattern library (Reflexion pattern)
    void recordError(ErrorPattern pattern); // contesto, errore, correzione, confidence
    List<ErrorPattern> findRelevantErrors(String taskEmbedding, int topK);

    // Architectural decision cache
    void cacheDecision(ArchDecision decision); // from Council
    List<ArchDecision> retrieveDecisions(String architectureContext);

    // ExpeL-style insight extraction
    List<Insight> extractInsights(UUID planId); // post-plan generalization
    void applyInsights(PlanRequest request, List<Insight> relevant);

    // Transfer confidence (CoPS, not GP posterior)
    double estimateTransferConfidence(String sourceArchetype, String targetArchetype);
}
```

**Correzioni dalla ricerca**:
- Voyager venue: **TMLR** (non NeurIPS 2023) — correzione critica
- AgentReuse anno arXiv: 2025 (non 2024). Solo 2 citazioni. Focus su plan caching, non transfer learning
- ETO richiede fine-tuning DPO dei pesi — **non applicabile** con LLM API-based. Usare Reflexion o ExpeL
- GP posterior per transfer confidence: O(n³), usare CoPS o sparse GP (correzione algoritmica)

**Paper di riferimento** (validati S29):
- Shinn et al. "Reflexion" (NeurIPS 2023, ~1500+ cit) — verbal reinforcement learning, zero parametric updates
- Zhao et al. "ExpeL" (AAAI 2024, ~300+ cit) — experiential learning senza fine-tuning
- Wang et al. "Voyager" (TMLR 2023, ~1360 cit) — skill library pattern
- Song et al. "ETO" (ACL 2024, ~187 cit) — contrastive learning (nota: richiede DPO)
- Forouzandeh et al. "MACLA" (AAMAS 2026) — memoria procedurale gerarchica, LLM frozen
- Nourzad & Joe-Wong "MIRA" (ICLR 2026) — memory graph con garanzie di convergenza

**File** (da creare/modificare):
- `control-plane/orchestrator/.../knowledge/CrossPlanKnowledgeEngine.java` (NEW)
- `control-plane/orchestrator/.../knowledge/ErrorPatternLibrary.java` (NEW)
- `control-plane/orchestrator/.../knowledge/PromptRefinementStore.java` (NEW)
- `control-plane/orchestrator/.../knowledge/ArchDecisionCache.java` (NEW)
- `control-plane/orchestrator/.../knowledge/InsightExtractor.java` (NEW)
- `shared/rag-engine/.../PlanArchetypeRegistry.java` (MOD — integrazione retrieval)

**DB**: `V{N}__cross_plan_knowledge.sql`
- `prompt_refinements` (id, archetype, worker_type, prompt_variant, prm_score_avg, usage_count, created_at)
- `error_patterns` (id, context_embedding vector(1024), error_description, correction, confidence, outcome, plan_id)
- `arch_decisions` (id, context, decision, rationale, outcome_score, plan_id, council_session_id)
- `plan_insights` (id, plan_id, insight_text, category, applicability_score, created_at)

**Dipendenze**: #131 (PlanArchetypeRegistry), RAG Pipeline, GP Engine (#11), #127 (PRM), AGE graph

**Test strategy**: Unit test per insight extraction con mock plan data. Integration test: eseguire piano A con errori → verificare che piano B riceva error patterns rilevanti. Benchmark: misurare riduzione errori ricorrenti dopo 5+ piani.

---

### 182. Self-Improving Prompt & Strategy Optimizer

**Sforzo**: 3.0g | **Valore**: Alto | **Tier**: 1

**Problema**: Prompt templates, strategie di planning e parametri di configurazione sono statici. Non c'è meccanismo per ottimizzarli automaticamente basandosi sui risultati.

**Soluzione**: `SelfImprovingOptimizerService` — evolutionary prompt optimization + BOHB per strategy HPO, con safety guardrails (canary, golden test suite, rollback). Architettura ibrida DSPy (backbone) + EvoPrompt (varianti) + canary (gate).

**Approccio** (post-ricerca S29):

- **Evolutionary prompt optimization**: popolazione di varianti per worker type. Score via PRM (#127) e task_outcomes. Selezione top performers, mutazioni LLM-guided. EvoPrompt (ICLR 2024, ~439 cit): GA/DE operators via LLM, fino a +25% su BBH
- **Strategy optimization**: dispatch ordering, enrichment pipeline, Council composition — BOHB per mixed-type HPO
- **Safety guardrails**: 5 rischi identificati dalla ricerca: mode collapse, reward hacking, prompt bloat, safety drift, regression. Mitigazioni: diversity penalty + multi-objective + rotazione giudice
- **Canary evaluation**: nuove varianti su 10% dei task (#164 pattern). Promozione dopo significatività statistica
- **Lineage tracking**: storia in `task_graph` (AGE) con discendenza, mutazioni, performance delta. Valore aggiunto rispetto alla letteratura (Promptbreeder non lo implementa)
- **Feedback rules domain-specific** (pattern PROMST, EMNLP 2024): regole di feedback per task multi-step
- **Archivio storico** (ispirazione ADAS): tutte le varianti testate, non solo le migliori

```java
@Service
public class SelfImprovingOptimizerService {
    // Prompt evolution
    List<PromptVariant> evolvePopulation(String workerType, int generation);
    PromptVariant mutate(PromptVariant parent, MutationStrategy strategy);
    double evaluate(PromptVariant variant, EvaluationSuite suite);

    // Strategy optimization (BOHB)
    StrategyConfig optimizeStrategy(StrategySearchSpace space, int budget);

    // Safety
    boolean passesGoldenSuite(PromptVariant variant); // #181 benchmark
    CanaryResult runCanary(PromptVariant variant, double fraction); // 10%
    void rollbackToLastGood(String workerType);

    // Lineage
    EvolutionLineage getLineage(UUID variantId);
}

public record PromptVariant(
    UUID id,
    UUID parentId,          // lineage
    String workerType,
    String promptContent,
    int generation,
    double fitnessScore,
    MutationStrategy mutationUsed,
    Instant createdAt
) {}
```

```yaml
self-improving:
  evolution:
    population-size: 10
    max-generations: 20
    mutation-rate: 0.1       # max 10% per generazione
    diversity-penalty: 0.2   # penalità per varianti troppo simili
    selection: TOURNAMENT    # top-k tournament
  canary:
    fraction: 0.10           # 10% dei task
    min-samples: 30          # minimo per significatività
    significance-level: 0.05
  safety:
    golden-suite-required: true
    rollback-on-regression: true
    max-prompt-length: 4096  # anti prompt bloat
    judge-rotation: true     # rotazione modello giudice anti reward hacking
  bohb:
    max-budget: 100
    min-budget: 5
    eta: 3                   # successive halving factor
```

**Correzioni dalla ricerca**:
- Promptbreeder: pubblicato a **ICML 2024** (non solo arXiv 2023), ~388 cit
- DSPy: 2 versioni — preprint "Self-Improving Pipelines" (~578 cit) vs ICLR "State-of-the-Art Pipelines" (~148 cit)
- 5 rischi espliciti: mode collapse, reward hacking, prompt bloat, safety drift, regression

**Paper di riferimento** (validati S29):
- Khattab et al. "DSPy" (ICLR 2024, ~148/578 cit) — MIPROv2 optimizer
- Falkner et al. "BOHB" (ICML 2018, ~1266 cit) — Bayesian optimization + HyperBand
- Fernando et al. "Promptbreeder" (ICML 2024, ~388 cit) — evolutionary prompt optimization
- Zhou et al. "APE" (ICLR 2023, ~1251 cit) — automatic prompt engineering
- Guo et al. "EvoPrompt" (ICLR 2024, ~439 cit) — GA/DE operators via LLM
- Chen et al. "PROMST" (EMNLP 2024, ~36 cit) — multi-step prompt optimization

**File** (da creare/modificare):
- `control-plane/orchestrator/.../optimizer/SelfImprovingOptimizerService.java` (NEW)
- `control-plane/orchestrator/.../optimizer/PromptEvolutionEngine.java` (NEW)
- `control-plane/orchestrator/.../optimizer/StrategyBOHBOptimizer.java` (NEW)
- `control-plane/orchestrator/.../optimizer/CanaryEvaluator.java` (NEW)
- `control-plane/orchestrator/.../optimizer/EvolutionLineageTracker.java` (NEW)
- `config/self-improving.yml` (NEW)

**DB**: `V{N}__self_improving.sql`
- `prompt_variants` (id, parent_id, worker_type, content, generation, fitness_score, mutation_strategy, created_at)
- `canary_results` (id, variant_id, task_count, success_rate, significance_p, promoted, timestamp)
- `strategy_configs` (id, config JSONB, bohb_budget_used, performance_score, timestamp)

**Dipendenze**: #181 (Benchmark), #127 (PRM), #164 (CanaryExecution), #161 (PipelineConfigurator), #178 (KnowledgeTransfer)

**Test strategy**: Unit test per mutation operators. Integration test: evoluzione di 3 generazioni su prompt semplice, verifica miglioramento fitness. Test safety: iniettare variante peggiorativa, verificare che canary la blocchi e rollback funzioni.

---

## Sub-fase 20c — Lifecycle & Integration (6.5g)

Catena: #184 → #179 → #180

---

### 184. External System Integration Hub

**Sforzo**: 3.0g | **Valore**: Alto | **Tier**: 0

**Problema**: Il framework opera in isolamento — nessuna connessione a issue tracker, CI/CD o notifiche. Il `TASK_MANAGER` worker type non è mai stato implementato.

**Soluzione**: `ExternalIntegrationHubService` — adapter pattern per sistemi esterni, TASK_MANAGER implementation, webhook receiver con idempotency keys, notification pipeline.

**Approccio** (post-ricerca S29):

- **Adapter pattern**: connettori per Jira, Azure DevOps, Gitea (issue tracker); Jenkins, GitHub Actions (CI/CD); Slack, email, SSE (notifica). Interfaccia comune `ExternalSystemAdapter`. Rate limiting per-sistema (Jira: ~100 req/min)
- **TASK_MANAGER**: worker type che recupera branch + issue snapshot via MCP tools, memorizza `issueSnapshot` in PlanItem
- **DB-first sync**: framework DB è source of truth. Sistemi esterni eventually consistent. Bidirezionale
- **Webhook receiver con idempotency keys** (raccomandazione dalla ricerca): senza idempotency keys, rischio concreto di elaborazione duplicata. Usare `X-GitHub-Delivery` o composite key
- **Notification pipeline**: regole configurabili (plan completed → Slack, task failed → email)
- **Agenti e CI/CD**: solo 3.25% dei cambiamenti agentici tocca CI/CD (Ghaleb, MSR 2026) — hub dedicato colma il gap

```java
public interface ExternalSystemAdapter<T> {
    String systemName();
    T fetchState(String identifier); // issue, pipeline, etc.
    void pushUpdate(String identifier, Map<String, Object> changes);
    void handleWebhook(WebhookPayload payload);
    RateLimitConfig rateLimitConfig();
}

@Service
public class ExternalIntegrationHubService {
    // Adapter management
    <T> ExternalSystemAdapter<T> getAdapter(ExternalSystem system);

    // TASK_MANAGER
    IssueSnapshot fetchIssueSnapshot(String issueKey, ExternalSystem system);
    void syncPlanStateToIssue(UUID planId, String issueKey);

    // Webhooks
    void processWebhook(WebhookPayload payload, IdempotencyKey key);
    boolean isProcessed(IdempotencyKey key); // dedup check

    // Notifications
    void notify(NotificationEvent event, List<NotificationChannel> channels);
}
```

**Correzioni dalla ricerca**:
- Hohpe EIP citazioni: ~1412 (non ~3200, -56% — inflated)
- HULA titolo corretto: "Human-In-The-Loop Software Development Agents" (non "HULA" nel titolo)
- Webhook idempotency keys: obbligatorie (non menzionate nel design originale)

**Paper di riferimento** (validati S29):
- Hohpe & Woolf "Enterprise Integration Patterns" (2003, ~1412 cit) — messaging patterns
- Takerngsaksiri et al. "Human-In-The-Loop Software Development Agents" (ICSE-SEIP 2025, ~35 cit) — Jira integration
- Ghaleb "When AI Agents Touch CI/CD Configurations" (MSR 2026) — solo 3.25% CI/CD
- Mozannar et al. "Magentic-UI" (Microsoft, arXiv 2025, ~22 cit) — approval gates

**File** (da creare/modificare):
- `control-plane/orchestrator/.../integration/ExternalIntegrationHubService.java` (NEW)
- `control-plane/orchestrator/.../integration/adapters/JiraAdapter.java` (NEW)
- `control-plane/orchestrator/.../integration/adapters/GiteaAdapter.java` (NEW)
- `control-plane/orchestrator/.../integration/adapters/JenkinsAdapter.java` (NEW)
- `control-plane/orchestrator/.../integration/WebhookReceiverController.java` (NEW)
- `control-plane/orchestrator/.../integration/NotificationPipeline.java` (NEW)
- `execution-plane/workers/.../TaskManagerWorker.java` (NEW)

**DB**: `V{N}__external_integration.sql`
- `webhook_events` (id, system, idempotency_key UNIQUE, payload JSONB, processed_at)
- `issue_snapshots` (id, plan_id, item_id, system, issue_key, snapshot JSONB, fetched_at)
- `notification_rules` (id, event_type, channel, template, enabled)

**Dipendenze**: #5 (SSE + TrackerSync), TASK_MANAGER, MCP devops-tools, #150 (HumanInteractionGateway)

**Test strategy**: Unit test per adapter con WireMock. Integration test webhook con idempotency (inviare stesso webhook 2x, verificare elaborazione singola). Test notification pipeline end-to-end.

---

### 179. Conversational Requirements Elicitor

**Sforzo**: 2.5g | **Valore**: Alto | **Tier**: 0

**Problema**: Il planner riceve una spec e produce immediatamente i task. Nessuna fase di disambiguazione — spec ambigue producono piani ambigui.

**Soluzione**: `RequirementsElicitorService` — conversazione strutturata pre-planning con **SAGE-Agent EVPI** (non conformal prediction pura — spazio aperto, non discreto) per decidere quando chiedere.

**Approccio** (post-ricerca S29):

- **Ambiguity detection**: LLM uncertainty scoring sulla spec. Se `p(spec_complete) < threshold`, loop disambiguazione
- **EVPI per "when to ask"** (non CP — correzione dalla ricerca): SAGE-Agent (Suri et al. 2025) modella incertezza strutturata per parametro come POMDP con Expected Value of Perfect Information. Più adatto di CP (KnowNo) che assume spazio discreto/finito. +7-39% coverage con 1.5-2.7x meno domande
- **Structured requirements model**: functional requirements, constraints, acceptance criteria, out-of-scope. Output: `PlanRequest` arricchito
- **Question ranking**: ordinate per information gain (formalmente equivalente all'IG del Bradley-Terry nel Ranking Todo — possibile implementazione condivisa)
- **Autonomous fallback**: se nessuna risposta entro timeout, procede con assunzioni conservative. Flag `ASSUMED`
- **Conversation tree** (pattern consolidato RE — Pohl 2010): branching su risposte, funnel questions broad → specifiche

```java
@Service
public class RequirementsElicitorService {
    // Main flow
    ElicitationResult elicit(String rawSpec, ElicitationConfig config);

    // Ambiguity detection
    AmbiguityAssessment assessAmbiguity(String spec);
    boolean needsElicitation(AmbiguityAssessment assessment); // EVPI threshold

    // Question generation
    List<RankedQuestion> generateQuestions(String spec, ElicitationContext ctx);
    double computeEVPI(RankedQuestion question, ElicitationContext ctx); // information gain

    // Conversation management
    ElicitationContext processAnswer(ElicitationContext ctx, UserAnswer answer);
    PlanRequest buildEnrichedRequest(String rawSpec, ElicitationContext finalCtx);

    // Autonomous fallback
    PlanRequest buildWithAssumptions(String rawSpec, ElicitationContext partialCtx);
}

public record ElicitationResult(
    PlanRequest enrichedRequest,
    List<Assumption> assumptions,  // flag ASSUMED
    int questionsAsked,
    int roundsCompleted
) {}
```

```yaml
requirements-elicitor:
  ambiguity:
    threshold: 0.7           # sotto → trigger elicitation
    max-rounds: 3            # max cicli di domande
    questions-per-round: 3-5
  evpi:
    min-information-gain: 0.1
  fallback:
    timeout: 300s            # 5 min senza risposta → assunzioni
    flag-assumptions: true   # flag ASSUMED nel piano
```

**Correzioni dalla ricerca**:
- KnowNo titolo: "Robots That Ask For Help: Uncertainty Alignment for LLM Planners" (non "KnowNo" nel titolo)
- KnowNo venue: CoRL 2023 **Oral** (non Best Paper), ~332 cit
- AgentIF titolo: "AGENTIF: Benchmarking Instruction Following..." — venue: **arXiv preprint** (non NeurIPS 2025)
- CP (conformal prediction): **parzialmente appropriata** — funziona per spazi discreti, non per RE con spazio aperto. Usare EVPI (SAGE-Agent)
- Connessione serendipitous: EVPI ≡ Information Gain del Bradley-Terry (Preference Sort)

**Paper di riferimento** (validati S29):
- Ren et al. "Robots That Ask For Help..." (CoRL 2023 Oral, ~332 cit) — ispirazione, non applicazione diretta
- Pohl "Requirements Engineering" (Springer 2010, ~612 cit) — canonical RE
- Suri et al. "SAGE-Agent" (arXiv 2025) — EVPI su incertezza strutturata, ClarifyBench
- Zhang et al. "Learning When to Ask" (ICLR 2025) — simulazione future turns
- Gorer & Aydemir (IEEE RE 2024) — GPT genera script di intervista RE

**File** (da creare/modificare):
- `control-plane/orchestrator/.../elicitation/RequirementsElicitorService.java` (NEW)
- `control-plane/orchestrator/.../elicitation/AmbiguityDetector.java` (NEW)
- `control-plane/orchestrator/.../elicitation/QuestionRanker.java` (NEW — EVPI)
- `control-plane/orchestrator/.../elicitation/ConversationTree.java` (NEW)
- `control-plane/orchestrator/.../elicitation/ElicitationResult.java` (NEW — record)
- `control-plane/orchestrator/.../PlannerService.java` (MOD — integrazione pre-planning)

**DB**: `V{N}__requirements_elicitor.sql`
- `elicitation_sessions` (id, plan_id, raw_spec, rounds_completed, questions_asked, assumptions JSONB, completed_at)
- `elicitation_questions` (id, session_id, round, question_text, evpi_score, answer, answered_at)

**Dipendenze**: #150 (HumanInteractionGateway), Council (#13), Planner

**Test strategy**: Unit test per AmbiguityDetector con spec chiare vs ambigue. Integration test: spec ambigua → verifica generazione domande con EVPI > threshold. Test fallback: timeout → verifica assunzioni flaggate.

---

### 180. Multi-Plan Project Lifecycle Manager

**Sforzo**: 3.5g | **Valore**: Alto | **Tier**: 1

**Problema**: Il framework gestisce singoli piani. Non esiste coordinazione multi-piano: epic decomposition, sprint planning, release management.

**Soluzione**: `ProjectLifecycleManager` — entity Project come container di piani correlati, epic decomposition con HTN ibrido + LLM, plan sequencing con **Saga pattern** (SagaLLM, VLDB 2025), sprint iteration.

**Approccio** (post-ricerca S29):

- **Project entity**: container per piani correlati con stato lifecycle (PLANNING → ACTIVE → STABILIZING → RELEASED)
- **Epic decomposition**: HTN ibrido + LLM (non HTN puro — con LLM si generano decomposizioni on-the-fly). ChatHTN (Xu & Munoz-Avila, ACS 2025): genera decomposizioni HTN via LLM, poi generalizza con memoization. 3 principi AOP: solvability, completeness, non-redundancy
- **Plan sequencing con Saga pattern** (raccomandazione SagaLLM, VLDB 2025): compensating actions per gestire piani falliti a metà. Exit criteria configurabili. Checkpoint + rollback first-class
- **Sprint iteration**: time-boxing con scoping automatico. Drop item bassa priorità se budget eccede, carry-over al prossimo sprint. Nessun paper formalizza "sprint autonomi" — contributo originale
- **Release management**: aggrega artifacts, verifica integrazione cross-plan (#139), genera release notes da event streams

```java
@Entity
public class Project {
    UUID id;
    String name;
    ProjectState state; // PLANNING, ACTIVE, STABILIZING, RELEASED
    List<Plan> plans;   // sequenza ordinata
    List<Epic> epics;
    ReleaseConfig releaseConfig;
}

@Service
public class ProjectLifecycleManager {
    // Project lifecycle
    Project createProject(ProjectSpec spec);
    void transitionState(UUID projectId, ProjectState newState);

    // Epic decomposition (HTN + LLM)
    List<PlanSpec> decomposeEpic(Epic epic, DecompositionConfig config);

    // Plan sequencing (Saga pattern)
    void startNextPlan(UUID projectId); // quando exit criteria soddisfatti
    void compensate(UUID projectId, UUID failedPlanId); // compensating actions

    // Sprint
    SprintPlan planSprint(UUID projectId, Duration timeBox, int tokenBudget);
    void carryOver(UUID projectId, UUID sprintId); // item non completati → prossimo

    // Release
    ReleaseBundle assembleRelease(UUID projectId);
}
```

**Correzioni dalla ricerca**:
- Erol citazioni: ~892 (non ~711, +25% — sottostimato)
- Georgievski citazioni: ~168 (non ~335, -50% — sovrastimato)
- Li AOP venue: ICLR **2024** (non 2025)
- Saga pattern (SagaLLM): compensating actions essenziali per lifecycle — aggiunto al design

**Paper di riferimento** (validati S29):
- Erol et al. "HTN Planning" (AAAI 1994, ~892 cit) — decomposizione gerarchica
- Chang & Geng "SagaLLM" (VLDB 2025, ~23 cit) — Saga pattern per multi-agent lifecycle
- Li et al. "AOP" (ICLR 2024, ~25 cit) — 3 principi decomposizione
- Zhang et al. "AgentOrchestra" (arXiv 2025, ~57 cit) — framework gerarchico
- Xu & Munoz-Avila "ChatHTN" (ACS 2025) — HTN via LLM con memoization

**File** (da creare/modificare):
- `control-plane/orchestrator/.../lifecycle/ProjectLifecycleManager.java` (NEW)
- `control-plane/orchestrator/.../lifecycle/Project.java` (NEW — entity)
- `control-plane/orchestrator/.../lifecycle/EpicDecomposer.java` (NEW)
- `control-plane/orchestrator/.../lifecycle/SagaCoordinator.java` (NEW)
- `control-plane/orchestrator/.../lifecycle/SprintPlanner.java` (NEW)
- `control-plane/orchestrator/.../lifecycle/ReleaseAssembler.java` (NEW)

**DB**: `V{N}__project_lifecycle.sql`
- `projects` (id, name, state, release_config JSONB, created_at, updated_at)
- `epics` (id, project_id, title, description, priority, state)
- `plan_sequence` (id, project_id, plan_id, sequence_order, exit_criteria JSONB, state)
- `sprints` (id, project_id, start_date, end_date, token_budget, items_planned, items_completed)
- `saga_compensations` (id, project_id, failed_plan_id, compensation_action, executed_at)

**Dipendenze**: #145 (HierarchicalSubPlan), #9 (SUB_PLAN type), #139 (IntegrationTest), Event Sourcing (#1)

**Test strategy**: Unit test per epic decomposition con spec di complessità crescente. Integration test Saga: simulare piano fallito → verificare compensating action e avvio piano correttivo. Test sprint: verificare carry-over item non completati.

---

## Sub-fase 20d — Measurement & Output (4.5g)

Catena: #181 → #183

---

### 181. Longitudinal Effectiveness Benchmark

**Sforzo**: 2.5g | **Valore**: Alto | **Tier**: 0

**Problema**: Non esiste misurazione dell'efficacia del framework nel tempo. #111 monitora health runtime, #127 valuta qualità step-level. Nessun trend longitudinale.

**Soluzione**: `EffectivenessBenchmarkService` — 5 core KPIs, golden test suite, regression detection con **E-Divisive** (alternativa robusta a BOCPD, validata in produzione MongoDB), dual-granularity (weekly + daily).

**Approccio** (post-ricerca S29):

- **5 core KPIs**: (1) plan success rate; (2) mean quality score (PRM + Ralph-Loop); (3) cost efficiency (token/task); (4) latency (wall-clock); (5) human override rate. Aggiungere **progress rate** (da AgentBoard, NeurIPS 2024) — misura avanzamento anche su task non completati
- **Golden test suite**: benchmark periodico (mensile) con specifiche fisse via #177. Confronto a baseline storica
- **Regression detection**: usare **E-Divisive means** (Daly et al., ICPE 2020, MongoDB) come alternativa a BOCPD. Non-parametrico, non richiede assunzioni distribuzionali. BOCPD ha assunzione i.i.d. problematica per KPI con trend — se usato, pre-differencing o GP-BOCPD
- **Dual-granularity**: weekly per trend detection, daily per alerting rapido (raccomandazione ricerca)
- **STL decomposition**: decomporre serie in trend + stagionalità + residuo prima di CPD (riduce falsi positivi da pattern stagionali)
- **Statistical testing**: Wilcoxon signed-rank per confronti before/after

```java
@Service
public class EffectivenessBenchmarkService {
    // KPI computation
    KpiSnapshot computeCurrentKpis(Duration window);
    List<KpiTimeSeries> getHistoricalKpis(Duration lookback, Granularity granularity);

    // Golden test suite
    BenchmarkResult runGoldenSuite(List<BenchmarkSpec> specs);
    BenchmarkComparison compareToBaseline(BenchmarkResult current);

    // Regression detection (E-Divisive)
    List<ChangePoint> detectChangePoints(KpiTimeSeries series);
    Alert alertOnRegression(ChangePoint cp, double threshold);

    // Statistical testing
    StatisticalTest comparePeriods(KpiTimeSeries before, KpiTimeSeries after);
}

public enum Granularity { DAILY, WEEKLY, MONTHLY }
```

```yaml
benchmark:
  kpis:
    - name: plan_success_rate
    - name: mean_quality_score
    - name: cost_efficiency
    - name: latency_p50
    - name: human_override_rate
    - name: progress_rate        # da AgentBoard
  golden-suite:
    frequency: MONTHLY
    specs-file: config/golden-suite.yml
  regression:
    algorithm: E_DIVISIVE        # non-parametrico, validato MongoDB
    granularity: WEEKLY          # primario
    alert-granularity: DAILY     # rapido
    significance-level: 0.01
  decomposition:
    stl-enabled: true            # riduce falsi positivi
```

**Correzioni dalla ricerca**:
- BOCPD citazioni: ~850 (non ~1800, -53% — inflated). Assunzione i.i.d. problematica per KPI con trend
- AgentBoard titolo corretto: "...Multi-turn LLM Agents" (non "Multi-Step Reasoning Agent")
- TheAgentCompany: solo arXiv preprint (non conferenza peer-reviewed)
- Alternativa E-Divisive: non-parametrica, validata MongoDB (ICPE 2020)

**Paper di riferimento** (validati S29):
- Ma et al. "AgentBoard" (NeurIPS 2024 D&B Oral, ~152 cit) — progress rate metric
- Adams & MacKay "BOCPD" (arXiv 2007, ~850 cit) — changepoint detection (con riserve)
- Daly et al. "E-Divisive for Performance Testing" (ICPE 2020, ~61 cit GS) — alternativa non-parametrica, MongoDB production
- Xu et al. "TheAgentCompany" (arXiv 2024, ~124 cit) — partial completion scoring

**File** (da creare/modificare):
- `control-plane/orchestrator/.../benchmark/EffectivenessBenchmarkService.java` (NEW)
- `control-plane/orchestrator/.../benchmark/KpiComputer.java` (NEW)
- `control-plane/orchestrator/.../benchmark/ChangePointDetector.java` (NEW — E-Divisive)
- `control-plane/orchestrator/.../benchmark/GoldenSuiteRunner.java` (NEW)
- `config/golden-suite.yml` (NEW — benchmark specs)

**DB**: `V{N}__effectiveness_benchmark.sql`
- `kpi_snapshots` (id, kpi_name, value, granularity, period_start, period_end, computed_at) — partitioned by period_start
- `golden_suite_results` (id, run_date, spec_id, success, quality_score, latency_ms, comparison_to_baseline JSONB)
- `change_points` (id, kpi_name, detected_at, significance, direction, alert_sent)

**Dipendenze**: #111 (SLIs), #117 (BOCPD/E-Divisive), #127 (PRM), #139 (IntegrationTest), #177 (ExecutionRuntime), G4 (Prometheus)

**Test strategy**: Unit test per E-Divisive con serie sintetiche (changepoint noto). Integration test: iniettare regressione in KPI → verificare detection e alert. Test golden suite: eseguire con spec fissa, verificare confronto baseline.

---

### 183. Architectural Visualization Generator

**Sforzo**: 2.0g | **Valore**: Medio-Alto | **Tier**: 1

**Problema**: I worker producono codice e testo ma nessun artifact visuale. La documentazione non è generata strutturalmente. #8 ha un endpoint DAG Mermaid basilare.

**Soluzione**: `VisualizationGeneratorService` — Mermaid diagram generation multi-tipo, documentation pipeline, C4 model support con **validation post-generazione obbligatoria** (correzione dalla ricerca: LLM hanno capability gaps nella generazione diagrammi).

**Approccio** (post-ricerca S29):

- **Mermaid diagram generation**: dal plan structure e code graph (#165) — architecture, sequence, class, deployment diagrams. Generazione automatica da worker outputs + code graph analysis
- **Validation post-generazione** (correzione critica dalla ricerca): MermaidSeqBench dimostra che LLM hanno capability gaps. Ogni diagramma generato deve essere validato (Mermaid CLI lint, rendering test). Pattern Patidar (2025): generazione automatica + validazione ibrida
- **C4 model support**: 4 livelli di zoom (Context, Container, Component, Code). Brown anno corretto: **2012** (non 2018)
- **Documentation pipeline**: code + metadata → README.md, API docs, ADR. LLM summarization narrativa
- **Multi-format output**: Mermaid (inline), SVG (rendered), PlantUML. Stored in #48 (CAS)
- **Dashboard integration**: D3.js per plan timeline, dependency flow, worker allocation heatmap

```java
@Service
public class VisualizationGeneratorService {
    // Diagram generation
    DiagramResult generateArchitectureDiagram(UUID planId, C4Level level);
    DiagramResult generateSequenceDiagram(UUID planId, String flowName);
    DiagramResult generateClassDiagram(UUID planId, String packageFilter);

    // Validation (obbligatoria post-generazione)
    ValidationResult validateMermaid(String mermaidCode);
    DiagramResult generateAndValidate(DiagramRequest request); // genera + valida + retry

    // Documentation pipeline
    DocumentationBundle generateDocumentation(UUID planId);

    // Multi-format export
    byte[] renderToSvg(String mermaidCode);
    String convertToPlantUml(String mermaidCode);
}

public record DiagramResult(
    String mermaidCode,
    ValidationResult validation,
    byte[] renderedSvg,       // null se validation fallita
    DiagramType type
) {}
```

**Correzioni dalla ricerca**:
- Brown C4 anno: **2012** (non 2018)
- Spinellis: correzione grave — titolo, anno, citazioni tutti errati. "On the Declarative Specification of Models" IEEE Software 2003, ~39 cit (non ~150)
- Validation post-generazione: obbligatoria — LLM hanno capability gaps significativi (MermaidSeqBench)

**Paper di riferimento** (validati S29):
- Brown "Software Architecture for Developers" (Leanpub 2012) — C4 model
- Epperson et al. "AGDebugger" (CHI 2025, ~45 cit) — visual debugging multi-agent
- Patidar et al. (2025) — C4 automatico + validazione ibrida

**File** (da creare/modificare):
- `control-plane/orchestrator/.../visualization/VisualizationGeneratorService.java` (NEW)
- `control-plane/orchestrator/.../visualization/MermaidGenerator.java` (NEW)
- `control-plane/orchestrator/.../visualization/MermaidValidator.java` (NEW)
- `control-plane/orchestrator/.../visualization/DocumentationPipeline.java` (NEW)
- `control-plane/orchestrator/.../visualization/C4ModelBuilder.java` (NEW)

**DB**: nessuna migrazione necessaria — artifact stored in CAS (#48)

**Dipendenze**: #8 (DAG Mermaid), #48 (CAS), #165 (SharedCodeModel), #28 (Dashboard)

**Test strategy**: Unit test per MermaidGenerator con fixture di plan structure. Test validazione: generare diagramma intenzionalmente malformato → verificare che validator lo catturi e retry funzioni. Integration test: piano reale → diagramma C4 a 4 livelli → verifica rendering SVG.
