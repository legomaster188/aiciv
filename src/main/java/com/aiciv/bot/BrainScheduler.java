package com.aiciv.bot;

import com.aiciv.AiCiv;
import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.SayAction;
import com.aiciv.actions.ToolCatalog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;

/** Wakes bots, gives queued bots their LLM turn when a provider is free, and applies the replies. */
final class BrainScheduler {
    private final BotManager m;
    /** When each bot first could not dispatch; cleared once it does. */
    private final Map<String, Long> waitingSince = new HashMap<>();

    BrainScheduler(BotManager m) {
        this.m = m;
    }

    void tick(long now) {
        for (Bot b : m.bots()) {
            try {
                if (b.player.isDeadOrDying()) continue;
                WakeReason r = b.brain.pollWake(now, b.executor.busy());
                if (r != null) m.queue().offer(b.name, r, now);
            } catch (RuntimeException e) {
                m.fault(b.name, "wake check", e);
            }
        }
        while (true) {
            Optional<ThinkQueue.Ticket> ticket = m.queue().pollFirst(name -> m.bot(name)
                    .map(this::hasRoute)
                    .orElse(true));
            if (ticket.isEmpty()) return;
            Optional<Bot> bot = m.bot(ticket.get().bot());
            if (bot.isEmpty() || bot.get().player.isDeadOrDying()) continue;
            try {
                if (!think(bot.get(), ticket.get(), now)) return;
            } catch (RuntimeException e) {
                m.fault(bot.get().name, "think", e);
                bot.get().brain.failed(ticket.get().reason());
            }
        }
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
        SnapshotData data = Perceiver.capture(b.player, b.executor.currentDescription(), b.brain.lastDecision(), p -> p instanceof AiPlayer, b.knownBlocks);
        String snapshot = SnapshotFormatter.format(data, SnapshotFormatter.MAX_CHARS);
        if (m.llm().menuMode(b.brain.modelKey())) return thinkMenu(b, ticket, now, data, snapshot);
        String system = PromptBuilder.system(b.name, b.brain.persona(), b.brain.goal(), m.othersFor(b),
                m.config().maxActionsPerDecision, ToolCatalog.guide());
        String user = PromptBuilder.user(List.of(), b.brain.events().lines(), snapshot);
        Optional<CompletableFuture<LlmService.Outcome>> sent = m.llm().trySend(b.brain.modelKey(), system, user, ToolCatalog.tools());
        if (sent.isEmpty()) {
            m.queue().offer(b.name, ticket.reason(), ticket.since());
            return false;
        }
        b.brain.dispatched(now);
        waitingSince.remove(b.name);
        int snapshotChars = snapshot.length();
        sent.get().whenComplete((outcome, err) -> m.server().execute(() -> apply(b, ticket, snapshotChars, null, outcome, err)));
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
        sent.get().whenComplete((outcome, err) -> m.server().execute(() -> apply(b, ticket, snapshotChars, options, outcome, err)));
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
        List<String> hostile = new ArrayList<>();
        for (Sighting s : data.mobs()) {
            if (s.distance() > 16 || hostile.contains(s.label())) continue;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(s.label()));
            if (type.getCategory() == MobCategory.MONSTER) hostile.add(s.label());
        }
        List<String> players = new ArrayList<>();
        for (Sighting s : data.players()) players.add(s.label().replaceAll(" \\[(AI|human)\\]$", ""));
        return new MenuContext(inventory, craftableTargets(b, inventory), data.food() <= 12, hostile, players, mineable.stream().distinct().toList());
    }

    /** Progression items worth offering as menu choices. */
    static final List<String> CRAFT_TARGETS = List.of("crafting_table", "wooden_pickaxe", "stone_pickaxe", "furnace",
            "iron_pickaxe", "iron_sword", "iron_chestplate", "bread", "torch");

    /** The progression items the bot could craft right now (a table within 4 blocks, or one it holds or can make). */
    private static List<String> craftableTargets(Bot b, Map<String, Integer> inventory) {
        List<RecipeOption> recipes = RecipeBook.load(b.player.level()); // cached until the recipes reload
        boolean tableNearby = !Perceiver.exposedBlocks(b.player.level(), b.player.blockPosition(), 4, id -> id.equals(CraftPlanner.TABLE)).isEmpty();
        List<String> out = new ArrayList<>();
        for (String target : CRAFT_TARGETS) {
            if (CraftPlanner.canMake(target, 1, inventory, recipes, tableNearby)) out.add(target);
        }
        return out;
    }

    private void apply(Bot b, ThinkQueue.Ticket ticket, int snapshotChars, List<MenuOption> options, LlmService.Outcome outcome, Throwable err) {
        if (m.bot(b.name).orElse(null) != b) return; // removed or replaced while thinking
        try {
            applyChecked(b, ticket, snapshotChars, options, outcome, err);
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

    private void applyChecked(Bot b, ThinkQueue.Ticket ticket, int snapshotChars, List<MenuOption> options,
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

        if (err == null && outcome instanceof LlmService.Outcome.Chosen chosen && options != null) {
            applyChoice(b, log, options, chosen);
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

    /** Turns calls into actions and starts them (talking aside), noting problems; shared by tools and menu mode. */
    private void runCalls(Bot b, JsonObject log, List<ToolCall> callList, String plainText, String finishReason) {
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
    }
}
