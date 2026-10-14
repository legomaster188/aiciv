package com.aiciv.bot;

import com.aiciv.AiCiv;
import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.SayAction;
import com.aiciv.actions.ToolCatalog;
import com.aiciv.autopilot.AutopilotTurns;
import com.aiciv.autopilot.Decision;
import com.aiciv.autopilot.GoalIds;
import com.aiciv.autopilot.Pulse;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.SituationCapture;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.brain.OptionMenu.MenuContext;
import com.aiciv.brain.PromptBuilder;
import com.aiciv.brain.ThinkQueue;
import com.aiciv.brain.WakeReason;
import com.aiciv.llm.LlmService;
import com.aiciv.llm.MenuReply;
import com.aiciv.llm.ToolCall;
import com.aiciv.craft.CraftPlanner;
import com.aiciv.gear.Armor;
import com.aiciv.gear.GearPlan;
import com.aiciv.craft.RecipeBook;
import com.aiciv.craft.RecipeOption;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotFormatter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Wakes bots, gives queued bots their LLM turn when a provider is free, and applies the replies. Autopilot bots never
 * enter the think queue: they decide synchronously, a few per tick in round robin.
 */
final class BrainScheduler {
    private final BotManager m;
    /** When each bot first could not dispatch; cleared once it does. */
    private final Map<String, Long> waitingSince = new HashMap<>();
    private final AutopilotTurns turns = new AutopilotTurns();
    private final Map<String, LiveChatPort> chatPorts = new HashMap<>();
    /** When any autopilot last spoke: the server-wide chat gap. */
    long lastAutopilotChatMillis = Long.MIN_VALUE / 2;
    /** Bots whose request a provider refused as too large: they get the compact catalog from then on. */
    private final Set<String> compact = new HashSet<>();
    /** Bots already logged as too large even with the compact catalog, so that is said once. */
    private final Set<String> tooLargeEvenCompact = new HashSet<>();

    BrainScheduler(BotManager m) {
        this.m = m;
    }

    /** Drops a bot's waiting autopilot turn and chat port (removed, paused, died or switched model). */
    void forget(String name) {
        turns.remove(name);
        chatPorts.remove(name);
    }

    void tick(long now) {
        long tick = m.server().getTickCount();
        for (Bot b : m.bots()) {
            try {
                if (b.player.isDeadOrDying()) continue;
                if (b.autopilot != null) {
                    if (b.brain.paused()) continue;
                    String urgent = tick % Math.max(1, m.config().autopilot.checkTicks) == 0
                            ? b.autopilot.urgent(Pulse.read(b.player, tick), now) : null;
                    WakeReason r = b.brain.pollWake(now, b.executor.busy());
                    if (urgent != null) turns.offer(b.name, "URGENT", urgent);
                    else if (r != null) turns.offer(b.name, r.name(), null);
                    b.autopilot.chat().tick(chatPorts.computeIfAbsent(b.name, n -> new LiveChatPort(m, b)), now);
                    continue;
                }
                WakeReason r = b.brain.pollWake(now, b.executor.busy());
                if (r != null) m.queue().offer(b.name, r, now);
            } catch (RuntimeException e) {
                m.fault(b.name, "wake check", e);
            }
        }
        for (AutopilotTurns.Turn t : turns.take(Math.max(1, m.config().autopilot.maxDecisionsPerTick))) {
            Optional<Bot> bot = m.bot(t.bot());
            if (bot.isEmpty() || bot.get().autopilot == null || bot.get().player.isDeadOrDying()) continue;
            try {
                decideAutopilot(bot.get(), t, now, tick);
            } catch (RuntimeException e) {
                m.fault(bot.get().name, "autopilot", e);
                bot.get().brain.replied("error");
            }
        }
        // A bot whose request could not be sent waits for the next tick; it never holds up the bots behind it.
        Set<String> unsent = new HashSet<>();
        while (true) {
            Optional<ThinkQueue.Ticket> ticket = m.queue().pollFirst(name -> !unsent.contains(name) && m.bot(name)
                    .map(this::hasRoute)
                    .orElse(true));
            if (ticket.isEmpty()) return;
            Optional<Bot> bot = m.bot(ticket.get().bot());
            if (bot.isEmpty() || bot.get().player.isDeadOrDying()) continue;
            try {
                if (!think(bot.get(), ticket.get(), now)) unsent.add(ticket.get().bot());
            } catch (RuntimeException e) {
                m.fault(bot.get().name, "think", e);
                bot.get().brain.failed(ticket.get().reason());
            }
        }
    }

    /** One synchronous autopilot decision: snapshot, situation, engine, then the same calls an LLM would make. */
    private void decideAutopilot(Bot b, AutopilotTurns.Turn t, long now, long tick) {
        boolean busy = b.executor.busy();
        String current = b.executor.currentDescription();
        String pinned = pinnedGoal(b.brain.goal());
        Situation s = b.autopilot.reusable(t.trigger(), busy, busy ? current : null, Math.round(b.player.getHealth()),
                b.player.getFoodData().getFoodLevel(), pinned, tick).orElse(null);
        if (s == null) {
            SnapshotData data = Perceiver.capture(b.player, current, b.brain.lastDecision(), p -> p instanceof AiPlayer,
                    b.knownBlocks, b.attachments);
            s = SituationCapture.capture(b.player, data, busy, busy ? current : null, b.autopilot.memory(),
                    b.autopilot.tuning(), pinned, tick);
        }
        b.brain.dispatched(now);
        Decision d = b.autopilot.decide(s, t.trigger(), tick, now);
        if (d.keepCurrent()) {
            b.brain.replied("kept: " + current);
            return;
        }
        JsonObject log = new JsonObject();
        log.addProperty("time", Instant.ofEpochMilli(m.clock().millis()).toString());
        log.addProperty("trigger", t.trigger());
        if (t.detail() != null) log.addProperty("urgent", t.detail());
        log.addProperty("snapshotChars", 0); // an autopilot reads the snapshot's data; nobody formats it as text
        log.addProperty("provider", "autopilot");
        log.addProperty("mode", "autopilot");
        log.addProperty("model", b.brain.modelKey());
        log.addProperty("personality", b.autopilot.personality().id());
        if (d.goal() != null) log.addProperty("goal", d.goal());
        if (d.step() != null) log.addProperty("step", d.step());
        log.addProperty("thought", d.thought());
        if (d.calls().isEmpty()) {
            log.add("calls", new JsonArray());
            log.add("problems", new JsonArray());
            b.brain.replied("waiting");
            m.decisionLog().append(b.name, log);
            return;
        }
        if (runCalls(b, log, d.calls(), null, null) == 0) {
            List<String> problems = new ArrayList<>();
            log.getAsJsonArray("problems").forEach(p -> problems.add(p.getAsString()));
            b.autopilot.actionsEnded("failed: invalid: " + String.join("; ", problems), tick, now);
        }
    }

    /** The bot's operator-set goal when it names an autopilot goal id, else null. */
    private static String pinnedGoal(String goal) {
        if (goal == null) return null;
        String g = goal.trim().toLowerCase(Locale.ROOT);
        return GoalIds.ALL.contains(g) ? g : null;
    }

    /** True when the bot's model has a route open now; otherwise, if no provider on it can recover soon, tells operators why. */
    private boolean hasRoute(Bot b) {
        try {
            String model = b.brain.modelKey();
            if (m.llm().peek(model).isPresent()) return true;
            long waited = m.clock().millis() - waitingSince.computeIfAbsent(b.name, n -> m.clock().millis());
            List<String> providers = m.llm().routeProviders(model);
            // A provider that is merely out of rate-limit budget frees up by itself; say nothing.
            if (providers.stream().anyMatch(m.llm()::providerAvailable)) return false;
            String text;
            if (providers.isEmpty()) {
                text = b.name + " uses model '" + model + "', which is not configured";
            } else {
                text = b.name + " (" + model + ") is waiting: " + providers.stream()
                        .map(p -> p + " " + m.llm().providerStatus(p)).collect(Collectors.joining("; "));
                if (m.llm().providerDisabled(providers.get(0))) {
                    text += ". Fix keys.json or aiciv.json for '" + providers.get(0) + "', then run /ai reload.";
                }
            }
            boolean primaryDisabled = !providers.isEmpty() && m.llm().providerDisabled(providers.get(0));
            m.warnOps("bot " + b.name, text, WarningPolicy.shouldChat(primaryDisabled, waited));
            return false;
        } catch (RuntimeException e) {
            m.fault(b.name, "route check", e);
            return false;
        }
    }

    private boolean think(Bot b, ThinkQueue.Ticket ticket, long now) {
        SnapshotData data = Perceiver.capture(b.player, b.executor.currentDescription(), b.brain.lastDecision(), p -> p instanceof AiPlayer, b.knownBlocks,
                b.attachments);
        String snapshot = SnapshotFormatter.format(data, SnapshotFormatter.MAX_CHARS);
        if (m.llm().menuMode(b.brain.modelKey())) return thinkMenu(b, ticket, now, data, snapshot);
        boolean small = compact.contains(b.name);
        ToolCatalog.Mode configured = small ? ToolCatalog.Mode.COMPACT : m.llm().toolCatalog(b.brain.modelKey());
        String system = PromptBuilder.system(b.name, b.brain.persona(), b.brain.goal(), m.othersFor(b),
                m.config().maxActionsPerDecision, ToolCatalog.guide(configured, data, b.context));
        String user = PromptBuilder.user(List.of(), b.brain.events().lines(), snapshot);
        Optional<CompletableFuture<LlmService.Outcome>> sent = m.llm().trySend(b.brain.modelKey(), system, user,
                mode -> ToolCatalog.tools(small ? ToolCatalog.Mode.COMPACT : mode, data, b.context));
        if (sent.isEmpty()) {
            m.queue().offer(b.name, ticket.reason(), ticket.since());
            return false;
        }
        b.brain.dispatched(now);
        waitingSince.remove(b.name);
        int snapshotChars = snapshot.length();
        int life = b.life;
        sent.get().whenComplete((outcome, err) -> m.server().execute(() -> apply(b, life, ticket, snapshotChars, null, outcome, err)));
        return true;
    }

    /** Menu mode: the model picks one option from a short list built from the bot's situation. */
    private boolean thinkMenu(Bot b, ThinkQueue.Ticket ticket, long now, SnapshotData data, String snapshot) {
        List<MenuOption> options = OptionMenu.build(data, menuContext(b, data));
        String system = PromptBuilder.menuSystem(b.name, b.brain.persona(), b.brain.goal(), m.othersFor(b));
        String user = PromptBuilder.menuUser(b.brain.events().lines(), snapshot);
        Optional<CompletableFuture<LlmService.Outcome>> sent = m.llm().trySendMenu(b.brain.modelKey(), system, user, options);
        if (sent.isEmpty()) {
            m.queue().offer(b.name, ticket.reason(), ticket.since());
            return false;
        }
        b.brain.dispatched(now);
        waitingSince.remove(b.name);
        int snapshotChars = snapshot.length();
        int life = b.life;
        sent.get().whenComplete((outcome, err) -> m.server().execute(() -> apply(b, life, ticket, snapshotChars, options, outcome, err)));
        return true;
    }

    private static MenuContext menuContext(Bot b, SnapshotData data) {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        boolean hasFood = false;
        boolean hasPickaxe = false;
        for (ItemStack s : b.player.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            inventory.merge(id, s.getCount(), Integer::sum);
            hasPickaxe |= id.endsWith("_pickaxe");
        }
        List<String> mineable = new ArrayList<>();
        for (Sighting s : data.blocks()) mineable.add(s.label());
        for (String r : data.remembered()) mineable.add(r.substring(0, r.indexOf(' ')));
        if (hasPickaxe) mineable.add("stone");
        List<String> hostile = Perceiver.hostileLabels(data.mobs(), 16);
        List<String> players = new ArrayList<>();
        for (Sighting s : data.players()) players.add(s.label().replaceAll(" \\[(AI|human)\\]$", ""));
        return new MenuContext(inventory, craftableTargets(b, inventory), data.food() <= 12, hostile, players, mineable.stream().distinct().toList());
    }

    /** Progression items worth offering as menu choices. */
    static final List<String> CRAFT_TARGETS = List.of("crafting_table", "wooden_pickaxe", "stone_pickaxe", "furnace",
            "iron_pickaxe", "iron_sword", "iron_chestplate", "bread", "torch");

    /** Shield and ranged gear offered as menu choices when craftable. */
    static final List<String> GEAR_TARGETS = List.of("shield", "bow", "arrow", "crossbow");
    /** Armor pieces offered per menu, so they do not crowd out everything else. */
    static final int ARMOR_CRAFT_CAP = 6;

    /** The progression items the bot could craft right now (a table within 4 blocks, or one it holds or can make). */
    private static List<String> craftableTargets(Bot b, Map<String, Integer> inventory) {
        List<RecipeOption> recipes = RecipeBook.load(b.player.level()); // cached until the recipes reload
        boolean tableNearby = !Perceiver.exposedBlocks(b.player.level(), b.player.blockPosition(), 4, id -> id.equals(CraftPlanner.TABLE)).isEmpty();
        List<String> out = new ArrayList<>();
        for (String target : CRAFT_TARGETS) {
            if (CraftPlanner.canMake(target, 1, inventory, recipes, tableNearby)) out.add(target);
        }
        for (String target : GEAR_TARGETS) {
            if (CraftPlanner.canMake(target, 1, inventory, recipes, tableNearby)) out.add(target);
        }
        List<String> armor = new ArrayList<>();
        for (String material : GearPlan.AUTO) {
            for (GearPlan.Slot slot : GearPlan.Slot.values()) {
                String piece = GearPlan.pieceId(material, slot);
                if (CraftPlanner.canMake(piece, 1, inventory, recipes, tableNearby)) armor.add(piece);
            }
        }
        out.addAll(GearPlan.armorCraftTargets(Armor.worn(b.player), armor, GearPlan::tableScore, ARMOR_CRAFT_CAP));
        return out;
    }

    private void apply(Bot b, int life, ThinkQueue.Ticket ticket, int snapshotChars, List<MenuOption> options,
                       LlmService.Outcome outcome, Throwable err) {
        if (m.bot(b.name).orElse(null) != b) return; // removed or replaced while thinking
        try {
            applyChecked(b, life, ticket, snapshotChars, options, outcome, err);
        } catch (RuntimeException e) {
            AiCiv.LOG.warn("AiCiv could not apply the reply for {}", b.name, e);
            JsonObject log = new JsonObject();
            log.addProperty("time", Instant.ofEpochMilli(m.clock().millis()).toString());
            log.addProperty("trigger", ticket.reason().name());
            log.addProperty("error", "could not apply reply: " + e);
            m.decisionLog().append(b.name, log);
            b.brain.failed(ticket.reason());
        }
    }

    private void applyChecked(Bot b, int life, ThinkQueue.Ticket ticket, int snapshotChars, List<MenuOption> options,
                              LlmService.Outcome outcome, Throwable err) {
        JsonObject log = new JsonObject();
        log.addProperty("time", Instant.ofEpochMilli(m.clock().millis()).toString());
        log.addProperty("trigger", ticket.reason().name());
        log.addProperty("snapshotChars", snapshotChars);
        if (outcome != null) log.addProperty("provider", outcome.route().providerKey());

        if (b.brain.paused()) {
            log.addProperty("discarded", "paused");
            b.brain.replied("paused");
            m.decisionLog().append(b.name, log);
            return;
        }
        if (b.life != life || b.player.isDeadOrDying()) {
            // Decided for a life that has ended: the plan is about items and places the bot no longer has.
            log.addProperty("discarded", "died");
            b.brain.replied("nothing (died before the reply came)");
            m.decisionLog().append(b.name, log);
            return;
        }

        if (err == null && outcome instanceof LlmService.Outcome.Chosen chosen && options != null) {
            applyChoice(b, log, options, chosen);
            return;
        }

        if (err == null && outcome instanceof LlmService.Outcome.TooLarge large) {
            tooLarge(b, large);
            log.addProperty("error", large.reason());
            b.brain.failed(ticket.reason()); // asks again, now with the compact catalog
            m.decisionLog().append(b.name, log);
            return;
        }

        if (err != null || !(outcome instanceof LlmService.Outcome.Reply reply)) {
            String why = err != null ? String.valueOf(err)
                    : outcome instanceof LlmService.Outcome.Failed f ? f.reason() : "rate limited";
            if (outcome instanceof LlmService.Outcome.Failed failed && m.llm().providerDisabled(failed.route().providerKey())) {
                m.warnOpsOnce(failed.route().providerKey(), failed.reason());
            }
            log.addProperty("error", why);
            b.brain.failed(ticket.reason());
            m.decisionLog().append(b.name, log);
            return;
        }

        log.addProperty("model", reply.route().modelId());
        log.addProperty("thought", reply.reply().thought());
        if (reply.reply().finishReason() != null) log.addProperty("finish_reason", reply.reply().finishReason());
        if (reply.reply().usage() != null) log.add("usage", reply.reply().usage());
        runCalls(b, log, reply.reply().calls(), reply.reply().plainText(), reply.reply().finishReason());
    }

    /** Switches the bot to the compact catalog, logging the first refusal (and a compact one) once. */
    private void tooLarge(Bot b, LlmService.Outcome.TooLarge large) {
        String provider = large.route().providerKey();
        if (compact.add(b.name)) {
            AiCiv.LOG.warn("AiCiv: {}'s request was too large for {} ({}); retrying with the compact tool catalog", b.name, provider,
                    large.reason());
        } else if (tooLargeEvenCompact.add(b.name)) {
            AiCiv.LOG.warn("AiCiv: {}'s request is too large for {} even with the compact tool catalog ({}); give the model a "
                    + "provider with a larger per-request token limit", b.name, provider, large.reason());
        }
    }

    /** Menu mode: runs the chosen option's call chain, or notes that the model named something the menu never offered. */
    private void applyChoice(Bot b, JsonObject log, List<MenuOption> options, LlmService.Outcome.Chosen chosen) {
        MenuReply reply = chosen.reply();
        log.addProperty("mode", "menu");
        log.addProperty("model", chosen.route().modelId());
        log.addProperty("option", reply.optionId());
        if (!reply.probabilities().isEmpty()) {
            JsonObject probs = new JsonObject();
            reply.probabilities().forEach(probs::addProperty);
            log.add("probabilities", probs);
        }
        if (reply.confidence() != null) log.addProperty("confidence", reply.confidence());
        MenuOption option = options.stream().filter(o -> o.id().equals(reply.optionId())).findFirst().orElse(null);
        if (option == null) {
            b.brain.note(m.stamp(), "you chose an unknown option '" + reply.optionId() + "'; nothing happened");
            b.brain.replied("nothing");
        } else if (option.calls().isEmpty()) {
            b.brain.replied(option.id());
        } else {
            runCalls(b, log, option.calls(), null, null);
            return;
        }
        m.decisionLog().append(b.name, log);
    }

    /**
     * Turns calls into actions and starts them (talking aside), noting problems; shared by tools, menu and autopilot mode.
     * Returns how many actions it started.
     */
    private int runCalls(Bot b, JsonObject log, List<ToolCall> callList, String plainText, String finishReason) {
        int max = m.config().maxActionsPerDecision;
        List<Action> actions = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        JsonArray calls = new JsonArray();
        for (ToolCall c : callList) {
            JsonObject jc = new JsonObject();
            jc.addProperty("name", c.name());
            jc.add("args", c.args());
            calls.add(jc);
            if (actions.size() >= max) {
                problems.add("only " + max + " actions per turn; ignored " + c.name());
                continue;
            }
            switch (ActionFactory.create(c)) {
                case ActionFactory.Result.Ok ok -> actions.add(ok.action());
                case ActionFactory.Result.Invalid bad -> problems.add(bad.reason());
            }
        }
        if (actions.isEmpty() && plainText != null) actions.add(new SayAction(plainText));
        log.add("calls", calls);
        if (plainText != null) log.addProperty("text", plainText);
        JsonArray problemsJson = new JsonArray();
        problems.forEach(problemsJson::add);
        log.add("problems", problemsJson);

        String stamp = m.stamp();
        if (!problems.isEmpty()) b.brain.note(stamp, "problems with your last turn: " + String.join("; ", problems));
        if (actions.isEmpty()) {
            if ("length".equals(finishReason)) {
                AiCiv.LOG.warn("{}'s reply was cut off by max_tokens before any action", b.name);
                b.brain.note(stamp, "your last reply was cut off before any action; reply with fewer words");
            } else {
                b.brain.note(stamp, "your last turn did nothing");
            }
        } else if (actions.stream().allMatch(a -> a instanceof SayAction)) {
            // Talking does not interrupt what the bot is doing.
            for (Action a : actions) a.tick(b.context);
        } else {
            b.executor.replace(actions, b.context);
        }
        b.brain.replied(actions.isEmpty() ? "nothing" : actions.stream().map(Action::describe).collect(Collectors.joining(", ")));
        m.decisionLog().append(b.name, log);
        return actions.size();
    }
}
