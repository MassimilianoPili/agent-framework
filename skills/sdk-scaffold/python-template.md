# Python Agent SDK — Template Scaffold

## Struttura progetto

```
<project_name>/
├── README.md
├── .gitignore
├── .env.example
├── requirements.txt
├── pyproject.toml
├── src/
│   ├── __init__.py
│   ├── agent.py          # Entry point: crea e configura l'agente
│   ├── config.py         # Caricamento configurazione da env
│   └── tools/
│       ├── __init__.py
│       └── example.py    # Tool di esempio
└── tests/
    ├── __init__.py
    └── test_tools.py     # Test base per i tool
```

## File: requirements.txt

```
claude-code-sdk>=0.1.0
python-dotenv>=1.0.0
```

## File: pyproject.toml

```toml
[project]
name = "<project_name>"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "claude-code-sdk>=0.1.0",
    "python-dotenv>=1.0.0",
]

[project.scripts]
<project_name> = "src.agent:main"
```

## File: src/agent.py

```python
"""
Agent entry point.

Configura l'agente Claude con i tool definiti in tools/ e avvia
il loop di interazione. Usa claude-code-sdk per la comunicazione.
"""
import asyncio
from claude_code_sdk import Agent, AgentConfig
from .config import load_config
from .tools import get_tools


async def run():
    config = load_config()
    agent = Agent(
        config=AgentConfig(
            model=config["model"],
            max_tokens=config["max_tokens"],
        ),
        tools=get_tools(),
        system_prompt=config.get("system_prompt", "You are a helpful assistant."),
    )
    result = await agent.run(config["initial_prompt"])
    print(result)


def main():
    asyncio.run(run())


if __name__ == "__main__":
    main()
```

## File: src/config.py

```python
"""Configurazione da variabili d'ambiente."""
import os
from dotenv import load_dotenv


def load_config() -> dict:
    load_dotenv()
    return {
        "model": os.getenv("CLAUDE_MODEL", "claude-sonnet-4-6"),
        "max_tokens": int(os.getenv("MAX_TOKENS", "8192")),
        "system_prompt": os.getenv("SYSTEM_PROMPT", ""),
        "initial_prompt": os.getenv("INITIAL_PROMPT", "Hello"),
    }
```

## File: src/tools/__init__.py

```python
"""Tool registry. Importa e registra tutti i tool disponibili."""
from .example import example_tool


def get_tools() -> list:
    return [example_tool]
```

## File: src/tools/example.py

```python
"""Tool di esempio — sostituire con implementazione reale."""


def example_tool(query: str) -> str:
    """
    Cerca informazioni relative alla query.

    Args:
        query: Testo da cercare

    Returns:
        Risultato della ricerca
    """
    return f"Risultato per: {query}"
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
__pycache__/
*.pyc
.env
.venv/
dist/
*.egg-info/
```

## File: tests/test_tools.py

```python
"""Test base per i tool."""
from src.tools.example import example_tool


def test_example_tool_returns_string():
    result = example_tool("test")
    assert isinstance(result, str)
    assert "test" in result
```

## Verifica sintassi

```bash
python -m py_compile src/agent.py
python -m py_compile src/config.py
python -m py_compile src/tools/example.py
python -m py_compile tests/test_tools.py
```

Se qualche file fallisce la compilazione, correggi l'errore e ripeti.
