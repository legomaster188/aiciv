package com.aiciv.modules;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Attachments;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

/** One feature's actions: its catalog functions, factory entries, menu options, snapshot lines and lifecycle. */
public interface ToolModule {
    String name();

    /** OpenAI function objects ({"type":"function","function":{...}}), built with Tools.fn. */
    List<JsonObject> tools();

    /** key = call name lower-cased with '_' and '-' removed. Null when the key is not this module's. */
    ActionFactory.Result create(String key, JsonObject args);

    default List<String> guideLines() {
        return List.of();
    }

    /**
     * Whether this module's tools (and guide lines) are worth sending in a gated catalog this turn. Every tool-mode
     * request carries the catalog, so modules that only matter in some situations (the End, a nearby station, an item
     * carried) say so here. Pure like {@link #menuOptions}: decide from {@code snap} ({@link Situation} helps);
     * {@code ctx} may be null. The full catalog ignores it.
     */
    default boolean relevant(SnapshotData snap, BotContext ctx) {
        return true;
    }

    /** Pure: may read only snap and ctx (no world access), so it is unit testable. */
    default List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        return List.of();
    }

    /** Fixtures for ModuleSyncTest: a situation in which this module offers at least one option. */
    default SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(), List.of());
    }

    default OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of());
    }

    /** Server thread. Extra snapshot lines (e.g. "Structures: ..."). */
    default List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        return List.of();
    }

    default void serverStarted(MinecraftServer server) {}

    default void autosave(MinecraftServer server) {}

    default void serverStopping(MinecraftServer server) {}
}
