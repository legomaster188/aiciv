package com.aiciv.modules;

import com.aiciv.AiCiv;
import com.aiciv.actions.ActionFactory;
import com.aiciv.animals.AnimalsModule;
import com.aiciv.actions.Attachments;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.building.BuildingModule;
import com.aiciv.combat.CombatModule;
import com.aiciv.dimension.DimensionModule;
import com.aiciv.dragon.DragonModule;
import com.aiciv.farm.FarmModule;
import com.aiciv.flight.FlightModule;
import com.aiciv.gear.GearModule;
import com.aiciv.items.ItemsModule;
import com.aiciv.nether.NetherModule;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.aiciv.stations.StationsModule;
import com.aiciv.storage.StorageModule;
import com.aiciv.stronghold.StrongholdModule;
import com.aiciv.trade.TradeModule;
import com.aiciv.utility.UtilityModule;
import com.aiciv.vehicles.VehicleModule;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

/** The registry of feature modules. Shared code asks it, never a module directly. */
public final class ToolModules {
    /** Never edited by the feature tasks; they fill their stub instead. */
    public static final List<ToolModule> ALL = List.of(new BuildingModule(), new StorageModule(), new CombatModule(),
            new TradeModule(), new FarmModule(), new UtilityModule(), new GearModule(), new DimensionModule(), new NetherModule(),
            new StrongholdModule(), new DragonModule(), new VehicleModule(), new FlightModule(), new ItemsModule(),
            new AnimalsModule(), new StationsModule());

    /** Module names that already logged a failure, so a module that throws every tick logs once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    static {
        checkUnique(ALL, ActionFactory.coreKeys());
    }

    private ToolModules() {}

    /** The factory's rule: lower case with '_' and '-' removed. */
    public static String normalise(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /** Every module's catalog functions, in module order. */
    public static List<JsonObject> tools() {
        List<JsonObject> out = new ArrayList<>();
        for (ToolModule m : ALL) out.addAll(m.tools());
        return out;
    }

    /** Every module tool's normalised name. */
    public static Set<String> keys() {
        Set<String> out = new LinkedHashSet<>();
        for (JsonObject t : tools()) out.add(normalise(toolName(t)));
        return out;
    }

    public static ActionFactory.Result create(String key, JsonObject args) {
        return createFrom(ALL, key, args);
    }

    /** The first module's non-null result, else null. */
    static ActionFactory.Result createFrom(List<ToolModule> modules, String key, JsonObject args) {
        for (ToolModule m : modules) {
            ActionFactory.Result r = m.create(key, args);
            if (r != null) return r;
        }
        return null;
    }

    public static List<String> guideLines() {
        List<String> out = new ArrayList<>();
        for (ToolModule m : ALL) out.addAll(m.guideLines());
        return out;
    }

    public static List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        return menuOptionsFrom(ALL, snap, ctx);
    }

    /** Module options in module order; a module that throws contributes nothing. Public for OptionMenu's test overload. */
    public static List<MenuOption> menuOptionsFrom(List<ToolModule> modules, SnapshotData snap, OptionMenu.MenuContext ctx) {
        return collect(modules, "menu options", m -> m.menuOptions(snap, ctx));
    }

    /** Server thread. */
    public static List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        return collect(ALL, "snapshot sections", m -> m.sections(bot, memory));
    }

    public static void serverStarted(MinecraftServer server) {
        each("serverStarted", m -> m.serverStarted(server));
    }

    public static void autosave(MinecraftServer server) {
        each("autosave", m -> m.autosave(server));
    }

    public static void serverStopping(MinecraftServer server) {
        each("serverStopping", m -> m.serverStopping(server));
    }

    /** Throws when a module tool's normalised name is a core action or another module's tool. */
    public static void checkUnique(List<ToolModule> modules, Set<String> coreKeys) {
        Map<String, String> owner = new HashMap<>();
        for (ToolModule m : modules) {
            for (JsonObject t : m.tools()) {
                String name = toolName(t);
                String key = normalise(name);
                if (coreKeys.contains(key)) {
                    throw new IllegalStateException("tool '" + name + "' from " + m.name() + " clashes with core action '" + key + "'");
                }
                String other = owner.putIfAbsent(key, m.name());
                if (other != null) throw new IllegalStateException("tool '" + name + "' from " + m.name() + " clashes with " + other);
            }
        }
    }

    private static String toolName(JsonObject tool) {
        return tool.getAsJsonObject("function").get("name").getAsString();
    }

    private static <T> List<T> collect(List<ToolModule> modules, String what, Function<ToolModule, List<T>> get) {
        List<T> out = new ArrayList<>();
        for (ToolModule m : modules) {
            try {
                List<T> part = get.apply(m);
                if (part != null) out.addAll(part);
            } catch (RuntimeException e) {
                warn(m, what, e);
            }
        }
        return out;
    }

    private static void each(String what, Consumer<ToolModule> hook) {
        for (ToolModule m : ALL) {
            try {
                hook.accept(m);
            } catch (RuntimeException e) {
                warn(m, what, e);
            }
        }
    }

    private static void warn(ToolModule m, String what, RuntimeException e) {
        if (WARNED.add(m.name())) AiCiv.LOG.warn("AiCiv module {} failed in {}; it is skipped there", m.name(), what, e);
    }
}
