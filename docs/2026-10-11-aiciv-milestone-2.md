# AiCiv Milestone 2 ("Survivor") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bots chop, mine, collect, craft (making intermediate items themselves), smelt, place, eat, equip, fight, react by reflex, respawn after death, and path by digging, bridging, pillaring and swimming — so one Gemini bot can go from a fresh spawn to iron tools on its own, with few LLM calls.

**Architecture:** Same layering as milestone 1. Pure-Java planners (craft planning, block-name resolution, reflex policy, known-block cache, path search moves) are unit tested; Minecraft-bound executors (block breaker/placer, inventory ops, actions, respawn mixin) are verified with Fabric GameTests driven by the scripted LLM backend. Long-running actions do several steps per LLM call ("do more with less").

**Tech Stack:** As milestone 1 (MC 26.3, Fabric loader 0.19.5, fabric-api 0.161.0+26.3, loom 1.18.2, Java 25, JUnit 5.13.4). Adds MixinExtras (bundled with Fabric Loader) for one `@WrapOperation`.

**Spec:** `docs/superpowers/specs/2026-10-11-aiciv-design.md` — milestone 2 section, Actions table, Reflexes, Navigation table, Perception (known-block cache).

**Format note (ruling, user asked to finish fast):** milestone 1's plan carried full code; this plan carries exact interfaces, algorithms, verified 26.3 API names and the exact test cases, and lets implementers write the code with compile and test feedback. Where an algorithm is subtle, the plan gives it as code. Reviews check code against these contracts.

## Global Constraints

Everything in milestone 1's Global Constraints still holds (26.3 stack, `implementation` deps, no `net.minecraft.client`, keys never in repo/log, worktree `E:\Projects cursor\Mcmods\.worktrees\aiciv` branch `aiciv`, commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`). Additionally:

- Everything a bot does is something a survival player could do: real break times for the held tool, correct-tool rules for drops, crafting tables for 3x3 recipes, real furnaces and fuel, block reach 4.5, entity reach 3.0, attack cooldown.
- **Bots are always in Survival game mode**, whatever the world's default.
- Action timeouts (spec): explore 3 min, mine 5 min, collect 1 min, craft 30 s **per crafted step** (a multi-step craft gets 30 s × steps, capped at 3 min), smelt 5 min, place 30 s, eat 10 s, equip instant (1 tick), attack 1 min. Existing goTo/goToPlayer/follow/say/stop unchanged.
- `explore` distance ≤ 128; `mine`/`craft`/`smelt` counts 1..64; `follow` seconds 1..300.
- Navigation moves and costs (spec table): walk 1 (diagonal 1.4), jump up 2, drop ≤3 at 1 + 0.5/block, **swim 2, break 1 + seconds × 4, pillar or bridge 3** with throwaway blocks (dirt, cobblestone, cobbled_deepslate, netherrack, any planks). Never enters lava/fire/drop >3. 20,000-node cap, 2 ms/tick shared budget.
- Reflex thresholds (spec): eat at food ≤ 12; fight back when attacked by a mob while holding a weapon or tool; flee when health ≤ 6; swim up when air < max/3; step out of lava/fire to the nearest safe block. A reflex pre-empts the current action and the action resumes afterwards.
- Known-block cache: up to 128 exposed ores and utility blocks (crafting table, furnace, chest, bed); entries dropped when seen gone.
- Death: the bot respawns (vanilla respawn: world spawn or its bed) and gets the event `you died: <death message>. Your items dropped at x y z`.
- `maxActionsPerDecision` default becomes **6** (a reply can chain mine → craft → craft …).

## Review Focus

1. **A bot told to mine something it can't harvest** (e.g. iron ore with a wooden pickaxe, or a block type with none known nearby): it must fail fast with a reason the model can act on ("needs a stone pickaxe or better", "no iron_ore known nearby — explore to find some"), not dig for 5 minutes. → Task 6 tests `mineFailsFastWithoutTheRightTool`, `mineFailsFastWhenNothingKnown`.
2. **Craft with missing raw materials**: report exactly what is missing ("missing 2 more oak_log (or any planks)"), not "failed". → Task 7 test `planReportsMissingRawMaterials`.
3. **Inventory full while mining/crafting**: items that don't fit are dropped at the bot's feet, never deleted; the action keeps counting what it actually holds. → Task 2 test `addOrDropNeverLosesItems`.
4. **A reflex fires mid-action** (hungry while mining): after eating, the mine action continues and still counts correctly. → Task 9 game test `hungryBotEatsThenKeepsMining`.
5. **Bot dies mid-action**: no exception, actions cleared, it respawns once, the model is told, and the old player object is never ticked again. → Task 10 game test `botRespawnsAfterDeath`.

---

## File Structure

New or changed files (all under `AiCiv/src/`):

```
main/java/com/aiciv/
  bot/AiPlayer.java                 (modify) force survival on join; public static create(...) used by respawn mixin
  bot/Bot.java                      (modify) player/navigator non-final, swapped on respawn; adds reflexes, knownBlocks, breaker
  bot/BotManager.java               (modify) respawn flow; reflex tick; warning policy
  bot/BrainScheduler.java           (modify) ops-warning policy (persistent problems only)
  bot/LiveContext.java              (modify) implements the wider BotContext
  mixin/PlayerListRespawnMixin.java (new)   @WrapOperation on `new ServerPlayer` inside PlayerList.respawn
  world/InventoryOps.java           (new)   counting, selecting best tool/weapon, moving to hotbar, addOrDrop
  world/BlockBreaker.java           (new)   tick-based honest block breaking
  world/BlockPlacer.java            (new)   honest block placing via gameMode.useItemOn
  world/BlockNames.java             (new, pure) resolve "log"/"oak_log"/"iron" style names
  world/KnownBlocks.java            (new, pure) 128-entry cache
  world/BreakTime.java              (new)   seconds to break a state with the bot's best tool (used by nav + actions)
  craft/RecipeOption.java           (new, pure) record
  craft/CraftPlanner.java           (new, pure) recursive plan with intermediates
  craft/RecipeBook.java             (new)   builds RecipeOptions from the server's crafting recipes
  craft/CraftExecutor.java          (new)   executes one planned craft step against the real inventory
  nav/BlockView.java, PathSearch.java, PathFollower.java, LevelBlockView.java, LevelNavigator.java (modify) dig/bridge/pillar/swim
  nav/Move.java, nav/PathStep.java, nav/SearchOptions.java (new)
  actions/BotContext.java           (modify) adds player(), knownBlocks(), breaker()
  actions/ExploreAction, MineAction, CollectAction, CraftAction, SmeltAction, PlaceAction, EatAction, EquipAction, AttackAction (new)
  actions/Action.java               (modify) default interrupted(ctx) hook
  actions/ActionExecutor.java       (modify) pause/resume for reflexes
  actions/ActionFactory.java, ToolCatalog.java (modify) new actions
  reflex/ReflexPolicy.java          (new, pure) decide which reflex applies
  reflex/ReflexController.java      (new)   runs a reflex, pauses/resumes the executor
  perception/Perceiver.java, SnapshotData.java, SnapshotFormatter.java (modify) equipment line, remembered blocks, cache updates
  config/AiCivConfig.java           (modify) ProviderConfig.timeoutSeconds; maxActionsPerDecision 6
  llm/LlmService.java, ProviderHealth.java (modify) per-provider timeout; status text without empty "()"
  gametest/AiCivGameTests.java      (modify) new game tests (or split into SurvivalGameTests.java in the same package, also listed in fabric.mod.json's fabric-gametest entrypoint)
resources/aiciv.mixins.json         (modify) add PlayerListRespawnMixin
test/java/com/aiciv/...             unit tests named per task
```

Verified 26.3 APIs (javap, 2026-10-11) the tasks rely on:
- `ServerPlayer.gameMode` (public final `ServerPlayerGameMode`): `destroyBlock(BlockPos)`, `useItemOn(ServerPlayer, Level, ItemStack, InteractionHand, BlockHitResult)`, `useItem(ServerPlayer, Level, ItemStack, InteractionHand)`; `ServerPlayer.setGameMode(GameType)`.
- `BlockState`: `getDestroyProgress(Player, BlockGetter, BlockPos)`, `getDestroySpeed(BlockGetter, BlockPos)` (hardness; < 0 = unbreakable), `requiresCorrectToolForDrops()`, `canBeReplaced()`.
- `ItemStack`: `getDestroySpeed(BlockState)`, `isCorrectToolForDrops(BlockState)`, `get(DataComponents.FOOD|TOOL|WEAPON|EQUIPPABLE)`, `copyWithCount`, `grow/shrink/split`; `Block.getDrops(BlockState, ServerLevel, BlockPos, BlockEntity, Entity, ItemInstance)`.
- `Level.destroyBlockProgress(int entityId, BlockPos, int stage)` (stage −1 clears).
- `Inventory`: `getNonEquipmentItems()` (main 36), `setSelectedSlot/getSelectedSlot`, `getSelectionSize()` (9), `add(ItemStack)`, `setItem/getItem/removeItem(int,int)`, `getFreeSlot()`.
- Recipes: `ServerLevel.recipeAccess()` → `RecipeManager.getRecipes()` (`Collection<RecipeHolder<?>>`), `getRecipeFor(RecipeType, I, Level)`; `CraftingRecipe` (`RecipeType.CRAFTING`), `ShapedRecipe.getWidth/getHeight/getIngredients()` (`List<Optional<Ingredient>>`), `Recipe.placementInfo().ingredients()`, `Recipe.matches(I, Level)`, **`Recipe.assemble(I)` (one argument in 26.3)**, `CraftingRecipe.getRemainingItems(CraftingInput)`, `CraftingInput.of(w, h, List<ItemStack>)`, `Ingredient.test(ItemStack)`, `Ingredient.items()` (`Stream<Holder<Item>>`); smelting: `RecipeType.SMELTING`, `SingleRecipeInput(ItemStack)`, `AbstractCookingRecipe.cookingTime()`.
- Furnace: `AbstractFurnaceBlockEntity` slots 0 input, 1 fuel, 2 result; `getItem/setItem/removeItem`, `canPlaceItem(1, stack)` tells whether a stack is fuel.
- Combat/eating: `Player.attack(Entity)`, `getAttackStrengthScale(float)`, `entityInteractionRange()`, `blockInteractionRange()`; `LivingEntity.swing(InteractionHand)`, `startUsingItem`, `isUsingItem`, `stopUsingItem`; `Entity.lookAt(EntityAnchorArgument.Anchor, Vec3)`, `getAirSupply/getMaxAirSupply`, `isInLava`, `isOnFire`; `Equippable.slot()`; `FoodProperties.nutrition()`.
- Respawn: `PlayerList.respawn(ServerPlayer, boolean, Entity.RemovalReason)` constructs exactly one `new ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ClientInformation)`; `ServerGamePacketListenerImpl.player` is a public field; `LevelData.RespawnData.pos()/dimension()`. MixinExtras 0.5.x is on the classpath via Fabric Loader.

---

### Task 1: Live-run fixes

From the first in-game run (12 bots, 2026-10-11).

**Files:** Modify `bot/BrainScheduler.java`, `bot/BotManager.java`, `llm/ProviderHealth.java`, `llm/LlmService.java`, `config/AiCivConfig.java`, `bot/AiPlayer.java`; tests in `test/java/com/aiciv/bot/WarningPolicyTest.java` (new), `llm/ProviderHealthTest.java`, `config/AiCivConfigTest.java`, `llm/LlmServiceTest.java`; one game test.

**Interfaces:**
- Produces `com.aiciv.bot.WarningPolicy` (pure, package-private OK): `static boolean shouldChat(boolean primaryAuthDisabled, long waitingMillis)` → true iff `primaryAuthDisabled || waitingMillis >= 60_000`.
- `AiCivConfig.ProviderConfig.timeoutSeconds` (int, default 30); `LlmService.create` passes `Duration.ofSeconds(max(5, timeoutSeconds))` to `HttpChatBackend`.
- `AiCivConfig.maxActionsPerDecision` default 6.

**Requirements:**
1. Ops chat warnings for a waiting bot are sent only when `WarningPolicy.shouldChat(...)` is true. Track per bot the time it first became unable to dispatch (`waitingSince`, cleared when it dispatches). Transient pauses (rate limits, 503s, timeouts) shorter than 60 s go only to the server log (existing log dedupe).
2. `ProviderHealth.status()` never prints an empty `()`: when paused with no recorded problem, print `paused until HH:mm:ss`.
3. `AiPlayer.join` sets `GameType.SURVIVAL` after `placeNewPlayer` (bots spawned in a Creative world were Creative).
4. `timeoutSeconds` per provider as above; README documents it ("raise to 60 for slow models like gemini-3.8-flash").

**Tests:**
- `WarningPolicyTest`: `(false, 59_999) → false`, `(false, 60_000) → true`, `(true, 0) → true`.
- `ProviderHealthTest.statusHasNoEmptyParentheses`: onRateLimited then onSuccess while still paused → status starts with `paused until` and does not contain `()`.
- `AiCivConfigTest`: defaults give `maxActionsPerDecision == 6` and every provider `timeoutSeconds == 30`; a file with `"timeoutSeconds": 60` loads 60.
- `LlmServiceTest.timeoutComesFromConfig`: keep simple — assert the backend for a provider was built (use `create` with a config whose provider has `timeoutSeconds: 7`; expose `LlmService.timeoutFor(String provider)` returning the Duration used).
- Game test `botsAreAlwaysSurvival`: `server.setDefaultGameMode(GameType.CREATIVE)` (restore to SURVIVAL in a finally), spawn a bot via `BotManager.spawn`, assert `player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL`.

- [ ] Write the failing tests, see them fail, implement, see them pass, run `./gradlew test runGametest --no-daemon`, commit `"Keep bots in survival, quiet transient warnings, per-provider timeouts"`.

---

### Task 2: Inventory, break time, block breaking and placing

**Files:** Create `world/InventoryOps.java`, `world/BreakTime.java`, `world/BlockBreaker.java`, `world/BlockPlacer.java`; unit test `test/java/com/aiciv/world/BreakTimeMathTest.java`; game tests.

**Interfaces (produces):**
- `InventoryOps` (static helpers over `AiPlayer`):
  - `int count(AiPlayer p, Predicate<ItemStack> match)` — main inventory + offhand.
  - `boolean has(AiPlayer p, Item item, int n)`.
  - `int remove(AiPlayer p, Predicate<ItemStack> match, int n)` — returns removed count.
  - `void addOrDrop(AiPlayer p, ItemStack stack)` — `inventory.add`, and if anything remains, `p.drop(remainder, false)` (verify the 26.3 `drop` signature with javap; must not delete items).
  - `boolean selectBest(AiPlayer p, ToDoubleFunction<ItemStack> score)` — moves the best-scoring main-inventory stack into the hotbar (swap with the selected slot if not already in the hotbar) and selects it; false if nothing scores > 0.
  - `ItemStack bestToolFor(AiPlayer p, BlockState s)` — highest `getDestroySpeed(s)` among stacks that `isCorrectToolForDrops(s)` when `s.requiresCorrectToolForDrops()`, else highest speed; `ItemStack.EMPTY` = bare hand.
  - `int throwawayCount(AiPlayer p)` and `boolean selectThrowaway(AiPlayer p)` — throwaways: dirt, cobblestone, cobbled_deepslate, netherrack, any item whose id ends with `_planks`.
- `BreakTime.seconds(BlockState s, float hardness, ItemStack tool)` (pure math, static): `hardness < 0` → `+∞`; `hardness == 0` → 0.05; speed = `max(1, tool.isEmpty() ? 1 : tool.getDestroySpeed(s))`; `canHarvest = !s.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(s))`; `perTick = speed / hardness / (canHarvest ? 30 : 100)`; return `1 / perTick / 20`. Unit-test the arithmetic through a small pure overload `seconds(float hardness, float speed, boolean canHarvest)` (`stone 1.5, hand 1, no harvest → 7.5 s`; `stone 1.5, wooden pick 2, harvest → 1.125 s`; `oak log 2.0, hand → 3.0 s`; `bedrock −1 → ∞`; `grass 0 → 0.05`).
- `BlockBreaker` (one per bot): `void start(BlockPos pos)`; `Status tick()` returns `RUNNING`, `DONE`, or `FAILED(reason)`; `void cancel()`; `boolean busy()`. Each tick: abort if the block changed to air (DONE) or became farther than `blockInteractionRange()` from the eye (FAILED "out of reach"); select `bestToolFor`; look at the block centre; `swing(MAIN_HAND)` every 5 ticks; `progress += state.getDestroyProgress(player, level, pos)`; send `level.destroyBlockProgress(player.getId(), pos, (int)(progress*10))`; when `progress >= 1` call `player.gameMode.destroyBlock(pos)` (vanilla drops, tool damage, stats) and clear the crack (`stage −1`). Unbreakable (`hardness < 0`) → FAILED "can't break <block>".
- `BlockPlacer.place(AiPlayer p, BlockPos target, Predicate<ItemStack> item)` → `PlaceResult` enum `PLACED, NO_ITEM, OCCUPIED, NO_SUPPORT, OUT_OF_REACH, REFUSED`: target must be `canBeReplaced()`; find a neighbour face whose block is not replaceable (prefer below); select the item into the hotbar; look at the face; call `gameMode.useItemOn(p, level, stack, MAIN_HAND, new BlockHitResult(faceCentre, face.getOpposite(), neighbour, false))`; PLACED iff the block at target changed to non-replaceable.

**Tests:**
- Unit `BreakTimeMathTest` (cases above).
- Game `breakerBreaksStoneWithPickaxeAndDropsCobblestone`: floor; stone at (3,1,3); bot at (1.5,1,3.5) with a wooden pickaxe in inventory (not selected); `BlockBreaker.start`, tick each game tick; succeed when the block is air **and** a cobblestone item entity or inventory cobblestone exists within 40 ticks after; assert the pickaxe was selected.
- Game `breakerWithoutToolBreaksStoneButGetsNoDrop`: same with empty inventory → block becomes air after ~150 ticks, and no cobblestone item.
- Game `placerPlacesDirtOnTheFloor`: give 3 dirt, place at (2,1,2) → dirt there, inventory 2 dirt; `place` at an occupied spot returns OCCUPIED.
- Game `addOrDropNeverLosesItems` (Review Focus 3): fill all 36 main slots with stone ×64, `addOrDrop(new ItemStack(DIRT, 5))` → a dirt item entity with count 5 appears near the bot.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`; commit `"Break and place blocks like a survival player"`.

---

### Task 3: Path search v2 (pure)

**Files:** Modify `nav/BlockView.java`, `nav/PathSearch.java`; create `nav/Move.java`, `nav/PathStep.java`, `nav/SearchOptions.java`; extend `test/.../testutil/GridView.java`; tests in `nav/PathSearchTest.java` (existing tests must still pass).

**Interfaces:**
- `BlockView` adds `double breakSeconds(int x,int y,int z)` (`+∞` = unbreakable or must not break: fluids, dangerous blocks, blocks with a block entity such as chests/furnaces/beds) and `boolean water(int x,int y,int z)` (a water source or flowing water with no collision).
- `enum Move { WALK, DIAGONAL, JUMP, DROP, SWIM, PILLAR, BRIDGE }`.
- `record PathStep(Pos to, Move move, List<Pos> breaks, Pos place)` — `breaks`: blocks to break before entering `to` (feet/head of `to`, and the head-room block above the current position for JUMP/PILLAR); `place`: block to place (BRIDGE: floor under `to`; PILLAR: the block the bot stood in) or `null`.
- `record SearchOptions(boolean canBreak, boolean canPlace)`; constant `SearchOptions.WALK_ONLY = (false,false)`.
- `PathSearch(BlockView, Pos start, Goal, int maxNodes, SearchOptions)`; the existing 4-arg constructor delegates with `WALK_ONLY` (milestone-1 behaviour and tests unchanged). New `List<PathStep> steps()`; `path()` keeps returning the positions.

**Move rules** (costs from Global Constraints; `breakCost(cells) = Σ (1 + 4·seconds)` over cells that are not passable, `+∞` if any is `+∞`):
1. WALK/DIAGONAL/JUMP/DROP as milestone 1, but when `canBreak`, a cardinal WALK may enter a cell whose feet/head are breakable: cost `1 + breakCost(feet, head)`; a JUMP may break the head-room above the start and the target's feet/head: cost `2 + breakCost(...)`. Diagonals never break.
2. SWIM: from a cell whose feet are water (or that is passable above water) to any of the 6 neighbours whose feet are water or passable, cost 2; vertical swimming up/down allowed; a water cell counts as "standable" for continuing a swim. Never through lava.
3. BRIDGE (`canPlace`): cardinal move to a cell with clear body space but no standable floor and whose floor is replaceable and not dangerous: cost 3, `place = floor`.
4. PILLAR (`canPlace`): move straight up one block (to `(x,y+1,z)`) when head-room above is clear (or breakable when `canBreak`, adding its cost): cost 3, `place = current feet cell`.
5. Breaking or placing never targets a block adjacent to lava (any of its 6 neighbours dangerous with `breakSeconds == +∞` and not passable-solid) — prevents opening lava pockets.

**Tests** (GridView gains `breakable(x,y,z, seconds)` and `water(x,y,z)`):
- `tunnelsThroughOneBlockWallWhenAllowed`: the 2-high wall from `cannotClimbATwoBlockWall` made breakable (0.5 s) → with `(true,false)` FOUND, the step entering x=3 has `breaks` = both wall cells; with `WALK_ONLY` still FAILED.
- `prefersWalkingAroundOverSlowDigging`: wall with a gap 3 blocks around, wall break time 10 s → path goes through the gap.
- `bridgesAOneBlockGap`: floor x0..2 and x4..6, hole at x3 (void below) → with `(false,true)` FOUND and one BRIDGE step with `place == (3,0,z)`.
- `pillarsUpToALedge`: start in a 1x1 shaft with a ledge 2 up → with `(false,true)` FOUND with two PILLAR steps.
- `swimsAcrossALake`: floor at y0 x0..10, water cells at y1..2 for x3..7 (deep) with no floor at y0 under x3..7 → FOUND with SWIM steps; `WALK_ONLY` also swims (swimming needs no tools) — assert FOUND for both.
- `neverDigsNextToLava`: wall cell adjacent to a lava cell → path avoids breaking it.
- All milestone-1 PathSearch tests unchanged and passing.

- [ ] Tests first; implement; `./gradlew test --no-daemon --tests "com.aiciv.nav.*"`; commit `"Plan paths that dig, bridge, pillar and swim"`.

---

### Task 4: Path execution v2

**Files:** Modify `nav/LevelBlockView.java`, `nav/PathFollower.java`, `nav/LevelNavigator.java`, `bot/Bot.java` (add `public final BlockBreaker breaker`), `bot/BotManager.java` (`join` creates the breaker and passes it to the navigator); game tests.

**Interfaces:**
- `LevelBlockView(ServerLevel level, AiPlayer player)` (new constructor; keep the old one for compatibility with `breakSeconds` = `+∞`): `breakSeconds` = `BreakTime.seconds(state, state.getDestroySpeed(level,pos), InventoryOps.bestToolFor(player, state))`, `+∞` for fluids, dangerous blocks, blocks with a block entity, and blocks not loaded; `water` from the fluid state.
- `PathFollower(List<PathStep> steps, BlockBreaker breaker)`; `Step tick(AiPlayer p)` as before (MOVING / ARRIVED / STUCK) plus the new behaviours below. Progress for the stuck timer counts while breaking or placing (a long dig is not "stuck").
- `LevelNavigator(AiPlayer, NavBudget, BlockBreaker)`; each `plan()` builds `SearchOptions(canBreak = true, canPlace = InventoryOps.throwawayCount(player) > 0)`.

**Step execution:**
- Before moving toward a step with non-empty `breaks`: stop moving, break each listed block in order with the `BlockBreaker` (skip cells already passable); if the breaker FAILS → STUCK.
- BRIDGE: walk to the edge (within 0.6 of the boundary toward `to`), sneak is not required; place a throwaway at `place` with `BlockPlacer` against the current floor's side face; if placement fails → STUCK.
- PILLAR: look straight down, set jumping; when `p.getY() >= place.getY() + 1.05` (feet above the block) place a throwaway at `place`; continue to the next step when on ground at `to`.
- SWIM: set jumping when the next step is higher or the eyes are in water; move toward the step centre.
- Re-plan rules unchanged (3 s no progress → replan; 4th → `stuck at x y z`).

**Tests (game):**
- `tunnelsThroughStoneWall`: 8x8 floor; a full-width 2-high stone wall at x=4; bot with a wooden pickaxe at x=1 → `goTo` x=6 ARRIVED; wall cells it passed are air.
- `bridgesGapWithDirt`: floor with a 1-wide trench of air 3 deep at x=4 (fill the trench below with air inside the structure); bot with 4 dirt → ARRIVES on the far side; dirt count decreased by ≥1.
- `pillarsOntoLedge`: 2-high ledge with no stairs; bot with 4 dirt → ARRIVES on the ledge.
- `swimsAcrossPool`: 3-deep water pool across the path (water source blocks), bot crosses and ARRIVES.
- `obstacleCourse` from milestone 1 still passes (bot must not dig through when walking around is cheaper — assert the magma-avoidance flag as before).

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`; commit `"Walk paths that dig, bridge, pillar and swim"`.

---

### Task 5: Known-block cache and perception updates

**Files:** Create `world/KnownBlocks.java`, `world/BlockNames.java`; modify `perception/Perceiver.java`, `SnapshotData.java`, `SnapshotFormatter.java`, `bot/Bot.java` (add `public final KnownBlocks knownBlocks = new KnownBlocks(128)`), `bot/BrainScheduler.java` (pass it to `capture`); tests `world/KnownBlocksTest.java`, `world/BlockNamesTest.java`, `perception/SnapshotFormatterTest.java`.

**Interfaces:**
- `KnownBlocks` (pure): `KnownBlocks(int capacity)` (128); `void see(String label, Pos pos, long tick)` (insert or refresh; evict the least-recently-seen beyond capacity); `void gone(Pos pos)`; `Optional<Pos> nearest(Predicate<String> label, Pos from)`; `List<Map.Entry<Pos,String>> nearestN(Pos from, int n, double minDistance)`; `int size()`.
- `BlockNames` (pure): `static Predicate<String> matcher(String query)` over block ids (paths without `minecraft:`): exact id; else the families `log|logs|wood|tree|trees` → id ends with `_log` or `_stem`; `stone` → `stone`, `cobblestone`, `deepslate`; `planks` → ends with `_planks`; else id ends with `_` + query or contains query (e.g. `iron` → `iron_ore`, `deepslate_iron_ore`); `static String normalise(String raw)` → lower-case, strips `minecraft:`, spaces → `_`, trailing `s` removed if no exact match.
- `Perceiver.capture(AiPlayer bot, String currentAction, String lastResult, Predicate<ServerPlayer> isAi, KnownBlocks known)` (keep the 4-argument overload, passing a throwaway cache, for existing callers/tests) additionally (a) records every exposed sighting of an ore, log, or utility block into the bot's `KnownBlocks` with its exact position (the scan already visits them), and (b) calls `gone(pos)` for cached entries within the scan radius whose block no longer matches. Logs are cached too (so `mine log` can return to trees).
- `Perceiver.exposedBlocks(ServerLevel level, BlockPos origin, int radius, Predicate<String> label)` → `List<Map.Entry<Pos,String>>` sorted nearest-first, using the same exposure rule as the snapshot (fluids need air; solids need air or water). Used by `MineAction` and `CraftAction`/`SmeltAction` table/furnace lookups.
- `SnapshotData` gains `String equipment` (e.g. `"iron_chestplate, shield"` or `"none"`) and `List<String> remembered` (up to 8 nearest cached entries farther than 24 m: `"iron_ore at 120 34 -50 (60m NE)"`). Formatter adds lines `Wearing: …` after Holding and `Remembered: …` after Mobs (truncation includes `remembered` as a fifth list).

**Tests:** `KnownBlocksTest` (capacity eviction keeps 128 newest; `gone` removes; `nearest` respects predicate and distance; `nearestN` skips entries closer than `minDistance`). `BlockNamesTest` (`log` matches `oak_log`, `birch_log`, `crimson_stem`, not `oak_planks`; `iron` matches `iron_ore`, `deepslate_iron_ore`; `Oak Logs` normalises to `oak_log`; `minecraft:stone` exact). `SnapshotFormatterTest` renders the two new lines and empties as `Wearing: none`, `Remembered: nothing`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`; commit `"Remember useful blocks and show gear in the snapshot"`.

---

### Task 6: Explore, mine and collect actions

**Files:** Create `actions/ExploreAction.java`, `actions/MineAction.java`, `actions/CollectAction.java`; modify `actions/BotContext.java` (+ `AiPlayer player()`, `KnownBlocks knownBlocks()`, `BlockBreaker breaker()`), `bot/LiveContext.java`, `testutil/FakeContext.java` (return null for the new ones); game tests.

**Interfaces / behaviour:**
- `ExploreAction(String direction, int distance)`: direction one of N, NE, E, SE, S, SW, W, NW (case-insensitive; also `north`, `north-east`…); target = position + unit vector × distance (≤128); `ColumnGoal(target, 8)`; DONE on ARRIVED; FAILED on nav failure; timeout 3 min.
- `MineAction(String blockQuery, int count)`: resolves `BlockNames.matcher`. Loop:
  1. Before anything: if no known/visible matching block → FAILED `no <query> known nearby — explore to find some` (check the bot's `KnownBlocks` and a fresh exposed scan within 24 using the Perceiver's exposure rule; Review Focus 1).
  2. Pick the nearest matching exposed block; if `state.requiresCorrectToolForDrops()` and no inventory item `isCorrectToolForDrops(state)` → FAILED `needs a <tier> pickaxe or better for <block>` (tier text: use the block's tag membership `BlockTags.NEEDS_STONE_TOOL` → "stone", `NEEDS_IRON_TOOL` → "iron", `NEEDS_DIAMOND_TOOL` → "diamond", else "wooden").
  3. Navigate to a `NearGoal(blockCentre, 3.5)`; then break with the bot's `BlockBreaker`; then collect drops: walk to item entities within 6 blocks of the broken block for up to 4 s (pickup is automatic in vanilla).
  4. `gained` = current count of the drop item types (computed from `Block.getDrops` of the first target before breaking; logs drop themselves) minus the count at start; DONE when `gained >= count`; mark mined positions `gone` in `KnownBlocks`.
  - Leaves in the way of a tree log: the path search may break them (they're cheap); mining a log at height > 2 above the bot: navigate as close as possible, break if within reach, else skip that log (try the next nearest).
  - Timeout 5 min; summary text on DONE includes what was gathered (`mine oak_log x8`).
- `CollectAction(String itemQuery)`: item entities within 16 matching (`BlockNames`-style matching over item ids); walk to each until none remain; DONE; timeout 1 min.

**Tests:**
- Game `minesLogsAcrossTwoTrees`: two 3-log oak trees (no leaves) 4 blocks apart; `MineAction("log", 5)` via the executor → bot holds ≥5 oak_log; both trees partly gone.
- Game `mineFailsFastWithoutTheRightTool`: exposed iron_ore, bot has a wooden pickaxe → FAILED within 20 ticks, reason contains `stone`.
- Game `mineFailsFastWhenNothingKnown`: nothing nearby → FAILED within 20 ticks with `explore`.
- Game `collectPicksUpDroppedItems`: drop 3 cobblestone item entities 5 blocks away → bot holds 3 cobblestone.
- Game `exploreWalksNorth`: open floor 8x8 is too small — use `ExploreAction("N", 6)` on a 16-long floor built with `helper.setBlock` (or assert ARRIVED/position moved ≥ 5 north).

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`; commit `"Explore, mine across several blocks, and pick up drops"`.

---

### Task 7: Crafting with intermediate items

**Files:** Create `craft/RecipeOption.java`, `craft/CraftPlanner.java`, `craft/RecipeBook.java`, `craft/CraftExecutor.java`, `actions/CraftAction.java`; tests `craft/CraftPlannerTest.java`; game tests.

**Interfaces:**
- `record RecipeOption(String output, int outputCount, List<Set<String>> ingredients, boolean needsTable, Object handle)` — `ingredients`: one entry per filled grid slot, each the set of acceptable item ids; `needsTable` = width>2 or height>2 (shaped) or >4 ingredients (shapeless); `handle` = the `RecipeHolder` (opaque to the planner).
- `CraftPlanner.plan(String target, int count, Map<String,Integer> inventory, List<RecipeOption> recipes, boolean tableNearby)` → `Plan` = `record Plan(List<Step> steps, Map<String,Integer> missing)`; `record Step(RecipeOption recipe, int times, Map<String,Integer> uses)`.
  - Algorithm (depth ≤ 4, no cycles): to obtain `n` of item `x`: use inventory first; for the shortfall pick, among recipes producing `x`, the first whose ingredients can be fully obtained (recursively) from the simulated inventory, preferring recipes whose ingredients are already held; `times = ceil(shortfall / outputCount)`; each ingredient slot consumes one item chosen from its set (prefer the item with the most units held).
  - If any step `needsTable` and `!tableNearby`: the plan first obtains `crafting_table` ×1 (itself via planks if needed) and the executor places it (see below).
  - If impossible: `missing` maps the cheapest raw requirement (e.g. `{"oak_log or any log": 1}`) — compute by trying the recipe whose ingredients are closest to satisfiable and reporting what's left after recursive crafting; use ingredient sets' first id when no family name applies, and render log/planks families as `any log`/`any planks`.
- `RecipeBook.load(ServerLevel)`: for each `RecipeHolder` whose value is a `CraftingRecipe` (`ShapedRecipe` or `ShapelessRecipe`; skip special recipes whose `placementInfo().isImpossibleToPlace()`): ingredients from `ShapedRecipe.getIngredients()` (skip empty Optionals) or `placementInfo().ingredients()`; acceptable ids from `Ingredient.items()`; output = `recipe.assemble(input)` where `input` is built with the first acceptable item per slot (shaped: `CraftingInput.of(w, h, items)` in pattern order with `ItemStack.EMPTY` for empty cells; shapeless: `CraftingInput.of(3, 3, …)` padded). Cache per server.
- `CraftExecutor.apply(AiPlayer p, Step step)` → `boolean`: per `times`: build the real `CraftingInput` from actual inventory items matching each slot, check `recipe.matches(input, level)`, remove the items, `assemble(input)` → `InventoryOps.addOrDrop`, and add `getRemainingItems(input)` back (buckets etc.).
- `CraftAction(String item, int count)`: `count` = items wanted (not crafts). Flow: plan with the current inventory and `tableNearby` (crafting table within 4 blocks, via scan or `KnownBlocks`); if `missing` non-empty → FAILED `missing <list>` (Review Focus 2); if a step needs a table and none is nearby: craft the table if the plan includes it, then place it on a free floor cell adjacent to the bot (`BlockPlacer`) and remember it; execute steps one per 5 ticks (so the action is watchable); timeout 30 s × steps (≤ 3 min); DONE summary `crafted 1 wooden_pickaxe (made 8 oak_planks, 4 stick, 1 crafting_table on the way)`.

**Tests:**
- Unit `CraftPlannerTest` with hand-written RecipeOptions (oak_log→4 oak_planks; 2 planks→4 stick; 4 planks→crafting_table (2x2); 3 planks+2 stick→wooden_pickaxe (table)): `plansPickaxeFromLogsWithIntermediates` (inventory 3 oak_log, no table → steps planks, (sticks), table, pickaxe, missing empty); `usesHeldIntermediatesFirst` (holding 3 planks + 2 sticks + table nearby → one step); `planReportsMissingRawMaterials` (1 oak_log → missing mentions `any log`); `doesNotLoopOnCycles` (a↔b recipes → returns missing, no stack overflow); `countMeansItemsNotCrafts` (want 8 sticks → 2 crafts).
- Game `craftsWoodenPickaxeFromLogsInOneAction`: bot holds 3 oak_log, no table nearby → `CraftAction("wooden_pickaxe", 1)` → holds a wooden_pickaxe; a crafting_table block exists within 4 blocks.
- Game `craftFailsWithMissingMaterials`: 1 oak_log, `craft stone_pickaxe` → FAILED mentioning `cobblestone`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`; commit `"Craft with intermediates and place a table when needed"`.

---

### Task 8: Smelt, place, eat and equip actions

**Files:** Create `actions/SmeltAction.java`, `PlaceAction.java`, `EatAction.java`, `EquipAction.java`; game tests.

**Behaviour:**
- `SmeltAction(String item, int count)`: `item` may be the input (`raw_iron`) or the output (`iron_ingot`) — resolve via smelting recipes (`getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level)` for each held stack; or recipes whose `assemble` result matches). Needs a furnace within 4 blocks (scan/`KnownBlocks`); if none: if the bot has a `furnace` item, place it; else if `CraftPlanner` can make one (8 cobblestone), craft and place it; else FAILED `needs a furnace or 8 cobblestone`. Fuel: any held stack the furnace accepts in slot 1 (`canPlaceItem(1, s)`), preferring coal/charcoal, then logs/planks, never the input item; enough for `count` items (coal = 8 items, planks/logs = 1.5, sticks = 0.5 — use `ceil` and add one extra). Move to within reach, put `count` inputs into slot 0 and fuel into slot 1 (respect existing contents: refuse if slot 0 holds a different item → take it out into the inventory first), wait while watching slot 2, take outputs as they appear (`addOrDrop`), DONE when `count` outputs taken; timeout 5 min. Take leftover fuel/input back on DONE or cancel.
- `PlaceAction(String block, int x, int y, int z)`: navigate within reach (NearGoal radius 3.5 around the target, not standing in the target cell), `BlockPlacer.place`; map results to FAILED reasons (`occupied`, `nothing to place it against`, `no <block> in inventory`).
- `EatAction()`: best food by `FoodProperties.nutrition()` (skip items with harmful effects: rotten_flesh, spider_eye, poisonous_potato, pufferfish, suspicious_stew); select into hotbar; `gameMode.useItem(...)`; keep `startUsingItem` until `!isUsingItem()` and the stack shrank; FAILED `nothing to eat` / `not hungry` (food == 20); timeout 10 s.
- `EquipAction(String item)`: armor (has `EQUIPPABLE` with an armor slot or offhand shield) → `setItemSlot(slot, stack)` moving the old piece back to the inventory; otherwise select into the hotbar. DONE in one tick; FAILED `no <item> in inventory`.

**Tests (game):** `smeltsRawIronWithCoal` (furnace placed by the test, bot holds 2 raw_iron + 1 coal → holds 2 iron_ingot; maxTicks 600), `smeltPlacesHeldFurnace` (bot holds furnace + raw_iron + coal, no furnace nearby → furnace block appears within 4), `placesCobblestoneAtTarget`, `eatsBreadWhenHungry` (food level 10 → after EatAction food > 10, bread count down), `equipsIronChestplate`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`; commit `"Smelt in furnaces, place blocks, eat and equip"`.

---

### Task 9: Combat and reflexes

**Files:** Create `actions/AttackAction.java`, `reflex/ReflexPolicy.java`, `reflex/ReflexController.java`; modify `bot/Bot.java` (add `public final ReflexController reflexes`), `actions/Action.java` (`default void interrupted(BotContext ctx) {}`), `actions/ActionExecutor.java` (`pause(BotContext)`, `resume(BotContext)`, `paused()`), `actions/GoToAction.java`, `GoToPlayerAction.java`, `FollowAction.java`, `MineAction.java` (on `interrupted`, forget the current navigation so the next tick re-plans), `bot/BotManager.java` (tick order: reflexes → navigator → executor); tests `reflex/ReflexPolicyTest.java`; game tests.

**Interfaces:**
- `AttackAction(String target)`: resolve `PlayerNames.clean(target)` as an online player in the same dimension, else a mob type by id (`zombie`, `minecraft:zombie`, plural `zombies` ok) — nearest living match within 16; select the best weapon (`WEAPON` component or highest attack damage; fall back to an axe, then any tool, then hand); approach with `NearGoal(target, 2.0)` re-planned when the target moves > 2 blocks; when within `entityInteractionRange()` and `getAttackStrengthScale(0.5f) >= 0.9f`: `lookAt(EYES, target.getEyePosition())`, `attack(target)`, `swing(MAIN_HAND)`. DONE when the target is dead (`!isAlive()`); FAILED `<target> got away` if farther than 16 for 3 s; FAILED `no <target> nearby` at start; timeout 1 min. Never targets itself.
- `ReflexPolicy` (pure): `enum Reflex { NONE, ESCAPE_HAZARD, SURFACE, FLEE, FIGHT_BACK, EAT }`; `static Reflex decide(Snapshot s)` with `record Snapshot(boolean inLavaOrFire, int air, int maxAir, float health, int food, boolean hasFood, boolean recentlyAttackedByMob, boolean holdingWeaponOrTool, boolean busyEating)` — priority: ESCAPE_HAZARD (in lava/fire) > SURFACE (`air < maxAir/3`) > FLEE (`health <= 6 && recentlyAttackedByMob`) > FIGHT_BACK (`recentlyAttackedByMob && holdingWeaponOrTool`) > EAT (`food <= 12 && hasFood`) > NONE.
- `ReflexController` (per bot): `void tick(Bot b)` — computes the snapshot (`recentlyAttackedByMob` = last hurt by a `Mob` within 5 s and that mob is alive within 16), and when a reflex starts: `executor.pause(ctx)` (calls `interrupted` on the current action and stops ticking it), runs the reflex with its own short behaviour (ESCAPE: navigator to the nearest non-dangerous standable cell within 6; SURFACE: jump/swim up; FLEE: `ColumnGoal` 12 blocks directly away from the attacker; FIGHT_BACK: an internal `AttackAction` on the attacker; EAT: an internal `EatAction`), and when it ends `executor.resume(ctx)`. Reflex start/end are noted in the brain's event log (`reflex: ate bread`, `reflex: fled from zombie`) without waking the brain. Reflexes never run while the bot is paused or dead.

**Tests:**
- Unit `ReflexPolicyTest`: one case per priority level plus "nothing applies → NONE" and "hungry but no food → NONE".
- Game `killsZombieWithSword`: set difficulty EASY (restore after), spawn a zombie 4 blocks away (`helper.spawn(EntityType.ZOMBIE, …)`), bot holds an iron sword → `AttackAction("zombie")` DONE and the zombie is dead within 300 ticks.
- Game `hungryBotEatsThenKeepsMining` (Review Focus 4): bot with bread, wooden pickaxe, food set to 8, running `MineAction("stone", 3)` with 4 exposed stone → ends DONE holding ≥3 cobblestone, food > 8, event log contains `reflex: ate`.
- Game `fleesWhenLowHealth`: health 5, recently hurt by a zombie → bot ends ≥ 8 blocks from the zombie (peaceful off; zombie with `setNoAi(true)` so it doesn't chase).

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`; commit `"Fight, flee, eat and escape by reflex"`.

---

### Task 10: Death and respawn

**Files:** Create `mixin/PlayerListRespawnMixin.java`; modify `resources/aiciv.mixins.json`, `bot/AiPlayer.java` (public static `AiPlayer create(MinecraftServer, ServerLevel, GameProfile)` wrapping the private constructor and re-applying survival + skin customisation), `bot/Bot.java` (`player`, `navigator`, `context` become non-final, swapped together), `bot/BotManager.java` (replace milestone 1's logout-on-death); game test.

**Respawn mixin (verified shape: one `new ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ClientInformation)` in `PlayerList.respawn(ServerPlayer, boolean, Entity.RemovalReason)`):**

```java
@Mixin(PlayerList.class)
abstract class PlayerListRespawnMixin {
    @WrapOperation(method = "respawn",
            at = @At(value = "NEW", target = "net/minecraft/server/level/ServerPlayer"))
    private ServerPlayer aiciv$respawnAsAi(MinecraftServer server, ServerLevel level, GameProfile profile,
                                           ClientInformation info, Operation<ServerPlayer> original,
                                           @Local(argsOnly = true) ServerPlayer old) {
        if (old instanceof AiPlayer) return AiPlayer.create(server, level, profile);
        return original.call(server, level, profile, info);
    }
}
```
(imports: `com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation`, `Operation`, `com.llamalad7.mixinextras.sugar.Local`.) Verify the constructor descriptor with `javap -c` if the injection fails; the mixin config stays `required: true`.

**Flow:** on death (existing AFTER_DEATH hook) the manager records `deathMessage` (`source.getLocalizedDeathMessage(p).getString()`) and death position, stops the executor/navigator/breaker/reflexes, and on the **next** server tick calls `server.getPlayerList().respawn(old, false, Entity.RemovalReason.KILLED)`. Vanilla `respawn` moves the connection to the new player (`newPlayer.connection = old.connection`) but only the client-command path updates the listener's own `player` field, so after the call set `newPlayer.connection.player = newPlayer` (public field). Swap `Bot.player`, rebuild `Bot.navigator` (`new LevelNavigator(newPlayer, navBudget, breaker)`) and `Bot.breaker`, keep the brain and executor; brain event `you died: <message>. Your items dropped at x y z` + wake ACTION_ENDED. Bots never call respawn twice for one death (guard on the old instance). The roster and playerdata are unchanged.

**Tests:** Game `botRespawnsAfterDeath` (Review Focus 5): spawn via manager, `kill`, tick 5 ticks → `manager.bot(name).player` is a different, alive `AiPlayer`, the player list holds exactly one entry with that name, health is max, the brain's events contain `you died`, and the old instance `isRemoved()`; the scripted backend received no request between death and respawn. Remove milestone 1's `botDeathLogsOutWithoutMoreCalls` expectations that the bot is gone (update that test to the new behaviour). README: replace the milestone-1 death note.

- [ ] Test first; implement; `./gradlew test runGametest --no-daemon`; commit `"Respawn dead bots like players do"`.

---

### Task 11: Tools, prompt and action wiring

**Files:** Modify `actions/ActionFactory.java`, `actions/ToolCatalog.java`, `brain/PromptBuilder.java` (only if wording needs it), `README.md`; tests `actions/ActionFactoryTest.java`, `brain/PromptBuilderTest.java`.

**Requirements:**
- Factory accepts (case/underscore-insensitive as before): `explore(direction, distance)`, `mine(block, count)`, `collect(item)`, `craft(item, count)`, `smelt(item, count)`, `place(block, x, y, z)`, `eat()`, `equip(item)`, `attack(target)`; counts default 1 and clamp to 1..64; distance clamps to 1..128; validation messages in the milestone-1 style; `AVAILABLE` lists all 14 actions; `KNOWN` and the switch stay in sync (add a test that every catalog name is known and every known name is in the catalog).
- `ToolCatalog.tools()` has 14 functions with clear one-line descriptions that state the "do more with less" behaviour: `mine` "Mine `count` more of a block, moving between blocks or trees as needed; use 'log' for any tree", `craft` "Craft `count` of an item; makes needed intermediates (planks, sticks, crafting table) from what you carry, and places a crafting table if one is needed", `smelt` "…places a furnace if you carry one or can craft one". `guide()` explains chaining: "One turn can chain up to 6 actions, e.g. mine log 6 → craft wooden_pickaxe → mine stone 3 → craft stone_pickaxe".
- README: new actions table, chaining tip, fighting needs difficulty Easy or higher for mobs.

**Tests:** factory parsing for each new action incl. defaults/clamps/bad args; catalog ↔ factory sync test; `PromptBuilderTest` asserts "up to 6".

- [ ] Tests first; implement; `./gradlew test --no-daemon`; commit `"Offer the survival actions to the model"`.

---

### Task 12: Survivor acceptance

**Files:** Game test in `gametest/AiCivGameTests.java` (or `SurvivalGameTests.java`); update `docs/superpowers/specs/2026-10-11-aiciv-design.md` "Changes made during milestone 2".

**Test `scriptedBotReachesIronTools`** (maxTicks 6000): build a 16x16 area (floor of stone and dirt, 3 oak trees, an exposed 6-block stone outcrop, exposed coal_ore ×2 and iron_ore ×3 at the surface); scripted backend with these replies in order, each a single message with chained tool calls:
1. `mine(log, 6)`; 2. `craft(wooden_pickaxe, 1)`; 3. `mine(stone, 11)`; 4. `craft(stone_pickaxe, 1)`, `mine(coal, 2)`; 5. `mine(iron, 3)`; 6. `smelt(raw_iron, 3)`; 7. `craft(iron_pickaxe, 1)`.
Assert within the time limit: the bot holds an `iron_pickaxe`; the decision log has 7+ decisions and no `"error"`.

**Live acceptance (hand to the user):** spawn one Gemini bot in a fresh survival world (`/ai spawn Smith gemini-35-flash "A determined survivor. Get iron tools as fast as you can."`) and watch it reach iron tools; report time and number of LLM calls from `logs/aiciv/Smith.jsonl`.

- [ ] Write the test; run `./gradlew build test runGametest --no-daemon`; record changes in the spec; commit `"Prove a bot can reach iron tools on its own"`.

---

### Task 13: Decision modes — "menu" choices and the Jev (TypeSafe) backend

Added 2026-10-11 at the user's request: let decision models such as TypeSafe AI's **Jev** (returns one choice from a fixed option list, with per-option probabilities; not a chat model) drive bots, and let any LLM run in the same constrained "menu" mode for comparison. Run this task after Task 11 and before Task 12.

**Files:** Create `brain/OptionMenu.java`, `brain/MenuOption.java`, `llm/TypesafeBackend.java`, `llm/MenuReply.java`; modify `config/AiCivConfig.java` (`ModelConfig.decisionMode`, provider `type: "typesafe"`), `llm/LlmService.java` (route menu requests; `LlmService.create` builds a `TypesafeBackend` for `type: "typesafe"`), `bot/BrainScheduler.java` (menu path), `brain/PromptBuilder.java` (menu prompt), `README.md`; tests `brain/OptionMenuTest.java`, `llm/TypesafeBackendTest.java` (mock HTTP server like `HttpChatBackendTest`), `llm/LlmServiceTest.java`; one game test.

**Interfaces:**
- `ModelConfig.decisionMode`: `"tools"` (default, current behaviour) or `"menu"`. Providers of `type: "typesafe"` force `"menu"`.
- `record MenuOption(String id, String description, List<ToolCall> calls)` — `id` is short, lower-case, `[a-z0-9_]{1,40}`, unique per menu (e.g. `mine_oak_log`, `craft_wooden_pickaxe`, `attack_zombie`, `go_to_steve`, `explore_n`); `calls` is the 1–3 action chain the option stands for (built with the existing `ToolCall.of`).
- `OptionMenu.build(SnapshotData snap, MenuContext ctx)` → `List<MenuOption>` (max 40; at least `wait` and `explore_*`). `record MenuContext(Map<String,Integer> inventory, List<String> craftableTargets, boolean hungry, List<String> hostileMobs, List<String> players, List<String> mineableLabels)` — built in the scheduler from the bot's inventory, `CraftPlanner` (targets from a fixed progression list: crafting_table, wooden_pickaxe, stone_pickaxe, furnace, iron_pickaxe, iron_sword, iron_chestplate, bread, torch — include only those the planner can make now), visible/known blocks (logs, stone, coal/iron/gold/diamond ores), hostile mobs within 16, players within 32.
  - Option rules: `mine_<label>` (count: logs 6, stone 8, ores 3); `craft_<item>`; `smelt_raw_iron` when holding raw iron; `eat` when hungry with food; `attack_<mob>` per hostile type; `go_to_<player>` and `follow_<player>` (60 s) per player; `explore_n|e|s|w` (64 blocks); `equip_<armor>` for unequipped armor held; `say_hello` (canned "Hi!") and `say_help` ("Anyone need help?") so menu bots can still be social; `wait` (no-op; the bot waits for its idle timer).
- `TypesafeBackend(String baseUrl, String apiKey, Duration timeout)`: `CompletableFuture<MenuReply> choose(String model, String state, List<MenuOption> options, String instructions)` → `POST {baseUrl}/v1/systemone` with `Authorization: Bearer <key>` and body `{"model": model, "state": state, "questions": {"action": {"type": "choice", "instructions": instructions, "criteria": {"<id>": "<description>", …}}}}` (type names lower-case; ≤255 options). Parse `answers`/`action` tolerantly: the chosen option id from the first string field among `choice`, `answer`, `value`, `option`; probabilities from an object field `probabilities` (id → number) if present; confidence from `confidence` if present. HTTP status handling identical to `HttpChatBackend.interpret` (429 → rate limited, 401/403 → auth, network/timeouts → failed; key redacted from reasons). **Response field names are unverified until a live key exists** — keep parsing in one method with a test per accepted shape, and log the raw JSON (key-free) at DEBUG on the first call.
- `record MenuReply(String optionId, Map<String,Double> probabilities, Double confidence)`.
- LLM providers in menu mode: one tool `choose` with parameter `option` (`"type": "string", "enum": [ids…]`, `required`), `tool_choice` = `{"type":"function","function":{"name":"choose"}}` when supported, else `"auto"`; the prompt lists `id — description` lines; the reply is parsed with the existing `ReplyParser` (tool call `choose`, or JSON `{"option": "<id>"}`, or a bare id in text).
- `BrainScheduler`: for menu-mode models, build the menu, send it, map the chosen id to its `calls` and run them through the existing `ActionFactory`/executor path (unknown id → note "chose an unknown option" and wait). The decision log adds `"mode":"menu"`, `"option"`, `"probabilities"` (if any), `"confidence"`.

**Config example (README):**
```json
"providers": { "typesafe": { "type": "typesafe", "baseUrl": "https://api.typesafe.ai", "key": "typesafe", "rpm": 60, "rpd": 0, "timeoutSeconds": 10 } },
"models": { "jev": { "provider": "typesafe", "model": "jev-latest", "decisionMode": "menu" },
            "gemma-menu": { "provider": "gemma", "model": "gemma-4-26b-a4b-it", "decisionMode": "menu" } }
```

**Tests:**
- `OptionMenuTest`: ids are unique and match the pattern; hungry + bread → `eat` present, full → absent; a zombie → `attack_zombie`; 3 oak_log and craftable `wooden_pickaxe` → `craft_wooden_pickaxe`; always contains `wait` and four `explore_*`; never more than 40.
- `TypesafeBackendTest` (JDK HttpServer): request path `/v1/systemone`, Bearer header, body has `questions.action.type == "choice"` and one criteria entry per option; response shapes `{"answers":{"action":{"choice":"mine_oak_log","probabilities":{…},"confidence":0.9}}}` and `{"action":{"answer":"eat"}}` both parse; 429/401/500/timeout map like the HTTP backend; the key never appears in a reason.
- `LlmServiceTest.menuModeUsesChooseTool`: a menu-mode model sends exactly one tool named `choose` whose `option` enum equals the option ids.
- Game `menuBotFollowsItsChoice`: scripted backend replying with tool call `choose{"option":"mine_oak_log"}` on a menu-mode model next to a tree → the bot holds oak_log.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`; commit `"Let decision models like Jev pick from a menu of actions"`.
