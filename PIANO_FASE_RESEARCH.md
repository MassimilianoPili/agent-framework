# Research Domains — Fasi 8-15 (#50-#136)

Questa sezione copre i research domains dalle Fasi 8 a 15: domini applicativi (#50-#106), research applicativi (#107-#116), adaptive operations (#117-#126), reflective intelligence (#127-#136).

→ Indice master: [PIANO.md](PIANO.md)

---

# Research Domains (#50-#61) ✅

12 items in 3 domini (Finanza, Sistemi Complessi, Matematica Avanzata), ~22.5g. Sessione S8-research.
Dettagli: [documentazione/05-fase-8-research.md](documentazione/05-fase-8-research.md) | `docs/agent-framework/research-domains-new.md`

---

## Research Domains — Fasi 9-12 (#62-#106) ✅

67 items completati, ~147.5g totali. Dettagli per fase:

| Fase | Items | Sforzo | File |
|------|-------|--------|------|
| 9 (#62-#76) | 15 | ~33.0g | [documentazione/06-fase-9-10-research.md](documentazione/06-fase-9-10-research.md) |
| 10 (#77-#86) | 10 | ~24.0g | [documentazione/06-fase-9-10-research.md](documentazione/06-fase-9-10-research.md) |
| 11 (#87-#96) | 10 | ~22.5g | [documentazione/07-fase-11-12-research.md](documentazione/07-fase-11-12-research.md) |
| 12 (#97-#106) | 10 | ~21.5g | [documentazione/07-fase-11-12-research.md](documentazione/07-fase-11-12-research.md) |

Consolidamento: `research-domains-consolidation.md` (matrice 57×21, Mermaid, coverage, theory clusters)

### Ordine implementazione Fase 13

```
Fase 13a (core, ~6.5g):            #110 ✅ → #111 ✅ → #116 ✅
Fase 13b (context+prompt, ~4.5g):  #107 ✅ → #108 ✅
Fase 13c (avanzato, ~8.0g):        #115 ✅ → #112 ✅ → #109 ✅
Fase 13d (attribution, ~5.0g):     #113 ✅ → #114 ✅
                                     ─────────────────────
                                     Totale: ~24.0g (#107-#116) ✅
```

### Riepilogo Fase 13 — Research Applicativi (#107-#116)

| Tier | # | Titolo | Componente gap | Sforzo | Valore |
|------|---|--------|----------------|--------|--------|
| 0 | 107 | Context Engineering ✅ | ContextWindowManager | 2.5g | Alto |
| 1 | 108 | Curriculum Prompting ✅ | PromptBuilder | 2.0g | Medio-Alto |
| 0 | 109 | Iterated Amplification ✅ | FeedbackCollector | 3.0g | Alto |
| 0 | 110 | Semantic Caching ✅ | CacheService | 2.0g | Alto |
| 0 | 111 | Observability SLIs ✅ | MetricsExporter | 2.0g | Alto |
| 0 | 112 | MCTS Dispatch ✅ | OrchestrationService | 3.0g | Alto |
| 1 | 113 | Worker-to-Worker Handoff ✅ | OrchestrationService | 2.5g | Medio-Alto |
| 1 | 114 | Markov Shapley Value ✅ | TaskOutcomeService | 2.5g | Medio-Alto |
| 1 | 115 | Factorised Belief Models ✅ | GP Engine | 3.0g | Medio-Alto |
| 1 | 116 | Logical Induction ✅ | GP Engine | 1.5g | Medio-Alto |
|   |     | **Totale Fase 13** | | **24.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§46-§55)

Risultati ricerca Fase 13 (35+ paper, 7 connessioni trasversali): → [PIANO_HISTORY.md] (S23)

### Ordine implementazione Fase 14

```
Fase 14a (core online, ~6.5g):       #117 → #119 → #120
Fase 14b (context+examples, ~6.5g):  #121 → #122 → #123
Fase 14c (attribution, ~4.5g):       #124 → #125
Fase 14d (resilience, ~5.5g):        #118 → #126
                                       ─────────────────────
                                       Totale: ~23.0g (#117-#126)
```

### Riepilogo Fase 14 — Adaptive Operations & Online Learning (#117-#126)

| Tier | # | Titolo | Componente target | Sforzo | Valore |
|------|---|--------|-------------------|--------|--------|
| 0 | 117 | Anomaly Detection su SLI Streams | SliAnomalyDetector | 2.0g | Alto |
| 1 | 118 | Semantic Cache Warm Transfer | CacheWarmTransferService | 2.0g | Medio-Alto |
| 0 | 119 | MCTS Online Policy Learning | MctsOnlineLearner | 2.5g | Alto |
| 0 | 120 | Self-Refine Loop | SelfRefineLoop | 2.0g | Alto |
| 0 | 121 | Context Compaction Pipeline | ContextCompactionPipeline | 2.5g | Alto |
| 1 | 122 | Golden Example Registry | GoldenExampleRegistry | 2.0g | Medio-Alto |
| 0 | 123 | Distributed Checkpointing | CheckpointService | 3.0g | Alto |
| 1 | 124 | Causal Shapley Attribution | CausalShapleyService | 2.5g | Medio-Alto |
| 1 | 125 | Adaptive Exploration Schedule | AdaptiveExplorationSchedule | 2.0g | Medio-Alto |
| 0 | 126 | Multi-Agent Failure Taxonomy | FailureTaxonomyService | 2.5g | Alto |
|   |     | **Totale Fase 14** | | **23.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§56-§65)

### Risultati ricerca Fase 14 — Sintesi accademica (S24)

Ricerca completata su 10 item (#117-#126). 50+ paper validati, 6 correzioni al design originale, 5 connessioni trasversali.

#### #117 Anomaly Detection — BOCPD validato, 7 raccomandazioni

**Paper validati:** Adams & MacKay 2007 (arXiv:0710.3742, ~1800 cit, BOCPD), Turner et al. 2009 (ICML, GP-BOCPD), Fearnhead & Liu 2007 (hazard adattivo).

**Correzioni al design:** (1) hazard lambda deve essere per-SLI-type (latency cambia più di availability), (2) servono detector paralleli a 3 scale temporali (raw, 1min, 5min) per catturare sia spike che drift, (3) AR(1) observation model per latency autocorrelate, (4) run-length pruning hard cap 500 (memory safety), (5) alert con posterior parameters (non solo binary), (6) independent per-stream + correlation aggregation (non joint multivariate).

#### #118 Semantic Cache Warm Transfer — Ben-David bound come fondamento

**Paper validati:** Ben-David et al. 2010 (ML journal, T1, ~5000 cit, domain adaptation bound), Pan & Yang 2010 (TKDE, T1, ~22000 cit), SUPER (Gerstgrasser 2023, selective sharing), LOL-GP (Wang 2024, local transfer), RECaST (Hickey 2024, Bayesian discount), C2C (Fu et al. ICLR 2026, cache-to-cache).

**Insight chiave:** Discount principled = `affinity_discount(d_HH) × uncertainty_gate(σ²) × temporal_decay(age)`. Uncertainty gate: trasferire solo dove σ²_target > σ²_source. Cap al 1-10% del cache (SUPER). Nessun framework esistente ha semantic cache transfer — design originale.

#### #119 MCTS Online — EMA insufficiente, 3 meccanismi aggiuntivi

**Paper validati:** Garivier & Moulines 2011 (ALT, T1, Discounted UCB), Gelly & Silver 2011 (T1, RAVE/AMAF), Guez et al. 2012 (NeurIPS, T1, BAMCP), LiZero (arXiv:2502.00633, adaptive decay).

**Correzione:** EMA decay (lambda=0.95) è sufficiente per drift graduale ma non per cambiamenti bruschi (~20 osservazioni per adattarsi). Aggiungere: (1) per-node adaptive lambda gated on Welford variance, (2) RAVE warm-start per nodi freddi, (3) BAMCP root sampling dal GP posterior (un Thompson sample per episodio).

#### #120 Self-Refine — CRITICO: task-type gate obbligatorio

**Paper validati:** Self-Refine (Madaan et al. NeurIPS 2023, ~1000 cit), Reflexion (Shinn et al. NeurIPS 2023, ~1520 cit), Cannot Self-Correct (Huang et al. ICLR 2024), Dark Side (Zhang 2024, -20.4% accuracy), MAgICoRe (Chen et al. EMNLP 2025, +4% a metà costo), DDI (Adnan & Kuhn, Sci. Reports 2025, 60-80% decay in 2-3 iter).

**Correzione CRITICA:** Self-refine **degrada** reasoning senza feedback esterno (58.8% risposte corrette ribaltate). Design rivisto: style/format → self-refine OK (max 3 iter); code → self-refine con test esterni (max 2 iter); math/reasoning → NO self-refine, escalare a H2 o usare PRM (MAgICoRe pattern). Epsilon deve essere misurato esternamente (embedding distance, non self-reported). Aggiungere flip rate monitor.

#### #121 Context Compaction — Non compattare mai le reasoning chains

**Paper validati:** LLMLingua-2 (Pan et al. ACL 2024), TALE (He et al. ACL 2025), "Complexity Trap" (JetBrains, NeurIPS 2025 Workshop, -52% costi), AOI (arXiv:2512.13956, 3 layer, 72.4% compression), ACON (Kang 2025), EHPC (NeurIPS 2025 Spotlight, evaluator heads).

**Correzione:** 4 segnali per tier assignment (non solo age+relevance): relevance + age + access frequency + information scent. **Mai compattare abstractivamente le reasoning chains** — solo observations e tool outputs. Simple observation masking ≥ LLM summarization (JetBrains: LLM summarization causa +13-15% allungamento traiettorie).

#### #122 Golden Example Registry — ZPD: esempi leggermente più facili

**Paper validati:** Cui & Sachan 2025 (ZPD per ICL, IRT-based), DemoShapley (2024, Shapley per example valuation), LLM-as-Judge (Zheng et al. NeurIPS 2023, ~7365 cit, bias noti), EPR (Rubin et al. 2022, trained retriever).

**Correzione:** Esempi devono essere **leggermente più facili** del task, ordinati easy-to-hard (Zone of Proximal Development). Finestra: `D(example) ∈ [D(task) - 2δ, D(task)]`, non `D(task) ± δ`. Quality gate ibrida: LLM-as-judge + position-swap debiasing + task-completion success + human audit periodico. DemoShapley per garbage collection.

#### #123 Distributed Checkpointing — Semplificato: event sourcing + GP snapshot

**Paper validati:** Chandy & Lamport 1985 (T1, ~1900 cit), Carbone et al. 2015 (Flink ABS, ~500 cit), Cheng & Boots 2016 (NeurIPS, incremental sparse GP), LangGraph/CrewAI docs.

**Correzione MAGGIORE:** Design over-engineered. PlanEventStore **già copre** il recovery del Plan state via event replay. Checkpoint ridotto a: `{lastEventSeqNum, gpHyperparameters, gpTrainingDataHash, cacheHotEntries}`. No MCTS tree (effimero, ricostruibile). No Chandy-Lamport (orchestratore centralizzato → quiesce-then-snapshot). GP re-training da dati ~200ms per n<1000. Una tabella `checkpoints` + un metodo.

#### #124 Causal Shapley — Owen Values ≡ Shapley Flow

**Paper validati:** Heskes et al. NeurIPS 2020 (T1, ~133 cit, Causal Shapley), Janzing et al. AISTATS 2020 (marginal è corretto), Shapley Flow (Wang et al. AISTATS 2021), Frye et al. (Asymmetric Shapley), PASV (Lee et al. 2026).

**Insight chiave:** Shapley Flow prova che **Owen values con coalizioni tree-structured = edge-level Shapley su DAG causale**. Ergo #114 (Owen) e #124 (Causal) sono viste della stessa struttura. Implementare Owen first, poi aggiungere conditioning interventionale. Monotonicità **non** completamente risolta — usare separazione direct/indirect come diagnostica.

#### #125 Adaptive Exploration — Randomized GP-UCB come alternativa semplice

**Paper validati:** Srinivas et al. 2010 (ICML/IEEE TIT, T1, ~1737 cit, GP-UCB), Vakili et al. 2021 (AISTATS, T1, bounds più stretti), Russo & Van Roy 2014 (NeurIPS/OR, T1, IDS), Takeno et al. 2023 (ICML, T1, Randomized GP-UCB), Deng et al. 2022 (AISTATS, T1, WGP-UCB non-stationary).

**Scelta strategica:** 4 path possibili: (A) TS solo → zero lavoro ma perde cold-domain boost; (B) **Randomized GP-UCB** → sample beta da Gamma, 80% beneficio con 20% complessità; (C) design corrente → valido, con fix a formula IG-lambda; (D) IDS → gold standard teorico, overkill. Raccomandazione: **Path B come default, Path C come upgrade**. Fix: invertire relazione IG-lambda (basso IG → exploit, non il contrario).

#### #126 Multi-Agent Failure Taxonomy — Correzione venue + priorità detection

**Paper validati:** Cemri et al. 2025 (**NeurIPS 2025 D&B spotlight**, non ICLR, arXiv:2503.13657), Zhu et al. 2025 (AgentDebug, +24% accuracy), Bholani 2026 (Self-Healing Router, -93% LLM calls), CONSENSAGENT (Pitre et al. ACL 2025, anti-sycophancy), Vennemeyer et al. 2025 (3 tipi sycophancy in latent space), AGDebugger (Epperson et al. CHI 2025).

**Correzione:** Tassonomia MAST ha **3 categorie** (non 4): FC1 Specification & System Design (5 modi), FC2 Inter-Agent Misalignment (6 modi), FC3 Task Verification & Termination (3 modi). Priorità detection: (1°) FM-3.2 No/Incomplete Verification (silente, cascade multiplier), (2°) FM-2.6 Reasoning-Action Mismatch, (3°) FM-1.3 Step Repetition (facile da detectare). Self-Healing Router per System failures. 11.54% delle esperienze sufficienti per learning (ChatDev).

#### Connessioni trasversali Fase 14

| Connessione | Descrizione | Impatto |
|-------------|-------------|---------|
| Owen ≡ Shapley Flow | #114 Owen hierarchy = #124 Causal Shapley su DAG | Implementare Owen first |
| ZPD + IRT | #122 difficulty matching = #108 IRT, stessa infrastruttura | Zero codice nuovo per difficulty |
| Ben-David + GP σ² | #118 discount = affinity(GP means) ha interpretazione teorica | Non è euristica |
| TS vs #125 | Thompson Sampling (#93) rende #125 parzialmente ridondante | Path B (RGP-UCB) come compromesso |
| Self-Refine gate + GP σ² | #120 decide se refinare basandosi su σ² (alta → refine, bassa → skip) | DDI decay ∝ 1/σ² |

Dipendenze Fase 12, audit qualita' Fase 9-12, arricchimenti: → [documentazione/07-fase-11-12-research.md](documentazione/07-fase-11-12-research.md)

### Ordine implementazione Fase 15

```
Fase 15a (verification & safety, ~8.0g):     #127 → #135 → #133
Fase 15b (transparency, ~4.0g):              #128 → #129
Fase 15c (optimization & learning, ~5.0g):   #132 → #131
Fase 15d (exploration & monitoring, ~5.5g):  #134 → #136 → #130
                                               ─────────────────────
                                               Totale: ~22.5g (#127-#136)
```

### Riepilogo Fase 15 — Reflective Intelligence & Decision Transparency (#127-#136)

> Tema: il sistema acquisisce la capacita' di ragionare sul proprio ragionamento, spiegare le decisioni,
> trasferire conoscenza, rilevare patologie, e verificare gli output tramite giudici esterni.

| # | Titolo | Componente target | Sforzo | Valore | Tier |
|---|--------|-------------------|--------|--------|------|
| 127 | Process Reward Model | `ProcessRewardModelService` | 2.5g | Alto | 0 |
| 128 | Explainable Decision Trace | `DecisionTraceService` | 2.0g | Alto | 0 |
| 129 | Sycophancy Detection in Council | `SycophancyDetectorService` | 2.0g | Alto | 0 |
| 130 | Graph-Based Recovery Router | `RecoveryRouterService` | 2.5g | Alto | 0 |
| 131 | Cross-Plan Meta-Learning | `PlanArchetypeRegistry` | 3.0g | Alto | 1 |
| 132 | Token Cost Pareto Optimizer | `ParetoDispatchOptimizer` | 2.0g | Medio-Alto | 1 |
| 133 | AUDIT_MANAGER Dual-Mode | `AuditManagerDualModeService` | 2.5g | Alto | 0 |
| 134 | Information-Directed Sampling | `InformationDirectedSamplingService` | 2.0g | Medio-Alto | 1 |
| 135 | Execution Sandbox | `SandboxExecutionService` | 3.0g | Alto | 0 |
| 136 | Bayesian Surprise Monitor | `BayesianSurpriseMonitor` | 1.5g | Medio-Alto | 1 |
|   |     | **Totale Fase 15** | | **22.5g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§66-§75)

### Sintesi ricerca accademica Fase 15 (S25)

10 item ricercati, ~40 paper validati, 5 correzioni al design, 4 connessioni trasversali.

#### #127 Process Reward Model — GP-posterior-as-proxy validato

**Paper validati:** Lightman et al. "Let's Verify Step by Step" (ICLR 2024, T1, ~2646 cit, ORM vs PRM — PRM superiore), MAgICoRe (Chen et al. EMNLP 2025, T1, +4% vs Self-Refine a <50% compute), DDI (Adnan & Kuhn, Scientific Reports 2025, T1, 60-80% decay confermato — ma specifico per code debugging), Huang et al. (ICLR 2024, T1, riconfermato).

**Insight chiave:** Il campo converge verso **PRM training-free** (implicit PRM, confidence-as-reward, generative verification) — valida fortemente il GP-posterior-as-proxy. CodePRM (ACL 2025) emerge come primo PRM specifico per codice. Task-type gate (style OK, reasoning NO) confermato empiricamente.

#### #128 Explainable Decision Trace — Contrastive > feature importance

**Paper validati:** SHAP (Lundberg & Lee NeurIPS 2017, T1, ~31707 cit), LIME (Ribeiro et al. KDD 2016, T1, ~20717 cit).

**Correzione:** Aggregare SHAP da 4 componenti indipendenti (GP, MCTS, EFE, Shapley) **non è sound** nel caso generale (Faith-Shap, Tsai et al. JMLR 2023 — interazioni tra componenti ignorati). Raccomandazione: (1) SHAP component-level OK come decomposizione, (2) aggiungere interaction audit quando componenti disagreano, (3) **contrastive explanations** ("why A not B") più utili di feature importance per dispatch (Miller 2019, AI journal, ~4985 cit; Lerouge et al. 2026, workforce scheduling). ACAR (Kumaresan 2026) — proxy attribution correla debolmente con leave-one-out ground truth. XRL taxonomy (Milani et al. ACM Computing Surveys 2023, ~192 cit) mappa perfettamente sui 3 livelli del DecisionTrace.

#### #129 Sycophancy Detection — 3 segnali insufficienti, soglia troppo alta

**Paper validati:** CONSENSAGENT (Pitre et al. ACL **Findings** 2025 — non main track), Vennemeyer et al. (arXiv:2509.21305, 3 tipi sycophancy separabili in latent space, confermato), Sharma et al. (ICLR 2024, T1, ~597 cit, foundational).

**Correzioni al design:** (1) Cosine similarity threshold **0.85-0.90** (non 0.95 — troppo stretta, perde sycophancy e flagga genuine agreement). (2) Aggiungere 3 segnali mancanti: **reasoning diversity collapse** (similarità delle justification, non solo dei voti), **first-mover anchoring** (conformity al primo a rispondere — Zhu et al. ACL 2025 main track), **calibration probe confidence shift**. (3) Devil's advocate deve essere **collaborativo** non adversarial (ColMAD, Chen et al. 2025 — competitive debate causa "debate hacking"). (4) **Model diversity mandate**: almeno 2-3 famiglie diverse tra gli 8 membri Council (MAEBE, Erisken et al. 2025 — ensemble omogenei convergono su errori correlati).

#### #130 Recovery Router — Bholani REALE, Dijkstra confermato

**Paper validati:** Bholani "Self-Healing Router" (arXiv:2603.01548, **2 marzo 2026** — paper reale, non hallucination. John Deere/MIT. -93% LLM control-plane calls confermato). MAST (Cemri et al. NeurIPS 2025 D&B, riconfermato, ~238 cit).

**Insight chiave:** Bholani conferma **esattamente** il design RecoveryRouterService — Dijkstra deterministico su tool graph, edge reweighted a infinito su failure, LLM riservato solo per "no feasible path". Dijkstra è la scelta giusta per edge weights deterministici (success rate storici). SHIELDA (2025, structured exception handling), CHIEF (2026, hierarchical causal graph for failure attribution), Who&When (2025, best accuracy 53.5% agent-level). **Nessun framework in produzione fa graph-based rerouting** — il nostro sarebbe il primo in Java/Spring.

#### #131 Cross-Plan Meta-Learning — MAML irrilevante, CBP lineage

**Paper validati:** MAML (Finn et al. ICML 2017, T1, ~13957 cit — confermato ma **irrilevante**), SUPER (Bogin et al. **EMNLP 2024** — non Wang et al., ed è un benchmark non un approccio).

**Correzione MAGGIORE:** MAML è gradient-based parameter initialization. PlanArchetypeRegistry è **retrieval-based few-shot priming** — lineage corretto: Case-Based Planning (Gerevini et al. JAIR 2023, plan library maintenance) → Skill Libraries (Voyager, NeurIPS 2023 spotlight) → Plan Reuse (AgentReuse, Li et al. 2024, **93% reuse rate** via intent classification). Graph edit distance NP-hard → usare **two-stage retrieval**: pgvector embedding (coarse) + WL kernel (fine). Store anche archetypes falliti per contrastive signal (ETO, Song et al. ACL 2024). Quality score multi-dimensionale: success rate + adaptation cost + IRT difficulty weighted + coverage + freshness.

#### #132 Token Cost Pareto Optimizer — Sener & Koltun validato

**Paper validati:** Sener & Koltun (NeurIPS 2018, T1, confermato — multi-task learning as multi-objective optimization), Ehrgott (2005, textbook Springer, confermato).

**Insight chiave:** FrugalGPT (Chen et al. 2023) e RouteLLM (Ong et al. 2024) dimostrano che cascade semplici (cheap model first, escalate se incerto) catturano ~80% del risparmio con complessità minima. Pareto frontier esplicito è più flessibile ma può essere overkill. Raccomandazione: partire con cascade GP-guided (se mu alto e sigma basso → cheap worker, altrimenti escalate), poi evolvere a Pareto se servono >2 obiettivi.

#### #133 AUDIT_MANAGER Dual-Mode — AutoCodeRover = ISSTA 2024

**Paper validati:** AutoCodeRover (Zhang et al. **ISSTA 2024** — non ICSE 2025, ~185 cit, spectrum-based fault localization + AST search), SWE-Agent (Yang et al. **NeurIPS 2024**, ~792 cit, agent-computer interface design).

**Insight chiave:** Agentless (Xia et al. 2024, ~289 cit) valida il concetto di pre-planning: localizzazione strutturata prima dell'azione → 32% SWE-bench a $0.70. CodexGraph (NAACL 2025, graph DB per code context), LocAgent (ACL 2025, lightweight directed graph), CGM (NeurIPS 2025, code graph nel LLM attention — 43% SWE-bench con open-weight). Gerarchia consolidata: **graph > hierarchical summary > AST > flat file list**. METR Report (marzo 2026): ~50% dei patch SWE-bench Verified verrebbero rifiutati da maintainer reali — benchmark sovrastima utilità ~2x.

#### #134 Information-Directed Sampling — Russo & Van Roy confermato

**Paper validati:** Russo & Van Roy (NeurIPS 2014 + Operations Research 2018, T1, confermato — IDS minimizza ratio regret²/information gain).

**Nota:** Ricerca S24 (#125) ha già coperto IDS in dettaglio. IDS è computazionalmente più costoso di TS (~10x per GP posteriors). Randomized GP-UCB (raccomandato in S24) cattura 80% del beneficio con 20% della complessità. IDS rimane la scelta ottimale teorica ma il fallback a TS è pragmaticamente superiore.

#### #135 Execution Sandbox — SWE-bench ICLR 2024 confermato

**Paper validati:** Jimenez et al. SWE-bench (ICLR 2024, T1, confermato — execution-based evaluation).

**Insight chiave:** SWE-bench è il benchmark standard per execution-based evaluation. gVisor/Firecracker offrono isolamento superiore a Docker ma con overhead di setup. Per il nostro caso (compilazione Java + test JUnit), Docker con seccomp profile + network none + read-only root è sufficiente (difesa in profondità già nel design #44). Container pre-warm pool riduce startup latency da ~2-5s a ~200ms.

#### #136 Bayesian Surprise Monitor — Itti & Baldi = NeurIPS 2005

**Paper validati:** Itti & Baldi (**NeurIPS 2005** — non 2009; il 2009 è la versione Vision Research journal, ~1709 cit), Schmidhuber (IEEE TAMD 2010, T1, ~849 cit — learning progress ≠ raw surprise).

**Correzione:** (1) `KL(posterior || prior)` è la direzione canonica — confermata da Feldman & Friston (2010, ~1258 cit, free-energy framework). (2) La distinzione novelty/anomaly è supportata: novelty = surprise + model improvement (Schmidhuber), anomaly = surprise persistente senza apprendimento (Achiam & Sastry 2017, ~250 cit). (3) Per threshold adattivi: percentile-based come baseline, ma integrare con BOCPD per il caso anomaly — Altamirano et al. (ICML 2023) mostra che generalised Bayesian posteriors danno robustezza a model misspecification. (4) Integrazione BOCPD↔Surprise è profonda: BOCPD può operare sulla serie temporale di surprise stessa.

### Connessioni trasversali Fase 15

| Connessione | Item | Insight |
|-------------|------|---------|
| PRM ↔ Sandbox | #127 ↔ #135 | Sandbox fornisce pass/fail binario come segnale PRM per codice |
| Sycophancy ↔ Surprise | #129 ↔ #136 | Entropy collapse nel Council = surprise anomala → Bayesian surprise come meta-segnale per sycophancy detection |
| Recovery ↔ Taxonomy | #130 ↔ #126 | MAST classifica, Recovery Router instrada — ruoli complementari |
| Meta-Learning ↔ IRT | #131 ↔ #108 | IRT difficulty scores pesano la qualità degli archetypes |
| BOCPD ↔ Surprise | #117 ↔ #136 | BOCPD opera sulla serie temporale di surprise — changepoint in surprise level |

### Correzioni al design identificate (6 totali)

1. **#128**: SHAP aggregato da 4 componenti non è sound → aggiungere interaction audit + contrastive explanations
2. **#129**: Soglia cosine 0.95 → 0.85-0.90; aggiungere 3 segnali mancanti; devil's advocate collaborativo
3. **#131**: MAML irrilevante → CBP/skill library lineage; SUPER = Bogin (non Wang); GED → two-stage retrieval
4. **#133**: AutoCodeRover = ISSTA 2024 (non ICSE 2025)
5. **#136**: Itti & Baldi = NeurIPS 2005 (non 2009)
6. **#132**: Cascade GP-guided più pragmatico di Pareto frontier esplicito come punto di partenza
