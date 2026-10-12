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

## Menu mode and decision models (Jev)

A model with `"decisionMode": "menu"` does not call action functions. Each turn the mod builds a short
menu of options from the bot's situation (mine a nearby block, craft, eat, attack a mob, go to or follow a
player, explore N/E/S/W, say hi, wait) and the model picks one id. Any chat model can run this way (it gets
one forced `choose` tool), which makes it easy to compare models on the same choices. Providers of
`"type": "typesafe"` are decision models such as TypeSafe AI's Jev: they return one choice plus per-option
probabilities and always use menu mode. The decision log gains `mode`, `option`, `probabilities` and `confidence`.
`decisionsPath` is where decisions are posted under `baseUrl` (default `/v1/systemone`).

```json
"providers": {
  "jev-or": { "type": "typesafe", "baseUrl": "https://openrouter.ai", "decisionsPath": "/api/alpha/decisions", "key": "openrouter", "rpm": 30, "rpd": 0, "timeoutSeconds": 15 },
  "typesafe": { "type": "typesafe", "baseUrl": "https://api.typesafe.ai", "key": "typesafe", "rpm": 60, "rpd": 0, "timeoutSeconds": 10 }
},
"models": {
  "jev": { "provider": "jev-or", "model": "typesafe/jev-1.13", "decisionMode": "menu" },
  "gemma-menu": { "provider": "gemma", "model": "gemma-4-26b-a4b-it", "decisionMode": "menu" }
}
```

On OpenRouter use `typesafe/jev-1.13`; `typesafe/jev-router` is a chat router to other models, not Jev.

## Actions

Bots play survival with the same limits as a player (real break times, correct tools, crafting tables,
furnaces and fuel, reach, attack cooldown). Each action ends as done or failed with a reason.

| Action | What it does |
|--------|--------------|
| `say(text)` | Public chat |
| `goTo(x, y, z)`, `goToPlayer(name)`, `follow(name, seconds)` | Walk (swim, jump, dig, pillar and bridge as needed) |
| `explore(direction, distance)` | Walk N/NE/E/SE/S/SW/W/NW up to 128 blocks |
| `mine(block, count)` | Mine `count` more (1 to 64), moving between blocks or trees; `log` means any tree |
| `collect(item)` | Pick up nearby dropped items |
| `craft(item, count)` | Crafts intermediates (planks, sticks, a table) and places a crafting table if needed |
| `smelt(item, count)` | Smelts in a furnace with inventory fuel; places a furnace if it has or can craft one |
| `place(block, x, y, z)` | Places a carried block within reach |
| `eat()` | Eats the best food carried |
| `equip(item)` | Wears armor or holds a tool |
| `attack(target)` | Fights a mob type or player with the best weapon carried |
| `stop()` | Stand still |

Tip: one turn can chain up to 6 actions (`maxActionsPerDecision`), for example
`mine log 6 -> craft wooden_pickaxe -> mine stone 3 -> craft stone_pickaxe`.

Fighting mobs needs the world difficulty set to Easy or higher; on Peaceful hostile mobs do not spawn.

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

## Limits

No building plans or chests yet.

A bot that dies respawns like a player (at the world spawn or its bed) with full health, and is
told what killed it and where its items dropped.

AI players count as awake players, so humans cannot skip the night by sleeping.
