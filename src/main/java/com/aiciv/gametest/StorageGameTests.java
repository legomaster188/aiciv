package com.aiciv.gametest;

import com.aiciv.actions.Action;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.modules.ToolModules;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotFormatter;
import com.aiciv.storage.ChestMemory;
import com.aiciv.storage.DepositAction;
import com.aiciv.storage.DepositRule;
import com.aiciv.storage.GiveAction;
import com.aiciv.storage.LookInChestAction;
import com.aiciv.storage.NameMobAction;
import com.aiciv.storage.RenameAction;
import com.aiciv.storage.SignAction;
import com.aiciv.storage.WithdrawAction;
import com.aiciv.world.InventoryOps;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.Vec3;

/** Chests, giving, naming and signs (Task 3). Bot names start with TestStore. */
public final class StorageGameTests {
    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("storage")), "the storage module is not registered");
        helper.succeed();
    }

    private static GameTestKit.Spawned arena(GameTestHelper helper, String name, int maxTicks) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, name, 2.5, 3.5);
        helper.runAfterDelay(maxTicks - 5, () -> GameTestKit.remove(s));
        return s;
    }

    private static void run(GameTestKit.Spawned s, Action action) {
        s.bot().executor.replace(List.of(action), s.bot().context);
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    private static int total(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
        return n;
    }

    private static int held(Bot bot, Item item) {
        return InventoryOps.count(bot.player, s -> s.is(item));
    }

    private static BlockPos placeChest(GameTestHelper helper, int x, int y, int z) {
        helper.setBlock(x, y, z, Blocks.CHEST);
        return new BlockPos(x, y, z);
    }

    @GameTest(maxTicks = 400)
    public void depositAllExceptToolsKeepsPickaxe(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreDeposit", 400);
        BlockPos chest = placeChest(helper, 5, 1, 3);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        bot.player.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 20));
        bot.player.getInventory().setItem(2, new ItemStack(Items.OAK_LOG, 5));
        bot.player.getInventory().setItem(3, new ItemStack(Items.BREAD, 3));
        run(s, new DepositAction(DepositRule.ALL_EXCEPT_TOOLS, null));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(bot).contains("finished: deposited 20 cobblestone, 5 oak_log"), "deposit did not finish: " + GameTestKit.events(bot));
            ChestBlockEntity be = helper.getBlockEntity(chest, ChestBlockEntity.class);
            helper.assertTrue(count(be, Items.COBBLESTONE) == 20 && count(be, Items.OAK_LOG) == 5, "chest holds the wrong items");
            helper.assertTrue(total(be) == 25, "the chest also took the pickaxe or bread");
            helper.assertTrue(held(bot, Items.STONE_PICKAXE) == 1 && held(bot, Items.BREAD) == 3, "the bot lost its pickaxe or bread");
            BlockPos abs = helper.absolutePos(chest);
            ChestMemory memory = bot.attachments.find(ChestMemory.class).orElse(null);
            helper.assertTrue(memory != null, "no chest memory");
            Map<String, Integer> remembered = memory.contents(new Pos(abs.getX(), abs.getY(), abs.getZ())).orElse(Map.of());
            helper.assertTrue(remembered.equals(Map.of("cobblestone", 20, "oak_log", 5)), "remembered " + remembered);
            String text = SnapshotFormatter.format(Perceiver.capture(bot.player, "none", "none", p -> false, bot.knownBlocks, bot.attachments), 6000);
            helper.assertTrue(text.contains("Chests: chest at"), "snapshot lacks the Chests line:\n" + text);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400)
    public void withdrawTakesExactCount(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreTake", 400);
        BlockPos chest = placeChest(helper, 5, 1, 3);
        helper.getBlockEntity(chest, ChestBlockEntity.class).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        run(s, new WithdrawAction("cobblestone", 10, null));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: took 10 cobblestone"), "withdraw did not finish: " + GameTestKit.events(s.bot()));
            helper.assertTrue(held(s.bot(), Items.COBBLESTONE) == 10, "the bot holds " + held(s.bot(), Items.COBBLESTONE));
            helper.assertTrue(count(helper.getBlockEntity(chest, ChestBlockEntity.class), Items.COBBLESTONE) == 54, "the chest should keep 54");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400)
    public void withdrawStopsWhenInventoryFull(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreFull", 400);
        BlockPos chest = placeChest(helper, 5, 1, 3);
        ChestBlockEntity be = helper.getBlockEntity(chest, ChestBlockEntity.class);
        be.setItem(0, new ItemStack(Items.STONE, 64));
        be.setItem(1, new ItemStack(Items.STONE, 64));
        for (int i = 0; i < 36; i++) if (i != 20) s.bot().player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        run(s, new WithdrawAction("stone", 100, null));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("inventory full"), "expected an inventory full report: " + events);
            helper.assertTrue(held(s.bot(), Items.STONE) == 64, "the bot holds " + held(s.bot(), Items.STONE) + " stone");
            helper.assertTrue(count(helper.getBlockEntity(chest, ChestBlockEntity.class), Items.STONE) == 64, "the chest should keep 64 stone");
            var around = s.bot().player.getBoundingBox().inflate(12);
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, e -> e.getItem().is(Items.STONE)).isEmpty(),
                    "stone was dropped on the floor");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400)
    public void lookInChestReportsContents(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreLook", 400);
        BlockPos chest = placeChest(helper, 5, 1, 3);
        helper.getBlockEntity(chest, ChestBlockEntity.class).setItem(4, new ItemStack(Items.COBBLESTONE, 64));
        run(s, new LookInChestAction(null));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("cobblestone x64"), "events: " + events);
            helper.assertTrue(events.contains("finished"), "look did not finish: " + events);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 700)
    public void chestBehindWallFails(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestStoreWall", 1.5, 4.5);
        helper.runAfterDelay(695, () -> GameTestKit.remove(s));
        BlockPos chest = placeChest(helper, 5, 1, 4);
        helper.getBlockEntity(chest, ChestBlockEntity.class).setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        // Glass on every side of the chest and of the one-block pocket beside it, so nothing can be seen into.
        for (BlockPos p : List.of(new BlockPos(4, 1, 4), new BlockPos(5, 1, 3), new BlockPos(5, 1, 5), new BlockPos(5, 2, 4),
                new BlockPos(7, 1, 4), new BlockPos(6, 1, 3), new BlockPos(6, 1, 5), new BlockPos(6, 2, 4))) {
            helper.setBlock(p, Blocks.GLASS);
        }
        run(s, new LookInChestAction(helper.absolutePos(chest)));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("failed") && events.contains("can't see"), "expected can't see: " + events);
            helper.assertTrue(count(helper.getBlockEntity(chest, ChestBlockEntity.class), Items.COBBLESTONE) == 10, "the chest changed");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400)
    public void cancelDuringDepositClosesChest(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        boolean[] stopped = {false};
        Bot[] watched = {null};
        // The check runs right after the bot's tick: the chest is open between the tick it opens and the next one,
        // and a separate onEachTick could run on either side of the bot's tick (that made this test fail at random).
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestStoreCancel", 2.5, 3.5, () -> {
            Bot b = watched[0];
            if (b != null && !stopped[0] && b.player.containerMenu != b.player.inventoryMenu) {
                b.executor.stop(b.context);
                stopped[0] = true;
            }
        });
        helper.runAfterDelay(395, () -> GameTestKit.remove(s));
        BlockPos chest = placeChest(helper, 5, 1, 3);
        Bot bot = s.bot();
        watched[0] = bot;
        Item[] items = {Items.COBBLESTONE, Items.DIRT, Items.STONE, Items.SAND, Items.GRAVEL, Items.OAK_LOG, Items.BIRCH_LOG,
            Items.SPRUCE_LOG, Items.ANDESITE, Items.GRANITE};
        for (int i = 0; i < items.length; i++) bot.player.getInventory().setItem(i, new ItemStack(items[i], 20));
        run(s, new DepositAction(DepositRule.ALL, null));
        helper.succeedWhen(() -> {
            helper.assertTrue(stopped[0], "the chest never opened; " + GameTestKit.events(bot));
            helper.assertTrue(bot.player.containerMenu == bot.player.inventoryMenu, "the chest menu is still open");
            helper.assertTrue(bot.player.containerMenu.getCarried().isEmpty(), "something is on the cursor");
            int inBot = 0;
            for (Item item : items) inBot += held(bot, item);
            int inChest = total(helper.getBlockEntity(chest, ChestBlockEntity.class));
            helper.assertTrue(inBot + inChest == 200, "items went missing: bot " + inBot + " + chest " + inChest);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 500)
    public void giveThrowsItemsToPlayer(GameTestHelper helper) throws Exception {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        BotManager manager = AiCivGameTests.scriptedManager(helper, new ScriptedBackend(List.of()), Files.createTempDirectory("aiciv-gametest"));
        Bot a = manager.spawn("TestStoreGiveA", "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 3.5)));
        Bot b = manager.spawn("TestStoreGiveB", "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(7.5, 1, 3.5)));
        helper.onEachTick(manager::tickStart);
        helper.runAfterDelay(495, () -> {
            for (String name : List.of("TestStoreGiveA", "TestStoreGiveB")) {
                if (manager.bot(name).isPresent()) removeBot(manager, name);
            }
        });
        a.player.getInventory().setItem(0, new ItemStack(Items.BREAD, 5));
        a.executor.replace(List.of(new GiveAction("TestStoreGiveB", "bread", 5)), a.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(held(b, Items.BREAD) == 5, "TestStoreGiveB holds " + held(b, Items.BREAD) + " bread; A: " + GameTestKit.events(a));
            helper.assertTrue(GameTestKit.events(b).contains("TestStoreGiveA threw you 5 bread"), "B's events: " + GameTestKit.events(b));
            removeBot(manager, "TestStoreGiveA");
            removeBot(manager, "TestStoreGiveB");
        });
    }

    private static void removeBot(BotManager manager, String name) {
        try {
            manager.remove(name);
        } catch (com.aiciv.bot.BotException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ItemStack tag(String name) {
        ItemStack tag = new ItemStack(Items.NAME_TAG);
        if (name != null) tag.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return tag;
    }

    private static boolean holdsTagNamed(Bot bot, String name) {
        for (ItemStack s : bot.player.getInventory().getNonEquipmentItems()) {
            if (s.is(Items.NAME_TAG) && s.get(DataComponents.CUSTOM_NAME) != null
                    && s.get(DataComponents.CUSTOM_NAME).getString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @GameTest(maxTicks = 400)
    public void renameAtAnvilCostsLevels(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreAnvil", 400);
        helper.setBlock(5, 1, 3, Blocks.ANVIL);
        s.bot().player.getInventory().setItem(0, tag(null));
        s.bot().player.giveExperienceLevels(5);
        run(s, new RenameAction("name_tag", "Bessie"));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: renamed name_tag to \"Bessie\" for 1 level"), "events: " + GameTestKit.events(s.bot()));
            helper.assertTrue(holdsTagNamed(s.bot(), "Bessie"), "no tag called Bessie");
            helper.assertTrue(s.bot().player.experienceLevel == 4, "levels: " + s.bot().player.experienceLevel);
            helper.assertTrue(s.bot().player.containerMenu == s.bot().player.inventoryMenu, "the anvil is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400)
    public void renameFailsWithoutLevels(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreNoXp", 400);
        helper.setBlock(5, 1, 3, Blocks.ANVIL);
        s.bot().player.getInventory().setItem(0, tag(null));
        run(s, new RenameAction("name_tag", "Bessie"));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("costs 1 level"), "events: " + GameTestKit.events(s.bot()));
            helper.assertTrue(held(s.bot(), Items.NAME_TAG) == 1 && !holdsTagNamed(s.bot(), "Bessie"), "the tag was lost or renamed");
            helper.assertTrue(s.bot().player.containerMenu == s.bot().player.inventoryMenu, "the anvil is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 500)
    public void nameMobWithTag(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreMob", 500);
        Cow cow = helper.spawn(EntityTypes.COW, new Vec3(5.5, 1, 3.5));
        cow.setNoAi(true);
        s.bot().player.getInventory().setItem(0, tag("Bessie"));
        run(s, new NameMobAction("cow", "Bessie"));
        helper.succeedWhen(() -> {
            helper.assertTrue(cow.getCustomName() != null && cow.getCustomName().getString().equals("Bessie"), "the cow is called " + cow.getCustomName()
                    + "; " + GameTestKit.events(s.bot()));
            helper.assertTrue(held(s.bot(), Items.NAME_TAG) == 0, "the tag was not consumed");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void signShowsText(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreSign", 300);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.OAK_SIGN));
        run(s, new SignAction("Welcome to Ada town", null));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished"), "sign did not finish: " + GameTestKit.events(s.bot()));
            SignBlockEntity found = null;
            for (int x = 0; x < 8 && found == null; x++) {
                for (int z = 0; z < 8 && found == null; z++) {
                    BlockPos rel = new BlockPos(x, 1, z);
                    if (!helper.getBlockState(rel).is(BlockTags.STANDING_SIGNS)) continue;
                    if (helper.absoluteVec(Vec3.atCenterOf(rel)).distanceTo(s.bot().player.position()) > 3.0) continue;
                    found = helper.getBlockEntity(rel, SignBlockEntity.class);
                }
            }
            helper.assertTrue(found != null, "no standing sign within 3 blocks");
            List<Component> lines = found.getText(SignTextSlot.FRONT).getMessages(false);
            helper.assertTrue(lines.get(0).getString().equals("Welcome to Ada") && lines.get(1).getString().equals("town"), "lines: " + lines);
            helper.assertTrue(held(s.bot(), Items.OAK_SIGN) == 0, "the sign was not used up");
            GameTestKit.remove(s);
        });
    }

    /** The withdraw menu option goes to the chest the bot remembers, not to whichever chest is nearest. */
    @GameTest(maxTicks = 400)
    public void withdrawOptionUsesTheRememberedChest(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStoreWhich", 400);
        BlockPos near = placeChest(helper, 3, 1, 5);
        BlockPos far = placeChest(helper, 6, 1, 3);
        helper.getBlockEntity(near, ChestBlockEntity.class).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        helper.getBlockEntity(far, ChestBlockEntity.class).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        Bot bot = s.bot();
        BlockPos abs = helper.absolutePos(far);
        bot.attachments.get(ChestMemory.class, ChestMemory::new).seen(new Pos(abs.getX(), abs.getY(), abs.getZ()), Map.of("cobblestone", 64), 0);
        var snap = Perceiver.capture(bot.player, "none", "none", p -> false, bot.knownBlocks, bot.attachments);
        var storage = ToolModules.ALL.stream().filter(m -> m.name().equals("storage")).findFirst().orElseThrow();
        var option = storage.menuOptions(snap, com.aiciv.modules.Samples.context(Map.of())).stream()
                .filter(o -> o.id().equals("withdraw_cobblestone")).findFirst().orElseThrow();
        var created = com.aiciv.actions.ActionFactory.create(option.calls().get(0));
        run(s, ((com.aiciv.actions.ActionFactory.Result.Ok) created).action());
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(bot).contains("finished: took 16 cobblestone"), "withdraw did not finish: " + GameTestKit.events(bot));
            helper.assertTrue(count(helper.getBlockEntity(far, ChestBlockEntity.class), Items.COBBLESTONE) == 48, "the remembered chest was not used");
            helper.assertTrue(count(helper.getBlockEntity(near, ChestBlockEntity.class), Items.COBBLESTONE) == 64, "the nearer chest was emptied");
            GameTestKit.remove(s);
        });
    }
}
