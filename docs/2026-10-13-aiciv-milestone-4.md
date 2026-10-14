# AiCiv Milestone 4 ("Everything a player can do") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bots can do the rest of what a survival player does: build and light portals and change dimension, find a fortress and blaze rods, make eyes and find and open the End portal, fight the dragon, travel by boat, horse, minecart, elytra and ender pearl, use buckets, bone meal, fire, potions, throwables, maps and compasses, tame, lead, milk, shear and dye animals, work smithing tables, stonecutters, grindstones, looms, cartography tables and books, and build oriented redstone, each as a primitive action and (for the endgame) as a routine built from them, for tool-mode LLMs and menu-mode models alike.

**Architecture:** Task 1 adds everything two or more features share: dimension-change handling and per-dimension memory, waypoints, a generic `enter_portal`, riding helpers plus a mixin that lets the server drive a bot's vehicle, a ballistic solver, bow shots, buckets, `Routine` (an action made of child actions), menu and placement helpers, and a stub module plus a game-test class for every later task. Tasks 2–10 are independent of each other and run in parallel in separate git worktrees after Task 1 merges (three at a time: RAM). Task 11 is the scripted acceptance. As before, pure planners/parsers are unit tested and Minecraft-bound actions are verified with Fabric GameTests.

**Tech Stack:** As milestones 1–3: MC 26.3 (unobfuscated Mojang names), Fabric loader 0.19.5, fabric-api 0.161.0+26.3, loom 1.18.2, Java 25, JUnit 5.13.4, MixinExtras (bundled with Fabric Loader).

**Spec:** `docs/superpowers/specs/2026-10-11-aiciv-design.md` — section "Milestone 4 — Everything a player can do" (and the milestone 3 fairness rules it builds on). Milestone 5 (`docs/superpowers/plans/2026-10-13-aiciv-milestone-5.md`) is planned in parallel; its `endgame.json` already names `build_portal`, `enter_portal`, `find_fortress`, `hunt_blazes`, `locate_stronghold`, `throw_eye`, `fill_end_portal` and `fight_dragon`, and its done-checks read the snapshot labels `nether_portal`, `nether_bricks`, `end_portal_frame` and the section tag `dragon_dead`. This plan keeps exactly those names.

**Format note:** same contract style as milestones 2 and 3: exact files, interfaces, numbers, verified 26.3 API names and exact test cases; implementers write the code against these contracts with compile and test feedback. Where an algorithm is subtle, the plan gives it as code or as numbered rules.

## Global Constraints

Everything in milestones 1–3's Global Constraints still holds (26.3 stack, `implementation` deps, no `net.minecraft.client`, worktree `E:\Projects cursor\Mcmods\.worktrees\aiciv` branch `aiciv`, API keys never in the repo or any log line, bots always Survival, reach 4.5 for blocks and 3.0 for entities, line of sight, real timings and costs, server thread only, never break or clear a block another player or bot placed). Every commit ends with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Additionally:

- **Survival fairness.** No item spawning, no teleporting: dimension changes only through real portal blocks and their real timers; pearls only through a thrown `ThrownEnderpearl`. Projectiles (pearls, eyes, snowballs, eggs, potions, arrows, fireworks) are launched only with `gameMode.useItem` / `releaseUsingItem` while the bot looks the way the throw needs; aiming uses `world/Ballistics` (vanilla gravity and drag), never velocity writes. Vehicles move only through the inputs a client sends (`AbstractBoat.setInput`, the rider's `xxa`/`zza`/yaw, `ServerPlayer.setLastClientInput`, `onPlayerJump`). Menus only through `AbstractContainerMenu.clicked`, `clickMenuButton` and the vanilla packet handlers (`ServerGamePacketListenerImpl.handleEditBook` for books). Reading game state is limited to what a client sees: entity positions and synced data (an eye's flight, the dragon's synced phase and boss-bar health, a horse being tamed, a spawner's spinning mob), never private fields such as `EyeOfEnder.target` or `AbstractHorse.temper`.
- **Dimension rule.** After Task 1 every action ends `FAILED "… you went through to <dimension>"` if the bot changes dimension while it runs, unless it overrides `Action.survivesDimensionChange()` to return true (only `EnterPortalAction` and routines whose current stage survives). Actions bound to a dimension check it on their first tick: `"<tool> works in the_nether; you are in overworld"` (same wording for `the_end` and `overworld`). Their game tests use a package-private constructor with `anyDimension = true` so they can run in the overworld test world.
- **Every new action** gets (a) a factory entry under its normalised key, (b) a catalog function whose parameter names equal the factory's argument names, (c) menu options, all through its module; a missing required argument yields an `Invalid` reason containing that argument's name in double quotes. The milestone 3 `ModuleSyncTest` enforces this; Task 1 adds rule 6: for the modules added in this milestone, every function description is at most 200 characters and every parameter description at most 100 (the catalog grows from about 35 to about 92 functions, and it is sent with every tool-mode request).
- **Limits** (clamped in the factory unless an `Invalid` message is given): `find_fortress` distance 64..1024 (default 384); `hunt_blazes`/`get_blaze_rods` count 1..16 (default 6); `make_eyes` count 1..12 (default 12); `locate_stronghold` spread 32..256 (default 96); `shoot_crystals` count 1..10 (default 10); `hit_dragon` seconds 5..120 (default 30); `ride_to`/`boat_to` horizontal distance ≤ 512 (`Invalid "ride_to goes at most 512 blocks per call"`); `fly_to` distance 16..1024; `throw_pearl` horizontal distance 3..40; `bone_meal` count 1..8 (default 3); `stonecut` count 1..64 (default 1); `write_book` text ≤ 1024 × 10 characters (10 pages, `WritableBookContent.PAGE_EDIT_LENGTH` 1024 each), title 1..32 (`WrittenBookContent.TITLE_MAX_LENGTH`); `tame` at most 16 items or 8 rides; at most 16 firework rockets per `fly_to`.
- **Snapshot sections** added in this milestone show at most 6 items each and omit their line when they have nothing to say (empty items, `emptyWord` null).
- **Game tests share one world.** Each task owns the game-test class Task 1 creates for it and only that class; bot names start with the class's prefix (≤ 16 chars): `TestDim`, `TestNeth`, `TestHold`, `TestDrag`, `TestRide`, `TestFly`, `TestItem`, `TestPet`, `TestStn`, `TestRed`, `TestEnd`. Arenas larger than 8 × 8 use `padding = 24` and `GameTestKit.clearBarriers`. Tests spawn their own entities, with `helper.spawnWithNoFreeWill(EntityTypes.X, …)` where a mob must keep still. Tests that send a bot to another dimension use the test server's own nether and End; if `server.getLevel(Level.NETHER)` (or `END`) is null, the test asserts the action failed with `the_nether does not exist on this server` and succeeds, and the implementer notes it in the spec. Every test removes its bots (`GameTestKit.remove`) even after a dimension change.
- **Parallel work rules (Tasks 2–10):** each task creates files only in its own package (`com.aiciv.<feature>`), its own test package and its own game-test class, and edits only its own module stub. Task 10 is the one exception: it also edits `building/*`, `utility/UseBlockAction.java` and `utility/UtilityModule.java`, which no other task touches. No task in 2–10 edits `ToolModules.java`, `fabric.mod.json`, `aiciv.mixins.json`, `Perceiver`, `KnownBlocks`, `nav/*` or `world/*` (Task 1 already made every shared change). README and spec updates happen only in Task 11.
- Run from `AiCiv/`: `./gradlew test runGametest --no-daemon`.

## Review Focus

1. **Changing dimension in the middle of something** (walking into a portal by accident, a death in the nether respawning the bot in the overworld, a pearl or an End gateway): the current action fails with `you went through to <dimension>`, the navigator and breaker stop, reflexes reset, nothing walks toward old-dimension coordinates, and remembered chests, ores and villagers of the old dimension come back when the bot returns. → Task 1 tests `actionFailsWhenBotChangesDimension`, `navigationNeverEntersPortal`, `KnownBlocksTest.keepsEachDimension`, `ChestMemoryTest.keepsEachDimension`.
2. **Missing or wrong items for a step** (9 obsidian, no flint, no bow or no arrows, 1 eye for 3 empty frames, a pig with no saddle, a wolf offered wheat): the action fails before changing anything, naming what is missing and what the bot has (`needs 10 obsidian (you have 9)`, `needs 3 ender_eye for 3 empty frames (you have 1)`, `wolves are tamed with bone`). → Task 2 `buildPortalFailsWithoutEnoughObsidian`; Task 3 `fillEndPortalFailsWithTooFewEyes`; Task 5 `boardPigFailsWithoutSaddle`; Task 8 `tameFailsWithWrongFood`.
3. **Riding goes wrong** (ejected by a bucking horse, boat stuck on land, minecart stops, action cancelled or replaced while riding): the bot is never left riding with inputs held; cancel clears inputs and dismounts, and a stuck vehicle fails within 60 ticks with where it stopped. → Task 1 `vehicleIsServerDrivenForBots`; Task 5 `cancelWhileRidingDismounts`, `boatStuckOnLandFails`.
4. **Lethal surroundings** (the End's void, lava in the nether, crystal explosions, pearl damage at low health, dragon breath): navigation never steps into the void or a portal, crystals are shot from ≥ 9 blocks, pearls are refused at ≤ 7 health, breath clouds are left at once. → Task 4 `shootsEndCrystalFromSafeDistance`, `stepsOutOfDragonBreath`; Task 6 `pearlRefusedAtLowHealth`; Task 1 `navigationNeverEntersPortal`.
5. **Model-supplied targets that are wrong** (a `throw` at a point behind a wall, `fill_bucket lava` with no lava in sight, `light_portal` on a broken frame, `ride_to` a point across land in a boat, an unknown banner pattern or trim material): the action fails within its approach limit with a readable reason and, for names, nearest-match suggestions; it never interacts through walls or times out silently. → Task 7 `throwAtUnseenPointFails`; Task 2 `lightPortalFailsOnBrokenFrame`; Task 9 `loomRejectsUnknownPattern`.

---

## File Structure

All paths under `AiCiv/src/`. New packages: `dimension`, `nether`, `stronghold`, `dragon`, `vehicles`, `flight`, `items`, `animals`, `stations`.

```
main/java/com/aiciv/
  dimension/DimensionModule.java, Portals.java, Waypoints.java, EnterPortalAction.java      (Task 1)
  actions/Action.java, ActionExecutor.java, BotContext.java                               (Task 1, modify)
  actions/Routine.java                                                                     (Task 1)
  bot/AiPlayer.java, Bot.java, BotManager.java                                             (Task 1, modify)
  world/KnownBlocks.java, MenuOps.java, BlockPlacer.java                                   (Task 1, modify)
  world/Riding.java, Ballistics.java, ItemUse.java, Buckets.java                           (Task 1)
  combat/BowShot.java                                                                      (Task 1)
  storage/ChestMemory.java, trade/VillagerMemory.java                                      (Task 1, modify: per dimension)
  perception/Perceiver.java                                                                (Task 1, modify: labels, dimension key)
  nav/LevelBlockView.java                                                                  (Task 1, modify: portals, obsidian)
  mixin/VehicleControlMixin.java                                                           (Task 1)
  modules/ToolModules.java                                                                 (Task 1, modify: register 9 modules)
  nether/*  (Task 2)   stronghold/* (Task 3)   dragon/*  (Task 4)   vehicles/* (Task 5)
  flight/*  (Task 6)   items/*      (Task 7)   animals/* (Task 8)   stations/* (Task 9)
  building/BlockSpec.java, PlacementRule.java, OrientedPlacer.java (new) + Blueprint, BuildPlan, BuildAction,
  BuildingModule (modify); utility/UseBlockAction.java, UtilityModule.java (modify)        (Task 10)
  gametest/DimensionGameTests, NetherGameTests, StrongholdGameTests, DragonGameTests, VehicleGameTests,
           FlightGameTests, ItemsGameTests, AnimalGameTests, StationGameTests, RedstoneGameTests,
           EndgameGameTests                                                                (created in Task 1; filled by owners)
resources/fabric.mod.json, aiciv.mixins.json                                               (Task 1)
test/java/com/aiciv/...                                                                    unit tests named per task
```

Verified 26.3 APIs (javap on `minecraft-merged-deobf-26.3.jar`, 2026-10-13). **Surprises vs. older versions are marked ⚠.**
- Entity types live in `net.minecraft.world.entity.EntityTypes` ⚠ (`EntityTypes.OAK_BOAT`, `MINECART`, `HORSE`, `BLAZE`, `ENDER_DRAGON`, `END_CRYSTAL`, `EYE_OF_ENDER`, `COW`, `WOLF`…; the existing tests already use it). Boats are per wood: `net.minecraft.world.entity.vehicle.boat.Boat`/`AbstractBoat` ⚠ (package moved); minecarts `net.minecraft.world.entity.vehicle.minecart.AbstractMinecart` ⚠; `MinecartItem.useOn`, `BoatItem.use` (ray from the eyes, places on water or a block).
- **Vehicle authority ⚠:** `Entity.isLocalInstanceAuthoritative()` is `final`: on the server it is `!isClientAuthoritative()`, and `Entity.isClientAuthoritative()` returns `getControllingPassenger().isClientAuthoritative()`; `Player.isClientAuthoritative()` always returns `true` (`ServerPlayer` does not override it). `Entity.getMoveSimulationType()` defaults to `AUTHORITATIVE_SIDE`, so the server never simulates a boat or a saddled horse driven by any player, bots included. `Player` uses `AUTHORITATIVE_SIDE_AND_SERVER` (bots already move). `AbstractBoat.tick` runs `controlBoat` only `if (isLocalInstanceAuthoritative())`; `AbstractHorse.tickRidden` checks it too. Minecarts (`AbstractMinecart` has no controlling passenger) and untamed horses (`AbstractHorse.getControllingPassenger()` is a player only when `isSaddled()`) are server-simulated already. → Task 1's `VehicleControlMixin` makes `Entity.isClientAuthoritative()` return false when the controlling passenger is an `AiPlayer` (`Player`'s override is untouched, so bots themselves are unaffected).
- Riding input: `AbstractBoat.setInput(boolean left, boolean right, boolean up, boolean down)`; `LivingEntity.getRiddenInput(Player, Vec3)` reads the rider's `xxa`/`zza`, `getRiddenRotation` the rider's yaw; `PlayerRideableJumping.onPlayerJump(int)`, `handleStartJump(int)`; `ServerPlayer.setLastClientInput(Input)` / `getLastClientMoveIntent()` ⚠ (minecart pushing reads it: `NewMinecartBehavior` and `OldMinecartBehavior`); `Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint)`, `Input.EMPTY`; `Player.rideTick` stops riding when `wantsToStopRiding()` (= `isShiftKeyDown()`), so a bot must not sneak while riding; `Entity.startRiding(Entity)`, `stopRiding()`, `getVehicle()`, `isPassenger()`.
- Horses: `net.minecraft.world.entity.animal.equine.AbstractHorse` ⚠ (`Horse`, `Donkey`, `Mule`, `Llama`), `isTamed()`, `setTamed(boolean)`, `getMaxTemper()`, `setTemper(int)` (test setup only), `tameWithName(Player)`, `fedFood`; saddles are equipment: `EquipmentSlot.SADDLE` ⚠, `Mob.isSaddled()`; there is no `SaddleItem` (the `Equippable` component equips on interact). `Pig`/`Strider` (`net.minecraft.world.entity.animal.pig.Pig`, `net.minecraft.world.entity.monster.Strider`) steer with `FoodOnAStickItem`; `Camel` (`animal.camel`).
- Portals: `NetherPortalBlock.AXIS`, `getPortalTransitionTime` (80 ticks for survival players); `PortalShape.findEmptyPortalShape(LevelAccessor, BlockPos, Direction.Axis) → Optional<PortalShape>`, `isComplete()`, `MAX_WIDTH = 21`, min 2 × 3 inside; `Entity.setAsInsidePortal`, `portalProcess`, `isOnPortalCooldown()`; `TeleportTransition(ServerLevel, Vec3 pos, Vec3 speed, float yRot, float xRot, Set<Relative>, PostTeleportTransition)`, `TeleportTransition.DO_NOTHING`. **End exit ⚠:** `EndPortalBlock.entityInside` in the End calls `ServerPlayer.showEndCredits()` when `!seenCredits`, which removes the player from the level until the client answers; a bot never answers, so `AiPlayer` keeps `seenCredits = true` and the exit portal then uses the normal `setAsInsidePortal` path to the respawn point. `EndPortalFrameBlock.HAS_EYE`, `FACING`; `Blocks.NETHER_PORTAL`, `END_PORTAL`, `END_GATEWAY`, `END_PORTAL_FRAME`, `CRYING_OBSIDIAN`, `SPAWNER`, `NETHER_BRICKS`, `STONE_BRICKS`; `Level.OVERWORLD/NETHER/END`.
- Eyes: `EnderEyeItem.use` returns `PASS` when looking at an end portal frame, and spawns an `EyeOfEnder` only when `ServerLevel.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, pos, 100, false)` finds one (otherwise no eye flies). `EyeOfEnder.signalTo(Vec3)` aims 12 blocks toward the stronghold and 8 blocks up when it is more than 12 blocks away (`TOO_FAR_DISTANCE`, `TOO_FAR_SIGNAL_HEIGHT`), else straight at it; `target` is private → read the flight from positions.
- Projectiles (for `Ballistics`): thrown items (`SnowballItem`, `EggItem`, `EnderpearlItem`) launch at 1.5 with pitch offset 0 and inaccuracy 1.0 through `Projectile.spawnProjectileFromRotation`; `ThrowablePotionItem` (splash and lingering) at 0.5 with pitch offset −20° ⚠; `ThrowableProjectile.getDefaultGravity()` 0.03, `AbstractThrownPotion` 0.05, `AbstractArrow` 0.05; per tick gravity, then drag ×0.99 in air, then move. `BowItem.getPowerForTime(int)` × 3.0 is the arrow speed. Ender pearls have a 20-tick cooldown (`player.getCooldowns()`), land with `hurtServer(…, 5.0F)`.
- Elytra: `Player.tryToStartFallFlying()`, `startFallFlying()`, `stopFallFlying()`; `LivingEntity.isFallFlying()`, `getFallFlyingTicks()`, `canGlideUsing(ItemStack, EquipmentSlot)`; `DataComponents.GLIDER` ⚠; `FireworkRocketItem.use` boosts a gliding user. The bot glides on the server because players simulate there.
- Dragon: `EnderDragon.head` (public `EnderDragonPart`), `getSubEntities()`, `getPhaseManager().getCurrentPhase().isSitting()`, `getPhase()`, `EnderDragonPhase.SITTING_SCANNING/HOLDING_PATTERN/DYING`, `DATA_PHASE` (synced), `dragonDeathTime`, `getFightOrigin()`; `aiStep` skips phase ticks when `isNoAi()` ⚠ (useful for tests); `EndCrystal` (`net.minecraft.world.entity.boss.enderdragon`), `hurtServer` from an arrow destroys it; `DragonFireball`; dragon breath is an `AreaEffectCloud` whose `getParticle()` is `ParticleTypes.DRAGON_BREATH`, `getRadius()`; `ServerLevel.getDragonFight()`.
- Item use: `BucketItem.use` (ray from the eyes; filling uses `ClipContext.Fluid.SOURCE_ONLY`), `getContent()`; there is no `MilkBucketItem` ⚠ (milk is a `CONSUMABLE` component); `BoneMealItem.useOn`; `FlintAndSteelItem.useOn`, `FireChargeItem.useOn` (fire in an obsidian frame creates the portal through `BaseFireBlock`); `TntBlock` primes on flint and steel; `ShearsItem`; `EmptyMapItem.use` → filled map, `MapItem.getSavedData(ItemStack, Level)`, `MapItemSavedData.centerX/centerZ/scale/locked` (public final); `DataComponents.LODESTONE_TRACKER` (`LodestoneTracker(Optional<GlobalPos>, boolean)`), `MAP_ID`, `POTION_CONTENTS`, `DYE`, `WRITABLE_BOOK_CONTENT`, `WRITTEN_BOOK_CONTENT`, `PROVIDES_TRIM_MATERIAL`, `PROVIDES_BANNER_PATTERNS`.
- Colours ⚠: coloured items are `ColorCollection<Item>` fields: `Items.DYE.pick(DyeColor.RED)`, `Items.BANNER.white()`, `Items.WOOL`, `Items.BED`, `Items.CARPET`; `DyeColor.byName(String, DyeColor)`. Ids such as `red_dye` still resolve through the registry.
- Animals: `TamableAnimal.isTame()`, `tame(Player)`, `getOwnerReference()`, `isOrderedToSit()`; `OwnableEntity.getOwner()`; `Wolf`, `Cat`, `Parrot` (`animal.wolf`, `animal.feline`, `animal.parrot` ⚠) tame in `mobInteract` with their `isFood`/taming item; `Sheep.isSheared()`, `getColor()`, `readyForShearing()` (`Shearable`); `AbstractCow.mobInteract` milks with a bucket, `MushroomCow` stews with a bowl; `Leashable.isLeashed()`, `getLeashHolder()`, `setLeashedTo`, `canBeLeashed()`; `LeadItem.bindPlayerMobs(Player, Level, BlockPos)` (also what clicking a fence does); `LeashFenceKnotEntity` (`entity.decoration`).
- Stations: `SmithingMenu.TEMPLATE_SLOT 0, BASE_SLOT 1, ADDITIONAL_SLOT 2, RESULT_SLOT 3`; `StonecutterMenu.INPUT_SLOT 0, RESULT_SLOT 1`, `getVisibleRecipes()` (`SelectableRecipe.SingleInputSet`), `getNumberOfVisibleRecipes()`, `clickMenuButton(Player, index)`; `GrindstoneMenu.INPUT_SLOT 0, ADDITIONAL_SLOT 1, RESULT_SLOT 2`; `LoomMenu.getBannerSlot()/getDyeSlot()/getPatternSlot()/getResultSlot()`, `getSelectablePatterns() → List<Holder<BannerPattern>>`, `clickMenuButton(Player, index)`; `CartographyTableMenu.MAP_SLOT 0, ADDITIONAL_SLOT 1, RESULT_SLOT 2`. Books: `ServerboundEditBookPacket(int slot, List<String> pages, Optional<String> title)` handled by `ServerGamePacketListenerImpl.handleEditBook` (hotbar slots only; the edit is applied asynchronously on the server executor, i.e. a later tick ⚠).
- Redstone placement: `DiodeBlock` (repeater, comparator) `FACING = context.getHorizontalDirection().getOpposite()`; `PistonBaseBlock` and `DispenserBlock` `FACING = getNearestLookingDirection().getOpposite()`; `ObserverBlock` `FACING = getNearestLookingDirection()`; `HopperBlock` `FACING = clickedFace.getOpposite()` (DOWN for a vertical face); `FaceAttachedHorizontalDirectionalBlock` (lever, buttons) `FACE` from the clicked face, `FACING` = horizontal direction on floor/ceiling, clicked face on a wall; `RepeaterBlock.DELAY` (1..4, a right-click adds 1), `ComparatorBlock.MODE`; `Direction.getApproximateNearest(Vec3)`, `Direction.fromYRot(double)`, `Direction.orderedByNearest(Entity)`.

---

### Task 1: Shared prerequisites

Two commits (Part A, then Part B). Tasks 2–10 start after this task merges.

#### Part A — dimensions, memory, portals, registry stubs

**Files:**
- Create `dimension/DimensionModule.java`, `dimension/Portals.java`, `dimension/Waypoints.java` (pure), `dimension/EnterPortalAction.java`.
- Create stub modules (`name()` plus empty `tools()`, `create` returning `null`): `nether/NetherModule` (`"nether"`), `stronghold/StrongholdModule` (`"stronghold"`), `dragon/DragonModule` (`"dragon"`), `vehicles/VehicleModule` (`"vehicles"`), `flight/FlightModule` (`"flight"`), `items/ItemsModule` (`"items"`), `animals/AnimalsModule` (`"animals"`), `stations/StationsModule` (`"stations"`). Modify `modules/ToolModules.java`: `ALL` gains, after `GearModule`, `new DimensionModule(), new NetherModule(), new StrongholdModule(), new DragonModule(), new VehicleModule(), new FlightModule(), new ItemsModule(), new AnimalsModule(), new StationsModule()` — **never edited again in this milestone**.
- Create game-test classes `DimensionGameTests`, `NetherGameTests`, `StrongholdGameTests`, `DragonGameTests`, `VehicleGameTests`, `FlightGameTests`, `ItemsGameTests`, `AnimalGameTests`, `StationGameTests`, `RedstoneGameTests`, `EndgameGameTests`, each with `moduleIsRegistered` (as in milestone 3; `RedstoneGameTests` checks `"building"`, `EndgameGameTests` checks all nine new names) and add all eleven to `fabric.mod.json`'s `fabric-gametest` list.
- Modify `actions/Action.java`, `actions/ActionExecutor.java`, `actions/BotContext.java`, `bot/Bot.java`, `bot/BotManager.java`, `bot/AiPlayer.java`, `world/KnownBlocks.java`, `storage/ChestMemory.java`, `trade/VillagerMemory.java`, `perception/Perceiver.java`, `nav/LevelBlockView.java`.
- Tests: `test/java/com/aiciv/dimension/WaypointsTest.java`, `PortalsTest.java`, `DimensionModuleTest.java`; update `world/KnownBlocksTest.java`, `storage/ChestMemoryTest.java`, `trade/VillagerMemoryTest.java` (their "forgets on dimension change" cases become "keeps each dimension"), `actions/ActionExecutorTest.java`, `modules/ModuleSyncTest.java` (rule 6); game tests in `DimensionGameTests`.

**Interfaces (produces):**
- `Action`: `default boolean survivesDimensionChange() { return false; }`.
- `ActionExecutor`: `public String dimensionChanged(BotContext ctx, String dimensionShortName)` — no current action → null; current survives → null (it keeps running); else cancel it quietly and return `"failed: <describe>: you went through to <dimension>"` plus ` (skipped: a, b)` for queued actions, clearing the queue, exactly like a failure from `tick`.
- `BotContext`: `default String dimension()` → `player() == null ? "minecraft:overworld" : Portals.key(player().level())`.
- `Bot`: `public String dimension` (full id, e.g. `minecraft:the_nether`), set in `BotManager.join` and `adopt`.
- `BotManager.tickStart`, for a live, unpaused bot, before reflexes: `String now = Portals.key(b.player.level()); if (!now.equals(b.dimension)) { b.dimension = now; b.navigator.stop(); b.breaker.cancel(); b.reflexes.reset(b); b.brain.note(stamp(), "you are now in " + Portals.shortName(now) + " at x y z"); String ended = b.executor.dimensionChanged(b.context, Portals.shortName(now)); if (ended != null) b.brain.actionsEnded(stamp(), ended); }`. `adopt` (respawn) sets `b.dimension` without the event.
- `AiPlayer.tick()`: first statement `seenCredits = true;` with the comment "a bot has no client to watch the credits; with this set the End exit portal sends it home through the normal portal path".
- `Portals` (static): `String key(Level level)` → `level.dimension().identifier().toString()`; `String shortName(String key)` → path after `:` (`minecraft:the_nether` → `the_nether`); `boolean isPortal(BlockState s)` (nether portal, end portal, end gateway); `Predicate<BlockState> ofKind(String kind)` — `nether` → `NETHER_PORTAL`, `end` → `END_PORTAL` or `END_GATEWAY`, `any` → all three; null for other kinds; `String NO_LEVEL = "%s does not exist on this server"`.
- `KnownBlocks`: one store per dimension. `enterDimension(key)` switches the active store (creating it), keeping at most `MAX_DIMENSIONS = 3` stores (the least recently entered is dropped); every other method acts on the active store; `size()` is the active store's. Group caps replace the single log cap: `static String group(String label)` → `"log"` for logs, `"rail"` for `rail|powered_rail|detector_rail|activator_rail`, else the label; `static int cap(String group)` → log 24 (`MAX_LOGS`), `nether_bricks` 8, `obsidian` 16, rail 8, otherwise `Integer.MAX_VALUE`; `see` evicts the oldest entry of the same group beyond its cap, then the oldest overall beyond capacity.
- `ChestMemory` and `VillagerMemory`: same per-dimension stores (≤ 3) instead of clearing; all existing methods act on the active store.
- `Perceiver`: `known.enterDimension(Portals.key(level))` (was `level.dimension().toString()`, a different key from the other memories); `UTILITY` gains `nether_portal, end_portal, end_gateway, end_portal_frame, spawner, obsidian, crying_obsidian, nether_bricks, rail, powered_rail, detector_rail, activator_rail, smithing_table, stonecutter, grindstone, loom, cartography_table, lodestone` (they then appear under "Blocks nearby" and in known blocks; Milestone 5's `endgame.json` relies on `nether_portal`, `nether_bricks`, `end_portal_frame`).
- `LevelBlockView`: `dangerous` also for `NETHER_PORTAL`, `END_PORTAL`, `END_GATEWAY` (paths never step into a portal; only `EnterPortalAction` does, by direct movement); `breakSeconds` `+∞` for `OBSIDIAN`, `CRYING_OBSIDIAN`, `END_PORTAL_FRAME` (navigation never tunnels through a portal frame; `mine obsidian` still works).
- `Waypoints` (pure, per-bot attachment `ctx.attachments().get(Waypoints.class, Waypoints::new)`): kinds `PORTAL = "portal"`, `FORTRESS = "fortress"`, `BLAZE_SPAWNER = "blaze_spawner"`, `STRONGHOLD = "stronghold"`, `END_PORTAL = "end_portal"`, `EXIT_PORTAL = "exit_portal"`, `ARRIVAL = "arrival"`; `record Waypoint(String kind, String dimension, Pos pos, String note, long tick)`; `CAPACITY = 32`; `void mark(String kind, String dimension, Pos pos, String note, long tick)` (a same-kind waypoint in the same dimension within 8 blocks is replaced; past capacity the oldest goes); `Optional<Waypoint> nearest(String kind, String dimension, Pos from)`; `List<Waypoint> in(String dimension)`; `void forget(String kind, String dimension, Pos pos)` (same-kind within 8); `List<String> describe(String dimension, Pos from, int n)` → `"portal at 10 64 3 (12m N), to the_nether"` (`<kind> at x y z (<d>m <dir>)` plus `", " + note` when the note is non-empty; nearest first; `Compass.direction`).
- `EnterPortalAction(String kind)` (`survivesDimensionChange() = true`; timeout 1200 ticks; `describe()` = `enter <kind> portal`, after DONE the summary):
  1. Unknown kind → (factory) `Invalid "kind must be nether, end or any"`.
  2. Target: nearest block of the kind within 16 from `Finder.visibleStates`; else the nearest `portal`/`exit_portal`/`end_portal` waypoint of this dimension within 128 → `Approach.block(waypoint, 4.0)` then look again; none → `FAILED "no <kind> portal in sight within 16 blocks"`.
  3. Destination check: `server.getLevel(...)` for the portal's destination (nether portal: overworld ↔ nether; end portal: overworld → end, end → overworld) null → `FAILED String.format(NO_LEVEL, short)`.
  4. Stand cell: a cell horizontally next to a portal block of the target's connected group, at the portal block's y (nether) or one above the portal's y (end portal: the frame rim), with passable feet and head and a standable block below; nearest first; walk there with `NearGoal(centre, 0.8)`; navigator FAILED 3 times → `FAILED <reason>`.
  5. Step in: face the portal block's centre (`p.lookAt(EntityAnchorArgument.Anchor.EYES, centre)`), `zza = 1`, jump on horizontal collision, ≤ 40 ticks until a portal block is in the bot's feet or head cell; then `stopMoving()`.
  6. Wait ≤ 300 ticks for `Portals.key(p.level())` to change (the nether portal timer is 80 ticks); pushed out (no portal block in feet/head) → step in again, at most 3 times; then `FAILED "the portal did not take you (stood in it 300 ticks)"`.
  7. Arrived: mark in the old dimension `PORTAL` at the entered portal block with note `to <new short>` (for an End exit: `EXIT_PORTAL`, note `to overworld`); mark in the new dimension `PORTAL` at the nearest portal block within 4 of the bot with note `to <old short>`, or `ARRIVAL` at the feet (End platform, respawn point) with note `from <old short>`; step out: if a portal block is in the feet cell, walk ≤ 40 ticks along the bot's facing until it is not; DONE, summary `went through to the_nether; you are at x y z`.
  `cancel` / `interrupted`: `stopMoving()`, navigator stop.
- `DimensionModule` (`"dimension"`): tool `enter_portal` [enterportal] (`kind`: `"nether, end or any (default any)"`, optional). Description: `Walk into the nearest nether portal, end portal or end gateway you can see and wait until it takes you to the other dimension.` Guide line: `Dimensions: enter_portal walks through a portal you can see. Waypoints lists the portals, fortress and stronghold you know in this dimension.`
  - Sections: `Dimension` — items `[shortName]` only outside the overworld, emptyWord null, tags `["dim:<short>"]` always; `Waypoints` — `Waypoints.describe(dimension, feet, 6)`, emptyWord null, tags `waypoint:<kind>` for each listed.
  - Menu options: `enter_nether_portal` (a `nether_portal` sighting ≤ 16) → `enter_portal{kind: "nether"}`; `enter_end_portal` (an `end_portal` or `end_gateway` sighting ≤ 16) → `enter_portal{kind: "end"}`. Sample: a `nether_portal` sighting at 5 m.
- `ModuleSyncTest` rule 6 (new): for modules named `dimension, nether, stronghold, dragon, vehicles, flight, items, animals, stations`, every function description ≤ 200 chars and every parameter `description` ≤ 100 chars.

**Tests (Part A):**
- `WaypointsTest`: `mark` twice within 8 blocks keeps one (the newer note); capacity 32 drops the oldest; `nearest` filters by kind and dimension; `describe` exact text `portal at 10 64 3 (12m N), to the_nether` for a bot at `10 64 15`; `forget`.
- `PortalsTest`: `shortName("minecraft:the_nether") == "the_nether"`, `shortName("overworld") == "overworld"`.
- `KnownBlocksTest.keepsEachDimension` (replaces the clearing case): see iron_ore in overworld → enter `minecraft:the_nether` → `nearest` empty, `size() == 0` → enter overworld → the iron_ore is back; a fourth dimension drops the least recently entered store; `capsNetherBricks`: 12 nether_bricks sightings keep 8 and evict the oldest first, while an ore seen before them stays.
- `ChestMemoryTest.keepsEachDimension`, `VillagerMemoryTest.keepsEachDimension`: same shape.
- `ActionExecutorTest`: `dimensionChangeFailsTheAction` (a running fake action plus one queued → summary `failed: <a>: you went through to the_nether (skipped: <b>)`, the action's `cancel` ran, `busy()` false); `portalActionSurvivesDimensionChange` (a fake action returning `survivesDimensionChange() = true` → null and it keeps ticking).
- `DimensionModuleTest`: `enter_portal` with `{kind: "lava"}` → `Invalid` containing `kind must be`; menu has `enter_nether_portal` with a sighting and nothing without.
- Game (`DimensionGameTests`):
  - `entersNetherPortalAndArrives` (`padding = 24`, maxTicks 800): obsidian frame 4 wide × 5 high with axis X at relative x 3..6, z 6, y 1..5, interior filled with `NETHER_PORTAL` (`AXIS` X) by `setBlock`; bot at (4.5, 1, 2.5) → `EnterPortalAction("nether")` DONE within 600 ticks; `bot.player.level().dimension() == Level.NETHER`; the bot's `Waypoints` has a `portal` in `minecraft:overworld` within 3 of (4, 2, 6) with note `to the_nether` and a `portal` in `minecraft:the_nether`; the decision events contain `you are now in the_nether`. (Missing nether → the fallback assertion in Global Constraints.)
  - `actionFailsWhenBotChangesDimension`: a running `GoToAction` 20 blocks away; the test moves the bot with `bot.player.teleport(new TeleportTransition(nether, pos, Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING))` (test harness only) → within 2 ticks the executor is idle and the brain's events contain `you went through to the_nether`; `navigator.status()` is not RUNNING.
  - `navigationNeverEntersPortal`: a 2-wide corridor 10 long whose left lane holds one nether-portal cell (frame around it), bot `goTo` the far end → ARRIVED and the dimension never changed (checked every tick).
  - `aiPlayerHasSeenCredits`: after one tick `bot.player.seenCredits` is true.

- [ ] Write the failing tests; run `./gradlew test --no-daemon` and see them fail.
- [ ] Implement Part A; `./gradlew test runGametest --no-daemon` passes (all earlier tests too).
- [ ] Commit `"Keep memories per dimension, fail actions on a dimension change, and walk through portals"` with the trailer.

#### Part B — riding, projectiles, buckets, routines, menus

**Files:**
- Create `world/Riding.java`, `world/Ballistics.java` (pure), `world/ItemUse.java`, `world/Buckets.java`, `combat/BowShot.java`, `actions/Routine.java`, `mixin/VehicleControlMixin.java`.
- Modify `resources/aiciv.mixins.json` (add `"VehicleControlMixin"`), `world/MenuOps.java` (add `putInto`, `takeResult`), `world/BlockPlacer.java` (add `placeAgainst`).
- Tests: `test/java/com/aiciv/world/BallisticsTest.java`, `test/java/com/aiciv/actions/RoutineTest.java`; game tests in `DimensionGameTests`.

**Interfaces (produces):**
- Mixin (no helper classes in the mixin package):

```java
@Mixin(Entity.class)
abstract class VehicleControlMixin {
    /** A vehicle trusts its driver's client; a bot has none, so the server drives a vehicle whose driver is a bot. */
    @Inject(method = "isClientAuthoritative", at = @At("HEAD"), cancellable = true)
    private void aiciv$serverDrivesBotVehicles(CallbackInfoReturnable<Boolean> cir) {
        if (((Entity) (Object) this).getControllingPassenger() instanceof AiPlayer) cir.setReturnValue(false);
    }
}
```
- `Riding` (static): `boolean selectEmptyHand(AiPlayer p)` (select an empty hotbar slot; else move the selected hotbar stack into an empty main slot 9..35 and keep that slot selected; false when the inventory has no empty slot); `BlockUse.Result mount(AiPlayer p, Entity vehicle)` (`selectEmptyHand` false → `NO_EFFECT`; then `EntityUse.interact(p, vehicle, MAIN_HAND)`; `USED` iff `p.getVehicle() == vehicle`); `void clearInput(AiPlayer p)` (`xxa = zza = 0`, `setJumping(false)`, `setLastClientInput(Input.EMPTY)`, and `setInput(false,false,false,false)` when the vehicle is an `AbstractBoat`); `void dismount(AiPlayer p)` (`clearInput`, then `p.stopRiding()`, which is what the server does for a sneaking client); `void pushInput(AiPlayer p, boolean forward, boolean left, boolean right, boolean jump)` (`setLastClientInput(new Input(forward, false, left, right, jump, false, false))`, `zza = forward ? 1 : 0`, `xxa = left ? 1 : right ? -1 : 0`); `static float yawTo(double dx, double dz)` → `(float) Math.toDegrees(Math.atan2(dz, dx)) - 90f` (south 0, west 90, north ±180, east −90); `void faceYaw(AiPlayer p, float yaw)` (`setYRot`, `setYHeadRot`, `setYBodyRot`).
- `Ballistics` (pure): `enum Kind { THROWN(1.5, 0.03, 0f), POTION(0.5, 0.05, -20f), ARROW(3.0, 0.05, 0f) }` with `speed()`, `gravity()`, `pitchOffset()`; `DRAG = 0.99`, `MAX_TICKS = 200`, `TOLERANCE = 0.5`; `static double[] simulate(double speed, double gravity, double elevationDeg, int ticks)` → `{horizontal, dy}` after `ticks` (per tick: `vy -= gravity; vx *= DRAG; vy *= DRAG; x += vx; y += vy`); `static OptionalDouble pitch(Kind kind, double speed, double horizontal, double dy)` → the **look** pitch in Minecraft degrees (negative = up) for the low arc whose path passes within `TOLERANCE` of `(horizontal, dy)`: find the elevation `e` in [−60°, eMax] (eMax = the elevation of maximum range at that `dy`, scanned in 1° steps) by bisection on "height when the horizontal distance is reached", then `lookPitch = -e - kind.pitchOffset()` (a potion is thrown 20° above where the player looks, so the look is 20° lower); empty when the target is beyond `maxRange`; `static double maxRange(Kind kind, double speed, double dy)`.
- `ItemUse` (static): `boolean select(AiPlayer p, Predicate<ItemStack> item)` (`InventoryOps.selectBest`); `InteractionResult useFacing(AiPlayer p, float yaw, float pitch)` (`Riding.faceYaw`, `setXRot(pitch)`, `gameMode.useItem(p, level, mainHand, MAIN_HAND)`); `Optional<float[]> aimThrow(AiPlayer p, Ballistics.Kind kind, Vec3 target)` → `{yaw, pitch}` from the eyes, empty when out of range.
- `Buckets` (static): `BlockUse.Result fill(AiPlayer p, BlockPos source)` — needs an empty `bucket` (`NO_EFFECT` otherwise); the cell must hold a fluid source; within reach of the eyes; a ray with `ClipContext.Fluid.SOURCE_ONLY` from the eyes to the source's centre must stop in that cell (`NOT_VISIBLE` otherwise); select the bucket, look at the centre, `useItem`; `USED` iff a `water_bucket` or `lava_bucket` count went up. `BlockUse.Result pour(AiPlayer p, BlockPos cell, Item filledBucket)` — cell replaceable or a fluid; choose a solid neighbour face exactly as `BlockPlacer.place` does (visible, in reach), look at that face's centre, `useItem`; `USED` iff the cell now holds that fluid's source.
- `BowShot` (`combat`): `BowShot(Supplier<Vec3> target, int chargeTicks)` (clamped 1..30); `enum State { DRAWING, RELEASED, FAILED }`; `State tick(AiPlayer p)` — first tick: needs `Items.BOW` and any `ItemTags.ARROWS` item (`FAILED`, `reason()` `no bow` / `no arrows`), select the bow, `gameMode.useItem(..., MAIN_HAND)`, `!isUsingItem()` → `FAILED "could not draw the bow"`; every tick aim with `Ballistics.pitch(ARROW, BowItem.getPowerForTime(chargeTicks) * 3.0, d, dy)` from the eyes (empty → `FAILED "out of bow range"`); at `getTicksUsingItem() >= chargeTicks` → `releaseUsingItem()`, `RELEASED`. `String reason()`; `void cancel(AiPlayer p)` (`stopUsingItem()` if using).
- `Routine` (`actions`, abstract, implements `Action`):

```java
public abstract class Routine implements Action {
    /** The next stage, or null when the routine is done. Called first and after each stage ends DONE. */
    protected abstract Action nextStage(BotContext ctx);
    /** A stage failed (or timed out); return a replacement to retry, or null to fail the routine. */
    protected Action retry(BotContext ctx, Action failed, String reason) { return null; }
    /** Shown by describe() once the routine is done; null keeps name(). */
    protected String summary() { return null; }
    protected abstract String name();
    public final ActionStatus tick(BotContext ctx);   // runs the current stage with its own timeoutTicks()
    public void cancel(BotContext ctx);               // cancels the current stage
    public void interrupted(BotContext ctx);          // forwards to the current stage
    public boolean survivesDimensionChange();         // the current stage's answer; false between stages
    protected final int stagesDone();
}
```
  A failing stage with no retry fails the routine with `"<stage.describe()>: <reason>"`; a stage over its own timeout is cancelled and counts as failing with `timed out`; `describe()` is `summary()` after DONE, else `name()`. Each routine sets its own `timeoutTicks()`.
- `MenuOps.putInto(AiPlayer p, AbstractContainerMenu menu, Predicate<ItemStack> match, int slot, int count)` → items moved (pick up a matching inventory stack, left-click the target slot when `count` ≥ the stack, else right-click once per item, put the rest back; never leaves anything carried); `ItemStack takeResult(AiPlayer p, AbstractContainerMenu menu, int slot)` (one `PICKUP` click on the result slot, which runs the menu's `onTake`, then `InventoryOps.addOrDrop` the carried stack; returns a copy of what was taken, `EMPTY` if nothing).
- `BlockPlacer.placeAgainst(AiPlayer p, BlockPos target, Direction side, Predicate<ItemStack> item)` → `PlaceResult`: like `place` but only against the neighbour `target.relative(side)`, clicking its face `side.getOpposite()`; `NO_SUPPORT` if that neighbour is replaceable.

**Tests (Part B):**
- `BallisticsTest`: for `THROWN` at (10, 0) and (25, 3) and `POTION` at (6, 0), simulating the returned look pitch (adding the offset back) passes within 0.5 of the target; `THROWN` (200, 0) → empty; `maxRange(THROWN, 1.5, 0)` is between 40 and 70; the potion's look pitch is exactly 20° lower (larger) than the launch pitch; `simulate(1.5, 0.03, 0, 1)` equals `{1.485, -0.0297}` within 1e-9.
- `RoutineTest` (with `FakeContext` and in-test stage actions): two DONE stages → routine DONE and `stagesDone() == 2`; a failing stage → FAILED `stage-b: broke`; `retry` returning a new stage → it runs and the routine succeeds; a stage that never ends with `timeoutTicks() == 5` → after 5 ticks the routine fails with `stage-c: timed out`; `cancel` reaches the running stage; `survivesDimensionChange` follows the current stage.
- Game (`DimensionGameTests`):
  - `vehicleIsServerDrivenForBots` (`padding = 24`): water pool 10 × 3 × 1 (stone basin); an oak boat spawned on it; `Riding.mount` → USED; 40 ticks of `boat.setInput(false, false, true, false)` → the boat moved ≥ 2 blocks; `Riding.dismount` → `!isPassenger()`.
  - `bowShotHitsZombie`: zombie (no free will) 10 blocks away, bot with bow + 3 arrows → `BowShot(() -> zombie centre, 20)` RELEASED within 40 ticks; within 60 more ticks the zombie's health < max and arrows == 2.
  - `thrownSnowballLandsNearTarget` (`padding = 24`): target point on the floor 12 blocks east; `ItemUse.aimThrow` + `useFacing` with a snowball; the snowball's last position before removal is within 1.5 blocks of the target (tracked in `afterTick`).
  - `bucketFillsAndPours`: water source in a 1-block pit 2 blocks away; `Buckets.fill` → USED, holds `water_bucket`; `Buckets.pour(cell 2 blocks the other way)` → USED, that cell is a water source, holds `bucket`.
  - `placeAgainstChoosesTheSide`: a torch placed with `placeAgainst(target, NORTH, torch)` next to a stone block on the north → a `wall_torch` facing south.
  - `putIntoFillsChosenSlot`: chest; bot holds 10 cobblestone → `putInto(..., slot 5, 3)` → chest slot 5 holds 3, bot 7; nothing carried.

- [ ] Write the failing tests; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Ride, throw, shoot, pour and run routines: shared hands for milestone 4"` with the trailer.

---

### Task 2: Portals, the nether, fortresses and blazes

**Files:** Create in `main/java/com/aiciv/nether/`: `PortalFrame.java` (pure), `BuildPortalAction.java`, `CastPortalAction.java`, `LightPortalAction.java`, `GoToNetherRoutine.java`, `LeaveNetherRoutine.java`, `FortressSearch.java` (pure), `FindFortressAction.java`, `HuntBlazesAction.java`, `GetBlazeRodsRoutine.java`; fill `NetherModule.java`. Tests `test/java/com/aiciv/nether/PortalFrameTest.java`, `FortressSearchTest.java`, `NetherModuleTest.java`; game tests in `gametest/NetherGameTests.java` (prefix `TestNeth`).

**Interfaces:**
- `PortalFrame` (pure): the vertical 4 × 5 frame. `static Blueprint blueprint(boolean obsidianCorners)` → 5 layers (bottom → top) of one row each: `"cooc"`, `"o..o"`, `"o..o"`, `"o..o"`, `"cooc"` with legend `o → obsidian`, `c → obsidian` or `cobblestone`; `static List<Pos> frameCells(Pos bottomLeft, Facing facing)` (the 10 obsidian cells in cast order: bottom row left → right, left column bottom → top, right column bottom → top, top row left → right; `bottomLeft` is the left corner as the builder sees it, columns run along `facing.right`), `static List<Pos> corners(Pos bottomLeft, Facing facing)`, `static List<Pos> interior(Pos bottomLeft, Facing facing)` (the 2 × 3 air cells), `static String cornerBlock(Map<String,Integer> inventory)` → `obsidian` when ≥ 14 obsidian, else the throwaway (`cobblestone`, `cobbled_deepslate`, `dirt`, `netherrack`, then any `_planks`) with ≥ 4 held, else null.
- `BuildPortalAction(BlockPos originOrNull, Facing facingOrNull)`: obsidian < 10 → `FAILED "needs 10 obsidian (you have <n>)"`; `cornerBlock` null → `FAILED "needs 4 more obsidian, or 4 cobblestone, dirt or planks for the corners"`; runs a child `BuildAction("portal_<x>_<z>", blueprint, origin, facing, "portal")` (negative numbers written `m12`, as milestone 3's menu names do) (origin default: the BuildAction rule, 2 blocks in front and centred); then, if the bot holds `flint_and_steel` or `fire_charge`, a child `LightPortalAction` on the built frame, else event `frame built at x y z; light it with light_portal`. DONE summary `built a nether portal at x y z (lit)` / `(not lit)`. Timeout = the build's + 600.
- `CastPortalAction(BlockPos originOrNull, Facing facingOrNull)`: needs `water_bucket` and (`lava_bucket` or an empty `bucket`), and 4 corner blocks (`cornerBlock` minus the obsidian rule); a lava source within 16 blocks of the site must be visible when no `lava_bucket` is held (`FAILED "needs a lava_bucket or lava within 16 blocks"`); site cells (frame, corners, interior) must be air or natural replaceable plants (else `FAILED "the portal site is not clear at x y z"`). Rules: (1) place the 4 corners with `BlockPlacer.place`; (2) for each of `frameCells` in order: if no `lava_bucket`, walk to the lava source (`Approach.block(source, 4.0)`) and `Buckets.fill`; `Approach.block(cell, 4.0)`; `Buckets.pour(cell, LAVA_BUCKET)`; next tick `Buckets.pour(cell.above(), WATER_BUCKET)`; next tick the cell must be `obsidian` (cobblestone → `FAILED "the cast went wrong at x y z (cobblestone)"`); `Buckets.fill(cell.above())` to take the water back (the source has not spread yet: water waits 5 ticks); (3) light as above. Timeout 9600 ticks.
- `LightPortalAction(BlockPos frameBlockOrNull)`: igniter = `flint_and_steel` or `fire_charge` (else `FAILED "needs flint_and_steel or a fire_charge"`); candidates = visible `obsidian` within 16 (or the given block) with air above for which `PortalShape.findEmptyPortalShape(level, pos.above(), Axis.X or Z)` is present; none → `FAILED "no complete empty portal frame in sight (a frame is 4 wide and 5 tall)"`; `Approach.block(pos, 4.0)`; select the igniter; `BlockUse.clickFace(p, pos, Direction.UP)`; DONE when a `nether_portal` block is at `pos.above()` within 5 ticks (summary `lit the portal at x y z`), else `FAILED "the fire went out: the frame is not a complete portal"`. Timeout 600.
- `GoToNetherRoutine` (`Routine`): if a `nether_portal` is visible within 32 or a `portal` waypoint within 128 → stage `EnterPortalAction("nether")`; else a visible complete empty frame → `LightPortalAction` then enter; else `BuildPortalAction(null, null)` (which lights) then enter. In the_nether already → `FAILED "you are already in the_nether"`. Timeout 36000.
- `LeaveNetherRoutine`: needs the_nether; target = nearest `portal` waypoint in the_nether (any distance) or a visible portal; stages `Approach`-based walk (as a small inner action `WalkToAction(BlockPos, double)` in this package: navigator `NearGoal`, DONE on arrival, FAILED on 3 navigator failures) then `EnterPortalAction("nether")`; none → `FAILED "no known portal in the_nether; build one with build_portal"`. Timeout 36000.
- `FortressSearch` (pure): `static List<int[]> spiral(int startX, int startZ, String firstDirection, int legLength, int budget)` → waypoints of a square spiral (legs `L, L, 2L, 2L, 3L, 3L…` turning clockwise from `firstDirection`) until the summed length reaches `budget`; `static boolean isFortressBlock(String id)` (`nether_bricks`, `nether_brick_fence`, `nether_brick_stairs`, `nether_brick_slab`, `nether_brick_wall`, `cracked_nether_bricks`, `chiseled_nether_bricks`).
- `FindFortressAction(String directionOrNull, int distance)` (+ package-private `(…, boolean anyDimension)`): the_nether check; every 20 ticks scan `Perceiver.exposedBlocks(level, feet, eyes, 24, FortressSearch::isFortressBlock)`; a hit (or a `nether_bricks` known block within 64) → `Approach.block(hit, 6.0)`, mark `FORTRESS` at the hit, DONE `found a nether fortress at x y z`; otherwise walk the spiral legs (default first direction = the one of N/E/S/W with the most passable cells in the 16 blocks ahead at eye height, `legLength 64`) with `ColumnGoal(x, z, 6)` legs; a leg whose navigation fails twice is skipped; budget spent → `FAILED "no fortress within <distance> blocks; try another direction"`. Timeout `min(24000, 40 × distance + 600)`.
- `HuntBlazesAction(int count)` (+ `anyDimension`): the_nether check; goal = `count` more `blaze_rod` than at start. Loop: target = nearest `Blaze` within 32 with line of sight (`Finder.nearestEntity`); a visible `spawner` within 24 whose display entity (`SpawnerBlockEntity.getSpawner().getOrCreateDisplayEntity(level, pos)`, what the client shows spinning) is a blaze → mark `BLAZE_SPAWNER`; with a target: distance > 6 and bow + arrows → `BowShot(() -> blaze centre, 20)`; else a child `AttackAction.on(blaze)`; after a kill, gather `blaze_rod` item entities within 8 with `ItemGatherer` (≤ 100 ticks); without a target: walk to within 6 of the spawner waypoint and wait; 600 ticks without seeing a blaze → DONE if any rods were gained (`got 3 of 6 blaze rods; no more blazes came`), else `FAILED "no blazes in sight"`. Health ≤ 8 → stop and `FAILED "too hurt to keep fighting blazes (health <n>)"`. DONE `got 6 blaze rods`. Timeout 6000 + 1200 × count.
- `GetBlazeRodsRoutine(int count)`: `FindFortressAction(null, 384)` unless a `fortress` waypoint within 128 or `nether_bricks` within 24 is known; then `HuntBlazesAction(count)`. Timeout 36000.

**Tools (NetherModule):** `build_portal` [buildportal] (`x`,`y`,`z`, `facing` optional); `cast_portal` [castportal] (same); `light_portal` [lightportal] (`x`,`y`,`z` optional: a bottom obsidian block); `go_to_nether` [gotonether]; `leave_nether` [leavenether]; `find_fortress` [findfortress] (`direction` N/E/S/W optional, `distance` 64..1024); `hunt_blazes` [huntblazes] (`count` 1..16); `get_blaze_rods` [getblazerods] (`count` 1..16). Validation: `Args.posProblem`, `facing must be N, E, S or W`, `direction must be N, E, S or W`. Guide line: `Nether: build_portal (10+ obsidian, lights it with flint_and_steel), go_to_nether, find_fortress, hunt_blazes or get_blaze_rods, leave_nether. Each routine is also its steps.`

**Snapshot section:** label `Nether`, only in the_nether: `"fortress at x y z (dm dir)"` from the `fortress` waypoint, `"blaze spawner at …"`, `"blaze rods: n"`; tags `fortress_known`, `blaze_spawner_known`, `in_nether`.

**Menu options:** `go_to_nether` (overworld, and (≥ 10 obsidian and an igniter held, or a `nether_portal` sighting ≤ 32)); `find_fortress` (`in_nether`, no `fortress_known`) → `find_fortress{}`; `hunt_blazes` (`fortress_known` or `blaze_spawner_known`) → `hunt_blazes{count: 6}`; `leave_nether` (`in_nether` and ≥ 6 `blaze_rod` held). Sample: overworld, 14 obsidian, flint_and_steel.

**Tests:**
- `PortalFrameTest`: the blueprint has 10 `o` cells, 4 `c` cells, 6 `.` cells; `frameCells(new Pos(0,0,0), SOUTH)` starts with the bottom row `(-1,0,0), (-2,0,0)` (the corner `(0,0,0)` is not in it; columns run along `right` = −x for south), has 10 distinct cells and contains none of `corners(...)`; `cornerBlock`: 14 obsidian → obsidian, 10 obsidian + 4 dirt → dirt, 10 obsidian alone → null.
- `FortressSearchTest`: spiral from (0,0) east, leg 64, budget 384 → `(64,0), (64,64), (-64,64), (-64,-64)` and stops when the next leg would pass 384 (the last point is cut to the budget); `isFortressBlock("nether_brick_fence")` true, `"netherrack"` false.
- `NetherModuleTest`: validation messages; menu `go_to_nether` at 14 obsidian + flint, not at 9 obsidian.
- Game (`padding = 24`, stone floor 16 × 16):
  - `buildsAndLightsPortalFromObsidian` (maxTicks 2400): bot holds 14 obsidian and flint_and_steel → `build_portal{x,y,z = abs(4,1,8), facing: "S"}` DONE; the 14 frame and corner cells are obsidian; 6 `nether_portal` blocks; a structure `portal_<x>_<z>` is registered.
  - `buildPortalFailsWithoutEnoughObsidian` (Review Focus 2): 9 obsidian → FAILED within 5 ticks containing `needs 10 obsidian (you have 9)`; no block changed.
  - `lightsPortal`: frame built with `setBlock`; flint_and_steel → 6 portal blocks; the flint's damage ≥ 1.
  - `lightPortalFailsOnBrokenFrame` (Review Focus 5): the same frame missing its top-right obsidian → FAILED containing `no complete empty portal frame`.
  - `castsPortalFromBuckets` (maxTicks 9600): a 3 × 3 lava pool (stone-lined) 8 blocks away; bot holds 1 water_bucket, 1 bucket, 4 cobblestone → `cast_portal{…}` DONE; 10 frame cells obsidian; interior air (no water or lava cells inside the frame); bot holds a water_bucket again.
  - `findsFortressBricks`: `FindFortressAction("E", 64, true)` with a 3 × 3 nether_bricks wall 18 blocks east → DONE within 600 ticks; `Waypoints` has a `fortress` within 3 of the wall.
  - `huntsBlazesWithSword`: 3 blazes (no free will) within 5, bot with a diamond_sword and 20 health → `HuntBlazesAction(1, true)` ends (DONE or FAILED `no blazes in sight`); all blazes dead; no `blaze_rod` item entity left within 8 (every dropped rod was picked up).

- [ ] Tests first (unit + game); implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Build, cast and light nether portals, find fortresses and hunt blazes"` with the trailer.

---

### Task 3: Eyes of ender, the stronghold and the End portal

**Files:** Create in `main/java/com/aiciv/stronghold/`: `Triangulate.java` (pure), `EyeReadings.java` (pure attachment), `StrongholdBlocks.java` (pure), `Frontier.java` (pure), `MakeEyesRoutine.java`, `EyeWatcher.java`, `ThrowEyeAction.java`, `LocateStrongholdRoutine.java`, `DigToStrongholdAction.java`, `FindPortalRoomAction.java`, `GoToStrongholdRoutine.java`, `FillEndPortalAction.java`, `GoToEndRoutine.java`; fill `StrongholdModule.java`. Tests `test/java/com/aiciv/stronghold/TriangulateTest.java`, `EyeReadingsTest.java`, `FrontierTest.java`, `StrongholdModuleTest.java`; game tests in `gametest/StrongholdGameTests.java` (prefix `TestHold`).

**Interfaces:**
- Bearings are degrees clockwise from north: direction vector `(sin b, −cos b)` in (x, z).
- `Triangulate.intersect(double x1, double z1, double b1, double x2, double z2, double b2)` → `Optional<double[]>` `{x, z}`; empty when `|sin(b2 − b1)| < sin(5°)` or the point lies behind either thrower; `static double bearing(double dx, double dz)` → `[0, 360)`.
- `EyeReadings` (attachment): `record Reading(double x, double z, double bearing, boolean sank, long tick)`; capacity 4 (oldest dropped); `void add(Reading)`; `List<Reading> all()`; `Optional<double[]> estimate()` → a `sank` reading's position if any, else the intersection of the pair with the widest angle between bearings.
- `StrongholdBlocks.is(String id)`: `stone_bricks`, `mossy_stone_bricks`, `cracked_stone_bricks`, `chiseled_stone_bricks`, `infested_*` versions, `iron_bars`, `end_portal_frame`, `oak_door`/`iron_door` are not counted.
- `Frontier` (pure): `record Cell(int x, int y, int z)`; `Frontier(int bucket)` (bucket 4: positions are grouped into 4 × 4 × 4 cells); `void visit(Pos p)`; `Optional<Pos> next(Pos from, List<Pos> openAir)` → the nearest candidate whose bucket is unvisited, preferring candidates at the same y ± 2; `boolean visited(Pos p)`.
- `EyeWatcher` (observes a thrown eye fairly: positions only): `static EyeWatcher start(AiPlayer p, Set<UUID> eyesBefore)`; `enum State { WAITING, READ, NONE }`; `State tick(AiPlayer p)` — finds a new `EyeOfEnder` within 4 blocks of the bot's eyes within 5 ticks (none → `NONE`); records its position for 30 ticks; `READ` when 30 samples exist or the eye is removed; `Reading reading()` with `bearing = bearing(pos[last] − pos[2])` and `sank = pos[last].y < pos[2].y − 0.5` (an eye that sinks is at the stronghold); `Optional<Vec3> dropPos()` (last position: where a surviving eye drops).
- `ThrowEyeAction()` (+ `anyDimension`): overworld check; needs `ender_eye`; look at the horizon (`pitch −10`, current yaw); `EyeWatcher.start`, then `ItemUse.useFacing`; `NONE` → `FAILED "the eye does not fly here: no stronghold in this dimension"` (and `stopUsingItem()`); `READ` → `EyeReadings.add`; gather an `ender_eye` item entity within 3 of `dropPos` with `ItemGatherer` (≤ 120 ticks); DONE summary `the eye flew toward 213° (SW) from x z` or `the eye sank at x y z: the stronghold is right below`, plus ` (the eye broke)` when no eye item appeared. Timeout 400.
- `MakeEyesRoutine(int count)`: needs `count` ender pearls (`FAILED "needs <n> more ender_pearl"`) and enough powder (`powder = held blaze_powder + 2 × blaze_rod`; short → `FAILED "needs <n> more blaze_rod"`); stages: `CraftAction("blaze_powder", rodsNeeded)` when powder short, then `CraftAction("ender_eye", count)`. Timeout 2400.
- `LocateStrongholdRoutine(int spread)`: stage 1 `ThrowEyeAction`; stage 2 `ExploreAction(dir, spread)` where `dir` = the 8-point compass direction of `bearing + 90`; stage 3 `ThrowEyeAction`; if `EyeReadings.estimate()` empty → one more explore + throw (max 3 throws); then mark `STRONGHOLD` at the estimate (y = 30, note `estimate from 2 eyes`), DONE `the stronghold is about <d> blocks <dir> at x z`. Timeout 12000.
- `DigToStrongholdAction(int minY)` (+ `anyDimension`): repeatedly `navigator.goTo(new NearGoal(x + 0.5, y − 8, z + 0.5, 2.0))` (the navigator digs a staircase and never enters lava); every 20 ticks scan visible blocks within 16 for `StrongholdBlocks.is` → DONE `reached the stronghold at x y z`; feet y ≤ `minY` → `FAILED "dug to y <y> without finding the stronghold"`. Timeout 6000.
- `FindPortalRoomAction(int radius)` (+ `anyDimension`): every 20 ticks: a visible `end_portal_frame` within 24 → `Approach.block(frame, 5.0)`, mark `END_PORTAL` at the ring's centre (mean of visible frames), DONE `found the end portal room at x y z`; a visible `spawner` showing a `silverfish` also counts as the room; otherwise `Frontier.visit(feet)` and walk to `Frontier.next(feet, openAir)` where `openAir` = air cells within 16 whose neighbour is a `StrongholdBlocks` block (from `Finder.visibleStates`); nothing left → `FAILED "explored the stronghold nearby and found no portal room"`. Timeout 6000.
- `GoToStrongholdRoutine`: needs a `stronghold` waypoint (`FAILED "no stronghold estimate; use locate_stronghold first"`); stages: walk in `ColumnGoal` legs of ≤ 128 blocks to within 24 (a small `WalkColumnAction(x, z, radius)` in this package); `ThrowEyeAction` (refines; a `sank` reading updates the waypoint); walk to the refined column; `DigToStrongholdAction(-50)`; `FindPortalRoomAction(64)`. Timeout 72000.
- `FillEndPortalAction()`: frames = visible `end_portal_frame` within 8 of the nearest one (the 12-frame ring); empty = those with `HAS_EYE == false`; eyes held < empty → `FAILED "needs <empty> ender_eye for <empty> empty frames (you have <n>)"` before using any; a visible `spawner` within 8 → break it first with `ctx.breaker()` when a pickaxe is held (silverfish stop coming); per empty frame: `Approach.block(frame, 4.0)`, select `ender_eye`, `BlockUse.clickFace(p, frame, UP)` (looking at a frame makes `EnderEyeItem.use` pass, so the click is never a throw); DONE when `end_portal` blocks exist within the ring (summary `filled 3 frames; the end portal is open`), or `FAILED "all frames filled but no portal opened (the frames face the wrong way?)"`. Timeout 1200.
- `GoToEndRoutine`: `FindPortalRoomAction(64)` unless an `end_portal_frame` is visible within 16; `FillEndPortalAction` unless `end_portal` is visible; `EnterPortalAction("end")`. Timeout 12000.

**Tools (StrongholdModule):** `make_eyes` [makeeyes] (`count` 1..12); `throw_eye` [throweye]; `locate_stronghold` [locatestronghold] (`spread` 32..256); `go_to_stronghold` [gotostronghold]; `find_portal_room` [findportalroom]; `fill_end_portal` [fillendportal]; `go_to_end` [gotoend]. Guide line: `Stronghold: make_eyes, locate_stronghold (2 eye throws), go_to_stronghold (travels, digs down, finds the portal room), fill_end_portal, or go_to_end for all of it.`

**Snapshot section:** label `Stronghold`: `"eye readings: 2 (last 213° SW)"`, `"stronghold about 640m NE at x z"` from the waypoint, `"end portal room at x y z"`; tags `eye_readings:<n>`, `stronghold_known`, `portal_room_known`, `end_portal_open` (an `end_portal` sighting).

**Menu options:** `make_eyes` (≥ 1 ender_pearl and (blaze_powder or blaze_rod) held, overworld or the_nether) → `make_eyes{count: min(pearls, 12)}`; `locate_stronghold` (overworld, ≥ 2 ender_eye, no `stronghold_known`); `go_to_stronghold` (`stronghold_known`, no `portal_room_known`); `go_to_end` (`portal_room_known` or an `end_portal_frame` sighting ≤ 24). Sample: 12 ender_eye, overworld.

**Tests:**
- `TriangulateTest`: from (0,0) bearing 45 and (100,0) bearing 315 → (50, −50) within 1e-6; parallel bearings (10 and 12) → empty; a point behind the second thrower → empty; `bearing(1, 0) == 90`, `bearing(0, -1) == 0`.
- `EyeReadingsTest`: capacity 4; `estimate` picks the widest pair; a `sank` reading wins.
- `FrontierTest`: visited buckets are skipped; same-height candidates are preferred.
- `StrongholdModuleTest`: validation; menus as above (`locate_stronghold` with 2 eyes, not with 1).
- Game:
  - `eyeWatcherReadsBearing`: the test spawns an `EyeOfEnder` at the bot's eyes, `setItem(new ItemStack(Items.ENDER_EYE))`, `signalTo(eyes + (100, 0, 0))` (east) right after `EyeWatcher.start` → `READ` within 40 ticks; `bearing` within 5° of 90; `sank` false.
  - `throwEyeWithoutStronghold`: bot with 1 ender_eye → if the test world has no stronghold the action FAILED containing `does not fly`; if an eye did fly, the bot has an `EyeReadings` entry instead (assert one of the two).
  - `makesEyes`: 2 blaze_rod, 3 ender_pearl (crafting table nearby) → `make_eyes{count: 3}` → 3 ender_eye, 1 blaze_powder left.
  - `fillsEndPortal` (`padding = 24`): a 12-frame ring (3 × 3 hole, frames `FACING` toward the centre) at y 1, 9 of them with `HAS_EYE`; bot with 3 ender_eye → DONE; 9 `end_portal` blocks; eyes 0.
  - `fillEndPortalFailsWithTooFewEyes` (Review Focus 2): same ring, bot with 1 eye → FAILED containing `needs 3 ender_eye for 3 empty frames (you have 1)`; still 9 filled frames.
  - `findsPortalRoomDownCorridor` (`padding = 24`): a stone_bricks corridor (3 wide, 3 high, 14 long) ending in a room with 4 visible end_portal_frames and a spawner set to silverfish (`SpawnerBlockEntity.setEntityId(EntityTypes.SILVERFISH, random)`); bot at the corridor start → `FindPortalRoomAction(64, true)` DONE within 1200 ticks; `Waypoints` has `end_portal`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Make eyes, triangulate the stronghold and open the End portal"` with the trailer.

---

### Task 4: The ender dragon

**Files:** Create in `main/java/com/aiciv/dragon/`: `DragonView.java` (reads what a client sees), `DragonTactics.java` (pure), `ShootCrystalsAction.java`, `BreakCageAction.java`, `HitDragonAction.java`, `Dodge.java`, `FightDragonRoutine.java`, `LeaveEndRoutine.java`; fill `DragonModule.java`. Tests `test/java/com/aiciv/dragon/DragonTacticsTest.java`, `DragonModuleTest.java`; game tests in `gametest/DragonGameTests.java` (prefix `TestDrag`).

**Interfaces:**
- `DragonView` (server thread): `static Optional<EnderDragon> dragon(AiPlayer p)` (within 192); `static boolean perched(EnderDragon d)` (`getPhaseManager().getCurrentPhase().isSitting()`, from the synced phase); `static boolean dead(EnderDragon d)` (phase `DYING` or `dragonDeathTime > 0` or removed); `static List<EndCrystal> crystals(AiPlayer p)` (within 160, alive); `static boolean caged(Level level, EndCrystal c)` (an `iron_bars` block within 2 horizontally and 0..2 above the crystal's block); `static boolean visible(AiPlayer p, Entity e)` (`hasLineOfSight`); `static List<AreaEffectCloud> breath(AiPlayer p)` (clouds within 16 whose `getParticle().getType() == ParticleTypes.DRAGON_BREATH`).
- `DragonTactics` (pure): `enum Move { DODGE, SHOOT_CRYSTAL, BREAK_CAGE, HIT_HEAD, SHOOT_DRAGON, WAIT, DONE }`; `record Situation(boolean inBreath, int openCrystalsVisible, int cagedCrystals, boolean dragonPerched, double headDistance, boolean dragonDead, boolean hasBow, int arrows, int throwaways, float health)`; `static Move next(Situation s)` — in order: `dragonDead → DONE`; `inBreath → DODGE`; `openCrystalsVisible > 0 && hasBow && arrows > 0 → SHOOT_CRYSTAL`; `cagedCrystals > 0 && hasBow && arrows > 0 && throwaways ≥ 24 && health > 12 → BREAK_CAGE`; `dragonPerched → HIT_HEAD`; `hasBow && arrows > 0 → SHOOT_DRAGON`; else `WAIT`.
- `Dodge` (helper used by actions): when the feet are within `radius + 1` of a breath cloud, walk with `NearGoal` to a point 6 blocks away from the cloud centre, away from the fountain (the dragon's `getFightOrigin()`), never toward a cell whose column has no standable block within 3 below (the void).
- `ShootCrystalsAction(int count)` (+ `anyDimension`): the_end check; bow + arrows; per visible uncaged crystal (nearest first): if within 9 blocks, back off to ≥ 10 (`NearGoal` on the far side); `BowShot(() -> crystal position + (0, 1, 0), 20)`, up to 4 arrows per crystal; DONE `destroyed 3 crystals (2 left in sight)`; none visible → `FAILED "no open end crystal in sight"`. Timeout 6000.
- `BreakCageAction()` (+ `anyDimension`): nearest caged crystal; needs ≥ 24 throwaways, a pickaxe, bow and arrows; pillar up beside its obsidian tower (`NearGoal` at the cell outside the cage at crystal y, navigator pillars with throwaways); break one `iron_bars` between the bot and the crystal (`ctx.breaker()`); bridge 9 blocks straight away from the crystal at that height (navigator `NearGoal` with bridging); `BowShot` through the gap (up to 4 arrows); then come down (navigator to the ground). DONE `broke the cage and destroyed the crystal at x y z`; `FAILED "can't see the caged crystal at x y z through the gap"`. Timeout 6000.
- `HitDragonAction(int seconds)` (+ `anyDimension`): for `seconds × 20` ticks: `Dodge` first; perched → `Approach.entity(dragon.head, 2.8)` then attack the **head part** (`p.attack(dragon.head)` after `getAttackStrengthScale(0.5f) ≥ 0.9`, `swing`), never standing on the exit-portal bedrock column (the fountain's centre 3 × 3); flying and bow → `BowShot` at the dragon's body centre when within 48; DONE when the dragon dies (`the dragon is dead`) or time runs out (`hit the dragon 7 times (health 120/200)`). Timeout `seconds × 20 + 200`.
- `FightDragonRoutine` (`Routine`; the_end check): each stage chosen by `DragonTactics.next(situation now)`: `SHOOT_CRYSTAL → ShootCrystalsAction(10)`, `BREAK_CAGE → BreakCageAction`, `HIT_HEAD`/`SHOOT_DRAGON`/`WAIT`/`DODGE → HitDragonAction(10)`, `DONE` → routine done (`the dragon is dead; the exit portal is open`); a failed stage is retried by the next decision up to 5 failures in a row, then the routine fails with the last reason. Health ≤ 6 → `FAILED "too hurt to keep fighting (health <n>)"`. Timeout 36000.
- `LeaveEndRoutine`: the_end check; dragon alive → `FAILED "the exit portal opens when the dragon is dead"`; walk to the fountain (`end_portal` blocks visible or the `getFightOrigin()` column) and `EnterPortalAction("end")` (leaving the End uses the normal portal path because `seenCredits` is set). Timeout 6000.

**Tools (DragonModule):** `fight_dragon` [fightdragon]; `shoot_crystals` [shootcrystals] (`count` 1..10); `break_cage` [breakcage]; `hit_dragon` [hitdragon] (`seconds` 5..120); `leave_end` [leaveend]. Guide line: `Dragon: fight_dragon does it all; or shoot_crystals (bow), break_cage, hit_dragon (melee the head while it perches), then leave_end through the exit portal.`

**Snapshot section:** label `Dragon` (the_end only): `"ender_dragon 120/200 hp, perched on the fountain (14m N)"` or `"…, flying 40m NE"`, `"end crystals: 6 seen, 2 caged"`, `"exit portal open at x y z"`; tags `dragon_perched`, `dragon_flying`, `crystals_left`, `dragon_dead` (also set when no dragon is alive and an `end_portal` is visible at the fountain), `exit_portal_open`.

**Menu options:** `fight_dragon` (the_end — section present — and not `dragon_dead`); `shoot_crystals` (`crystals_left`, bow and arrows held); `hit_dragon` (`dragon_perched`); `leave_end` (`exit_portal_open` and `dragon_dead`). Sample: a Dragon section with `dragon_flying`, `crystals_left`, bow + 32 arrows.

**Tests:**
- `DragonTacticsTest`: one case per row of the order above, including `inBreath` beating crystals and `BREAK_CAGE` needing 24 throwaways and health > 12.
- `DragonModuleTest`: validation; menu rules.
- Game:
  - `shootsEndCrystalFromSafeDistance` (`padding = 24`, Review Focus 4): an obsidian pillar 4 high 13 blocks away with an `EndCrystal` on top (`new EndCrystal(level, x, y, z)` + `setShowBottom(false)`); bot with a bow and 8 arrows, health 20 → `ShootCrystalsAction(1, true)` DONE within 600 ticks; the crystal is removed; the bot's health ≥ 14 and it never stood within 9 of the crystal (checked every tick).
  - `hitsPerchedDragonHead` (`padding = 24`, maxTicks 600): an `EnderDragon` spawned 6 blocks away with `setNoAi(true)` and `getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING)`; bot with an iron_sword → `HitDragonAction(10, true)` → the dragon's health drops within 200 ticks. (If its parts are not positioned with `NoAi`, run with AI and re-set the phase every tick in `afterTick`; record which in the spec.)
  - `stepsOutOfDragonBreath` (Review Focus 4): an `AreaEffectCloud` at the bot's feet with `setCustomParticle(ParticleTypes.DRAGON_BREATH)`, radius 3, duration 200 → `HitDragonAction(5, true)` with no dragon → within 60 ticks the bot is ≥ 4 blocks from the cloud centre.
  - `fightDragonOnlyInTheEnd`: in the overworld `fight_dragon` FAILED containing `works in the_end`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Fight the ender dragon: crystals, cages, the perched head and the exit portal"` with the trailer.

---

### Task 5: Boats, horses, pigs, striders, camels and minecarts

**Files:** Create in `main/java/com/aiciv/vehicles/`: `Steering.java` (pure), `Mounts.java`, `PlaceBoatAction.java`, `BoardAction.java`, `DismountAction.java`, `RideToAction.java`, `BoatToRoutine.java`, `PlaceMinecartAction.java`, `RideMinecartRoutine.java`; fill `VehicleModule.java`. Tests `test/java/com/aiciv/vehicles/SteeringTest.java`, `VehicleModuleTest.java`; game tests in `gametest/VehicleGameTests.java` (prefix `TestRide`).

**Interfaces:**
- `Steering` (pure): `enum Turn { LEFT, RIGHT, STRAIGHT }`; `static Turn turn(float yaw, float desired, float deadband)` (wrapped difference `Mth.wrapDegrees(desired − yaw)`; a positive difference turns RIGHT in Minecraft's yaw, which grows clockwise); `static boolean throttle(float yaw, float desired)` (forward only when `|difference| < 60`).
- `Mounts`: `static Optional<Entity> find(AiPlayer p, String kind, double radius)` (`boat` → any `AbstractBoat`; `minecart` → `AbstractMinecart`; `horse|donkey|mule|llama|camel|pig|strider` → that entity type via `AttackAction.entityType`; nearest without passengers, within 16); `static String problem(AiPlayer p, Entity v)` → null or the reason a ride cannot be controlled: `AbstractHorse` not tamed → `the horse is not tamed (use tame)`; not saddled → `the <kind> needs a saddle (use saddle)`; `Pig`/`Strider` not saddled → same; pig without `carrot_on_a_stick` held → `steer a pig with a carrot_on_a_stick`; strider without `warped_fungus_on_a_stick` → likewise.
- `PlaceBoatAction()`: needs any item ending `_boat` or `_raft`; target = nearest visible water source with air above within 4 (`Finder.visibleStates`), else `FAILED "no open water within 4 blocks"`; look at its top centre, `gameMode.useItem`; DONE when an `AbstractBoat` is within 3 of it (`placed an oak_boat at x y z`). Timeout 200.
- `BoardAction(String kind)`: `Mounts.find` (`FAILED "no <kind> within 16 blocks"`); `Approach.entity(v, 2.5)`; for pigs, horses and camels a missing saddle → `FAILED` with `Mounts.problem`; `Riding.mount` (`FAILED "could not get on the <kind>"`); DONE `riding the <kind>`. Untamed horses are left to `tame` (Task 8): `board horse` on an untamed horse → `FAILED "the horse is not tamed (use tame)"`. Timeout 600.
- `DismountAction()`: not riding → DONE (`you were not riding`); `Riding.dismount`; DONE. Timeout 20.
- `RideToAction(int x, int z)`: not riding → `FAILED "you are not riding anything (use board first)"`; `Mounts.problem` non-null → FAILED with it; per tick, `desired = Riding.yawTo(x − vx, z − vz)` from the vehicle's position:
  - `AbstractBoat b`: `turn = Steering.turn(b.getYRot(), desired, 10)`; `b.setInput(turn == LEFT, turn == RIGHT, Steering.throttle(...), false)`; `Riding.faceYaw(p, b.getYRot())`.
  - Living mount (`AbstractHorse`, `Camel`, `Pig`, `Strider`): `Riding.faceYaw(p, desired)` (the mount follows the rider's yaw), `zza = 1`; on `v.horizontalCollision` and an `AbstractHorse`/`Camel`: `onPlayerJump(100)` then `handleStartJump(100)` (once per 20 ticks).
  - `AbstractMinecart`: `Riding.pushInput(p, true, false, false, false)` (minecarts move by rails and the rider's push).
  - Arrive within 3 blocks horizontally → `Riding.clearInput`, DONE `rode the <kind> to x z` (still riding). Moved < 0.5 blocks in 60 ticks → `Riding.clearInput`, `FAILED "the <kind> is stuck at x z"` (+ ` (land in the way of the boat?)` for boats on land). Ejected (`!isPassenger()`) → `FAILED "you fell off the <kind> at x y z"`.
  - `cancel`/`interrupted`: `Riding.dismount` (Review Focus 3).
  - Timeout `min(24000, 600 + 20 × distance)`.
- `BoatToRoutine(int x, int z)`: `BoardAction("boat")` if a boat is within 8, else `PlaceBoatAction` then board; `RideToAction(x, z)`; `DismountAction`; then break the boat (attack it until it drops: `p.attack(boat)` up to 5 times, 10 ticks apart) and pick the item up with `ItemGatherer` (≤ 100 ticks). Timeout as `RideToAction` + 1200.
- `PlaceMinecartAction(BlockPos railOrNull)`: needs `minecart`; rail = the given or nearest visible `BlockTags.RAILS` within 8; `Approach.block(rail, 4.0)`; `BlockUse.click(p, rail)` with the minecart selected (`MinecartItem.useOn`); DONE when an `AbstractMinecart` is within 1.5 of the rail. Timeout 400.
- `RideMinecartRoutine()`: `PlaceMinecartAction(null)` unless a minecart is within 8; `BoardAction("minecart")`; a ride stage (inner action in this package `CartRideAction`) pushing forward until the cart's speed < 0.01 for 40 ticks (DONE `the minecart stopped at x y z`) or 2400 ticks; `DismountAction`; break the cart and collect it like the boat. Timeout 4800.

**Tools (VehicleModule):** `place_boat` [placeboat]; `board` (`target` required: `boat, minecart, horse, donkey, mule, llama, camel, pig or strider`); `dismount`; `ride_to` [rideto] (`x`, `z` required); `boat_to` [boatto] (`x`, `z` required); `place_minecart` [placeminecart] (`x`,`y`,`z` optional); `ride_minecart` [rideminecart]. Validation: `ride_to needs a whole number "x"` (and `"z"`), distance > 512 → `ride_to goes at most 512 blocks per call`, unknown target → `board "target" must be one of boat, minecart, horse, donkey, mule, llama, camel, pig, strider`. Guide line: `Travel: boat_to x z crosses water; board a tamed saddled horse (or pig/strider with its stick) and ride_to x z; ride_minecart on rails; dismount.`

**Snapshot section:** label `Riding`: `"oak_boat"` / `"horse (tamed, saddled)"` when riding; plus `"horse 6m E (tamed, saddled)"`-style lines for up to 3 rideable mounts within 16 that `Mounts.problem` would accept; tags `riding`, `riding:<kind>`, `rideable:<kind>`.

**Menu options:** `dismount` (`riding`); `board_<kind>` for up to 2 `rideable:` tags; `boat_to_<dir>` for N/E/S/W 64 blocks when holding a boat and a `water` sighting ≤ 4 → `boat_to{x, z}` (absolute from `snap.x()/z()`). Sample: holding `oak_boat`, water sighting at 2 m.

**Tests:**
- `SteeringTest`: `turn(0, 30, 10) == RIGHT`, `turn(0, -30, 10) == LEFT`, `turn(170, -170, 10) == RIGHT` (wraps), `turn(0, 5, 10) == STRAIGHT`; `throttle(0, 70)` false.
- `VehicleModuleTest`: validation messages; menu rules.
- Game (`padding = 24`):
  - `boatToCrossesWater` (maxTicks 2400): a stone-lined water channel 18 × 3 × 1; bot at one end holding an oak_boat → `boat_to` the far end → DONE; bot within 3 blocks of the target, not riding, holds an oak_boat again.
  - `boatStuckOnLandFails` (Review Focus 3): boat in a 3 × 3 pool, `ride_to` 20 blocks over land → FAILED containing `stuck` within 200 ticks; the boat has no input (`getPaddleState(0)` and `(1)` false).
  - `ridesSaddledHorse` (maxTicks 1200): a horse with `setTamed(true)` and `setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE))` 2 blocks away; `board horse` then `ride_to` 14 blocks east → DONE; the horse moved ≥ 10 blocks east; the bot is still riding it.
  - `boardPigFailsWithoutSaddle` (Review Focus 2): pig, no saddle → FAILED containing `needs a saddle`.
  - `cancelWhileRidingDismounts` (Review Focus 3): during `ride_to` on the horse, `executor.stop` → next tick `!isPassenger()` and `zza == 0`.
  - `ridesMinecartOnPoweredRails` (maxTicks 2400): 14 rails east with powered rails over redstone blocks; bot holds a minecart → `ride_minecart` DONE; the bot ended ≥ 8 blocks east of the start, not riding, holds a minecart.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Ride boats, horses, pigs, striders, camels and minecarts"` with the trailer.

---

### Task 6: Elytra flight and ender pearls

**Files:** Create in `main/java/com/aiciv/flight/`: `ElytraPilot.java` (pure), `LaunchElytraAction.java`, `BoostAction.java`, `FlyToAction.java`, `ThrowPearlAction.java`; fill `FlightModule.java`. Tests `test/java/com/aiciv/flight/ElytraPilotTest.java`, `FlightModuleTest.java`; game tests in `gametest/FlightGameTests.java` (prefix `TestFly`).

**Interfaces:**
- `ElytraPilot` (pure): `record State(double dx, double dz, double y, double groundY, double speed, int rockets, int sinceRocket, double startY)`; `record Command(float pitch, boolean rocket, boolean land)`; `static Command next(State s)`: `horizontal = hypot(dx, dz)`; `horizontal < 24` → `pitch 35, land true`; `rockets > 0 && sinceRocket ≥ 40 && (y < startY + 20 || speed < 0.8)` → `pitch −20, rocket true`; `y − groundY < 6` and `rockets == 0` → `pitch 0`; else `pitch 10` (a ~10:1 glide). `static final int ROCKET_GAP = 40`, `MAX_ROCKETS = 16`.
- `LaunchElytraAction()`: elytra worn in the chest slot (`canGlideUsing`), else held → run `EquipAction("elytra")` first, else `FAILED "no elytra"`; durability left ≤ 10 → `FAILED "the elytra is nearly broken"`; on the ground: `jumpFromGround()`; on the first tick with `!onGround() && getDeltaMovement().y < 0` → `tryToStartFallFlying()`; then, if a `firework_rocket` is held, use one (`ItemUse.useFacing` at pitch −30); DONE when `isFallFlying()` within 30 ticks, else `FAILED "could not take off (jump from 3+ blocks up or carry firework rockets)"`. Timeout 60.
- `BoostAction()`: gliding and holding a rocket → use one; DONE; else `FAILED "boost needs you gliding with a firework_rocket"`. Timeout 5.
- `FlyToAction(int x, int z)`: launch (inner `LaunchElytraAction` unless gliding); each tick `ElytraPilot.next(...)` with `groundY` = the highest motion-blocking block under the bot (`level.getHeight(MOTION_BLOCKING, …)`), yaw from `Riding.yawTo`, `setXRot(pitch)`; rockets used through `ItemUse.useFacing`; stalled (not gliding, not on ground, 5 ticks) → `tryToStartFallFlying()` again; `onGround()` or `isInWater()` → stop: within 16 blocks of the target → DONE `flew to x z (used 3 rockets)`, else `FAILED "landed at x y z, <d> blocks short"`. Timeout `600 + 10 × distance`.
- `ThrowPearlAction(int x, int y, int z)`: the landing block `(x, y, z)` (the bot lands on top); health ≤ 7 → `FAILED "too hurt to pearl (it costs 5 health; you have <n>)"`; no `ender_pearl` → `FAILED "no ender_pearl"`; horizontal distance outside 3..40 → `FAILED "a pearl lands 3 to 40 blocks away"`; `Sight.canSee(p, landing)` false → `FAILED "can't see where to land at x y z"`; on cooldown → wait ≤ 20 ticks; aim at `(x + 0.5, y + 1.05, z + 0.5)` with `ItemUse.aimThrow(THROWN)` (empty → `FAILED "too far to throw a pearl there"`), `useFacing`; wait ≤ 100 ticks for the bot to move > 2 blocks at once (the teleport); DONE `pearled to x y z (took <n> damage)` when it ends within 4 of the landing block, else `FAILED "the pearl landed at x y z instead"`. Timeout 200.

**Tools (FlightModule):** `fly_to` [flyto] (`x`, `z` required); `launch_elytra` [launchelytra]; `boost`; `throw_pearl` [throwpearl] (`x`, `y`, `z` required: the block to land on). Guide line: `Flight: fly_to x z with an elytra (rockets help); throw_pearl x y z lands you on a block you can see, for 5 health.`

**Snapshot section:** label `Flight` when an elytra is worn or carried or pearls are held: `"elytra 87% (worn)"`, `"firework_rocket x5"`, `"ender_pearl x3"`; tags `has_elytra`, `elytra_worn`, `rockets`, `pearls`.

**Menu options:** `launch_elytra` (`elytra_worn` and `rockets`); `fly_<dir>` for N/E/S/W 256 blocks when `elytra_worn` and `rockets` → `fly_to{x, z}`. Sample: elytra worn, 8 rockets.

**Tests:**
- `ElytraPilotTest`: near the target → land; low and rockets ready → rocket with pitch −20; rocket gap respected; no rockets and close to the ground → pitch 0.
- `FlightModuleTest`: `throw_pearl` without `y` → `Invalid` containing `"y"`; menus.
- Game (`padding = 24`):
  - `flyToPlatform` (maxTicks 900): a 12-high stone tower at (2, 1–12, 2); bot on top wearing an elytra with 4 rockets → `fly_to` relative (20, 20) → DONE; bot within 16 (horizontal) of the target, alive, `!isFallFlying()`.
  - `pearlLandsOnPlatform`: a stone platform 14 blocks east at the same height; bot with 2 pearls and 20 health → `throw_pearl` DONE; feet within 2.5 blocks of the platform; health ≤ 16; pearls 1.
  - `pearlRefusedAtLowHealth` (Review Focus 4): health 6 → FAILED containing `too hurt to pearl`; pearls unchanged.
  - `pearlNeedsSightOfLanding` (Review Focus 5): a stone wall 4 high between bot and platform → FAILED containing `can't see where to land`; pearls unchanged.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Fly with an elytra and travel by ender pearl"` with the trailer.

---

### Task 7: Buckets, bone meal, fire, potions, throwables, maps and compasses

**Files:** Create in `main/java/com/aiciv/items/`: `FillBucketAction.java`, `EmptyBucketAction.java`, `BoneMealAction.java`, `IgniteAction.java`, `CarvePumpkinAction.java`, `DrinkAction.java`, `ThrowAction.java`, `MakeMapAction.java`, `LinkCompassAction.java`, `Potions.java` (pure name table), `CompassReading.java` (pure); fill `ItemsModule.java`. Tests `test/java/com/aiciv/items/PotionsTest.java`, `CompassReadingTest.java`, `ItemsModuleTest.java`; game tests in `gametest/ItemsGameTests.java` (prefix `TestItem`).

**Interfaces:**
- `FillBucketAction(String fluid, BlockPos sourceOrNull)`: fluid `water|lava` (factory: `fluid must be water or lava`); needs an empty `bucket`; source = given or nearest visible source of that fluid within 8 (`FAILED "no <fluid> source in sight within 8 blocks"`); `Approach.block(source, 4.0)`; `Buckets.fill`; DONE `filled a <fluid>_bucket`. Timeout 600.
- `EmptyBucketAction(int x, int y, int z)`: needs `water_bucket` or `lava_bucket` (water preferred unless only lava is held); `Approach.block(cell, 4.0)`; `Buckets.pour`; DONE `poured water at x y z`. Timeout 600.
- `BoneMealAction(BlockPos targetOrNull, int count)`: target = given, or the nearest visible block within 8 that is a growing `CropBlock`, a sapling (`BlockTags.SAPLINGS`), or a grass block; uses bone meal with `BlockUse.click` up to `count` times, 4 ticks apart, stopping when a crop is at max age or a sapling turned into a log; DONE `used 3 bone_meal on wheat (now ripe)`; none held → `FAILED "no bone_meal"`. Timeout 600.
- `IgniteAction(int x, int y, int z)`: igniter `flint_and_steel` or `fire_charge`; `Approach.block`; on `tnt` or an unlit `campfire`/`candle` → `BlockUse.click` the block; otherwise `clickFace(UP)` to set fire on top (needs air above: `FAILED "no room for fire above x y z"`); DONE `primed tnt at x y z` / `lit the campfire` / `set fire on top of x y z`. Never on a block another player placed except TNT and campfires (`PlacedBlocks.isPlacedByOther` → `FAILED "that block belongs to <placer>"`). Timeout 400.
- `CarvePumpkinAction(BlockPos orNull)`: shears; nearest visible `pumpkin` within 8; `BlockUse.click` with shears selected; DONE when it is `carved_pumpkin`, and the dropped seeds are gathered (≤ 60 ticks). Timeout 400.
- `Potions` (pure): `static Optional<String> normalise(String raw)` reuses milestone 3's `BrewTable.normalise` names plus `"milk"` → `milk_bucket`, `"honey"` → `honey_bottle`, `"water"`; `static boolean isThrowable(String itemId)` (`splash_potion`, `lingering_potion`, `snowball`, `egg`, `experience_bottle`, `ender_pearl` is **not** here — pearls are `throw_pearl`).
- `DrinkAction(String item)`: matches a held `potion` whose `PotionContents` is the named potion, `milk_bucket` or `honey_bottle` (`FAILED "no <item> to drink"`); `ItemUse.select`, `gameMode.useItem`; RUNNING while `isUsingItem()` (≤ 60 ticks); DONE `drank <item>` when the stack was consumed. Timeout 80.
- `ThrowAction(String item, BlockPos atOrNull, String targetOrNull)` (exactly one of `at`/`target`; factory messages `throw needs x y z or a "target"` and `use either x y z or "target", not both`): `item` must be `Potions.isThrowable` (`FAILED "<item> can't be thrown; throw works for snowball, egg, splash_potion, lingering_potion, experience_bottle"`); potions match by name (`splash healing` → a `splash_potion` with `Potions.HEALING`); target point = the block's top centre or the entity's centre (`AttackAction.findTarget(p, target, 32)`); not visible (`Sight.canSee` / `hasLineOfSight`) → `FAILED "can't see <what> at x y z"` (Review Focus 5); kind `POTION` for potions else `THROWN`; aim, `useFacing`; DONE `threw a snowball at x y z`. Timeout 60.
- `MakeMapAction()`: needs `map` (empty); `useItem`; DONE `made map #<id> of x z (scale 0)` reading `MapItem.getSavedData`. Timeout 20.
- `LinkCompassAction(BlockPos lodestoneOrNull)`: `compass` + a visible `lodestone` within 8; `BlockUse.click` with the compass; DONE when a held compass has `LODESTONE_TRACKER`. Timeout 400.
- `CompassReading` (pure): `static String line(String kind, int dx, int dz)` → `"compass points SW (world spawn)"` / `"(lodestone)"` / `"recovery_compass points E (where you died)"` with `Compass.direction` (direction only, as a real compass shows); null when `dx == dz == 0`.

**Tools (ItemsModule):** `fill_bucket` [fillbucket] (`fluid` required, `x`,`y`,`z` optional); `empty_bucket` [emptybucket] (`x`,`y`,`z` required); `bone_meal` [bonemeal] (`x`,`y`,`z` optional, `count` 1..8); `ignite` (`x`,`y`,`z` required); `carve_pumpkin` [carvepumpkin] (`x`,`y`,`z` optional); `drink` (`item` required); `throw` (`item` required, `x`,`y`,`z` or `target`); `make_map` [makemap]; `link_compass` [linkcompass] (`x`,`y`,`z` optional). Guide line: `Items: fill_bucket/empty_bucket, bone_meal crops, ignite with flint_and_steel, drink potions or milk, throw snowballs, eggs and splash potions, make_map, link_compass to a lodestone.`

**Snapshot section:** label `Items`: a compass line (spawn = `level.getRespawnData().pos()`; lodestone from `LODESTONE_TRACKER.target()` when in this dimension; recovery compass from `getLastDeathLocation()`), and for each held filled map `"map #3 around 0 0 (128 blocks), you are inside"`; tags `has_compass`, `has_map`, `bucket_empty`, `bucket_water`.

**Menu options:** `fill_water_bucket` (`bucket_empty` and a `water` sighting ≤ 8); `drink_<potion>` for a held named potion other than water/awkward (first one); `bone_meal_crops` (bone_meal held and an item of milestone 3's `Crops` section contains `growing`) → `bone_meal{count: 3}`; `make_map` (`map` held). Sample: an empty bucket and a water sighting at 3 m.

**Tests:**
- `PotionsTest`: `"potion of healing"` → healing, `"milk"` → milk_bucket; `isThrowable("ender_pearl")` false.
- `CompassReadingTest`: `(−10, 10)` → `SW`; zero → null.
- `ItemsModuleTest`: `throw` with coords and target → `Invalid` with `not both`; `fill_bucket{fluid: "honey"}` → `Invalid` containing `water or lava`; menus.
- Game:
  - `fillsAndEmptiesWaterBucket`: water source 3 blocks away; empty bucket → `fill_bucket water` → water_bucket; `empty_bucket` at a cell 3 blocks the other way → water source there.
  - `bonemealRipensWheat`: wheat age 0 on farmland; 8 bone meal → `bone_meal{count: 8}` DONE; wheat at max age; ≥ 1 bone meal used.
  - `ignitesCampfireAndNetherrack`: unlit campfire (`LIT` false) → lit; netherrack → fire above.
  - `drinksSwiftness`: a swiftness potion (`PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS)`) → `drink swiftness` → `MobEffects.SPEED` active; a glass_bottle in the inventory.
  - `throwsSnowballAtPoint` (`padding = 24`): snowballs land within 2 blocks of a floor point 12 away (tracked in `afterTick`).
  - `throwAtUnseenPointFails` (Review Focus 5): the point is behind a stone wall → FAILED containing `can't see`; snowballs unchanged.
  - `makesMap`: empty map → `filled_map` whose saved data's centre is within 64 of the bot.
  - `compassLineInSnapshot`: holding a compass → `Perceiver.capture` text contains `compass points`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Use buckets, bone meal, fire, potions, throwables, maps and compasses"` with the trailer.

---

### Task 8: Taming, leads, milking, shearing and dyeing

**Files:** Create in `main/java/com/aiciv/animals/`: `Tameables.java` (pure table), `TameAction.java`, `SaddleAction.java`, `LeashAction.java`, `TieLeadAction.java`, `UnleashAction.java`, `MilkAction.java`, `ShearAction.java`, `DyeSheepAction.java`; fill `AnimalsModule.java`. Tests `test/java/com/aiciv/animals/TameablesTest.java`, `AnimalsModuleTest.java`; game tests in `gametest/AnimalGameTests.java` (prefix `TestPet`).

**Interfaces:**
- `Tameables` (pure): `enum Method { FEED, RIDE }`; `static Optional<Method> method(String animal)` (`wolf`, `cat`, `parrot` → FEED; `horse`, `donkey`, `mule`, `llama`, `trader_llama` → RIDE; others empty); `static List<String> items(String animal)` (`wolf → bone`; `cat → cod, salmon`; `parrot → wheat_seeds, melon_seeds, pumpkin_seeds, beetroot_seeds, torchflower_seeds, pitcher_pod`); used for messages and menus (actions still ask the entity: `isFood`/the real interaction).
- `TameAction(String animal)`: the nearest untamed adult of that type within 16 (`FAILED "no untamed <animal> within 16 blocks"`); FEED: needs one of `Tameables.items` (`FAILED "<animal>s are tamed with <items>"`, Review Focus 2); `Approach.entity(a, 2.5)`; select the item; `EntityUse.interact` once per 20 ticks until `isTame()` (owner = the bot) or 16 items are used (`FAILED "used 16 bone and the wolf is still wild"`); RIDE: `Riding.mount` with an empty hand; wait ≤ 200 ticks for `isTamed()` or `!isPassenger()` (bucked off); up to 8 mounts (`FAILED "the horse threw you off 8 times"`); before the first ride, feed up to 2 held `golden_carrot`/`golden_apple`/`apple`/`sugar`/`wheat` (raises temper the vanilla way); DONE `tamed the wolf (3 bone)` / `tamed the horse after 2 rides` (still riding a tamed horse → dismount first). Timeout 6000.
- `SaddleAction(String animal)`: needs `saddle`; nearest adult of that type within 16 that is not saddled and, for horses, donkeys and mules, tamed (`FAILED "tame the <animal> first"`; camels, pigs and striders need no taming); `Approach.entity`; select saddle; `EntityUse.interact`; DONE when `isSaddled()`. Timeout 600.
- `LeashAction(String animal)`: needs `lead`; nearest `Leashable` of that type with `canBeLeashed()` within 16; interact; DONE when `isLeashed() && getLeashHolder() == p`. Timeout 600.
- `TieLeadAction(BlockPos fenceOrNull)`: the bot leads at least one mob (`Leashable.leashableLeashedTo(p)` non-empty, else `FAILED "you are not leading any animal"`); fence = given or nearest visible `BlockTags.FENCES` within 8; `Approach.block(fence, 4.0)`; `BlockUse.click` (vanilla binds the bot's leashed mobs); DONE when every such mob's holder is a `LeashFenceKnotEntity`. Timeout 600.
- `UnleashAction(String animal)`: the nearest leashed mob of that type (held by the bot or tied to a knot); interact with an empty hand; gather the dropped `lead`. Timeout 600.
- `MilkAction(String animalOrNull)`: default `cow` (`cow`, `goat`, `mooshroom`); a `bucket` (→ `milk_bucket`) or, for a mooshroom, a `bowl` (→ `mushroom_stew`); interact; DONE `milked the cow`. Timeout 600.
- `ShearAction(String animalOrNull)`: default `sheep` (`sheep`, `mooshroom`, `snow_golem`); needs `shears`; nearest that `readyForShearing()`; interact; gather wool/mushrooms within 6 (≤ 80 ticks); DONE `sheared the sheep (got 2 white_wool)`. Timeout 600.
- `DyeSheepAction(String color)`: `DyeColor.byName(color, null)` (null → factory `Invalid "unknown color \"x\" (white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black)"`); needs `Items.DYE.pick(color)`; nearest sheep within 16 whose colour differs; interact; DONE when `getColor() == color`. Timeout 600.

**Tools (AnimalsModule):** `tame` (`animal` required); `saddle` (`animal` required); `leash` (`animal` required); `tie_lead` [tielead] (`x`,`y`,`z` optional); `unleash` (`animal` required); `milk` (`animal` optional); `shear` (`animal` optional); `dye_sheep` [dyesheep] (`color` required). Guide line: `Animals: tame wolves (bone), cats (cod), parrots (seeds), horses (ride them); saddle, leash and tie_lead to a fence; milk cows; shear and dye_sheep. breed is in Farming.`

**Snapshot section:** label `Pets`: animals within 16 owned by the bot (`OwnableEntity.getOwner() == bot`, or a tamed horse whose `getOwnerReference()` is the bot) `"wolf 4m N (yours, sitting)"`, `"horse 8m E (yours, saddled)"`, plus `"leading: cow"`; tags `pet:<type>`, `leading`, `tameable:<type>` for an untamed tameable within 16 whose item is held (or RIDE kinds).

**Menu options:** `tame_<type>` for up to 2 `tameable:` tags; `milk_cow` (bucket held, `cow` mob sighting ≤ 8); `shear_sheep` (shears, `sheep` sighting ≤ 8); `tie_lead` (`leading`) → `tie_lead{}`. Sample: holding 5 bones with a `wolf` mob sighting and a Pets section tag `tameable:wolf`.

**Tests:**
- `TameablesTest`: methods and items; `"fox"` empty.
- `AnimalsModuleTest`: `dye_sheep{color: "teal"}` → `Invalid` containing `unknown color`; menus.
- Game (animals spawned with no free will unless noted):
  - `tamesWolfWithBones`: wolf 3 blocks away, 16 bones → DONE; `isTame()`, owner is the bot; bones < 16.
  - `tameFailsWithWrongFood` (Review Focus 2): wolf, 5 wheat → FAILED containing `tamed with bone`; wheat 5.
  - `tamesHorseByRiding` (maxTicks 2400): horse (free will on, so it bucks) with `setTemper(getMaxTemper() - 1)` → DONE; `isTamed()`; the bot is not riding.
  - `saddlesTamedHorse`: tamed horse + saddle → `isSaddled()`; saddle consumed.
  - `leashesAndTiesCow`: cow, lead, oak fence 3 blocks away → `leash cow` then `tie_lead` → the cow's leash holder is a `LeashFenceKnotEntity`.
  - `milksCow`: bucket → `milk_bucket`.
  - `shearsSheep`: shears → `isSheared()`; ≥ 1 white_wool held.
  - `dyesSheepRed`: red_dye → `getColor() == DyeColor.RED`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Tame, saddle, lead, milk, shear and dye animals"` with the trailer.

---

### Task 9: Smithing, stonecutter, grindstone, loom, cartography and books

**Files:** Create in `main/java/com/aiciv/stations/`: `StationTarget.java` (find + approach + open, like milestone 3's `ContainerTarget`), `UpgradeNetheriteAction.java`, `TrimArmorAction.java`, `StonecutAction.java`, `GrindstoneAction.java` (disenchant and repair), `LoomAction.java`, `CartographyAction.java`, `WriteBookAction.java`, `StationNames.java` (pure); fill `StationsModule.java`. Tests `test/java/com/aiciv/stations/StationNamesTest.java`, `StationsModuleTest.java`; game tests in `gametest/StationGameTests.java` (prefix `TestStn`).

**Interfaces:**
- `StationTarget<M>`: `StationTarget(String blockId, Class<M> menu)`; `ActionStatus tick(BotContext)` → RUNNING / DONE when `menu()` is open / `FAILED "no <block> within 16 blocks"` / `"the <block> won't open"`; `M menu()`; `void close(BotContext)` (`MenuOps.close`). Every action closes the menu in `cancel` and `interrupted`, and before reporting.
- `StationNames` (pure): `static Optional<String> trimTemplate(String raw)` (`"coast"` / `"coast trim"` / `"coast_armor_trim_smithing_template"` → `coast_armor_trim_smithing_template`); `static Optional<String> netheriteResult(String diamondItem)` (`diamond_sword` → `netherite_sword`, all diamond tools and armor); `static String patternKey(String raw)` (lower case, spaces → `_`, `"bottom stripe"` → `stripe_bottom` via a small alias table: `bottom stripe → stripe_bottom`, `top stripe → stripe_top`, `cross → cross`, `border → border`, `creeper → creeper`, `flower → flower`, `skull → skull`, `globe → globe`, `piglin → piglin`, `flow → flow`, `guster → guster`, `mojang → mojang`).
- `UpgradeNetheriteAction(String item)`: needs the diamond item, `netherite_upgrade_smithing_template`, `netherite_ingot` (`FAILED "needs <list>"`); open `SmithingMenu` at a `smithing_table`; `MenuOps.putInto` template → `TEMPLATE_SLOT`, item → `BASE_SLOT`, ingot → `ADDITIONAL_SLOT`; result empty → close, `FAILED "the smithing table shows no result"`; `MenuOps.takeResult(RESULT_SLOT)`; DONE `upgraded diamond_sword to netherite_sword` (enchantments kept by vanilla). Timeout 600.
- `TrimArmorAction(String item, String template, String material)`: material = an item with `PROVIDES_TRIM_MATERIAL` matching the name (`iron_ingot`, `gold_ingot`, `copper_ingot`, `diamond`, `emerald`, `lapis_lazuli`, `quartz`, `redstone`, `amethyst_shard`, `netherite_ingot`, `resin_brick`); same slot flow; DONE when the result has `DataComponents.TRIM`. Timeout 600.
- `StonecutAction(String input, String output, int count)`: open `StonecutterMenu`; `putInto` input → `INPUT_SLOT` (all matching up to what `count` needs); for `i` in `0 until getNumberOfVisibleRecipes()`: `clickMenuButton(p, i)` and read slot `RESULT_SLOT` — the first whose item id matches `output` is used (unknown → close, `FAILED "the stonecutter can't make <output> from <input>; it offers: <ids>"`); repeat `takeResult` until `count` made or the input runs out; leftover input back (close returns it); DONE `cut 4 stone_bricks`. Timeout 1200.
- `GrindstoneAction(String item, boolean repair)`: disenchant: one enchanted matching item → `INPUT_SLOT`; repair: two matching damaged items → `INPUT_SLOT` and `ADDITIONAL_SLOT` (`FAILED "repair needs two <item>"`); `takeResult` (vanilla drops the XP orbs); DONE `removed enchantments from iron_pickaxe` / `repaired two iron_pickaxe into one (<n>% durability)`. Timeout 600.
- `LoomAction(String pattern, String color, String bannerOrNull)`: banner = held item in `Items.BANNER` (named colour if given); dye `Items.DYE.pick(DyeColor.byName(color))`; open `LoomMenu`; `putInto` banner → `getBannerSlot()`, dye → `getDyeSlot()`, a matching banner-pattern item (`PROVIDES_BANNER_PATTERNS`) → `getPatternSlot()` when held; index = the position in `getSelectablePatterns()` whose key path equals `StationNames.patternKey(pattern)` (none → close, `FAILED "the loom has no pattern <p>; it offers: <first 12 keys>"`, Review Focus 5); `clickMenuButton(p, index)`; `takeResult`; DONE `made a white_banner with red stripe_bottom`. Timeout 600.
- `CartographyAction(String action)`: `zoom` (map + `paper`), `copy` (map + `map`), `lock` (map + `glass_pane`); `putInto` map → `MAP_SLOT`, the other → `ADDITIONAL_SLOT`; `takeResult`; DONE `zoomed map #3 out to scale 1` / `copied map #3` / `locked map #3`. Timeout 600.
- `WriteBookAction(String text, String titleOrNull)`: needs `writable_book`; split `text` into pages of ≤ 1024 chars (≤ 10 pages; `Invalid` beyond); move the book into the hotbar and select it; `p.connection.handleEditBook(new ServerboundEditBookPacket(hotbarSlot, pages, Optional.ofNullable(title)))`; wait ≤ 10 ticks (the server applies it on a later task) until the slot holds a `written_book` (with title) or a `writable_book` whose pages equal `pages`; DONE `wrote 2 pages` / `signed the book "Diary"`. Timeout 40.

**Tools (StationsModule):** `upgrade_netherite` [upgradenetherite] (`item` required); `trim_armor` [trimarmor] (`item`, `template`, `material` required); `stonecut` (`input`, `output` required, `count` 1..64); `grindstone` (`item` required); `repair` (`item` required); `loom` (`pattern`, `color` required, `banner` optional); `cartography` (`action` required: zoom, copy or lock); `write_book` [writebook] (`text` required, `title` optional, 1..32 chars). Guide line: `Stations: upgrade_netherite, trim_armor at a smithing_table; stonecut; grindstone removes enchantments, repair joins two worn tools; loom patterns banners; cartography zooms/copies/locks maps; write_book (title signs it).`

**Snapshot section:** none (the stations appear under Blocks nearby since Task 1).

**Menu options:** `upgrade_<item>` (a `smithing_table` sighting ≤ 16, the template, an ingot and a diamond tool or armor piece held); `stonecut_stone_bricks` (stonecutter sighting, ≥ 4 `stone` held) → `stonecut{input: stone, output: stone_bricks, count: min(held, 16)}`; grindstone, loom, cartography and books are left to tool-mode models (they need judgement). Sample: smithing_table sighting, template, ingot, diamond_sword.

**Tests:**
- `StationNamesTest`: trim template aliases; `netheriteResult("diamond_hoe") == netherite_hoe`, `("iron_sword")` empty; pattern aliases.
- `StationsModuleTest`: `cartography{action: "fold"}` → `Invalid` containing `zoom, copy or lock`; `write_book` title of 33 chars → `Invalid`; menus.
- Game (station blocks set 2 blocks from the bot):
  - `upgradesDiamondSwordToNetherite`: diamond_sword (Sharpness I), template, ingot → `netherite_sword` still enchanted; template and ingot gone.
  - `trimsChestplate`: iron_chestplate + `coast_armor_trim_smithing_template` + `iron_ingot` → chestplate has `TRIM`.
  - `stonecutsStoneBricks`: 4 stone → 4 stone_bricks.
  - `grindstoneDisenchants`: an enchanted iron_pickaxe → no enchantments; ≥ 1 `ExperienceOrb` within 4.
  - `repairsTwoPickaxes`: two damaged iron_pickaxes → one, damage lower than either.
  - `loomMakesStripedBanner`: white banner + red dye → the banner's `BANNER_PATTERNS` has one layer `stripe_bottom` red.
  - `loomRejectsUnknownPattern` (Review Focus 5): `pattern: "dragon"` → FAILED containing `has no pattern`; banner and dye still held, menu closed.
  - `cartographyZoomsMap`: a filled map (scale 0) + paper → scale 1.
  - `writesAndSignsBook`: writable_book → `write_book{text: "Day 1: found iron.", title: "Diary"}` → a `written_book` with title `Diary`, author = bot name, page 1 = the text.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Use smithing tables, stonecutters, grindstones, looms, cartography tables and books"` with the trailer.

---

### Task 10: Oriented blocks and redstone in blueprints

**Files:** Create `building/BlockSpec.java` (pure), `building/PlacementRule.java` (pure), `building/OrientedPlacer.java`; modify `building/Blueprint.java`, `building/BuildPlan.java`, `building/BuildAction.java`, `building/BuildingModule.java` (description and guide line), `utility/UseBlockAction.java`, `utility/UtilityModule.java` (description). Tests `test/java/com/aiciv/building/BlockSpecTest.java`, `PlacementRuleTest.java`, new cases in `BlueprintTest.java` and `BuildPlanTest.java`; game tests in `gametest/RedstoneGameTests.java` (prefix `TestRed`).

**Interfaces:**
- `record BlockSpec(String id, Map<String, String> props)`; `static BlockSpec parse(String raw) throws BlueprintException`: `id[k=v,k=v]` with optional spaces; id through `BlockNames.normalise`; keys and values lower case; errors `legend value "<raw>" has an unclosed [`, `property "<p>" needs a value`. `String text()` → canonical `id[k=v,…]` (sorted keys).
- `Blueprint` gains a trailing component `Map<Character, Map<String, String>> states` with a compatibility constructor taking the old 6 arguments (`states` empty), so `Templates` and tests compile unchanged; `parseLegend` accepts `BlockSpec` syntax and fills both maps.
- Facing words: `facing` accepts `north|east|south|west|up|down` (absolute) and `forward|back|left|right` (relative to the blueprint: `forward` = the direction of increasing rows, away from the builder). `BuildPlan.of` resolves relative facings with the build `Facing` (`forward → (forwardX, forwardZ)`, `right → (rightX, rightZ)`, `left`, `back` the negatives) into absolute ones. `BuildPlan.Cell` gains `Map<String, String> props` (compatibility constructor without it); `materials` ignores props.
- Supported properties: `facing`, `delay` (repeater, 1..4), `mode` (comparator, `compare|subtract`), `face` (lever and buttons, `floor|wall|ceiling`), `axis` (logs, pillars: `x|y|z`), `powered` (lever only, `true|false`). Others → `BuildAction` start check `FAILED "<id>: property \"<p>\" can't be chosen by hand (supported: facing, delay, mode, face, axis, powered)"`; a value the block's `StateDefinition` rejects → `FAILED "<id> has no <p>=<v>"`.
- `PlacementRule` (pure): `enum Kind { HORIZONTAL_OPPOSITE, NEAREST_OPPOSITE, NEAREST_SAME, CLICKED_FACE_OPPOSITE, CLICKED_FACE, ATTACHED, AXIS, NONE }`; `static Kind of(String blockId)`: `repeater`, `comparator` → HORIZONTAL_OPPOSITE; `piston`, `sticky_piston`, `dispenser`, `dropper` → NEAREST_OPPOSITE; `observer` → NEAREST_SAME; `hopper` → CLICKED_FACE_OPPOSITE; `redstone_wall_torch`, `wall_torch`, ladders → CLICKED_FACE; `lever` and `*_button` → ATTACHED; ids ending `_log`, `_wood`, `_stem`, `_hyphae`, `basalt`, `quartz_pillar`, `bone_block`, `hay_block` → AXIS; else NONE. `record Need(Direction horizontalLook, Direction nearestLook, Direction supportSide)` (nulls mean "any"); `static Need need(Kind kind, Map<String, String> props)`:
  - HORIZONTAL_OPPOSITE: `horizontalLook = facing.getOpposite()`, support DOWN.
  - NEAREST_OPPOSITE: `nearestLook = facing.getOpposite()`.
  - NEAREST_SAME: `nearestLook = facing`.
  - CLICKED_FACE_OPPOSITE (hopper): `facing == DOWN` → support DOWN; else `supportSide = facing` (click the neighbour on the facing side, its face toward the hopper).
  - CLICKED_FACE (wall torch): `supportSide = facing.getOpposite()`.
  - ATTACHED: `face=floor` → support DOWN, `horizontalLook = facing`; `ceiling` → support UP, `horizontalLook = facing`; `wall` → `supportSide = facing.getOpposite()`.
  - AXIS: `axis=y` → support DOWN or UP; `x` → support EAST or WEST; `z` → NORTH or SOUTH (first that is solid).
- `OrientedPlacer` (in `BuildAction`'s place step for cells with props): (1) `Need`; (2) stand cell = the nearest cell within 4 horizontally and −2..+2 vertically of the target that is outside the plan's cells, has passable feet and head and a standable block below, and from whose eye point (feet + 1.62) the vector to the support face's hit point satisfies the need (`Direction.fromYRot(yawTo(vector))` equals `horizontalLook`; `Direction.getApproximateNearest(vector)` equals `nearestLook`) and is within 4.5; none → `FAILED "can't place <spec> from anywhere nearby (a player would have to look <dir>)"`; (3) walk there (`NearGoal` radius 0.6); (4) `BlockPlacer.placeAgainst(p, target, supportSide, item)` (the bot looks at the hit point, so its look is the one the rule needs); (5) verify each wanted property on the placed state; a mismatch → break it again (`ctx.breaker()`; it is the bot's own block) and `FAILED "placed <id> with <p>=<got> instead of <want>"`; (6) adjust by right-clicks with a free hand (`BlockUse.withFreeHand`, `BlockUse.click`), 4 ticks apart: repeater `delay d` → `d − 1` clicks; comparator `mode=subtract` → 1 click; lever `powered=true` → 1 click; verify.
- `UseBlockAction` also allows `repeater` (each use adds 1 delay, 4 → 1), `comparator` (toggles mode) and `daylight_detector` (inverts); DONE `set repeater at x y z to delay 3` / `comparator at x y z now subtract` / `daylight_detector at x y z now inverted`. Its refusal message lists them.
- `build` description adds: `Legend values may give block states, e.g. "repeater[facing=forward,delay=2]"; facing forward/back/left/right is relative to the blueprint.` (keep the function description ≤ its current length + 140).

**Tests:**
- `BlockSpecTest`: `"repeater[facing=north, delay=2]"` → id `repeater`, props `{facing: north, delay: 2}`; `"minecraft:Lever[face=wall]"` normalises; `"repeater[facing"` → unclosed message; plain `"stone"` → empty props.
- `BlueprintTest`: legend `{"R": "repeater[facing=north,delay=2]"}` → legend `repeater`, states `{facing: north, delay: 2}`.
- `BuildPlanTest`: `facing=forward` under build facing EAST → cell props `facing: east`; under SOUTH → `south`; `left` under NORTH → `west`; absolute `north` stays `north`.
- `PlacementRuleTest`: one `need` case per kind (repeater facing north → look south, support DOWN; piston up → nearest look DOWN; observer east → nearest look EAST; hopper north → support NORTH; lever wall facing east → support WEST; log axis x → support EAST/WEST).
- Game (`padding = 24`, stone floor):
  - `buildsRepeaterLineFacingNorth`: blueprint one layer, one column of 3 rows: `"d"`, `"R"`, `"d"` with `{d: redstone_wire, R: "repeater[facing=north,delay=3]"}`, facing S at a fixed corner; bot holds 2 redstone and 1 repeater → DONE; the repeater's `FACING` is north and `DELAY` 3.
  - `buildsPistonFacingUp`: `{"P": "piston[facing=up]"}` single cell → `FACING` up.
  - `buildsObserverFacingRelative`: `{"O": "observer[facing=forward]"}` with build facing E → `FACING` east.
  - `orientationFailsWhenNoStandingSpot`: the repeater cell enclosed so no stand cell exists → FAILED containing `can't place repeater`; nothing placed.
  - `useBlockSetsRepeaterDelay`: a placed repeater at delay 1 → `use_block` twice → delay 3.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Build redstone and other oriented blocks from blueprint block states"` with the trailer.

---

### Task 11: Milestone 4 acceptance

Runs after Tasks 2–10 are merged.

**Files:** Game tests in `gametest/EndgameGameTests.java` (prefix `TestEnd`); `README.md` (all milestone 4 actions in the actions table, the legend block-state syntax with the repeater example, the dimension rule, "routines and their steps" table, the tool-catalog size); spec "Changes made during milestone 4".

**Test `scriptedBotTravelsToTheNetherAndBack`** (`padding = 24`, maxTicks 6000): 16 × 16 stone arena; bot `TestEndNether` at (8.5, 1, 3.5) holding 14 obsidian, 1 flint_and_steel, 16 cobblestone. Scripted replies (`GameTestKit.turn`):
1. `build_portal {x,y,z = abs(6,1,8), facing: "S"}` (lights it).
2. `enter_portal {kind: "nether"}`.
3. `leave_nether {}`.
Assert in `succeedWhen`: the bot is in the overworld again within 6 blocks of the built portal; at some tick it was in `the_nether` (an `onEachTick` flag); its `Waypoints` hold a `portal` in each dimension with the right notes; the decision log has ≥ 3 decisions and no line contains `"error"`. (Missing nether: the Global Constraints fallback.) Record the finishing tick in the spec.

**Test `scriptedBotOpensTheEndPortal`** (`padding = 24`, maxTicks 3000): a 12-frame ring at y 1 with 10 frames filled; bot `TestEndPortal` holding 2 ender_eye. Replies: 1. `fill_end_portal {}`; 2. `enter_portal {kind: "end"}`. Assert: the bot arrives in `the_end` (flag), its `Waypoints` has an `arrival` in `the_end`; decision log clean. Then remove the bot at once (the test End may hold a live dragon fight).

**Catalog size:** a unit test `CatalogSizeTest` prints the JSON length of `ToolCatalog.tools()` and asserts it is below 60,000 characters; the spec records the measured size and tool count.

**Live acceptance (hand to the user):** (1) in a survival world, `/ai spawn Ember gemini-35-flash "An adventurer who wants to reach the nether and bring back blaze rods."`, give it time and chat goals; report which of portal, nether, fortress and rods it reached and the LLM calls from `logs/aiciv/Ember.jsonl`. (2) a second bot asked in chat to cross a lake by boat, ride a tamed horse and glide with an elytra; report what worked.

- [ ] Write the tests; run `./gradlew build test runGametest --no-daemon`; update README and spec; commit `"Prove a bot can reach the nether and back and open the End portal"` with the trailer.

---

## Parallel schedule

- Task 1 alone (Parts A and B).
- Then any three of Tasks 2–10 at a time, each in its own worktree; suggested waves: **[2, 5, 7]**, then **[3, 6, 8]**, then **[4, 9, 10]**. None of them depends on another's code; Task 3's routines use only Task 1 and core actions, and Task 4's exit uses Task 1's `EnterPortalAction`.
- Task 11 after all of them.

## After this milestone

- The tool catalog is large (about 92 functions). If a provider's token limits bite, a later milestone can send only the modules relevant to the bot's situation (dimension, held items) in tool mode; menu mode is unaffected.
- Milestone 5's `endgame.json` should list `go_to_nether`, `get_blaze_rods`, `make_eyes`, `go_to_stronghold`, `go_to_end` and `leave_end` as extra candidates (data only).

## Self-review notes

- Spec coverage: portal build/cast/light → Task 2; dimension handling, waypoints, `enter_portal`, End credits → Task 1; fortress and blazes → Task 2; eyes, triangulation, stronghold, portal room, filling, entering the End → Task 3; crystals, cages, perched head, breath, void, exit → Tasks 4 and 1; boats, horses, pigs, striders, camels, minecarts → Task 5; elytra and pearls → Task 6; buckets, bone meal, shears (pumpkins; leaves through `mine`), fire, potions, throwables, maps, compass → Task 7; taming, leads, milk, shear, dye → Task 8; smithing (netherite and trims), stonecutter, grindstone, loom, cartography, books → Task 9; redstone blueprints and `use_block` tuning → Task 10; routines + primitives → `Routine` (Task 1) and each endgame task; acceptance → Task 11.
- Shared files after Task 1: none for Tasks 2–9; Task 10 alone edits `building/*` and `utility/UseBlockAction`/`UtilityModule`.
- Names kept for Milestone 5: `build_portal`, `enter_portal`, `find_fortress`, `hunt_blazes{count}`, `throw_eye`, `locate_stronghold`, `fill_end_portal`, `fight_dragon`, snapshot labels `nether_portal`, `nether_bricks`, `end_portal_frame`, tag `dragon_dead`.
