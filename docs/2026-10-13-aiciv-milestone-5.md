# AiCiv Milestone 5 ("Autopilot bots") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bots that need no LLM: an AutoClef-style goal planner drives them through the same actions the LLM bots use, a personality engine picks which goal to pursue and how, and a chat layer speaks template lines (optionally written by a cheap LLM), selectable per bot as `/ai spawn Rex autopilot-miner`.

**Architecture:** Task 1 adds the plumbing: an `autopilot` decision mode resolved from the model key, a synchronous decision path in `BrainScheduler` (no ThinkQueue, no rate limiter), per-bot `AutopilotBrain` state, the pure `Situation` the brain decides from, the shared skeleton types (goals, steps, personality, chat) and stub files that later tasks own. Tasks 2 (planner + survival goals), 3 (personality engine) and 4 (chat) then run in parallel worktrees, each filling only its own package and its own stub. Task 5 (world goals: build, farm, trade, defend, explore, social, store) starts once Tasks 2 and 3 are merged and may run in parallel with Task 4. Task 6 is the acceptance run. Everything the planner does is a `ToolCall` built by `ActionFactory`, so autopilots inherit survival fairness and every action Milestone 4 adds.

**Tech Stack:** As milestones 1–3: MC 26.3 (unobfuscated Mojang names), Fabric loader 0.19.5, fabric-api 0.161.0+26.3, loom 1.18.2, Java 25, JUnit 5.13.4, Gson (shipped with Minecraft).

**Spec:** `docs/superpowers/specs/2026-10-11-aiciv-design.md` — section "Milestone 5 — Autopilot bots" (and the Actions, Think cycle and fairness rules it builds on).

**Format note:** same contract style as milestones 2 and 3: exact files, interfaces, numbers and test cases; implementers write the code against these contracts with compile and test feedback. Where an algorithm is subtle, the plan gives it as code.

## Global Constraints

Everything in milestones 1–3's Global Constraints still holds (26.3 stack, `implementation` deps, no `net.minecraft.client`, worktree `E:\Projects cursor\Mcmods\.worktrees\aiciv` branch `aiciv`, API keys never in the repo or any log line, bots always Survival, reach/timing/fairness rules, game access only on the server thread, game tests own their arenas and bot-name prefixes). Every commit ends with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Additionally:

- **Same actions, no shortcuts.** An autopilot acts only through `ToolCall`s passed to the existing `BrainScheduler.runCalls` → `ActionFactory.create`. No autopilot code calls an `Action` constructor, touches inventories, places or breaks blocks itself.
- **Dynamic actions.** The planner learns which actions exist from `ActionFactory.knownKeys()` (copied into `Situation.actions()` once per decision). A step whose required action is missing is skipped (optional step) or blocks its goal with `needs <action> (not available yet)`; it never throws. Milestone 4 action names appear **only** in the data file `resources/assets/aiciv/autopilot/endgame.json`.
- **Cheap.** `AutopilotEngine.decide` is pure (no world access) and must take < 0.5 ms on a normal `Situation` (tests: 2,000 decisions < 1 s). World reads per decision: one `Perceiver.capture` plus inventory counting (`SituationCapture`). World reads between decisions: one `Pulse.read` per bot every `autopilot.checkTicks` (20) ticks = one `getEntitiesOfClass(Monster, box ±8)`. At most `autopilot.maxDecisionsPerTick` (4) autopilot decisions per server tick, round robin.
- **Config defaults (exact):** `autopilot.thinkSeconds 5`, `gapMillis 500`, `checkTicks 20`, `maxDecisionsPerTick 4`, `chatCooldownSeconds 30`, `chatLlmCooldownSeconds 60`, `globalChatGapMillis 2000`, `chatLlmMaxAgeSeconds 20`. Urgent turns ≥ 40 ticks apart per bot. Step backoff `min(6000, 600 << (n-1))` ticks for the n-th cooldown. A blocked goal is skipped for 1200 ticks. Requests expire after 2400 ticks, at most 4 pending. Mention replies ≥ 5 s apart per bot. Banter ≥ 6000 ticks per bot pair. LLM chat lines ≤ 100 characters asked, ≤ 120 kept.
- **Identifiers:** personality ids `[a-z0-9_]{1,24}`; implicit model keys `autopilot-<id>` (and `autopilot:<id>` where no Brigadier word is involved: aiciv.json, roster, panel). Brigadier `StringArgumentType.word()` does not accept `:`, so commands use `autopilot-<id>`. Home structure name `Situation.homeName(bot)` = bot name lower-cased + `_home`, cut to 32 characters.
- **Goal ids (exact, `GoalIds.ALL` in this order):** `get_wood_tools, get_stone_tools, get_iron_tools, get_iron_armor, get_food, defend, social, build_base, store_items, farm, trade_emeralds, mine_diamonds, explore, beat_game`. Task 2 implements the first five plus `mine_diamonds` and `beat_game` in `CoreGoals`; Task 5 implements the other seven in `WorldGoals`.
- **Decision log shape** (same file, same fields as LLM decisions plus four): `time, trigger, snapshotChars, provider: "autopilot", mode: "autopilot", model, personality, goal, step, thought, calls, problems` and `urgent` when the trigger was a pulse. Chat lines are logged as `{time, mode: "autopilot-chat", event, source: "template"|"llm", model (chat model or null), text}`. Decisions that keep the running action are not logged.
- **Parallel work rules.** After Task 1: Task 2 creates files only in `com.aiciv.autopilot.goal` and owns `goal/StepPlanner.java` and `goal/CoreGoals.java`; Task 3 only in `com.aiciv.autopilot.personality`, owns `personality/Personalities.java`, and edits exactly one line of `autopilot/Engines.java`; Task 4 only in `com.aiciv.autopilot.chat`, and edits exactly one line of `autopilot/ChatDirectors.java` and adds the resource `assets/aiciv/autopilot/phrases.json`; Task 5 only in `com.aiciv.autopilot.goal.world` and owns `goal/WorldGoals.java` and `autopilot/Requests.java`. Each owns the game-test class Task 1 creates for it. No two of Tasks 2–5 modify the same existing file. README and spec updates happen only in Task 6.
- Run from `AiCiv/`: `./gradlew test runGametest --no-daemon`.

## Review Focus

1. **Livelock: a step "succeeds" but its done-check never turns true** (mine finished but the drops were out of reach, craft finished a different wood type, a tier mismatch): the bot would repeat the same step forever, one decision every half second. Expected: three finishes of the same step without it becoming done count as one failure and go through the normal retry/explore/backoff path. → Task 2 test `stepThatFinishesWithoutProgressBacksOff`.
2. **Milestone 4 actions missing, renamed, or rejecting our arguments:** `beat_game` must stop at that step with a readable reason in the thought and the panel, the engine must move to other goals for 60 s, and an `Invalid` from the factory counts as a step failure, never a crash or a tight loop. → Task 2 tests `beatGameStopsAtFirstUnavailableStep`, `invalidMappedCallCountsAsFailure`; Task 3 test `blockedGoalIsSkippedForAMinute`.
3. **Interrupt storms while busy** (a zombie circling at 7–9 blocks, night falling during a five-minute mine, damage over time): replanning must not restart the same long action every pulse. Expected: an urgent turn only on a state transition, ≥ 40 ticks apart, and a decision that picks the step already running keeps it. → Task 1 tests `pulseFiresOncePerTransition`, `sameStepWhileBusyIsKept`.
4. **Chat floods and LLM chat failures with 15+ autopilots:** server-wide gap, per-bot cooldown scaled by `social`, loners nearly silent; a failed, rate-limited or late LLM chat falls back to the template or says nothing; no line ever contains an unfilled `{placeholder}`. → Task 4 tests `globalGapHoldsAcrossBots`, `llmFailureFallsBackToTemplate`, `lateLlmReplyIsDropped`, `noShippedTemplateHasUnknownPlaceholders`.
5. **A broken or partial `personalities.json`** (bad JSON, unknown goal id, trait 3.0, missing phrases, a model naming a personality that does not exist): the shipped defaults keep working, problems are reported by `/ai reload` and the server log, traits are clamped, and an autopilot whose personality vanished moves to `defaultModel` like a bot whose model vanished. → Task 3 tests `badUserFileKeepsDefaults`, `unknownGoalIdIsReported`, `traitsAreClamped`; Task 1 test `reloadMovesBotWithVanishedPersonality`.

---

## File Structure

All paths under `AiCiv/src/`. Package `com.aiciv.autopilot` and its sub-packages are new.

```
main/java/com/aiciv/
  autopilot/AutopilotSpec, AutopilotModels, Situation, SituationCapture, Decision, ChatCue,
            AutopilotEvent, AutopilotMemory, Tuning, Pulse, PulseWatch, AutopilotTurns, Milestones,
            AutopilotBrain, AutopilotEngine, Engines (T3 edits 1 line), SimpleEngine,
            AutopilotChat, ChatPort, ChatDirectors (T4 edits 1 line), Calls, GoalIds,
            Requests (stub, T5 owns), AutopilotView                              (Task 1)
  autopilot/goal/Goal, Step, Move, PlanResult, GoalLibrary                       (Task 1)
  autopilot/goal/StepPlanner (T1 minimal, T2 owns), CoreGoals (T1 stub, T2 owns),
                 WorldGoals (T1 stub, T5 owns)                                   (Task 1)
  autopilot/goal/Costs, Gather, Steps, EndgameMap, EndgameCondition,
                 GetWoodTools, GetStoneTools, GetIronTools, GetIronArmor, GetFood,
                 MineDiamonds, BeatGame                                          (Task 2)
  autopilot/personality/Traits, Personality                                      (Task 1)
  autopilot/personality/Personalities (T1 stub, T3 owns), Behaviour, GoalChooser,
                 PersonalityEngine                                               (Task 3)
  autopilot/chat/Templates, PhraseBook, ChatPolicy, LlmChat, TemplateChat        (Task 4)
  autopilot/goal/world/BuildBase, StoreItems, Farm, TradeEmeralds, Defend,
                 Explore, Social                                                 (Task 5)
  config/AiCivConfig, bot/Bot, bot/BotManager, bot/BrainScheduler, bot/LiveContext,
  bot/AiCommands, panel/PanelSnapshot, perception/Perceiver, AiCiv              (Task 1, modify)
  gametest/GameTestKit (modify), AutopilotGameTests, AutopilotGoalGameTests,
           AutopilotChatGameTests, AutopilotWorldGameTests                       (Task 1 creates)
resources/assets/aiciv/autopilot/personalities.json                             (Task 1 minimal, T3 owns)
resources/assets/aiciv/autopilot/endgame.json                                   (Task 2)
resources/assets/aiciv/autopilot/phrases.json                                   (Task 4)
resources/assets/aiciv/panel/index.html, fabric.mod.json                        (Task 1)
test/java/com/aiciv/autopilot/...                                               unit tests named per task
```

Verified facts used below (from the current code, 2026-10-13): `ActionFactory.knownKeys()` returns normalised keys (lower case, `_`/`-` removed) of core actions ∪ module tools; `ToolModules.normalise(String)`; `BotBrain.pollWake(now, busy)`, `dispatched(now)`, `replied(summary)`, `setTiming(idleMillis, mentionMillis, eventWindow)`, `setActionEndedGap(millis)`; `ActionExecutor.tick` returns `"finished: a, b"` or `"failed: <desc>: <reason>[ (skipped: ...)]"`; `Perceiver.capture(AiPlayer, String, String, Predicate<ServerPlayer>, KnownBlocks, Attachments)`; `SnapshotSection(label, items, emptyWord, tags)` with tags such as `structure:<name>`, `crop_ripe`, `farmland_free`, `water_near`, `villager:<profession>`, `offer:<profession>:<index>:<result>:<costA>:<countA>`, `chest_item:<id>`; Structures items read `"<name> by <owner> at x y z (<d>m <dir>)"`; entity types live in `EntityTypes` (e.g. `EntityTypes.ZOMBIE`); `LlmService.trySend(model, system, user, JsonArray tools)` with an empty array sends no `tools` field and the reply's `plainText()` carries the text; `LlmService.peek(model)` checks a route without spending budget.

---

### Task 1: Autopilot plumbing, skeleton types and panel fields

Sequential; Tasks 2, 3 and 4 start after it merges.

**Files:**
- Create every Task 1 file in the File Structure above.
- Create `resources/assets/aiciv/autopilot/personalities.json` holding only `{"version": 1, "base": {"traits": {}, "goals": {}, "phrases": {}}, "personalities": {"default": {"name": "Default", "description": "An ordinary survivor."}}}` (Task 3 replaces it).
- Modify `config/AiCivConfig.java`, `bot/Bot.java`, `bot/BotManager.java`, `bot/BrainScheduler.java`, `bot/LiveContext.java`, `bot/AiCommands.java`, `panel/PanelSnapshot.java`, `resources/assets/aiciv/panel/index.html`, `perception/Perceiver.java`, `AiCiv.java`, `gametest/GameTestKit.java`, `resources/fabric.mod.json` (add the four new game-test classes), `test/.../panel/PanelSnapshotTest.java`.
- Tests: `test/java/com/aiciv/autopilot/AutopilotModelsTest.java`, `SituationTest.java`, `AutopilotMemoryTest.java`, `PulseWatchTest.java`, `AutopilotTurnsTest.java`, `MilestonesTest.java`, `AutopilotBrainTest.java`, `goal/StepPlannerBasicsTest.java`, `config/AiCivConfigTest.java` (new cases); game tests in `gametest/AutopilotGameTests.java` (prefix `TestAp`).

**Interfaces (produces):**

Config (`AiCivConfig`):
```java
public AutopilotConfig autopilot = new AutopilotConfig();
public static final class AutopilotConfig {
    public int thinkSeconds = 5;            // idle re-think while not busy
    public int gapMillis = 500;             // least time after an action ends before the next decision
    public int checkTicks = 20;             // pulse period
    public int maxDecisionsPerTick = 4;
    public int chatCooldownSeconds = 30;
    public int chatLlmCooldownSeconds = 60;
    public int globalChatGapMillis = 2000;
    public int chatLlmMaxAgeSeconds = 20;
}
// ModelConfig gains:
public String type = "llm";          // "llm" or "autopilot"
public String personality;           // autopilot only
public String chatModel;             // autopilot only: an LLM model key that writes chat lines
public Integer thinkSeconds;         // autopilot only: overrides autopilot.thinkSeconds
public boolean isAutopilot() { return "autopilot".equals(type); }
```
- `loadOrCreate`: null `autopilot` → new; per model null `type` → `"llm"`.
- `validate()` = `validate(null)`; new `public List<String> validate(Set<String> personalityIds)` (null skips personality checks). For autopilot models the provider/model-id/decisionMode checks are skipped and these apply, verbatim: `model 'x' has unknown type 'y' (use llm or autopilot)`, `autopilot model 'x' has no personality`, `autopilot model 'x' uses unknown personality 'y' (known: a, b, c)` (ids sorted), `autopilot model 'x' uses unknown chatModel 'y'`, `autopilot model 'x' chats with 'y', which is an autopilot; chatModel must be an LLM model`, `autopilot model 'x' cannot have a fallback`; plus `autopilot.thinkSeconds must be at least 1`, `autopilot.checkTicks must be at least 1`, `autopilot.maxDecisionsPerTick must be at least 1`. `isMenuMode` is false for autopilot models; `typesafeFallback` skips them.

Model resolution:
```java
public record AutopilotSpec(String modelKey, String personality, String chatModel, int thinkSeconds) {}
public final class AutopilotModels {
    public static final Pattern IMPLICIT = Pattern.compile("autopilot[-:]([a-z0-9_]{1,24})");
    /** A configured model with type "autopilot", else autopilot-<id>/autopilot:<id> (key lower-cased) for a known personality. */
    public static Optional<AutopilotSpec> resolve(String modelKey, AiCivConfig config, Set<String> personalityIds);
    /** "autopilot-<id>" for every personality, sorted. */
    public static List<String> implicitKeys(Set<String> personalityIds);
}
```
A configured autopilot model whose personality is unknown resolves to empty (so it counts as an unknown model). `thinkSeconds` = model override, else `config.autopilot.thinkSeconds`, clamped ≥ 1. Implicit keys get `chatModel` null.

Shared pure types (`com.aiciv.autopilot`):
```java
public final class GoalIds { public static final List<String> ALL = List.of(/* the 14 ids in Global Constraints order */); }
public final class Calls { /** key, value, ...; values String or Number (same rule as OptionMenu.call). */ public static ToolCall of(String name, Object... kv); }
public record ChatCue(String category, Map<String, String> vars, boolean priority) {}
public record Tuning(int maxRetries, int exploreDistance, int retreatHealth, double hysteresis, boolean homeAtNight,
                     int keepCount, boolean helpsWhenAsked, double chatCooldownFactor, int depositWhenFree) {
    public static final Tuning DEFAULT = new Tuning(3, 48, 8, 0.15, false, 16, true, 1.0, 0);
}
public record Decision(List<ToolCall> calls, String thought, String goal, String step, String flag,
                       List<ChatCue> cues, boolean keepCurrent) {
    public static Decision waiting(String thought);                       // no calls, goal/step null
    public static Decision of(PlanResult.Next next, String thought);      // calls/cues from the move, flag from the step
    public Decision kept();                                               // same, keepCurrent true
}
public sealed interface AutopilotEvent {
    record Spawned() ...; record Heard(String speaker, String text, boolean mentioned, boolean speakerIsAi) ...;
    record Hurt(String attacker, int health) ...; record ActionsEnded(String summary) ...;
    record Died(String message) ...; record OtherDied(String name, String message) ...;
    record PlayerJoined(String name, boolean ai) ...; record NightFell() ...;
    record Received(String from, String text) ...; record Milestone(String what) ...;
    record GoalChanged(String goal, String summary) ...;
    record StepBlocked(String goal, String step, String reason, int failures) ...;
}
```

`Situation` (pure record; everything a decision may read):
```java
public record Situation(String name, String dimension, int x, int y, int z, int health, int food, boolean night, long tick,
        Map<String, Integer> inventory, Set<String> worn, String holding, int freeSlots,
        List<Sighting> blocks, List<Sighting> players, List<Sighting> mobs, List<String> hostiles,
        List<String> remembered, Set<String> tags, List<String> structures, Set<String> actions,
        String currentAction, boolean busy, Set<String> flags, Map<String, Long> lastDone,
        List<AutopilotMemory.Request> requests, Tuning tuning, String pinnedGoal) { ... }
```
Helper methods (exact semantics; `SituationTest` covers each):
- `int count(String id)`; `int countLike(String query)` (exact id, else the sum over ids containing `query` with a trailing `s` removed); `int logs()` (ids ending `_log` or `_stem`); `int planks()` (ending `_planks`); `int logEquivalents()` = `logs() + planks() / 4`.
- `static int toolTierOf(String material)`: `wooden 1, golden 1, stone 2, copper 2, iron 3, diamond 4, netherite 5`, else 0. `int toolTier(String kind)` = max tier of held/inventory ids `<material>_<kind>` (kind ∈ pickaxe, axe, sword, shovel, hoe). `boolean hasTool(String item)` = `toolTier(kind) >= toolTierOf(material)` for tool ids, else `count(item) > 0`.
- `int wornTier(String piece)` (piece ∈ helmet, chestplate, leggings, boots; `leather 1, golden 2, copper 2, chainmail 3, iron 3, diamond 4, netherite 5`) over `worn`. `int wornAtLeast(int tier)` = how many of the four pieces reach it.
- `static final Set<String> FOODS` (the exact set in `OptionMenu.FOODS`); `int foodItems()`; `static final List<String> RAW_FOODS = List.of("beef","porkchop","chicken","mutton","rabbit","cod","salmon","potato")`.
- `boolean sees(String label, int within)` (a block sighting with that label and distance ≤ within); `boolean knows(String label)` = `sees(label, 24)` or a `remembered` line starting `label + " at "`; `boolean tableNear()` = `sees("crafting_table", 4) || count("crafting_table") > 0`; `boolean furnaceNear()` = `sees("furnace", 16) || count("furnace") > 0`.
- `int mobCount(String label, int within)`; `Optional<Sighting> nearestHostile(int within)` (mobs whose label ∈ `hostiles`, distance ≤ within).
- `boolean hasTag(String tag)`; `List<String> tagsWithPrefix(String prefix)`.
- `static String homeName(String bot)`; `Optional<int[]> home()` (parses the Structures item starting `homeName(name) + " by "` with `^(\S+) by (\S+) at (-?\d+) (-?\d+) (-?\d+)`); `double distanceTo(int[] p)`.
- `boolean hasAction(String name)` = `actions.contains(ToolModules.normalise(name))`.
- `boolean inOverworld()/inNether()/inEnd()` (dimension `overworld`, `the_nether`, `the_end`).
- `boolean flag(String f)`; `boolean doneWithin(String goalSlashStep, long ticks)` (`lastDone` entry ≥ `tick - ticks`).
- `List<String> humans()` / `List<String> aiPlayers()` (player sighting labels ending ` [human]` / ` [AI]`, tag removed, within 32).

`SituationCapture.capture(AiPlayer p, SnapshotData data, boolean busy, String currentAction, AutopilotMemory mem, Tuning tuning, String pinnedGoal, long tick)` (server thread): inventory from `getInventory().getNonEquipmentItems()`; `worn` from `Perceiver.equipment(p)` split on `", "` (make it public); `freeSlots` = empty stacks among those 36; `hostiles` = new public `Perceiver.hostileLabels(List<Sighting> mobs, int within)` with `within = 16` (the loop moved out of `BrainScheduler.menuContext`, which then calls it); `tags` = union of all section tags; `structures` = items of the section labelled `Structures`; `actions` = `ActionFactory.knownKeys()` (cached in a static field after first use; the module list never changes at run time); `dimension` = `p.level().dimension().identifier().getPath()`; `night` = `p.level().isDarkOutside()`; flags, lastDone and requests copied from `mem`.

Memory (pure, per bot):
```java
public final class AutopilotMemory {
    public record InFlight(String goal, String step, String flag, long tick) {}
    public record Request(String kind, String from, String item, int count, long tick) {}   // kind GIVE, COME, FOLLOW
    public String goal(); public String step(); public int decisions();
    public void stepStarted(String goal, String step, String flag, long tick);   // a different step resets the old step's finish count
    public Optional<InFlight> inFlight();
    /** "finished..." → failures and cooldown of the in-flight step cleared, lastDone[goal/step] = tick, flag set, finishes++.
     *  "failed..." → fail(goal, step, tick, maxRetries). Either way the in-flight step is cleared. */
    public void outcome(String summary, long tick, int maxRetries);
    public void fail(String goal, String step, long tick, int maxRetries);  // count++; count >= maxRetries → retryAt = tick + backoffTicks(count - maxRetries + 1)
    public int failures(String goal, String step);
    public boolean coolingDown(String goal, String step, long tick);       // tick < retryAt
    public long retryAt(String goal, String step);
    public int finishesWithoutDone(String goal, String step);
    public void resetFinishes(String goal, String step);
    public void block(String goal, long untilTick); public boolean blocked(String goal, long tick);
    public Set<String> flags(); public Map<String, Long> lastDone(); public Set<String> bragged();
    public void heard(Request r);                      // one per speaker (newer replaces), at most 4 (oldest dropped)
    public List<Request> requests(long tick);          // drops those older than 2400 ticks
    public void handled(Request r);
    public static long backoffTicks(int n) { return Math.min(6000L, 600L << Math.min(n - 1, 10)); }
}
```

Pulse (urgent replanning without a snapshot):
```java
public record Pulse(int health, int food, boolean night, String hostileWithin8, long tick) {   // hostileWithin8 = entity type path or null
    public static Pulse read(AiPlayer p, long tick);   // one getEntitiesOfClass(Monster.class, box inflated 8, LivingEntity::isAlive)
}
public final class PulseWatch {   // pure, one per bot
    public static final int MIN_GAP_TICKS = 40;
    /** The urgent reason, or null. The first pulse only records. A transition suppressed by the gap is kept and returned once the gap has passed. */
    public String check(Pulse now, Tuning t);
}
```
Rules, checked in this order against the previous pulse: `night && !prev.night` → `"night fell"`; `food <= 6 && prev.food > 6` → `"hungry"`; `health <= t.retreatHealth() && hostile != null && (prev.health > t.retreatHealth() || prev.hostile == null)` → `"health low"`; `hostile != null && prev.hostile == null` → `"<hostile> nearby"`.

Turns (pure round robin):
```java
public final class AutopilotTurns {
    public record Turn(String bot, String trigger, String detail) {}       // trigger: URGENT, HURT, MENTIONED, ACTION_ENDED, IDLE
    public void offer(String bot, String trigger, String detail);          // a waiting bot keeps its place; the more urgent trigger wins (URGENT first, then WakeReason order)
    public List<Turn> take(int max);                                       // oldest first; taken bots leave the queue
    public void remove(String bot); public int size();
}
```

Milestones (pure): `static final List<String> ALL = List.of("wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "iron_armor", "diamond", "home", "diamond_pickaxe", "nether", "ender_eye", "end")`; `static List<String> reached(Situation s)` (pickaxes via `hasTool`, `iron_armor` = `wornTier("chestplate") >= 3`, `diamond` = `count("diamond") > 0`, `home` = `home().isPresent()`, `nether`/`end` by dimension, `ender_eye` = `count("ender_eye") > 0`).

Goal skeleton (`com.aiciv.autopilot.goal`):
```java
public interface Step {
    String id();
    boolean done(Situation s);
    default Set<String> needs() { return Set.of(); }     // action names as the catalog spells them
    default boolean optional() { return false; }
    default String flag() { return null; }                // set in memory when this step's actions finish
    Move next(Situation s);
}
public record Move(List<ToolCall> calls, String why, Kind kind, List<ChatCue> cues) {
    public enum Kind { ACT, GATHER, BLOCKED }
    public static Move act(String why, ToolCall... calls); public static Move gather(String why, ToolCall... calls);
    public static Move blocked(String why); public Move withCues(ChatCue... cues);
}
public interface Goal {
    String id(); String summary(); List<Step> steps();
    default boolean achieved(Situation s) { return steps().stream().filter(st -> !st.optional()).allMatch(st -> st.done(s)); }
    default double need(Situation s) { return achieved(s) ? 0 : 1; }      // 0..1
}
public sealed interface PlanResult {
    record Next(Goal goal, Step step, Move move) implements PlanResult {}
    record Blocked(Goal goal, Step step, String reason) implements PlanResult {}
    record Achieved(Goal goal) implements PlanResult {}
}
public final class GoalLibrary {   // never edited after Task 1
    public static List<Goal> all() { List<Goal> out = new ArrayList<>(CoreGoals.list()); out.addAll(WorldGoals.list()); return out; }
    public static Optional<Goal> byId(String id);
}
public final class CoreGoals { public static List<Goal> list() { return List.of(); } }    // Task 2 fills
public final class WorldGoals { public static List<Goal> list() { return List.of(); } }   // Task 5 fills
```
`StepPlanner` (Task 1 minimal version; Task 2 rewrites it): `public PlanResult next(Goal goal, Situation s, AutopilotMemory mem, Tuning tuning)` — first step not `done`; its missing `needs` → optional: skip, else `Blocked(..., "needs <a, b> (not available yet)")`; `Move.Kind.BLOCKED` → `Blocked(..., move.why())`; else `Next`; no step left → `Achieved`.

Personality skeleton (`com.aiciv.autopilot.personality`):
```java
public record Traits(double brave, double greedy, double social, double tidy, double patient) {
    public Traits { /* each clamped to [-1, 1]; NaN → 0 */ }
    public static final Traits NEUTRAL = new Traits(0, 0, 0, 0, 0);
    public Map<String, Double> asMap();   // insertion order brave, greedy, social, tidy, patient
}
public record Personality(String id, String name, String description, Traits traits,
                          Map<String, Double> goals, Map<String, List<String>> phrases) {}
public final class Personalities {   // Task 1 stub: reads only "default" from the shipped file; Task 3 owns the body
    public static Personalities defaults();
    public static Personalities load(Path configDir);   // never throws; problems() lists what was wrong
    public Optional<Personality> get(String id); public Set<String> ids(); public List<Personality> all();
    public List<String> problems(); public Personality fallback();   // the "default" personality
}
```

Engine and chat seams:
```java
public interface AutopilotEngine {
    Decision decide(Situation s, AutopilotMemory mem, Personality p, String trigger, long tick);
    default Tuning tuning(Personality p) { return Tuning.DEFAULT; }
}
public final class Engines { public static AutopilotEngine create() { return new SimpleEngine(new StepPlanner()); } }   // Task 3 changes this line
// SimpleEngine: first goal of GoalLibrary.all() whose plan is Next → Decision.of(next, "goal <id>: step <step>: <why>"); none → Decision.waiting("nothing to do")
public interface AutopilotChat {
    AutopilotChat NONE = /* ignores everything */;
    void observe(AutopilotEvent event, long nowMillis);
    void cue(ChatCue cue, long nowMillis);
    void tick(ChatPort port, long nowMillis);   // server thread, every tick; says at most one line
}
public interface ChatPort {
    String botName(); String modelOf(String botName);   // null when that name is not an online bot
    Situation situation();                               // the last one captured, or null
    void say(String text);                               // BotManager.botSays
    boolean globalGapOk(long nowMillis); void spoke(long nowMillis);
    boolean llmReady(String modelKey);                   // LlmService.peek(modelKey).isPresent()
    Optional<CompletableFuture<LlmService.Outcome>> sendLlm(String modelKey, String system, String user);  // trySend with an empty tools array
    void onServer(Runnable r);                           // server.execute
    void log(JsonObject record);                         // decision log append
}
public final class ChatDirectors {
    public static AutopilotChat create(AutopilotSpec spec, Personality p, Tuning t, AiCivConfig.AutopilotConfig cfg) { return AutopilotChat.NONE; }   // Task 4 changes this line
}
public final class Requests {   // Task 5 owns the body
    public static Optional<AutopilotMemory.Request> parse(String text, String botName, boolean mentioned, long tick) { return Optional.empty(); }
}
```

Per-bot brain:
```java
public final class AutopilotBrain {
    public AutopilotBrain(AutopilotSpec spec, Personality p, AutopilotEngine engine, AutopilotChat chat);
    public AutopilotSpec spec(); public Personality personality(); public AutopilotMemory memory();
    public AutopilotChat chat(); public Tuning tuning(); public Situation lastSituation();
    public Decision decide(Situation s, String trigger, long tick, long nowMillis);
    public void actionsEnded(String summary, long tick, long nowMillis);   // memory.outcome(summary, tick, tuning.maxRetries()) + chat ActionsEnded
    public String urgent(Pulse pulse, long nowMillis);                     // PulseWatch.check; "night fell" also sends NightFell to chat
    public void observe(AutopilotEvent e, long nowMillis);                 // Heard → Requests.parse → memory.heard; every event → chat
    public void respec(AutopilotSpec spec, Personality p, AutopilotEngine engine, AutopilotChat chat);   // keeps memory
    public AutopilotView view();
}
public record AutopilotView(String personality, String personalityName, String goal, String step, String thought,
                            Map<String, Double> traits, String chatModel) {}
```
`decide` does, in order: on the first decision (`memory.decisions() == 0`) add every `Milestones.reached(s)` to `bragged` silently; afterwards each newly reached milestone → `chat.observe(new Milestone(m))`; `Decision d = engine.decide(s, mem, p, trigger, tick)`; goal differs from `memory.goal()` and is non-null → `GoalChanged(goal, summary)` (summary from `GoalLibrary.byId`, else the id); if `s.busy()` and `memory.inFlight()` has the same goal and step and the same calls (`ToolCall` name and `args` JSON equal) and `trigger` is not `ACTION_ENDED` → return `d.kept()`; else, if `d.calls()` non-empty, `memory.stepStarted(goal, step, flag, tick)`; when `d.step()` starts with `request_`, `memory.handled(oldest request)`; every cue → `chat.cue`; store `lastThought`, `lastSituation`.

**Scheduler and manager wiring:**
- `Bot` gains `public AutopilotBrain autopilot;` (null for LLM bots).
- `BotManager`: new constructor `(server, config, llm, rosterFile, decisionLog, clock, Personalities personalities)`; the old 6-arg one passes `Personalities.defaults()`. `reload(config, llm)` keeps working; new `reload(config, llm, personalities)`. `boolean knowsModel(String key)` = `llm.knowsModel(key) || AutopilotModels.resolve(key, config, personalities.ids()).isPresent()`, used by `spawn`, `setModel`, `restoreAll` and `reload` (a bot whose autopilot no longer resolves moves to `defaultModel`, as unknown models do). `modelKeys()` = configured keys ∪ `implicitKeys`, sorted. `attach(Bot b)` (called from `join`, `setModel` and `reload`): resolve; autopilot → create or `respec` `b.autopilot` with `Engines.create()`, `ChatDirectors.create(...)`, and set `b.brain.setTiming(spec.thinkSeconds() * 1000L, mentionCooldownSeconds * 1000L, eventWindow)` and `setActionEndedGap(config.autopilot.gapMillis)`; LLM → `b.autopilot = null` and the normal timings. After `join` of an autopilot: `observe(new Spawned())`. `String modelOf(String bot)`.
- Event hooks: `tickStart` — when `ended != null` and `b.autopilot != null`, also `b.autopilot.actionsEnded(ended, tick, now)`. `routeChat` — autopilot bots that hear a line get `Heard(speaker, text, mentioned, speakerIsAi)`. `onDamage` → `Hurt(cause, round(health))`. `onDeath` → for the dying autopilot `Died(message)`; for any dying `ServerPlayer`, every other autopilot within `hearingRange` (same level) gets `OtherDied(name, message)`. New `onJoin(ServerPlayer p)` → every autopilot gets `PlayerJoined(name, p instanceof AiPlayer)`; `AiCiv` registers `ServerPlayConnectionEvents.JOIN` (fabric-networking-api-v1) to call it, and `BotManager.join` calls it for bots. `LiveContext.tell` also sends `Received(<bot name>, text)` when the receiver has an autopilot.
- `statusLines` for autopilots adds `Personality: <Name> (<id>) brave 0.4, greedy 0.5, social -0.3, tidy 0.2, patient 0.6`, `Goal: <goal> / step <step>`, `Thought: <thought>`, `Chat model: <key or none>`; `listLines` appends ` (goal <goal>)`.
- `BrainScheduler.tick(now)`: `long tick = server.getTickCount()`. For each bot: autopilot bots never enter the ThinkQueue:

```java
if (b.autopilot != null) {
    if (b.brain.paused()) continue;
    String urgent = tick % m.config().autopilot.checkTicks == 0 ? b.autopilot.urgent(Pulse.read(b.player, tick), now) : null;
    WakeReason r = b.brain.pollWake(now, b.executor.busy());
    if (urgent != null) turns.offer(b.name, "URGENT", urgent);
    else if (r != null) turns.offer(b.name, r.name(), null);
    b.autopilot.chat().tick(chatPorts.computeIfAbsent(b.name, n -> new LiveChatPort(m, b)), now);
    continue;
}
```
then `for (Turn t : turns.take(m.config().autopilot.maxDecisionsPerTick)) decideAutopilot(...)` (each wrapped in `try/catch (RuntimeException)` → `m.fault(name, "autopilot", e)` and `b.brain.replied("error")`), then the existing LLM loop unchanged. `decideAutopilot`: capture the snapshot exactly as `think` does; `Situation s = SituationCapture.capture(...)` (pinned goal = `b.brain.goal()` when it equals a goal id, case-insensitive, else null); `b.brain.dispatched(now)`; `Decision d = b.autopilot.decide(s, t.trigger(), tick, now)`; `d.keepCurrent()` → `b.brain.replied("kept: " + current)` and return (not logged); build the log (fields in Global Constraints); no calls → `calls []`, `problems []`, `replied("waiting")`, append; else `int started = runCalls(b, log, d.calls(), null, null)` — **`runCalls` now returns how many actions it started** — and `started == 0` → `b.autopilot.actionsEnded("failed: invalid: " + problems, tick, now)`. `LiveChatPort` (package-private in `bot`) implements `ChatPort` over the manager; the global gap is one `long lastAutopilotChatMillis` field in `BrainScheduler`. Removal, pause and death call `turns.remove(name)`.
- `AiCommands`: model suggestions use `BotManager.modelKeys()`.
- `AiCiv.loadServices`: `Personalities.load(configDir)`; its problems are logged and appended to the list `reload()` returns; `validate(personalities.ids())` replaces `validate()` there.
- `PanelSnapshot.BotView` gains a trailing `AutopilotView autopilot` (null for LLM bots) plus a compatibility constructor with the old 18 arguments; `mode` is `"autopilot"` for autopilot models; `models` adds every implicit key as `ModelView(key, "autopilot", <personality id>, "autopilot")` and configured autopilot models the same way. `index.html`: badge class `autopilot`; for autopilot bots the Goal row shows `goal · step`, and new rows `Personality` (`Name — brave 0.4 · greedy 0.5 · social -0.3 · tidy 0.2 · patient 0.6`) and `Chat` (chat model or `templates`).
- `GameTestKit`: `record AutoSpawned(BotManager manager, Bot bot, Path dir, ScriptedBackend backend)`; `AutoSpawned spawnAutopilot(GameTestHelper h, String name, double x, double z, String model)` (config below, ticks `tickStart` and `tickEnd` every tick); `spawnAutopilot(h, name, x, z, model, String configJson, Map<String, ChatBackend> backends)`; `String autopilotConfig(String extraModelsJson)` = scripted provider + `"scripted"` model + `extraModelsJson` + `"defaultModel": "scripted", "decisionGapSeconds": 0, "autopilot": {"thinkSeconds": 1, "gapMillis": 0}`.
- New game-test classes, each with `classLoads` (`helper.succeed()`), registered in `fabric.mod.json`: `AutopilotGameTests` (Tasks 1 and 6), `AutopilotGoalGameTests` (Task 2), `AutopilotChatGameTests` (Task 4), `AutopilotWorldGameTests` (Task 5).

**Tests:**
- `AutopilotModelsTest`: `autopilot-miner` and `autopilot:Miner` resolve when `miner` is known, `autopilot-pirate` does not; a configured `{"type": "autopilot", "personality": "guard", "chatModel": "groq-llama", "thinkSeconds": 2}` resolves with those values; a configured autopilot with an unknown personality resolves empty; `implicitKeys` sorted.
- `AiCivConfigTest` (new cases): each autopilot validation message above, verbatim; an autopilot model with no provider produces no provider problem; `isMenuMode` false for it.
- `SituationTest`: `logEquivalents` with 2 logs + 9 planks = 4; `toolTier("pickaxe")` with stone and wooden = 2; `hasTool("wooden_sword")` true when holding `iron_sword`; `wornAtLeast(3)` with iron helmet + diamond boots = 2; `foodItems` counts bread 3 + apple 2 = 5; `knows("iron_ore")` from a remembered line; `home()` parses `ada_home by Ada at 10 64 -3 (12m NE)`; `homeName("A_Very_Long_Bot_Name_X")` ≤ 32 chars; `hasAction("place_chest")` with key `placechest`; `humans()` strips the tag.
- `AutopilotMemoryTest`: three failures with `maxRetries 3` → cooling down for exactly 600 ticks, the next cooldown 1200, capped at 6000; a `finished:` clears failures and sets the step flag and `lastDone`; `finishesWithoutDone` counts finishes of the same step and resets when another step starts; requests: one per speaker, 4 max, expired after 2400 ticks.
- `PulseWatchTest` (`pulseFiresOncePerTransition`, Review Focus 3): first pulse returns null; day→night returns `night fell` once, staying night returns null; a zombie appearing returns `zombie nearby`, staying returns null, leaving and returning 30 ticks later is held and returned at tick +40; health 7 with a hostile and `retreatHealth 8` → `health low`.
- `AutopilotTurnsTest`: 10 bots offered, `take(4)` gives 4, 4, 2 in offer order; re-offering a waiting bot keeps its place; `URGENT` replaces `IDLE`.
- `MilestonesTest`: `reached` for a situation with stone_pickaxe, diamond and a home.
- `AutopilotBrainTest` (fake engine, recording chat): `sameStepWhileBusyIsKept` (Review Focus 3: busy, same goal/step/calls, trigger `HURT` → `keepCurrent`; trigger `ACTION_ENDED` → not kept); first decision brags nothing, gaining `iron_pickaxe` later emits `Milestone("iron_pickaxe")` once; a goal change emits `GoalChanged`; `actionsEnded("failed: ...")` reaches `memory.failures`.
- `StepPlannerBasicsTest`: done steps skipped; missing non-optional action → `Blocked` with `needs light_portal (not available yet)`; missing optional → skipped; `Move.blocked` → `Blocked` with its reason.
- `PanelSnapshotTest`: the old constructor still compiles; a view with an `AutopilotView` serialises `autopilot.goal`.
- Game `AutopilotGameTests`:
  - `autopilotBotSpawnsAndDecides`: 8 × 8 stone floor; `spawnAutopilot(h, "TestApSpawn", 3.5, 3.5, "autopilot-default")` → within 100 ticks the decision log has a line with `"mode":"autopilot"`, `"model":"autopilot-default"` and `"provider":"autopilot"`; no line contains `"error"`; `backend.requests()` is empty; `manager.statusLines("TestApSpawn")` contains a line starting `Personality: Default`; `PanelSnapshot.capture(manager)` has a bot view with `autopilot.personality == "default"` and a model view `autopilot-default`; then remove the bot.
  - `reloadMovesBotWithVanishedPersonality` (Review Focus 5): spawn `TestApVanish` on `autopilot-default`; `manager.reload(config, llm, personalitiesWithout("default"))` (a `Personalities` built from a temp config dir whose file defines only `nobody`) → the bot's model is `scripted` and `bot.autopilot == null`.

- [ ] Write the failing unit tests; run `./gradlew test --no-daemon` and see them fail.
- [ ] Implement; `./gradlew test runGametest --no-daemon` passes (all milestone 1–3 tests too).
- [ ] Commit `"Add the autopilot decision mode, its situation and the panel fields"` with the trailer.

---

### Task 2: Goal planner and survival goal library

Runs after Task 1, **in parallel with Tasks 3 and 4**.

**Files:** Rewrite `autopilot/goal/StepPlanner.java` and `autopilot/goal/CoreGoals.java`; create in `autopilot/goal/`: `Costs.java`, `Gather.java`, `Steps.java`, `EndgameMap.java`, `EndgameCondition.java`, `GetWoodTools.java`, `GetStoneTools.java`, `GetIronTools.java`, `GetIronArmor.java`, `GetFood.java`, `MineDiamonds.java`, `BeatGame.java`; resource `resources/assets/aiciv/autopilot/endgame.json`. Tests `test/java/com/aiciv/autopilot/goal/CostsTest.java`, `GatherTest.java`, `StepPlannerTest.java`, `CoreGoalsTest.java`, `BeatGameTest.java`, `EndgameMapTest.java`, `PlannerSpeedTest.java`, and the shared fixture `test/java/com/aiciv/autopilot/Sit.java` (a builder for `Situation` with fluent `inv("oak_log", 3)`, `block("iron_ore", 5)`, `mob("cow", 2, 6, "N")`, `tag(...)`, `actions(...)` defaulting to `ActionFactory.knownKeys()`, `dim(...)`, `tick(...)`); game tests in `gametest/AutopilotGoalGameTests.java` (prefix `TestApGoal`). Before starting, if `docs/superpowers/plans/2026-10-13-aiciv-milestone-4.md` exists, add its action names to the candidate lists in `endgame.json` (data only; never remove the names below).

**Interfaces:**
- `Costs` (pure): `static Map<String, Integer> raw(String item)` — exact table (materials: `log` in log-equivalents, `cobblestone`, `iron_ingot`, `diamond`, `blaze_rod`, `ender_pearl`, `flint`, `wool`):

| item | raw | needs a table |
|---|---|---|
| `wooden_pickaxe` | log 2 | yes |
| `wooden_sword`, `wooden_hoe`, `wooden_shovel` | log 1 | yes |
| `wooden_axe` | log 2 | yes |
| `stone_pickaxe`, `stone_axe` | cobblestone 3, log 1 | yes |
| `stone_sword`, `stone_hoe` | cobblestone 2, log 1 | yes |
| `iron_pickaxe`, `iron_axe` | iron_ingot 3, log 1 | yes |
| `iron_sword` | iron_ingot 2, log 1 | yes |
| `iron_helmet` / `iron_chestplate` / `iron_leggings` / `iron_boots` | iron_ingot 5 / 8 / 7 / 4 | yes |
| `diamond_pickaxe` | diamond 3, log 1 | yes |
| `diamond_sword` | diamond 2, log 1 | yes |
| `crafting_table` | log 1 | no |
| `furnace` | cobblestone 8 | yes |
| `chest` | log 2 | yes |
| `shield` | iron_ingot 1, log 2 | yes |
| `bucket` | iron_ingot 3 | yes |
| `flint_and_steel` | iron_ingot 1, flint 1 | no |
| `white_bed` | wool 3, log 1 | yes |
| `ender_eye` | blaze_rod 1 per 2 eyes (rounded up), ender_pearl 1 per eye | no |
| `bread` | wheat 3 | yes |

  `static boolean needsTable(String item)`; `static Map<String, Integer> forCraft(String item, int count, Situation s)` = `raw × count` (ender_eye rounding as above) plus `log 1` when `needsTable(item) && !s.tableNear()`; `static int have(String material, Situation s)`: `log` → `logEquivalents()`, `cobblestone` → `count("cobblestone") + count("cobbled_deepslate")`, `wool` → sum of ids ending `_wool`, else `count(material)`; `static Map<String, Integer> missing(Map<String, Integer> need, Situation s)` (insertion order kept).
- `Gather` (pure): `static Move move(String material, int n, Situation s)` — exact:
  - `log` → `gather("need <n> more log", mine{block: "log", count: max(n, 3)})`.
  - `cobblestone` → `toolTier("pickaxe") < 1` → `blocked("needs a pickaxe for cobblestone")`; else `gather(..., mine{block: "stone", count: n})`.
  - `coal` → `gather(..., mine{block: "coal_ore", count: n})` (needs a pickaxe as above).
  - `raw_iron` → tier < 2 → `blocked("needs a stone pickaxe for iron")`; else `mine{block: "iron_ore", count: n}`.
  - `iron_ingot` → let `raw = count("raw_iron")`; in order: `raw < n` → `move("raw_iron", n - raw, s)`; `!furnaceNear()` and `have("cobblestone") < 8` → `move("cobblestone", 8 - have, s)`; fuel ok = `count("coal") + count("charcoal") >= ceil(n / 8.0)` or `logs() >= n`; not ok → `move("coal", ceil(n / 8.0), s)`; else `act("smelt <n> raw_iron", smelt{item: "raw_iron", count: n})`.
  - `diamond` → tier < 3 → `blocked("needs an iron pickaxe for diamonds")`; not `knows("diamond_ore")` and `y > 16` → `gather("dig down toward diamond level", goTo{x: x + 8, y: max(-54, y - 40), z})`; else `mine{block: "diamond_ore", count: n}`.
  - `blaze_rod` → `act(..., attack{target: "blaze"}, collect{item: "blaze_rod"})`; `ender_pearl` → `act(..., attack{target: "enderman"}, collect{item: "ender_pearl"})`; `wool` → sheep `mobCount("sheep", 24) >= 1` → `attack{target: "sheep"}, collect{item: "white_wool"}` else `gather("look for sheep", explore{...})`.
  - anything else → `blocked("don't know how to get <material>")`.
  - `needs()` of each move = the call names it uses.
- `Steps` (pure factories): `static Step craft(String item, int count)` — id = item; `done` = `hasTool(item)` for tools (tier-aware), `wornTier`-or-held for armor, else `count(item) >= count`; `next` = first entry of `Costs.missing(Costs.forCraft(item, count, s), s)` → `Gather.move`, none missing → `act("craft <count> <item>", craft{item, count})`; `needs` `{"craft", "mine"}`. `static Step of(String id, Predicate<Situation> done, Function<Situation, Move> next, Set<String> needs, boolean optional, String flag)` for everything else. Shared instances `Steps.WOODEN_PICKAXE`, `STONE_PICKAXE`, `IRON_PICKAXE` are reused by every goal that needs them.
- `StepPlanner.next(Goal goal, Situation s, AutopilotMemory mem, Tuning t)` (full version):

```java
for (Step step : goal.steps()) {
    if (step.done(s)) continue;
    Set<String> missing = step.needs().stream().filter(n -> !s.hasAction(n)).collect(toCollection(TreeSet::new));
    if (!missing.isEmpty()) {
        if (step.optional()) continue;
        return new Blocked(goal, step, "needs " + String.join(", ", missing) + " (not available yet)");
    }
    if (mem.finishesWithoutDone(goal.id(), step.id()) >= 3) {          // Review Focus 1
        mem.resetFinishes(goal.id(), step.id());
        mem.fail(goal.id(), step.id(), s.tick(), t.maxRetries());
    }
    if (mem.coolingDown(goal.id(), step.id(), s.tick())) {
        if (step.optional()) continue;
        long secs = (mem.retryAt(goal.id(), step.id()) - s.tick() + 19) / 20;
        return new Blocked(goal, step, "step " + step.id() + " failed " + mem.failures(goal.id(), step.id()) + " times; retry in " + secs + " s");
    }
    Move move = step.next(s);
    if (move.kind() == Move.Kind.BLOCKED) {
        if (step.optional()) continue;
        return new Blocked(goal, step, move.why());
    }
    int failures = mem.failures(goal.id(), step.id());
    if (move.kind() == Move.Kind.GATHER && failures >= 2 && failures % 2 == 0 && s.hasAction("explore")) {
        String dir = List.of("N", "E", "S", "W").get(Math.floorMod(goal.id().hashCode() + failures / 2, 4));
        move = Move.gather(move.why() + " (not found nearby; exploring " + dir + ")",
                Calls.of("explore", "direction", dir, "distance", t.exploreDistance()));
    }
    return new Next(goal, step, move);
}
return new Achieved(goal);
```
- Goals (`CoreGoals.list()` returns them in this order; `summary()` in brackets; need = default unless given):
  - `GetWoodTools` [`get wood tools`]: `WOODEN_PICKAXE`, `craft("wooden_sword", 1)`.
  - `GetStoneTools` [`get stone tools`]: `WOODEN_PICKAXE`, `STONE_PICKAXE`, `craft("stone_sword", 1)`.
  - `GetIronTools` [`get iron tools`]: `WOODEN_PICKAXE`, `STONE_PICKAXE`, `IRON_PICKAXE`, `craft("iron_sword", 1)`.
  - `GetIronArmor` [`get iron armor`]: `WOODEN_PICKAXE`, `STONE_PICKAXE`, then per piece in `chestplate, helmet, leggings, boots` a step `iron_<piece>`: done `wornTier(piece) >= 3`; next: holds `iron_<piece>` or `have("iron_ingot") >= cost` → `act("put on iron armor", gear_up{material: "iron", shield: false})`, else `Gather.move("iron_ingot", cost - have, s)`; needs `{"gear_up", "mine", "smelt"}`. need = `1 - wornAtLeast(3) / 4.0`.
  - `GetFood` [`get food`]: one step `food`, done `foodItems() >= 8`; next, first that applies: (a) a held raw food and fuel (`count("coal") + count("charcoal") >= 1 || logs() >= 1`) and `hasAction("smelt")` → `smelt{item: raw, count: min(held, 8)}`; (b) `hasTag("crop_ripe") && hasAction("harvest")` → `harvest{count: 32}`; (c) for `cow, pig, sheep, chicken, rabbit` in that order, `mobCount(a, 24) >= 2` → `act(..., attack{target: a}, collect{item: drop(a)})` (drops `beef, porkchop, mutton, chicken, rabbit`; never the last visible animal); (d) `count("fishing_rod") > 0 && sees("water", 16) && hasAction("fish")` → `fish{catches: 3}`; (e) `count("wheat") >= 3` → `craft{item: "bread", count: wheat / 3}`; (f) `gather("look for food", explore{direction: <rotating>, distance: tuning.exploreDistance()})`. need = `foodItems() >= 8 ? 0 : (food <= 6 && foodItems() == 0 ? 1.0 : (8 - foodItems()) / 8.0 * (food <= 14 ? 1.0 : 0.5))`.
  - `MineDiamonds` [`mine diamonds`]: wood/stone/iron pickaxe steps, then `diamonds`: done `count("diamond") >= 3`, next `Gather.move("diamond", 3 - have, s)`.
  - `BeatGame` [`beat the game`]: `WOODEN_PICKAXE`, `STONE_PICKAXE`, `IRON_PICKAXE`, `craft("iron_sword", 1)`, the four iron-armor steps marked **optional**, a `food` step (as `GetFood`, done at 8), then one step per `endgame.json` entry. need = `flag("dragon_dead") ? 0 : 1`.
- `endgame.json` (exact):

```json
{"version": 1, "steps": [
 {"id": "nether_portal",    "doneWhen": "PORTAL_READY",     "candidates": [{"action": "build_portal", "args": {}}, {"action": "nether_portal", "args": {}}, {"action": "make_portal", "args": {}}]},
 {"id": "enter_nether",     "doneWhen": "IN_NETHER_OR_PAST","candidates": [{"action": "enter_portal", "args": {}}, {"action": "use_portal", "args": {}}, {"action": "travel", "args": {"to": "nether"}}]},
 {"id": "find_fortress",    "doneWhen": "FORTRESS_FOUND",   "candidates": [{"action": "find_fortress", "args": {}}, {"action": "locate_fortress", "args": {}}, {"action": "find_structure", "args": {"structure": "fortress"}}]},
 {"id": "blaze_rods",       "doneWhen": "RODS_READY",       "candidates": [{"action": "hunt_blazes", "args": {"count": 6}}, {"action": "attack", "args": {"target": "blaze"}}]},
 {"id": "ender_pearls",     "doneWhen": "PEARLS_READY",     "candidates": [{"action": "barter", "args": {"want": "ender_pearl"}}, {"action": "hunt_endermen", "args": {"count": 12}}, {"action": "attack", "args": {"target": "enderman"}}]},
 {"id": "ender_eyes",       "doneWhen": "EYES_READY",       "candidates": [{"action": "craft", "args": {"item": "ender_eye", "count": 12}}]},
 {"id": "return_overworld", "doneWhen": "IN_OVERWORLD_OR_END", "candidates": [{"action": "enter_portal", "args": {}}, {"action": "use_portal", "args": {}}, {"action": "travel", "args": {"to": "overworld"}}]},
 {"id": "find_stronghold",  "doneWhen": "STRONGHOLD_FOUND", "candidates": [{"action": "find_stronghold", "args": {}}, {"action": "throw_eye", "args": {}}, {"action": "locate_stronghold", "args": {}}]},
 {"id": "enter_end",        "doneWhen": "IN_END",           "candidates": [{"action": "activate_end_portal", "args": {"x": "{x}", "y": "{y}", "z": "{z}"}}, {"action": "fill_end_portal", "args": {}}, {"action": "enter_portal", "args": {}}]},
 {"id": "kill_dragon",      "doneWhen": "DRAGON_DEAD",      "flag": "dragon_dead", "candidates": [{"action": "fight_dragon", "args": {}}, {"action": "attack", "args": {"target": "ender_dragon"}}]}
]}
```
- `EndgameCondition` (enum, pure; `eyes = count("ender_eye") >= 12`, `rods = count("blaze_rod") * 2 + count("blaze_powder") >= 12 || eyes`, `pearls = count("ender_pearl") >= 12 || eyes`): `PORTAL_READY` = `!inOverworld() || sees("nether_portal", 16) || (rods && pearls)`; `IN_NETHER_OR_PAST` = `inNether() || inEnd() || (rods && pearls)`; `FORTRESS_FOUND` = `sees("nether_bricks", 24) || rods || inEnd()`; `RODS_READY` = `rods`; `PEARLS_READY` = `pearls`; `EYES_READY` = `eyes || inEnd()`; `IN_OVERWORLD_OR_END` = `inOverworld() || inEnd()`; `STRONGHOLD_FOUND` = `sees("end_portal_frame", 24) || inEnd()`; `IN_END` = `inEnd()`; `DRAGON_DEAD` = `flag("dragon_dead")`.
- `EndgameMap.load()` (classpath `/assets/aiciv/autopilot/endgame.json`, cached) → `List<Entry>`; `record Entry(String id, EndgameCondition doneWhen, String flag, List<Candidate> candidates)`; `record Candidate(String action, JsonObject args)`; `Step toStep(Entry e)`: `needs` is empty (it checks candidates itself); `next(s)` = first candidate with `s.hasAction(action)` → `act("<id> with <action>", ToolCall.of(action, filled args))` where string args `"{x}"`, `"{y}"`, `"{z}"` become the bot's integer coordinates; none → `blocked("needs one of " + names + " (not available yet)")`.

**Tests:**
- `CostsTest`: `forCraft("wooden_pickaxe", 1, noTable)` = log 3 and = log 2 with a table sighted at 3; `stone_pickaxe` = cobblestone 3, log 1(+1); `ender_eye` × 12 = blaze_rod 6, ender_pearl 12; `missing` with 2 logs + 4 planks against log 3 is empty.
- `GatherTest`: `log` 3 → `mine log 3`; `cobblestone` with no pickaxe → blocked `needs a pickaxe for cobblestone`; `iron_ingot` 3 with raw_iron 3, coal 1, a furnace sighted → `smelt raw_iron 3`; with raw_iron 0 and a stone pickaxe → `mine iron_ore 3`; with raw 3, no fuel, no logs → `mine coal_ore 1`; with raw 3, coal 1, no furnace, cobblestone 2 → `mine stone 6`; `diamond` at y 70 with an iron pickaxe and no diamond known → `goTo` with y 30.
- `StepPlannerTest`: two failures on a GATHER step with `explore` available → an `explore` move with `distance` = `tuning.exploreDistance()`; three failures with `maxRetries 3` → `Blocked` with `failed 3 times; retry in 30 s`, and at `retryAt` → `Next` again; `stepThatFinishesWithoutProgressBacksOff` (Review Focus 1: a step finishes 3 × 3 times while its done-check stays false → it is cooling down after the ninth finish); `invalidMappedCallCountsAsFailure` (Review Focus 2: `memory.outcome("failed: invalid: unknown action 'build_portal'...")` for the in-flight `nether_portal` step raises its failures).
- `CoreGoalsTest`: empty inventory → `GetIronTools` gives step `wooden_pickaxe`, move `mine log 3`; stone_pickaxe + 3 raw_iron + coal + furnace sighted → step `iron_pickaxe`, `smelt raw_iron 3`; iron_pickaxe held → step `iron_sword`; `GetFood` with 3 cows → `attack cow` + `collect beef`, with 1 cow → explore, with 4 beef + 1 coal → `smelt beef 4`, with tag `crop_ripe` → `harvest`; `GetFood.need` values at foodItems 0/4/8 and food 6/14/20; `GetIronArmor` with 8 ingots and a worn iron helmet → `gear_up`.
- `BeatGameTest` (`beatGameStopsAtFirstUnavailableStep`, Review Focus 2): iron pickaxe + iron sword + 8 bread, actions = today's `knownKeys()` → `Blocked` at `nether_portal` whose reason names `build_portal`; with `buildportal` added to the actions → `Next` with `ToolCall("build_portal", {})`; in `the_nether` with 6 blaze rods and 12 ender pearls → step `ender_eyes`, `craft ender_eye 12`; armor steps skipped when `gear_up` is missing.
- `EndgameMapTest`: the resource loads; ids unique; every candidate's args is an object; `{x}` is replaced by the bot's x as a number.
- `PlannerSpeedTest`: 10,000 `StepPlanner.next` calls over every `CoreGoals` goal on a mid-game situation finish in < 1 s.
- Game `AutopilotGoalGameTests.woodStepMinesAndCrafts` (`padding = 24`, maxTicks 2000): 8 × 8 dirt floor, two oak trees (4 logs each) at (1,1,1) and (6,1,6); `spawnAutopilot(h, "TestApGoalWood", 3.5, 3.5, "autopilot-default")` holding 8 bread → the bot holds a `wooden_pickaxe` within 2000 ticks; the decision log has ≥ 2 autopilot lines whose `goal` is one of `get_wood_tools, get_stone_tools, get_iron_tools` and no line contains `"error"`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Plan survival goals step by step with retries, exploring and backoff"` with the trailer.

---

### Task 3: Personality engine, traits and personalities.json

Runs after Task 1, **in parallel with Tasks 2 and 4**. Its tests use in-test fake goals, so they do not need Task 2.

**Files:** Rewrite `autopilot/personality/Personalities.java` and `resources/assets/aiciv/autopilot/personalities.json`; create in `autopilot/personality/`: `Behaviour.java`, `GoalChooser.java`, `PersonalityEngine.java`; change the one line in `autopilot/Engines.java` to `return new PersonalityEngine(GoalLibrary::all, new StepPlanner());`; add `/ai personalities` to `bot/AiCommands.java` (lines: `<id>: <Name> — <description>`) — the only other shared edit. Tests `test/java/com/aiciv/autopilot/personality/TraitsTest.java`, `PersonalitiesTest.java`, `BehaviourTest.java`, `GoalChooserTest.java`, `PersonalityEngineTest.java`, `EngineSpeedTest.java`.

**personalities.json (exact numbers).** Format: `{"version": 1, "base": {...}, "personalities": {"<id>": {"name", "description", "traits": {...}, "goals": {...}, "phrases": {...}}}}`. `base.goals`: `get_wood_tools 0.9, get_stone_tools 0.8, get_iron_tools 0.6, get_iron_armor 0.4, get_food 0.9, defend 0.5, social 0.6, build_base 0.4, store_items 0.6, farm 0.3, trade_emeralds 0.2, mine_diamonds 0.2, explore 0.2, beat_game 0.0`. Personalities (traits brave/greedy/social/tidy/patient; goal weights override base):

| id | name | traits | goal weights |
|---|---|---|---|
| default | Default | 0 / 0 / 0 / 0 / 0 | (base) |
| miner | Miner | 0.4 / 0.5 / -0.3 / 0.2 / 0.6 | get_iron_tools 0.9, mine_diamonds 1.0, get_iron_armor 0.6, build_base 0.3, farm 0.1, trade_emeralds 0.1, explore 0.3 |
| builder | Builder | -0.2 / -0.2 / 0.3 / 0.9 / 0.8 | build_base 1.0, get_stone_tools 0.9, get_iron_tools 0.4, farm 0.4, mine_diamonds 0.1, store_items 0.8 |
| farmer | Farmer | -0.5 / -0.4 / 0.4 / 0.6 / 0.9 | farm 1.0, get_food 1.0, build_base 0.6, trade_emeralds 0.4 |
| trader | Trader | -0.3 / 0.8 / 0.9 / 0.3 / 0.4 | trade_emeralds 1.0, farm 0.5, build_base 0.4, social 0.7 |
| guard | Guard | 1.0 / -0.5 / 0.5 / 0.0 / 0.3 | defend 1.0, get_iron_armor 0.9, get_iron_tools 0.8, social 0.6 |
| explorer | Explorer | 0.6 / 0.0 / 0.2 / -0.6 / -0.4 | explore 1.0, get_food 0.9, get_stone_tools 0.8, get_iron_tools 0.5, build_base 0.1 |
| speedrunner | Speedrunner | 0.7 / 0.6 / -0.5 / -0.8 / -0.9 | get_iron_tools 1.0, beat_game 1.0, get_iron_armor 0.8, mine_diamonds 0.6, build_base 0.0, farm 0.0, social 0.1, store_items 0.0 |
| hermit | Hermit | -0.6 / 0.3 / -1.0 / 0.7 / 0.7 | build_base 0.9, farm 0.7, get_iron_tools 0.6, explore 0.2, social 0.0, defend 0.2 |
| chatty | Chatty | -0.2 / -0.8 / 1.0 / 0.0 / 0.0 | social 1.0, farm 0.4, build_base 0.4, explore 0.3, trade_emeralds 0.5 |

Descriptions are one sentence each (e.g. miner: `Happiest underground; digs for iron and diamonds and keeps the best ore.`). The shipped `phrases` objects are empty; phrases live in Task 4's `phrases.json`.

**Interfaces:**
- `Personalities.load(Path configDir)`: shipped file first, then `configDir/personalities.json` if present. A user entry with the id of a shipped one is merged field by field (traits key by key, goals key by key, phrases category by category); a new id inherits `base`; a user `base` merges into the shipped `base`. Every personality's goal map = base goals overlaid with its own. Problems (all added to `problems()`, verbatim prefixes): `personalities.json: <Gson message>` (the whole user file is ignored), `personality '<id>': id must match [a-z0-9_]{1,24}` (entry skipped), `personality '<id>': unknown goal '<g>'` (that weight dropped), `personality '<id>': trait '<t>' is <v>; clamped to <c>`, `personality '<id>': unknown trait '<t>'`. `fallback()` = `default`, which always exists.
- `Behaviour` (pure, `static Tuning tuning(Traits t)`): `maxRetries = clamp(3 + round(2·patient), 1, 5)`; `exploreDistance = 32 + round(32·max(0, brave))`; `retreatHealth = clamp(round(10 − 4·brave), 6, 14)`; `hysteresis = 0.15·(1 + patient)`; `homeAtNight = tidy >= 0.3 || brave <= -0.3`; `keepCount = greedy <= -0.5 ? 0 : clamp(round(16 + 32·greedy), 0, 64)`; `helpsWhenAsked = social >= -0.5`; `chatCooldownFactor = 1 − 0.5·social`; `depositWhenFree = tidy >= 0.5 ? 9 : tidy >= 0 ? 4 : 0`.
- `GoalChooser` (pure): `record Scored(Goal goal, double utility)`; `static List<Scored> rank(List<Goal> goals, Personality p, Situation s, AutopilotMemory mem, Tuning t, long tick)`: skip goals with `mem.blocked(id, tick)`; `w = p.goals().getOrDefault(id, 0.0)`; `social` gets `w = 0` when `!t.helpsWhenAsked()`, `store_items` gets `w = 0` when `t.depositWhenFree() == 0`; `u = w · goal.need(s)`, `+ t.hysteresis()` when `id.equals(mem.goal()) && need > 0`, `+ 1.0` when `id.equals(s.pinnedGoal())`; keep `u >= 0.05`; sort by `u` descending, ties by `GoalLibrary` order.
- `PersonalityEngine(Supplier<List<Goal>> goals, StepPlanner planner)` implements `AutopilotEngine`; `tuning(p) = Behaviour.tuning(p.traits())`. `decide` order:
  1. **Retreat:** `health <= retreatHealth`, a hostile within 8, and not (`brave >= 0.5 && toolTier("sword") >= 2 && health > 6`) → goal `survive`, step `retreat`: home known and farther than 8 → `goTo` home; else `explore{direction: opposite(nearest hostile's direction), distance: 24}` (opposite of `N`=`S`, `NE`=`SW`, …). Thought `survive: retreat from <mob> (health <h>)`.
  2. **Hunger:** `food <= 6 && foodItems() == 0` → plan `get_food` first; use it if `Next`.
  3. **Night:** `night && homeAtNight && home known && no hostile within 8`: farther than 6 → goal `night`, step `go_home`, `goTo` home; else `hasAction("sleep") && !doneWithin("night/sleep", 12000)` → step `sleep`, `sleep{}`; else `Decision.waiting("night: staying home")` with goal `night`.
  4. **Utility:** for each ranked goal: `planner.next` → `Next` → `Decision.of(next, String.format(Locale.ROOT, "goal %s (%.2f): step %s: %s", id, u, step, why))`; `Blocked` → `mem.block(id, tick + 1200)`, add `StepBlocked`-style cue `ChatCue("ask_help", {goal, step, item: step id when it is an item id in Costs}, false)` once per block, try the next; `Achieved` → next.
  5. **Fallback:** `explore` available → goal `explore`, step `wander`, `explore{direction: N/E/S/W by (tick / 2400) % 4, distance: exploreDistance}`; else `Decision.waiting("nothing to do")`.

**Tests:**
- `TraitsTest` (`traitsAreClamped`, Review Focus 5): 3.0 → 1.0, -7 → -1.0, NaN → 0.
- `PersonalitiesTest`: shipped ids are exactly the ten above; every trait in range; every goal key ∈ `GoalIds.ALL`; speedrunner's `beat_game` = 1.0 and `farm` = 0.0 and `get_food` = 0.9 (from base); a user file adding `pirate` and setting miner `brave 0.9` → both applied, miner's other traits unchanged; `badUserFileKeepsDefaults` (`{not json` → ten personalities, one problem starting `personalities.json:`); `unknownGoalIdIsReported` (`"goals": {"fly": 1}` → problem `personality 'pirate': unknown goal 'fly'`).
- `BehaviourTest`: every formula at traits −1, 0, +1 (e.g. `retreatHealth` 14 / 10 / 6; `keepCount` 0 / 16 / 48; `maxRetries` 1 / 3 / 5).
- `GoalChooserTest`: `w × need` ordering; hysteresis keeps the current goal at 0.85 against a 0.9 rival when patient = 1 (0.85 + 0.30); a blocked goal is excluded; ties by library order; a pinned goal wins.
- `PersonalityEngineTest` (fake goals `fakeIron`, `fakeBuild`, `fakeDefend` with fixed needs and one-step plans): speedrunner picks `fakeIron` over `fakeBuild`; guard picks `fakeDefend` with a zombie at 5; retreat fires for a cautious bot at health 12 with a zombie at 6 and not for a brave one with an iron sword at health 12; night with a known home 20 blocks away → `goTo` home for the builder, nothing special for the speedrunner; `blockedGoalIsSkippedForAMinute` (Review Focus 2: a goal whose plan is `Blocked` is skipped at tick+1 and tick+1199 and planned again at tick+1200); nothing to do and no `explore` → `waiting`; the thought matches `goal fake_iron (1.00): step s1: why`.
- `EngineSpeedTest`: 2,000 `decide` calls with the real `GoalLibrary` (whatever it holds) on a mid-game situation < 1 s.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Pick goals by personality and traits"` with the trailer.

---

### Task 4: Chat templates and optional LLM chat

Runs after Task 1, **in parallel with Tasks 2, 3 and 5**.

**Files:** Create in `autopilot/chat/`: `Templates.java`, `PhraseBook.java`, `ChatPolicy.java`, `LlmChat.java`, `TemplateChat.java`; resource `resources/assets/aiciv/autopilot/phrases.json`; change the one line in `autopilot/ChatDirectors.java` to `return new TemplateChat(spec, p, t, cfg, PhraseBook.forPersonality(p), new SplittableRandom(spec.modelKey().hashCode() ^ p.id().hashCode()));`. Tests `test/java/com/aiciv/autopilot/chat/TemplatesTest.java`, `PhraseBookTest.java`, `ChatPolicyTest.java`, `LlmChatTest.java`, `TemplateChatTest.java` (with a fake `ChatPort`); game tests in `gametest/AutopilotChatGameTests.java` (prefix `TestApChat`).

**Categories and triggers (exact):**

| category | raised by | vars | kind |
|---|---|---|---|
| `greet` | `Spawned`; `PlayerJoined` of someone else | me, other | optional |
| `brag` | `Milestone` | me, item (the milestone id with `_` → space) | priority |
| `complain` | `Hurt` with health ≤ 10 | attacker | optional |
| `ask_help` | engine cue | goal, step, item | optional |
| `died` | `Died` | message | priority |
| `other_died` | `OtherDied` | dead, message | optional |
| `mention` | `Heard` with `mentioned` and no request parsed | speaker | reply |
| `banter` | chat tick: an AI player within 16 | other, otherModel | optional |
| `night` | `NightFell` | me | optional |
| `goal` | `GoalChanged` | goal (summary) | optional |
| `give` / `refuse` / `coming` | Task 5 cues | speaker, item, count | reply |
| `thanks` | `Received` | speaker (= from) | reply |

Placeholders allowed in templates: `me, other, otherModel, speaker, item, count, goal, step, attacker, dead, message, personality`. `otherModel` is `port.modelOf(other)` (e.g. `gemini-flash`, `autopilot-miner`), so bots named after their models can be teased about them.

**Interfaces:**
- `Templates.render(String template, Map<String, String> vars)` → `Optional<String>`: replaces `{name}`; any placeholder without a non-blank value → empty; result through `SayAction.sanitize`. `Templates.placeholders(String)` → set of names.
- `PhraseBook.forPersonality(Personality p)`: categories from `phrases.json` `base`, then `phrases.json[<p.id>]`, then `p.phrases()`; a category given at a later level replaces the earlier list. `List<String> lines(String category)`.
- `phrases.json`: `{"version": 1, "base": {...}, "<personality id>": {...}}`. `base` has at least 3 lines for every category above; every shipped personality has its own `greet`, `brag`, `banter` and `mention` (≥ 2 each). Every line ≤ 100 characters with 16-character names filled. Examples (flavour, not exhaustive): base `greet`: `"Hey {other}!"`, `"Hi all, {me} here."`; base `brag`: `"Got myself a {item}!"`; miner `banter`: `"{other}, does {otherModel} even know what a pickaxe is?"`; speedrunner `banter`: `"{other}, {otherModel} would still be loading the world."`; chatty `mention`: `"Yes {speaker}? I'm all ears!"`; hermit `mention`: `"What, {speaker}."`.
- `ChatPolicy` (pure; times in ms; `t` = tuning, `cfg` = `AutopilotConfig`): `long personalCooldown()` = `cfg.chatCooldownSeconds × 1000 × t.chatCooldownFactor()`; loner = `social <= -0.7` (from the personality) → allowed categories only `mention, died, give, refuse, coming, thanks`; `boolean optionalSpeaks(Random r)` = `r.nextDouble() < 0.5 + 0.5·social`; mention/reply categories ignore the personal cooldown but are ≥ 5,000 ms apart; priority categories ignore the personal cooldown; every line needs `port.globalGapOk(now)` (≥ `globalChatGapMillis` since any autopilot spoke); banter: each AI player pair is considered at most once per 1200 ticks and spoken at most once per 6000 ticks; a pending cue waits at most 10 s, then it is dropped.
- `TemplateChat implements AutopilotChat`: `observe` maps events to cues (table); keeps the last 3 `Heard` lines (`"<speaker>: <text>"`) for LLM context; `tick(port, now)`: takes the oldest allowed pending cue (priority first), picks a line from its category that renders (avoiding the last 3 lines this bot said; deterministic `Random`), then: LLM chat when `spec.chatModel() != null`, category ∈ `{greet, brag, mention, banter, died, other_died}`, `port.llmReady(chatModel)` and ≥ `chatLlmCooldownSeconds × 1000 × factor` since the last LLM chat → `LlmChat.request(...)` and hold the template as fallback; otherwise say the template now. On saying: `port.say(line)`, `port.spoke(now)`, `port.log({mode: "autopilot-chat", event, source, model, text})`.
- `LlmChat` (pure prompt building + cleaning):
  - system: `You are <me>, a player in a Minecraft survival world. Personality: <Name>: <description>. Write exactly one short in-character chat message (at most 100 characters). No quotes, no emojis, no actions in asterisks.`
  - user: `What just happened: <event text>.\nYou: health <h>/20, food <f>/20, <day|night>, doing: <current action or nothing>.\nRecent chat:\n- <line>... (or "Recent chat: none")\nA line you might say: "<template>"\nReply with the chat message only.` Event texts: greet `you just arrived` / `<other> joined the game`; brag `you just got <item>`; mention `<speaker> spoke to you: "<text>"`; banter `<other> (who runs on <otherModel>) is nearby`; died `you died: <message>`; other_died `<dead> died: <message>`.
  - `static Optional<String> clean(String raw, String me)`: first non-blank line; strip surrounding `"`, `'`, `*`; remove a leading `<me>:` or `<<me>>`; strip leading `/`; `SayAction.sanitize`; cut to ≤ 120 characters at a word boundary; empty → empty.
  - The future completes off-thread; `whenComplete` → `port.onServer(...)`: `Outcome.Reply` with a cleaned line and ≤ `chatLlmMaxAgeSeconds` since the cue → say it (`source "llm"`); failed, rate-limited or empty, still fresh → say the template (`source "template"`); older than `chatLlmMaxAgeSeconds` → say nothing. `trySend` returning empty → the template at once.

**Tests:**
- `TemplatesTest`: `"Hi {other}"` with `other=Bob` → `Hi Bob`; missing `other` → empty; placeholder set extraction.
- `PhraseBookTest`: merge order base < phrases.json[id] < personality.phrases; `noShippedTemplateHasUnknownPlaceholders` (Review Focus 4: every line of every category of every personality uses only the allowed placeholders and renders ≤ 100 chars with 16-char values); every category has ≥ 3 base lines.
- `ChatPolicyTest`: cooldown factor at social −1/0/1 → 45 s/30 s/15 s; loner categories; banter pair limits.
- `LlmChatTest`: `clean("\"Ada: hello there!\"\nsecond", "Ada")` → `hello there!`; `/kill` → `kill`; 300 chars → ≤ 120 ending at a word; prompt contains the template and the recent chat.
- `TemplateChatTest` (fake port with a settable clock, recording `said` and `logs`): `Milestone("iron_pickaxe")` → a brag line containing `iron pickaxe` on the next tick; a mention reply bypasses the 30 s cooldown but not the 5 s gap; `globalGapHoldsAcrossBots` (two `TemplateChat`s on one port: the second waits ≥ 2,000 ms); `llmFailureFallsBackToTemplate` (port returns a completed `Outcome.Failed` → the template is said with `source "template"`); LLM success → `source "llm"` with the cleaned text; `lateLlmReplyIsDropped` (the future completes 21 s after the cue → nothing said); `llmReady` false → template immediately; a loner never greets.
- Game `AutopilotChatGameTests.chatLinesReachDecisionLog` (maxTicks 400): 8 × 8 stone floor; `spawnAutopilot(h, "TestApChatA", 2.5, 2.5, "autopilot-chatty")` → within 200 ticks the log has an `autopilot-chat` line with `"event":"greet"` and `"source":"template"` whose text contains no `{`; then `manager.say` from a second plain bot `TestApChatB` (`GameTestKit.spawn`) of `"TestApChatA how are you"` → within 200 more ticks a `"event":"mention"` line.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Let autopilots talk in character, optionally through a cheap LLM"` with the trailer.

---

### Task 5: World goals — build, store, farm, trade, defend, explore, social

Runs after Tasks 2 and 3 are merged (it uses `Steps`, `Gather`, `Costs` and the personalities), **in parallel with Task 4**.

**Files:** Rewrite `autopilot/goal/WorldGoals.java` and `autopilot/Requests.java`; create in `autopilot/goal/world/`: `BuildBase.java`, `StoreItems.java`, `Farm.java`, `TradeEmeralds.java`, `Defend.java`, `Explore.java`, `Social.java`. Tests `test/java/com/aiciv/autopilot/RequestsTest.java`, `test/java/com/aiciv/autopilot/goal/world/WorldGoalsTest.java`; game tests in `gametest/AutopilotWorldGameTests.java` (prefix `TestApWorld`).

**Interfaces:**
- `Requests.parse(String text, String botName, boolean mentioned, long tick)` — lower-case, remove the bot's name, `@`, and `,.!?`, trim; then, verbatim regexes:
  - `^(?:can you |could you |please )?(?:give|throw|toss|pass)(?: me)?(?: (\d+))? (?:a |an |some )?([a-z_ ]+?)(?: please)?$` → `GIVE` (requires `mentioned`);
  - `^(?:can i have|i need|anyone got|anyone have|got any|do you have)(?: some| any)?(?: (\d+))? (?:a |an |some )?([a-z_ ]+?)$` → `GIVE` (without a mention only when the text began with `anyone` or `got any`);
  - `^(?:come|come here|over here|help|help me|to me)$` → `COME` (requires `mentioned`);
  - `^follow me$` → `FOLLOW` (requires `mentioned`).
  Item: spaces → `_`; count default 1, clamped 1..16.
- `WorldGoals.list()` = `Defend, Social, BuildBase, StoreItems, Farm, TradeEmeralds, Explore` (ids `defend, social, build_base, store_items, farm, trade_emeralds, explore`; the library order is Core then World).
- `Defend` [`defend`]: need = hostile within 16 → 1.0; else a human within 32 → 0.4; else 0. Steps: `arm` (optional; done when holding the best sword/axe carried; `equip{item}`), `fight` (done when no hostile within 16; `attack{target: nearest hostile}`), `guard` (optional; done when no human within 32 or a hostile is near; `follow{name: nearest human, seconds: 60}`).
- `Social` [`social`]: need = `requests` non-empty ? 1 : 0. One step `request_<kind>` built from the oldest request: `GIVE` — `have = countLike(item)`, `surplus = have − tuning.keepCount()` → surplus ≥ 1 → `act("give <n> <item> to <from>", give{name: from, item: <best matching id>, count: min(count, surplus)})` with cue `give {speaker, item, count}`; else `act("keep my <item>")` with **no calls** and cue `refuse {speaker, item}`. `COME` → `goToPlayer{name}` + cue `coming`; `FOLLOW` → `follow{name, seconds: 120}` + cue `coming`. Never done while requests exist.
- `BuildBase` [`build a home`]: need = achieved ? 0 : 1. Steps: `Steps.WOODEN_PICKAXE`; `materials` (done when `home().isPresent()` or `have("cobblestone") >= 71`; `Gather.move("cobblestone", 71 − have)` capped at 64 per call); `hut` (done when home present; `build_template{template: "hut", size: 5, material: "cobblestone", name: homeName}`; flag `home_built`); `chest` (done when `flag("home_chest")`; holds a chest → `goTo` home if farther than 4, then `place_chest{}`; else `craft{item: "chest", count: 1}` via `Steps.craft`; flag `home_chest`; needs `place_chest`); `bed` (optional; done when a `_bed` item is held or `flag("home_bed")`; `Steps.craft("white_bed", 1)`, wool via `Gather`).
- `StoreItems` [`store my things`]: need = `home().isPresent() && flag("home_chest") && freeSlots <= tuning.depositWhenFree()` ? 1 : 0. Step `deposit`: home farther than 4 → `goTo` home and `deposit{items: "all_except_tools"}` (two calls); done when `freeSlots > depositWhenFree`; needs `deposit`.
- `Farm` [`farm wheat`]: need = `crop_ripe` → 1.0; not `flag("planted")` → 0.8; `farmland_free` and seeds held → 0.7; else 0.1. Steps: `hoe` (`Steps.craft("wooden_hoe", 1)`, done when any `_hoe` held); `seeds` (done when `wheat_seeds >= 1 || flag("planted")`; `gather("find seeds", mine{block: "short_grass", count: 3})`); `till` (done when `farmland_free || flag("planted")`; `till{count: min(8, seeds)}`; needs `till`); `plant` (done when `!farmland_free && flag("planted")`; `plant{seed: "wheat_seeds", count: min(seeds, 8)}`; flag `planted`; needs `plant`); `harvest` (done when no `crop_ripe`; `harvest{count: 32}`); `bread` (optional; done when `wheat < 3`; `craft{item: "bread", count: wheat / 3}`); `breed` (optional; done when fewer than 2 cows within 16, wheat < 2, or `doneWithin("farm/breed", 6000)`; `breed{animal: "cow"}`).
- `TradeEmeralds` [`trade with villagers`]: need = no `villager:` tag → 0; else 1.0 when an `offer:` tag sells for emeralds something held in quantity, else 0.5. Steps: `list` (done when any `offer:` tag; `trade_list{}`); `sell` (done when `count("emerald") >= 16` or no sellable offer; for the first tag `offer:<prof>:<i>:emerald:<costA>:<countA>` with `count(costA) >= countA` → `trade{villager: prof, offer: i, times: min(count(costA) / countA, 4)}`); `buy_food` (optional; done when `foodItems() >= 8` or no food offer affordable; offer whose result ∈ `FOODS` and costA `emerald` → `trade{villager, offer, times: 1}`).
- `Explore` [`explore`]: need = 0.6. Step `wander` never done: `explore{direction: dirs8[(abs(name.hashCode()) + tick / 6000) % 8], distance: tuning.exploreDistance()}` with `dirs8 = N, NE, E, SE, S, SW, W, NW`.

**Tests:**
- `RequestsTest`: `"TestBot, give me 3 bread"` → GIVE bread 3; `"@TestBot can you toss me an apple please"` → GIVE apple 1; `"anyone got iron ingot"` without a mention → GIVE iron_ingot 1; `"give me bread"` without a mention → empty; `"TestBot follow me"` → FOLLOW; `"TestBot help"` → COME; count 99 → 16.
- `WorldGoalsTest`: `Defend` with a zombie at 5 → `attack zombie`, need 1.0; with a human at 20 and no hostiles → `follow`, need 0.4; `Social` GIVE bread 3 with 20 bread and keepCount 16 → `give` count 3, cue `give`; with 10 bread → no calls, cue `refuse`; with keepCount 0 (generous) and 2 bread → gives 2; `BuildBase` with 80 cobblestone and a stone pickaxe → `build_template hut 5 cobblestone <home>`; with a home → step `chest`; `StoreItems` need 1 at freeSlots 3 with depositWhenFree 4, home and `home_chest`; `Farm` with `crop_ripe` → `harvest`, with a hoe, 4 seeds and no farmland → `till 4`; `TradeEmeralds` with tag `offer:farmer:1:emerald:wheat:20` and 45 wheat → `trade{villager: farmer, offer: 1, times: 2}`; `Explore` direction stable within 6000 ticks.
- Game `AutopilotWorldGameTests`:
  - `socialGivesRequestedItem` (maxTicks 600): 8 × 8 floor; `TestApWorldGive` on `autopilot-chatty` holding 20 bread at (2.5, 2.5); plain bot `TestApWorldAsk` (`GameTestKit.spawn`) at (6.5, 6.5); `manager.say("TestApWorldAsk", "TestApWorldGive give me 3 bread")` → within 400 ticks `TestApWorldAsk` holds exactly 3 bread and the giver's log has a decision with `"goal":"social"`.
  - `farmerTillsAndPlants` (maxTicks 1600): 8 × 8 dirt floor with a water source at (4, 0, 4); `TestApWorldFarm` on `autopilot-farmer` holding a wooden_hoe, 4 wheat_seeds and 8 bread → within 1500 ticks ≥ 3 wheat crops stand on farmland and the log has decisions with `"goal":"farm"`.

- [ ] Tests first; implement; `./gradlew test runGametest --no-daemon`.
- [ ] Commit `"Give autopilots homes, farms, trades, guard duty and manners"` with the trailer.

---

### Task 6: Autopilot acceptance

Runs after Tasks 2–5 are merged.

**Files:** Game tests in `gametest/AutopilotGameTests.java` (prefix `TestAp`); make `SurvivalGameTests.arena` package-private and `CombatGameTests.easy`/`restoreDifficulty` package-private static (visibility only, so difficulty stays reference-counted across classes); `README.md` (autopilot section: model keys, aiciv.json example, personalities and traits table, `personalities.json`/`phrases.json` override, `chatModel`, `/ai personalities`, pinning a goal with `/ai goal <name> <goal id>`); spec "Changes made during milestone 5".

**Tests** (each removes its bots at the end; each asserts the decision log has no line containing `"error"` and the scripted backend received no request unless stated):
1. `autopilotSpeedrunnerReachesIronTools` (`padding = 24`, maxTicks 6000): `SurvivalGameTests.arena(helper)` (the milestone-2 16 × 16 arena: 3 trees, stone, 2 coal ore, 3 iron ore); `TestApSpeed` on `autopilot-speedrunner` at (8.5, 1, 8.5) holding 4 bread → `succeedWhen` the bot holds an `iron_pickaxe`; the log's autopilot decisions have `goal` ∈ {`get_iron_tools`, `beat_game`} and ≥ 6 distinct `step`s; record the finishing tick and the number of decisions in the spec.
2. `autopilotBuilderBuildsHut` (`padding = 24`, maxTicks 4000): 16 × 16 stone floor (`clearBarriers`); `TestApBuilder` on `autopilot-builder` at (8.5, 1, 3.5) facing south holding 80 cobblestone, a stone_pickaxe, a stone_sword and 8 bread → `StructureRegistry.of(server).store().get("testapbuilder_home")` is present and its 71 cells are cobblestone; a decision has `"goal":"build_base"` and `"step":"hut"`.
3. `autopilotGuardKillsZombie` (maxTicks 800): switch to easy with `CombatGameTests.easy(server)` and put it back with `restoreDifficulty(server)` exactly where milestone 2's `killsZombieWithSword` does; 8 × 8 dirt floor; `TestApGuard` on `autopilot-guard` holding an iron_sword and 8 bread at (1.5, 1, 1.5); a zombie (`EntityTypes.ZOMBIE`, `setNoAi(true)`) at (6.5, 1, 6.5) → within 600 ticks the zombie is dead; a decision has `"goal":"defend"`.
4. `autopilotChatTemplatesFire` (maxTicks 600): config with models `"chatty-llm": {"type": "autopilot", "personality": "chatty", "chatModel": "scripted"}` and `"miner-off": {"type": "autopilot", "personality": "miner", "chatModel": "offline"}` where `offline` is an LLM model on a provider with no backend (disabled); the scripted backend replies `{"content": "beep from the llm"}`; `TestApChatty` on `chatty-llm` and `TestApMiner` on `miner-off` 4 blocks apart → within 500 ticks: `TestApChatty`'s log has an `autopilot-chat` line with `"source":"llm"` and text `beep from the llm`; `TestApMiner`'s log has an `autopilot-chat` line with `"source":"template"`; no chat text contains `{`; across both logs, chat lines from the two bots are ≥ 2,000 ms apart.

**Live acceptance (hand to the user):** in a fresh survival world, `/ai spawn Rex autopilot-speedrunner`, `/ai spawn Bea autopilot-builder`, `/ai spawn Fen autopilot-farmer`, `/ai spawn Gus autopilot-guard`, and one LLM bot (`/ai spawn Ada gemini-35-flash`) for 30 minutes; report per autopilot the goals reached (Milestones bragged), structures built, deaths, chat lines (template vs LLM) and server tick time (`/tick query`) with all five online.

- [ ] Write the tests; run `./gradlew build test runGametest --no-daemon`; update README and spec; commit `"Prove autopilots reach iron, build, guard and talk"` with the trailer.

---

## After this milestone

- Milestone 4 lands its endgame actions; `endgame.json` gets their final names (data only) and `beat_game` runs further without code changes.
- Possible later: per-bot trait overrides in the roster, autopilots that coordinate (shared village goals), learning which goal pays off.

## Self-review notes

- Spec coverage: decision mode and model keys (`autopilot-<id>`, configured `type: autopilot`, `chatModel`, `thinkSeconds`) → Task 1; scheduler path without ThinkQueue/limiter, round robin cap, pulses, decision log shape, panel fields, `/ai status` → Task 1; planner (preconditions, retries, explore fallback, backoff, livelock, dynamic actions) → Task 2; survival goals and `beat_game` with the tolerant mapping → Task 2; archetypes, traits, `personalities.json`, utility choice, retreat/hunger/night overrides, pinned goals → Task 3; templates, categories incl. model-name banter, rate limits, optional LLM chat and fallback → Task 4; build_base, store, farm, trade, defend/guard, explore, social give/help → Task 5; acceptance → Task 6. Menu- and tool-mode LLM bots are untouched except `runCalls` returning a count.
- Shared files after Task 1: `Engines.java` (Task 3, one line), `ChatDirectors.java` (Task 4, one line), `AiCommands.java` (Task 3 adds `/ai personalities`), `SurvivalGameTests`/`CombatGameTests` visibility (Task 6). Tasks 2–5 never touch the same existing file.
- Type consistency: `StepPlanner.next(Goal, Situation, AutopilotMemory, Tuning)`, `AutopilotEngine.decide(Situation, AutopilotMemory, Personality, String, long)`, `Decision.of(PlanResult.Next, String)`, `Move(calls, why, kind, cues)`, `Tuning` fields and `Behaviour.tuning(Traits)` are used with the same names in every task.
