# Fase 20 — Execution Grounding & Adaptive Evolution (#177-#186)

**Tema**: Il framework non può verificare ciò che produce — nessuna compilazione, test, o git safety. Ogni piano parte da zero senza transfer learning. Fase 20 ground il framework nella realtà (bash → git safety → compile-test-fix) e lo rende adattivo (cross-plan learning, self-improvement, project lifecycle). Sblocca finalmente #25 (mcp-bash-tool), P16 e P18.

→ Indice master: [PIANO.md](PIANO.md) | Documentazione implementativa: [13-fase-20-execution-grounding.md](documentazione/13-fase-20-execution-grounding.md)

---

### Ordine implementazione Fase 20

```
Fase 20a (execution grounding, 7.5g):     #177 → #185 → #186
Fase 20b (intelligence & learning, 6.0g): #178 → #182
Fase 20c (lifecycle & integration, 6.5g): #184 → #179 → #180
Fase 20d (measurement & output, 4.5g):    #181 → #183
```

### Riepilogo Fase 20 — Execution Grounding & Adaptive Evolution (#177-#186)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 177 | Execution Runtime Orchestrator | `ExecutionRuntimeOrchestrator` | 3.0g | Molto alto | 0 |
| 178 | Cross-Plan Knowledge Transfer Engine | `CrossPlanKnowledgeEngine` | 3.0g | Alto | 1 |
| 179 | Conversational Requirements Elicitor | `RequirementsElicitorService` | 2.5g | Alto | 0 |
| 180 | Multi-Plan Project Lifecycle Manager | `ProjectLifecycleManager` | 3.5g | Alto | 1 |
| 181 | Longitudinal Effectiveness Benchmark | `EffectivenessBenchmarkService` | 2.5g | Alto | 0 |
| 182 | Self-Improving Prompt & Strategy Optimizer | `SelfImprovingOptimizerService` | 3.0g | Alto | 1 |
| 183 | Architectural Visualization Generator | `VisualizationGeneratorService` | 2.0g | Medio-Alto | 1 |
| 184 | External System Integration Hub | `ExternalIntegrationHubService` | 3.0g | Alto | 0 |
| 185 | Git Safety Protocol Enforcer | `GitSafetyProtocolService` | 2.0g | Alto | 0 |
| 186 | Compile-Test-Fix Verification Loop | `CompileTestFixLoopService` | 2.5g | Molto alto | 0 |
|   |     | **Totale Fase 20** | | **27.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§116-§125)

Claude Code patterns sbloccati (Fase 20): P16 (Git safety), P18 (Test running)
Cumulativo Fasi 17-20: 24/28 pattern coperti. Residui: 🔧 parziali + P23 (N/A)

### Sintesi ricerca accademica Fase 20 (S29, 2026-03-15)

10 item validati con ricerca accademica (Template F). ~30 paper verificati, ~60 nuovi paper trovati. Report completi in `docs/research/`.

#### Per-item summaries

**#177 Execution Runtime** — ChatDev sottostimato (~601 vs ~464). Bui singolo autore. OpenHands (ICLR 2025, ~436 cit) reference per sandbox. Docker + cgroups v2 raccomandato. Nessun sistema implementa approval gates per comandi distruttivi — gap che #177+#185 colmano.

**#178 Cross-Plan Knowledge** — Voyager **TMLR 2024** (non NeurIPS 2023 Spotlight). CoPS (Yang 2024) alternativa superiore al GP posterior (regret bounds, scala meglio). ACE (Zhang 2025, ~72 cit) "evolving playbooks".

**#179 Requirements Elicitor** — KnowNo confermato CoRL 2023 Oral (non Best Paper). CP applicabile con caveat (exchangeability). ReqElicitGym (Feb 2026): primo benchmark. Bashir (ICSME 2025): LLM +20.2% su requisiti ambigui.

**#180 Project Lifecycle** — Erol sottostimato (~892 vs ~711). Georgievski sovrastimato (~168 vs ~335). Li AOP: ICLR **2024**. SagaLLM (VLDB 2025): Saga pattern per lifecycle. Nessun paper formalizza "sprint autonomi" — contributo originale.

**#181 Effectiveness Benchmark** — BOCPD gonfiate (~850 vs ~1800), arXiv-only, i.i.d. assumption. xbench (Chen 2025, ~59 cit): quasi identico a #181.

**#182 Self-Improving Optimizer** — Promptbreeder: **ICML 2024**. 5 rischi: mode collapse, reward hacking, prompt bloat, safety drift, regression. Godel Agent (ACL 2025) come ispirazione.

**#183 Visualization Generator** — Brown C4 anno **2012**. **Spinellis: correzione grave** (titolo/anno/cit errati). MermaidSeqBench: LLM hanno capability gaps. Patidar (2025): C4 automatico + validazione ibrida.

**#184 External Integration** — Hohpe EIP gonfiate (~1412 vs ~3200). HULA titolo corretto. Ghaleb (MSR 2026): agenti toccano CI/CD solo 3.25%. Architettura #184 originale.

**#185 Git Safety** — AgentSpec autori **Wang, Poskitt, Sun**. "Predicting Faults" autori **Kim, Zimmermann** ICSE **2007**. Nessun paper su git safety per AI agents. Solo **17% defense rate** senza safety layer (Shan 2026).

**#186 Compile-Test-Fix** — Self-Refine sottostimato (~2961 vs ~1000). 2-3 iterazioni ottimali. Test feedback > compiler di **9.4-18.9 punti**. RepairAgent (ICSE 2025, ~262 cit) reference.

#### Correzioni citazioni

| Paper | Claimed | Verified (S2) | Delta |
|-------|---------|---------------|-------|
| Self-Refine (NeurIPS 2023) | ~1000 | ~2961 | +196% |
| Erol HTN (AAAI 1994) | ~711 | ~892 | +25% |
| ChatDev (ACL 2024) | ~464 | ~601 | +29% |
| BOCPD (arXiv 2007) | ~1800 | ~850 | -53% |
| Georgievski (AI 2015) | ~335 | ~168 | -50% |
| Hohpe EIP (2003) | ~3200 | ~1412 | -56% |
| Spinellis (IEEE Sw 2003) | ~150 | ~39 | -74% |

#### Correzioni venue/autore (11)

Voyager: TMLR 2024 (non NeurIPS 2023). Li AOP: ICLR 2024 (non 2025). Promptbreeder: ICML 2024 (non arXiv-only). Brown C4: 2012 (non 2018). Spinellis: "On the Declarative Specification of Models" IEEE Software 2003 (non 2008). AgentSpec: Wang, Poskitt, Sun (non Zhou). Predicting Faults: Kim, Zimmermann ICSE 2007 (non Bird ICSE 2004). Bui: singolo autore. AgentReuse/HULA/LLMLOOP: titoli e track corretti.

#### Paper fabbricati: nessuno (Fase 19 ne aveva 4)

#### Top-10 paper scoperti

OpenHands (ICLR 2025, ~436 cit, #177), SagaLLM (VLDB 2025, #180), CoPS (arXiv 2024, #178), ACE (arXiv 2025, ~72 cit, #178), RepairAgent (ICSE 2025, ~262 cit, #186), xbench (arXiv 2025, ~59 cit, #181), ReqElicitGym (arXiv 2026, #179), Ghaleb CI/CD (MSR 2026, #184), AgentSpec (ICSE 2026, ~27 cit, #185), FeedbackEval (arXiv 2025, #186).

#### Correzioni algoritmiche (10)

1. GP posterior #178: O(n^3), usare CoPS o sparse GP
2. BOCPD #181: i.i.d. assumption, usare GP-BOCPD o pre-differencing
3. CP #179: exchangeability non garantita, usare adaptive CP
4. Pattern matching git #185: insufficiente, serve analisi contestuale
5. Iterations #186: 2-3 ottimali, mixed feedback massimizza
6. Prompt evolution #182: diversity penalty + multi-objective + rotazione giudice
7. C4 #183: validation post-generazione obbligatoria
8. Webhook #184: idempotency keys obbligatorie
9. Saga #180: compensating actions, adottare SagaLLM
10. Error patterns #178: triple contestuali (contesto, errore, correzione, confidence)

#### Cross-connessioni dalla ricerca (8)

OpenHands SDK → #177+#185 | SagaLLM → #180+#143 | CoPS → #178+#11 | AgentSpec → #185+#168 | FeedbackEval → #186+#127 | xbench → #181+#111 | ReqElicitGym → #179 | Pillar Security → #185+#153

Report: `docs/research/execution-runtime-177.md`, `cross-plan-knowledge-178.md`, `requirements-elicitor-179.md`, `project-lifecycle-180.md`, `benchmark-visualization-181-183.md`, `self-improvement-182.md`, `external-integration-184.md`, `git-safety-185.md`, `compile-test-fix-186.md`
