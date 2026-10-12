package com.aiciv.gametest;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.ToolModules;
import com.aiciv.trade.VillagerMemory;
import com.aiciv.world.InventoryOps;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Trading with villagers (Task 5). Bot names start with TestTrade. */
public final class TradeGameTests {
    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("trade")), "the trade module is not registered");
        helper.succeed();
    }

    /** A farmer that stands still and sells 6 bread for an emerald (offer 0) and buys 20 wheat for an emerald (offer 1). */
    private static Villager farmer(GameTestHelper helper, double x, double z, boolean baby) {
        Villager v = helper.spawn(EntityTypes.VILLAGER, new Vec3(x, 1, z));
        v.setNoAi(true);
        v.setVillagerData(v.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.FARMER)));
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), Optional.empty(), new ItemStack(Items.BREAD, 6), 0, 12, 1, 0.05f, 0));
        offers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 20), Optional.empty(), new ItemStack(Items.EMERALD), 0, 16, 2, 0.05f, 0));
        v.setOffers(offers); // overrideOffers is a no-op on the server
        if (baby) v.setBaby(true);
        return v;
    }

    private static Action action(String name, String json) {
        JsonObject args = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        ActionFactory.Result r = ActionFactory.create(ToolCall.of(name, args));
        if (r instanceof ActionFactory.Result.Ok ok) return ok.action();
        throw new IllegalStateException(name + " " + json + ": " + r);
    }

    private static GameTestKit.Spawned arena(GameTestHelper helper, String bot) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.clearBarriers(helper, 8);
        return GameTestKit.spawn(helper, bot, 1.5, 3.5);
    }

    private static boolean ended(GameTestKit.Spawned s) {
        String events = GameTestKit.events(s.bot());
        return events.contains("finished: ") || events.contains("failed: ");
    }

    private static int count(GameTestKit.Spawned s, net.minecraft.world.item.Item item) {
        return InventoryOps.count(s.bot().player, st -> st.is(item));
    }

    private static void assertMenuClosed(GameTestHelper helper, GameTestKit.Spawned s) {
        helper.assertTrue(s.bot().player.containerMenu == s.bot().player.inventoryMenu, "the trading menu is still open");
        helper.assertTrue(s.bot().player.containerMenu.getCarried().isEmpty(), "something is left on the cursor");
    }

    @GameTest(maxTicks = 400, padding = 24)
    public void tradeListPutsOffersInEvents(GameTestHelper helper) {
        farmer(helper, 6.5, 3.5, false);
        GameTestKit.Spawned s = arena(helper, "TestTradeList");
        s.bot().executor.replace(List.of(action("trade_list", "{}")), s.bot().context);
        helper.runAfterDelay(395, () -> GameTestKit.remove(s));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("0: 1 emerald -> 6 bread"), "no bread offer in the events: " + events);
            helper.assertTrue(events.contains("1: 20 wheat -> 1 emerald"), "no wheat offer in the events: " + events);
            helper.assertTrue(events.contains("finished: "), "trade_list did not finish: " + events);
            VillagerMemory memory = s.bot().attachments.find(VillagerMemory.class).orElse(null);
            helper.assertTrue(memory != null && memory.size() == 1, "the farmer is not remembered");
            helper.assertTrue(memory.tags(new com.aiciv.nav.Pos(0, 0, 0)).contains("villager:farmer"), "no farmer tag: " + memory.tags(new com.aiciv.nav.Pos(0, 0, 0)));
            assertMenuClosed(helper, s);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 500, padding = 24)
    public void tradeBuysBreadTwice(GameTestHelper helper) {
        Villager v = farmer(helper, 6.5, 3.5, false);
        GameTestKit.Spawned s = arena(helper, "TestTradeBuy");
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));
        s.bot().executor.replace(List.of(action("trade", "{\"villager\":\"farmer\",\"want\":\"bread\",\"times\":2}")), s.bot().context);
        helper.runAfterDelay(495, () -> GameTestKit.remove(s));
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(s), "trade has not ended: " + GameTestKit.events(s.bot()));
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: traded 2x: 2 emerald -> 12 bread"), "unexpected result: " + events);
            helper.assertTrue(count(s, Items.BREAD) == 12, "bread: " + count(s, Items.BREAD));
            helper.assertTrue(count(s, Items.EMERALD) == 1, "emeralds: " + count(s, Items.EMERALD));
            helper.assertTrue(v.getOffers().get(0).getUses() == 2, "uses: " + v.getOffers().get(0).getUses());
            assertMenuClosed(helper, s);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400, padding = 24)
    public void tradeFailsWhenTooPoor(GameTestHelper helper) {
        farmer(helper, 6.5, 3.5, false);
        GameTestKit.Spawned s = arena(helper, "TestTradePoor");
        s.bot().executor.replace(List.of(action("trade", "{\"want\":\"bread\"}")), s.bot().context);
        helper.runAfterDelay(395, () -> GameTestKit.remove(s));
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(s), "trade has not ended: " + GameTestKit.events(s.bot()));
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("failed: ") && events.contains("needs 1 emerald"), "unexpected result: " + events);
            helper.assertTrue(count(s, Items.BREAD) == 0, "bread appeared from nowhere");
            assertMenuClosed(helper, s);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 500, padding = 24)
    public void tradeSellsWheat(GameTestHelper helper) {
        farmer(helper, 6.5, 3.5, false);
        GameTestKit.Spawned s = arena(helper, "TestTradeSell");
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 20));
        s.bot().executor.replace(List.of(action("trade", "{\"offer\":1}")), s.bot().context);
        helper.runAfterDelay(495, () -> GameTestKit.remove(s));
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(s), "trade has not ended: " + GameTestKit.events(s.bot()));
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: traded 1x: 20 wheat -> 1 emerald"), "unexpected result: " + GameTestKit.events(s.bot()));
            helper.assertTrue(count(s, Items.EMERALD) == 1, "emeralds: " + count(s, Items.EMERALD));
            helper.assertTrue(count(s, Items.WHEAT) == 0, "wheat: " + count(s, Items.WHEAT));
            assertMenuClosed(helper, s);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400, padding = 24)
    public void tradeNamesWhatTheVillagerDoesSell(GameTestHelper helper) {
        farmer(helper, 6.5, 3.5, false);
        GameTestKit.Spawned s = arena(helper, "TestTradeNone");
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));
        s.bot().executor.replace(List.of(action("trade", "{\"want\":\"diamond\"}")), s.bot().context);
        helper.runAfterDelay(395, () -> GameTestKit.remove(s));
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(s), "trade has not ended: " + GameTestKit.events(s.bot()));
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("doesn't sell diamond; offers: 0: 1 emerald -> 6 bread"), "unexpected result: " + events);
            helper.assertTrue(count(s, Items.EMERALD) == 3, "emeralds were spent: " + count(s, Items.EMERALD));
            assertMenuClosed(helper, s);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400, padding = 24)
    public void babyVillagerWontTrade(GameTestHelper helper) {
        farmer(helper, 6.5, 3.5, true);
        GameTestKit.Spawned s = arena(helper, "TestTradeBaby");
        s.bot().executor.replace(List.of(action("trade_list", "{\"villager\":\"farmer\"}")), s.bot().context);
        helper.runAfterDelay(395, () -> GameTestKit.remove(s));
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(s), "trade_list has not ended: " + GameTestKit.events(s.bot()));
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("failed: ") && (events.contains("won't trade") || events.contains("no farmer nearby")),
                    "unexpected result: " + events);
            assertMenuClosed(helper, s);
            GameTestKit.remove(s);
        });
    }
}
