package com.aiciv;

import com.aiciv.autopilot.personality.Personalities;
import com.aiciv.bot.AiCommands;
import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.BotManager;
import com.aiciv.brain.DecisionLog;
import com.aiciv.config.AiCivConfig;
import com.aiciv.config.KeyStore;
import com.aiciv.llm.LlmService;
import com.aiciv.modules.ToolModules;
import com.aiciv.panel.PanelServer;
import com.aiciv.world.PlacedBlocks;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiCiv implements ModInitializer {
    public static final String MOD_ID = "aiciv";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    /** Five minutes: how often world-bound module state (placed blocks, structures) is saved when dirty. */
    private static final int AUTOSAVE_TICKS = 6000;

    private static volatile BotManager manager;
    private static volatile PanelServer panel;
    private static Path configDir;
    private static volatile MinecraftServer server;

    @Override
    public void onInitialize() {
        configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            try {
                start(s);
            } catch (IOException | RuntimeException e) {
                LOG.error("AiCiv failed to start; fix the config and run /ai reload", e);
            }
            PlacedBlocks.of(s);
            ToolModules.serverStarted(s);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            ToolModules.serverStopping(s);
            PlacedBlocks.unload(s);
            stop();
        });
        ServerTickEvents.START_SERVER_TICK.register(s -> {
            BotManager m = manager;
            if (m != null) m.tickStart();
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            BotManager m = manager;
            if (m != null) m.tickEnd();
            PanelServer p = panel;
            if (p != null) p.tick();
            if (s.getTickCount() % AUTOSAVE_TICKS == 0) {
                PlacedBlocks.of(s).save();
                ToolModules.autosave(s);
            }
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            BotManager m = manager;
            if (m != null) m.onChat(sender, message.signedContent());
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            BotManager m = manager;
            if (m != null) m.onDamage(entity, source, damageTaken);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            BotManager m = manager;
            // Bots are announced by BotManager.join, once they are set up.
            if (m != null && !(handler.player instanceof AiPlayer)) m.onJoin(handler.player);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            BotManager m = manager;
            if (m != null) m.onDeath(entity, source);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> AiCommands.register(dispatcher));
        LOG.info("AiCiv initialized");
    }

    /** The running server's bot manager, or null when no server is running or AiCiv failed to start. */
    public static BotManager manager() {
        return manager;
    }

    /** Re-reads aiciv.json and keys.json, retrying startup if it failed. Returns config problems; empty when clean. */
    public static List<String> reload() throws IOException {
        BotManager m = manager;
        if (m == null) {
            MinecraftServer s = server;
            if (s == null) throw new IOException("AiCiv is not running");
            return start(s);
        }
        Loaded l = loadServices();
        m.reload(l.config(), l.llm(), l.personalities());
        return l.problems();
    }

    private static List<String> start(MinecraftServer server) throws IOException {
        Loaded l = loadServices();
        Path roster = server.getWorldPath(LevelResource.ROOT).resolve("aiciv").resolve("roster.json");
        DecisionLog log = new DecisionLog(FabricLoader.getInstance().getGameDir().resolve("logs").resolve("aiciv"));
        manager = new BotManager(server, l.config(), l.llm(), roster, log, Clock.systemUTC(), l.personalities());
        manager.restoreAll();
        startPanel(server, l.config());
        return l.problems();
    }

    /** The running admin panel, or null when it is disabled or could not bind its port. */
    public static PanelServer panel() {
        return panel;
    }

    private static void startPanel(MinecraftServer server, AiCivConfig config) {
        if (!config.panel.enabled || panel != null) return;
        try {
            panel = PanelServer.start(server, manager, config.panel.port, AiCiv::reload);
            LOG.info("AiCiv panel at {} (use /ai panel for the link)", panel.baseUrl());
        } catch (IOException | RuntimeException e) {
            LOG.warn("AiCiv panel could not start on port {}: {}", config.panel.port, e.toString());
        }
    }

    private static void stop() {
        BotManager m = manager;
        manager = null;
        PanelServer p = panel;
        panel = null;
        if (p != null) p.stop();
        server = null;
        if (m != null) m.shutdown();
    }

    /** {@code problems}: aiciv.json problems, then personalities.json problems. */
    private record Loaded(AiCivConfig config, LlmService llm, Personalities personalities, List<String> problems) {}

    private static Loaded loadServices() throws IOException {
        AiCivConfig config = AiCivConfig.loadOrCreate(configDir.resolve("aiciv.json"));
        Personalities personalities = Personalities.load(configDir);
        List<String> problems = new ArrayList<>(config.validate(personalities.ids()));
        for (String problem : problems) LOG.warn("aiciv.json: {}", problem);
        for (String problem : personalities.problems()) LOG.warn("AiCiv {}", problem);
        problems.addAll(personalities.problems());
        KeyStore keys = KeyStore.load(configDir.resolve("keys.json"), System::getenv);
        LlmService llm = LlmService.create(config, keys, configDir, Clock.systemUTC());
        for (String provider : config.providers.keySet()) {
            if (llm.providerDisabled(provider)) LOG.warn("AiCiv provider {} is {}", provider, llm.providerStatus(provider));
        }
        return new Loaded(config, llm, personalities, problems);
    }
}
