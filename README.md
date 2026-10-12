# AiCiv

AI players for Minecraft 26.3, each driven by a language model (Gemini, Groq, OpenRouter,
GitHub Models, Mistral, Cerebras, OpenCode Zen or a local Ollama model). They join as normal
players, chat, and walk around. Milestone 1 of the design in
`docs/superpowers/specs/2026-10-11-aiciv-design.md`.

Server-side only: viewers join with an unmodded 26.3 client. Works in singleplayer too.

## Install

Put `aiciv-0.1.0.jar` and Fabric API 0.161.0+26.3 in `mods/` of a Fabric 26.3 server or client.

## Keys

Open a world once (singleplayer) or start the server once (dedicated): the config files are
written when the world or server starts, not at the title screen. That creates `config/aiciv/aiciv.json` and an empty `config/aiciv/keys.json`.
Put keys in `keys.json`, never in `aiciv.json`:

```json
{ "gemini": "your-key", "groq": "your-key" }
```

Or set environment variables instead: `AICIV_KEY_GEMINI`, `AICIV_KEY_GROQ`, and so on.
Run `/ai reload` after changing `keys.json`; a key set through an environment variable needs
the game or server relaunched.

## Providers and limits

`aiciv.json` lists providers (`baseUrl`, `key`, `rpm`, `rpd`, `resetZone`, `maxInFlight`, `timeoutSeconds`)
and models (`provider`, `model`, `fallback`, `maxTokens`, `extra`). The limits in a fresh
file are examples: replace them with your real tier limits. `0` means unlimited. A model
whose provider is down, rate limited or out of daily budget hands its turn to its
`fallback` model. `timeoutSeconds` (default 30, minimum 5) is the HTTP timeout per request:
raise to 60 for slow models like gemini-3.8-flash.

A fresh `aiciv.json` ships only `gemini`, `groq`, `opencode` and `ollama`. OpenRouter,
GitHub Models, Mistral and Cerebras must be added by hand, for example:

```json
"providers": {
  "openrouter": { "baseUrl": "https://openrouter.ai/api/v1", "key": "openrouter", "rpm": 20, "rpd": 200, "resetZone": "UTC", "maxInFlight": 2 }
},
"models": {
  "openrouter-llama": { "provider": "openrouter", "model": "meta-llama/llama-3.3-70b-instruct:free", "fallback": null }
}
```

Add each entry inside the existing `providers` and `models` objects, then put `openrouter` in `keys.json`.

Gemini counts thinking tokens against `maxTokens`, so `gemini-flash` ships with 4096. If a
Gemini model still returns empty replies, check `finish_reason` in the decision log: `length`
means the budget ran out. Raise `maxTokens` or add `"extra": {"reasoning_effort": "none"}`.

## Commands (op level 2)

In singleplayer, `/ai` needs cheats enabled for the world.

| Command | What it does |
|---------|--------------|
| `/ai spawn <name> [model] [persona...]` | Adds an AI player at your position |
| `/ai remove <name>` | Removes it from the world and the roster |
| `/ai list` | Every AI player and what it is doing |
| `/ai goal <name> <text>` | Sets its long-term goal |
| `/ai pause [name\|all]`, `/ai resume [name\|all]` | Stops or restarts thinking and acting |
| `/ai status <name>` | Details for one AI player |
| `/ai budget` | Requests used and left per provider, and provider health |
| `/ai reload` | Re-reads `aiciv.json` and `keys.json` |

AI players notice chat within 64 blocks, and any chat that names them from anywhere.

## Logs

Every decision goes to `logs/aiciv/<name>.jsonl`: what woke the bot, which provider and model
answered, the model's thought, the actions, and any problems.

## Testing without spending quota

A provider with `"type": "scripted"` and `"script": "scripted.json"` replays assistant
messages from `config/aiciv/scripted.json` in order, for example
`[{"content": null, "tool_calls": [{"function": {"name": "say", "arguments": "{\"text\": \"hi\"}"}}]}]`.

Developers: `./gradlew test` runs the unit tests, `./gradlew runGametest` the in-game tests.

## Milestone 1 limits

No mining, crafting or building yet, and no respawn: a bot that dies leaves the world, and
rejoins automatically at its death spot, with full health, the next time the world loads or the
server starts (`/ai spawn <name>` brings it back sooner).

AI players count as awake players, so humans cannot skip the night by sleeping.
