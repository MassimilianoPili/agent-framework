# TypeScript Agent SDK — Template Scaffold

## Struttura progetto

```
<project_name>/
├── README.md
├── .gitignore
├── .env.example
├── package.json
├── tsconfig.json
├── src/
│   ├── index.ts          # Entry point: crea e configura l'agente
│   ├── config.ts         # Caricamento configurazione da env
│   └── tools/
│       ├── index.ts      # Tool registry
│       └── example.ts    # Tool di esempio
└── tests/
    └── tools.test.ts     # Test base per i tool
```

## File: package.json

```json
{
  "name": "<project_name>",
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "build": "tsc",
    "start": "node dist/index.js",
    "dev": "tsx src/index.ts",
    "test": "vitest run",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@anthropic-ai/claude-code-sdk": "^0.1.0",
    "dotenv": "^16.4.0"
  },
  "devDependencies": {
    "typescript": "^5.6.0",
    "tsx": "^4.19.0",
    "vitest": "^2.1.0",
    "@types/node": "^22.0.0"
  }
}
```

## File: tsconfig.json

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "outDir": "dist",
    "rootDir": "src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "declaration": true
  },
  "include": ["src/**/*"],
  "exclude": ["node_modules", "dist", "tests"]
}
```

## File: src/index.ts

```typescript
/**
 * Agent entry point.
 *
 * Configura l'agente Claude con i tool definiti in tools/ e avvia
 * il loop di interazione. Usa claude-code-sdk per la comunicazione.
 */
import { Agent, AgentConfig } from "@anthropic-ai/claude-code-sdk";
import { loadConfig } from "./config.js";
import { getTools } from "./tools/index.js";

async function main(): Promise<void> {
  const config = loadConfig();

  const agent = new Agent({
    config: new AgentConfig({
      model: config.model,
      maxTokens: config.maxTokens,
    }),
    tools: getTools(),
    systemPrompt: config.systemPrompt,
  });

  const result = await agent.run(config.initialPrompt);
  console.log(result);
}

main().catch(console.error);
```

## File: src/config.ts

```typescript
/** Configurazione da variabili d'ambiente. */
import "dotenv/config";

export interface AppConfig {
  model: string;
  maxTokens: number;
  systemPrompt: string;
  initialPrompt: string;
}

export function loadConfig(): AppConfig {
  return {
    model: process.env.CLAUDE_MODEL ?? "claude-sonnet-4-6",
    maxTokens: parseInt(process.env.MAX_TOKENS ?? "8192", 10),
    systemPrompt: process.env.SYSTEM_PROMPT ?? "You are a helpful assistant.",
    initialPrompt: process.env.INITIAL_PROMPT ?? "Hello",
  };
}
```

## File: src/tools/index.ts

```typescript
/** Tool registry. Importa e registra tutti i tool disponibili. */
import { exampleTool } from "./example.js";

export function getTools(): unknown[] {
  return [exampleTool];
}
```

## File: src/tools/example.ts

```typescript
/** Tool di esempio — sostituire con implementazione reale. */

export interface ExampleToolInput {
  query: string;
}

export function exampleTool(input: ExampleToolInput): string {
  return `Risultato per: ${input.query}`;
}
```

## File: .env.example

```
CLAUDE_MODEL=claude-sonnet-4-6
MAX_TOKENS=8192
SYSTEM_PROMPT=You are a helpful assistant.
INITIAL_PROMPT=Hello
```

## File: .gitignore

```
node_modules/
dist/
.env
*.tsbuildinfo
```

## File: tests/tools.test.ts

```typescript
import { describe, it, expect } from "vitest";
import { exampleTool } from "../src/tools/example.js";

describe("exampleTool", () => {
  it("returns a string containing the query", () => {
    const result = exampleTool({ query: "test" });
    expect(result).toContain("test");
    expect(typeof result).toBe("string");
  });
});
```

## Verifica sintassi

```bash
npx tsc --noEmit
```

Se fallisce, correggi gli errori di tipo e ripeti (max 3 iterazioni).

**Nota**: `npm install` NON deve essere eseguito dal worker.
La verifica si limita al type checking con `tsc --noEmit` assumendo
che i tipi dei pacchetti siano disponibili.
Per progetti scaffold, e' accettabile verificare solo la sintassi
TS base senza le dipendenze installate.
