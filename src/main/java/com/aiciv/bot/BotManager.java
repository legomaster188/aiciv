package com.aiciv.bot;

import com.aiciv.AiCiv;
import com.aiciv.actions.ActionExecutor;
import com.aiciv.brain.BotBrain;
import com.aiciv.brain.DecisionLog;
import com.aiciv.brain.Hearing;
import com.aiciv.brain.ThinkQueue;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.LlmService;
import com.aiciv.nav.LevelNavigator;
import com.aiciv.nav.NavBudget;
import com.aiciv.perception.GameTime;
import com.aiciv.world.BlockBreaker;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Owns every AI player in one server. All methods run on the server thread. */
public final class BotManager {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final MinecraftServer server;
    private AiCivConfig config;
    private LlmService llm;
    private final Path rosterFile;
    private final Roster roster;
    private final DecisionLog decisionLog;
    private final Clock clock;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private final ThinkQueue queue = new ThinkQueue();
    private final NavBudget navBudget = new NavBudget(NavBudget.DEFAULT_NANOS, System::nanoTime);
    private final BrainScheduler scheduler = new BrainScheduler(this);
    /** Last warning text per source, so the log is not spammed. Cleared on reload. */
    private final Map<String, String> warningsLogged = new HashMap<>();
    /** Last warning text an operator actually received per source. Cleared on reload. */
    private final Map<String, String> warningsDelivered = new HashMap<>();
    private final Set<String> faultsLogged = new HashSet<>();

    public BotManager(MinecraftServer server, AiCivConfig config, LlmService llm, Path rosterFile,
                      DecisionLog decisionLog, Clock clock) throws IOException {
        this.server = server;
        this.config = config;
        this.llm = llm;
        this.rosterFile = rosterFile;
        this.roster = Roster.load(rosterFile);
        this.decisionLog = decisionLog;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- lifecycle

    public void restoreAll() {
        for (Roster.Entry e : roster.all()) {
            if (server.getPlayerList().getPlayerByName(e.name) != null) {
                AiCiv.LOG.warn("Not restoring {}: a player with that name is online", e.name);
                continue;
            }
            if (!llm.knowsModel(e.model)) {
                AiCiv.LOG.warn("{} uses unknown model '{}'; falling back to '{}'", e.name, e.model, config.defaultModel);
                e.model = config.defaultModel;
                saveRoster();
            }
            join(e);
        }
    }

    public void shutdown() {
        saveRoster();
        for (Bot b : bots.values()) b.executor.stop(b.context);
        bots.clear();
        queue.clear();
    }

    public void reload(AiCivConfig config, LlmService llm) {
        this.config = config;
        this.llm = llm;
        warningsLogged.clear();
        warningsDelivered.clear();
        boolean changed = false;
        for (Bot b : bots.values()) {
            b.brain.setTiming(config.idleWakeSeconds * 1000L, config.mentionCooldownSeconds * 1000L, config.eventWindow);
            if (!llm.knowsModel(b.brain.modelKey())) {
                AiCiv.LOG.warn("{} uses unknown model '{}'; falling back to '{}'", b.name, b.brain.modelKey(), config.defaultModel);
                b.brain.setModelKey(config.defaultModel);
                roster.get(b.name).ifPresent(e -> e.model = config.defaultModel);
                changed = true;
            }
        }
        if (changed) saveRoster();
    }

    /** Tells online operators, once per provider, that its key is missing or was rejected. Never shows the key. */
    void warnOpsOnce(String provider, String reason) {
        warnOps("provider " + provider, "Provider '" + provider + "' is disabled: " + reason
                + ". Fix keys.json, then run /ai reload.");
    }

    /**
     * Logs a warning and chats it to online operators. A warning counts as delivered only once an operator
     * has received it, so one raised before anyone is online is retried; the same text is never repeated.
     */
    void warnOps(String source, String text) {
        warnOps(source, text, true);
    }

    /** As {@link #warnOps(String, String)}, but with {@code chat} false the warning only reaches the server log. */
    void warnOps(String source, String text, boolean chat) {
        if (!text.equals(warningsLogged.put(source, text))) AiCiv.LOG.warn("AiCiv: {}", text);
        if (!chat || text.equals(warningsDelivered.get(source))) return;
        Component msg = Component.literal("[AiCiv] " + text).withStyle(ChatFormatting.RED);
        int told = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Commands.LEVEL_GAMEMASTERS.check(p.permissions())) {
                p.sendSystemMessage(msg);
                told++;
            }
        }
        if (told > 0) warningsDelivered.put(source, text);
    }

    /** Logs an unexpected exception from one bot's tick work, once per bot and phase, so the server keeps running. */
    void fault(String bot, String phase, RuntimeException e) {
        if (faultsLogged.add(key(bot) + "/" + phase)) {
            AiCiv.LOG.error("AiCiv {} failed for {}; later failures of this kind are not logged", phase, bot, e);
        }
    }

    // ---------------------------------------------------------------- commands

    public Bot spawn(String name, String modelKey, String persona, ServerLevel level, Vec3 pos) throws BotException {
        if (!NAME.matcher(name).matches()) throw new BotException("Bot names must be 3 to 16 letters, digits or _");
        if (server.getPlayerList().getPlayerByName(name) != null) throw new BotException(name + " is already online");
        if (name.equalsIgnoreCase("all")) throw new BotException("'all' is reserved");
        Optional<Roster.Entry> existing = roster.get(name);
        String model = modelKey != null ? modelKey
                : existing.map(e -> e.model).filter(llm::knowsModel).orElse(config.defaultModel);
        if (!llm.knowsModel(model)) {
            throw new BotException("Unknown model '" + model + "'. Configured models: " + String.join(", ", llm.modelKeys()));
        }
        Roster.Entry e = roster.getOrAdd(name);
        e.model = model;
        if (persona != null) e.persona = persona;
        e.paused = false;
        saveRoster();
        Bot b = join(e);
        b.player.moveTo(level, pos, 0.0F);
        return b;
    }

    public void remove(String name) throws BotException {
        Bot b = bots.get(key(name));
        boolean known = roster.remove(name);
        if (b == null && !known) throw new BotException("No AI player named " + name);
        if (b != null) logout(b);
        saveRoster();
    }

    public void setGoal(String name, String goal) throws BotException {
        Roster.Entry e = roster.get(name).orElseThrow(() -> new BotException("No AI player named " + name));
        e.goal = goal;
        Bot b = bots.get(key(name));
        if (b != null) b.brain.setGoal(goal);
        saveRoster();
    }

    /** Pauses or resumes one bot, or all of them when target is null or "all". Returns how many changed. */
    public int setPaused(String target, boolean paused) throws BotException {
        List<Roster.Entry> chosen = new ArrayList<>();
        if (target == null || target.equalsIgnoreCase("all")) {
            chosen.addAll(roster.all());
        } else {
            chosen.add(roster.get(target).orElseThrow(() -> new BotException("No AI player named " + target)));
        }
        for (Roster.Entry e : chosen) {
            e.paused = paused;
            Bot b = bots.get(key(e.name));
            if (b == null) continue;
            b.brain.setPaused(paused);
            if (paused) {
                b.executor.stop(b.context);
                queue.remove(b.name);
            }
        }
        saveRoster();
        return chosen.size();
    }

    public List<String> listLines() {
        if (roster.all().isEmpty()) return List.of("No AI players. Add one with /ai spawn <name> [model] [persona]");
        List<String> out = new ArrayList<>();
        for (Roster.Entry e : roster.all()) {
            Bot b = bots.get(key(e.name));
            String line = e.name + " [" + e.model + "] " + state(b);
            if (b != null && b.executor.busy()) line += ": " + b.executor.currentDescription();
            out.add(line);
        }
        return out;
    }

    public List<String> statusLines(String name) throws BotException {
        Roster.Entry e = roster.get(name).orElseThrow(() -> new BotException("No AI player named " + name));
        Bot b = bots.get(key(name));
        List<String> out = new ArrayList<>();
        out.add(e.name + " [" + e.model + "] " + state(b));
        out.add("Persona: " + (e.persona.isBlank() ? "(none)" : e.persona));
        out.add("Goal: " + (e.goal.isBlank() ? "(none)" : e.goal));
        if (b != null) {
            out.add("Doing: " + b.executor.currentDescription());
            out.add("Last decision: " + b.brain.lastDecision());
            int pos = queue.positionOf(b.name);
            out.add("Queue position: " + (pos < 0 ? "not waiting" : pos));
            out.add("Remembered events: " + b.brain.events().lines().size());
        }
        return out;
    }

    public List<String> budgetLines() {
        return llm.budgetLines();
    }

    public Optional<Bot> bot(String name) {
        return Optional.ofNullable(bots.get(key(name)));
    }

    public Collection<String> botNames() {
        return roster.all().stream().map(e -> e.name).toList();
    }

    public Set<String> modelKeys() {
        return llm.modelKeys();
    }

    // ---------------------------------------------------------------- ticking

    /** Start of server tick: search paths, steer, run actions. */
    public void tickStart() {
        navBudget.tick();
        for (Bot b : List.copyOf(bots.values())) {
            try {
                if (b.death != null && b.player.isDeadOrDying()) {
                    try {
                        respawn(b);
                    } catch (RuntimeException e) {
                        fault(b.name, "respawn", e);
                        logout(b);
                    }
                    continue;
                }
                if (b.player.isDeadOrDying() || b.brain.paused()) continue;
                b.navigator.tick();
                String ended = b.executor.tick(b.context);
                if (ended != null) b.brain.actionsEnded(stamp(), ended);
            } catch (RuntimeException e) {
                fault(b.name, "tick", e);
            }
        }
    }

    /** End of server tick: wake bots and send LLM requests. */
    public void tickEnd() {
        scheduler.tick(clock.millis());
    }

    // ---------------------------------------------------------------- events

    public void onChat(ServerPlayer speaker, String text) {
        routeChat(speaker.getScoreboardName(), speaker.level(), speaker.position(), text, null);
    }

    void botSays(Bot bot, String text) {
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("chat.type.text", bot.player.getDisplayName(), Component.literal(text)), false);
        bot.brain.note(stamp(), "you said: \"" + text + "\"");
        routeChat(bot.name, bot.player.level(), bot.player.position(), text, bot);
    }

    public void onDamage(LivingEntity entity, DamageSource source, float taken) {
        if (!(entity instanceof AiPlayer p) || taken <= 0) return;
        Bot b = bots.get(key(p.getScoreboardName()));
        if (b == null || b.player != p) return;
        Entity attacker = source.getEntity();
        String cause = attacker != null ? attacker.getName().getString() : source.getMsgId();
        b.brain.hurt(stamp(), String.format(Locale.ROOT, "you were hurt by %s (%.1f damage), health now %d/%d",
                cause, taken, Math.round(p.getHealth()), Math.round(p.getMaxHealth())), clock.millis());
    }

    /** Records the death; the bot respawns on the next tick, like a player clicking "Respawn". */
    public void onDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof AiPlayer p)) return;
        Bot b = bots.get(key(p.getScoreboardName()));
        if (b == null || b.player != p || b.death != null) return;
        BlockPos at = p.blockPosition();
        b.death = new Bot.Death(source.getLocalizedDeathMessage(p).getString(), at.getX(), at.getY(), at.getZ());
        queue.remove(b.name);
        b.executor.stop(b.context);
        b.navigator.stop();
        b.breaker.cancel();
    }

    private void respawn(Bot b) {
        Bot.Death death = b.death;
        b.death = null; // one respawn per death, whatever happens below
        AiPlayer old = b.player;
        ServerPlayer fresh = server.getPlayerList().respawn(old, false, Entity.RemovalReason.KILLED);
        if (!(fresh instanceof AiPlayer player)) throw new IllegalStateException("respawn produced " + fresh);
        // Vanilla moved the connection to the new player, but only the client-command path repoints the listener.
        player.connection.player = player;
        BlockBreaker breaker = new BlockBreaker(player);
        b.player = player;
        b.breaker = breaker;
        b.navigator = new LevelNavigator(player, navBudget, breaker);
        b.context = new LiveContext(this, b); // builds the action breaker for the new player too
        b.brain.actionsEnded(stamp(), String.format(Locale.ROOT, "you died: %s. Your items dropped at %d %d %d",
                death.message(), death.x(), death.y(), death.z()));
    }

    // ---------------------------------------------------------------- internals

    private Bot join(Roster.Entry e) {
        ServerGamePacketListenerImpl[] holder = new ServerGamePacketListenerImpl[1];
        AiPlayer p = AiPlayer.join(server, e.name, () -> server.execute(() -> handleDisconnect(e.name, holder[0])));
        holder[0] = p.connection; // the same listener serves the bot's replacement after a respawn
        BotBrain brain = new BotBrain(e.name, e.persona, e.goal, e.model, config.eventWindow,
                config.idleWakeSeconds * 1000L, config.mentionCooldownSeconds * 1000L, clock.millis());
        brain.setPaused(e.paused);
        BlockBreaker breaker = new BlockBreaker(p);
        Bot b = new Bot(e.name, p, brain, new ActionExecutor(), new LevelNavigator(p, navBudget, breaker), breaker);
        b.context = new LiveContext(this, b);
        bots.put(key(e.name), b);
        return b;
    }

    private void logout(Bot b) {
        b.executor.stop(b.context);
        b.navigator.stop();
        queue.remove(b.name);
        bots.remove(key(b.name));
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("multiplayer.player.left", b.player.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
        server.getPlayerList().remove(b.player);
    }

    private void handleDisconnect(String name, ServerGamePacketListenerImpl listener) {
        Bot b = bots.get(key(name));
        if (b != null && b.player.connection == listener) logout(b);
    }

    private void routeChat(String speaker, Level level, Vec3 pos, String text, Bot exclude) {
        long now = clock.millis();
        String stamp = stamp();
        for (Bot b : bots.values()) {
            if (b == exclude) continue;
            boolean mentioned = Hearing.mentions(text, b.name);
            double d2 = b.player.level() == level ? b.player.position().distanceToSqr(pos) : Double.MAX_VALUE;
            if (Hearing.hears(d2, config.hearingRange, mentioned)) b.brain.hearChat(stamp, speaker, text, mentioned, now);
        }
    }

    private String state(Bot b) {
        if (b == null) return "offline";
        if (b.brain.paused()) return "paused";
        if (b.brain.awaitingReply()) return "thinking";
        if (b.executor.busy()) return "acting";
        if (queue.positionOf(b.name) > 0) return "waiting for a turn";
        return "idle";
    }

    private void saveRoster() {
        try {
            roster.save(rosterFile);
        } catch (IOException e) {
            AiCiv.LOG.error("Could not save the AiCiv roster to {}", rosterFile, e);
        }
    }

    /** Names of everyone else online, tagged AI or human, for the system prompt. */
    List<String> othersFor(Bot self) {
        List<String> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == self.player) continue;
            out.add(p.getScoreboardName() + (p instanceof AiPlayer ? " [AI]" : " [human]"));
        }
        return out;
    }

    String stamp() {
        return GameTime.stamp(server.overworld().getOverworldClockTime());
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    Collection<Bot> bots() { return bots.values(); }
    ThinkQueue queue() { return queue; }
    LlmService llm() { return llm; }
    AiCivConfig config() { return config; }
    DecisionLog decisionLog() { return decisionLog; }
    MinecraftServer server() { return server; }
    Clock clock() { return clock; }
}
