package com.aiciv.gametest;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.bot.AiPlayer;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.ToolModules;
import com.aiciv.stations.CartographyAction;
import com.aiciv.stations.GrindstoneAction;
import com.aiciv.stations.LoomAction;
import com.aiciv.stations.StonecutAction;
import com.aiciv.stations.TrimArmorAction;
import com.aiciv.stations.UpgradeNetheriteAction;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Task 9: smithing, stonecutter, grindstone, loom, cartography and books. Bot names start with TestStn. */
public final class StationGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("stations")), "stations module missing");
        helper.succeed();
    }

    /** A floor, the station block two blocks east of the bot, and a bot holding {@code stacks} in its first slots. */
    private static GameTestKit.Spawned arena(GameTestHelper helper, String name, Block station, ItemStack... stacks) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, station);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, name, 2.5, 3.5);
        for (int i = 0; i < stacks.length; i++) s.bot().player.getInventory().setItem(i, stacks[i]);
        return s;
    }

    private static void run(GameTestKit.Spawned s, Action action) {
        s.bot().executor.replace(List.of(action), s.bot().context);
    }

    private static ItemStack enchanted(GameTestHelper helper, ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key, int level) {
        stack.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), level);
        return stack;
    }

    private static int count(AiPlayer p, net.minecraft.world.item.Item item) {
        return InventoryOps.count(p, s -> s.is(item));
    }

    private static ItemStack item(String id, int count) {
        return new ItemStack(ItemNames.item(id), count);
    }

    @GameTest(maxTicks = 300)
    public void upgradesDiamondSwordToNetherite(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStnUpgrade", Blocks.SMITHING_TABLE,
                enchanted(helper, new ItemStack(Items.DIAMOND_SWORD), Enchantments.SHARPNESS, 1),
                item("netherite_upgrade_smithing_template", 1), new ItemStack(Items.NETHERITE_INGOT));
        AiPlayer p = s.bot().player;
        run(s, new UpgradeNetheriteAction("diamond_sword"));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: upgraded diamond_sword to netherite_sword"), "not upgraded: " + events);
            helper.assertTrue(InventoryOps.count(p, x -> x.is(Items.NETHERITE_SWORD) && x.isEnchanted()) == 1, "no enchanted netherite_sword");
            helper.assertTrue(count(p, Items.DIAMOND_SWORD) == 0, "the diamond sword is still held");
            helper.assertTrue(count(p, Items.NETHERITE_INGOT) == 0, "the ingot is still held");
            helper.assertTrue(InventoryOps.count(p, x -> x.is(ItemNames.item("netherite_upgrade_smithing_template"))) == 0, "the template is still held");
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the smithing screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void upgradeNetheriteFailsWithoutTheIngot(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStnNoIngot", Blocks.SMITHING_TABLE,
                new ItemStack(Items.DIAMOND_SWORD), item("netherite_upgrade_smithing_template", 1));
        run(s, new UpgradeNetheriteAction("diamond_sword"));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("needs netherite_ingot"), "no complaint: " + events);
            helper.assertTrue(count(s.bot().player, Items.DIAMOND_SWORD) == 1, "the sword moved");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void trimsChestplate(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStnTrim", Blocks.SMITHING_TABLE,
                new ItemStack(Items.IRON_CHESTPLATE), item("coast_armor_trim_smithing_template", 1), new ItemStack(Items.IRON_INGOT));
        AiPlayer p = s.bot().player;
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE)); // worn, so the iron one is not put on
        run(s, new TrimArmorAction("iron_chestplate", "coast_armor_trim_smithing_template", "iron_ingot"));
        helper.succeedWhen(() -> {
            helper.assertTrue(InventoryOps.count(p, x -> x.is(Items.IRON_CHESTPLATE) && x.has(DataComponents.TRIM)) == 1,
                    "the chestplate has no trim: " + GameTestKit.events(s.bot()));
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the smithing screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400)
    public void stonecutsStoneBricks(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStnCut", Blocks.STONECUTTER, new ItemStack(Items.STONE, 4));
        AiPlayer p = s.bot().player;
        run(s, new StonecutAction("stone", "stone_bricks", 4));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: cut 4 stone_bricks"), "not cut: " + GameTestKit.events(s.bot()));
            helper.assertTrue(count(p, Items.STONE_BRICKS) == 4, "stone_bricks held: " + count(p, Items.STONE_BRICKS));
            helper.assertTrue(count(p, Items.STONE) == 0, "stone left: " + count(p, Items.STONE));
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the stonecutter screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void stonecutRefusesWhatItCannotMake(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStnNoCut", Blocks.STONECUTTER, new ItemStack(Items.STONE, 4));
        AiPlayer p = s.bot().player;
        run(s, new StonecutAction("stone", "oak_slab", 1));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("the stonecutter can't make oak_slab from stone; it offers:") && events.contains("stone_bricks"), "no readable failure: " + events);
            helper.assertTrue(count(p, Items.STONE) == 4, "stone held: " + count(p, Items.STONE));
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the stonecutter screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void grindstoneDisenchants(GameTestHelper helper) {
        AtomicBoolean sawOrb = new AtomicBoolean();
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.GRINDSTONE);
        Vec3 spot = helper.absoluteVec(new Vec3(3.5, 1, 3.5));
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestStnGrind", 2.5, 3.5, () -> {
            if (!helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(spot, spot).inflate(4)).isEmpty()) sawOrb.set(true);
        });
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(0, enchanted(helper, new ItemStack(Items.IRON_PICKAXE), Enchantments.EFFICIENCY, 3));
        run(s, new GrindstoneAction("iron_pickaxe", false));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: removed enchantments from iron_pickaxe"), "not done: " + GameTestKit.events(s.bot()));
            helper.assertTrue(InventoryOps.count(p, x -> x.is(Items.IRON_PICKAXE) && !x.isEnchanted()) == 1, "the pickaxe is still enchanted or missing");
            helper.assertTrue(sawOrb.get(), "no experience orb appeared");
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the grindstone screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void repairsTwoPickaxes(GameTestHelper helper) {
        ItemStack a = new ItemStack(Items.IRON_PICKAXE), b = new ItemStack(Items.IRON_PICKAXE);
        a.setDamageValue(200);
        b.setDamageValue(100);
        GameTestKit.Spawned s = arena(helper, "TestStnRepair", Blocks.GRINDSTONE, a, b);
        AiPlayer p = s.bot().player;
        run(s, new GrindstoneAction("iron_pickaxe", true));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: repaired two iron_pickaxe into one"), "not repaired: " + GameTestKit.events(s.bot()));
            helper.assertTrue(count(p, Items.IRON_PICKAXE) == 1, "pickaxes held: " + count(p, Items.IRON_PICKAXE));
            int damage = InventoryOps.count(p, x -> x.is(Items.IRON_PICKAXE) && x.getDamageValue() < 100) ;
            helper.assertTrue(damage == 1, "the pickaxe is not less damaged than both");
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the grindstone screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void loomMakesStripedBanner(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStnLoom", Blocks.LOOM, new ItemStack(Items.BANNER.pick(DyeColor.WHITE)), new ItemStack(Items.DYE.pick(DyeColor.RED)));
        AiPlayer p = s.bot().player;
        run(s, new LoomAction("bottom stripe", "red", null));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: made a white_banner with red stripe_bottom"), "not made: " + GameTestKit.events(s.bot()));
            ItemStack banner = p.getInventory().getNonEquipmentItems().stream().filter(x -> x.is(Items.BANNER.pick(DyeColor.WHITE))).findFirst().orElse(ItemStack.EMPTY);
            BannerPatternLayers layers = banner.get(DataComponents.BANNER_PATTERNS);
            helper.assertTrue(layers != null && layers.layers().size() == 1, "banner layers: " + layers);
            BannerPatternLayers.Layer layer = layers.layers().get(0);
            helper.assertTrue(layer.color() == DyeColor.RED && layer.pattern().unwrapKey().map(k -> k.identifier().getPath()).orElse("").equals("stripe_bottom"), "wrong layer: " + layer);
            helper.assertTrue(count(p, Items.DYE.pick(DyeColor.RED)) == 0, "the dye was not used");
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the loom screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void loomRejectsUnknownPattern(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestStnBadLoom", Blocks.LOOM, new ItemStack(Items.BANNER.pick(DyeColor.WHITE)), new ItemStack(Items.DYE.pick(DyeColor.RED)));
        AiPlayer p = s.bot().player;
        run(s, new LoomAction("dragon", "red", null));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("has no pattern dragon") && events.contains("it offers:"), "no readable failure: " + events);
            helper.assertTrue(count(p, Items.BANNER.pick(DyeColor.WHITE)) == 1, "the banner is gone");
            helper.assertTrue(count(p, Items.DYE.pick(DyeColor.RED)) == 1, "the dye is gone");
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the loom screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void cartographyZoomsMap(GameTestHelper helper) {
        ItemStack map = MapItem.create(helper.getLevel(), helper.absolutePos(new net.minecraft.core.BlockPos(3, 1, 3)).getX(), helper.absolutePos(new net.minecraft.core.BlockPos(3, 1, 3)).getZ(), (byte) 0, false, false);
        GameTestKit.Spawned s = arena(helper, "TestStnMap", Blocks.CARTOGRAPHY_TABLE, map, new ItemStack(Items.PAPER));
        AiPlayer p = s.bot().player;
        run(s, new CartographyAction("zoom"));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("out to scale 1"), "not zoomed: " + GameTestKit.events(s.bot()));
            ItemStack held = p.getInventory().getNonEquipmentItems().stream().filter(x -> x.is(Items.FILLED_MAP)).findFirst().orElse(ItemStack.EMPTY);
            MapItemSavedData data = MapItem.getSavedData(held, helper.getLevel());
            helper.assertTrue(data != null && data.scale == 1, "map scale " + (data == null ? "unknown" : data.scale));
            helper.assertTrue(count(p, Items.PAPER) == 0, "the paper was not used");
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the cartography screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void writesAndSignsBook(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestStnBook", 2.5, 3.5);
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(20, new ItemStack(Items.WRITABLE_BOOK)); // not in the hotbar: the action moves it
        JsonObject args = new JsonObject();
        args.addProperty("text", "Day 1: found iron.");
        args.addProperty("title", "Diary");
        ActionFactory.Result r = ActionFactory.create(ToolCall.of("write_book", args));
        helper.assertTrue(r instanceof ActionFactory.Result.Ok, "write_book rejected: " + r);
        run(s, ((ActionFactory.Result.Ok) r).action());
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: signed the book \"Diary\""), "not signed: " + GameTestKit.events(s.bot()));
            ItemStack book = p.getInventory().getNonEquipmentItems().stream().filter(x -> x.is(Items.WRITTEN_BOOK)).findFirst().orElse(ItemStack.EMPTY);
            WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
            helper.assertTrue(content != null, "no written_book held");
            helper.assertTrue(content.title().raw().equals("Diary"), "title " + content.title().raw());
            helper.assertTrue(content.author().equals(p.getGameProfile().name()), "author " + content.author());
            helper.assertTrue(content.getPages(false).size() == 1 && content.getPages(false).get(0).getString().equals("Day 1: found iron."), "pages " + content.getPages(false));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void writesUnsignedBookInPages(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestStnPages", 2.5, 3.5);
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(0, new ItemStack(Items.WRITABLE_BOOK));
        JsonObject args = new JsonObject();
        args.addProperty("text", "word ".repeat(300).trim());
        ActionFactory.Result r = ActionFactory.create(ToolCall.of("write_book", args));
        helper.assertTrue(r instanceof ActionFactory.Result.Ok, "write_book rejected: " + r);
        run(s, ((ActionFactory.Result.Ok) r).action());
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: wrote 2 pages"), "not written: " + GameTestKit.events(s.bot()));
            ItemStack book = p.getInventory().getNonEquipmentItems().stream().filter(x -> x.is(Items.WRITABLE_BOOK)).findFirst().orElse(ItemStack.EMPTY);
            WritableBookContent content = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
            helper.assertTrue(content != null && content.getPages(false).count() == 2, "pages " + content);
            GameTestKit.remove(s);
        });
    }
}
