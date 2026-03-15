# Fasi 16-18 — Operational Maturity, Worker Autonomy, Production Intelligence (#137-#166)

Questa sezione copre le Fasi 16-18: Operational Maturity & Production Resilience (#137-#146), Worker Autonomy & Interactive Intelligence (#147-#156), Production Intelligence & Collaborative Coordination (#157-#166).

→ Indice master: [PIANO.md](PIANO.md)

---

### Ordine implementazione Fase 16

```
Fase 16a (security & foundations, 7.5g):     #137 → #138 → #142
Fase 16b (verification & testing, 4.5g):     #139 → #146
Fase 16c (prediction & learning, 7.5g):      #141 → #143 → #140
Fase 16d (scaling, 5.5g):                    #144 → #145
```

### Riepilogo Fase 16 — Operational Maturity & Production Resilience (#137-#146)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 137 | Prompt Injection Detector | `PromptInjectionDetectorService` | 2.5g | Alto | 0 |
| 138 | Tenant Context Isolation | `TenantIsolationService` | 3.0g | Alto | 0 |
| 139 | Integration Test Framework | `PlanIntegrityTestFramework` | 2.5g | Alto | 0 |
| 140 | Human Correction Learning | `HumanCorrectionLearnerService` | 2.5g | Alto | 1 |
| 141 | Predictive Cost & Failure Forecaster | `PredictiveForecasterService` | 2.5g | Alto | 1 |
| 142 | Distributed Tracing Correlator | `DistributedTracingService` | 2.0g | Alto | 0 |
| 143 | Failure Pattern Predictor | `FailurePatternPredictorService` | 2.5g | Alto | 1 |
| 144 | Multi-Instance Plan Router | `PlanRoutingService` | 3.0g | Medio-Alto | 0 |
| 145 | Hierarchical Sub-Plan | `SubPlanOrchestrationService` | 2.5g | Alto | 0 |
| 146 | Plan Integrity Verifier | `PlanIntegrityVerifierService` | 2.0g | Alto | 0 |
|   |     | **Totale Fase 16** | | **25.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§76-§85)

### Sintesi ricerca Fase 16 (S25)

Ricerca accademica completata su tutti i 10 item (#137-#146). 10 agenti paralleli, ~20 paper citati validati, ~30 paper nuovi trovati. Di seguito le sintesi per item con correzioni e raccomandazioni.

#### #137 — Prompt Injection Detector
- **Paper validati**: Greshake et al. (AISec 2023, ~761 cit), Jain et al. (arXiv 2023, T2), Yi et al. (**KDD 2025**, non arXiv — venue upgrade)
- **Paper nuovi**: Kang et al. (NAACL 2025), Spotlighting/Datamarking (L0 defense layer)
- **Correzione**: aggiungere Spotlighting come Layer 0 pre-filter

#### #138 — Tenant Context Isolation
- **Krebs et al.**: venue è **CLOSER 2012**, non ICSEA; titolo è "Architectural Concerns"
- **Paper nuovi**: PROMPTPEEK (NDSS 2025), Burn-after-use (arXiv 2026)
- **Raccomandazione**: aggiungere PostgreSQL RLS come defense-in-depth

#### #139 — Integration Test Framework
- **Paper validati**: QuickCheck (ICFP 2000, ~1271 S2), Swarm Testing (ISSTA 2012, ~200)
- **Paper nuovi**: quickcheck-state-machine (Andjelkovic 2017), MASEval/MAESTRO
- **Raccomandazione**: metamorphic testing + mutation testing (PIT)

#### #140 — Human Correction Learning
- **Paper validati**: Christiano (NeurIPS 2017, ~4321), Ouyang (NeurIPS 2022), Fails & Olsen (IUI 2003, ~465 — non ~1000)
- **Correzione**: peso correzione fisso 2.0x → **adattivo [0.5, 5.0]**
- **Raccomandazione**: step-level granularity > task-level

#### #141 — Predictive Cost & Failure Forecaster
- **Notaro et al.**: **venue errato** — ACM TIST, non Computing Surveys. Citazioni ~67, non ~250
- **Correzione critica**: **Holt-Winters → Holt's linear (damped trend)** (nessuna stagionalità)
- **Paper nuovi**: Salfner et al. (CSUR 2010, >500 cit), Feng et al. MarBLR (JAMIA 2022)
- **Raccomandazione**: prediction intervals al posto di threshold 1.2x. MarBLR > BLR per drift

#### #142 — Distributed Tracing Correlator
- **W3C Trace Context**: anno è **2020**, non 2021
- **Sambasivan et al.**: **CMU tech report 2014**, non HotNets 2016. Citare SoCC 2016 version
- **Paper nuovi**: OTel GenAI Semantic Conventions (2025), AG2 OTel Tracing (2026)
- **Raccomandazione**: Micrometer Observation API corretta per Spring Boot 3.x

#### #143 — Failure Pattern Predictor
- **Notaro et al.**: stessa correzione venue di #141 (TIST, non CSUR)
- **Correzione critica**: **Aho-Corasick è sbagliato** (exact substring, non subsequence). Alternativa: NFA skip semantics (SASE, SIGMOD 2008) o pointer array O(n×k)
- **Paper nuovi**: CM-SPADE (PAKDD 2014, 8x più veloce di PrefixSpan)

#### #144 — Multi-Instance Plan Router
- **Citazioni corrette**: Karger ~3230 (non ~5000), DeCandia ~4607 (non ~6000)
- **Ketama**: OK + layerare **bounded-load** (Mirrokni et al. 2018)
- **Correzione**: **Redis pub/sub → Redis Streams** (delivery garantito). MULTI/EXEC per atomicità vnodes

#### #145 — Hierarchical Sub-Plan
- **Citazioni corrette**: Erol ~711 (non ~1000), Nau/SHOP2 ~1148 (non ~2000)
- **Georgievski venue**: *Artificial Intelligence* (Elsevier), non "AI Review"
- **Depth limit 3**: confermato (Temporal, Cadence, Airflow convergono su 2-3)
- **Raccomandazione**: ParentClosePolicy, fan-out limit, budget reservation con watermark

#### #146 — Plan Integrity Verifier
- **Paper validati**: Schneider (TISSEC 2000, ~866 S2), Leucker & Schallhart (JLAP 2009, ~872 S2)
- **Raccomandazione**: Kahn's algorithm O(V+E) per cycle detection. #146 e #38 complementari
- **Correzione**: force-FAILED deve essere transizione valida da qualsiasi stato. Gestione concorrenza necessaria

#### Correzioni cumulative Fase 16

| # | Cosa | Da | A |
|---|------|----|---|
| 138 | Krebs venue | ICSEA | CLOSER 2012 |
| 141 | Notaro venue | ACM Computing Surveys | ACM TIST |
| 141 | Notaro citazioni | ~250 | ~67 |
| 141 | Metodo forecasting | Holt-Winters | Holt's linear (damped trend) |
| 142 | W3C Trace Context anno | 2021 | 2020 |
| 142 | Sambasivan venue | HotNets 2016 | CMU-PDL-14-102 (2014) / SoCC 2016 |
| 143 | Notaro venue | ACM Computing Surveys | ACM TIST (stessa correzione) |
| 143 | Online matching | Aho-Corasick | NFA skip semantics / pointer array |
| 144 | Karger citazioni | ~5000 | ~3230 (S2) |
| 144 | DeCandia citazioni | ~6000 | ~4607 (S2) |
| 144 | Plan forwarding | Redis pub/sub | Redis Streams (delivery garantito) |
| 145 | Erol citazioni | ~1000 | ~711 |
| 145 | Nau/SHOP2 citazioni | ~2000 | ~1148 |
| 145 | Georgievski venue | AI Review | *Artificial Intelligence* (Elsevier) |

#### Cross-connessioni inter-fase Fase 16

| Connessione | Da → A | Tipo |
|-------------|--------|------|
| Notaro et al. (TIST) | #141 ↔ #143 | Stesso paper, stessa correzione venue |
| Salfner et al. (CSUR 2010) | #141 ↔ #143 | Paper chiave condiviso per failure prediction |
| MarBLR drift detection | #141 → #136 (Bayesian Surprise) | Model drift alimenta surprise detection |
| Redis Streams forwarding | #144 → #142 | Trace context propagation nel forwarding |
| Fan-out limit + depth limit | #145 → #146 (I1) | Entrambi prevengono esplosione strutturale |
| Self-Healing Router (arXiv:2603.01548) | #130 ↔ #146 | Edge-reweighting vs break-weakest |
| OTel GenAI conventions | #142 → #145 | Sub-plan span hierarchy = agent span hierarchy |

Report completi: `docs/research/{injection-detector-137,tenant-isolation-138,test-framework-139,human-correction-140,predictive-forecaster-141,distributed-tracing-142,failure-pattern-143,multi-instance-router-144,hierarchical-subplan-145,plan-integrity-146}.md`

---


### Ordine implementazione Fase 17

```
Fase 17a (foundations, 7.5g):              #147 → #148 → #152
Fase 17b (interaction, 4.5g):             #150 → #155
Fase 17c (intelligence, 5.0g):            #151 → #149
Fase 17d (safety & discovery, 7.0g):      #153 → #154 → #156
```

### Riepilogo Fase 17 — Worker Autonomy & Interactive Intelligence (#147-#156)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 147 | Phased Worker Execution | `WorkerPhaseOrchestrator` | 2.5g | Alto | 0 |
| 148 | Worker Workspace Isolation | `WorkerWorkspaceManager` | 3.0g | Alto | 0 |
| 149 | Parallel Tool Orchestration | `ParallelToolCallingManager` | 2.5g | Alto | 0 |
| 150 | Mid-Execution Human Interaction | `HumanInteractionGateway` | 2.5g | Alto | 0 |
| 151 | Persistent Worker Memory | `WorkerEpisodicMemory` | 2.5g | Alto | 0 |
| 152 | Project Constraint Injection | `ProjectConstraintManager` | 2.0g | Alto | 0 |
| 153 | Information Flow Guard | `InformationFlowGuard` | 2.5g | Alto | 0 |
| 154 | Automated Validation Pipeline | `ValidationPipelineService` | 2.5g | Alto | 1 |
| 155 | Worker Progress Estimation | `WorkerProgressTracker` | 2.0g | Medio-Alto | 1 |
| 156 | Dynamic Tool Discovery | `DynamicToolRegistry` | 2.0g | Medio-Alto | 1 |
|   |     | **Totale Fase 17** | | **24.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§86-§95)

Claude Code patterns coperti: P2, P3, P5, P7, P8, P10, P14, P15, P18, P21, P22, P26, P28 (13/17 gap chiusi)

### Sintesi ricerca accademica Fase 17 (S26, 2026-03-15)

10 report Template F completati. 37 riferimenti validati, 1 fabbricato, ~60 paper aggiuntivi identificati.

#### Per-item summaries

**#147** — SWE-Agent: **claim "tool filtering per phase" ERRATO** (ACI statico). Hard tool allowlist per fase = contributo novel. Paper chiave: AFlow (ICLR 2025 Oral), MCP-Zero.

**#148** — 4 riferimenti validati. Git worktree: no T1-T3, solo practitioner. Paper chiave: FIDES (Microsoft), MAGIS (NeurIPS 2024).

**#149** — **Lea: cit 549 (non 1400)**. Paper chiave: **LLMCompiler (ICML 2024, 3.7x speedup)**, W&D (~3 tool paralleli ottimali).

**#150** — **Settles: confusione libro 2012 (~695) vs survey 2009 (~6564)**. Paper chiave: **KnowNo (CoRL 2023 Best Paper)**, HULA (ICSE SEIP 2025).

**#151** — **Lin: cit 1698 (non 3500)**. **Park: cit 3003 (sottostimate)**. Paper chiave: **CER (ACL 2025)**, MemGPT, CoALA.

**#152** — **Hoare: cit 1727 (non 6500, sovrastima 3.8x)**. Paper chiave: **Wink (Microsoft, 90% singolo intervento)**, AgentIF (NeurIPS 2025).

**#153** — **Gruschka (arXiv:2311.11438): FABBRICATA** (= fisica nucleare). Sostituto: Basak (ESEM 2023). Paper chiave: **FIDES (100% block injection)**.

**#154** — **Rothermel: cit 815 (non 1700)**. Ralph-Loop validato da **LLMLOOP (ICSME 2025, +9.2%)**. MuTAP: +28%.

**#155** — **Little: cit 2556 (non 6500)**. EVM per AI agent: territorio inesplorato. Paper chiave: **BRIDGE IRT**, **AgentBoard (NeurIPS 2024)**.

**#156** — **Papazoglou: cit 1184 (non 3800)**. Paper chiave: **MCP-Zero (architettura identica)**, **Tool2Vec (+27%)**, **ToolRet (ACL 2025)**.

#### Citazione fabbricata

| # | Paper | Problema | Sostituto |
|---|-------|----------|-----------|
| 153 | Gruschka et al. (arXiv:2311.11438) | arXiv ID = paper fisica nucleare | Basak et al. (ESEM 2023, arXiv:2307.00714) |

#### Correzioni algoritmiche

| # | Claim | Correzione |
|---|-------|------------|
| 147 | SWE-Agent "tool filtering per phase" | ACI statico; filtering per fase = contributo novel |
| 150 | Settles 2012 ~6500 cit | ~6500 = survey 2009; libro 2012 = ~695 |

#### Tabella correzioni citazioni (S2 vs claim, solo delta significativi)

| Paper | Claim | S2 | Delta | Item |
|-------|-------|----|-------|------|
| Lea | ~1400 | 549 | **-61%** | 149 |
| Settles (book) | ~6500 | 695 | **-89%** | 150 |
| Lin | ~3500 | 1,698 | -51% | 151 |
| Park | ~2000 | 3,003 | **+50%** | 151 |
| Hoare | ~6500 | 1,727 | **-73%** | 152 |
| Rothermel | ~1700 | 815 | -52% | 154 |
| Little | ~6500 | 2,556 | **-61%** | 155 |
| Boehm | ~3800 | 1,937 | -49% | 155 |
| Papazoglou | ~3800 | 1,184 | **-69%** | 156 |
| Toolformer | ~1500 | 2,625 | **+75%** | 156 |

21/25 sovrastimati (media -37%). 3 sottostimati (Anderson, Park, Toolformer).

#### Paper chiave scoperti (T1 top-10)

| Paper | Venue | Item | Perché |
|-------|-------|------|--------|
| LLMCompiler (Kim) | ICML 2024 | 149 | DAG planning, 3.7x speedup |
| KnowNo (Ren) | CoRL 2023 Best | 150 | Conformal prediction "when to ask" |
| HULA | ICSE SEIP 2025 | 150 | Coding agents in produzione (Atlassian) |
| CER (Liu) | ACL 2025 | 151 | Ponte Lin 1992 → LLM agents |
| AgentIF (Qi) | NeurIPS 2025 | 152 | GPT-4o 87→58.5 su vincoli agentic |
| LLMLOOP (Ravi) | ICSME 2025 | 154 | Stesso pattern pipeline (+9.2%) |
| Meta ACH | FSE 2025 | 154 | 10K classi, 93.4% fault detection |
| AgentBoard (Ma) | NeurIPS 2024 | 155 | Progress Rate ≈ SPI |
| AnyTool (Du) | ICML 2024 | 156 | Retrieval gerarchico 16K API |
| ToolRet | ACL 2025 | 156 | Retriever generici inadeguati |

#### Cross-connessioni dalla ricerca

| Connessione | Implicazione |
|-------------|-------------|
| FIDES ≈ #153 | Design quasi identico — differenziare su secret scanning + reversibilità |
| MCP-Zero ≈ #156 | Architettura quasi identica — validazione empirica |
| LLMLOOP ≈ #154 | Stesso feedback loop — validazione indipendente |
| Tool2Vec → #156 | Description-based subottimale; usage-driven +27% |
| Wink → #152 | 90% con singolo intervento — valida rate-limiting |
| BRIDGE → #155 | IRT più rigoroso del k-NN |
| MuTAP → #154 | Surviving mutants +28% test quality |

Report: `docs/research/{phased-execution-147,workspace-isolation-148,parallel-tools-149,human-interaction-150,persistent-memory-151,project-constraints-152,information-flow-153,validation-pipeline-154,progress-estimation-155,tool-discovery-156}.md`

---


### Ordine implementazione Fase 18

```
Fase 18a (collaboration, 8.0g):              #157 → #165 → #158
Fase 18b (production feedback, 4.5g):        #159 → #162
Fase 18c (economics, 7.0g):                  #160 → #161 → #164
Fase 18d (resilience, 4.5g):                 #163 → #166
```

### Riepilogo Fase 18 — Production Intelligence & Collaborative Coordination (#157-#166)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 157 | Shared Workspace Blackboard | `SharedBlackboardService` | 2.5g | Alto | 0 |
| 158 | Worker Negotiation Protocol | `WorkerNegotiationService` | 3.0g | Alto | 0 |
| 159 | Production Feedback Collector | `ProductionFeedbackService` | 2.5g | Alto | 0 |
| 160 | Cost Accounting & Budget Controller | `PlanCostAccountingService` | 2.0g | Alto | 0 |
| 161 | Adaptive Pipeline Configurator | `PipelineConfiguratorService` | 2.5g | Alto | 1 |
| 162 | Worker Self-Assessment | `WorkerSelfAssessmentService` | 2.0g | Alto | 0 |
| 163 | Conflict Resolution Arbiter | `ConflictResolutionArbiterService` | 2.5g | Alto | 1 |
| 164 | Canary Execution Strategy | `CanaryExecutionService` | 2.5g | Medio-Alto | 1 |
| 165 | Collaborative Code Understanding | `SharedCodeModelService` | 2.5g | Alto | 0 |
| 166 | Pipeline Degradation Manager | `DegradationManagerService` | 2.0g | Medio-Alto | 1 |
|   |     | **Totale Fase 18** | | **24.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§96-§105)

Claude Code patterns coperti (cumulativo Fasi 17-18): P2, P3, P5, P7, P8, P10, P14, P15, P18, P21, P22, P24, P26, P28 (14/17 gap chiusi)


### Sintesi ricerca accademica Fase 18 (S27, 2026-03-15)

10 report Template F completati. ~40 riferimenti validati, 0 fabbricati, ~80 paper aggiuntivi identificati.

#### Per-item summaries

**#157** — Blackboard: **Corkill venue ERRATA** (AI Expert, non AI Magazine). **Hayes-Roth cit ~144 (non ~1100, 8x)**. **Cosine < 0.3 per contradiction: FONDAMENTALMENTE SBAGLIATO** — embedding non distinguono contraddizioni da affermazioni fedeli. Usare NLI cross-encoder. Paper chiave: "The Semantic Illusion" (arXiv:2512.15068), LLM-Blackboard (arXiv:2510.01285).

**#158** — Negotiation: **Jennings: anno 2004→1998, venue AAMAS conf→journal**. **Smith cit ~4300 (non ~5800)**. CNP over-engineered per questo caso — letteratura converge su pre-assegnazione (MetaGPT SOP) + negoziazione solo per conflitti residui. GP tiebreaker: novel, nessun precedente.

**#159** — Production Feedback: 4 riferimenti reali. Kim/Bass sono **libri T7**, non letteratura accademica. Shapley per file attribution: **giustificato ma overkill** — approccio gerarchico: git blame pesato (default) + Shapley (escalation per 3+ contributor). Temporal decay 30g: parametrizzazione arbitraria. Paper chiave: **RUDDER (NeurIPS 2019)**, Data Shapley (ICML 2019), **SHARP (arXiv 2026)**.

**#160** — Cost Accounting: **FrugalGPT cit ~160 (non ~350, 2x)**. **Maelstrom cit ~13 (non ~150, 11x)** + rilevanza dubbia. **RouteLLM: ICLR 2025 (non solo arXiv)**. Paper chiave: **BATS (Google, arXiv:2511.17006)** — budget-aware agent scaling, modello diretto per budget controller.

**#161** — Pipeline Config: 4 riferimenti T1 tutti validi. **Auto-sklearn cit ~1728 (non ~4500)** — conflazione NeurIPS+book. **GP-UCB subottimale per 20 dim** — BOHB o SMAC superiori per mixed-type parameters. Soglia 10 piani: fragile senza warm-starting. Paper chiave: **ARTEMIS (arXiv:2512.09108)**, DSPy MIPROv2.

**#162** — Self-Assessment: 5 riferimenti tutti validi. **Kadavath: T2 arXiv (non peer-reviewed)**. **Platt scaling: non SOTA per LLM** — confidenze LLM clusterizzano vicino a 1.0. Sostituire con **temperature scaling adattivo** (Thermometer ICML 2024). **ECE ha patologie note** (binning sensitivity) — usare ACE o Brier Score come target. Sycophancy-calibration link **validato** (arXiv:2509.21305). Paper chiave: **Thermometer (ICML 2024)**, UQ Survey (ACM CS 2024).

**#163** — Conflict Arbiter: **ChatDev autore: Qian, non Tian**. **Tessier cit ~23 (non ~200, 10x)**, data 2000 non 2001. **ChatDev cit ~464 (non ~700)**. **Cosine < 0.3: stessa critica di #157** — usare NLI. BDI: valido come ispirazione architetturale, non implementazione diretta. Priority hierarchy ben fondata (Brewka & Eiter). Paper chiave: **ABBEL (arXiv:2512.20111)**, "Learning to Negotiate" (arXiv:2603.10476).

**#164** — Canary Execution: **Johari venue ERRATA** — non NeurIPS 2017, ma arXiv 2015 / **Operations Research 2022**. **Schermann venue ERRATA** — non JSS ma **IEEE Software 2018**. SPRT: ottimalità campionaria valida ma "5 piani consecutivi" statisticamente fragile — usare **mSPRT** o binomial test su finestra mobile. Hash routing: documentare partitioned ramps.

**#165** — Code Understanding: **CodexGraph autore: Liu, non Zhang**. Garcia cit ~54 (non ~600). Campo in rapida evoluzione 2024-2026. AGE appropriato per unified stack (Cypher compatibile con CodexGraph). Scout pattern validato (Willison 2025, blackboard classico). Paper chiave: **LocAgent (ACL 2025)**, **CGM (NeurIPS 2025)**, LogicLens (arXiv 2026).

**#166** — Degradation Manager: 4 riferimenti tutti validi. Circuit breaker per LLM: pratica standard ma non sufficiente — serve layered approach (retry→fallback→circuit breaker). Threshold "5 in 30s" nell'ordine corretto ma **rate-based > count-based** (Resilience4j). Paper chiave: **Sun et al. (SC'25, LLM non intrinsecamente resilienti)**, Portkey AI Gateway.

#### Correzione critica cross-item

| Problema | Item | Azione |
|----------|------|--------|
| Cosine distance per contradiction detection | #157, #163 | **Sostituire con NLI cross-encoder** (DeBERTa-v3-large-mnli) o approccio ibrido (cosine per topic + NLI per relazione logica) |

#### Correzioni algoritmiche

| # | Claim | Correzione |
|---|-------|------------|
| 157 | Cosine < 0.3 per contraddizioni | NLI cross-encoder. Embedding non catturano relazioni logiche |
| 158 | CNP puro per negoziazione | Ibrido: pre-assegnazione + negoziazione residui. FCFS declassare a fallback |
| 159 | Shapley per ogni file | Gerarchico: git blame (default) + Shapley (3+ contributor, alta severità) |
| 159 | Exponential decay 30g | Configurabile; considerare hyperbolic discounting |
| 160 | FrugalGPT cascade per codice | Serve quality gate basato su test execution, non confidence |
| 161 | GP-UCB per 20 parametri | BOHB/SMAC superiori per mixed-type high-dim |
| 162 | Platt scaling per calibrazione | Temperature scaling (Guo 2017) o Adaptive TS (EMNLP 2024) |
| 162 | ECE come metrica primaria | ACE o Brier Score come target; ECE come dashboard |
| 163 | BDI come implementazione | Solo ispirazione architetturale; usare "context reconciliation" |
| 164 | 5 piani consecutivi per rollback | mSPRT o binomial test su finestra mobile |
| 164 | SPRT per piccoli campioni | Considerare Bayesian con Beta prior per campioni < 100 |
| 166 | 5 failures in 30s (count-based) | Rate-based (50% su sliding window 10 chiamate) |

#### Tabella correzioni citazioni (S2 vs claim, solo delta significativi)

| Paper | Claim | Verificato | Delta | Item |
|-------|-------|------------|-------|------|
| Hayes-Roth | ~1100 | ~144 | **-87%** | 157 |
| Corkill | ~450 | ~767 | **+70%** | 157 |
| Smith | ~5800 | ~4300 | -26% | 158 |
| Jennings | ~3500 | ~2341 | -33% | 158 |
| FrugalGPT | ~350 | ~160 | **-54%** | 160 |
| Maelstrom | ~150 | ~13 | **-91%** | 160 |
| Auto-sklearn | ~4500 | ~1728 | **-62%** | 161 |
| Tessier | ~200 | ~23 | **-89%** | 163 |
| ChatDev | ~700 | ~464 | -34% | 163 |
| Garcia | ~600 | ~54 | **-91%** | 165 |

9/10 sovrastimati (media -52%). 1 sottostimato (Corkill, +70%).

#### Correzioni venue/autore

| Paper | Errore | Correzione | Item |
|-------|--------|------------|------|
| Corkill | AI Magazine | **AI Expert** | 157 |
| Jennings | AAMAS 2004 | **AAMAS journal 1998** | 158 |
| ChatDev | Tian et al. | **Qian** et al. | 163 |
| CodexGraph | Zhang et al. | **Liu** et al. | 165 |
| Johari | NeurIPS 2017 | **arXiv 2015 / Operations Research 2022** | 164 |
| Schermann | JSS 2018 | **IEEE Software 2018** | 164 |
| Tessier | 2001 | **2000** | 163 |
| RouteLLM | arXiv 2024 | **ICLR 2025** | 160 |

#### Paper chiave scoperti (T1 top-10)

| Paper | Venue | Item | Perché |
|-------|-------|------|--------|
| "The Semantic Illusion" | arXiv:2512.15068 | 157,163 | Distrugge approccio cosine per contradiction |
| BATS (Google) | arXiv:2511.17006 | 160 | Budget-aware agent scaling, modello diretto |
| ARTEMIS | arXiv:2512.09108 | 161 | Auto-config agent pipeline, 36.9% token reduction |
| Thermometer (Shen) | ICML 2024 | 162 | Task-adaptive temp scaling, supera Platt |
| RUDDER | NeurIPS 2019 | 159 | Return decomposition per delayed rewards |
| LocAgent | ACL 2025 | 165 | 92.7% file-level localization, graph-guided |
| CGM | NeurIPS 2025 | 165 | Graph structure in LLM attention, 43% SWE-bench |
| SHARP | arXiv:2602.08335 | 159 | Shapley credit gerarchico per multi-agent |
| Sun et al. | SC'25 | 166 | LLM non intrinsecamente resilienti ai fault |
| "Sycophancy Is Not One Thing" | arXiv:2509.21305 | 162 | Sycophancy separabile in latent space |

#### Cross-connessioni dalla ricerca

| Connessione | Implicazione |
|-------------|-------------|
| Cosine→NLI (#157,#163) | Entrambi necessitano NLI cross-encoder — componente condiviso |
| MetaGPT SOP → #158 | Pre-assegnazione strutturale > negoziazione runtime |
| RUDDER → #159 | Redistribuzione reward > discounting temporale |
| Thermometer → #162 | Temperature scaling adattivo sostituisce Platt |
| BATS → #160 | Budget tracker unificato: token + tool consumption |
| ARTEMIS → #161 | Evolutionary search valida per agent config |
| mSPRT → #164 | Johari è già tra i riferimenti — usare mSPRT, non SPRT |
| Layered resilience → #166 | retry→fallback→circuit breaker (Portkey pattern) |
| NLI + Blackboard (#157) ↔ Conflict Arbiter (#163) | Stesso componente NLI per contradiction detection |
| Bradley-Terry ↔ calibrazione (#162) | Stessa struttura logistic regression (Platt ≡ BT) |

Report: `docs/research/{blackboard-157,negotiation-158,production-feedback-159,cost-accounting-160,pipeline-config-161,conflict-arbiter-163,canary-execution-164,code-understanding-165,degradation-166}.md` + `docs/design-validation/item-162-worker-self-assessment.md`
