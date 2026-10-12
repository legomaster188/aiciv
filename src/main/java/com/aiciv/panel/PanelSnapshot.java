package com.aiciv.panel;

import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.LlmService;
import com.aiciv.perception.GameTime;
import com.aiciv.reflex.ReflexPolicy.Reflex;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Everything /api/state serves, copied out of the game on the server thread so HTTP threads never touch game state.
 * Null fields are left out of the JSON.
 */
public record PanelSnapshot(String time, long dayTime, List<BotView> bots, List<ModelView> models,
                            List<LlmService.ProviderInfo> providers) {
    public record BotView(String name, String model, String provider, String mode, boolean paused, boolean dead,
                          double health, int food, double x, double y, double z, String dimension, String holding,
                          String action, String reflex, JsonObject lastDecision, String goal, String persona) {}

    public record ModelView(String key, String provider, String model, String mode) {}

    /** Server thread only. */
    public static PanelSnapshot capture(BotManager m) {
        long clock = m.server().overworld().getOverworldClockTime();
        AiCivConfig config = m.config();
        List<BotView> bots = new ArrayList<>();
        for (Bot b : m.bots()) bots.add(view(m, b, config));
        List<ModelView> models = new ArrayList<>();
        config.models.forEach((key, mc) -> models.add(new ModelView(key, mc.provider, mc.model, mode(config, key))));
        return new PanelSnapshot(GameTime.stamp(clock), Math.floorMod(clock, 24000L), List.copyOf(bots), List.copyOf(models),
                List.copyOf(m.llm().providerInfos()));
    }

    private static BotView view(BotManager m, Bot b, AiCivConfig config) {
        AiPlayer p = b.player;
        String model = b.brain.modelKey();
        AiCivConfig.ModelConfig mc = config.models.get(model);
        Reflex reflex = b.reflexes.active();
        return new BotView(b.name, model, mc == null ? null : mc.provider, mode(config, model), b.brain.paused(), b.dead(),
                Math.round(p.getHealth() * 10) / 10.0, p.getFoodData().getFoodLevel(),
                Math.round(p.getX() * 10) / 10.0, Math.round(p.getY() * 10) / 10.0, Math.round(p.getZ() * 10) / 10.0,
                p.level().dimension().identifier().toString(), holding(p.getMainHandItem()),
                b.executor.busy() ? b.executor.currentDescription() : "idle",
                reflex == Reflex.NONE ? null : reflex.name().toLowerCase(java.util.Locale.ROOT),
                m.decisionLog().last(b.name), b.brain.goal(), b.brain.persona());
    }

    private static String mode(AiCivConfig config, String model) {
        return config.isMenuMode(model) ? "menu" : "tools";
    }

    private static String holding(ItemStack stack) {
        if (stack.isEmpty()) return null;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return stack.getCount() > 1 ? stack.getCount() + " " + id : id;
    }
}
