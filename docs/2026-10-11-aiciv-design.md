# AiCiv: LLM-driven AI players for Minecraft 26.3

Date: 2026-10-11
Status: approved design, not yet implemented

## Goal

A server-side Fabric mod that puts 10 to 20 AI-controlled players into one Minecraft 26.3
world and lets them found a civilization: gather, craft, build homes, trade, and talk to
each other and to human players in chat. The output is meant to be recorded for videos in
the style of the "AIs play Minecraft" genre.

Success means:

1. Unmodded 26.3 clients (the recorder, viewers) join and see the AIs as ordinary players:
   tab list, join and death messages, normal chat lines.
2. Each AI is driven by a configurable LLM (Gemini, Groq, OpenRouter, GitHub Models,
   Mistral, Cerebras, OpenCode Zen, local Ollama), mixable per bot.
3. 15 bots run for hours inside the user's free-tier quotas without stalling, because the
   LLM makes coarse decisions and Java code does the tick-level work.
4. Everything a bot does is something a survival player could do. No cheats, no commands.

## Non-goals

- Client-side control or Baritone. Baritone drives one `LocalPlayer` per game client; it
  cannot drive server-side players. Running one client per AI is rejected as too heavy.
- Mindcraft / Mineflayer. Mineflayer has no confirmed 26.3 support, and the user wants a mod.
- Per-block LLM control of building. Building goes through templates (see Actions).
- Screenshot or vision input. The model sees a text snapshot only.
- Rotating API keys or creating extra projects to get around a provider's quota. One
  quota is one budget.
- Custom skins in v1. Bots use the default skin their UUID selects. Copying a real
  account's skin is deferred.
- Combat-focused scenarios (team PvP, hunger games). The first scenario is civilization.

## Constraints and facts

- Target Minecraft **26.3** (released 2026-09-15), Fabric loader 0.19.5,
  fabric-api `0.161.0+26.3`, `net.fabricmc.fabric-loom` 1.18.2, Java 25. These are copied
  from `SummitSpawn/`, which already builds and runs on 26.3 against the stock Fabric
  Maven. Minecraft 26.x ships unobfuscated, so code uses Mojang names and needs no
  `mappings` line.
- Server-side only. Works on a dedicated server and in singleplayer (integrated server).
  No client-side component exists, so viewers need no mod.
- Free-tier limits differ by provider, change over time, and for Gemini apply per Google
  Cloud project, not per key. No limit is hardcoded; all limits come from config.
- API keys never enter the repository. See Configuration.
- Lives in a new `AiCiv/` gradle project in this repo, on branch `aiciv` cut from `main`.

## Architecture

Eight packages under `com.aiciv`, each with one job and a narrow interface.

| Package | Responsibility | Depends on |
|---------|----------------|------------|
| `bot` | Fake-player lifecycle: spawn, remove, respawn, persist the roster. `/ai` commands. | `brain`, `config` |
| `perception` | Builds the text snapshot a bot sees. | Minecraft world only |
| `brain` | Decides when a bot thinks, runs the think queue, turns LLM replies into queued actions, keeps the event log. | `llm`, `perception`, `actions`, `memory` |
| `llm` | OpenAI-compatible chat-completions client, rate limiter, provider health, fallback models. | `config` |
| `actions` | Executes one long-running action per bot per tick and reports done/failed with a reason. | `nav`, Minecraft |
| `nav` | Server-side A* pathfinder and the movement controller that walks a fake player along a path. | `BlockView` interface |
| `memory` | Per-bot notes the bot writes for itself. Persisted with the world. | none |
| `config` | Loads `aiciv.json` and `keys.json`. Hot-reloadable with `/ai reload`. | none |

```
            wake trigger (action done/failed, hurt, mentioned, idle timer)
                                   |
                                   v
   perception ---snapshot--->  brain.ThinkQueue ---request---> llm.Client --HTTP--> provider
                                   ^                                  |
                                   |             tool calls / text    |
   memory  ---notes------------>   +<---------------------------------+
                                   |
                                   v
                          actions.Executor  --path requests-->  nav.Pathfinder
                                   |
                                   v
                          fake ServerPlayer (ticks every server tick)
```

Threading: `llm` calls are asynchronous (`java.net.http.HttpClient`). Replies are handed
back to the server thread through the server's task queue. Nothing outside `llm` touches
the network, and nothing in `llm` touches the world.

`nav` reads blocks only through a `BlockView` interface, so the pathfinder is unit
testable on hand-built grids without Minecraft.

## Bots (`bot`)

Each AI is a fake `ServerPlayer` with a no-op network connection, placed into the world
through the player list so the server treats it as a real player. It gets join/leave
messages, a tab list entry, vanilla playerdata saving (inventory, position, health), and
vanilla physics and gravity on every server tick.

Spawning and moving a fake player on 26.3 is the riskiest unknown in the design, so it is
the first task of milestone 1 and is proven in-game before anything builds on it.

Names follow vanilla rules (3 to 16 characters, `[A-Za-z0-9_]`). The UUID is derived from
the name (offline-style), so a bot keeps its playerdata across restarts.

**Commands** (permission level 2):

| Command | Effect |
|---------|--------|
| `/ai spawn <name> <model> [persona...]` | Adds a bot. `model` is a key in `aiciv.json` `models`. Persona is free text. |
| `/ai remove <name>` | Logs the bot out and deletes it from the roster. Playerdata stays on disk. |
| `/ai list` | Name, model, state (thinking / acting / waiting / paused), current action. |
| `/ai goal <name> <text>` | Sets the bot's long-term goal, shown in its system prompt. |
| `/ai pause [name\|all]` / `/ai resume [name\|all]` | Paused bots make no LLM calls and stop acting. For recording breaks and saving quota. |
| `/ai status <name>` | Last snapshot size, last decision, queue position, notes count. |
| `/ai budget` | Per provider: requests used today, remaining, current effective idle interval. |
| `/ai reload` | Reloads `aiciv.json` and `keys.json`. |

**Roster** is saved to `<world>/aiciv/roster.json`: name, UUID, model key, persona, goal,
paused flag. On server start, every roster bot rejoins.

**Death**: the bot respawns immediately at its spawn point. The event
`you died: <vanilla death message>. Your items dropped at x y z` goes into its event log.

## Configuration (`config`)

`config/aiciv/aiciv.json` in the game directory:

```json
{
  "providers": {
    "gemini":   { "baseUrl": "https://generativelanguage.googleapis.com/v1beta/openai", "key": "gemini",   "rpm": 10, "rpd": 250, "resetZone": "America/Los_Angeles", "maxInFlight": 2 },
    "groq":     { "baseUrl": "https://api.groq.com/openai/v1",                          "key": "groq",     "rpm": 30, "rpd": 1000, "resetZone": "UTC", "maxInFlight": 2 },
    "opencode": { "baseUrl": "https://opencode.ai/zen/v1",                              "key": "opencode", "rpm": 10, "rpd": 500, "resetZone": "UTC", "maxInFlight": 2 },
    "ollama":   { "baseUrl": "http://localhost:11434/v1",                               "key": null,       "rpm": 0,  "rpd": 0,   "resetZone": "UTC", "maxInFlight": 1 }
  },
  "models": {
    "gemini-flash": { "provider": "gemini", "model": "<id>", "fallback": "groq-llama" },
    "groq-llama":   { "provider": "groq",   "model": "<id>", "fallback": null }
  },
  "defaultModel": "gemini-flash",
  "idleWakeSeconds": 60,
  "mentionCooldownSeconds": 15,
  "hearingRange": 64,
  "maxNotes": 20,
  "eventWindow": 10,
  "maxActionsPerDecision": 3
}
```

- The `rpm`/`rpd` numbers shown are illustrative defaults written into a fresh config.
  The user replaces them with their real tier limits. `0` means unlimited.
- The `<id>` model strings are filled at implementation time from each provider's
  `GET {baseUrl}/models` listing, since available model ids change.
- `resetZone` is when the daily counter resets. Gemini resets at midnight Pacific.
- Base URLs are verified against provider docs at implementation time.

`config/aiciv/keys.json` maps key names to secrets: `{ "gemini": "...", "groq": "..." }`.
An environment variable `AICIV_KEY_<NAME>` (upper-cased) overrides the file. The mod
writes an empty `keys.json` on first run and never logs key values. In the dev
environment the game directory is `AiCiv/run/`, which `.gitignore` already excludes.

## Think cycle (`brain`)

**Wake triggers**, in priority order:

1. Hurt (took damage since last decision).
2. Mentioned: a chat line containing the bot's name, rate limited to one wake per
   `mentionCooldownSeconds`. Chat in between is batched into the next request.
3. Action sequence finished or failed.
4. Idle timer: `effectiveIdle` seconds since the last decision.

A bot that is executing an action and has no trigger makes no calls.

**ThinkQueue**: woken bots wait in a priority queue (priority above, then longest
waiting). Whenever a provider's limiter has capacity, the queue dispatches the best
waiting bot on that provider. Under scarcity every bot slows down evenly.

**Budget stretching**: per provider,
`effectiveIdle = max(idleWakeSeconds, secondsUntilReset * botsOnProvider / remainingToday)`.
`/ai budget` shows it. When `remainingToday` hits 0, bots on that provider use their
fallback model if it has budget, otherwise they keep running reflexes and wait for the
reset.

**Request** (stateless, no chat history carried between calls):

- `system`: persona, goal, civilization framing ("you are one of N people founding a
  settlement in this world; other players are listed below"), game rules that matter
  (survival, no commands), and how to use the actions.
- `user`: notes, the last `eventWindow` events, then the snapshot.
- `tools`: the action definitions for the current milestone, in OpenAI function format.
- `max_tokens` 400, `temperature` 0.8.

**Reply handling**:

1. If the reply has `tool_calls`, take up to `maxActionsPerDecision` of them in order.
2. Else, if the content parses as a JSON array of `{ "action": ..., "args": {...} }`,
   use that. This is the fallback for models without function calling.
3. Else, treat non-empty plain text as a single `say` (truncated), and the bot waits for
   its next trigger.
4. Any text content that accompanies tool calls is recorded as the bot's thought in the
   decision log. It is not sent to chat.

Invalid calls (unknown action, bad arguments, unknown block or item id) do not execute.
They produce a failed event with the reason, including nearest-match suggestions for ids,
which the bot sees on its next turn.

**Event log** per bot (ring buffer, `eventWindow` entries in the prompt, timestamped with
game time): chat heard, actions finished or failed with reasons, damage taken, deaths,
items received.

## Perception (`perception`)

The snapshot is compact text, capped at 6000 characters. Lists are truncated
nearest-first to fit. Example:

```
You: Ada | pos 102 64 -33 | hp 18/20 food 14/20 | day 3, afternoon | biome plains
Holding: stone_pickaxe
Inventory: oak_log x12, cobblestone x40, bread x3, stone_pickaxe, wooden_axe
Blocks nearby: iron_ore x3 (nearest 8m NE), oak_log x31 (5m W), crafting_table (3m S), water (12m E)
Players nearby: Bob [AI] 6m N, legomaster80 [human] 20m S
Mobs nearby: zombie 15m E
Current action: none (last: mine iron_ore x10, failed: no path)
```

Fairness rules:

- Blocks are reported within 24 blocks, and only if exposed (at least one face touching
  air or water). No x-ray.
- Entities are reported within 32 blocks.
- From milestone 2, a known-block cache remembers up to 128 exposed ores and utility
  blocks (crafting table, furnace, chest, bed) the bot has seen, so it can return to them.
  Entries are dropped when the bot sees the block is gone.

Chat is heard within `hearingRange` blocks, except that any chat containing the bot's
name reaches it from anywhere.

## Actions (`actions`)

One action runs per bot at a time. Each ends `done` or `failed: <reason>` and has a
timeout. A decision queues up to 3 actions, which run in order. A failure discards the
rest of that sequence.

| Action | Args | Done when | Timeout | Milestone |
|--------|------|-----------|---------|-----------|
| `say` | `text` | Posted as `<Name> text`, max 256 chars, newlines removed | instant | 1 |
| `goTo` | `x, y, z` | Within 1.5 blocks of target | 2 min | 1 |
| `goToPlayer` | `name` | Within 3 blocks of the player | 2 min | 1 |
| `follow` | `name, seconds` | Seconds elapsed (max 300), keeping within 3 blocks | seconds + 5 | 1 |
| `stop` | none | Immediately; clears the queue | instant | 1 |
| `explore` | `direction, distance` | Travelled `distance` (max 128) roughly in that direction | 3 min | 2 |
| `mine` | `block, count` | Gathered `count` more of the block's drops | 5 min | 2 |
| `collect` | `item` | No more of that item on the ground within 16 blocks | 1 min | 2 |
| `craft` | `item, count` | Crafted; uses a crafting table within 4 blocks for 3x3 recipes | 30 s | 2 |
| `smelt` | `item, count` | Output taken from a furnace within 4 blocks | 5 min | 2 |
| `place` | `block, x, y, z` | Placed, against an existing face | 30 s | 2 |
| `eat` | none | Ate the best food in inventory | 10 s | 2 |
| `equip` | `item` | Item in hand or armor slot | instant | 2 |
| `attack` | `target` (player name or mob type) | Target dead or out of range 16 | 1 min | 2 |
| `give` | `name, item, count` | Walked within 3 blocks and dropped items toward the player | 2 min | 3 |
| `store` / `take` | `item, count` | Moved items into / out of a chest within 4 blocks | 30 s | 3 |
| `build` | `template, x, y, z, facing` | Template complete; gathers missing materials itself | 20 min | 3 |
| `remember` / `forget` | `text` / `index` | Note added (oldest dropped past `maxNotes`) / removed | instant | 3 |

All actions obey survival rules: real break times for the held tool (the best tool in
inventory is selected automatically), crafting tables for 3x3 recipes, real furnaces,
reach of 4.5 blocks.

**Build templates** (milestone 3): hut, house, farm plot, wall segment, storage room.
Adapted from the styles in `MeteorExtras` `HouseBuilder` into server-side block lists.

**Reflexes** (milestone 2, no LLM call): eat when food is 12 or lower and food is
available; fight back when attacked by a mob while holding a weapon or tool; flee away
from the attacker when health is 6 or lower; swim up when air is below a third; step out
of lava or fire to the nearest safe block. A reflex pre-empts the current action for as
long as it needs, then the action resumes.

## Navigation (`nav`)

A* over block positions, read through `BlockView`.

| Move | Base cost | Milestone |
|------|-----------|-----------|
| Walk to adjacent block, including diagonal | 1 (1.4 diagonal) | 1 |
| Jump up one block | 2 | 1 |
| Drop down up to 3 blocks | 1 + 0.5 per block | 1 |
| Swim through water | 2 | 2 |
| Break a blocking block | 1 + break seconds with best tool x 4 | 2 |
| Pillar up or bridge across using a throwaway block (dirt, cobblestone, netherrack, planks) | 3 | 2 |

Never enters lava, fire, or a drop above 3 blocks. Search is capped at 20,000 nodes; a
capped search fails the action with `no path`. Trips longer than 128 blocks are split
into legs.

All path searches share a fixed time budget of 2 ms per server tick, given out round
robin, so 15 bots never lag the server.

The movement controller sets the fake player's facing and movement inputs each tick and
jumps when the next node is higher or it collides horizontally. If the bot makes no
progress for 3 seconds, it replans. Three failed replans fail the action with
`stuck at x y z`.

## LLM client (`llm`)

- `POST {baseUrl}/chat/completions` with `Authorization: Bearer <key>` (omitted when the
  key is null), via `java.net.http.HttpClient`, 30 s timeout, JSON through the Gson
  library Minecraft already ships.
- Rate limiter per provider: token bucket for `rpm`, a counter for `rpd` that resets at
  midnight in `resetZone`, and at most `maxInFlight` concurrent requests.
- HTTP 429: wait for `Retry-After` if present, otherwise back off 5 s, 10 s, 20 s up to
  120 s. The bot stays queued.
- 5xx, timeout, or network error: three consecutive failures mark the provider down for
  5 minutes. Its bots use their fallback model, or wait.
- 401/403: the provider is disabled until `/ai reload`, and operators get a chat warning
  naming the provider (never the key).
- Provider state is visible in `/ai budget`.

## Memory and logs

- Notes: up to `maxNotes` per bot, written by `remember`, removed by `forget`, saved in
  `<world>/aiciv/notes/<name>.json`. They are the only long-term memory.
- Decision log: one JSON line per decision in `logs/aiciv/<name>.jsonl` with the trigger,
  snapshot size, the model's thought text, the actions, and their outcomes. A minimal
  version ships in milestone 1 because it is the main debugging aid; milestone 3 adds
  outcome tracking for video narration.

## Testing

- **Scripted provider**: a provider type that returns pre-written replies from a file.
  Dev runs and game tests use it, so tests spend no quota.
- **Unit tests** (JUnit, no Minecraft): rate limiter and daily reset, budget stretching
  formula, ThinkQueue priority and fairness, reply parsing (tool calls, JSON fallback,
  plain text, malformed and invalid input), snapshot truncation, provider client against
  a local mock HTTP server (429 with and without `Retry-After`, provider down, fallback,
  401).
- **Pathfinder tests** on hand-built `BlockView` grids: wall, gap, lava pool, staircase,
  pillar-up, bridge, unreachable target, node cap.
- **Game tests** (Fabric GameTest, scripted provider): spawn and remove a bot; walk an
  obstacle course; milestone 2 adds mining logs and crafting planks, table, and pickaxe.
- **Live smoke tests**: milestone 1 with one bot on Gemini; milestone 3 with 15 bots on
  mixed providers for one hour, checking that no provider exceeds its limits.

## Milestones

Each milestone gets its own implementation plan and ends with something recordable.

1. **Hello, AI.** Gradle project on 26.3; fake player spawn, remove, persist, and move
   (proven first); `config` with key file; `llm` client, limiter, and fallback; snapshot;
   think cycle; actions `say`, `goTo`, `goToPlayer`, `follow`, `stop` with walk, jump, and
   drop pathing; `/ai` commands; minimal decision log.
   Accepted when: in singleplayer, `/ai spawn Ada gemini-flash "a cheerful explorer"`
   brings Ada into the tab list, she answers chat that mentions her, and walks to the
   player when asked.
2. **Survivor.** Full pathing (break, pillar, bridge, swim), `explore`, `mine`,
   `collect`, `craft`, `smelt`, `place`, `eat`, `equip`, `attack`, reflexes, death and
   respawn, known-block cache.
   Accepted when: a bot spawned in a fresh world reaches iron tools on its own.
3. **Civilization.** 10 to 20 bots through the ThinkQueue with budget stretching, notes,
   `give`, `store`/`take`, `build` with templates, all listed providers, decision log
   with outcomes.
   Accepted when: 15 bots on mixed providers run for an hour, build shelters, and talk,
   with no provider over its limit.

## Risks

- **Fake-player movement on 26.3.** The server normally trusts the client for player
  movement. A fake player must run physics itself. Proven as milestone 1, task 1; if it
  cannot be made to work, the fallback is a custom humanoid entity using player skins,
  which loses tab-list presence and needs a design revision.
- **Small free models make poor tool calls.** Mitigated by the JSON fallback, plain-text
  `say`, nearest-match suggestions, and failure reasons fed back into the next prompt.
- **Free tiers change.** All limits and model ids are config, and Ollama gives a
  quota-free fallback.
- **Pathfinder performance with 20 bots.** Mitigated by the shared per-tick budget and
  node cap; measured in the milestone 3 smoke test.

## Changes made during milestone 1

- `max_tokens` defaults to 1000, not 400, with per-model `maxTokens` and `extra` request
  fields. Gemini thinking models count reasoning tokens against `max_tokens`.
- Hurt wakes have a 10 s cooldown, so damage over time costs one call per 10 s at most.
- A reply made only of `say` calls is spoken without cancelling the current action.
- `/ai spawn <name>` without a model reuses the roster model or `defaultModel`.
- Until milestone 2 adds respawning, a bot that dies leaves the world with health
  restored; `/ai spawn <name>` brings it back.
- A provider whose key is missing or rejected, or whose `baseUrl` is invalid, is disabled
  with a readable reason. When a bot is waiting and no provider on its model's route is
  available (disabled or paused), operators get one chat line per bot listing every
  provider on that route with its status; it is repeated only when that text changes. A
  warning counts as delivered only once at least one operator (level 2) received it, so
  one raised before anyone is online is retried every tick until someone is. The server
  log gets each distinct text once. A provider rejected mid-request still warns once.
- Gemini's default `maxTokens` is 4096. Decision logs record `finish_reason` and `usage`,
  and a reply cut off by `length` before any action tells the bot to use fewer words.
- `/ai reload` also updates running bots (idle wake, mention cooldown, event window), and
  a bot whose model was removed from the config moves to `defaultModel`.
- A bot that changes dimension is cleared of the changing-dimension flag at once, because
  it never sends the client acknowledgement that normally clears it.
- Model-supplied player names are cleaned (leading `@`, quotes, trailing `[tag]`/`(tag)`)
  before lookup, and players are tagged `[AI]` / `[human]` in both prompt and snapshot.
- Provider pauses only ever extend, so a late reply cannot shorten a 5-minute pause.
- The snapshot shows water and lava only where they touch air.
- A hurt event is logged at most once per 10 s, so damage over time cannot flood the
  event log.

## Changes made during milestone 2

- Survival actions: `explore`, `mine`, `collect`, `craft`, `smelt`, `place`, `eat`, `equip` and `attack` join the milestone 1 set. Bots are always in Survival mode and use real break times, tool tiers, crafting tables and furnaces.
- `maxActionsPerDecision` defaults to 6, so one reply can chain mine, craft, mine and so on.
- Reflexes (eat, fight back, flee, swim up, leave lava or fire) pre-empt the current action and it resumes afterwards. A bot that dies respawns and is told where its items dropped.
- Acceptance: the game test `scriptedBotReachesIronTools` (`SurvivalGameTests`) runs a scripted seven-reply plan on a 16x16 arena and ends with a bot holding an iron pickaxe and a decision log without errors. It finished at about tick 1960 of 6000. The arena clears the gametest box's barrier outline, which otherwise sits inside a 16x16 area and blocks both line of sight and movement.
- Live acceptance (hand to the user): `/ai spawn Smith gemini-35-flash "A determined survivor. Get iron tools as fast as you can."` in a fresh survival world; report time and the number of LLM calls from `logs/aiciv/Smith.jsonl`.
