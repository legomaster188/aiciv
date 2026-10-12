package com.aiciv;

import com.aiciv.bot.AiCommands;
import com.aiciv.bot.BotManager;
import com.aiciv.brain.DecisionLog;
import com.aiciv.config.AiCivConfig;
import com.aiciv.config.KeyStore;
import com.aiciv.llm.LlmService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiCiv implements ModInitializer {
    public static final String MOD_ID = "aiciv";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static volatile BotManager manager;
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
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
        ServerTickEvents.START_SERVER_TICK.register(s -> {
            BotManager m = manager;
            if (m != null) m.tickStart();
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            BotManager m = manager;
            if (m != null) m.tickEnd();
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            BotManager m = manager;
            if (m != null) m.onChat(sender, message.signedContent());
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            BotManager m = manager;
            if (m != null) m.onDamage(entity, source, damageTaken);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            BotManager m = manager;
            if (m != null) m.onDeath(entity);
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
        m.reload(l.config(), l.llm());
        return l.config().validate();
    }

    private static List<String> start(MinecraftServer server) throws IOException {
        Loaded l = loadServices();
        Path roster = server.getWorldPath(LevelResource.ROOT).resolve("aiciv").resolve("roster.json");
        DecisionLog log = new DecisionLog(FabricLoader.getInstance().getGameDir().resolve("logs").resolve("aiciv"));
        manager = new BotManager(server, l.config(), l.llm(), roster, log, Clock.systemUTC());
        manager.restoreAll();
        return l.config().validate();
    }

    private static void stop() {
        BotManager m = manager;
        manager = null;
        server = null;
        if (m != null) m.shutdown();
    }

    private record Loaded(AiCivConfig config, LlmService llm) {}

    private static Loaded loadServices() throws IOException {
        AiCivConfig config = AiCivConfig.loadOrCreate(configDir.resolve("aiciv.json"));
        for (String problem : config.validate()) LOG.warn("aiciv.json: {}", problem);
        KeyStore keys = KeyStore.load(configDir.resolve("keys.json"), System::getenv);
        LlmService llm = LlmService.create(config, keys, configDir, Clock.systemUTC());
        for (String provider : config.providers.keySet()) {
            if (llm.providerDisabled(provider)) LOG.warn("AiCiv provider {} is {}", provider, llm.providerStatus(provider));
        }
        return new Loaded(config, llm);
    }
}
