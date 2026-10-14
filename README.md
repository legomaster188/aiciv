# AiCiv

AI players for Minecraft 26.3, each driven by a language model (Gemini, Groq, OpenRouter,
GitHub Models, Mistral, Cerebras, OpenCode Zen or a local Ollama model). They join as normal
players, chat, and walk around. Milestones 1 to 4 (chat, survival, building, society and the endgame) of the design in
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

In tool mode every request carries the whole function catalog: 95 functions, 34,578 characters of JSON (about 8,600 to
11,500 tokens, measured by `CatalogSizeTest`, which fails above 60,000 characters). Menu mode does not send it. If a
provider's token limit bites, a later milestone can send only the modules that fit the bot's situation.

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
| `/ai goal <name> <text>` | Sets its long-term goal |
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
