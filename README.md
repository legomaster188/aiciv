# AiCiv

AI players for Minecraft 26.3, each driven by a language model (Gemini, Groq, OpenRouter,
GitHub Models, Mistral, Cerebras, OpenCode Zen or a local Ollama model). They join as normal
players, chat, and walk around. Milestones 1 to 3 (chat, survival, building and society) and 5 (autopilot
bots that need no model) of the design in `docs/superpowers/specs/2026-10-11-aiciv-design.md`.

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

## Autopilot bots (no LLM needed)

An autopilot plays survival with a built-in planner instead of a language model. It is an ordinary fake
player: same reflexes, same snapshot, same actions and the same fairness rules as an LLM bot. Each one has a
personality that sets what it works on, how it talks and how it treats other players. Autopilots cost no
requests, so any number can run next to the LLM bots.

Spawn one with the model key `autopilot-<personality>`, for example `/ai spawn Rex autopilot-speedrunner`
(`autopilot:<personality>` also works in `aiciv.json` and the panel; commands cannot contain `:`). Every
personality has an implicit key, so no config is needed. To pick a chat model or a different thinking
interval, add a model of `"type": "autopilot"` to `aiciv.json`:

```json
"models": {
  "rex": { "type": "autopilot", "personality": "speedrunner" },
  "fen": { "type": "autopilot", "personality": "farmer", "chatModel": "gemma-menu-free", "thinkSeconds": 8 }
},
"autopilot": { "thinkSeconds": 5, "gapMillis": 500, "checkTicks": 20, "maxDecisionsPerTick": 4,
               "chatCooldownSeconds": 30, "chatLlmCooldownSeconds": 60, "globalChatGapMillis": 2000, "chatLlmMaxAgeSeconds": 20 }
```

`chatModel` names any LLM model in the file (a cheap one is plenty). It only rewrites the bot's chat lines in
its own words, at most once per `chatLlmCooldownSeconds`; the planner still decides every action. If that
model is unavailable, rate limited or slow (a reply older than `chatLlmMaxAgeSeconds` is dropped), the bot says
the template line or nothing. Without `chatModel` an autopilot only uses its templates. The `autopilot` block
is optional; the values above are the defaults.

Personalities (`/ai personalities` lists them). Traits run from -1 to 1:

| Id | Brave | Greedy | Social | Tidy | Patient | Likes |
|----|------:|-------:|-------:|-----:|--------:|-------|
| `default` | 0 | 0 | 0 | 0 | 0 | an ordinary survivor |
| `miner` | 0.4 | 0.5 | -0.3 | 0.2 | 0.6 | iron, diamonds |
| `builder` | -0.2 | -0.2 | 0.3 | 0.9 | 0.8 | a stocked home |
| `farmer` | -0.5 | -0.4 | 0.4 | 0.6 | 0.9 | crops, bread, selling the surplus |
| `trader` | -0.3 | 0.8 | 0.9 | 0.3 | 0.4 | emeralds, villagers |
| `guard` | 1.0 | -0.5 | 0.5 | 0 | 0.3 | armor and fighting |
| `explorer` | 0.6 | 0 | 0.2 | -0.6 | -0.4 | wandering |
| `speedrunner` | 0.7 | 0.6 | -0.5 | -0.8 | -0.9 | iron, then the end of the game |
| `hermit` | -0.6 | 0.3 | -1.0 | 0.7 | 0.7 | a quiet home, no company |
| `chatty` | -0.2 | -0.8 | 1.0 | 0 | 0 | talking and giving things away |

Brave sets when the bot retreats and how far it fights; greedy how much it keeps before giving things away
(generous bots give everything); social how often it talks (a loner only answers when spoken to); tidy when it
stores its things and builds a home; patient how long it sticks to a goal and how often it retries a step.

Goals, in the order the planner knows them: `get_wood_tools`, `get_stone_tools`, `get_iron_tools`,
`get_iron_armor`, `get_food`, `defend`, `social`, `build_base`, `store_items`, `farm`, `trade_emeralds`,
`mine_diamonds`, `explore`, `beat_game`. Each personality weights them differently, and the bot works on the
one with the best weight times need. **Pin a goal** with `/ai goal <name> <goal id>` (for example
`/ai goal Rex get_iron_armor`); any other text is just remembered as a note. `social` answers chat such as
"Rex give me 3 bread", "Rex come here" or "Rex follow me", and anyone's "anyone got bread". `beat_game` runs
the iron chain and then the endgame steps listed in `assets/aiciv/autopilot/endgame.json`, as far as the
actions that exist go; when a step needs an action that is not available it stops there and says why.

**Your own personalities.** Put a `personalities.json` in `config/aiciv/`. It is merged over the shipped file
field by field, so you can change one trait of `miner` or add a new id (`[a-z0-9_]{1,24}`, then use
`autopilot-<id>`):

```json
{
  "personalities": {
    "scout": {
      "name": "Scout", "description": "Runs ahead and reports back.",
      "traits": { "brave": 0.3, "social": 0.6, "patient": -0.5 },
      "goals": { "explore": 1.0, "get_food": 0.6 },
      "phrases": { "greet": ["Scout {me} reporting in, {other}!"], "brag": ["Found a {item}!"] }
    }
  }
}
```

Traits and goal weights are clamped to -1..1, and a bad entry (unknown trait or goal id, a non-number, broken JSON)
is reported, never fatal: `/ai reload` and the server log list every problem, and the shipped personalities keep
working. A chat line whose `{placeholder}` cannot be filled is never said. The chat templates ship in the mod
as `assets/aiciv/autopilot/phrases.json` (a `base` set plus lines per personality, with `{me}`, `{speaker}`,
`{other}`, `{item}` and similar placeholders); the `phrases` key of a personality in `personalities.json`
replaces a category for that personality. An autopilot whose personality disappears on reload switches to
`defaultModel`, like any bot whose model disappeared.

Autopilot decisions are in `logs/aiciv/<name>.jsonl` with `mode: autopilot`, the personality, the goal, the
step and the planner's reasoning in `thought`. Chat lines are logged with `mode: autopilot-chat` and their
source (`template` or `llm`). The panel and `/ai status` show the personality, traits, goal, step and thought.

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
| `build(name, layers, legend, x, y, z, facing)` | Builds a blueprint from carried blocks (see below); `x y z facing` are optional |
| `build_template(template, material, name, size)` | Builds a `hut`, `tower`, `wall`, `bridge` or `farm_plot` from one material |
| `place_chest()`, `look_in_chest(x, y, z)` | Places a chest; opens one and reads what is inside |
| `deposit(items, x, y, z)`, `withdraw(item, count, x, y, z)` | Puts items in a chest (`all`, `all_except_tools` or a list); takes some out |
| `give(name, item, count)`, `drop(item, count)` | Throws items to a player, or onto the ground |
| `rename(item, name)`, `name_mob(target, name)`, `sign(text)` | Anvil rename (pays the real level cost), name tag on a mob, write a sign |
| `combat(moves)` | Up to 12 exact moves: `attack_once`, `strafe`, `back_off`, `jump_crit`, `shield`, `bow`, `hotbar` |
| `trade_list(villager)`, `trade(villager, want, offer, times)` | Reads a villager's offers; trades 1 to 12 times at the real price |
| `till(count)`, `plant(seed, count)`, `harvest(count)` | Hoe soil near water, sow seeds, harvest ripe crops and replant |
| `breed(animal)`, `fish(catches)` | Feed two adults; cast a real rod and reel in bites |
| `sleep()`, `use_block(x, y, z)` | Sleep in a bed (places one it carries) at night; press, flip or open a block |
| `enchant(item, option)`, `brew(potion, count)` | Enchanting table (lapis and levels) and brewing stand |
| `stop()` | Stand still |

Tip: one turn can chain up to 6 actions (`maxActionsPerDecision`), for example
`mine log 6 -> craft wooden_pickaxe -> mine stone 3 -> craft stone_pickaxe`.

### Building

`build` takes a blueprint: `layers` from bottom to top, each a list of rows (row 0 nearest the builder, columns
left to right), and a `legend` from one-character symbols to block ids. `.` means the cell must be air and a space
means leave it alone. Without `x y z` it is built centred 2 blocks in front of the bot; `facing` is N, E, S or W.
The limit is 16 x 16 x 16. A 5 x 5 hut with a doorway (55 planks):

```json
{ "name": "ada_hut", "facing": "S", "x": 10, "y": 64, "z": -3, "legend": { "p": "oak_planks" },
  "layers": [
    ["pp.pp", "p...p", "p...p", "p...p", "ppppp"],
    ["pp.pp", "p...p", "p...p", "p...p", "ppppp"],
    ["ppppp", "ppppp", "ppppp", "ppppp", "ppppp"] ] }
```

The bot must carry every block (a missing-materials list is the failure), places them bottom-up from where it can
reach and see a face, pillars with throwaway blocks for high cells, and takes that scaffolding down and picks it
up afterwards. Beds cannot be part of a blueprint: use `place`. Finished builds are remembered per world in
`<world>/aiciv/structures.json` and listed in every bot's view.

**Bots never break blocks other players or bots placed.** Every placed block is recorded in
`<world>/aiciv/placed.json`; navigation never digs them, `mine` skips them and `build` fails rather than clearing them.
Natural blocks in a footprint are cleared.

### Testing sleep

Sleeping only works at night or in a thunderstorm. Game tests that need night use the `aiciv:night` test
environment, which runs them in their own batch with the clock set to night. To try it by hand, set the time with `/time set night`.

Fighting mobs needs the world difficulty set to Easy or higher; on Peaceful hostile mobs do not spawn.

## Commands (op level 2)

In singleplayer, `/ai` needs cheats enabled for the world.

| Command | What it does |
|---------|--------------|
| `/ai spawn <name> [model] [persona...]` | Adds an AI player at your position |
| `/ai remove <name>` | Removes it from the world and the roster |
| `/ai list` | Every AI player and what it is doing |
| `/ai goal <name> <text>` | Sets its long-term goal (an autopilot treats an exact goal id as a pinned goal) |
| `/ai personalities` | Lists the autopilot personalities |
| `/ai pause [name\|all]`, `/ai resume [name\|all]` | Stops or restarts thinking and acting |
| `/ai status <name>` | Details for one AI player |
| `/ai budget` | Requests used and left per provider, and provider health |
| `/ai model <name> <model>` | Switches an AI player to another configured model |
| `/ai panel` | Prints the clickable link to the admin panel (only you see it) |
| `/ai reload` | Re-reads `aiciv.json` and `keys.json` |

AI players notice chat within 64 blocks, and any chat that names them from anywhere.

## Admin panel

While a world is running, AiCiv serves a local control page at `http://localhost:8765/`. Run `/ai panel`
in game and click the link it prints; the link carries an access token that changes each time the server starts. The page shows every AI
player live (model, health, food, position, current action, last thought or Jev probabilities), lets you
spawn, remove, pause, resume, change model, set a goal or make a bot say something, reload the config,
and shows each bot's decision feed when you click its card. The layout suits a second monitor and narrow
windows alike.

Configure it in `aiciv.json` (the default if the block is missing):

```json
"panel": { "enabled": true, "port": 8765 }
```

Security: it listens on 127.0.0.1 only, never on the network. Every API call needs the token, which is
random per server start, lives in the link's `#` fragment (never sent to the server or logged) and is
printed only to the operator who runs `/ai panel`. Requests with a foreign `Origin` or `Host` are
refused. The panel never shows API keys. If the port is taken the server log says so and AiCiv carries on
without the panel; change `panel.port` and restart. Changes made in the panel run on the server thread
exactly like the matching `/ai` commands.

## Logs

Every decision goes to `logs/aiciv/<name>.jsonl`: what woke the bot, which provider and model
answered, the model's thought, the actions, and any problems.

## Testing without spending quota

A provider with `"type": "scripted"` and `"script": "scripted.json"` replays assistant
messages from `config/aiciv/scripted.json` in order, for example
`[{"content": null, "tool_calls": [{"function": {"name": "say", "arguments": "{\"text\": \"hi\"}"}}]}]`.

Developers: `./gradlew test` runs the unit tests, `./gradlew runGametest` the in-game tests.

## Limits

Blueprints are limited to 16 x 16 x 16 and bots build one at a time. Bots only use villagers, chests,
anvils, enchanting tables and brewing stands through their real menus.

A bot that dies respawns like a player (at the world spawn or its bed) with full health, and is
told what killed it and where its items dropped.

AI players count as awake players, so humans cannot skip the night by sleeping.
