# SDK Scaffold — Skill Definition

## Obiettivo

Generare un progetto Agent SDK completo e verificato. Il progetto deve essere
immediatamente eseguibile dopo l'installazione delle dipendenze.

## Linguaggi supportati

- **Python** (Claude Agent SDK per Python) → vedi `python-template.md`
- **TypeScript** (Claude Agent SDK per TypeScript) → vedi `typescript-template.md`

## Flusso di lavoro

1. **Analisi**: leggi la descrizione del task per determinare:
   - Linguaggio target (Python o TypeScript)
   - Nome del progetto
   - Tool da implementare (se specificati)
   - Configurazioni speciali (MCP server, deployment, etc.)

2. **Generazione**: segui il template specifico per il linguaggio.
   Crea tutti i file nella directory `generated/<project_name>/`.

3. **Verifica (Ralph-Loop inline)**:
   ```
   per iterazione in [1, 2, 3]:
     esegui check sintattico
     se errori:
       identifica e correggi
     altrimenti:
       break → successo
   ```

4. **Report**: restituisci il JSON di risultato con lista file, stato verifica, summary.

## Struttura output attesa

```
generated/<project_name>/
├── README.md               # Istruzioni setup e uso
├── .gitignore               # Pattern standard per il linguaggio
├── .env.example             # Template variabili d'ambiente
├── src/                     # Codice sorgente
│   ├── agent.{py,ts}        # Entry point agente
│   ├── tools/               # Definizioni tool
│   │   └── ...
│   └── config.{py,ts}       # Configurazione
├── tests/                   # Test base
│   └── ...
└── {requirements.txt,package.json}  # Dipendenze
```

## Regole

- NON installare dipendenze (solo generare i file)
- NON eseguire il progetto (solo verificare la sintassi)
- Usa path relativi dentro `generated/`
- Includi commenti esplicativi nel codice generato
- Segui le convenzioni del linguaggio (PEP 8 per Python, ESLint standard per TS)
