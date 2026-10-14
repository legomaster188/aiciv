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

## Milestone 3 — Builders & Society

Approved 2026-10-12. It replaces the earlier milestone 3 list. Notes (`remember`/`forget`), budget stretching and the 15-bot live smoke test are still open and move to a later milestone. Plan: `docs/superpowers/plans/2026-10-12-aiciv-milestone-3.md`.

**Building.** `build` takes a blueprint the model writes: layers listed bottom to top, each a list of rows (row 0 nearest the builder, columns left to right), plus a legend from symbols to block ids. `.` means the cell must be air and a space means leave it alone. The origin is "here" (centred 2 blocks in front of the bot) or given coordinates, with a facing of N, E, S or W. The limit is 16x16x16. `build_template` builds a `hut`, `tower`, `wall`, `bridge` or `farm_plot` from a size and a material. Building is survival-fair:
- Blocks must be in the inventory. Missing materials fail the build before anything changes, with the exact list (`missing materials: 13 cobblestone`).
- Blocks are placed bottom-up, within reach and line of sight, against a real face. The bot pillars with throwaway blocks to reach high cells and removes that scaffolding afterwards.
- Only natural blocks inside the footprint are cleared. A footprint that overlaps a block another player or bot placed, a block entity, or an unbreakable block fails instead.

Finished builds are named and kept per world in `<world>/aiciv/structures.json`. Every bot's snapshot lists them (`Structures: ada_hut by Ada at 10 64 -3 (12m NE)`).

**Placed blocks.** Every block a player or bot places is recorded in `<world>/aiciv/placed.json` (capacity 250,000). Navigation never digs placed blocks and `mine` skips them, so bots do not tunnel through or strip each other's builds.

**Chests.** `place_chest`, `look_in_chest` (puts the contents in the event log), `deposit` (`all`, `all_except_tools`, which also keeps weapons, armor and food, or an item list) and `withdraw item count`. All of them use the chest's real menu. Each bot remembers the last-seen contents of up to 16 containers, and the snapshot shows them.

**Naming and signs.** `rename` at an anvil pays the real level cost. `name_mob` uses a name tag and renames the tag at an anvil first when needed. `sign` places a sign and writes up to 4 lines of 15 characters.

**Combat moves.** `combat` runs a short chain of exact moves: `attack_once`, `strafe left|right` (≤ 20 ticks), `back_off` (≤ 20), `jump_crit`, `shield` (≤ 40), `bow` (charge ≤ 30 ticks) and `hotbar`. There are at most 12 moves per call. `attack` and the reflexes stay as they are.

**Social and economy.**
- `give` walks to a player and throws the items toward them, and a receiving bot is told.
- `drop` throws items on the ground.
- `trade_list` and `trade` deal with villagers through the real merchant menu, so prices include demand and reputation and stock is real. A trade is chosen by wanted item or by offer index, 1 to 12 times.

**Farming and food.**
- `till` needs a hoe and prefers soil within 4 blocks of water.
- `plant` puts seeds on farmland.
- `harvest` breaks ripe crops and replants them.
- `breed` feeds two adults the food that animal really eats.
- `fish` casts a real rod and reels in when a fish bites. It reads the hook's bite timer through an accessor mixin, which is fair because clients see the bobber dip.
- Cooking is the existing `smelt`.

**Utility.**
- `sleep` follows the real bed rules (night or thunder, no monsters nearby) and places a carried bed if needed.
- Wooden doors and fence gates are opened during path following and closed behind the bot. Doors are never broken.
- `use_block` presses buttons, flips levers, and works doors, trapdoors, gates and bells.
- `enchant` at a table uses lapis and levels and picks option 1 to 3.
- `brew` makes water bottles, an awkward base, then one of ten basic potions.

**Perception.** The snapshot adds the XP level, remembered chest contents, villagers and their known offers, crop states (ripe, growing, free farmland), known structures, and a night line when sleeping is possible.

**Both decision modes.** Each feature lives in its own module. A module registers its tools for tool-mode models, its factory entries, its menu options for menu-mode models (Jev) and its snapshot sections. A sync test checks that catalog names, factory argument names and menu calls agree.

**Fairness rules carried from milestone 2:**
- No teleporting, no item spawning, and no seeing or acting through walls.
- Reach is 4.5 for blocks and 3.0 for entities. Timings and costs are real.
- Game state is touched only on the server thread.
- Game tests share one world. Each test keeps its own arena, bot names and entities, and night tests run in their own `aiciv:night` environment.

**Accepted when:** the scripted game test `scriptedBotBuildsStoresAndSleeps` passes. In it, a bot builds a 5x5 hut from a blueprint, stores its cobblestone, dirt and logs in a chest it places, and sleeps in a bed it carries. Live: a builder bot and a Jev trader bot run for 30 minutes in a world near a village.

### Changes made during milestone 3

- Acceptance: the game test `scriptedBotBuildsStoresAndSleeps` (`CivGameTests`, `aiciv:night`, 16x16 arena, `padding = 24`) runs three scripted replies: `build` of the 55-plank `ada_hut`, `place_chest` + `deposit`, `sleep`. It checks the registered structure, the 55 planks, a chest with exactly 20 cobblestone, 10 dirt and 5 oak logs, that the bot slept, that the stone pickaxe was kept, and a decision log with no errors. It finished at about tick 520 of 6000 (517-523 over several runs).
- Beds are rejected inside blueprints (a bed is two cells with an orientation); the bot places one with `place`, and `sleep` places a carried bed.
- `build` walks to a computed viewpoint when it cannot place a cell from where it stands, because `Approach` only guarantees sight of the target cell, not of a face to place against.
- The acceptance test exposed two bugs, both fixed. (1) `Approach` could never succeed on its close-in retry for a block at floor level: the goal point sits 1.62 below the target, so a radius under about 1.5 was unreachable from any standable cell; the goal radius now has a floor of 1.6. That made the bot give up taking down its scaffolding (the block stayed in the world). (2) Scaffold removal left the cobblestone drop on the ground out of reach, so the bot ended with one cobblestone fewer; `BuildAction` now walks to pick up the scaffold drops inside its box before it reports the build as finished.
- Bots take real fall damage (`AiPlayer` adds up falls each tick).
- Known flaky game tests, not related to this milestone: `breakerBreaksStone...`, `minesThroughTheWall...`, `hungryBotEats...`, `panelServesState...`, `sleepsInCarriedBed` (night environment timing) and, occasionally, `cancelDuringDepositClosesChest`.
- Live acceptance (hand to the user): in a survival world near a village, `/ai spawn Ada gemini-35-flash "A builder who wants a cosy home and a farm."` and `/ai spawn Jev1 jev "A trader."` for 30 minutes; report structures built (the Structures line), chests used, trades made, nights slept, and LLM calls per bot from `logs/aiciv/*.jsonl`.

### Changes made during milestone 5

- Acceptance (`AutopilotGameTests`, prefix `TestAp`, autopilots run on a clock that advances 50 ms per server tick, `GameTestKit.tickClock`, because game tests run far faster than real time and think gaps and chat gaps are in milliseconds; on the wall clock the speedrunner made 3 decisions in 6000 ticks).
  - `autopilotSpeedrunnerReachesIronTools`: `autopilot-speedrunner` in the milestone-2 arena with 4 bread holds an iron pickaxe at tick 1750 to 1830 (four runs) after 10 logged decisions (decisions that keep the running action are not logged). Goals in the log are `get_iron_tools` and `beat_game` in the usual run; the test also allows the `get_wood_tools`, `get_stone_tools` and `get_food` detours, which show up when the bot picks `beat_game` early and wants swords. The brief expected at least 6 distinct `step` values; steps are coarse (`wooden_pickaxe`, `stone_pickaxe`, `iron_pickaxe`, each covering its own gathering), so the test asserts at least 3 steps and at least 6 distinct actions (here 7 or 8: craft x3, mine log, stone, iron_ore and sometimes coal_ore, smelt raw_iron).
  - Test environment: these three tests run in a new `aiciv:day` environment (noon), because the shared world's clock moves on between tests and a night sent the builder home to sleep mid-build. The speedrunner and builder arenas get two dirt layers under the floor: the arena floor is one layer over void, and a bot that mines the stone it stands on fell out of the world (this was also the cause of the known flakiness of `scriptedBotReachesIronTools`, which uses the same arena).
  - `autopilotBuilderBuildsHut`: the 71-cell cobblestone `testapbuilder_home` is registered and a `build_base`/`hut` decision is logged. `autopilotGuardKillsZombie`: a no-AI zombie 7 blocks away is dead within 600 ticks and a `defend` decision is logged (difficulty is switched with the reference-counted `CombatGameTests.easy`/`restoreDifficulty`). `autopilotChatTemplatesFire`: a `chatty` autopilot with `chatModel` on the scripted backend says `beep from the llm` (source `llm`); a `miner` whose `chatModel` has no backend says a template line; no line has a `{`, and all lines from the two bots in one manager are at least 2000 ms apart.
  - Each test asserts no log line contains `"error"`; the first three also assert the scripted backend saw no request.
- Merge fix: with a full planner, optional cues (`goal`, `ask_help`) arrived every decision and the 8-slot pending list of `TemplateChat` evicted the oldest cue first, which was the waiting mention reply. It now evicts the oldest optional cue first, then replies, then priority cues. (Not the cause: a mention that parses as a request is dropped on purpose and answered by the social goal's give, refuse or coming line.)
- The admin panel's latest decision skips `autopilot-chat` lines (`DecisionLog.append` still writes them to the file).
- Planner stall: a step that finishes three times without becoming done counts as one failure (`AutopilotMemory.stall`), because a finish clears a step's ordinary failures.
- `BuildBase` has a separate `chest_item` step before `chest`, because a step raises its flag when any of its moves finishes. `Requests.parse` leaves `from` empty; the brain fills it from the speaker.
- `endgame.json` gained `make_eyes {count: 12}` as the first candidate of `ender_eyes` and `go_to_end` before `enter_portal`.
- A user `personalities.json` can add personalities and change fields but cannot remove `default`; `Personalities.of(collection)` builds an exact set for tests. The shipped `phrases.json` has no user override file; per-personality `phrases` in `personalities.json` replace a category.
- `AutopilotBrain` and `AutopilotMemory` gained a few small additions listed in the task 1 and 2 reports (bot name and exact tick for request parsing, `current`, `decided`, `stall`).
- Known flaky game tests, unchanged: `breakerBreaksStone...`, `minesThroughTheWall...`, `hungryBotEats...`, `panelServesState...`, `buildsTowerWithScaffold`, `scriptedBotReachesIronTools`.
- Live acceptance (hand to the user): in a fresh survival world, `/ai spawn Rex autopilot-speedrunner`, `/ai spawn Bea autopilot-builder`, `/ai spawn Fen autopilot-farmer`, `/ai spawn Gus autopilot-guard` and one LLM bot (`/ai spawn Ada gemini-35-flash`) for 30 minutes; report per autopilot the goals reached (milestones bragged), structures built, deaths, chat lines (template or LLM) and server tick time (`/tick query`) with all five online.

## Milestone 4 — Everything a player can do

Approved 2026-10-13 ("I want bots to do anything a player can do"; all four groups). Plan: `docs/superpowers/plans/2026-10-13-aiciv-milestone-4.md`. It replaces the earlier "Endgame" outline. A long `beat_game` is not part of it; Milestone 5's autopilot `beat_game` chains these actions through `endgame.json`.

**Both levels.** Each endgame step is a routine action (`go_to_nether`, `get_blaze_rods`, `go_to_end`, `fight_dragon`...) built from primitive actions the model can also call one by one (`build_portal`, `light_portal`, `enter_portal`, `find_fortress`, `hunt_blazes`, `throw_eye`, `fill_end_portal`, `shoot_crystals`, `hit_dragon`...). A routine runs its stages as ordinary actions and fails with the failing stage's reason.

**A. Endgame.**
- Portal: `build_portal` builds the 4 × 5 obsidian frame (corners from obsidian or any throwaway block) through the milestone-3 builder and lights it when the bot holds flint and steel or a fire charge; `cast_portal` casts the frame from a water bucket and lava (refilled from a lava pool within 16 blocks); `light_portal` lights a finished frame.
- Dimensions: `enter_portal` walks into a nether portal, end portal or end gateway and waits for the real portal timer. Positions are remembered per dimension: known blocks, chests and villagers are kept for up to 3 dimensions instead of being forgotten, and portals, fortresses, the stronghold estimate and the End exit are kept as waypoints that every snapshot lists. An action that is not a portal action fails when the bot changes dimension. Bots never get the End credits screen: leaving the End uses the normal portal path.
- Nether: `find_fortress` explores in a square spiral until nether bricks are in sight; `hunt_blazes` shoots or fights blazes and picks up the rods.
- Stronghold: `make_eyes` crafts blaze powder and eyes; `throw_eye` throws a real eye and reads its flight direction (what a player sees); `locate_stronghold` throws from two places and triangulates; `go_to_stronghold` travels, digs down and explores to the portal room (end portal frames, silverfish spawner); `fill_end_portal` fills the frames; `go_to_end` does all of it and enters.
- Dragon: `shoot_crystals` shoots open crystals with a bow from at least 9 blocks; `break_cage` pillars up beside a caged crystal, breaks a bar, bridges away and shoots; `hit_dragon` hits the head while the dragon perches and shoots it in flight, stepping out of dragon breath; `fight_dragon` runs these to the end; `leave_end` takes the exit portal. The snapshot shows the dragon's health and perch state (the boss bar and the synced phase) and the crystals in sight.

**B. Travel.** `place_boat`, `board`, `dismount`, `ride_to x z` (boats on water; tamed, saddled horses, donkeys, mules and camels; pigs with a carrot on a stick; striders with a warped fungus on a stick; minecarts pushed forward), `boat_to x z`, `place_minecart`, `ride_minecart`. The server simulates a vehicle whose driver is a bot (a vehicle normally trusts its driver's client). `fly_to x z` and `launch_elytra`/`boost` glide with an elytra and firework rockets. `throw_pearl x y z` aims a real ender pearl at a visible landing spot and takes the real 5 damage; it is refused at 7 health or less.

**C. Items and animals.** `fill_bucket`, `empty_bucket`, `bone_meal`, `ignite` (fire, TNT, campfires), `carve_pumpkin`, `drink`, `throw` (snowballs, eggs, splash and lingering potions, bottles o' enchanting), `make_map`, `link_compass`; the snapshot shows where a carried compass points (direction only). `tame` uses the real item and chance (bones, raw cod or salmon, seeds; horses, donkeys, mules and llamas by riding), `saddle`, `leash`, `tie_lead`, `unleash`, `milk`, `shear`, `dye_sheep`. `breed` stays as in milestone 3.

**D. Stations and redstone.** `upgrade_netherite`, `trim_armor`, `stonecut`, `grindstone`, `repair`, `loom`, `cartography`, `write_book` (signed through the vanilla book packet path). Blueprint legends take block states, e.g. `"R": "repeater[facing=north,delay=2]"`; `facing` may also be relative (`forward`, `back`, `left`, `right`, `up`, `down`). The builder gets the orientation the survival way, by standing where a player would have to look from, then sets delay, mode or power with right-clicks. `use_block` also tunes repeaters and comparators.

**Fairness additions.** Projectiles are aimed by simulating vanilla gravity and drag, never spawned or steered. Eyes of ender are read from the eye entity's flight. Portals use the real timers and destinations. Navigation never walks into a portal block and never digs obsidian, crying obsidian or end portal frames. Vehicles move only through the inputs a client would send.

**Accepted when:** the scripted game tests `scriptedBotTravelsToTheNetherAndBack` (build and light a portal from carried obsidian, enter the nether, come back through the arrival portal) and `scriptedBotOpensTheEndPortal` (fill the last frames of a constructed portal room and arrive in the End) pass. Live: one LLM bot works through portal → nether → fortress → rods with chat goals; a second bot demonstrates a boat, a horse and an elytra flight.

### Changes made during milestone 4

- Acceptance: `EndgameGameTests.scriptedBotTravelsToTheNetherAndBack` (16x16 stone arena, `padding = 24`) builds and lights a portal from 14 obsidian and flint and steel, enters the nether, and comes back through the portal it arrived by; it finished at tick 289 of 6000. `scriptedBotOpensTheEndPortal` fills the last two frames of a 12-frame ring with 2 eyes and steps into the End; it finished at tick 55 of 3000, and the bot is removed at once. The test server's own nether and End are used; with no nether or End the tests assert the `... does not exist on this server` failure instead.
- The acceptance test exposed a real bug in `EnterPortalAction`, fixed: after a crossing the player has a portal cooldown, and standing inside a portal during the cooldown only restarts it, so a bot that walked straight back into the arrival portal (`leave_nether`) stood there for the full 300-tick wait and failed. The action now waits beside the portal until the cooldown has run out.
- The tool catalog has 95 functions and 34,578 characters of JSON (about 8,600 tokens at 4 characters per token, about 11,500 at 3) plus a 4,090-character guide. `CatalogSizeTest` asserts it stays under 60,000 characters. That is under the 12,000-token level at which a "compact" catalog was planned, so no compact mode was built.
- Dimension changes: every action fails with `you went through to <dimension>` unless it survives the change; known blocks, chests, villagers and waypoints are kept per dimension; navigation never enters a portal block. Bots arriving by portal step out of the portal and wait for the portal cooldown before using it again.
- `cast_portal` needs a temporary 4x5 wall of 20 cobblestone, dirt or planks to pour against (fluids need a solid face); it takes the wall down again when it has a pickaxe. `Buckets.pour` (Task 1) puts a fluid beside a corner block instead of in the cell, so `nether/BucketPour` does the pour with a stricter test; `Buckets.pour` itself still has the problem.
- The test world has no stronghold, so the eye-flight path (`throw_eye`, `locate_stronghold`, most of `go_to_stronghold`) is covered by pure triangulation tests and a simulated eye, not by an end-to-end game test. `go_to_stronghold` digs its own turning stair down because the navigator has no dig-down move.
- Eyes of ender are read from the eye entity's flight; a sunk eye ends `throw_eye` at once. The stronghold waypoint's y is fixed at 30.
- The End: `fight_dragon` chooses crystals, cages or the dragon from what it sees. The game tests use a constructed obsidian arena (the real dragon smashes ordinary blocks and throws the bot); the head of a sitting dragon is about at ground level. Nothing ran against a real dragon fight.
- Vehicles: `BoatControlMixin` runs the boat's `controlBoat()` on the server for AI riders (it only ran on the client). Breaking a boat or cart bare-handed takes about 9 hits at attack-strength 0.9 because vehicles heal 1 damage per tick. `ride_to` and `boat_to` refuse more than 512 blocks at the first tick.
- Flight and pearls: aiming uses `world/Ballistics` with vanilla gravity and drag; a thrown potion reaches only about 4.2 blocks on the level. `AutoWear` can swap a worn elytra for a carried chestplate (not fixed).
- Items and animals: `fill_bucket` finds its own source (the perceiver misses shoreline water); `Perceiver` now counts see-through blocks (portals, rails) as seen when the ray reaches them. Animals: `milk` and `shear` accept only the animals they work on; `unleash` clicks the knot after approaching its fence block.
- Stations and redstone: `trim_armor` needs the armor in the inventory, not worn. Blueprint legends take block states (`repeater[facing=forward,delay=2]`); placement stands and looks as a player would, with 4.3 reach for stand cells, and a wrongly oriented block is taken down and the build fails.
- Game tests share one world and several new ones need `GameTestKit.keepEntitiesTicking` (entities in chunks that are loaded but not entity-ticking freeze) and `GameTestKit.clearBarriers`.
- Known flaky game tests, unrelated to this milestone: `breakerBreaksStone...`, `minesThroughTheWall...`, `hungryBotEats...`, `panelServesState...`, `buildsTowerWithScaffold`, `scriptedBotReachesIronTools`, and occasionally the cast-portal wall removal and `tamesWolfWithBones`.
- Live acceptance (hand to the user): (1) in a survival world, `/ai spawn Ember gemini-35-flash "An adventurer who wants to reach the nether and bring back blaze rods."`; give it time and chat goals; report which of portal, nether, fortress and rods it reached and the LLM calls from `logs/aiciv/Ember.jsonl`. (2) A second bot asked in chat to cross a lake by boat, ride a tamed horse and glide with an elytra; report what worked.

## Milestone 5 — Autopilot bots

Approved 2026-10-13. Plan: `docs/superpowers/plans/2026-10-13-aiciv-milestone-5.md`. Built in parallel with Milestone 4 and independent of it.

Autopilots are bots that need no LLM to act. They play like AutoClef, but each one has a personality. An autopilot is an ordinary fake player with the same reflexes and the same snapshot as any other bot. It acts only through the same `ToolCall`s, so it obeys every survival-fairness rule and can use any action a later milestone adds.

**Choosing one.** The model key `autopilot-<personality>` makes a bot an autopilot, for example `/ai spawn Rex autopilot-miner`. Outside commands, `autopilot:<personality>` also works. Brigadier words cannot contain `:`, so commands use the dash form. An `aiciv.json` model with `"type": "autopilot"` and a `personality` works too. It can also set a `chatModel` and its own `thinkSeconds`. Menu-mode and tool-mode LLM bots are unchanged.

**Thinking.** Autopilots decide on the server thread at once. They skip the ThinkQueue and the rate limiter. They wake on the usual triggers: hurt, mentioned, actions ended, and idle every `autopilot.thinkSeconds` (5). A cheap check every 20 ticks also replans when night falls, the bot gets hungry, a hostile mob comes within 8 blocks, or health falls to the retreat line. These urgent replans happen only on a change of state and at least 40 ticks apart. If the bot picks the step it is already running, that step keeps running. The server makes at most 4 autopilot decisions per tick, in turn. A decision costs one snapshot and no network call.

**Goal planner.** A goal is an ordered list of steps. Each step has a done-check against the bot's situation (inventory, gear, what it sees, section tags, dimension), the actions it needs, and a move made of tool calls. The planner runs the first step that is not done. A raw-material cost table turns "craft an iron pickaxe" into gathering logs, cobblestone, iron ore and fuel, a furnace, smelting, and the craft.
- Retries: a failed step is retried. On the second failure of a gathering step, the bot explores in a turning direction. After `maxRetries` failures (1 to 5, set by patience) the step backs off for 30 s, doubling up to 5 min.
- Livelock guard: a step that finishes three times without ever becoming done counts as one failure.
- Missing actions: the planner learns which actions exist from `ActionFactory.knownKeys()`. A step whose action is missing is skipped if it is optional; otherwise it blocks its goal with a readable reason, and that goal is set aside for 60 s.

Goals: `get_wood_tools`, `get_stone_tools`, `get_iron_tools`, `get_iron_armor` (`gear_up`), `get_food` (cook, harvest, hunt, fish, bread, explore), `defend` (fight hostiles, guard nearby humans), `social` (answer "give me 3 bread", "come here", "follow me"), `build_base` (a cobblestone hut named `<bot>_home`, a chest and a bed), `store_items` (deposit at home when the inventory fills), `farm` (hoe, seeds, till, plant, harvest, bread, breed), `trade_emeralds`, `mine_diamonds`, `explore` and `beat_game`. `beat_game` runs the iron chain, armor and food, and then the endgame steps listed in `assets/aiciv/autopilot/endgame.json`: portal, nether, fortress, blaze rods, ender pearls, eyes, stronghold, end and dragon. That data file is the only place Milestone 4 action names appear. Each step lists candidate action names, and the first one that exists is used. The chain stops at the first step none of whose candidates exist.

**Personality.** There are nine archetypes plus `default`: Miner, Builder, Farmer, Trader, Guard, Explorer, Speedrunner, Hermit and Chatty. Each has goal weights and five traits from -1 to 1: brave↔cautious, greedy↔generous, social↔loner, tidy↔messy and patient↔impulsive. They ship in `assets/aiciv/autopilot/personalities.json`. A `config/aiciv/personalities.json` file can add personalities or change them field by field. Mistakes in that file are reported by `/ai reload`, and the shipped defaults keep working.
- Goal choice: utility = weight × need. The goal the bot is already pursuing gets a bonus that grows with patience. `/ai goal <bot> <goal id>` pins a goal.
- Overrides before utility: retreat when health is at or below `10 − 4·brave` (between 6 and 14) with a hostile close; get food when starving; go home and sleep at night if tidy or cautious.
- Traits also set: how much of an item the bot keeps before giving it away (generous bots give everything), whether it helps players who ask, how far it explores, how often it talks, and when it stores its things.

**Chat.** Lines come from templates in `assets/aiciv/autopilot/phrases.json` (base lines plus per-personality lines) with `{placeholders}`. Categories: greet, brag on milestones, complain, ask for help, own death, others' deaths, replies when mentioned, give/refuse/coming, thanks, night, new goal, and banter about nearby bots. Banter uses `{otherModel}`, so bots named after their models can tease each other about them.
- Limits: a per-bot cooldown of 30 s scaled by `social`, at least 2 s between any two autopilot lines on the server, mention replies at least 5 s apart, and banter at most once per 5 min per pair. Loners only answer.
- Optional LLM chat: a bot's `chatModel` (a cheap model such as gemma or an Ollama model) may write the line from a short context, at most once per 60 s. The planner still decides every action. A failed or rate-limited LLM call falls back to the template line, and a reply older than 20 s is dropped.

**Visibility.** Decisions go to `logs/aiciv/<bot>.jsonl` in the usual shape. `mode` is `autopilot`. `thought` is the planner's reasoning, for example `goal get_iron_tools (0.95): step iron_pickaxe: smelt 3 raw_iron`, and `goal`, `step` and `personality` are added. Chat lines are logged with `mode: autopilot-chat` and their source (template or llm). The admin panel and `/ai status` show the personality, traits, goal, step and thought. `/ai personalities` lists the archetypes.

**Accepted when** these game tests pass:
- An autopilot Speedrunner reaches an iron pickaxe in the milestone-2 arena with no LLM requests.
- A Builder builds its 71-block cobblestone hut.
- A Guard kills a zombie.
- Template and LLM chat lines both fire, with the fallback and the server-wide gap holding.

Live: four autopilots and one LLM bot run for 30 minutes in a fresh world.
