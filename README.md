# AiCiv: AI players for Minecraft

[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62b47a)
![Fabric](https://img.shields.io/badge/loader-Fabric-dbd0b4)
![Java 25](https://img.shields.io/badge/Java-25-orange)

**AiCiv is a server-side Fabric mod that adds AI players to Minecraft 26.3.** Each one is driven by a language model
(Gemini, Groq, OpenRouter, GitHub Models, Mistral, Cerebras, OpenCode Zen or a local Ollama model), a decision model such as
TypeSafe AI's Jev, or a built-in planner that needs no model at all. They join as normal players, chat, mine, craft,
build, farm, trade, fight and can play all the way to the Ender Dragon, under the same rules a human has.

- **Any model, free tiers included.** Per-provider rate limits and daily budgets, automatic fallback models, and a
  scripted provider for testing without spending quota.
- **Plays like a player.** Real break times, correct tools, crafting tables, furnaces, reach and attack cooldowns. 95 actions
  and routines, including portals, the Nether, the stronghold, the End, boats, elytra, enchanting and redstone.
- **Autopilot bots.** 10 personalities (miner, builder, farmer, trader, guard, explorer, speedrunner, hermit, chatty,
  default) that cost no API requests, so any number can run next to the LLM bots. Add your own in JSON.
- **Compare models head to head.** Menu mode puts every model in front of the same choices and logs probabilities and confidence.
- **Local admin panel and full decision logs** for every bot.
- **No client mod.** Viewers join with an unmodded 26.3 client. Works in singleplayer too.

Milestones 1 to 5 of the design in [`docs/2026-10-11-aiciv-design.md`](docs/2026-10-11-aiciv-design.md): chat, survival,
building and society, everything a player can do (the endgame, travel, items, animals, stations and redstone), and
autopilot bots.

Not affiliated with or endorsed by Mojang or Microsoft.

**Contents:** [Install](#install) · [Keys](#keys) · [Providers and limits](#providers-and-limits) ·
[Menu mode and decision models](#menu-mode-and-decision-models-jev) · [Autopilot bots](#autopilot-bots-no-llm-needed) ·
[Actions](#actions) · [Commands](#commands-op-level-2) · [Admin panel](#admin-panel) · [Logs](#logs) · [Limits](#limits)

## Quick start

1. Put `aiciv-0.1.0.jar` and Fabric API in `mods/` of a Fabric 26.3 server or client (see [Install](#install)).
2. Start the world once, then put a free API key in `config/aiciv/keys.json` (see [Keys](#keys)).
3. In game, run `/ai spawn Ada gemini-flash` for an LLM bot or `/ai spawn Rex autopilot-speedrunner` for one that needs no key.
4. `/ai panel` prints a link to the local admin panel.

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
`/ai goal Rex get_iron_armor`, `/ai goal Rex none` to unpin); other text is refused with the list of ids.
`social` answers chat such as "Rex give me 3 bread", "Rex come here" or "Rex follow me", and anyone's
"anyone got bread" (but not another autopilot's). A person's request comes before any goal except staying
alive; a bot that cannot answer at once says it is busy, and a hermit says no. `beat_game` runs
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

Milestone 4 added about 57 actions. Each is a catalog function with the same limits and failure texts a player would
meet; `go_to_nether`, `get_blaze_rods`, `go_to_end` and the other routines chain primitives (see below).

| Group | Actions |
|-------|---------|
| Portals and dimensions | `build_portal` (10 obsidian, lit with flint and steel or a fire charge), `cast_portal` (water on lava against a temporary wall), `light_portal`, `enter_portal(kind)` (`nether`, `end`, `any`; real portal timers), `go_to_nether`, `leave_nether` |
| Nether | `find_fortress(direction, distance)`, `hunt_blazes(count)`, `get_blaze_rods(count)` |
| Stronghold and the End | `make_eyes(count)`, `throw_eye`, `locate_stronghold(spread)`, `go_to_stronghold`, `find_portal_room`, `fill_end_portal`, `go_to_end` |
| The dragon | `fight_dragon`, `shoot_crystals(count)`, `break_cage`, `hit_dragon(seconds)`, `leave_end` |
| Travel | `place_boat`, `board`, `dismount`, `ride_to(x, z)`, `boat_to(x, z)`, `place_minecart`, `ride_minecart` (at most 512 blocks per call) |
| Flight and pearls | `fly_to(x, z)` (elytra, 16 to 1024 blocks, up to 16 rockets), `launch_elytra`, `boost`, `throw_pearl(x, y, z)` (3 to 40 blocks, costs 5 health) |
| Items | `fill_bucket`, `empty_bucket`, `bone_meal(count)`, `ignite`, `carve_pumpkin`, `drink`, `throw`, `make_map`, `link_compass` |
| Animals | `tame`, `saddle`, `leash`, `tie_lead`, `unleash`, `milk`, `shear`, `dye_sheep` |
| Stations | `upgrade_netherite`, `trim_armor`, `stonecut(count)`, `grindstone`, `repair`, `loom`, `cartography`, `write_book` (10 pages of 1024 characters, title up to 32) |
| Redstone | `build` legends take block states, `use_block` sets repeater delay, comparator mode, daylight detector mode |

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

Legend values may carry block states in brackets, so redstone and other oriented blocks can be built:
`"R": "repeater[facing=forward,delay=2]"`. `facing` is a compass direction or, relative to the blueprint,
`forward`, `back`, `left` or `right`. Supported states are `facing`, `delay`, `mode`, `face`, `axis` and `powered`
(levers only). The bot stands and looks the way a player would to get the facing it wants, sets repeater delays,
comparator modes and lever positions with real clicks, and fails (taking its own wrong block down) when the block
comes out different. A stone strip with a line of three repeaters on it, each delaying by 3 ticks:

```json
{ "name": "delay_line", "facing": "S", "legend": { "r": "repeater[facing=forward,delay=3]", "s": "stone" },
  "layers": [ ["sss"], ["rrr"] ] }
```

**Bots never break blocks other players or bots placed.** Every placed block is recorded in
`<world>/aiciv/placed.json`; navigation never digs them, `mine` skips them and `build` fails rather than clearing them.
Natural blocks in a footprint are cleared.

### Dimensions

Bots change dimension only through real portals, with their real timers: no teleporting. The rule for every action:
if the bot ends up in another dimension while an action runs (an accidental portal, a death, an end gateway), the
action ends `FAILED "... you went through to <dimension>"`, the navigator stops, and nothing walks toward coordinates of
the old dimension. Only `enter_portal` and the routines that expect to cross carry on. Actions that belong to one
dimension check it first: `"<tool> works in the_nether; you are in overworld"`. Known blocks, chests, villagers and
waypoints (portals, a fortress, the stronghold estimate, the End arrival) are remembered per dimension and are back when
the bot returns. Navigation never steps into a portal block or the void. After a crossing the bot waits beside the
arrival portal for the portal cooldown before it takes it back. If the server has no nether or End, `enter_portal`
fails with `the_nether does not exist on this server`.

### Routines and their steps

A routine is one action made of primitives that the model can also call one at a time. Each step runs when the previous
one is done; a failed step ends the routine with that step's reason.

| Routine | Steps |
|---------|-------|
| `go_to_nether` | walk to a known portal, else light a frame in sight, else `build_portal`; then `enter_portal nether` |
| `leave_nether` | walk to the nearest portal waypoint; `enter_portal nether` |
| `get_blaze_rods` | `find_fortress` unless one is remembered nearby; `hunt_blazes` |
| `make_eyes` | `craft blaze_powder` from rods when short; `craft ender_eye` |
| `locate_stronghold` | `throw_eye`, walk sideways, `throw_eye` (a third time if the flights are nearly parallel), triangulate |
| `go_to_stronghold` | walk to the estimate, throw eyes until one sinks, dig down, `find_portal_room` |
| `go_to_end` | `find_portal_room`; `fill_end_portal`; `enter_portal end` (each skipped when done) |
| `fight_dragon` | repeat what the situation needs: `shoot_crystals`, `break_cage`, `hit_dragon` |
| `leave_end` | walk to the fountain; `enter_portal end` through the exit portal |
| `boat_to` | `place_boat` (or use one nearby); `board`; `ride_to`; `dismount`; pick the boat up |
| `ride_minecart` | `place_minecart`; `board`; ride until it stops; `dismount`; pick the cart up |

### Tool-catalog size

In tool mode every request carries a function catalog; menu mode does not send one. Each model's `toolCatalog` picks how
much of it:

- `"gated"` (the default): the core and milestone 1-3 tools always, and each milestone 4 module only when it fits the
  bot's situation (dragon tools only in the End; nether tools in the nether or with obsidian, flint and steel, a fire
  charge or a bucket of lava or water, or a nether portal in sight; stronghold tools with ender pearls, eyes, blaze rods
  or powder or an end portal frame in sight; vehicles while riding, near a mount, boat or minecart, or carrying a boat,
  minecart or saddle; flight with an elytra or pearls; stations near a station block; animals with an animal in view;
  items when carrying a bucket, bone meal, shears, a potion, a throwable, a map or a compass). A typical overworld bot
  gets 46 functions, about 19,200 characters.
- `"full"`: all 95 functions, 34,578 characters (about 8,600 to 11,500 tokens).
- `"compact"`: gated, without the parameter descriptions (about 13,100 characters for the same overworld bot).

A provider that refuses a request as too large (HTTP 413, or Groq's "Request too large ... tokens per minute") does not
count against its health: the bot logs it once and asks again at once with the compact catalog, which it keeps from then
on. `CatalogSizeTest` measures these sizes.

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
| `/ai goal <name> <text>` | Sets its long-term goal (an autopilot takes only a goal id, which it pins, or `none`) |
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
