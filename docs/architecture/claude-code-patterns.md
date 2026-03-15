# Pattern Claude Code → Agent Framework

> Spostato da PIANO.md — mappatura sistematica dei pattern architetturali di Claude Code
> che il framework puo' adottare. Ogni pattern valutato per stato e roadmap item.

## 1. Context Management

| # | Pattern | Stato | Mapping |
|---|---------|-------|---------|
| P1 | Auto-compacting | 🔧 B17 L2 | Worker SDK stima token pre-call, compatta se >75% context |
| P2 | Project instructions (CLAUDE.md) | ❌ | Campo `projectInstructions` nel Plan |
| P3 | Persistent memory (MEMORY.md) | ❌ | `WorkerMemory` nel DB, per-project |
| P4 | Session resume | 🔧 parziale | Event Sourcing permette replay, ma worker non riprendono |
| P5 | System reminders | ❌ | `ToolResultEnricher` — aggiunge contesto ai tool result |

## 2. Planning & Execution

| # | Pattern | Stato | Mapping |
|---|---------|-------|---------|
| P6 | Plan mode (discovery) | 🔧 parziale | Manca fase di discovery pre-planning |
| P7 | Progress tracking (TodoWrite) | ❌ | Worker emette eventi PROGRESS via Redis |
| P8 | Phased execution | ❌ | `WorkerPhase` enum (EXPLORE, IMPLEMENT, VERIFY) |
| P9 | Subagent delegation | ✅ | Il framework E' questo |
| P10 | Parallel tool calls | ❌ | `ParallelToolCallingManager` (spring-ai-reactive-tools) |

## 3. Safety & Policy

| # | Pattern | Stato | Mapping |
|---|---------|-------|---------|
| P11 | 3-level permission | ✅ | Manifest allowlist + task-level policy + path ownership |
| P12 | Pre/Post tool hooks | 🔧 parziale | HookPolicy controlla, ma no hook scriptabili |
| P13 | Dangerous command detection | 🔧 parziale | PathOwnershipEnforcer, no content inspection |
| P14 | Secret scanning | ❌ | Hook PostToolUse su fs_write, regex pattern |
| P15 | Reversibility assessment | ❌ | `dangerousTools` in manifest |
| P16 | Git safety protocol | ❌ | Rilevante con mcp-bash-tool (#25) |

## 4. Quality & Review

| # | Pattern | Stato | Mapping |
|---|---------|-------|---------|
| P17 | Proactive code review | 🔧 parziale | Ralph-Loop (#16) |
| P18 | Test running after changes | ❌ | Richiede #25 (bash tool) |
| P19 | Code simplification | ❌ | Possibile SIMPLIFIER worker |
| P20 | Comment analysis | ❌ | Possibile check in AUDIT_MANAGER |

## 5. Communication & UX

| # | Pattern | Stato | Mapping |
|---|---------|-------|---------|
| P21 | Human-in-the-loop | ❌ | Stato WAITING_INPUT + SSE |
| P22 | Progress reporting | 🔧 parziale | SSE per cambio stato, no visibilita' interna worker |
| P23 | Output styles | ❌ | Non rilevante per agenti autonomi |
| P24 | Insight blocks | 🔧 parziale | `selfAssessment` in WorkerResult (#162) — insight block strutturato |

## 6. Tool Architecture

| # | Pattern | Stato | Mapping |
|---|---------|-------|---------|
| P25 | Dedicated tools over shell | ✅ | Tool MCP dedicati |
| P26 | Deferred tool loading | ❌ | `ToolSearch` MCP tool |
| P27 | Background tasks | ✅ | Framework intrinsecamente asincrono |
| P28 | Worktrees (isolation) | ❌ | `WorkerWorkspace` con directory isolata |

## Priorita'

| Priorita' | Pattern | Sforzo | Roadmap |
|-----------|---------|--------|---------|
| CRITICA | P1 Auto-compacting | 2g | B17 |
| ALTA | P2 Project instructions | 0.5g | Nuovo |
| ALTA | P7 Progress tracking | 1g | #5 (SSE) |
| ALTA | P28 Worktrees | 2g | Nuovo |
| ALTA | P22 Progress reporting | 1g | #5 (SSE) |
| MEDIA | P5-P8, P10, P14, P3 | ~6g | Vari |
| BASSA | P4, P15, P18, P26 | ~3g | Vari |

**Totale**: ~18g per tutti, ~7g per CRITICA+ALTA.
