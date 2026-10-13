# AiCiv Milestone 3 ("Builders & Society") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bots build from blueprints and templates, keep and share items through chests, give and drop items, name things, fight with short combat moves, trade with villagers, farm, breed, fish, sleep, use doors/buttons/levers, enchant and brew, all as a survival player could, offered to both tool-mode LLMs and menu-mode decision models (Jev).

**Architecture:** Task 1 adds a module registry (`ToolModule`) and a shared world toolkit, so every later feature lives in its own package and registers its tools, factory entries, menu options and snapshot sections there without touching shared files. Tasks 2 to 7 are independent and can run in parallel in separate git worktrees after Task 1 merges. Task 8 is the scripted acceptance run. As in milestone 2, pure planners/parsers are unit tested and Minecraft-bound actions are verified with Fabric GameTests.

**Tech Stack:** As milestones 1–2: MC 26.3 (unobfuscated Mojang names), Fabric loader 0.19.5, fabric-api 0.161.0+26.3, loom 1.18.2, Java 25, JUnit 5.13.4, MixinExtras (bundled with Fabric Loader).

**Spec:** `docs/superpowers/specs/2026-10-11-aiciv-design.md` — section "Milestone 3 — Builders & Society" (and the Actions, Perception and fairness rules it builds on).

**Format note:** same contract style as milestone 2: exact files, interfaces, numbers, verified 26.3 API names and exact test cases; implementers write the code against these contracts with compile and test feedback. Where an algorithm is subtle, the plan gives it as code.

## Global Constraints

Everything in milestones 1 and 2's Global Constraints still holds (26.3 stack, `implementation` deps, no `net.minecraft.client`, worktree `E:\Projects cursor\Mcmods\.worktrees\aiciv` branch `aiciv`, API keys never in the repo or any log line, bots always Survival, reach and timing rules). Every commit ends with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Additionally:

- **Survival fairness.** No teleporting, no item spawning, no `setItem` on another container, no seeing or acting through walls. Blocks are used within `player.blockInteractionRange()` (4.5) and only when a ray from the eyes reaches the clicked face (`world/Sight`); entities within `player.entityInteractionRange()` (3.0) and `hasLineOfSight`. Containers, anvils, villagers, enchanting tables and brewing stands are used only through the menu a right-click opens, and only through `AbstractContainerMenu.clicked`, `clickMenuButton`, `AnvilMenu.setItemName`, `MerchantMenu.setSelectionHint/tryMoveItems`. Costs are real: XP levels, lapis, emeralds and villager prices with demand and reputation, tool durability, brew and smelt times, sleep rules.
- **Game access only on the server thread.** HTTP and LLM threads never touch the world.
- **Never break or clear a block another player or bot placed.** `PlacedBlocks` (Task 1) records every block placed through `BlockItem.place` by any player. Navigation never digs placed blocks, `mine` skips them, and `build` fails rather than clearing them. Only the build that placed its own scaffolding removes it.
- **Every new action** gets (a) a factory entry under its normalised key (lower case, `_` and `-` removed), (b) a `ToolCatalog` function whose parameter names equal the factory's argument names, (c) menu options for menu-mode models, all through its module. A missing required argument yields an `Invalid` reason that contains that argument's name in double quotes (e.g. `build needs a "name"`). The Task 1 sync test enforces all three.
- **Limits:** blueprint at most 16 × 16 × 16; build timeout `min(24000, 600 + 40 × cells)` ticks; `combat` at most 12 moves, `strafe`/`back_off` ≤ 20 ticks, `shield` ≤ 40, `bow` charge 1..30; `give`/`drop`/`withdraw` counts 1..64; `trade` times 1..12; `till`/`plant`/`harvest` counts 1..32; `fish` catches 1..10; `enchant` option 1..3; `brew` count 1..3; sign text ≤ 60 chars (4 lines of ≤ 15); item names ≤ 50 (`AnvilMenu.MAX_NAME_LENGTH`); structure names `[a-z0-9_]{1,32}`.
- **Persistence:** `<world>/aiciv/placed.json` (Task 1) and `<world>/aiciv/structures.json` (Task 2), written atomically (temp file + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`, as `Roster.save` does) on server stop and every 6000 ticks when dirty.
- **Game tests share one world.** Each task owns the game-test class Task 1 creates for it and only that class. Bot names start with the class's prefix (≤ 16 chars). Arenas larger than 8 × 8 use `padding = 24` and `GameTestKit.clearBarriers` (the milestone-2 `SurvivalGameTests` trick: the 8 × 8 box's barrier outline otherwise blocks sight and movement). Entity searches in tests are ≤ 16 blocks and the test spawns its own entities. Tests that need night use the `aiciv:night` test environment (Task 7) so they run in their own batch.
- **Parallel work rules (Tasks 2–7):** each task creates files only in its own package (`com.aiciv.<feature>`), its own test package, and its own game-test class, and edits only its own module stub. The only edits to existing shared files are those listed in the task's **Files**: Task 6 adds one entry to `aiciv.mixins.json`, Task 7 edits the `nav/` files and `testutil/GridView.java` and adds a test-environment JSON. No two of Tasks 2–7 modify the same existing file. README and spec updates happen only in Task 8.
- Run from `AiCiv/`: `./gradlew test runGametest --no-daemon`.

## Review Focus

1. **Malformed blueprints from small models** (ragged rows, a layer sent as one `"ccc/c.c/ccc"` string, unknown symbols, legend values like `"minecraft:Oak Planks"` or `"water"`, a door with `.` above it): `build` must fail at once with the exact layer/row/symbol or id at fault and place nothing. → Task 2 tests `BlueprintTest.*`, `buildFailsFastOnUnplaceableLegend`.
2. **Building where people live:** a footprint that overlaps another player's block, a chest, or any block entity must fail with the positions and the placer's name, never break them; natural dirt or grass in the footprint is cleared. → Task 2 tests `buildNeverClearsPlayerPlacedBlocks`, `buildClearsNaturalBlocksInFootprint`.
3. **Inventory full while taking items** (withdraw, trade, harvest, fishing): items are never deleted; the action stops or drops at the feet and reports what it actually got (`took 10 of 20 cobblestone (inventory full)`). → Task 3 test `withdrawStopsWhenInventoryFull`; Task 1 `MenuOps` test `carriedItemIsNeverLost`.
4. **An action is cancelled, times out, or a reflex fires while a container menu is open:** the menu is closed, the carried stack returns to the inventory, and nothing is duplicated or lost. → Task 1 test `closeReturnsCarriedStack`; Task 3 test `cancelDuringDepositClosesChest`.
5. **Targets behind walls or glass** (chest, villager, bed, button): the action fails within its approach limit with `can't see ...`, never interacts through the wall, never times out silently. → Task 1 test `blockUseRefusesThroughWall`; Task 3 test `chestBehindWallFails`.

---

## File Structure

All paths under `AiCiv/src/`. Packages `building`, `storage`, `combat`, `trade`, `farm`, `utility` are new.

```
main/java/com/aiciv/
  modules/ToolModule.java, ToolModules.java, Tools.java, Samples.java   (Task 1)
  actions/Args.java, Attachments.java, Approach.java                    (Task 1)
  actions/ActionFactory.java, ToolCatalog.java, BotContext.java,
          ActionExecutor.java, ItemGatherer.java, MineAction.java,
          AttackAction.java, PlaceAction.java                           (Task 1, modify)
  brain/OptionMenu.java                                                  (Task 1, modify)
  perception/SnapshotSection.java (new), SnapshotData.java, SnapshotFormatter.java, Perceiver.java (Task 1)
  bot/Bot.java, LiveContext.java, BotManager.java, BrainScheduler.java  (Task 1, modify)
  world/PlacementStore.java, PlacedBlocks.java, WorldFiles.java, BlockUse.java, EntityUse.java,
        MenuOps.java, Finder.java, ItemNames.java                       (Task 1)
  world/BlockPlacer.java                                                 (Task 1, modify: line of sight)
  nav/LevelBlockView.java                                                (Task 1, modify: placed blocks unbreakable)
  mixin/BlockItemPlaceMixin.java                                         (Task 1)
  AiCiv.java                                                             (Task 1, modify: lifecycle hooks)
  building/*   (Task 2)   storage/*   (Task 3)   combat/*  (Task 4)
  trade/*      (Task 5)   farm/*      (Task 6)   utility/* (Task 7)
  mixin/FishingHookAccessor.java                                         (Task 6)
  nav/BlockView.java, PathStep.java, PathSearch.java, PathFollower.java  (Task 7, doors)
  gametest/GameTestKit.java, ToolkitGameTests.java, BuildGameTests.java, StorageGameTests.java,
           CombatMoveGameTests.java, TradeGameTests.java, FarmGameTests.java, UtilityGameTests.java,
           CivGameTests.java                                             (created in Task 1; filled by owners)
resources/fabric.mod.json, aiciv.mixins.json                            (Task 1; Task 6 adds one mixin)
resources/data/aiciv/test_environment/night.json                         (Task 7)
test/java/com/aiciv/...                                                  unit tests named per task
```

Verified 26.3 APIs (javap on `minecraft-merged-deobf-26.3.jar`, 2026-10-12). **Surprises vs. older versions are marked ⚠.**
- Menus: `AbstractContainerMenu.clicked(int slot, int button, ContainerInput input, Player)` ⚠ (`ClickType` is now `net.minecraft.world.inventory.ContainerInput`: `PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL`); `clickMenuButton(Player, int)`, `getCarried()/setCarried`, `findSlot(Container, int) → OptionalInt`, `slots` (public), `quickMoveStack`. `Player.containerMenu` (public field), `Player.inventoryMenu`; `ServerPlayer.closeContainer()`, `doCloseContainer()`, `openMenu(MenuProvider) → OptionalInt`. `ChestMenu.getContainer()`, `getRowCount()`.
- Anvil: `AnvilMenu.INPUT_SLOT=0, ADDITIONAL_SLOT=1, RESULT_SLOT=2, MAX_NAME_LENGTH=50`, `setItemName(String) → boolean`, `getCost()`, `createResult()`.
- Trading: villagers moved to `net.minecraft.world.entity.npc.villager` ⚠ (`Villager`, `AbstractVillager`, `VillagerData`, `VillagerProfession`); `Villager.mobInteract(Player, InteractionHand)` refuses when not alive, trading, sleeping or baby, or when `getOffers()` is empty; `VillagerData(Holder<VillagerType>, Holder<VillagerProfession>, int)`, `withProfession(Holder)`; `VillagerProfession.FARMER/NONE/NITWIT` are `ResourceKey`s; `Villager.setVillagerData`, `AbstractVillager.overrideOffers(MerchantOffers)`, `getOffers()`, `isTrading()`. `MerchantMenu.setSelectionHint(int)`, `tryMoveItems(int)`, `getOffers()`; payment slots 0, 1 and result slot 2. `MerchantOffer(ItemCost, Optional<ItemCost>, ItemStack, int uses, int maxUses, int xp, float priceMult, int demand)`, `getCostA()` (price after modifiers), `getCostB()`, `getResult()`, `isOutOfStock()`, `getUses()`, `getMaxUses()`; `ItemCost(ItemLike, int)`. Villager trades are data-driven (`data/minecraft/villager_trade/...`) ⚠.
- Entities: `Player.interactOn(Entity, InteractionHand, Vec3 hitRelativeToEntity)` ⚠ (three arguments; the client sends the hit location relative to the entity's position). `Player.drop(ItemStack, boolean, Prediction)` returns the `ItemEntity` (existing `InventoryOps.addOrDrop` passes `false, Prediction.SERVER_ONLY`). `NameTagItem.interactLivingEntity(...)` uses the stack's `DataComponents.CUSTOM_NAME`.
- Animals: `Animal.isFood(ItemStack)` (abstract), `canFallInLove()`, `isInLove()`, `setInLove(Player)`, `AgeableMob.isBaby()`, `getAge()`; feeding happens in `Animal.mobInteract`.
- Fishing: `Player.fishing` (public `FishingHook`); `FishingHook` keeps `nibble`, `biting`, `timeUntilHooked` **private** ⚠ → Task 6 adds an `@Accessor` mixin; `retrieve(ItemStack) → int`, `getHookedIn()`. Casting and reeling are both `gameMode.useItem` with the rod.
- Farming: there is **no `HoeItem`** ⚠: tilling is data-driven through the `BLOCK_TRANSFORMERS` item component (`data/minecraft/block_transformer/hoe.json`: blocks in `#turns_into_farmland` with air above → farmland), so a bot tills by `gameMode.useItemOn` with a hoe (`ItemTags.HOES`) on the top face. `CropBlock.getAge(BlockState)`, `getMaxAge()`, `isMaxAge(BlockState)`, `CropBlock.AGE`; `FarmlandBlock.MOISTURE`; tags `BlockTags.CROPS`, `TURNS_INTO_FARMLAND`, `SUPPORTS_CROPS`.
- Beds: `Player.startSleepInBed(AbstractBedBlock, BlockState, BedRule, BlockPos) → Either<BedSleepingProblem, Unit>` ⚠ (bed rules are environment attributes: `net.minecraft.world.attribute.BedRule` with `canSleep(Level)`); `BedSleepingProblem` is a record (`TOO_FAR_AWAY, OBSTRUCTED, OTHER_PROBLEM, NOT_SAFE`, `message()`); `AbstractBedBlock.getBedRule(Level, BlockPos)`, `PART`, `OCCUPIED`; `isSleeping()`, `stopSleepInBed(boolean, boolean)`, `isSleepingLongEnough()`. Right-clicking the bed with `gameMode.useItemOn` runs the vanilla checks.
- Time: no `setDayTime` ⚠; clocks are `ServerClockManager` (`level.clockManager()`, `setTotalTicks(Holder<WorldClock>, long)`), `Level.isDarkOutside()`, `getOverworldClockTime()`. Game tests get night from a test environment: `@GameTest(environment = "aiciv:night")` with a datapack `test_environment` of type `minecraft:clock_time` (fields `clock`, `time`).
- Enchanting: `EnchantmentMenu.costs`, `enchantClue`, `levelClue` (public `int[3]`), `clickMenuButton(Player, id)` consumes `id+1` lapis and `id+1` levels; slot 0 item, slot 1 lapis. `EnchantmentHelper.hasAnyEnchantments(ItemStack)`.
- Brewing: brewing is **recipe based** ⚠: `RecipeType.BREWING`, `BrewingRecipe(PotionIngredient input, PotionIngredient reagent, ItemStackTemplate output)`, `BrewingInput(ItemStack, ItemStack)`. `BrewingStandBlockEntity` slots 0–2 bottles, 3 ingredient, 4 fuel (fuel = items with `DataComponents.BREWING_FUEL`), `BREWING_TIME_SECONDS = 20`; `BrewingStandMenu.getBrewingTicks()`, `getFuel()`. `Potions.WATER/AWKWARD/SWIFTNESS/STRENGTH/HEALING/FIRE_RESISTANCE/NIGHT_VISION` are `Holder<Potion>`; `PotionContents.is(Holder<Potion>)`, `PotionContents.createItemStack(Item, Holder<Potion>)`.
- Signs: `SignBlockEntity.updateSignText(Player, SignTextSlot, List<FilteredText>)` (vanilla's packet path; checks the editing player and wax), `getText(SignTextSlot)`, `SignTextSlot.FRONT/BACK` ⚠ (replaces the `boolean front` flag), `FilteredText.passThrough(String)`, `SignText.LINES` (4), `getMessages(boolean filtered)`.
- Doors and switches: `DoorBlock.isWoodenDoor(BlockState)`, `OPEN`, `HALF`; `FenceGateBlock.OPEN`; `ButtonBlock.POWERED`, `LeverBlock.POWERED`; tags `BlockTags.WOODEN_DOORS`, `FENCE_GATES`, `WOODEN_TRAPDOORS`, `BUTTONS`.
- Combat: `LivingEntity.startUsingItem(InteractionHand)`, `releaseUsingItem()`, `stopUsingItem()`, `isUsingItem()`, `isBlocking()`, `getTicksUsingItem()`, `jumpFromGround()`; `ServerPlayer.gameMode.useItem(ServerPlayer, Level, ItemStack, InteractionHand)`; movement inputs `xxa` (positive = left) and `zza` (positive = forward).
- Block placing hook: `BlockItem.place(BlockPlaceContext)` calls `Block.setPlacedBy(Level, BlockPos, BlockState, LivingEntity, ItemStack)` once the block is in place (doors and beds place their second half there).
- Tags: `ItemTags.HOES, SWORDS, AXES, ARROWS, BEDS, SIGNS, ANVIL, VILLAGER_PLANTABLE_SEEDS`; `BlockTags.BEDS, ANVIL, ALL_SIGNS`.

---

### Task 1: Module registry and shared world toolkit

Two commits (Part A, then Part B). After this task merges, Tasks 2–7 start in parallel.

#### Part A — registry, sections, context

**Files:**
- Create `modules/ToolModule.java`, `modules/ToolModules.java`, `modules/Tools.java`, `modules/Samples.java`, `actions/Args.java`, `actions/Attachments.java`, `perception/SnapshotSection.java`.
- Create stub modules (each `name()` plus empty `tools()`, `create` returning `null`): `building/BuildingModule.java` (`"building"`), `storage/StorageModule.java` (`"storage"`), `combat/CombatModule.java` (`"combat"`), `trade/TradeModule.java` (`"trade"`), `farm/FarmModule.java` (`"farm"`), `utility/UtilityModule.java` (`"utility"`).
- Create game-test classes `gametest/GameTestKit.java`, `ToolkitGameTests.java`, `BuildGameTests.java`, `StorageGameTests.java`, `CombatMoveGameTests.java`, `TradeGameTests.java`, `FarmGameTests.java`, `UtilityGameTests.java`, `CivGameTests.java`; add all eight test classes to `fabric.mod.json`'s `fabric-gametest` list.
- Modify `actions/ActionFactory.java`, `actions/ToolCatalog.java`, `actions/BotContext.java`, `actions/ActionExecutor.java` (`reflexOwned` forwards the new methods), `brain/OptionMenu.java`, `perception/SnapshotData.java`, `perception/SnapshotFormatter.java`, `perception/Perceiver.java`, `bot/Bot.java`, `bot/LiveContext.java`, `bot/BotManager.java` (`stamp()` package-private if it is private), `bot/BrainScheduler.java` (pass `b.attachments` to `capture`), `AiCiv.java`, `test/.../testutil/FakeContext.java`.
- Tests: `test/java/com/aiciv/modules/ToolModulesTest.java`, `test/java/com/aiciv/modules/ModuleSyncTest.java`, update `actions/ActionFactoryTest.java`, `perception/SnapshotFormatterTest.java`, `brain/OptionMenuTest.java`.

**Interfaces (produces):**

```java
package com.aiciv.modules;
public interface ToolModule {
    String name();
    /** OpenAI function objects ({"type":"function","function":{...}}), built with Tools.fn. */
    List<JsonObject> tools();
    /** key = call name lower-cased with '_' and '-' removed. Null when the key is not this module's. */
    ActionFactory.Result create(String key, JsonObject args);
    default List<String> guideLines() { return List.of(); }
    /** Pure: may read only snap and ctx (no world access), so it is unit testable. */
    default List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) { return List.of(); }
    /** Fixtures for ModuleSyncTest: a situation in which this module offers at least one option. */
    default SnapshotData sampleSnapshot() { return Samples.snapshot(List.of(), List.of()); }
    default OptionMenu.MenuContext sampleContext() { return Samples.context(Map.of()); }
    /** Server thread. Extra snapshot lines (e.g. "Structures: ..."). */
    default List<SnapshotSection> sections(AiPlayer bot, Attachments memory) { return List.of(); }
    default void serverStarted(MinecraftServer server) {}
    default void autosave(MinecraftServer server) {}
    default void serverStopping(MinecraftServer server) {}
}
```

- `ToolModules` (final, static): `public static final List<ToolModule> ALL = List.of(new BuildingModule(), new StorageModule(), new CombatModule(), new TradeModule(), new FarmModule(), new UtilityModule());` — **this list is never edited by Tasks 2–7**; they fill their stub instead. Methods: `static String normalise(String name)` (same rule as the factory); `static List<JsonObject> tools()`; `static Set<String> keys()`; `static ActionFactory.Result create(String key, JsonObject args)` → `createFrom(ALL, key, args)`; package-private `static ActionFactory.Result createFrom(List<ToolModule>, String key, JsonObject args)` (first non-null, else null); `static List<String> guideLines()`; `static List<MenuOption> menuOptions(SnapshotData, MenuContext)`; `static List<SnapshotSection> sections(AiPlayer, Attachments)`; `static void serverStarted/autosave/serverStopping(MinecraftServer)`; `static void checkUnique(List<ToolModule> modules, Set<String> coreKeys)` throws `IllegalStateException("tool '<name>' from <module> clashes with <other>")`, called from a static initialiser. Every per-module call in `menuOptions`, `sections` and the lifecycle hooks is wrapped in `try/catch (RuntimeException)`: a failing module contributes nothing and is logged once per module name (`AiCiv.LOG.warn`), so one broken module never breaks a snapshot or menu.
- `Tools` (public static): `JsonObject fn(String name, String description, JsonObject properties, String... required)`, `JsonObject props(String name, String type, String description)`, `JsonObject prop(String type, String description)`, `JsonObject stringArray(String description)`, `JsonObject arrayOfStringArrays(String description)`, `JsonObject stringMap(String description)` (`{"type":"object","additionalProperties":{"type":"string"}}`), `JsonObject objectArray(JsonObject itemProperties, String description)`. `ToolCatalog` now uses these (move its private helpers here).
- `Samples` (public static, pure): `SnapshotData snapshot(List<Sighting> blocks, List<SnapshotSection> sections)`, `snapshot(List<Sighting> blocks, List<Sighting> mobs, List<SnapshotSection> sections)`, `OptionMenu.MenuContext context(Map<String,Integer> inventory)`, `context(Map<String,Integer> inventory, List<String> hostileMobs)`.
- `Args` (public static, in `actions`): `String str(JsonObject, String)`, `Integer integer(JsonObject, String)` (moved from the factory; the factory delegates), `int intIn(JsonObject a, String key, int def, int min, int max)` (clamped), `Optional<BlockPos> optionalPos(JsonObject a)` (empty when x, y and z are all absent), `String posProblem(String action, JsonObject a)` → null, or `"<action> needs all of x, y and z, or none"`, or the existing y/world-border messages.
- `Attachments` (per bot, server thread): `public <T> T get(Class<T> type, Supplier<T> create)`, `public <T> Optional<T> find(Class<T> type)`. `Bot` gains `public final Attachments attachments = new Attachments();` (survives respawn, like `knownBlocks`).
- `record SnapshotSection(String label, List<String> items, String emptyWord, List<String> tags)` — `tags` are machine-readable tokens for menus (e.g. `"structure:ada_hut"`, `"crop_ripe"`); when `items` is empty and `emptyWord` is null the line is omitted.
- `SnapshotData` gains two trailing components `int xpLevel, List<SnapshotSection> sections`, plus a compatibility constructor with the old 19 arguments (`xpLevel 0`, `sections List.of()`), so existing tests compile unchanged.
- `SnapshotFormatter`: first line `... | hp %d/%d food %d/20 xp %d | day ...`; after `Remembered:` one line per section `Label: items` (or `Label: emptyWord`); truncation treats each section's items as one more list.
- `Perceiver.capture(AiPlayer, String, String, Predicate<ServerPlayer>, KnownBlocks, Attachments)`; the 5-argument overload passes `new Attachments()`. Fills `xpLevel = bot.experienceLevel` and `sections = ToolModules.sections(bot, attachments)`.
- `BotContext` adds `default void event(String text) {}` (adds a line to this bot's event log without waking it), `default void tell(String botName, String text) {}` (adds an event line to another bot, if it is an online bot), `default Attachments attachments() { return null; }`. `LiveContext`: `event` → `bot.brain.note(manager.stamp(), text)`; `tell` → `manager.bot(PlayerNames.clean(botName)).ifPresent(o -> o.brain.note(manager.stamp(), text))`; `attachments` → `bot.attachments`. `FakeContext` returns a real `Attachments` field and records `event` lines in a public list. `ActionExecutor.reflexOwned` forwards all three.
- `ActionFactory`: `public static String available()` = the core list + `", "` + module tool names in catalog order; `static Set<String> knownKeys()` = core keys ∪ `ToolModules.keys()`. Flow: normalise → unknown (not core and not a module key) → `Invalid("unknown action '<name>'. Available: " + available())`; `argsError` → invalid; core key → existing switch; else `ToolModules.create(key, args)`.
- `ToolCatalog.tools()` appends `ToolModules.tools()`; `guide()` starts `Actions: <available()>. Each one ends as done or failed...` and appends every `ToolModules.guideLines()` line.
- `OptionMenu.build`: core situational options are capped at `CORE_CAP = 20`, then module options are added in module order (ids re-validated against `ID`, duplicates dropped), then the list is trimmed to `MAX_OPTIONS - fixed.size()`; fixed options (`explore_*`, `say_*`, `wait`) stay last as today.
- `AiCiv`: `SERVER_STARTED` → after `start(s)`, `ToolModules.serverStarted(s)`; `SERVER_STOPPING` → `ToolModules.serverStopping(s)` before `stop()`; `END_SERVER_TICK` → every 6000 ticks (`s.getTickCount() % 6000 == 0`) `ToolModules.autosave(s)`.
- `GameTestKit` (package `gametest`, public static): `void clearBarriers(GameTestHelper, int size)` (relative `-1..size` × `0..8` × `-1..size`), `void floor(GameTestHelper, int size, Block)`, `record Spawned(BotManager manager, Bot bot)`, `Spawned spawn(GameTestHelper, String name, double x, double z)` (scripted manager with an empty script, bot at relative `(x, 1, z)`, `onEachTick(manager::tickStart)`), `Spawned spawnScripted(GameTestHelper, String name, double x, double z, ScriptedBackend)` (ticks `tickStart` and `tickEnd`), `void remove(Spawned)`, `String events(Bot)`, `JsonObject turn(String... nameAndArgs)` (moved from `SurvivalGameTests`, which then uses it), `List<String> decisionLines(Path dir, String bot)`.
- Each new game-test class contains one test, `moduleIsRegistered` (e.g. `BuildGameTests`: `helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("building")), ...)`, then `helper.succeed()`); `CivGameTests` checks all six names. Owners add their tests next to it.

**Tests (Part A):**
- `ToolModulesTest`: `createFrom` returns the first module's non-null result and null when no module claims the key (two tiny in-test modules); `checkUnique` throws when two modules both offer `"place_chest"` and when a module offers the core name `"place"`, and the message names both; a module whose `menuOptions` throws contributes nothing while another module's options still appear (`ToolModules` exposes package-private `menuOptionsFrom(List<ToolModule>, SnapshotData, MenuContext)` for this).
- `ModuleSyncTest` (the catalog ↔ factory ↔ menu contract for every module, so later tasks are checked automatically):
  1. every catalog function name is accepted by `ActionFactory` (the reason never contains `unknown action`) and has a non-blank description;
  2. `ActionFactory.knownKeys()` equals the set of normalised catalog names;
  3. for every **module** function with `required` parameters, `create(name, {})` is `Invalid` and its reason contains `"<first required name>"` in double quotes (core milestone 1–2 messages are left as they are);
  4. for every module menu option, every argument name in each of its `ToolCall`s is a property of that tool in the catalog (menu ↔ catalog);
  5. for every module, `menuOptions(sampleSnapshot(), sampleContext())` is non-empty once the module has tools (skipped for empty stubs), every option id matches `OptionMenu.ID`, and every `ToolCall` in every option builds `ActionFactory.Result.Ok`.
- `ActionFactoryTest.catalogNamesMatchTheFactory`: replace the hard-coded `14` with `ToolCatalog.tools().size() == 14 + ToolModules.tools().size()` and compare against `available()`.
- `SnapshotFormatterTest`: first line contains `xp 3`; a section `("Structures", ["hut by Ada at 1 2 3 (4m N)"], "none", [])` renders `Structures: hut by Ada at 1 2 3 (4m N)`; an empty section with `emptyWord` null renders no line; truncation drops section items before the line exceeds `MAX_CHARS` (a section with 500 long items ends `+N more`).
- `OptionMenuTest`: with 30 core options (30 players) and a module offering 10 options (in-test module via `OptionMenu.build(snap, ctx, List<ToolModule>)`, a package-private overload), the menu has ≤ 40 options, contains all 10 module options and all fixed options.

- [ ] Write the failing tests; run `./gradlew test --no-daemon` and see them fail.
- [ ] Implement Part A; `./gradlew test runGametest --no-daemon` passes (all milestone 1–2 tests too).
- [ ] Commit `"Add tool modules, snapshot sections and per-bot attachments"` with the trailer.

#### Part B — world toolkit

**Files:**
- Create `world/PlacementStore.java` (pure), `world/PlacedBlocks.java`, `world/WorldFiles.java`, `mixin/BlockItemPlaceMixin.java`, `world/BlockUse.java`, `world/EntityUse.java`, `world/MenuOps.java`, `world/Finder.java`, `world/ItemNames.java`, `actions/Approach.java`.
- Modify `resources/aiciv.mixins.json` (add `"BlockItemPlaceMixin"`), `world/BlockPlacer.java` (line of sight, new result `NOT_VISIBLE`), `actions/PlaceAction.java` (map `NOT_VISIBLE` like `OUT_OF_REACH`: move closer once, then fail `can't see a face to place against at x y z`), `nav/LevelBlockView.java`, `actions/MineAction.java`, `actions/ItemGatherer.java` (make the class, constructor and `tick` public), `actions/AttackAction.java` (make `entityType(String)` and `weaponScore(ItemStack)` public and add `public static LivingEntity findTarget(AiPlayer me, String label, double radius)` holding the body of the private `resolve`, which then calls it — used by Tasks 3, 4 and 6), `perception/Perceiver.java` (add `exposedStates`), `AiCiv.java` (PlacedBlocks lifecycle).
- Tests: `test/java/com/aiciv/world/PlacementStoreTest.java`, `world/ItemNamesTest.java`; game tests in `ToolkitGameTests`.

**Interfaces (produces):**
- `PlacementStore` (pure): `record Placement(String block, String placer, long tick)`; `PlacementStore(int capacity)`; `void put(String dimension, long pos, Placement p)` (re-put refreshes; beyond capacity the oldest insertion is evicted); `Optional<Placement> get(String dimension, long pos)`; `void remove(String dimension, long pos)`; `List<Long> placedBy(String dimension, String placer, long sinceTick)`; `int size()`; `boolean dirty()`; `void markClean()`; `String toJson()`; `static PlacementStore fromJson(String json, int capacity)` (format `{"<dimension>": {"<pos long>": ["block", "placer", tick], ...}}`).
- `PlacedBlocks`: `static final int CAPACITY = 250_000`; `static PlacedBlocks of(MinecraftServer server)` (one instance per running server, loaded from `WorldFiles.file(server, "placed.json")` on first use); `void record(ServerLevel level, BlockPos pos, String placer)` (stores the block id now at `pos`; also the other half: a state with `BlockStateProperties.DOUBLE_BLOCK_HALF` records the cell above or below, a bed records `pos.relative(state.getValue(FACING))` for the foot or the opposite for the head); `Optional<PlacementStore.Placement> at(ServerLevel level, BlockPos pos)` (present only while the block there still has the recorded id; a stale entry is removed); `boolean isPlaced(ServerLevel, BlockPos)`; `List<BlockPos> placedSince(ServerLevel level, String placer, long tick)`; `void save()` (if dirty, atomic write); `static void unload(MinecraftServer server)` (save and forget the instance). `AiCiv` calls `PlacedBlocks.of(s)` on start, `save()` in the 6000-tick autosave, and `unload(s)` on stop.
- `WorldFiles.file(MinecraftServer server, String name)` → `server.getWorldPath(LevelResource.ROOT).resolve("aiciv").resolve(name)`; `WorldFiles.writeAtomically(Path, String)`.
- Mixin (helpers stay out of the mixin package — the Xenogenesis gotcha: a helper class in the mixin package throws `IllegalClassLoadError` at boot):

```java
@Mixin(BlockItem.class)
abstract class BlockItemPlaceMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;setPlacedBy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V"))
    private void aiciv$recordPlacement(Block block, Level level, BlockPos pos, BlockState state, LivingEntity placer,
                                       ItemStack stack, Operation<Void> original) {
        original.call(block, level, pos, state, placer, stack);
        if (level instanceof ServerLevel server && placer instanceof Player player) {
            PlacedBlocks.of(server.getServer()).record(server, pos, player.getScoreboardName());
        }
    }
}
```
- `LevelBlockView.breakSeconds`: `+∞` when `PlacedBlocks.of(level.getServer()).isPlaced(level, cursor)` (checked before the per-state cache). `MineAction.candidates` and `diggable` skip placed positions.
- `BlockPlacer.place`: among faces with a non-replaceable neighbour (same order as today), use the first whose hit point a ray from the eyes reaches (`ClipContext.Block.OUTLINE`, the hit block must be the neighbour); none visible → `NOT_VISIBLE`. Reach check unchanged.
- `BlockUse` (`enum Result { USED, OUT_OF_REACH, NOT_VISIBLE, NO_EFFECT }`): `Result click(AiPlayer p, BlockPos pos)` — picks the face whose centre (0.45 inside the face, as `Sight` does) is nearest the eyes among faces a ray reaches; not sneaking; `lookAt`; `gameMode.useItemOn(p, level, p.getMainHandItem(), MAIN_HAND, hit)`; `USED` iff the result `consumesAction()`. `Result clickFace(AiPlayer p, BlockPos pos, Direction face)` — only that face (tilling, planting). `boolean withFreeHand(AiPlayer p)` — selects an empty hotbar slot, else a hotbar slot holding a non-`BlockItem`, so a click uses the block instead of placing; false if neither exists.
- `EntityUse.interact(AiPlayer p, Entity target, InteractionHand hand)` → `BlockUse.Result`: needs `p.isWithinEntityInteractionRange(target, 0.0)` and `p.hasLineOfSight(target)`; `lookAt(EYES, target.getEyePosition())`; `p.interactOn(target, hand, target.getBoundingBox().getCenter().subtract(target.position()))`.
- `MenuOps`: `<M extends AbstractContainerMenu> M open(AiPlayer p, BlockPos pos, Class<M> type)` (`BlockUse.click`; returns `p.containerMenu` if it is a `type`, else closes whatever opened and returns null); `List<Slot> containerSlots(AbstractContainerMenu menu)` (slots whose container is not the player's inventory); `OptionalInt inventorySlot(AbstractContainerMenu menu, AiPlayer p, int inventoryIndex)` (`menu.findSlot(p.getInventory(), i)`); `int moveIn(AiPlayer p, AbstractContainerMenu menu, Predicate<ItemStack> match, int max)` (shift-click whole matching inventory stacks while `remaining >= stack size`; for a partial stack: `PICKUP` primary on the source, `PICKUP` secondary on the first container slot that accepts it once per item, then `PICKUP` primary back on the source; returns the number moved; stops when nothing moved); `int moveOut(AiPlayer p, AbstractContainerMenu menu, int slot, int max)` (same idea in the other direction; stops when the inventory has no room); `void close(AiPlayer p)` (if `getCarried()` is non-empty, `InventoryOps.addOrDrop` it and `setCarried(EMPTY)` first; then `p.closeContainer()` when `p.containerMenu != p.inventoryMenu`).
- `Finder`: `Optional<BlockPos> nearestBlock(BotContext ctx, Predicate<String> blockId, int radius)` (visible via `Perceiver.exposedBlocks` from the bot's eyes, or a `KnownBlocks` entry within `radius` whose block still matches; nearest first); `List<BlockPos> visibleStates(AiPlayer p, Predicate<BlockState> match, int radius)` (via new `Perceiver.exposedStates(ServerLevel, BlockPos origin, Vec3 eye, int radius, Predicate<BlockState>)`, nearest first); `<T extends Entity> Optional<T> nearestEntity(AiPlayer p, Class<T> type, Predicate<T> match, double radius)`.
- `ItemNames`: `Item item(String query)` (exact registry item for `BlockNames.normalise(query)`, else null; `"air"` → null); `Predicate<ItemStack> matcher(String query)` (exact id, else `BlockNames.matcher` over item ids); `String id(ItemStack)`; `List<String> suggestions(String query, int n)` (item ids with the smallest Levenshtein distance; ties alphabetical) used for `no item called "x" (did you mean ...)`.
- `Approach` (`enum State { RUNNING, ARRIVED, FAILED }`, `record Status(State state, String reason)`): `static Approach block(BlockPos target, double radius)`, `static Approach entity(Entity target, double radius)`; `Status tick(BotContext ctx)`; `void cancel(BotContext ctx)`. Arrived when the eyes are within `radius` of the target centre **and** it is visible (`Sight.canSee` / `hasLineOfSight`); within radius but not visible → one retry with radius 1.5, then `FAILED "can't see <what> at x y z from anywhere near"`; navigation `FAILED` three times → `FAILED` with the navigator's reason; for entities re-plan when the target moved > 2 blocks or the navigator has been idle 10 ticks; `FAILED "<what> is gone"` when the entity is removed. Stops the navigator on arrival.

**Tests (Part B):**
- `PlacementStoreTest`: capacity 3 evicts the oldest; `get` after `remove` is empty; `placedBy` filters by dimension, placer and tick; `toJson`/`fromJson` round trip keeps all entries; `dirty()` true after `put`, false after `markClean()`.
- `ItemNamesTest` cannot touch registries; test the pure `ItemNames.levenshtein(String, String)` (`kitten/sitting → 3`, `"" → length`) and `ItemNames.rank(String query, List<String> ids, int n)` ordering.
- Game `ToolkitGameTests`:
  - `placementIsRecordedAndForgotten`: bot places dirt with `BlockPlacer.place` → `PlacedBlocks.of(server).at(level, pos)` has `placer == "TestKitPlace"`; `level.destroyBlock(pos, false)` → `at` empty.
  - `doorRecordsBothHalves`: bot places an `oak_door` → both halves `isPlaced`.
  - `navigationNeverDigsPlacedBlocks`: a 2-high dirt wall across an 8-wide corridor recorded with `PlacedBlocks.record(..., "Steve")`; bot with a stone shovel, `goTo` the far side → `FAILED` within 400 ticks; the wall is intact. The same wall made of unrecorded dirt → ARRIVED (control).
  - `mineSkipsPlacedBlocks`: one recorded cobblestone and one natural stone visible → `MineAction("stone", 1)` mines the natural one; the recorded block remains.
  - `blockUseRefusesThroughWall` (Review Focus 5): lever behind a full glass wall within 3 blocks → `BlockUse.click` returns `NOT_VISIBLE`, lever unpowered; with the glass removed → `USED`, powered.
  - `placerRefusesThroughWall`: target cell whose only solid neighbour is on the far side of a wall from the bot → `NOT_VISIBLE`.
  - `menuOpsMovesPartialStacks`: chest; bot holds 64 cobblestone → `moveIn(..., cobblestone, 10)` → chest 10, bot 54; `moveOut(slot, 4)` → chest 6, bot 58.
  - `closeReturnsCarriedStack` (Review Focus 4): open a chest, `menu.clicked(invSlot, 0, PICKUP, p)` so a stack is carried, `MenuOps.close` → `p.containerMenu == p.inventoryMenu`, carried empty, the inventory count unchanged.
  - `approachFailsWhenTargetHidden`: chest sealed in stone (one face exposed toward a 1-wide pit the bot cannot see into) → `Approach.block` returns FAILED containing `can't see` within 300 ticks.

- [ ] Write the failing tests; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Remember placed blocks and add the shared world toolkit"` with the trailer.

---

### Task 2: Building — blueprints, templates, named structures

**Files:** Create in `main/java/com/aiciv/building/`: `Blueprint.java`, `BlueprintException.java`, `Facing.java`, `BuildPlan.java`, `Templates.java`, `Structure.java`, `StructureStore.java` (pure), `StructureRegistry.java`, `BuildAction.java`; fill `BuildingModule.java`. Tests: `test/java/com/aiciv/building/BlueprintTest.java`, `BuildPlanTest.java`, `TemplatesTest.java`, `StructureStoreTest.java`, `BuildingModuleTest.java`; game tests in `gametest/BuildGameTests.java` (prefix `TestBuild`).

**Interfaces:**
- `record Blueprint(int width, int depth, int height, char[][][] cells, Map<Character, String> legend, int yOffset)` — `cells[y][row][col]`, `width` = longest row, `depth` = rows per layer, `height` = layers. Symbols: `'.'` must be air, `' '` leave as is, other chars must be legend keys.
  - `static Blueprint parse(JsonElement layers, JsonElement legend)` throws `BlueprintException` (message is the model-facing reason): `layers` is an array (bottom → top) whose elements are either an array of row strings or one string with rows separated by `/` or `\n`; rows shorter than the longest are padded with `' '`; legend is an object of single-character keys (`.` and space are rejected) to block ids (normalised with `BlockNames.normalise`, `minecraft:` stripped). Errors, verbatim prefixes: `layers must be a non-empty list`, `blueprint is <w>x<d>x<h>; the limit is 16x16x16`, `unknown symbol 'x' in layer 2 row 3 (add it to the legend)`, `legend key 'ab' must be one character`, `the blueprint places nothing`.
- `enum Facing { NORTH, EAST, SOUTH, WEST }` with `static Facing parse(String)` (`n|north|N` …, null on bad input), `int forwardX(), forwardZ(), rightX(), rightZ()`: south `f=(0,+1) r=(-1,0)`, north `f=(0,-1) r=(+1,0)`, east `f=(+1,0) r=(0,+1)`, west `f=(-1,0) r=(0,-1)`.
- `BuildPlan` (pure): `static BuildPlan of(Blueprint bp, Pos origin, Facing facing)`; world cell for `(col, row, y)` = `origin + col·right + row·forward + (y + yOffset)·up` (row 0 is nearest the builder, columns run left → right as the builder sees them). Fields/methods: `List<Cell> places()` with `record Cell(Pos pos, String block)` ordered bottom-up then by `row`, then `col`; `List<Pos> mustBeAir()`; `Pos min(), max()` (bounding box); `Map<String,Integer> materials(Function<Pos,String> blockAt)` (needed items per block id, excluding cells that already hold that block; a door or bed counts once per item); `List<Cell> floating(Predicate<Pos> solidInWorld)` (planned cells not connected to a solid world block: BFS that starts at planned cells with a solid world neighbour and moves through 6-neighbour planned cells; whatever the BFS never reaches floats); `static String doorProblem(Blueprint bp)` (a legend block ending in `_door` needs `' '` or the same symbol directly above, else `door at layer L row R col C needs ' ' above it`).
- `Templates` (pure): `static Blueprint make(String template, int size, String material)` throws `BlueprintException`; `static int defaultSize(String template)`; `static final List<String> NAMES = List.of("hut", "tower", "wall", "bridge", "farm_plot")`. Sizes are clamped. Exact shapes (front row 0 faces the builder, `m` = material):
  - `hut` size 5..9 (default 5): layers 0–2 are the `s × s` perimeter of `m` with `.` inside; door gap `.` at row 0, col `s/2`, layers 0 and 1; layer 3 is a full `s × s` roof. Count `3(4s−4) − 2 + s²` (s=5 → **71**).
  - `tower` size 3..7 (default 4): height `h = min(16, 2s + 2)`; layers 0..h−2 perimeter with `.` inside and the same door gap; top layer: perimeter cells with `(row + col) % 2 == 0`. s=3 → **58**.
  - `wall` size 4..16 (default 8): one row, length `s`, height 3 → `3s` (s=8 → **24**).
  - `bridge` size 4..16 (default 6): `yOffset −1`; layer 0 is 3 wide × `s` rows of `m`; layer 1 has `m` at cols 0 and 2 on every row → `5s` (s=4 → **20**).
  - `farm_plot` size 3..9 (default 5): one layer, `(s+2) × (s+2)` perimeter of `m`, interior `' '` → `4s + 4` (s=5 → **24**).
- `record Structure(String name, String owner, String dimension, Pos origin, Pos min, Pos max, String kind, long builtTick)`.
- `StructureStore` (pure): `Optional<Structure> get(String name)` (case-insensitive), `boolean add(Structure)` (false if the name exists), `List<Structure> nearest(String dimension, Pos from, int n)`, `String toJson()`, `static StructureStore fromJson(String)`, `boolean dirty()`, `void markClean()`.
- `StructureRegistry`: `static StructureRegistry of(MinecraftServer)` (loads `structures.json` via `WorldFiles`), `StructureStore store()`, `void save()`, `static void unload(MinecraftServer)`. `BuildingModule` wires `serverStarted/autosave/serverStopping` to these.
- `BuildAction(String name, Blueprint bp, BlockPos originOrNull, Facing facingOrNull, String kind)`; `describe()` = `build <name>`; timeout `min(24000, 600 + 40 × cells)` ticks.

**Tools (BuildingModule):**
- `build` — params `name` (string, required), `layers` (array of arrays of strings, or of strings with `/` between rows; required), `legend` (object symbol → block id; required), `x`, `y`, `z` (integers, optional, the corner cell of row 0, column 0, layer 0), `facing` (N/E/S/W, optional, default the bot's facing). Description: `Build a structure you design: layers from bottom to top, each a list of rows (row 0 nearest you, columns left to right); legend maps symbols to blocks; '.' must be air, ' ' is left alone. Max 16x16x16. You must carry every block. Without x/y/z it is built 2 blocks in front of you, centred.`
- `build_template` — `template` (one of `NAMES`, required), `material` (block id, required), `name` (required), `size` (integer, optional), `x`, `y`, `z`, `facing`. Description lists the five templates with their default block counts.
- Validation (`Invalid` reasons): `build needs a "name"`, `build needs "layers"`, `build needs a "legend"`, names normalised (lower case, spaces → `_`) and checked against `[a-z0-9_]{1,32}` (`"name" may use only a-z, 0-9 and _`), `Args.posProblem`, `facing must be N, E, S or W`, any `BlueprintException` message, `Templates` unknown → `template must be one of hut, tower, wall, bridge, farm_plot`.
- Guide line: `Build: design with build (layers + legend) or use build_template; finished builds are listed under Structures for everyone.`

**BuildAction algorithm** (one placement or break at most every 4 ticks so it is watchable):
1. *Start (first tick, fail fast; nothing is changed if any check fails):* origin = given cell, or `feet + 2·forward − (width/2)·right`; every cell within 48 blocks of the bot, else `too far: walk within 48 blocks first`; every legend id resolves to a block whose `asItem() != Items.AIR`, else `<id> can't be placed by hand` (with `ItemNames.suggestions` when the id is unknown); `doorProblem`; for each `mustBeAir` cell and each place cell holding a different non-replaceable block: placed by anyone (`PlacedBlocks.at`) → collect `x y z (<block> placed by <placer>)`; block entity, unbreakable (`hardness < 0`) or fluid source other than water → collect `x y z (<block>)`; if any collected → `FAILED "can't build here: <first 3>; pick another spot"`. `floating` non-empty → `cell x y z would float; give it support in the blueprint`. Name already in the registry → `a structure named <name> exists`. `materials` minus inventory → missing non-empty → `FAILED "missing materials: 13 cobblestone, 1 oak_door"` (ids in blueprint order).
2. *Clear:* natural blocks in `mustBeAir` cells and in place cells holding a different block, top-down, each with `ctx.breaker()` after `Approach.block(cell, 4.0)`.
3. *Place:* next cell = first in `places()` order whose layer is ≤ the lowest unfinished layer and that has a solid neighbour now; if the bot's feet or head occupy it, walk to `NearGoal` 2 blocks outside the bounding box on the bot's side; `Approach.block(cell, 4.0)` (the navigator pillars with throwaways when the cell is high); `BlockPlacer.place(p, cell, s -> s.is(item))`; `NOT_VISIBLE`/`OUT_OF_REACH` → re-approach with radius 2.5, a second failure on the same cell → `FAILED "could not reach x y z"`; `NO_ITEM` → `FAILED "ran out of <block>"`.
4. *Scaffold removal:* `PlacedBlocks.placedSince(level, botName, startTick)` within the bounding box inflated by 4, minus plan cells, only throwaway blocks → break top-down (standing on one: break the block below the feet; a fall of one block is fine).
5. *Finish:* register `Structure`, `ctx.event("built <name>: <cells> blocks at x y z")`, DONE.
- `cancel`/`interrupted`: stop the breaker and navigator; partial builds stay (no registry entry).

**Snapshot section** (`BuildingModule.sections`): label `Structures`, nearest 8 in the bot's dimension, items `"<name> by <owner> at x y z (<d>m <dir>)"` (`Compass.direction`), emptyWord `"none known"`, tags `structure:<name>`.

**Menu options:** for the held material with the most units among items ending in `_planks` or in `{cobblestone, stone, stone_bricks, bricks, cobbled_deepslate, mud_bricks}`: `build_hut` if count ≥ 71 → `build_template{template: hut, size: 5, material, name: "hut_<x>_<z>"}` (negative numbers written `m12`, from `snap.x()/z()`); `build_wall` if count ≥ 24 → wall size 8, name `wall_<x>_<z>`. `sampleContext()` holds 80 `oak_planks`.

**Tests:**
- `BlueprintTest`: ragged rows pad with spaces (`["ab","a"]` → width 2); `"ccc/c.c/ccc"` equals the array form; unknown symbol message names layer 1 row 2 (1-based); a 17-row layer → limit message; legend `"minecraft:Oak Planks"` normalises to `oak_planks` (`BlockNames.normalise` keeps the s of `planks`); legend key `"."` rejected; all-`'.'` blueprint → `places nothing`.
- `BuildPlanTest`: an L-shape (`"cc"`,`"c "`) at origin `(0,0,0)` → south `{(0,0,0),(-1,0,0),(0,0,1)}`, east `{(0,0,0),(0,0,1),(1,0,0)}`, north `{(0,0,0),(1,0,0),(0,0,-1)}`, west `{(0,0,0),(0,0,-1),(-1,0,0)}`; `places()` is non-decreasing in y; `materials` subtracts cells already correct; a cell on layer 2 with nothing under it and no planned neighbour is `floating` while the same cell on a pillar is not; `doorProblem` fires for `.` above a door.
- `TemplatesTest`: counts 71 / 58 / 24 / 20 / 24 for the default examples above; door gap cells are `.`; every template at its max size fits 16×16×16; size 99 clamps to the max.
- `StructureStoreTest`: duplicate names (any case) rejected; `nearest` filters dimension and orders by distance; JSON round trip.
- `BuildingModuleTest`: `create("build", {...valid...})` is `Ok`; bad facing, bad name and partial coordinates are `Invalid` with the messages above; `menuOptions` offers `build_hut` at 71 planks and not at 70.
- Game (`padding = 24` where noted):
  - `buildsBlueprintHut`: bot at (1.5,1,1.5) holds 30 cobblestone; `build{name: "test_hut", layers: [["ccc","c.c","c.c"],["ccc","c.c","c.c"],["ccc","ccc","ccc"]], legend: {c: cobblestone}, x,y,z = absolute (3,1,3), facing: S}` → within 1200 ticks: the 23 cells are cobblestone at the south-facing positions, the 4 `.` cells are air, the bot holds 7 cobblestone, `StructureRegistry.of(server).store().get("test_hut")` is present, and `Perceiver.capture(...)` text contains `test_hut by TestBuildHut`.
  - `buildFailsWithExactMissingList`: same with 10 cobblestone → FAILED within 20 ticks, reason `missing materials: 13 cobblestone`; no cobblestone placed.
  - `buildFailsFastOnUnplaceableLegend` (Review Focus 1): legend `{w: water}` → `Invalid` from the factory or FAILED within 5 ticks containing `can't be placed by hand`.
  - `buildNeverClearsPlayerPlacedBlocks` (Review Focus 2): dirt in a `.` cell recorded with `PlacedBlocks.record(..., "Steve")` → FAILED within 20 ticks containing `placed by Steve`; dirt still there.
  - `buildClearsNaturalBlocksInFootprint`: natural dirt in a `.` cell → DONE and that cell is air.
  - `buildsTowerWithScaffold` (`padding = 24`, maxTicks 4000): `build_template{template: tower, size: 3, material: cobblestone, name: test_tower}` with 58 cobblestone + 16 dirt → DONE; all 58 cells cobblestone; no dirt block left in the arena (scaffold removed).

- [ ] Tests first (unit + game); implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Build from blueprints and templates, and remember named structures"` with the trailer.

---

### Task 3: Chests, giving, naming and signs

**Files:** Create in `main/java/com/aiciv/storage/`: `ChestMemory.java` (pure), `DepositRule.java` (pure), `SignLines.java` (pure), `ContainerTarget.java`, `PlaceChestAction.java`, `LookInChestAction.java`, `DepositAction.java`, `WithdrawAction.java`, `GiveAction.java`, `DropAction.java`, `RenameAction.java`, `NameMobAction.java`, `SignAction.java`; fill `StorageModule.java`. Tests `test/java/com/aiciv/storage/ChestMemoryTest.java`, `DepositRuleTest.java`, `SignLinesTest.java`, `StorageModuleTest.java`; game tests in `gametest/StorageGameTests.java` (prefix `TestStore`).

**Interfaces:**
- `ChestMemory` (per-bot attachment via `ctx.attachments().get(ChestMemory.class, ChestMemory::new)`): capacity 16 containers; `void seen(Pos pos, Map<String,Integer> contents, long tick)`; `void forget(Pos pos)`; `Optional<Map<String,Integer>> contents(Pos pos)`; `List<String> describe(Pos from, int n)` → `"chest at 3 64 5 (4m E): cobblestone x40, oak_log x12 (+2 more kinds)"` (top 4 kinds by count), nearest first.
- `DepositRule` (pure): `static DepositRule parse(String items)` → `ALL`, `ALL_EXCEPT_TOOLS` (`all_except_tools`, `all except tools`, `everything but tools`), or a list `"cobblestone x20, oak_log, dirt 5"` (`x`-suffix or trailing number = max count, none = all of it); throws `IllegalArgumentException("deposit \"items\" must be all, all_except_tools, or a list like \"cobblestone x20, oak_log\"")`; `boolean keeps(String itemId, boolean isToolWeaponOrArmor)`; `int limit(String itemId)` (`Integer.MAX_VALUE` = all). Tools = has `DataComponents.TOOL` or `WEAPON` or an `EQUIPPABLE` armor slot, plus food is **kept** under `ALL_EXCEPT_TOOLS` too (a bot should not lock away its food) — the rule's name stays as the user approved; the description says "keeps tools, weapons, armor and food".
- `ContainerTarget` (shared by the chest actions): resolves `Optional<BlockPos>` from args, else `Finder.nearestBlock(ctx, id -> id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel"), 16)`; `FAILED "no chest within 16 blocks"`; then `Approach.block(pos, 4.0)`; then `MenuOps.open(p, pos, ChestMenu.class)`; null → `FAILED "the chest won't open (is something on top of it?)"`. After every look, deposit or withdraw it calls `ChestMemory.seen` with the container slots' contents. All actions call `MenuOps.close` in `cancel` and `interrupted`.
- Actions and timeouts: `PlaceChestAction()` (holds `chest`; `BlockPlacer.placeNearby`; 30 s); `LookInChestAction(BlockPos orNull)` (event `chest at x y z holds: <all kinds with counts>` or `... is empty`; 60 s); `DepositAction(DepositRule, BlockPos orNull)` (moves matching stacks with `MenuOps.moveIn`; DONE summary `deposited 20 cobblestone, 5 oak_log`; FAILED `the chest is full` if nothing moved and something should have; 60 s); `WithdrawAction(String item, int count, BlockPos orNull)` (from container slots matching `ItemNames.matcher`, `MenuOps.moveOut`; partial → DONE with `took 10 of 20 cobblestone (inventory full)` or `(the chest had only 10)`; none → FAILED `no <item> in the chest`; 60 s); `GiveAction(String player, String item, int count)` (online player in this dimension within 64, else FAILED `<name> is not nearby`; `Approach.entity(target, 3.0)`; look at their eyes; remove up to `count` and `drop(stack, false, Prediction.SERVER_ONLY)` per stack; `ctx.tell(name, "<me> threw you <n> <item>")`; waits ≤ 40 ticks for pickup; DONE `gave 5 bread to Bob` or `threw 5 bread to Bob (not picked up yet)`; 120 s); `DropAction(String item, int count)` (throws forward; 5 s); `RenameAction(String item, String name)` (anvil = `Finder.nearestBlock` over `anvil|chipped_anvil|damaged_anvil` within 16; `MenuOps.open(... AnvilMenu.class)`; move one matching stack into slot 0 by quick-move; `setItemName(name)`; `getCost()`; `experienceLevel < cost` → close and FAILED `renaming costs <cost> levels; you have <n>`; else quick-move `RESULT_SLOT`; verify `CUSTOM_NAME`; DONE `renamed name_tag to "Bessie" for 1 level`; 60 s); `NameMobAction(String target, String name)` (needs a `name_tag` whose custom name equals `name`; if only an unnamed tag is held and an anvil is within 16, run an inner `RenameAction` first; else FAILED `rename a name_tag to "<name>" at an anvil first`; target = nearest living non-player entity of `AttackAction.entityType(target)`'s type within 16; `Approach.entity(target, 3.0)`; select the tag; `EntityUse.interact`; DONE when `getCustomName()` equals `name`; 120 s); `SignAction(String text, BlockPos orNull)` (holds any item in `ItemTags.SIGNS`; place at the cell, or `placeNearby`; then the placed `SignBlockEntity.updateSignText(p, SignTextSlot.FRONT, SignLines.wrap(text).stream().map(FilteredText::passThrough).toList())`; DONE when `getText(FRONT).getMessages(false)` joined equals the wrapped text; 30 s).
- `SignLines.wrap(String text)` (pure): ≤ 4 lines of ≤ 15 characters, word-wrapped, a word longer than 15 split; text over 60 characters is cut with no ellipsis.

**Tools (StorageModule)**, factory keys in brackets: `place_chest` [placechest] (no args); `look_in_chest` [lookinchest] (`x`,`y`,`z` optional); `deposit` (`items` required, `x`,`y`,`z`); `withdraw` (`item` required, `count` 1..64 default 1, `x`,`y`,`z`); `give` (`name`, `item` required, `count` 1..64 default 1); `drop` (`item` required, `count` 1..64 default 1); `rename` (`item`, `name` required; name 1..50 after trimming); `name_mob` [namemob] (`target`, `name` required); `sign` (`text` required, 1..60 chars after `SayAction.sanitize`, `x`,`y`,`z` optional). Guide line: `Chests: place_chest, look_in_chest, deposit "all_except_tools", withdraw item count. Share with give (walks over and throws).`

**Snapshot section:** label `Chests`, `ChestMemory.describe(feet, 4)`, emptyWord null (line omitted when nothing is remembered), tags `chest_item:<id>` for every kind in the nearest remembered chest.

**Menu options:** `look_in_chest` (a `chest`/`barrel` sighting ≤ 16 in `snap.blocks()`); `deposit_all_except_tools` (same + `ctx.inventory().size() >= 6`); `place_chest` (holds `chest`, no chest sighting ≤ 16); `withdraw_<id>` for up to 3 `chest_item:` tags (count 16).

**Tests:**
- `ChestMemoryTest`: capacity 16 drops the least recently seen; `describe` text format and `+N more kinds`; `forget`.
- `DepositRuleTest`: the three spellings of except-tools; `"cobblestone x20, oak_log, dirt 5"` limits 20 / all / 5; garbage throws with the quoted message; `ALL_EXCEPT_TOOLS` keeps tools and food.
- `SignLinesTest`: `"Welcome to Ada town"` → `["Welcome to Ada", "town"]`; a 20-letter word splits 15 + 5; 80 chars → 4 lines totalling 60.
- `StorageModuleTest`: arg validation messages; `deposit` with `items: "???"` → `Invalid` containing `"items"`; menu ids for the sample.
- Game:
  - `depositAllExceptToolsKeepsPickaxe`: chest at (5,1,3); bot holds stone_pickaxe, 20 cobblestone, 5 oak_log, 3 bread → chest has 20 cobblestone + 5 oak_log; bot keeps pickaxe and bread; `ChestMemory` contents match; snapshot text has `Chests: chest at`.
  - `withdrawTakesExactCount`: chest holds 64 cobblestone → `withdraw cobblestone 10` → bot 10, chest 54.
  - `withdrawStopsWhenInventoryFull` (Review Focus 3): bot's 36 slots full of dirt ×64 except one empty slot; chest holds 2 × 64 stone → bot gains exactly 64 stone, chest keeps 64, DONE text contains `inventory full`, no stone item entity exists.
  - `lookInChestReportsContents`: events contain `cobblestone x64`.
  - `chestBehindWallFails` (Review Focus 5): chest enclosed by glass → FAILED containing `can't see` within 600 ticks; chest contents unchanged.
  - `cancelDuringDepositClosesChest` (Review Focus 4): start a deposit of 10 stacks, `executor.stop` on the tick the chest menu opens → `containerMenu == inventoryMenu`, carried empty, items in bot + chest == original total.
  - `giveThrowsItemsToPlayer`: bots `TestStoreGiveA` and `TestStoreGiveB` 6 blocks apart; A gives B 5 bread → within 300 ticks B holds 5 bread; B's events contain `TestStoreGiveA threw you 5 bread`.
  - `renameAtAnvilCostsLevels`: anvil; name_tag; 5 levels → tag named `Bessie`, levels 4.
  - `renameFailsWithoutLevels`: 0 levels → FAILED containing `costs 1 level`; tag still held, unnamed.
  - `nameMobWithTag`: cow with `setNoAi(true)` 3 blocks away; tag already named `Bessie` → cow named `Bessie`, tag consumed.
  - `signShowsText`: bot holds oak_sign → `sign{text: "Welcome to Ada town"}` → a standing sign within 3 blocks whose front lines are `Welcome to Ada`, `town`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Use chests, give and drop items, rename things and write signs"` with the trailer.

---

### Task 4: Combat moves

**Files:** Create in `main/java/com/aiciv/combat/`: `CombatMove.java`, `CombatMoves.java` (pure parser), `CombatAction.java`; fill `CombatModule.java`. Uses `AttackAction.findTarget` and `AttackAction.weaponScore` (made public in Task 1). Tests `test/java/com/aiciv/combat/CombatMovesTest.java`, `CombatModuleTest.java`; game tests in `gametest/CombatMoveGameTests.java` (prefix `TestMove`).

**Interfaces:**
- `sealed interface CombatMove` with records `AttackOnce(String target)`, `Strafe(boolean left, int ticks)`, `BackOff(int ticks)`, `JumpCrit(String target)`, `Shield(int ticks)`, `Bow(String target, int chargeTicks)`, `Hotbar(int slot)`; `int maxTicks()` per move: attack_once 60, jump_crit 30, strafe/back_off/shield its ticks, bow `charge + 20`, hotbar 1.
- `CombatMoves.parse(JsonElement moves)` → `List<CombatMove>` or throws `IllegalArgumentException` with the model-facing reason. Accepts an array whose elements are objects (`{"move":"strafe","dir":"left","ticks":10}`, `{"move":"attack_once","target":"zombie"}`, `{"move":"bow","target":"skeleton","charge":20}`, `{"move":"hotbar","slot":2}`) or strings (`"strafe left 10"`, `"attack_once zombie"`, `"bow skeleton 20"`, `"hotbar 2"`, `"shield 30"`, `"back_off 8"`, `"jump_crit zombie"`). Clamps: strafe/back_off 1..20, shield 1..40, charge 1..30, slot 1..9. A move without a target reuses the previous move's target; the first targeted move must name one (`attack_once needs a target`). Errors: `combat needs "moves": a list like ["attack_once zombie", "back_off 8"]`, `unknown move 'x' (use attack_once, strafe, back_off, jump_crit, shield, bow, hotbar)`, `at most 12 moves`.
- `CombatAction(List<CombatMove> moves)`: `describe()` = `combat <n> moves vs <first target>`; timeout `min(1200, Σ maxTicks + 200)` ticks. Keeps `LivingEntity current` (resolved with `AttackAction.findTarget(me, label, 16)` when a move names a new target; none → FAILED `no <target> nearby`; dead target → the remaining moves are skipped, DONE `<target> is dead`). Per move:
  - `AttackOnce`: if not within entity reach or no line of sight, approach (`NearGoal` 2.0, re-aimed when it moved > 2) for up to 40 ticks, else FAILED `attack_once: <target> out of reach`; wait for `getAttackStrengthScale(0.5f) >= 0.9f` (≤ 20 ticks), `lookAt`, `attack`, `swing(MAIN_HAND, SwingAnimation.DEFAULT, false)`.
  - `Strafe`: face the target each tick, `xxa = left ? 1 : -1`, `zza = 0` for `ticks`, then `stopMoving()`.
  - `BackOff`: face the target, `zza = -1` for `ticks`.
  - `JumpCrit`: needs reach; `jumpFromGround()` when on ground; attack on the first tick with `!onGround() && getDeltaMovement().y < 0` and reach and cooldown ≥ 0.9 (vanilla then makes it a critical hit); 30 ticks without that → FAILED `jump_crit: could not land a falling hit`.
  - `Shield`: needs `Items.SHIELD` in offhand or inventory (moved to offhand like `EquipAction`, old offhand item back to inventory), else FAILED `no shield`; `gameMode.useItem(p, level, offhand, OFF_HAND)`; face the target; after `ticks`, `stopUsingItem()`.
  - `Bow`: needs a bow and at least one item in `ItemTags.ARROWS`, else FAILED `no bow` / `no arrows`; target within 32; select the bow; `gameMode.useItem(..., MAIN_HAND)`; each tick look at `target.position().add(0, target.getBbHeight() * 0.5 + 0.0045 * d * d, 0)` (d = horizontal distance); after `chargeTicks`, `releaseUsingItem()`.
  - `Hotbar`: `getInventory().setSelectedSlot(slot - 1)`.
  - `cancel`/`interrupted`: `stopMoving()`, `stopUsingItem()`, `navigator().stop()`.

**Tools:** `combat` — `moves` (array; each item a string like `"strafe left 10"` or an object; required). Description: `Fight with exact moves, run in order: attack_once <target>, strafe left|right <ticks ≤20>, back_off <ticks ≤20>, jump_crit <target> (a falling critical hit), shield <ticks ≤40>, bow <target> <charge ticks, 20 = full>, hotbar <slot 1-9>. 20 ticks = 1 second. Use attack for a simple fight to the death.` Guide line: `Combat: attack fights to the end; combat runs short exact moves such as ["attack_once zombie", "back_off 8", "attack_once zombie"].`

**Menu options** (one hostile mob type `m` from `ctx.hostileMobs()`, at most 2 mobs): `kite_<m>` when holding an item in `swords|axes` → `combat{moves: ["attack_once m", "back_off 8", "attack_once m", "back_off 8", "attack_once m"]}`; `shoot_<m>` when holding `bow` and arrows → `combat{moves: ["bow m 20", "bow m 20", "bow m 20"]}`; `block_<m>` when holding `shield` → `combat{moves: ["shield 30", "attack_once m", "shield 30", "attack_once m"]}`.

**Tests:**
- `CombatMovesTest`: object and string forms parse to equal lists; `strafe left 50` clamps to 20; `bow zombie` without charge defaults 20; a target carries over (`["attack_once zombie", "jump_crit"]`); unknown move, 13 moves and an empty list each throw with their messages; `attack_once` with no target anywhere throws.
- `CombatModuleTest`: `combat` with `{}` → `Invalid` containing `"moves"`; menu `kite_zombie` appears with a sword and not without.
- Game (difficulty EASY set and restored as in milestone 2's `killsZombieWithSword`; zombies `setNoAi(true)`):
  - `strafeMovesSideways`: zombie 3 blocks north; `strafe left 10` → the bot moved ≥ 0.8 blocks east–west and its distance to the zombie changed by < 1.0.
  - `backOffIncreasesDistance`: `back_off 10` → distance grew ≥ 1.0.
  - `jumpCritDealsCritDamage`: iron sword, zombie 2 blocks away at full health → after `jump_crit zombie` the zombie lost ≥ 8.0 health (iron sword 6 × 1.5 crit, minus 2 armor ≈ 8.86; a plain hit is ≈ 5.9).
  - `shieldBlocksWhileRaised`: shield in inventory → within 10 ticks of `shield 30` `isBlocking()` is true; after the action ends it is false and the shield is in the offhand.
  - `bowShootsAtTarget`: bow + 5 arrows, zombie 8 blocks away → within 80 ticks of `bow zombie 20` the bot has 4 arrows and the zombie's health < max.
  - `hotbarSelectsSlot`: `hotbar 3` → selected slot 2.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Fight with short exact combat moves"` with the trailer.

---

### Task 5: Trading with villagers

**Files:** Create in `main/java/com/aiciv/trade/`: `OfferView.java` (pure), `VillagerMemory.java` (pure), `Villagers.java` (finding and opening), `TradeListAction.java`, `TradeAction.java`; fill `TradeModule.java`. Tests `test/java/com/aiciv/trade/OfferViewTest.java`, `VillagerMemoryTest.java`, `TradeModuleTest.java`; game tests in `gametest/TradeGameTests.java` (prefix `TestTrade`).

**Interfaces:**
- `record OfferView(int index, String costA, int countA, String costB, int countB, String result, int resultCount, int uses, int maxUses)` with `boolean outOfStock()` (`uses >= maxUses`), `String text()` → `"1: 1 emerald -> 6 bread (10 left)"` or with `+ 2 book` for a second cost, `"(sold out)"`; `static OfferView of(int index, MerchantOffer o)` (uses `getCostA()`/`getCostB()` — the current prices); `static int find(List<OfferView> offers, String want)` → index of the first in-stock offer whose result matches `ItemNames`-style id matching (exact, else suffix/contains), else −1; `static int findSelling(List<OfferView>, String give)` — first in-stock offer whose `costA` matches (for "sell wheat").
- `VillagerMemory` (attachment): `void seen(UUID id, String profession, Pos pos, List<OfferView> offers, long tick)`; capacity 12; `List<String> describe(Pos from, int n)` → `"farmer 6m N: 0: 20 wheat -> 1 emerald; 1: 1 emerald -> 6 bread"` (first 3 offers), nearest first; `List<String> tags(Pos from)` → `villager:<profession>` and `offer:<profession>:<index>:<result id>:<costA id>:<countA>`.
- `Villagers.find(AiPlayer p, String professionOrNull, double radius)` → nearest `Villager` that is alive, not a baby, profession not `NONE`/`NITWIT` and (if given) whose profession key path equals `professionOrNull` (`"farmer"`, plural `"farmers"` ok); radius 32. `Villagers.profession(Villager)` → key path. `Villagers.open(AiPlayer p, Villager v)` → `MerchantMenu` or null (via `EntityUse.interact`, then `p.containerMenu instanceof MerchantMenu`).
- `TradeListAction(String villagerOrNull)`: find (FAILED `no villager nearby` / `no <profession> nearby`), `Approach.entity(v, 3.0)`, open (null → FAILED `the <profession> won't trade right now (busy, asleep or a baby)`), read `menu.getOffers()` into `OfferView`s, `ctx.event("<profession> at x y z offers: " + texts joined "; ")`, `VillagerMemory.seen`, close; DONE; 60 s.
- `TradeAction(String villagerOrNull, String wantOrNull, Integer offerOrNull, int times)`: exactly one of `want`/`offer` (factory checks); after opening: index = `offer` or `OfferView.find(want)` (−1 → close, event the offer list, FAILED `the <profession> doesn't sell <want>; offers: <first 4>`); per trade (one every 10 ticks): out of stock → stop; `menu.setSelectionHint(i)`, `menu.tryMoveItems(i)`; result slot 2 empty → stop with reason `can't afford: needs <countA> <costA>[ and <countB> <costB>] (have <n>)`; else `menu.clicked(2, 0, ContainerInput.PICKUP, p)` and `InventoryOps.addOrDrop(p, carried)`, `setCarried(EMPTY)` (one trade per click; never `QUICK_MOVE`, which would trade repeatedly); after the loop `MenuOps.close`. DONE `traded 2x: 2 emerald -> 12 bread` when ≥ 1 trade happened, else FAILED with the stop reason; 120 s.

**Tools:** `trade_list` [tradelist] (`villager` optional: profession like farmer); `trade` (`villager` optional, `want` optional item id, `offer` optional integer index, `times` 1..12 default 1; `Invalid` `trade needs "want" (an item) or "offer" (a number from trade_list)` when both missing, `use either "want" or "offer", not both`). Guide line: `Villagers: trade_list shows offers (prices change with demand); trade by "want" or "offer" index.`

**Snapshot section:** label `Villagers`: villagers within 32 (seen through `Finder.nearestEntity` loop, any profession) as `"farmer 6m N"` plus their remembered offers from `VillagerMemory.describe`, emptyWord null, tags from `VillagerMemory.tags` plus `villager:<profession>` for each seen.

**Menu options:** `trade_list_<profession>` for up to 3 `villager:` tags without remembered offers; `trade_<profession>_<index>` for up to 3 remembered offers whose `costA` the bot holds in ≥ `countA` (from `ctx.inventory()`), `times 1`, description `"trade <countA> <costA> for <result>"`.

**Tests:**
- `OfferViewTest`: `text()` for one and two costs and sold out; `find("bread")` skips a sold-out bread offer and returns the next; `find("diamond")` → −1; `findSelling("wheat")`.
- `VillagerMemoryTest`: capacity, `describe` format, tags.
- `TradeModuleTest`: validation messages; menu offers `trade_farmer_1` only with ≥ 1 emerald held.
- Game (`padding = 24`; villagers spawned with `helper.spawn(EntityType.VILLAGER, …)`, `setNoAi(true)`, profession set with `setVillagerData(getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.FARMER)))`, offers via `overrideOffers(...)` with `new MerchantOffer(new ItemCost(Items.EMERALD, 1), Optional.empty(), new ItemStack(Items.BREAD, 6), 0, 12, 1, 0.05f, 0)` and `new MerchantOffer(new ItemCost(Items.WHEAT, 20), Optional.empty(), new ItemStack(Items.EMERALD), 0, 16, 2, 0.05f, 0)`):
  - `tradeListPutsOffersInEvents`: events contain `0: 1 emerald -> 6 bread`; `VillagerMemory` has the farmer.
  - `tradeBuysBreadTwice`: 3 emeralds → `trade{villager: farmer, want: bread, times: 2}` → 12 bread, 1 emerald, the offer's `getUses() == 2`, menu closed.
  - `tradeFailsWhenTooPoor`: 0 emeralds → FAILED containing `needs 1 emerald`.
  - `tradeSellsWheat`: 20 wheat → `trade{offer: 1}` → 1 emerald, 0 wheat.
  - `babyVillagerWontTrade`: a baby farmer only → FAILED containing `won't trade` (or `no farmer nearby`, because babies are skipped by `find`; assert either).

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Trade with villagers at their real prices"` with the trailer.

---

### Task 6: Farming, breeding and fishing

**Files:** Create in `main/java/com/aiciv/farm/`: `Crops.java` (pure seed↔crop table), `AnimalFood.java` (pure, menu only), `TillAction.java`, `PlantAction.java`, `HarvestAction.java`, `BreedAction.java`, `FishAction.java`; `main/java/com/aiciv/mixin/FishingHookAccessor.java`; fill `FarmModule.java`. Modify `resources/aiciv.mixins.json` (add `"FishingHookAccessor"` — the only shared-file edit). Tests `test/java/com/aiciv/farm/CropsTest.java`, `AnimalFoodTest.java`, `FarmModuleTest.java`; game tests in `gametest/FarmGameTests.java` (prefix `TestFarm`).

**Interfaces:**
- `Crops` (pure): `static Optional<String> cropFor(String seedItem)` — `wheat_seeds→wheat`, `beetroot_seeds→beetroots`, `carrot→carrots`, `potato→potatoes`, `pumpkin_seeds→pumpkin_stem`, `melon_seeds→melon_stem`, `torchflower_seeds→torchflower_crop`; `static Optional<String> seedFor(String cropBlock)` (inverse); `static boolean isSeed(String item)`.
- `AnimalFood` (pure, only for menus — actions ask `Animal.isFood`): `cow|mooshroom|sheep|goat → wheat`, `pig → carrot, potato, beetroot`, `chicken → wheat_seeds, beetroot_seeds, melon_seeds, pumpkin_seeds`, `rabbit → carrot, dandelion`, `horse|donkey → golden_carrot, golden_apple`, `wolf → any meat`, `cat → cod, salmon`; `static List<String> foods(String animal)`.
- `FishingHookAccessor` — `@Mixin(FishingHook.class) public interface FishingHookAccessor { @Accessor("nibble") int aiciv$nibble(); }` (the bobber's dip is visible to a real client through the synced `DATA_BITING`, so reading it is fair).
- `TillAction(int count)`: needs a hoe (`ItemTags.HOES`), else FAILED `needs a hoe`; candidates = visible blocks in `#turns_into_farmland` with air above within 8 (`Finder.visibleStates`), ordered by (has water within 4 horizontally and 0..1 above — vanilla `FarmlandBlock` moisture rule — first) then distance; per block: `Approach.block(pos, 4.0)`, select the hoe, `BlockUse.clickFace(p, pos, UP)`; success iff the block became farmland; DONE `tilled 3 (2 near water)`; when none was near water, also `ctx.event("tilled soil has no water within 4 blocks and will dry out")`; no candidates → FAILED `no dirt or grass with air above within 8`; timeout `min(3600, 1200 + 60·count)` ticks.
- `PlantAction(String seed, int count)`: seed via `Crops.isSeed` (else FAILED `<seed> is not a seed; use wheat_seeds, carrot, potato, beetroot_seeds...`); targets = visible farmland with air above within 8; `clickFace(p, farmland, UP)` with the seed selected; success iff the cell above is now the crop; DONE `planted 3 wheat_seeds`; FAILED `no empty farmland within 8 — till some first`; same timeout formula.
- `HarvestAction(int count)`: targets = visible `CropBlock` states with `isMaxAge` within 16 (also fully grown `sweet_berry_bush` is out of scope); per crop: approach, break with `ctx.breaker()`, gather drops within 4 blocks with `ItemGatherer` (≤ 60 ticks), replant with the matching seed (`Crops.seedFor`) if held; DONE `harvested 3 wheat (replanted 3)`; none ripe → FAILED `no ripe crops in sight`; timeout 3 min.
- `BreedAction(String animal)`: type via `AttackAction.entityType` (public since Task 1); candidates = adult (`!isBaby()`), `canFallInLove()`, not in love, of that type, within 16; < 2 → FAILED `need two grown <animal>s nearby`; food = a held stack with `animal.isFood(stack)`, need 2, else FAILED `needs 2 food for <animal>s (they eat <AnimalFood list>)`; feed the two nearest: `Approach.entity(a, 3.0)`, select food, `EntityUse.interact`; DONE when both `isInLove()`: `bred two cows`; 60 s.
- `FishAction(int catches)`: needs `fishing_rod`; water target = nearest visible water source with air above within 8 (`Finder.visibleStates`), else FAILED `no open water within 8`; stand within 4 of it (`Approach.block(water, 4.0)`), look at its top centre, cast `gameMode.useItem(..., rod, MAIN_HAND)`; each tick: `p.fishing == null` → recast after 10 ticks; hook `isInWater()` and `((FishingHookAccessor) hook).aiciv$nibble() > 0` → reel (`useItem` again), compare non-rod inventory counts before/after, `ctx.event("caught <item>")` per new item; hook on land or not in water for 100 ticks → reel and recast at the next of up to 3 water cells; DONE after `catches` catches (`caught 3: cod, cod, salmon`); rod broken → DONE with what was caught, or FAILED `the rod broke` if none; timeout 5 min.

**Tools:** `till` (`count` 1..32 default 4); `plant` (`seed` required, `count` 1..32 default 4); `harvest` (`count` 1..32 default 32); `breed` (`animal` required); `fish` (`catches` 1..10 default 3). Guide line: `Farming: till near water (needs a hoe), plant seeds on farmland, harvest replants. breed feeds two adults their food. fish needs a rod. Cook with smelt.`

**Snapshot section:** label `Crops`: visible crops within 16 grouped `"wheat x6 ripe, x3 growing (nearest 4m E)"`, plus `"free farmland x4"`; emptyWord null; tags `crop_ripe` (any ripe), `farmland_free` (any farmland with air above), `water_near` (any visible water within 8).

**Menu options:** `harvest_crops` (`crop_ripe`); `plant_<seed>` (`farmland_free` and the seed held; count = min(held, 8)); `till_soil` (hoe held, a seed held, no `farmland_free`); `breed_<animal>` (a `snap.mobs()` sighting of that animal with count ≥ 2 and one of `AnimalFood.foods` held ≥ 2); `fish` (rod held and (`water_near` or a `water` sighting ≤ 8)); `cook_<raw>` for held `beef|porkchop|chicken|mutton|cod|salmon|potato` → `smelt{item, count: min(held, 8)}`.

**Tests:**
- `CropsTest`: seed↔crop both ways; `isSeed("dirt")` false.
- `AnimalFoodTest`: cow → wheat; pig includes carrot; unknown → empty.
- `FarmModuleTest`: validation (`plant` without seed → `Invalid` containing `"seed"`); menu `breed_cow` needs 2 cows and 2 wheat; `cook_beef` uses `smelt`.
- Game (`FarmGameTests`, 8 × 8 dirt floor unless noted):
  - `tillsNearWater`: water source at (4,0,4); bot with wooden_hoe → `till 3` → 3 farmland blocks within 4 of the water; hoe damage ≥ 1.
  - `plantsSeeds`: 3 farmland cells; 3 wheat_seeds → wheat (age 0) above all three; seeds 0.
  - `harvestReplants`: 3 wheat at `CropBlock.AGE` 7 on farmland; bot holds no seeds → holds ≥ 3 wheat; all three cells are wheat at age 0 (replanted from the dropped seeds).
  - `breedsCows`: two adult cows `setNoAi(true)` 3 blocks apart; bot with 2 wheat → both `isInLove()` within 300 ticks; wheat 0.
  - `breedFailsWithWrongFood`: two pigs, 2 wheat → FAILED containing `carrot`.
  - `fishesFromPool` (`padding = 24`, maxTicks 2400): 5 × 5 × 2 water pool, bot on its edge with a fishing rod → `fish 1` DONE; the bot holds at least one new non-rod item; `p.fishing == null` afterwards.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Farm, breed animals and fish with a real rod"` with the trailer.

---

### Task 7: Sleep, doors, switches, enchanting and brewing

**Files:** Create in `main/java/com/aiciv/utility/`: `SleepAction.java`, `UseBlockAction.java`, `EnchantAction.java`, `BrewTable.java` (pure), `BrewAction.java`; fill `UtilityModule.java`. Modify `nav/BlockView.java` (add `default boolean openable(int x, int y, int z) { return false; }`), `nav/PathStep.java` (add component `Pos open` with a compatibility 4-argument constructor setting it null), `nav/PathSearch.java`, `nav/PathFollower.java`, `nav/LevelBlockView.java`, `test/java/com/aiciv/testutil/GridView.java` (`door(x,y,z)`); create `resources/data/aiciv/test_environment/night.json`. Tests `test/java/com/aiciv/utility/BrewTableTest.java`, `UtilityModuleTest.java`, new cases in `test/java/com/aiciv/nav/PathSearchTest.java`; game tests in `gametest/UtilityGameTests.java` (prefix `TestUtil`).

**Doors (navigation):**
- `LevelBlockView.openable`: true for a closed (`OPEN == false`) block in `BlockTags.WOODEN_DOORS` or `BlockTags.FENCE_GATES`; `breakSeconds` returns `+∞` for any door, trapdoor or fence gate (never break doors, even natural village ones).
- `PathSearch`: a cardinal WALK (never diagonal, jump or drop) may enter a cell whose feet (and head, for the upper door half) are `openable`; cost `1 + 1`; the step's `open` = the feet cell. Allowed with any `SearchOptions` (opening needs no tool).
- `PathFollower`: before moving into a step with `open != null` whose block is still closed: stop, `BlockUse.click(p, open)` (≤ 3 tries, 5 ticks apart; still closed → STUCK); once the bot's feet are one full step past the door cell, click it again to close it behind (only if it is still open and no other entity stands in it).
- `night.json`: `{"type": "minecraft:all_of", "definitions": [{"type": "minecraft:clock_time", "clock": "minecraft:overworld", "time": 18000}, {"type": "minecraft:difficulty", "difficulty": "peaceful"}]}`. If the loader rejects it, read `TestEnvironmentDefinition.ClockTime.CODEC` with javap and fix the field names; the test fails at startup with the codec error, not silently.

**Actions:**
- `SleepAction()`: bed = `Finder.nearestBlock(ctx, id -> id.endsWith("_bed"), 16)` not `OCCUPIED`; none → if holding an item in `ItemTags.BEDS`, `BlockPlacer.placeNearby` it (the bed's head goes in the look direction; try every free cell); else FAILED `no bed nearby and none in your inventory`; `Approach.block(bed, 2.5)`; `BlockUse.click`; if `p.isSleeping()` → RUNNING until it wakes; DONE `slept until morning` (or `woke up` if woken early); if not sleeping, FAILED with the rule that stopped it, checked in this order: `!level.isDarkOutside() && !level.isThundering()` → `you can only sleep at night or in a thunderstorm`; a `Monster` within 8 × 5 × 8 of the bed that `isPreventingPlayerRest(level, p)` → `monsters are nearby`; otherwise `the bed is blocked or too far`. Timeout 14 400 ticks (12 min). `cancel` → `stopSleepInBed(true, true)` if sleeping.
- `UseBlockAction(int x, int y, int z)`: allowed blocks: `BlockTags.BUTTONS`, `Blocks.LEVER`, `WOODEN_DOORS`, `WOODEN_TRAPDOORS`, `FENCE_GATES`, `Blocks.BELL`; anything else → FAILED `use_block works on buttons, levers, wooden doors, trapdoors, fence gates and bells; use look_in_chest for chests`; `Approach.block(pos, 4.0)`, `BlockUse.withFreeHand`, `BlockUse.click`; DONE `pressed stone_button at x y z` / `flipped lever ... (now on)` / `opened oak_door ...` / `closed ...`; `NO_EFFECT` → FAILED `nothing happened`; 30 s.
- `EnchantAction(String item, int option)`: table within 16 (`enchanting_table`); item held (`ItemNames.matcher`) and enchantable (`stack.isEnchantable()`), else FAILED `<item> can't be enchanted`; lapis ≥ `option`; open `EnchantmentMenu`; quick-move the item (to slot 0) and lapis (to slot 1); read `costs`; `ctx.event("enchant options: 1: 3 levels, 2: 6 levels, 3: 9 levels")` (append the clue enchantment name when `enchantClue[i] >= 0`, looked up in `level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)` by id — verify the id-map method with javap; leave the name out if it is not available); `costs[option-1] == 0` → FAILED `option <n> is not available (put bookshelves around the table for more)`; `experienceLevel < costs[option-1]` → FAILED `option <n> needs level <cost>; you have <lvl>`; else `clickMenuButton(p, option - 1)`; quick-move slot 0 back; close; DONE `enchanted iron_pickaxe (cost 1 level and 1 lapis)` and verify `EnchantmentHelper.hasAnyEnchantments`; 60 s.
- `BrewTable` (pure): `static Optional<String> ingredientFor(String potion)` — `awkward→nether_wart`, `swiftness→sugar`, `strength→blaze_powder`, `healing→glistering_melon_slice`, `fire_resistance→magma_cream`, `night_vision→golden_carrot`, `water_breathing→pufferfish`, `regeneration→ghast_tear`, `leaping→rabbit_foot`, `slow_falling→phantom_membrane`; `static boolean needsAwkwardBase(String potion)` (all but `awkward`); `static String normalise(String raw)` (`"potion of swiftness"`, `"speed"` → `swiftness`; `"fire res"` → `fire_resistance`).
- `BrewAction(String potion, int count)` (count 1..3): brewing stand within 16 (or a held `brewing_stand` placed with `placeNearby`); fuel: the stand's fuel > 0 or a held `blaze_powder` (FAILED `needs blaze_powder as fuel`); bottles: held `potion` items with `Potions.AWKWARD` (when the target needs the base) or `Potions.WATER`; missing water bottles are filled from held `glass_bottle`s by looking at a visible water source within 4 and `gameMode.useItem` (FAILED `needs <n> water bottles or glass bottles and water`); if the target needs awkward and fewer than `count` awkward potions are held, brew awkward first (needs `nether_wart`); ingredient for each stage held, else FAILED `needs <ingredient>`. Per stage: open `BrewingStandMenu`, quick-move fuel, bottles (≤ count) and the ingredient, close, wait near the stand until all bottles carry the stage's potion (`PotionContents.is`) — about 400 ticks — reopen, quick-move the bottles back, close. DONE `brewed 3 potion of swiftness`; timeout 3600 ticks.

**Tools:** `sleep` (no args); `use_block` [useblock] (`x`,`y`,`z` required); `enchant` (`item` required, `option` 1..3 default 1); `brew` (`potion` required, `count` 1..3 default 1). Guide line: `Utility: sleep at night (places a carried bed); use_block presses buttons, flips levers, opens doors; enchant at a table with lapis and levels; brew awkward potions then real ones. You open doors on your way automatically.`

**Snapshot section:** label `Rest`: when `level.isDarkOutside() || level.isThundering()` one item `"it is night: sleep in a bed to skip it"` with tag `can_sleep`; otherwise no line. (Tables, stands, anvils and beds already appear under Blocks nearby.)

**Menu options:** `sleep` (`can_sleep` and (a `_bed` sighting ≤ 16 or a bed held)); `enchant_<item>` for the best held enchantable item among `diamond_sword, diamond_pickaxe, iron_sword, iron_pickaxe, iron_chestplate, bow` when an `enchanting_table` sighting ≤ 16 exists and `lapis_lazuli` is held → option 1; `brew_awkward` when a `brewing_stand` sighting ≤ 16 exists and `nether_wart`, `blaze_powder` and (`glass_bottle` or `potion`) are held.

**Tests:**
- `PathSearchTest` (existing tests unchanged): `opensADoorInAWall` (a wall with one closed door cell → FOUND through the door, that step has `open` set and no `breaks`, with `WALK_ONLY` and with `(true, true)`); `neverBreaksADoor` (door cell `breakSeconds = +∞`, door closed but `openable` false — an iron door → FAILED with `WALK_ONLY` and with `(true, false)`, no step breaks it).
- `BrewTableTest`: every listed potion maps; `speed` normalises to `swiftness`; `awkward` needs no base.
- `UtilityModuleTest`: `use_block` without z → `Invalid` containing `"z"`; `enchant` option 7 clamps to 3; menu `sleep` only with `can_sleep`.
- Game:
  - `opensDoorOnTheWay`: an 8-wide oak plank wall at x=4 with a closed oak_door as the only gap; bot `goTo` x=6 → ARRIVED; the door still exists and is closed again; no plank was broken.
  - `pressesButtonAndFlipsLever`: `use_block` on a stone_button → `POWERED` true; on a lever → toggled.
  - `useBlockRefusesChests`: FAILED containing `use look_in_chest`.
  - `sleepsInCarriedBed` (`environment = "aiciv:night"`, maxTicks 600): bot holds a white_bed → within 200 ticks `isSleeping()`; `executor.stop` → not sleeping.
  - `sleepFailsByDay` (default environment, which is day at the test's start; assert `!level.isDarkOutside()` first and skip with `helper.succeed()` otherwise): FAILED containing `only sleep at night`.
  - `enchantsPickaxe`: enchanting_table; iron_pickaxe, 3 lapis, `giveExperienceLevels(30)` → `enchant iron_pickaxe 1` → enchanted, lapis 2, level 29.
  - `brewsAwkwardPotions` (maxTicks 1200): brewing_stand; water source at distance 2; bot holds 3 glass_bottle, 1 nether_wart, 1 blaze_powder → `brew awkward 3` → 3 potions with `Potions.AWKWARD`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Sleep, open doors, use switches, enchant and brew"` with the trailer.

---

### Task 8: Builders & Society acceptance

Runs after Tasks 2–7 are merged.

**Files:** Game test in `gametest/CivGameTests.java` (prefix `TestCiv`); `README.md` (all milestone-3 actions in the actions table, blueprint format with the hut example, `aiciv:night` note for testers, "bots never break blocks other players placed"); spec `docs/superpowers/specs/2026-10-11-aiciv-design.md` "Changes made during milestone 3".

**Test `scriptedBotBuildsStoresAndSleeps`** (`environment = "aiciv:night"`, `padding = 24`, maxTicks 6000): 16 × 16 arena (`GameTestKit.clearBarriers`, stone floor); bot `TestCivAda` at (12.5, 1, 12.5) holding 64 oak_planks, 1 chest, 1 white_bed, 20 cobblestone, 10 dirt, 5 oak_log, 1 stone_pickaxe. Scripted replies (`GameTestKit.turn`), absolute coordinates computed from `helper.absolutePos`:
1. `build {name: "ada_hut", facing: "S", x,y,z = (4,1,3), legend: {p: oak_planks}, layers: L}` where layers 0 and 1 are `["pp.pp","p...p","p...p","p...p","ppppp"]` (door gap in row 0) and layer 2 is five `"ppppp"` rows → 2 × 15 + 25 = **55** planks; facing south puts columns at x = 4, 3, 2, 1, 0 and rows at z = 3..7 (relative).
2. `place_chest {}`, then `deposit {items: "cobblestone, dirt, oak_log"}`.
3. `sleep {}`.
Assert, in `succeedWhen`: `StructureRegistry` has `ada_hut` and its 55 cells are oak_planks; a chest in the arena holds exactly 20 cobblestone, 10 dirt, 5 oak_log; the bot was sleeping at some tick (an `onEachTick` flag) or its events contain `slept`; the bot still holds the stone_pickaxe; the decision log has ≥ 3 decisions and no line contains `"error"`. Record the finishing tick in the spec.

**Live acceptance (hand to the user):** in a survival world near a village, `/ai spawn Ada gemini-35-flash "A builder who wants a cosy home and a farm."` and `/ai spawn Jev1 jev "A trader."` for 30 minutes; report: structures built (Structures line), chests used, trades made, nights slept, and LLM calls per bot from `logs/aiciv/*.jsonl`.

- [ ] Write the test; run `./gradlew build test runGametest --no-daemon`; update README and spec; commit `"Prove a bot can build a home, store its things and sleep"` with the trailer.

---

## After this milestone

Milestone 4, **Endgame** (out of scope here): nether portal building and lighting, nether travel and fortress finding, blaze fighting for rods, eyes of ender and stronghold triangulation, and the ender dragon fight.

## Self-review notes

- Spec coverage: building (`build`, `build_template`, survival placement, scaffolding, natural-only clearing, missing list, named structures in every snapshot) → Task 2; chests (place, look, deposit modes, withdraw, remembered contents) → Task 3; naming (anvil with real cost, name tag, sign) → Task 3; combat moves → Task 4; give/drop → Task 3; trading → Task 5; farming, breeding, fishing, cook → Task 6; sleep, doors, use_block, enchant, brew → Task 7; perception (XP → Task 1; containers → Task 3; villagers → Task 5; crops → Task 6; structures → Task 2); tool + menu + sync test → Task 1 contract, each module; global constraints → Global Constraints; acceptance → Task 8; Milestone 4 named.
- Shared files after Task 1: only `aiciv.mixins.json` (Task 6) and `nav/*` + `GridView` (Task 7). Entity-name resolution (`AttackAction.entityType`/`findTarget`) is made public in Task 1, so Tasks 3, 4 and 6 do not depend on each other.
