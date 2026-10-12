# AiCiv Milestone 1 ("Hello, AI") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A server-side Fabric 26.3 mod where `/ai spawn Ada gemini-flash "a cheerful explorer"` brings an LLM-driven fake player into the world that chats, answers when mentioned, and walks to players.

**Architecture:** A pure-Java core (config, LLM client and rate limiting, brain scheduling, A* search, action logic, snapshot formatting) is unit tested with JUnit and never touches Minecraft. Thin Minecraft glue (fake player, level adapters, perceiver, bot manager, commands) is verified with Fabric GameTests driven by a scripted LLM provider, then one live run against Gemini.

**Tech Stack:** Minecraft 26.3, Fabric loader 0.19.5, fabric-api 0.161.0+26.3, net.fabricmc.fabric-loom 1.18.2, Java 25, Gradle 9.7.0 (wrapper copied from `SummitSpawn/`), Gson (ships with Minecraft), `java.net.http.HttpClient`, JUnit 5.13.4, the JDK's `com.sun.net.httpserver` for mock providers.

**Spec:** `docs/superpowers/specs/2026-10-11-aiciv-design.md` (milestone 1 section). Read it before starting.

## Global Constraints

- Minecraft **26.3**, Fabric loader **0.19.5**, fabric-api **0.161.0+26.3**, loom **1.18.2**, Java **25**. No `mappings` line. Mojang names (`ServerPlayer`, `Component`, `Identifier`).
- Depend on fabric-loader and fabric-api with plain `implementation`, exactly like `SummitSpawn/build.gradle`. `modImplementation` is not used.
- Server-side only. Never import anything from `net.minecraft.client`.
- All work happens in `AiCiv/` inside the worktree `E:\Projects cursor\Mcmods\.worktrees\aiciv` on branch `aiciv`. Run git from the worktree, never from the main checkout.
- API keys never go into the repo or the log. They live in `<game dir>/config/aiciv/keys.json` (in dev: `AiCiv/run/config/aiciv/keys.json`, gitignored by the root `run/` rule) or in `AICIV_KEY_<NAME>` environment variables.
- Rate limits and model ids are config values. Code only writes illustrative defaults into a fresh `aiciv.json`.
- Bots never run commands or get op. `say` text is at most 256 characters with control characters removed.
- Bot names match `[A-Za-z0-9_]{3,16}`.
- Navigation never enters lava, fire, or a drop deeper than 3 blocks. Search cap 20,000 nodes. Shared search budget 2 ms per server tick. 3 seconds without progress triggers a replan; a 4th replan fails with `stuck at x y z`. Trips longer than 128 blocks go in legs.
- Timeouts: `goTo` and `goToPlayer` 2 minutes; `follow` its seconds + 5 (seconds clamped to 1..300).
- Snapshot at most 6000 characters; blocks within 24 and exposed; entities within 32.
- Chat is heard within `hearingRange` (default 64); a line naming the bot reaches it from anywhere; at most one mention wake per `mentionCooldownSeconds` (default 15).
- **Deliberate changes from the spec**, record them in the spec when Task 14 finishes:
  1. `max_tokens` defaults to **1000** (spec: 400) and each model gets `maxTokens` and `extra` config fields. Gemini thinking models count reasoning tokens against `max_tokens` and return empty replies at 400.
  2. A **hurt wake cooldown of 10 s**. The spec is silent; without it, standing in fire would trigger a new LLM call every time a reply lands.
  3. A reply made only of `say` calls is spoken immediately **without cancelling** the current action, so talking does not stop a walk.
  4. `/ai spawn <name>` with no model reuses the bot's roster model or `defaultModel`. Death in milestone 1 logs the bot out with health restored; real respawn is milestone 2.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

Five conditions the spec implies but would be easy to get wrong. Each has a pinning test in the task that owns the code.

1. **Damage over time** (lava, fire, drowning): one HURT wake per 10 s, never one LLM call per reply. Test `BotBrainTest.hurtWakesAreRateLimited` (Task 7).
2. **A second mention inside the cooldown** is answered when the cooldown ends, not dropped until the 60 s idle timer. Test `BotBrainTest.mentionDuringCooldownWakesWhenItEnds` (Task 7).
3. **A bot dies in milestone 1**: no further LLM calls, it leaves the world with health restored, and `/ai spawn <name>` brings it back. GameTest `botDeathLogsOutWithoutMoreCalls` (Task 13).
4. **Sloppy model output**: coordinates as strings or floats (`"x": "12"`, `12.7`), names like `go_to` or `GoTo`. Test `ActionFactoryTest` (Task 10).
5. **Missing or rejected key** (Gemini answers HTTP 400 "API key not valid" for a bad key): provider disabled with a readable status, bot uses its fallback, nothing crashes. Tests `LlmServiceTest.missingKeyDisablesProviderAndFallsBack` (Task 6) and `HttpChatBackendTest.badGeminiKeyIsAnAuthFailure` (Task 5).

---

## File Structure

```
AiCiv/
  build.gradle, settings.gradle, gradle.properties, gradlew, gradlew.bat, gradle/wrapper/*
  README.md
  src/main/resources/fabric.mod.json
  src/main/java/com/aiciv/
    AiCiv.java                     mod entrypoint: lifecycle, events, reload
    bot/
      FakeConnection.java          socketless Connection; drops all packets
      FakeGamePacketListener.java  game listener; turns disconnects into a callback
      AiPlayer.java                ServerPlayer that ticks its own physics
      Roster.java                  roster.json persistence (pure)
      Bot.java                     one online bot: player + brain + executor + navigator
      LiveContext.java             BotContext backed by the real world
      BotManager.java              spawn/remove/restore, ticking, chat/damage/death routing
      BrainScheduler.java          wake -> queue -> LLM -> actions
      BotException.java            user-facing command errors
      AiCommands.java              /ai command tree
    config/
      AiCivConfig.java             aiciv.json (pure)
      KeyStore.java                keys.json + env override (pure)
    llm/
      ToolCall.java, LlmReply.java, ReplyFormatException.java, ReplyParser.java
      BackendResult.java, ChatBackend.java, HttpChatBackend.java, ScriptedBackend.java
      RateLimiter.java, ProviderHealth.java, ChatRequest.java, LlmService.java
    brain/
      WakeReason.java, EventLog.java, Hearing.java, BotBrain.java, ThinkQueue.java
      PromptBuilder.java, DecisionLog.java
    perception/
      Compass.java, GameTime.java, Sighting.java, SnapshotData.java, SnapshotFormatter.java (pure)
      Perceiver.java               world -> SnapshotData
    nav/
      Pos.java, BlockView.java, Goal.java, NearGoal.java, ColumnGoal.java
      PathSearch.java, NavBudget.java, Navigator.java, NavStatus.java (pure)
      LevelBlockView.java, PathFollower.java, LevelNavigator.java (Minecraft)
    actions/
      Action.java, ActionStatus.java, BotContext.java, ActionExecutor.java
      ActionFactory.java, ToolCatalog.java
      SayAction.java, GoToAction.java, GoToPlayerAction.java, FollowAction.java, StopAction.java
    gametest/
      AiCivGameTests.java          all in-game tests (entrypoint "fabric-gametest")
  src/test/java/com/aiciv/
    testutil/MutableClock.java, testutil/GridView.java, testutil/FakeNavigator.java, testutil/FakeContext.java
    config/*Test, llm/*Test, brain/*Test, perception/*Test, nav/*Test, actions/*Test, bot/RosterTest
```

Run every Gradle command from `E:\Projects cursor\Mcmods\.worktrees\aiciv\AiCiv` (Bash: `cd "/e/Projects cursor/Mcmods/.worktrees/aiciv/AiCiv"`).

---

### Task 1: Project scaffold and fake-player spike

This task proves the riskiest assumption in the spec: a server-side fake player can join, run physics, and leave on 26.3. Nothing else builds on an unproven player.

**Files:**
- Create: `AiCiv/settings.gradle`, `AiCiv/gradle.properties`, `AiCiv/build.gradle`
- Copy: `AiCiv/gradlew`, `AiCiv/gradlew.bat`, `AiCiv/gradle/wrapper/gradle-wrapper.jar`, `AiCiv/gradle/wrapper/gradle-wrapper.properties` from `E:\Projects cursor\Mcmods\SummitSpawn\`
- Create: `AiCiv/src/main/resources/fabric.mod.json`
- Create: `AiCiv/src/main/java/com/aiciv/AiCiv.java`
- Create: `AiCiv/src/main/java/com/aiciv/bot/FakeConnection.java`
- Create: `AiCiv/src/main/java/com/aiciv/bot/FakeGamePacketListener.java`
- Create: `AiCiv/src/main/java/com/aiciv/bot/AiPlayer.java`
- Test: `AiCiv/src/main/java/com/aiciv/gametest/AiCivGameTests.java`

**Interfaces:**
- Produces: `AiPlayer.join(MinecraftServer server, String name, Runnable onDisconnect) -> AiPlayer`, `AiPlayer.moveTo(ServerLevel, Vec3, float yaw)`, `AiPlayer.stopMoving()`. `AiCiv.LOG` (slf4j `Logger`), `AiCiv.MOD_ID = "aiciv"`.

- [ ] **Step 1: Copy the Gradle wrapper and write the build files**

```bash
cd "/e/Projects cursor/Mcmods/.worktrees/aiciv"
mkdir -p AiCiv/gradle/wrapper
cp "/e/Projects cursor/Mcmods/SummitSpawn/gradlew" "/e/Projects cursor/Mcmods/SummitSpawn/gradlew.bat" AiCiv/
cp "/e/Projects cursor/Mcmods/SummitSpawn/gradle/wrapper/"* AiCiv/gradle/wrapper/
```

`AiCiv/settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven { name "Fabric"; url "https://maven.fabricmc.net/" }
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = 'AiCiv'
```

`AiCiv/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3G
loader_version=0.19.5
minecraft_version=26.3
fabric_api_version=0.161.0+26.3
mod_version=0.1.0
maven_group=com.aiciv
archives_base_name=aiciv
```

`AiCiv/build.gradle`:

```groovy
plugins {
    id 'net.fabricmc.fabric-loom' version '1.18.2'
}

version = project.mod_version
group = project.maven_group
base { archivesName = project.archives_base_name }

repositories {
    maven { url "https://maven.fabricmc.net/" }
    mavenCentral()
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"

    testImplementation platform("org.junit:junit-bom:5.13.4")
    testImplementation "org.junit.jupiter:junit-jupiter"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}

loom {
    runs {
        gametest {
            server()
            name "Game Test"
            vmArg "-Dfabric-api.gametest"
            vmArg "-Dfabric-api.gametest.report-file=${project.layout.buildDirectory.get().asFile}/gametest/junit.xml"
            runDir "build/gametest"
        }
    }
}

processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") { expand "version": project.version }
}

tasks.withType(JavaCompile).configureEach { it.options.release = 25 }
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

test { useJUnitPlatform() }

tasks.matching { it.name == 'runServer' }.configureEach { standardInput = System.in }
```

`AiCiv/src/main/resources/fabric.mod.json`:

```json
{
  "schemaVersion": 1,
  "id": "aiciv",
  "version": "${version}",
  "name": "AiCiv",
  "description": "LLM-driven AI players that found a civilization in your world.",
  "authors": ["legomaster188"],
  "license": "MIT",
  "environment": "*",
  "entrypoints": {
    "main": ["com.aiciv.AiCiv"],
    "fabric-gametest": ["com.aiciv.gametest.AiCivGameTests"]
  },
  "depends": {
    "fabricloader": ">=0.19.5",
    "fabric-api": "*",
    "minecraft": "~26.3",
    "java": ">=25"
  }
}
```

`AiCiv/src/main/java/com/aiciv/AiCiv.java` (Task 14 replaces the body):

```java
package com.aiciv;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiCiv implements ModInitializer {
    public static final String MOD_ID = "aiciv";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("AiCiv initialized");
    }
}
```

- [ ] **Step 2: Write the failing game test**

`AiCiv/src/main/java/com/aiciv/gametest/AiCivGameTests.java`:

```java
package com.aiciv.gametest;

import com.aiciv.bot.AiPlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class AiCivGameTests {
    static void stoneFloor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    @GameTest(maxTicks = 200)
    public void fakePlayerJoinsWalksAndLeaves(GameTestHelper helper) {
        stoneFloor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestWalker", () -> {});
        Vec3 start = helper.absoluteVec(new Vec3(0.5, 1, 3.5));
        // Yaw -90 faces +X (east).
        bot.moveTo(helper.getLevel(), start, -90.0F);
        helper.onEachTick(() -> {
            bot.setYRot(-90.0F);
            bot.zza = 1.0F;
        });
        helper.runAfterDelay(25, () -> {
            double moved = bot.position().x - start.x;
            server.getPlayerList().remove(bot);
            helper.assertTrue(moved > 2.0, "bot moved only " + moved + " blocks");
            helper.assertTrue(server.getPlayerList().getPlayerByName("TestWalker") == null, "bot is still in the player list");
            helper.succeed();
        });
    }
}
```

- [ ] **Step 3: Run it and watch it fail to compile**

Run: `./gradlew runGametest --no-daemon`
Expected: FAIL, `cannot find symbol: class AiPlayer`.

- [ ] **Step 4: Write the fake player**

`AiCiv/src/main/java/com/aiciv/bot/FakeConnection.java`:

```java
package com.aiciv.bot;

import io.netty.channel.ChannelFutureListener;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** A connection with no socket behind it. Every outbound packet is dropped. */
public final class FakeConnection extends Connection {
    private static final SocketAddress ADDRESS = InetSocketAddress.createUnresolved("aiciv", 0);

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override public void send(Packet<?> packet) {}
    @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
    @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
    @Override public boolean isConnected() { return true; }
    @Override public SocketAddress getRemoteAddress() { return ADDRESS; }
    @Override public String getLoggableAddress(boolean logIps) { return "aiciv"; }
    @Override public void setReadOnly() {}
    @Override public void handleDisconnection() {}
    @Override public void disconnect(Component reason) {}
    @Override public void disconnect(DisconnectionDetails details) {}
    @Override public void setListenerForServerboundHandshake(PacketListener listener) {}
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {}
    @Override public void setupOutboundProtocol(ProtocolInfo<?> info) {}
    @Override public void tick() {}
}
```

`AiCiv/src/main/java/com/aiciv/bot/FakeGamePacketListener.java`:

```java
package com.aiciv.bot;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Game listener for an {@link AiPlayer}. The server never ticks it (the fake connection is not
 * in the server's connection list), so there is no keep-alive timeout. Disconnect requests
 * (kick, duplicate login, shutdown) go to a callback instead of a socket.
 */
public final class FakeGamePacketListener extends ServerGamePacketListenerImpl {
    private final Runnable onDisconnect;

    public FakeGamePacketListener(MinecraftServer server, FakeConnection connection, AiPlayer player,
                                  CommonListenerCookie cookie, Runnable onDisconnect) {
        super(server, connection, player, cookie);
        this.onDisconnect = onDisconnect;
    }

    @Override public void send(Packet<?> packet) {}
    @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}

    @Override
    public void disconnect(DisconnectionDetails details) {
        onDisconnect.run();
    }
}
```

`AiCiv/src/main/java/com/aiciv/bot/AiPlayer.java`:

```java
package com.aiciv.bot;

import com.mojang.authlib.GameProfile;
import java.util.Set;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.phys.Vec3;

/** A server-side player with no client. It runs its own physics every tick. */
public final class AiPlayer extends ServerPlayer {
    private AiPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    /**
     * Logs a new AI player in through the normal player list, so it gets a tab-list entry,
     * a join message and vanilla playerdata saving. Call on the server thread.
     *
     * @param onDisconnect runs when the server asks this player to disconnect
     */
    public static AiPlayer join(MinecraftServer server, String name, Runnable onDisconnect) {
        GameProfile profile = UUIDUtil.createOfflineProfile(name);
        AiPlayer player = new AiPlayer(server, server.overworld(), profile);
        FakeConnection connection = new FakeConnection();
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, player.clientInformation(), false);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // The listener's constructor assigns player.connection, replacing the vanilla listener.
        new FakeGamePacketListener(server, connection, player, cookie, onDisconnect);
        return player;
    }

    public void moveTo(ServerLevel level, Vec3 pos, float yaw) {
        teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, 0.0F, true);
    }

    public void stopMoving() {
        xxa = 0.0F;
        zza = 0.0F;
        setJumping(false);
    }

    @Override
    public void tick() {
        if (level().getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            level().getChunkSource().move(this);
        }
        super.tick();
        // A real player's physics run from its network listener's tick. Ours never ticks.
        doTick();
    }
}
```

- [ ] **Step 5: Run the game test and the build**

Run: `./gradlew build runGametest --no-daemon`
Expected: BUILD SUCCESSFUL, the log contains `fakePlayerJoinsWalksAndLeaves` passing, and `build/gametest/junit.xml` shows `failures="0"`.

If `placeNewPlayer` throws a `NullPointerException` from inside `Connection` (a code path touching the null netty channel), give the connection a channel the way Carpet does:

1. Create `AiCiv/src/main/java/com/aiciv/mixin/ConnectionAccessor.java`:

```java
package com.aiciv.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    void aiciv$setChannel(Channel channel);
}
```

2. Create `AiCiv/src/main/resources/aiciv.mixins.json`:

```json
{
  "required": true,
  "package": "com.aiciv.mixin",
  "compatibilityLevel": "JAVA_25",
  "mixins": ["ConnectionAccessor"],
  "injectors": { "defaultRequire": 1 }
}
```

3. Add `"mixins": ["aiciv.mixins.json"],` to `fabric.mod.json`, and in the `FakeConnection` constructor add `((com.aiciv.mixin.ConnectionAccessor) this).aiciv$setChannel(new io.netty.channel.embedded.EmbeddedChannel());`.

If the bot spawns but `moved` stays near 0, stop and report. The spec's risk section says this outcome means a design revision (custom entity instead of fake player). Do not continue to Task 2.

- [ ] **Step 6: Commit**

```bash
cd "/e/Projects cursor/Mcmods/.worktrees/aiciv"
git add AiCiv
git commit -m "Spawn a fake player that walks on its own in 26.3

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Config and key store

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/config/AiCivConfig.java`
- Create: `AiCiv/src/main/java/com/aiciv/config/KeyStore.java`
- Test: `AiCiv/src/test/java/com/aiciv/config/AiCivConfigTest.java`
- Test: `AiCiv/src/test/java/com/aiciv/config/KeyStoreTest.java`

**Interfaces:**
- Produces: `AiCivConfig` with public fields `providers: Map<String, ProviderConfig>`, `models: Map<String, ModelConfig>`, `defaultModel`, `idleWakeSeconds`, `mentionCooldownSeconds`, `hearingRange`, `maxNotes`, `eventWindow`, `maxActionsPerDecision`; `ProviderConfig{type, baseUrl, key, rpm, rpd, resetZone, maxInFlight, script}`; `ModelConfig{provider, model, fallback, maxTokens, extra: JsonObject}`; `AiCivConfig.GSON`; `static AiCivConfig defaults()`; `static AiCivConfig loadOrCreate(Path) throws IOException`; `List<String> validate()`.
- Produces: `KeyStore.load(Path file, Function<String,String> env) throws IOException`; `Optional<String> get(String keyName)`; `static String envName(String keyName)`.

- [ ] **Step 1: Write the failing tests**

`AiCiv/src/test/java/com/aiciv/config/AiCivConfigTest.java`:

```java
package com.aiciv.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiCivConfigTest {
    @TempDir Path dir;

    @Test
    void writesDefaultsWhenMissingAndTheyAreValid() throws Exception {
        Path file = dir.resolve("aiciv").resolve("aiciv.json");
        AiCivConfig c = AiCivConfig.loadOrCreate(file);
        assertTrue(Files.exists(file));
        assertEquals("gemini-flash", c.defaultModel);
        assertEquals("gemini", c.models.get("gemini-flash").provider);
        assertEquals("groq-llama", c.models.get("gemini-flash").fallback);
        assertNull(c.providers.get("ollama").key);
        assertEquals(java.util.List.of(), c.validate());
        assertEquals(c.defaultModel, AiCivConfig.loadOrCreate(file).defaultModel);
    }

    @Test
    void missingFieldsGetDefaults() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"p": {"baseUrl": "http://x"}},
             "models": {"m": {"provider": "p", "model": "x"}},
             "defaultModel": "m", "idleWakeSeconds": 30}""");
        AiCivConfig c = AiCivConfig.loadOrCreate(file);
        assertEquals(30, c.idleWakeSeconds);
        assertEquals(15, c.mentionCooldownSeconds);
        assertEquals(64, c.hearingRange);
        assertEquals("openai", c.providers.get("p").type);
        assertEquals(2, c.providers.get("p").maxInFlight);
        assertEquals(1000, c.models.get("m").maxTokens);
        assertNotNull(c.models.get("m").extra);
        assertEquals(java.util.List.of(), c.validate());
    }

    @Test
    void validateReportsBrokenReferences() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"p": {"baseUrl": "http://x", "resetZone": "Not/AZone"}},
             "models": {"m": {"provider": "nope", "model": "x", "fallback": "ghost"}},
             "defaultModel": "missing"}""");
        String problems = String.join("\n", AiCivConfig.loadOrCreate(file).validate());
        assertTrue(problems.contains("unknown provider 'nope'"), problems);
        assertTrue(problems.contains("unknown model 'ghost'"), problems);
        assertTrue(problems.contains("invalid resetZone"), problems);
        assertTrue(problems.contains("defaultModel 'missing'"), problems);
    }
}
```

`AiCiv/src/test/java/com/aiciv/config/KeyStoreTest.java`:

```java
package com.aiciv.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyStoreTest {
    @TempDir Path dir;

    @Test
    void createsAnEmptyFile() throws Exception {
        Path file = dir.resolve("aiciv").resolve("keys.json");
        KeyStore keys = KeyStore.load(file, name -> null);
        assertTrue(Files.exists(file));
        assertEquals(Optional.empty(), keys.get("gemini"));
    }

    @Test
    void environmentOverridesFileAndBlankIsIgnored() throws Exception {
        Path file = dir.resolve("keys.json");
        Files.writeString(file, "{\"gemini\": \"from-file\", \"groq\": \"  \", \"open-code\": \"oc\"}");
        Map<String, String> env = Map.of("AICIV_KEY_GEMINI", "from-env");
        KeyStore keys = KeyStore.load(file, env::get);
        assertEquals(Optional.of("from-env"), keys.get("gemini"));
        assertEquals(Optional.empty(), keys.get("groq"));
        assertEquals(Optional.of("oc"), keys.get("open-code"));
    }

    @Test
    void envNameIsUpperCaseWithUnderscores() {
        assertEquals("AICIV_KEY_OPEN_CODE", KeyStore.envName("open-code"));
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.config.*"`
Expected: FAIL, `cannot find symbol: class AiCivConfig`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/config/AiCivConfig.java`:

```java
package com.aiciv.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** aiciv.json. Public fields so Gson can fill them; field initializers are the defaults. */
public final class AiCivConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    public Map<String, ModelConfig> models = new LinkedHashMap<>();
    public String defaultModel = "gemini-flash";
    public int idleWakeSeconds = 60;
    public int mentionCooldownSeconds = 15;
    public int hearingRange = 64;
    public int maxNotes = 20;
    public int eventWindow = 10;
    public int maxActionsPerDecision = 3;

    public static final class ProviderConfig {
        /** "openai" for any OpenAI-compatible HTTP API, "scripted" for canned replies. */
        public String type = "openai";
        public String baseUrl = "";
        /** Name of the key in keys.json, or null when the provider needs none. */
        public String key;
        /** Requests per minute, 0 for unlimited. */
        public int rpm;
        /** Requests per day, 0 for unlimited. */
        public int rpd;
        public String resetZone = "UTC";
        public int maxInFlight = 2;
        /** Scripted providers only: a JSON file next to aiciv.json. */
        public String script;

        public ProviderConfig() {}

        ProviderConfig(String baseUrl, String key, int rpm, int rpd, String resetZone, int maxInFlight) {
            this.baseUrl = baseUrl;
            this.key = key;
            this.rpm = rpm;
            this.rpd = rpd;
            this.resetZone = resetZone;
            this.maxInFlight = maxInFlight;
        }
    }

    public static final class ModelConfig {
        public String provider;
        public String model;
        public String fallback;
        public int maxTokens = 1000;
        /** Extra request fields merged into the body, for example {"reasoning_effort": "none"}. */
        public JsonObject extra = new JsonObject();

        public ModelConfig() {}

        ModelConfig(String provider, String model, String fallback) {
            this.provider = provider;
            this.model = model;
            this.fallback = fallback;
        }
    }

    /** Illustrative limits; the user replaces them with their real tier limits. */
    public static AiCivConfig defaults() {
        AiCivConfig c = new AiCivConfig();
        c.providers.put("gemini", new ProviderConfig("https://generativelanguage.googleapis.com/v1beta/openai", "gemini", 10, 250, "America/Los_Angeles", 2));
        c.providers.put("groq", new ProviderConfig("https://api.groq.com/openai/v1", "groq", 30, 1000, "UTC", 2));
        c.providers.put("opencode", new ProviderConfig("https://opencode.ai/zen/v1", "opencode", 10, 500, "UTC", 2));
        c.providers.put("ollama", new ProviderConfig("http://localhost:11434/v1", null, 0, 0, "UTC", 1));
        c.models.put("gemini-flash", new ModelConfig("gemini", "gemini-2.5-flash", "groq-llama"));
        c.models.put("groq-llama", new ModelConfig("groq", "llama-3.3-70b-versatile", null));
        c.models.put("opencode-free", new ModelConfig("opencode", "big-pickle", null));
        c.models.put("ollama-local", new ModelConfig("ollama", "llama3.2", null));
        return c;
    }

    /** Reads the file, or writes and returns the defaults when it does not exist. */
    public static AiCivConfig loadOrCreate(Path file) throws IOException {
        if (Files.notExists(file)) {
            AiCivConfig c = defaults();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(c));
            return c;
        }
        AiCivConfig c = GSON.fromJson(Files.readString(file), AiCivConfig.class);
        if (c == null) throw new IOException(file + " is empty");
        if (c.providers == null) c.providers = new LinkedHashMap<>();
        if (c.models == null) c.models = new LinkedHashMap<>();
        for (ModelConfig m : c.models.values()) {
            if (m.extra == null) m.extra = new JsonObject();
        }
        return c;
    }

    /** Human-readable problems; empty when the config is usable. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, ModelConfig> e : models.entrySet()) {
            ModelConfig m = e.getValue();
            if (m.provider == null || !providers.containsKey(m.provider)) {
                problems.add("model '" + e.getKey() + "' uses unknown provider '" + m.provider + "'");
            }
            if (m.model == null || m.model.isBlank()) {
                problems.add("model '" + e.getKey() + "' has no model id");
            }
            if (m.fallback != null && !models.containsKey(m.fallback)) {
                problems.add("model '" + e.getKey() + "' falls back to unknown model '" + m.fallback + "'");
            }
        }
        for (Map.Entry<String, ProviderConfig> e : providers.entrySet()) {
            ProviderConfig p = e.getValue();
            if ("scripted".equals(p.type)) {
                if (p.script == null || p.script.isBlank()) problems.add("scripted provider '" + e.getKey() + "' has no script file");
            } else if (!"openai".equals(p.type)) {
                problems.add("provider '" + e.getKey() + "' has unknown type '" + p.type + "'");
            } else if (p.baseUrl == null || p.baseUrl.isBlank()) {
                problems.add("provider '" + e.getKey() + "' has no baseUrl");
            }
            try {
                ZoneId.of(p.resetZone);
            } catch (RuntimeException ex) {
                problems.add("provider '" + e.getKey() + "' has invalid resetZone '" + p.resetZone + "'");
            }
        }
        if (!models.containsKey(defaultModel)) {
            problems.add("defaultModel '" + defaultModel + "' is not a configured model");
        }
        return problems;
    }
}
```

`AiCiv/src/main/java/com/aiciv/config/KeyStore.java`:

```java
package com.aiciv.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** API keys from keys.json, overridable by AICIV_KEY_<NAME> environment variables. Never logs values. */
public final class KeyStore {
    private final Map<String, String> fileKeys;
    private final Function<String, String> env;

    private KeyStore(Map<String, String> fileKeys, Function<String, String> env) {
        this.fileKeys = fileKeys;
        this.env = env;
    }

    /** Loads keys.json, creating an empty one when it is missing. */
    public static KeyStore load(Path file, Function<String, String> env) throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{}\n");
        }
        Map<String, String> keys = new HashMap<>();
        JsonElement root = JsonParser.parseString(Files.readString(file));
        if (root != null && root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) keys.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return new KeyStore(keys, env);
    }

    public static String envName(String keyName) {
        return "AICIV_KEY_" + keyName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    public Optional<String> get(String keyName) {
        String fromEnv = env.apply(envName(keyName));
        if (fromEnv != null && !fromEnv.isBlank()) return Optional.of(fromEnv.trim());
        String fromFile = fileKeys.get(keyName);
        if (fromFile != null && !fromFile.isBlank()) return Optional.of(fromFile.trim());
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.config.*"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/config AiCiv/src/test/java/com/aiciv/config
git commit -m "Read aiciv.json and keys.json, with environment overrides for keys

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Rate limiter and provider health

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/llm/RateLimiter.java`
- Create: `AiCiv/src/main/java/com/aiciv/llm/ProviderHealth.java`
- Create: `AiCiv/src/test/java/com/aiciv/testutil/MutableClock.java`
- Test: `AiCiv/src/test/java/com/aiciv/llm/RateLimiterTest.java`
- Test: `AiCiv/src/test/java/com/aiciv/llm/ProviderHealthTest.java`

**Interfaces:**
- Produces: `new RateLimiter(int rpm, int rpd, int maxInFlight, ZoneId resetZone, Clock clock)`; `boolean tryAcquire()`, `boolean wouldAllow()`, `void release()`, `int usedToday()`, `int remainingToday()` (-1 when unlimited).
- Produces: `new ProviderHealth(Clock clock)`; `boolean available()`, `void onSuccess()`, `void onRateLimited(Duration retryAfterOrNull)`, `void onFailure(String reason)`, `void onAuthFailure(String reason)`, `boolean isAuthDisabled()`, `String status()`; `static Duration backoff(int consecutive)`.
- Produces (test util): `new MutableClock(Instant start)`, `void advance(Duration)`.

- [ ] **Step 1: Write the test clock and the failing tests**

`AiCiv/src/test/java/com/aiciv/testutil/MutableClock.java`:

```java
package com.aiciv.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class MutableClock extends Clock {
    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(now, z); }
    @Override public Instant instant() { return now; }
}
```

`AiCiv/src/test/java/com/aiciv/llm/RateLimiterTest.java`:

```java
package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));

    @Test
    void perMinuteTokensRefill() {
        RateLimiter l = new RateLimiter(2, 0, 10, ZoneOffset.UTC, clock);
        assertTrue(l.tryAcquire());
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
        clock.advance(Duration.ofSeconds(30));
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
    }

    @Test
    void inFlightCapUntilReleased() {
        RateLimiter l = new RateLimiter(0, 0, 1, ZoneOffset.UTC, clock);
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
        l.release();
        assertTrue(l.tryAcquire());
    }

    @Test
    void dailyCapResetsAtMidnightInTheResetZone() {
        // 06:59 UTC is 23:59 the previous day in Los Angeles (PDT).
        MutableClock la = new MutableClock(Instant.parse("2026-10-11T06:59:00Z"));
        RateLimiter l = new RateLimiter(0, 2, 10, ZoneId.of("America/Los_Angeles"), la);
        assertTrue(l.tryAcquire());
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
        assertEquals(0, l.remainingToday());
        la.advance(Duration.ofMinutes(2));
        assertEquals(2, l.remainingToday());
        assertTrue(l.tryAcquire());
        assertEquals(1, l.usedToday());
    }

    @Test
    void zeroMeansUnlimited() {
        RateLimiter l = new RateLimiter(0, 0, 1000, ZoneOffset.UTC, clock);
        for (int i = 0; i < 500; i++) assertTrue(l.tryAcquire());
        assertEquals(-1, l.remainingToday());
    }

    @Test
    void wouldAllowDoesNotConsume() {
        RateLimiter l = new RateLimiter(1, 0, 10, ZoneOffset.UTC, clock);
        assertTrue(l.wouldAllow());
        assertTrue(l.wouldAllow());
        assertTrue(l.tryAcquire());
        assertFalse(l.wouldAllow());
    }
}
```

`AiCiv/src/test/java/com/aiciv/llm/ProviderHealthTest.java`:

```java
package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderHealthTest {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));
    ProviderHealth h = new ProviderHealth(clock);

    @Test
    void threeFailuresTakeItDownForFiveMinutes() {
        h.onFailure("HTTP 500");
        h.onFailure("HTTP 500");
        assertTrue(h.available());
        h.onFailure("HTTP 500");
        assertFalse(h.available());
        assertTrue(h.status().contains("HTTP 500"), h.status());
        clock.advance(Duration.ofMinutes(5));
        assertTrue(h.available());
    }

    @Test
    void successResetsTheFailureCount() {
        h.onFailure("x");
        h.onFailure("x");
        h.onSuccess();
        h.onFailure("x");
        h.onFailure("x");
        assertTrue(h.available());
    }

    @Test
    void retryAfterIsHonoured() {
        h.onRateLimited(Duration.ofSeconds(7));
        assertFalse(h.available());
        clock.advance(Duration.ofSeconds(7));
        assertTrue(h.available());
    }

    @Test
    void backoffDoublesUpTo120Seconds() {
        assertEquals(Duration.ofSeconds(5), ProviderHealth.backoff(1));
        assertEquals(Duration.ofSeconds(10), ProviderHealth.backoff(2));
        assertEquals(Duration.ofSeconds(20), ProviderHealth.backoff(3));
        assertEquals(Duration.ofSeconds(80), ProviderHealth.backoff(5));
        assertEquals(Duration.ofSeconds(120), ProviderHealth.backoff(6));
        assertEquals(Duration.ofSeconds(120), ProviderHealth.backoff(40));
    }

    @Test
    void rateLimitWithoutRetryAfterBacksOff() {
        h.onRateLimited(null);
        clock.advance(Duration.ofSeconds(5));
        assertTrue(h.available());
        h.onRateLimited(null);
        clock.advance(Duration.ofSeconds(5));
        assertFalse(h.available());
        clock.advance(Duration.ofSeconds(5));
        assertTrue(h.available());
    }

    @Test
    void authFailureDisablesForGood() {
        h.onAuthFailure("HTTP 401 (key rejected)");
        clock.advance(Duration.ofDays(1));
        assertFalse(h.available());
        assertTrue(h.status().startsWith("disabled"), h.status());
        assertTrue(h.isAuthDisabled());
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.llm.*"`
Expected: FAIL, `cannot find symbol: class RateLimiter`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/llm/RateLimiter.java`:

```java
package com.aiciv.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Per-provider request budget: requests per minute, per day, and concurrently in flight. */
public final class RateLimiter {
    private final int rpm;
    private final int rpd;
    private final int maxInFlight;
    private final ZoneId zone;
    private final Clock clock;
    private double tokens;
    private long lastRefillMillis;
    private LocalDate day;
    private int usedToday;
    private int inFlight;

    public RateLimiter(int rpm, int rpd, int maxInFlight, ZoneId zone, Clock clock) {
        this.rpm = Math.max(0, rpm);
        this.rpd = Math.max(0, rpd);
        this.maxInFlight = Math.max(1, maxInFlight);
        this.zone = zone;
        this.clock = clock;
        this.tokens = this.rpm;
        this.lastRefillMillis = clock.millis();
        this.day = today();
    }

    public synchronized boolean tryAcquire() {
        if (!wouldAllow()) return false;
        if (rpm > 0) tokens -= 1.0;
        usedToday++;
        inFlight++;
        return true;
    }

    public synchronized boolean wouldAllow() {
        refill();
        rollDay();
        if (inFlight >= maxInFlight) return false;
        if (rpd > 0 && usedToday >= rpd) return false;
        return rpm == 0 || tokens >= 1.0;
    }

    public synchronized void release() {
        if (inFlight > 0) inFlight--;
    }

    public synchronized int usedToday() {
        rollDay();
        return usedToday;
    }

    /** Requests left today, or -1 when there is no daily limit. */
    public synchronized int remainingToday() {
        rollDay();
        return rpd == 0 ? -1 : Math.max(0, rpd - usedToday);
    }

    private void refill() {
        if (rpm == 0) return;
        long now = clock.millis();
        tokens = Math.min(rpm, tokens + (now - lastRefillMillis) * rpm / 60_000.0);
        lastRefillMillis = now;
    }

    private void rollDay() {
        LocalDate today = today();
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }
}
```

`AiCiv/src/main/java/com/aiciv/llm/ProviderHealth.java`:

```java
package com.aiciv.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Tracks whether a provider should get requests right now. */
public final class ProviderHealth {
    static final Duration DOWN_FOR = Duration.ofMinutes(5);
    static final int FAILURES_TO_DOWN = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;
    private int consecutiveFailures;
    private int consecutiveRateLimits;
    private Instant unavailableUntil = Instant.EPOCH;
    private boolean authDisabled;
    private String lastProblem = "";

    public ProviderHealth(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean available() {
        return !authDisabled && !clock.instant().isBefore(unavailableUntil);
    }

    public synchronized void onSuccess() {
        consecutiveFailures = 0;
        consecutiveRateLimits = 0;
        lastProblem = "";
    }

    public synchronized void onRateLimited(Duration retryAfter) {
        consecutiveRateLimits++;
        Duration wait = retryAfter != null ? retryAfter : backoff(consecutiveRateLimits);
        unavailableUntil = clock.instant().plus(wait);
        lastProblem = "rate limited for " + wait.toSeconds() + "s";
    }

    public synchronized void onFailure(String reason) {
        consecutiveFailures++;
        lastProblem = reason;
        if (consecutiveFailures >= FAILURES_TO_DOWN) {
            unavailableUntil = clock.instant().plus(DOWN_FOR);
            consecutiveFailures = 0;
        }
    }

    public synchronized void onAuthFailure(String reason) {
        authDisabled = true;
        lastProblem = reason;
    }

    /** True once a key was missing or rejected; only a reload clears it. */
    public synchronized boolean isAuthDisabled() {
        return authDisabled;
    }

    public synchronized String status() {
        if (authDisabled) return "disabled: " + lastProblem;
        if (clock.instant().isBefore(unavailableUntil)) {
            String until = TIME.format(unavailableUntil.atZone(ZoneId.systemDefault()));
            return "paused until " + until + " (" + lastProblem + ")";
        }
        return lastProblem.isEmpty() ? "ok" : "ok (last problem: " + lastProblem + ")";
    }

    /** 5 s, 10 s, 20 s, ... capped at 120 s, for the n-th rate limit in a row. */
    static Duration backoff(int consecutive) {
        long seconds = 5L << Math.min(Math.max(consecutive, 1) - 1, 5);
        return Duration.ofSeconds(Math.min(seconds, 120));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.llm.*"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/llm AiCiv/src/test/java/com/aiciv
git commit -m "Budget provider requests per minute, per day and in flight

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Reply parsing

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/llm/ToolCall.java`
- Create: `AiCiv/src/main/java/com/aiciv/llm/LlmReply.java`
- Create: `AiCiv/src/main/java/com/aiciv/llm/ReplyFormatException.java`
- Create: `AiCiv/src/main/java/com/aiciv/llm/ReplyParser.java`
- Test: `AiCiv/src/test/java/com/aiciv/llm/ReplyParserTest.java`

**Interfaces:**
- Produces: `record ToolCall(String name, JsonObject args, String argsError)` with `static ToolCall of(String, JsonObject)`; `record LlmReply(List<ToolCall> calls, String thought, String plainText)`; `ReplyParser.parse(JsonObject response) throws ReplyFormatException -> LlmReply`.

- [ ] **Step 1: Write the failing tests**

`AiCiv/src/test/java/com/aiciv/llm/ReplyParserTest.java`:

```java
package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ReplyParserTest {
    static JsonObject response(String messageJson) {
        return JsonParser.parseString("{\"choices\": [{\"message\": " + messageJson + "}]}").getAsJsonObject();
    }

    @Test
    void readsToolCallsWithStringArguments() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": "On my way.", "tool_calls": [
              {"id": "1", "type": "function", "function": {"name": "say", "arguments": "{\\"text\\": \\"hi\\"}"}},
              {"id": "2", "type": "function", "function": {"name": "goTo", "arguments": {"x": 1, "y": 64, "z": 2}}}]}"""));
        assertEquals(2, r.calls().size());
        assertEquals("say", r.calls().get(0).name());
        assertEquals("hi", r.calls().get(0).args().get("text").getAsString());
        assertEquals(64, r.calls().get(1).args().get("y").getAsInt());
        assertEquals("On my way.", r.thought());
        assertNull(r.plainText());
    }

    @Test
    void brokenArgumentsAreFlaggedNotThrown() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": null, "tool_calls": [{"function": {"name": "goTo", "arguments": "{x: oops"}}]}"""));
        assertEquals(1, r.calls().size());
        assertNotNull(r.calls().get(0).argsError());
    }

    @Test
    void contentPartsAreJoined() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": [{"type": "text", "text": "Hello "}, {"type": "text", "text": "there"}]}"""));
        assertEquals("Hello there", r.plainText());
    }

    @Test
    void jsonFallbackInACodeFence() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": "```json\\n[{\\"action\\": \\"say\\", \\"args\\": {\\"text\\": \\"yo\\"}}, {\\"action\\": \\"stop\\"}]\\n```"}"""));
        assertEquals(2, r.calls().size());
        assertEquals("stop", r.calls().get(1).name());
        assertEquals(0, r.calls().get(1).args().size());
        assertNull(r.plainText());
    }

    @Test
    void singleObjectFallback() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": "{\\"action\\": \\"goToPlayer\\", \\"args\\": {\\"name\\": \\"Steve\\"}}"}"""));
        assertEquals("goToPlayer", r.calls().get(0).name());
    }

    @Test
    void plainTextWithBracketsStaysText() throws Exception {
        LlmReply r = ReplyParser.parse(response("{\"content\": \"I'll head [north] soon\"}"));
        assertTrue(r.calls().isEmpty());
        assertEquals("I'll head [north] soon", r.plainText());
    }

    @Test
    void emptyMessageMeansNothing() throws Exception {
        LlmReply r = ReplyParser.parse(response("{\"content\": \"  \"}"));
        assertTrue(r.calls().isEmpty());
        assertNull(r.plainText());
    }

    @Test
    void missingChoicesThrow() {
        assertThrows(ReplyFormatException.class, () -> ReplyParser.parse(new JsonObject()));
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.llm.ReplyParserTest"`
Expected: FAIL, `cannot find symbol: class ReplyParser`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/llm/ToolCall.java`:

```java
package com.aiciv.llm;

import com.google.gson.JsonObject;

/** One action the model asked for. argsError is non-null when its arguments could not be read. */
public record ToolCall(String name, JsonObject args, String argsError) {
    public static ToolCall of(String name, JsonObject args) {
        return new ToolCall(name, args, null);
    }
}
```

`AiCiv/src/main/java/com/aiciv/llm/LlmReply.java`:

```java
package com.aiciv.llm;

import java.util.List;

/**
 * A parsed model reply. thought is text that came alongside tool calls (logged, never chatted).
 * plainText is set only when the reply had no actions at all.
 */
public record LlmReply(List<ToolCall> calls, String thought, String plainText) {}
```

`AiCiv/src/main/java/com/aiciv/llm/ReplyFormatException.java`:

```java
package com.aiciv.llm;

public final class ReplyFormatException extends Exception {
    public ReplyFormatException(String message) {
        super(message);
    }
}
```

`AiCiv/src/main/java/com/aiciv/llm/ReplyParser.java`:

```java
package com.aiciv.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Reads an OpenAI-style chat completion into actions, with a JSON-in-text fallback. */
public final class ReplyParser {
    private ReplyParser() {}

    public static LlmReply parse(JsonObject response) throws ReplyFormatException {
        JsonElement choicesEl = response.get("choices");
        if (choicesEl == null || !choicesEl.isJsonArray() || choicesEl.getAsJsonArray().isEmpty()) {
            throw new ReplyFormatException("reply had no choices");
        }
        JsonElement first = choicesEl.getAsJsonArray().get(0);
        JsonElement msgEl = first.isJsonObject() ? first.getAsJsonObject().get("message") : null;
        if (msgEl == null || !msgEl.isJsonObject()) throw new ReplyFormatException("reply had no message");
        JsonObject msg = msgEl.getAsJsonObject();

        String content = contentText(msg.get("content"));
        List<ToolCall> calls = new ArrayList<>();
        JsonElement toolCalls = msg.get("tool_calls");
        if (toolCalls != null && toolCalls.isJsonArray()) {
            for (JsonElement el : toolCalls.getAsJsonArray()) {
                ToolCall c = toolCall(el);
                if (c != null) calls.add(c);
            }
        }
        if (!calls.isEmpty()) return new LlmReply(calls, content.trim(), null);

        List<ToolCall> fromText = jsonFallback(content);
        if (!fromText.isEmpty()) return new LlmReply(fromText, "", null);

        String text = content.trim();
        return new LlmReply(List.of(), "", text.isEmpty() ? null : text);
    }

    private static String contentText(JsonElement c) {
        if (c == null || c.isJsonNull()) return "";
        if (c.isJsonPrimitive()) return c.getAsString();
        if (c.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : c.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    sb.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static ToolCall toolCall(JsonElement el) {
        if (!el.isJsonObject()) return null;
        JsonElement fnEl = el.getAsJsonObject().get("function");
        if (fnEl == null || !fnEl.isJsonObject()) return null;
        JsonObject fn = fnEl.getAsJsonObject();
        JsonElement name = fn.get("name");
        if (name == null || !name.isJsonPrimitive()) return null;
        return withArgs(name.getAsString(), fn.get("arguments"));
    }

    private static ToolCall withArgs(String name, JsonElement raw) {
        if (raw == null || raw.isJsonNull()) return ToolCall.of(name, new JsonObject());
        if (raw.isJsonObject()) return ToolCall.of(name, raw.getAsJsonObject());
        if (raw.isJsonPrimitive()) {
            String s = raw.getAsString().trim();
            if (s.isEmpty()) return ToolCall.of(name, new JsonObject());
            try {
                JsonElement parsed = JsonParser.parseString(s);
                if (parsed.isJsonObject()) return ToolCall.of(name, parsed.getAsJsonObject());
            } catch (JsonParseException ignored) {
                // fall through to the error below
            }
        }
        return new ToolCall(name, new JsonObject(), "arguments for " + name + " were not a valid JSON object");
    }

    /** Accepts [{"action": "say", "args": {...}}, ...] or one such object, optionally in a code fence. */
    static List<ToolCall> jsonFallback(String content) {
        String s = stripFence(content.trim());
        JsonElement root;
        try {
            int open = s.indexOf('[');
            int close = s.lastIndexOf(']');
            if (open >= 0 && close > open) {
                root = JsonParser.parseString(s.substring(open, close + 1));
            } else {
                int o = s.indexOf('{');
                int c = s.lastIndexOf('}');
                if (o < 0 || c <= o) return List.of();
                root = JsonParser.parseString(s.substring(o, c + 1));
            }
        } catch (JsonParseException e) {
            return List.of();
        }
        List<JsonElement> items = new ArrayList<>();
        if (root.isJsonArray()) {
            JsonArray arr = root.getAsJsonArray();
            arr.forEach(items::add);
        } else {
            items.add(root);
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement item : items) {
            if (!item.isJsonObject()) return List.of();
            JsonElement action = item.getAsJsonObject().get("action");
            if (action == null || !action.isJsonPrimitive()) return List.of();
            calls.add(withArgs(action.getAsString(), item.getAsJsonObject().get("args")));
        }
        return calls;
    }

    private static String stripFence(String s) {
        if (!s.startsWith("```")) return s;
        int firstNewline = s.indexOf('\n');
        String body = firstNewline < 0 ? "" : s.substring(firstNewline + 1);
        int end = body.lastIndexOf("```");
        return end < 0 ? body : body.substring(0, end);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.llm.ReplyParserTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/llm AiCiv/src/test/java/com/aiciv/llm
git commit -m "Turn model replies into actions, including JSON written as text

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: HTTP and scripted backends

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/llm/BackendResult.java`
- Create: `AiCiv/src/main/java/com/aiciv/llm/ChatBackend.java`
- Create: `AiCiv/src/main/java/com/aiciv/llm/HttpChatBackend.java`
- Create: `AiCiv/src/main/java/com/aiciv/llm/ScriptedBackend.java`
- Test: `AiCiv/src/test/java/com/aiciv/llm/HttpChatBackendTest.java`
- Test: `AiCiv/src/test/java/com/aiciv/llm/ScriptedBackendTest.java`

**Interfaces:**
- Produces: `sealed interface BackendResult` with records `Ok(JsonObject body)`, `RateLimited(Duration retryAfter)` (null when unknown), `Failed(String reason, boolean auth)`.
- Produces: `interface ChatBackend { CompletableFuture<BackendResult> send(JsonObject requestBody); }`.
- Produces: `new HttpChatBackend(String baseUrl, String apiKeyOrNull, Duration timeout)`; posts to `{baseUrl}/chat/completions`.
- Produces: `new ScriptedBackend(List<JsonObject> assistantMessages)`, `static ScriptedBackend fromFile(Path)`, `List<JsonObject> requests()`.

- [ ] **Step 1: Write the failing tests**

`AiCiv/src/test/java/com/aiciv/llm/HttpChatBackendTest.java`:

```java
package com.aiciv.llm;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpChatBackendTest {
    HttpServer server;
    volatile int status = 200;
    volatile String body = "{\"choices\": []}";
    volatile Map<String, String> headers = Map.of();
    volatile long delayMillis;
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.getRequestBody().readAllBytes();
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { }
            }
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    BackendResult call(String key, Duration timeout) throws Exception {
        return new HttpChatBackend(base(), key, timeout).send(new JsonObject()).get(10, SECONDS);
    }

    @Test
    void okReplyIsParsedAndKeyIsSent() throws Exception {
        BackendResult r = call("secret", Duration.ofSeconds(5));
        assertInstanceOf(BackendResult.Ok.class, r);
        assertEquals("/v1/chat/completions", paths.get(0));
        assertEquals("Bearer secret", auth.get(0));
    }

    @Test
    void trailingSlashAndNoKey() throws Exception {
        new HttpChatBackend(base() + "/", null, Duration.ofSeconds(5)).send(new JsonObject()).get(10, SECONDS);
        assertEquals("/v1/chat/completions", paths.get(0));
        assertEquals("null", auth.get(0));
    }

    @Test
    void rateLimitReadsRetryAfter() throws Exception {
        status = 429;
        headers = Map.of("Retry-After", "7");
        BackendResult r = call("k", Duration.ofSeconds(5));
        assertEquals(new BackendResult.RateLimited(Duration.ofSeconds(7)), r);
    }

    @Test
    void rateLimitWithoutRetryAfter() throws Exception {
        status = 429;
        assertEquals(new BackendResult.RateLimited(null), call("k", Duration.ofSeconds(5)));
    }

    @Test
    void unauthorizedIsAnAuthFailure() throws Exception {
        status = 401;
        body = "{\"error\": \"bad key\"}";
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofSeconds(5)));
        assertTrue(f.auth());
    }

    @Test
    void badGeminiKeyIsAnAuthFailure() throws Exception {
        status = 400;
        body = "[{\"error\": {\"code\": 400, \"message\": \"API key not valid. Please pass a valid API key.\"}}]";
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofSeconds(5)));
        assertTrue(f.auth(), f.reason());
    }

    @Test
    void serverErrorIsNotAuth() throws Exception {
        status = 503;
        body = "overloaded";
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofSeconds(5)));
        assertFalse(f.auth());
        assertTrue(f.reason().contains("503"));
    }

    @Test
    void nonJsonOkIsAFailure() throws Exception {
        body = "<html>oops</html>";
        assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofSeconds(5)));
    }

    @Test
    void timeoutIsANetworkFailure() throws Exception {
        delayMillis = 2000;
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofMillis(300)));
        assertTrue(f.reason().startsWith("network"), f.reason());
    }
}
```

`AiCiv/src/test/java/com/aiciv/llm/ScriptedBackendTest.java`:

```java
package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptedBackendTest {
    @TempDir Path dir;

    @Test
    void repliesInOrderThenEmptyAndRecordsRequests() throws Exception {
        JsonObject m1 = JsonParser.parseString("{\"content\": \"one\"}").getAsJsonObject();
        ScriptedBackend b = new ScriptedBackend(List.of(m1));
        JsonObject req = new JsonObject();
        req.addProperty("model", "x");
        BackendResult.Ok first = (BackendResult.Ok) b.send(req).get();
        assertEquals("one", ReplyParser.parse(first.body()).plainText());
        BackendResult.Ok second = (BackendResult.Ok) b.send(req).get();
        assertNull(ReplyParser.parse(second.body()).plainText());
        assertEquals(2, b.requests().size());
    }

    @Test
    void loadsAFile() throws Exception {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "[{\"content\": \"hi\"}, {\"content\": \"bye\"}]");
        ScriptedBackend b = ScriptedBackend.fromFile(f);
        assertEquals("hi", ReplyParser.parse(((BackendResult.Ok) b.send(new JsonObject()).get()).body()).plainText());
    }
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.llm.*BackendTest"`
Expected: FAIL, `cannot find symbol: class HttpChatBackend`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/llm/BackendResult.java`:

```java
package com.aiciv.llm;

import com.google.gson.JsonObject;
import java.time.Duration;

public sealed interface BackendResult {
    record Ok(JsonObject body) implements BackendResult {}

    /** retryAfter is null when the provider did not say how long to wait. */
    record RateLimited(Duration retryAfter) implements BackendResult {}

    /** auth is true when the key was rejected or missing. */
    record Failed(String reason, boolean auth) implements BackendResult {}
}
```

`AiCiv/src/main/java/com/aiciv/llm/ChatBackend.java`:

```java
package com.aiciv.llm;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** Sends one chat-completions request body. Never completes exceptionally for HTTP errors. */
public interface ChatBackend {
    CompletableFuture<BackendResult> send(JsonObject requestBody);
}
```

`AiCiv/src/main/java/com/aiciv/llm/HttpChatBackend.java`:

```java
package com.aiciv.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Any OpenAI-compatible chat-completions endpoint: Gemini, Groq, OpenRouter, OpenCode Zen, Ollama... */
public final class HttpChatBackend implements ChatBackend {
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    public HttpChatBackend(String baseUrl, String apiKey, Duration timeout) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/chat/completions");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public CompletableFuture<BackendResult> send(JsonObject body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (apiKey != null) req.header("Authorization", "Bearer " + apiKey);
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> err != null
                        ? new BackendResult.Failed("network: " + describe(err), false)
                        : interpret(resp.statusCode(), resp.body(), resp.headers().firstValue("Retry-After")));
    }

    static BackendResult interpret(int status, String body, Optional<String> retryAfter) {
        if (status == 200) {
            try {
                JsonElement el = JsonParser.parseString(body);
                if (el.isJsonObject()) return new BackendResult.Ok(el.getAsJsonObject());
            } catch (JsonParseException ignored) {
                // reported below
            }
            return new BackendResult.Failed("provider sent a reply that is not JSON", false);
        }
        if (status == 429) {
            return new BackendResult.RateLimited(retryAfter.flatMap(HttpChatBackend::seconds).orElse(null));
        }
        String snippet = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
        // Gemini answers HTTP 400 "API key not valid" for a bad key.
        boolean badKey = status == 401 || status == 403 || (status == 400 && snippet.contains("API key"));
        if (badKey) return new BackendResult.Failed("HTTP " + status + " (key rejected): " + snippet, true);
        return new BackendResult.Failed("HTTP " + status + ": " + snippet, false);
    }

    private static Optional<Duration> seconds(String value) {
        try {
            long s = Long.parseLong(value.trim());
            return s >= 0 ? Optional.of(Duration.ofSeconds(s)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String describe(Throwable err) {
        Throwable t = err;
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
```

`AiCiv/src/main/java/com/aiciv/llm/ScriptedBackend.java`:

```java
package com.aiciv.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Canned replies for tests and dev runs. Each entry is an assistant message object
 * ({"content": ..., "tool_calls": [...]}). Once the script runs out it replies with an empty message.
 */
public final class ScriptedBackend implements ChatBackend {
    private final Deque<JsonObject> messages;
    private final List<JsonObject> requests = new ArrayList<>();

    public ScriptedBackend(List<JsonObject> assistantMessages) {
        this.messages = new ArrayDeque<>(assistantMessages);
    }

    public static ScriptedBackend fromFile(Path file) throws IOException {
        JsonElement root = JsonParser.parseString(Files.readString(file));
        if (!root.isJsonArray()) throw new IOException(file + " must hold a JSON array of assistant messages");
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray()) {
            if (!el.isJsonObject()) throw new IOException(file + " has a non-object entry");
            list.add(el.getAsJsonObject());
        }
        return new ScriptedBackend(list);
    }

    @Override
    public synchronized CompletableFuture<BackendResult> send(JsonObject requestBody) {
        requests.add(requestBody.deepCopy());
        JsonObject message = messages.isEmpty() ? new JsonObject() : messages.poll();
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return CompletableFuture.completedFuture(new BackendResult.Ok(response));
    }

    public synchronized List<JsonObject> requests() {
        return List.copyOf(requests);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.llm.*BackendTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/llm AiCiv/src/test/java/com/aiciv/llm
git commit -m "Talk to OpenAI-compatible providers, or replay a script

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: LLM service with routing and fallback

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/llm/ChatRequest.java`
- Create: `AiCiv/src/main/java/com/aiciv/llm/LlmService.java`
- Test: `AiCiv/src/test/java/com/aiciv/llm/LlmServiceTest.java`

**Interfaces:**
- Consumes: `AiCivConfig`, `KeyStore` (Task 2); `RateLimiter`, `ProviderHealth` (Task 3); `ReplyParser`, `LlmReply` (Task 4); `ChatBackend`, `BackendResult`, `HttpChatBackend`, `ScriptedBackend` (Task 5).
- Produces: `ChatRequest.build(String model, String system, String user, JsonArray tools, int maxTokens, JsonObject extra) -> JsonObject`.
- Produces: `new LlmService(AiCivConfig, Map<String, ChatBackend> backendsByProvider, Clock)`; `static LlmService create(AiCivConfig, KeyStore, Path configDir, Clock)`; `record Route(String modelKey, String providerKey, String modelId)`; `sealed interface Outcome { Route route(); }` with `Reply(Route, LlmReply)`, `RateLimited(Route)`, `Failed(Route, String reason)`; `Optional<Route> peek(String modelKey)`; `Optional<CompletableFuture<Outcome>> trySend(String modelKey, String system, String user, JsonArray tools)`; `boolean knowsModel(String)`; `Set<String> modelKeys()`; `boolean providerDisabled(String providerKey)`; `List<String> budgetLines()`.

- [ ] **Step 1: Write the failing tests**

`AiCiv/src/test/java/com/aiciv/llm/LlmServiceTest.java`:

```java
package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.config.AiCivConfig;
import com.aiciv.config.KeyStore;
import com.aiciv.testutil.MutableClock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmServiceTest {
    @TempDir Path dir;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));

    static final class FakeBackend implements ChatBackend {
        final Deque<BackendResult> results = new ArrayDeque<>();
        final List<JsonObject> bodies = new ArrayList<>();

        @Override
        public CompletableFuture<BackendResult> send(JsonObject body) {
            bodies.add(body);
            BackendResult r = results.isEmpty() ? ok("{\"content\": \"hi\"}") : results.poll();
            return CompletableFuture.completedFuture(r);
        }
    }

    static BackendResult ok(String messageJson) {
        return new BackendResult.Ok(JsonParser.parseString("{\"choices\": [{\"message\": " + messageJson + "}]}").getAsJsonObject());
    }

    /** Model "ma" on provider "a" falls back to "mb" on provider "b". */
    static AiCivConfig config(int rpdA) throws Exception {
        String json = """
            {"providers": {"a": {"baseUrl": "http://a", "rpd": %d}, "b": {"baseUrl": "http://b"}},
             "models": {"ma": {"provider": "a", "model": "model-a", "fallback": "mb"},
                        "mb": {"provider": "b", "model": "model-b"}},
             "defaultModel": "ma"}""".formatted(rpdA);
        return AiCivConfig.GSON.fromJson(json, AiCivConfig.class);
    }

    LlmService.Outcome send(LlmService s, String model) throws Exception {
        return s.trySend(model, "sys", "user", new JsonArray()).orElseThrow().get();
    }

    @Test
    void replyComesFromThePrimaryWithTheRightBody() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        JsonArray tools = JsonParser.parseString("[{\"type\": \"function\", \"function\": {\"name\": \"say\"}}]").getAsJsonArray();
        LlmService.Outcome o = s.trySend("ma", "sys", "user", tools).orElseThrow().get();
        LlmService.Outcome.Reply r = assertInstanceOf(LlmService.Outcome.Reply.class, o);
        assertEquals("a", r.route().providerKey());
        assertEquals("hi", r.reply().plainText());
        JsonObject body = a.bodies.get(0);
        assertEquals("model-a", body.get("model").getAsString());
        assertEquals(1000, body.get("max_tokens").getAsInt());
        assertEquals("auto", body.get("tool_choice").getAsString());
        assertEquals("sys", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void rateLimitedPrimaryHandsOverToFallback() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        a.results.add(new BackendResult.RateLimited(Duration.ofSeconds(30)));
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        assertInstanceOf(LlmService.Outcome.RateLimited.class, send(s, "ma"));
        assertEquals("b", s.peek("ma").orElseThrow().providerKey());
        LlmService.Outcome next = send(s, "ma");
        assertEquals("b", next.route().providerKey());
        clock.advance(Duration.ofSeconds(30));
        assertEquals("a", s.peek("ma").orElseThrow().providerKey());
    }

    @Test
    void authFailureDisablesAndShowsInBudget() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        a.results.add(new BackendResult.Failed("HTTP 401 (key rejected): no", true));
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        assertInstanceOf(LlmService.Outcome.Failed.class, send(s, "ma"));
        assertEquals("b", s.peek("ma").orElseThrow().providerKey());
        String budget = String.join("\n", s.budgetLines());
        assertTrue(budget.contains("a: 1 used today, no daily limit, disabled"), budget);
        assertTrue(s.providerDisabled("a"));
        assertFalse(s.providerDisabled("b"));
    }

    @Test
    void dailyCapFallsBack() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        LlmService s = new LlmService(config(1), Map.of("a", a, "b", b), clock);
        assertEquals("a", send(s, "ma").route().providerKey());
        assertEquals("b", send(s, "ma").route().providerKey());
    }

    @Test
    void noRouteMeansEmpty() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        a.results.add(new BackendResult.Failed("HTTP 401 (key rejected)", true));
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        send(s, "ma");
        assertTrue(s.trySend("unknown", "s", "u", new JsonArray()).isEmpty());
        b.results.add(new BackendResult.Failed("HTTP 401 (key rejected)", true));
        send(s, "mb");
        assertTrue(s.peek("ma").isEmpty());
        assertTrue(s.trySend("ma", "s", "u", new JsonArray()).isEmpty());
    }

    @Test
    void garbageReplyCountsAsFailure() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        a.results.add(new BackendResult.Ok(new JsonObject()));
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        LlmService.Outcome.Failed f = assertInstanceOf(LlmService.Outcome.Failed.class, send(s, "ma"));
        assertTrue(f.reason().contains("no choices"));
    }

    @Test
    void missingKeyDisablesProviderAndFallsBack() throws Exception {
        AiCivConfig c = config(0);
        c.providers.get("a").key = "gemini";
        KeyStore keys = KeyStore.load(dir.resolve("keys.json"), name -> null);
        LlmService s = LlmService.create(c, keys, dir, clock);
        assertEquals("b", s.peek("ma").orElseThrow().providerKey());
        String budget = String.join("\n", s.budgetLines());
        assertTrue(budget.contains("no key 'gemini'"), budget);
        assertTrue(Files.exists(dir.resolve("keys.json")));
    }

    @Test
    void extraFieldsAreMerged() throws Exception {
        AiCivConfig c = config(0);
        c.models.get("ma").extra.addProperty("reasoning_effort", "none");
        c.models.get("ma").maxTokens = 222;
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        LlmService s = new LlmService(c, Map.of("a", a, "b", b), clock);
        send(s, "ma");
        assertEquals("none", a.bodies.get(0).get("reasoning_effort").getAsString());
        assertEquals(222, a.bodies.get(0).get("max_tokens").getAsInt());
        assertFalse(a.bodies.get(0).has("tools"));
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.llm.LlmServiceTest"`
Expected: FAIL, `cannot find symbol: class LlmService`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/llm/ChatRequest.java`:

```java
package com.aiciv.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/** Builds an OpenAI-format chat-completions request body. */
public final class ChatRequest {
    private ChatRequest() {}

    public static JsonObject build(String model, String system, String user, JsonArray tools, int maxTokens, JsonObject extra) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools.deepCopy());
            body.addProperty("tool_choice", "auto");
        }
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", 0.8);
        if (extra != null) {
            for (Map.Entry<String, JsonElement> e : extra.entrySet()) body.add(e.getKey(), e.getValue().deepCopy());
        }
        return body;
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }
}
```

`AiCiv/src/main/java/com/aiciv/llm/LlmService.java`:

```java
package com.aiciv.llm;

import com.aiciv.config.AiCivConfig;
import com.aiciv.config.AiCivConfig.ModelConfig;
import com.aiciv.config.AiCivConfig.ProviderConfig;
import com.aiciv.config.KeyStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Picks a healthy provider with budget for a model (following fallbacks) and sends the request. */
public final class LlmService {
    public record Route(String modelKey, String providerKey, String modelId) {}

    public sealed interface Outcome {
        Route route();

        record Reply(Route route, LlmReply reply) implements Outcome {}
        record RateLimited(Route route) implements Outcome {}
        record Failed(Route route, String reason) implements Outcome {}
    }

    private final AiCivConfig config;
    private final Map<String, ChatBackend> backends;
    private final Map<String, RateLimiter> limiters = new HashMap<>();
    private final Map<String, ProviderHealth> health = new HashMap<>();

    public LlmService(AiCivConfig config, Map<String, ChatBackend> backends, Clock clock) {
        this.config = config;
        this.backends = Map.copyOf(backends);
        for (Map.Entry<String, ProviderConfig> e : config.providers.entrySet()) {
            ProviderConfig p = e.getValue();
            limiters.put(e.getKey(), new RateLimiter(p.rpm, p.rpd, p.maxInFlight, zone(p.resetZone), clock));
            health.put(e.getKey(), new ProviderHealth(clock));
        }
        for (String provider : config.providers.keySet()) {
            if (!this.backends.containsKey(provider)) health.get(provider).onAuthFailure("no backend configured");
        }
    }

    /** Builds HTTP or scripted backends from config. Providers missing a key start disabled. */
    public static LlmService create(AiCivConfig config, KeyStore keys, Path configDir, Clock clock) {
        Map<String, ChatBackend> backends = new HashMap<>();
        Map<String, String> disabled = new HashMap<>();
        for (Map.Entry<String, ProviderConfig> e : config.providers.entrySet()) {
            ProviderConfig p = e.getValue();
            if ("scripted".equals(p.type)) {
                try {
                    backends.put(e.getKey(), ScriptedBackend.fromFile(configDir.resolve(p.script)));
                } catch (IOException | RuntimeException ex) {
                    disabled.put(e.getKey(), "cannot read script " + p.script + ": " + ex.getMessage());
                }
                continue;
            }
            String key = null;
            if (p.key != null) {
                Optional<String> k = keys.get(p.key);
                if (k.isEmpty()) disabled.put(e.getKey(), "no key '" + p.key + "' in keys.json or " + KeyStore.envName(p.key));
                key = k.orElse(null);
            }
            backends.put(e.getKey(), new HttpChatBackend(p.baseUrl, key, Duration.ofSeconds(30)));
        }
        LlmService service = new LlmService(config, backends, clock);
        disabled.forEach((provider, reason) -> service.health.get(provider).onAuthFailure(reason));
        return service;
    }

    public boolean knowsModel(String modelKey) {
        return !routes(modelKey).isEmpty();
    }

    public Set<String> modelKeys() {
        return config.models.keySet();
    }

    /** The first route that would take a request right now, without using any budget. */
    public Optional<Route> peek(String modelKey) {
        for (Route r : routes(modelKey)) {
            if (health.get(r.providerKey()).available() && limiters.get(r.providerKey()).wouldAllow()) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** Sends through the first available route, or returns empty when none can take a request now. */
    public Optional<CompletableFuture<Outcome>> trySend(String modelKey, String system, String user, JsonArray tools) {
        for (Route r : routes(modelKey)) {
            ProviderHealth h = health.get(r.providerKey());
            RateLimiter limiter = limiters.get(r.providerKey());
            if (!h.available() || !limiter.tryAcquire()) continue;
            ModelConfig m = config.models.get(r.modelKey());
            JsonObject body = ChatRequest.build(r.modelId(), system, user, tools, m.maxTokens, m.extra);
            CompletableFuture<Outcome> f = backends.get(r.providerKey()).send(body)
                    .handle((result, err) -> {
                        limiter.release();
                        return settle(r, h, result, err);
                    });
            return Optional.of(f);
        }
        return Optional.empty();
    }

    /** True when the provider's key is missing or was rejected. */
    public boolean providerDisabled(String providerKey) {
        ProviderHealth h = health.get(providerKey);
        return h != null && h.isAuthDisabled();
    }

    public List<String> budgetLines() {
        List<String> lines = new ArrayList<>();
        for (String p : config.providers.keySet()) {
            RateLimiter l = limiters.get(p);
            int remaining = l.remainingToday();
            lines.add(p + ": " + l.usedToday() + " used today, "
                    + (remaining < 0 ? "no daily limit" : remaining + " left") + ", " + health.get(p).status());
        }
        return lines;
    }

    private List<Route> routes(String modelKey) {
        List<Route> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String key = modelKey;
        while (key != null && seen.add(key)) {
            ModelConfig m = config.models.get(key);
            if (m == null || !limiters.containsKey(m.provider)) break;
            out.add(new Route(key, m.provider, m.model));
            key = m.fallback;
        }
        return out;
    }

    private static Outcome settle(Route r, ProviderHealth h, BackendResult result, Throwable err) {
        if (err != null) {
            h.onFailure("internal error: " + err);
            return new Outcome.Failed(r, String.valueOf(err));
        }
        if (result instanceof BackendResult.Ok ok) {
            try {
                LlmReply reply = ReplyParser.parse(ok.body());
                h.onSuccess();
                return new Outcome.Reply(r, reply);
            } catch (ReplyFormatException e) {
                h.onFailure(e.getMessage());
                return new Outcome.Failed(r, e.getMessage());
            }
        }
        if (result instanceof BackendResult.RateLimited rl) {
            h.onRateLimited(rl.retryAfter());
            return new Outcome.RateLimited(r);
        }
        BackendResult.Failed f = (BackendResult.Failed) result;
        if (f.auth()) h.onAuthFailure(f.reason());
        else h.onFailure(f.reason());
        return new Outcome.Failed(r, f.reason());
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.llm.*"`
Expected: PASS (all llm tests, 38 total).

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/llm AiCiv/src/test/java/com/aiciv/llm
git commit -m "Route each request to a healthy provider and fall back when one fails

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Brain core (wakes, events, hearing, queue)

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/brain/WakeReason.java`
- Create: `AiCiv/src/main/java/com/aiciv/brain/EventLog.java`
- Create: `AiCiv/src/main/java/com/aiciv/brain/Hearing.java`
- Create: `AiCiv/src/main/java/com/aiciv/brain/BotBrain.java`
- Create: `AiCiv/src/main/java/com/aiciv/brain/ThinkQueue.java`
- Test: `AiCiv/src/test/java/com/aiciv/brain/BotBrainTest.java`
- Test: `AiCiv/src/test/java/com/aiciv/brain/HearingTest.java`
- Test: `AiCiv/src/test/java/com/aiciv/brain/ThinkQueueTest.java`

**Interfaces:**
- Produces: `enum WakeReason { HURT, MENTIONED, ACTION_ENDED, IDLE }` (declaration order is priority, first is most urgent).
- Produces: `new EventLog(int capacity)`; `void add(String stamp, String text)`; `List<String> lines()` ("[stamp] text", oldest first).
- Produces: `Hearing.mentions(String text, String name)`, `Hearing.hears(double distanceSq, int range, boolean mentioned)`.
- Produces: `new BotBrain(String name, String persona, String goal, String modelKey, int eventWindow, long idleMillis, long mentionCooldownMillis, long nowMillis)`; `hearChat(String stamp, String speaker, String text, boolean mentioned, long now)`, `hurt(String stamp, String description, long now)`, `actionsEnded(String stamp, String summary)`, `note(String stamp, String text)`, `WakeReason pollWake(long now, boolean busy)`, `dispatched(long now)`, `replied(String summary)`, `failed(WakeReason reason)`; getters `name()`, `persona()`, `goal()`, `modelKey()`, `paused()`, `awaitingReply()`, `lastDecision()`, `events()`; setters `setPersona`, `setGoal`, `setModelKey`, `setPaused`.
- Produces: `ThinkQueue` with `record Ticket(String bot, WakeReason reason, long since)`, `offer(String bot, WakeReason reason, long since)`, `Optional<Ticket> pollFirst(Predicate<String> dispatchable)`, `remove(String bot)`, `clear()`, `size()`, `positionOf(String bot)` (1-based, -1 if absent).

- [ ] **Step 1: Write the failing tests**

`AiCiv/src/test/java/com/aiciv/brain/BotBrainTest.java`:

```java
package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BotBrainTest {
    static final long IDLE = 60_000, MENTION = 15_000;
    long t0 = 1_000_000;

    BotBrain brain() {
        return new BotBrain("Ada", "cheerful", "", "gemini-flash", 3, IDLE, MENTION, t0);
    }

    @Test
    void thinksRightAfterJoiningThenWaitsForIdle() {
        BotBrain b = brain();
        assertEquals(WakeReason.IDLE, b.pollWake(t0, false));
        b.dispatched(t0);
        b.replied("say hi");
        assertNull(b.pollWake(t0 + 1000, false));
        assertNull(b.pollWake(t0 + IDLE - 1, false));
        assertEquals(WakeReason.IDLE, b.pollWake(t0 + IDLE, false));
    }

    @Test
    void busyBotsDoNotIdleWake() {
        BotBrain b = brain();
        assertNull(b.pollWake(t0, true));
    }

    @Test
    void awaitingReplyBlocksWakesButKeepsThem() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.actionsEnded("s", "finished: say hi");
        assertNull(b.pollWake(t0 + 10, false));
        b.replied("x");
        assertEquals(WakeReason.ACTION_ENDED, b.pollWake(t0 + 20, false));
    }

    @Test
    void mentionWakesOncePerCooldown() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.replied("x");
        b.hearChat("s", "Steve", "hi Ada", true, t0 + 100);
        assertEquals(WakeReason.MENTIONED, b.pollWake(t0 + 100, true));
        b.dispatched(t0 + 100);
        b.replied("say hi");
        b.hearChat("s", "Steve", "Ada?", true, t0 + 2000);
        assertNull(b.pollWake(t0 + 2000, true));
    }

    @Test
    void mentionDuringCooldownWakesWhenItEnds() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.replied("x");
        b.hearChat("s", "Steve", "Ada come here", true, t0 + 100);
        assertEquals(WakeReason.MENTIONED, b.pollWake(t0 + 100, true));
        b.dispatched(t0 + 100);
        b.replied("say ok");
        b.hearChat("s", "Steve", "Ada, faster", true, t0 + 3000);
        assertNull(b.pollWake(t0 + 3000, true));
        assertNull(b.pollWake(t0 + 100 + MENTION - 1, true));
        assertEquals(WakeReason.MENTIONED, b.pollWake(t0 + 100 + MENTION, true));
        assertEquals(2, b.events().lines().size());
    }

    @Test
    void chatWithoutMentionOnlyLogs() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.replied("x");
        b.hearChat("day 1, 06:00", "Steve", "nice weather", false, t0 + 100);
        assertNull(b.pollWake(t0 + 100, true));
        assertEquals("[day 1, 06:00] Steve said: \"nice weather\"", b.events().lines().get(0));
    }

    @Test
    void hurtWakesAreRateLimited() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.replied("x");
        b.hurt("s", "burning", t0 + 100);
        assertEquals(WakeReason.HURT, b.pollWake(t0 + 100, true));
        b.dispatched(t0 + 100);
        b.replied("goTo away");
        b.hurt("s", "burning", t0 + 1100);
        b.hurt("s", "burning", t0 + 2100);
        assertNull(b.pollWake(t0 + 2100, true));
        b.hurt("s", "burning", t0 + 100 + BotBrain.HURT_COOLDOWN_MILLIS);
        assertEquals(WakeReason.HURT, b.pollWake(t0 + 100 + BotBrain.HURT_COOLDOWN_MILLIS, true));
    }

    @Test
    void mostUrgentPendingWins() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.actionsEnded("s", "done");
        b.hurt("s", "ouch", t0);
        b.replied("x");
        assertEquals(WakeReason.HURT, b.pollWake(t0 + 1, false));
        assertNull(b.pollWake(t0 + 2, false));
    }

    @Test
    void failedRequestRaisesItsReasonAgain() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.failed(WakeReason.MENTIONED);
        assertFalse(b.awaitingReply());
        assertEquals(WakeReason.MENTIONED, b.pollWake(t0 + 1, true));
    }

    @Test
    void pausedBotsNeverWake() {
        BotBrain b = brain();
        b.setPaused(true);
        b.hurt("s", "ouch", t0);
        assertNull(b.pollWake(t0 + IDLE * 2, false));
        b.setPaused(false);
        assertEquals(WakeReason.HURT, b.pollWake(t0 + IDLE * 2, false));
    }

    @Test
    void eventLogKeepsTheNewest() {
        BotBrain b = brain();
        for (int i = 0; i < 5; i++) b.note("s", "event " + i);
        assertEquals(java.util.List.of("[s] event 2", "[s] event 3", "[s] event 4"), b.events().lines());
    }
}
```

`AiCiv/src/test/java/com/aiciv/brain/HearingTest.java`:

```java
package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HearingTest {
    @Test
    void mentionsAreWholeWordsIgnoringCase() {
        assertTrue(Hearing.mentions("hey ada!", "Ada"));
        assertTrue(Hearing.mentions("ADA, come here", "Ada"));
        assertTrue(Hearing.mentions("@Ada_2 hi", "Ada_2"));
        assertFalse(Hearing.mentions("adam is here", "Ada"));
        assertFalse(Hearing.mentions("canada", "Ada"));
        assertFalse(Hearing.mentions("Ada_2 hi", "Ada"));
    }

    @Test
    void hearingRangeOrMention() {
        assertTrue(Hearing.hears(64 * 64, 64, false));
        assertFalse(Hearing.hears(64 * 64 + 1, 64, false));
        assertTrue(Hearing.hears(Double.MAX_VALUE, 64, true));
    }
}
```

`AiCiv/src/test/java/com/aiciv/brain/ThinkQueueTest.java`:

```java
package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ThinkQueueTest {
    @Test
    void priorityThenOldestFirst() {
        ThinkQueue q = new ThinkQueue();
        q.offer("idle1", WakeReason.IDLE, 1);
        q.offer("ended", WakeReason.ACTION_ENDED, 5);
        q.offer("hurt", WakeReason.HURT, 9);
        q.offer("idle0", WakeReason.IDLE, 0);
        assertEquals("hurt", q.pollFirst(b -> true).orElseThrow().bot());
        assertEquals("ended", q.pollFirst(b -> true).orElseThrow().bot());
        assertEquals("idle0", q.pollFirst(b -> true).orElseThrow().bot());
        assertEquals("idle1", q.pollFirst(b -> true).orElseThrow().bot());
        assertTrue(q.pollFirst(b -> true).isEmpty());
    }

    @Test
    void reofferKeepsHigherPriorityAndEarliestTime() {
        ThinkQueue q = new ThinkQueue();
        q.offer("a", WakeReason.IDLE, 10);
        q.offer("a", WakeReason.MENTIONED, 20);
        q.offer("a", WakeReason.IDLE, 5);
        ThinkQueue.Ticket t = q.pollFirst(b -> true).orElseThrow();
        assertEquals(WakeReason.MENTIONED, t.reason());
        assertEquals(5, t.since());
        assertEquals(0, q.size());
    }

    @Test
    void blockedBotsAreSkippedNotDropped() {
        ThinkQueue q = new ThinkQueue();
        q.offer("blocked", WakeReason.HURT, 0);
        q.offer("free", WakeReason.IDLE, 0);
        assertEquals("free", q.pollFirst(b -> !b.equals("blocked")).orElseThrow().bot());
        assertEquals(1, q.positionOf("blocked"));
        assertEquals(-1, q.positionOf("free"));
        q.remove("blocked");
        assertEquals(0, q.size());
    }
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.brain.*"`
Expected: FAIL, `cannot find symbol: class BotBrain`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/brain/WakeReason.java`:

```java
package com.aiciv.brain;

/** Why a bot should think. Declaration order is priority: the first entry is most urgent. */
public enum WakeReason {
    HURT,
    MENTIONED,
    ACTION_ENDED,
    IDLE
}
```

`AiCiv/src/main/java/com/aiciv/brain/EventLog.java`:

```java
package com.aiciv.brain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** The newest events a bot remembers, sent with every request. */
public final class EventLog {
    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();

    public EventLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void add(String stamp, String text) {
        lines.addLast("[" + stamp + "] " + text);
        while (lines.size() > capacity) lines.removeFirst();
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }
}
```

`AiCiv/src/main/java/com/aiciv/brain/Hearing.java`:

```java
package com.aiciv.brain;

import java.util.regex.Pattern;

public final class Hearing {
    private Hearing() {}

    /** True when text names the player as a whole word, ignoring case. */
    public static boolean mentions(String text, String name) {
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        return p.matcher(text).find();
    }

    public static boolean hears(double distanceSq, int range, boolean mentioned) {
        return mentioned || distanceSq <= (double) range * range;
    }
}
```

`AiCiv/src/main/java/com/aiciv/brain/BotBrain.java`:

```java
package com.aiciv.brain;

/**
 * Decides when one bot should think. Pure state: callers pass the time in milliseconds.
 * Not thread-safe; use it from the server thread only.
 */
public final class BotBrain {
    /** One hurt wake per this long, so damage over time does not cost a call per reply. */
    public static final long HURT_COOLDOWN_MILLIS = 10_000;

    private final String name;
    private String persona;
    private String goal;
    private String modelKey;
    private boolean paused;
    private final EventLog events;
    private final long idleMillis;
    private final long mentionCooldownMillis;
    private WakeReason pending;
    private long lastDecisionMillis;
    private long lastMentionWakeMillis = Long.MIN_VALUE / 2;
    private long lastHurtWakeMillis = Long.MIN_VALUE / 2;
    private boolean mentionWaiting;
    private boolean awaitingReply;
    private String lastDecision = "nothing yet";

    public BotBrain(String name, String persona, String goal, String modelKey, int eventWindow,
                    long idleMillis, long mentionCooldownMillis, long nowMillis) {
        this.name = name;
        this.persona = persona;
        this.goal = goal;
        this.modelKey = modelKey;
        this.events = new EventLog(eventWindow);
        this.idleMillis = idleMillis;
        this.mentionCooldownMillis = mentionCooldownMillis;
        this.lastDecisionMillis = nowMillis - idleMillis; // think right after joining
    }

    public void hearChat(String stamp, String speaker, String text, boolean mentioned, long now) {
        events.add(stamp, speaker + " said: \"" + text + "\"");
        if (mentioned) mentionWaiting = true;
    }

    public void hurt(String stamp, String description, long now) {
        events.add(stamp, description);
        if (now - lastHurtWakeMillis >= HURT_COOLDOWN_MILLIS) {
            lastHurtWakeMillis = now;
            raise(WakeReason.HURT);
        }
    }

    public void actionsEnded(String stamp, String summary) {
        events.add(stamp, summary);
        raise(WakeReason.ACTION_ENDED);
    }

    /** Records an event without waking the bot. */
    public void note(String stamp, String text) {
        events.add(stamp, text);
    }

    /** Why the bot should think now, or null. Clears the trigger it returns. */
    public WakeReason pollWake(long now, boolean busy) {
        if (mentionWaiting && now - lastMentionWakeMillis >= mentionCooldownMillis) {
            mentionWaiting = false;
            lastMentionWakeMillis = now;
            raise(WakeReason.MENTIONED);
        }
        if (paused || awaitingReply) return null;
        if (pending != null) {
            WakeReason r = pending;
            pending = null;
            return r;
        }
        if (!busy && now - lastDecisionMillis >= idleMillis) return WakeReason.IDLE;
        return null;
    }

    public void dispatched(long now) {
        awaitingReply = true;
        lastDecisionMillis = now;
    }

    public void replied(String summary) {
        awaitingReply = false;
        lastDecision = summary;
    }

    /** The request failed; ask again for the same reason once a provider is free. */
    public void failed(WakeReason reason) {
        awaitingReply = false;
        raise(reason);
    }

    private void raise(WakeReason r) {
        if (pending == null || r.ordinal() < pending.ordinal()) pending = r;
    }

    public String name() { return name; }
    public String persona() { return persona; }
    public String goal() { return goal; }
    public String modelKey() { return modelKey; }
    public boolean paused() { return paused; }
    public boolean awaitingReply() { return awaitingReply; }
    public String lastDecision() { return lastDecision; }
    public EventLog events() { return events; }

    public void setPersona(String persona) { this.persona = persona; }
    public void setGoal(String goal) { this.goal = goal; }
    public void setModelKey(String modelKey) { this.modelKey = modelKey; }
    public void setPaused(boolean paused) { this.paused = paused; }
}
```

`AiCiv/src/main/java/com/aiciv/brain/ThinkQueue.java`:

```java
package com.aiciv.brain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Bots waiting for an LLM call, most urgent first, then longest waiting. Server thread only. */
public final class ThinkQueue {
    public record Ticket(String bot, WakeReason reason, long since) {}

    private static final Comparator<Ticket> ORDER =
            Comparator.comparingInt((Ticket t) -> t.reason().ordinal()).thenComparingLong(Ticket::since);

    private final Map<String, Ticket> tickets = new LinkedHashMap<>();

    public void offer(String bot, WakeReason reason, long since) {
        tickets.merge(bot, new Ticket(bot, reason, since), (old, neu) -> new Ticket(bot,
                neu.reason().ordinal() < old.reason().ordinal() ? neu.reason() : old.reason(),
                Math.min(old.since(), neu.since())));
    }

    /** Removes and returns the best ticket whose bot can be dispatched now. */
    public Optional<Ticket> pollFirst(Predicate<String> dispatchable) {
        Optional<Ticket> best = tickets.values().stream().filter(t -> dispatchable.test(t.bot())).min(ORDER);
        best.ifPresent(t -> tickets.remove(t.bot()));
        return best;
    }

    public void remove(String bot) {
        tickets.remove(bot);
    }

    public void clear() {
        tickets.clear();
    }

    public int size() {
        return tickets.size();
    }

    public int positionOf(String bot) {
        List<Ticket> sorted = tickets.values().stream().sorted(ORDER).toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).bot().equals(bot)) return i + 1;
        }
        return -1;
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.brain.*"`
Expected: PASS (16 tests).

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/brain AiCiv/src/test/java/com/aiciv/brain
git commit -m "Decide when each bot thinks, with cooldowns on mentions and damage

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Prompt and snapshot formatting

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/perception/Compass.java`
- Create: `AiCiv/src/main/java/com/aiciv/perception/GameTime.java`
- Create: `AiCiv/src/main/java/com/aiciv/perception/Sighting.java`
- Create: `AiCiv/src/main/java/com/aiciv/perception/SnapshotData.java`
- Create: `AiCiv/src/main/java/com/aiciv/perception/SnapshotFormatter.java`
- Create: `AiCiv/src/main/java/com/aiciv/brain/PromptBuilder.java`
- Test: `AiCiv/src/test/java/com/aiciv/perception/CompassAndTimeTest.java`
- Test: `AiCiv/src/test/java/com/aiciv/perception/SnapshotFormatterTest.java`
- Test: `AiCiv/src/test/java/com/aiciv/brain/PromptBuilderTest.java`

**Interfaces:**
- Produces: `Compass.direction(double dx, double dz) -> "N".."NW" | "here"`; `GameTime.stamp(long clockTicks)`, `GameTime.day(long)`, `GameTime.phase(long)`.
- Produces: `record Sighting(String label, int count, int distance, String direction)`; `record SnapshotData(String name, int x, int y, int z, int health, int maxHealth, int food, long day, String timeOfDay, String biome, String holding, List<String> inventory, List<Sighting> blocks, List<Sighting> players, List<Sighting> mobs, String currentAction, String lastResult)`.
- Produces: `SnapshotFormatter.MAX_CHARS = 6000`; `SnapshotFormatter.format(SnapshotData, int maxChars) -> String`.
- Produces: `PromptBuilder.system(String name, String persona, String goal, List<String> others, int maxActions, String toolGuide)`; `PromptBuilder.user(List<String> notes, List<String> events, String snapshot)`.

- [ ] **Step 1: Write the failing tests**

`AiCiv/src/test/java/com/aiciv/perception/CompassAndTimeTest.java`:

```java
package com.aiciv.perception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompassAndTimeTest {
    @Test
    void compassUsesMinecraftAxes() {
        assertEquals("N", Compass.direction(0, -10));
        assertEquals("E", Compass.direction(10, 0));
        assertEquals("S", Compass.direction(0, 10));
        assertEquals("W", Compass.direction(-10, 0));
        assertEquals("NE", Compass.direction(7, -7));
        assertEquals("SW", Compass.direction(-7, 7));
        assertEquals("here", Compass.direction(0.2, -0.3));
    }

    @Test
    void stampsAndPhases() {
        assertEquals("day 1, 06:00", GameTime.stamp(0));
        assertEquals("day 1, 12:00", GameTime.stamp(6000));
        assertEquals("day 1, 12:30", GameTime.stamp(6500));
        assertEquals("day 2, 00:00", GameTime.stamp(24000 + 18000));
        assertEquals(3, GameTime.day(48000));
        assertEquals("morning", GameTime.phase(3000));
        assertEquals("afternoon", GameTime.phase(24000 + 8000));
        assertEquals("night", GameTime.phase(15000));
    }
}
```

`AiCiv/src/test/java/com/aiciv/perception/SnapshotFormatterTest.java`:

```java
package com.aiciv.perception;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotFormatterTest {
    static SnapshotData sample(List<Sighting> blocks) {
        return new SnapshotData("Ada", 102, 64, -33, 18, 20, 14, 3, "afternoon", "plains", "stone_pickaxe",
                List.of("oak_log x12", "bread x3"), blocks,
                List.of(new Sighting("Bob [AI]", 1, 6, "N")),
                List.of(new Sighting("zombie", 2, 15, "E")),
                "none", "mine iron_ore x10 failed: no path");
    }

    @Test
    void rendersEveryLine() {
        String text = SnapshotFormatter.format(sample(List.of(new Sighting("iron_ore", 3, 8, "NE"), new Sighting("crafting_table", 1, 3, "S"))), 6000);
        assertEquals("""
                You: Ada | pos 102 64 -33 | hp 18/20 food 14/20 | day 3, afternoon | biome plains
                Holding: stone_pickaxe
                Inventory: oak_log x12, bread x3
                Blocks nearby: iron_ore x3 (nearest 8m NE), crafting_table (3m S)
                Players nearby: Bob [AI] (6m N)
                Mobs nearby: zombie x2 (nearest 15m E)
                Current action: none (last: mine iron_ore x10 failed: no path)""", text);
    }

    @Test
    void emptyListsSayNothing() {
        SnapshotData s = new SnapshotData("Ada", 0, 64, 0, 20, 20, 20, 1, "morning", "plains", "nothing",
                List.of(), List.of(), List.of(), List.of(), "none", "nothing yet");
        String text = SnapshotFormatter.format(s, 6000);
        assertTrue(text.contains("Inventory: empty"));
        assertTrue(text.contains("Blocks nearby: nothing notable"));
        assertTrue(text.contains("Players nearby: nobody"));
        assertTrue(text.contains("Mobs nearby: none"));
    }

    @Test
    void longListsAreTruncatedWithACount() {
        List<Sighting> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add(new Sighting("block_number_" + i, 1, i, "N"));
        String text = SnapshotFormatter.format(sample(many), 6000);
        assertTrue(text.length() <= 6000, "length " + text.length());
        assertTrue(text.contains("block_number_0 "), text);
        assertTrue(text.matches("(?s).*\\+\\d+ more.*"), text);
        assertTrue(text.contains("Current action:"));
    }
}
```

`AiCiv/src/test/java/com/aiciv/brain/PromptBuilderTest.java`:

```java
package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {
    @Test
    void systemPromptCarriesIdentityAndRules() {
        String s = PromptBuilder.system("Ada", "a cheerful explorer", "build a lighthouse",
                List.of("Bob (AI)", "Steve (human)"), 3, "TOOLS GO HERE");
        assertTrue(s.contains("You are Ada"));
        assertTrue(s.contains("one of 3 people"));
        assertTrue(s.contains("Bob (AI), Steve (human)"));
        assertTrue(s.contains("a cheerful explorer"));
        assertTrue(s.contains("build a lighthouse"));
        assertTrue(s.contains("up to 3"));
        assertTrue(s.contains("TOOLS GO HERE"));
    }

    @Test
    void blankPersonaAndGoalGetDefaults() {
        String s = PromptBuilder.system("Ada", "", "", List.of(), 3, "");
        assertTrue(s.contains("nobody else yet"));
        assertTrue(s.contains("friendly and curious"));
        assertTrue(s.contains("help the settlement survive and grow"));
    }

    @Test
    void userPromptListsNotesEventsAndSnapshot() {
        String u = PromptBuilder.user(List.of(), List.of("[day 1, 06:00] Steve said: \"hi\""), "SNAPSHOT");
        assertTrue(u.contains("Your notes: none"));
        assertTrue(u.contains("- [day 1, 06:00] Steve said: \"hi\""));
        assertTrue(u.contains("SNAPSHOT"));
        assertTrue(u.endsWith("Decide what to do next."));
        assertTrue(PromptBuilder.user(List.of(), List.of(), "S").contains("Recent events: none"));
    }
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.perception.*" --tests "com.aiciv.brain.PromptBuilderTest"`
Expected: FAIL, `cannot find symbol: class Compass`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/perception/Compass.java`:

```java
package com.aiciv.perception;

public final class Compass {
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private Compass() {}

    /** Eight-way direction for an offset. North is -z, east is +x. */
    public static String direction(double dx, double dz) {
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return "here";
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        return NAMES[Math.floorMod(Math.round(angle / 45.0), 8)];
    }
}
```

`AiCiv/src/main/java/com/aiciv/perception/GameTime.java`:

```java
package com.aiciv.perception;

import java.util.Locale;

/** Overworld clock ticks to human time. Tick 0 is 06:00 on day 1. */
public final class GameTime {
    private GameTime() {}

    public static String stamp(long clockTicks) {
        long t = Math.floorMod(clockTicks, 24000L);
        long hours = (t / 1000 + 6) % 24;
        long minutes = (t % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "day %d, %02d:%02d", day(clockTicks), hours, minutes);
    }

    public static long day(long clockTicks) {
        return Math.floorDiv(clockTicks, 24000L) + 1;
    }

    public static String phase(long clockTicks) {
        long t = Math.floorMod(clockTicks, 24000L);
        if (t < 1000) return "early morning";
        if (t < 6000) return "morning";
        if (t < 11000) return "afternoon";
        if (t < 13000) return "evening";
        if (t < 23000) return "night";
        return "dawn";
    }
}
```

`AiCiv/src/main/java/com/aiciv/perception/Sighting.java`:

```java
package com.aiciv.perception;

/** Something seen nearby: count of that kind, distance in blocks to the nearest, and its direction. */
public record Sighting(String label, int count, int distance, String direction) {}
```

`AiCiv/src/main/java/com/aiciv/perception/SnapshotData.java`:

```java
package com.aiciv.perception;

import java.util.List;

/** Everything a bot is told about its situation. Lists are sorted nearest first. */
public record SnapshotData(
        String name, int x, int y, int z,
        int health, int maxHealth, int food,
        long day, String timeOfDay, String biome,
        String holding, List<String> inventory,
        List<Sighting> blocks, List<Sighting> players, List<Sighting> mobs,
        String currentAction, String lastResult) {}
```

`AiCiv/src/main/java/com/aiciv/perception/SnapshotFormatter.java`:

```java
package com.aiciv.perception;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SnapshotFormatter {
    public static final int MAX_CHARS = 6000;

    private SnapshotFormatter() {}

    /** Renders the snapshot, dropping the farthest entries of the longest lists until it fits. */
    public static String format(SnapshotData s, int maxChars) {
        List<List<String>> lists = List.of(
                new ArrayList<>(s.inventory()),
                sightings(s.blocks()),
                sightings(s.players()),
                sightings(s.mobs()));
        int[] dropped = new int[4];
        String text = render(s, lists, dropped);
        while (text.length() > maxChars) {
            int longest = -1;
            for (int i = 0; i < lists.size(); i++) {
                if (!lists.get(i).isEmpty() && (longest < 0 || lists.get(i).size() > lists.get(longest).size())) longest = i;
            }
            if (longest < 0) break;
            List<String> l = lists.get(longest);
            l.remove(l.size() - 1);
            dropped[longest]++;
            text = render(s, lists, dropped);
        }
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private static List<String> sightings(List<Sighting> in) {
        List<String> out = new ArrayList<>();
        for (Sighting b : in) {
            out.add(b.count() > 1
                    ? String.format(Locale.ROOT, "%s x%d (nearest %dm %s)", b.label(), b.count(), b.distance(), b.direction())
                    : String.format(Locale.ROOT, "%s (%dm %s)", b.label(), b.distance(), b.direction()));
        }
        return out;
    }

    private static String render(SnapshotData s, List<List<String>> lists, int[] dropped) {
        return String.format(Locale.ROOT, "You: %s | pos %d %d %d | hp %d/%d food %d/20 | day %d, %s | biome %s",
                        s.name(), s.x(), s.y(), s.z(), s.health(), s.maxHealth(), s.food(), s.day(), s.timeOfDay(), s.biome())
                + "\nHolding: " + s.holding() + "\n"
                + "Inventory: " + join(lists.get(0), dropped[0], "empty") + "\n"
                + "Blocks nearby: " + join(lists.get(1), dropped[1], "nothing notable") + "\n"
                + "Players nearby: " + join(lists.get(2), dropped[2], "nobody") + "\n"
                + "Mobs nearby: " + join(lists.get(3), dropped[3], "none") + "\n"
                + "Current action: " + s.currentAction() + " (last: " + s.lastResult() + ")";
    }

    private static String join(List<String> items, int dropped, String emptyWord) {
        if (items.isEmpty() && dropped == 0) return emptyWord;
        String joined = String.join(", ", items);
        if (dropped == 0) return joined;
        return (joined.isEmpty() ? "" : joined + ", ") + "+" + dropped + " more";
    }
}
```

`AiCiv/src/main/java/com/aiciv/brain/PromptBuilder.java`:

```java
package com.aiciv.brain;

import java.util.List;

public final class PromptBuilder {
    private PromptBuilder() {}

    public static String system(String name, String persona, String goal, List<String> others, int maxActions, String toolGuide) {
        String people = others.isEmpty() ? "nobody else yet" : String.join(", ", others);
        String who = persona == null || persona.isBlank() ? "friendly and curious" : persona;
        String aim = goal == null || goal.isBlank() ? "help the settlement survive and grow" : goal;
        return """
                You are %s, a player in a Minecraft survival world. You are one of %d people founding a settlement here. \
                The others are: %s.
                Your personality: %s.
                Your long-term goal: %s.

                How to play:
                - You play survival like a normal player. You cannot use commands or cheats.
                - You act by calling the provided functions. Each turn you may call up to %d; they run in order.
                - Saying something does not interrupt what you are doing. Any other action replaces your current one.
                - Chat like a real player: short messages, in character.
                - Coordinates: x grows to the east, z grows to the south, y is height.
                - You only know what your situation lists. Explore to find more.
                %s""".formatted(name, others.size() + 1, people, who, aim, maxActions, toolGuide);
    }

    public static String user(List<String> notes, List<String> events, String snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(notes.isEmpty() ? "Your notes: none\n" : "Your notes:\n" + bullets(notes));
        sb.append('\n');
        sb.append(events.isEmpty() ? "Recent events: none\n" : "Recent events (oldest first):\n" + bullets(events));
        sb.append('\n');
        sb.append("Your situation now:\n").append(snapshot).append("\n\n");
        sb.append("Decide what to do next.");
        return sb.toString();
    }

    private static String bullets(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append("- ").append(l).append('\n');
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.perception.*" --tests "com.aiciv.brain.PromptBuilderTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/perception AiCiv/src/main/java/com/aiciv/brain AiCiv/src/test/java/com/aiciv
git commit -m "Describe a bot's situation in a few capped lines and build its prompts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Pathfinder (pure)

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/nav/Pos.java`
- Create: `AiCiv/src/main/java/com/aiciv/nav/BlockView.java`
- Create: `AiCiv/src/main/java/com/aiciv/nav/Goal.java`
- Create: `AiCiv/src/main/java/com/aiciv/nav/NearGoal.java`
- Create: `AiCiv/src/main/java/com/aiciv/nav/ColumnGoal.java`
- Create: `AiCiv/src/main/java/com/aiciv/nav/PathSearch.java`
- Create: `AiCiv/src/main/java/com/aiciv/nav/NavBudget.java`
- Create: `AiCiv/src/test/java/com/aiciv/testutil/GridView.java`
- Test: `AiCiv/src/test/java/com/aiciv/nav/PathSearchTest.java`
- Test: `AiCiv/src/test/java/com/aiciv/nav/NavBudgetTest.java`

**Interfaces:**
- Produces: `record Pos(int x, int y, int z)`; `interface BlockView { boolean passable(int,int,int); boolean standable(int,int,int); boolean dangerous(int,int,int); }`.
- Produces: `interface Goal { boolean reached(Pos feet); double estimate(Pos feet); String describe(); }`; `record NearGoal(double x, double y, double z, double radius)` (target point, e.g. block centre `bx + 0.5, by, bz + 0.5`); `record ColumnGoal(double x, double z, double radius)`.
- Produces: `new PathSearch(BlockView, Pos start, Goal, int maxNodes)`; `State advance(int nodeBudget)`; `State state()`; `List<Pos> path()` (excludes start); `String failure()`; `int expandedCount()`; `void cancel()`; `PathSearch.MAX_NODES = 20_000`; `enum State { RUNNING, FOUND, FAILED }`.
- Produces: `new NavBudget(long budgetNanos, LongSupplier nanoClock)`; `NavBudget.DEFAULT_NANOS = 2_000_000L`; `submit(PathSearch)`, `tick()`, `activeCount()`.

- [ ] **Step 1: Write the grid helper and the failing tests**

`AiCiv/src/test/java/com/aiciv/testutil/GridView.java`:

```java
package com.aiciv.testutil;

import com.aiciv.nav.BlockView;
import com.aiciv.nav.Pos;
import java.util.HashSet;
import java.util.Set;

/** A hand-built block world: everything is air unless marked solid or dangerous. */
public final class GridView implements BlockView {
    private final Set<Pos> solid = new HashSet<>();
    private final Set<Pos> danger = new HashSet<>();

    public GridView floor(int x0, int x1, int y, int z0, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) solid.add(new Pos(x, y, z));
        return this;
    }

    public GridView solid(int x, int y, int z) {
        solid.add(new Pos(x, y, z));
        return this;
    }

    /** A dangerous block you can stand on, like magma. */
    public GridView magma(int x, int y, int z) {
        solid.add(new Pos(x, y, z));
        danger.add(new Pos(x, y, z));
        return this;
    }

    /** A dangerous block with no collision, like lava. */
    public GridView lava(int x, int y, int z) {
        solid.remove(new Pos(x, y, z));
        danger.add(new Pos(x, y, z));
        return this;
    }

    @Override public boolean passable(int x, int y, int z) { return !solid.contains(new Pos(x, y, z)); }
    @Override public boolean standable(int x, int y, int z) { return solid.contains(new Pos(x, y, z)); }
    @Override public boolean dangerous(int x, int y, int z) { return danger.contains(new Pos(x, y, z)); }
}
```

`AiCiv/src/test/java/com/aiciv/nav/PathSearchTest.java`:

```java
package com.aiciv.nav;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.GridView;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSearchTest {
    static NearGoal at(int x, int y, int z) {
        return new NearGoal(x + 0.5, y, z + 0.5, 0.5);
    }

    static PathSearch run(GridView g, Pos start, Goal goal) {
        PathSearch s = new PathSearch(g, start, goal, PathSearch.MAX_NODES);
        s.advance(Integer.MAX_VALUE);
        return s;
    }

    @Test
    void walksAcrossAFlatFloor() {
        PathSearch s = run(new GridView().floor(0, 10, 0, 0, 0), new Pos(0, 1, 0), at(10, 1, 0));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertEquals(10, s.path().size());
        assertEquals(new Pos(10, 1, 0), s.path().get(9));
    }

    @Test
    void diagonalsDoNotCutCorners() {
        GridView g = new GridView().floor(0, 2, 0, 0, 2).solid(1, 1, 0).solid(1, 2, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), at(1, 1, 1));
        assertEquals(List.of(new Pos(0, 1, 1), new Pos(1, 1, 1)), s.path());
    }

    @Test
    void jumpsUpOneBlock() {
        GridView g = new GridView().floor(0, 5, 0, 0, 0).floor(3, 5, 1, 0, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), at(5, 2, 0));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertTrue(s.path().contains(new Pos(3, 2, 0)), s.path().toString());
    }

    @Test
    void cannotClimbATwoBlockWall() {
        GridView g = new GridView().floor(0, 6, 0, 0, 2);
        for (int z = 0; z <= 2; z++) g.solid(3, 1, z).solid(3, 2, z);
        PathSearch s = run(g, new Pos(0, 1, 1), at(5, 1, 1));
        assertEquals(PathSearch.State.FAILED, s.state());
        assertEquals("no path", s.failure());
    }

    @Test
    void dropsThreeButNotFour() {
        GridView ok = new GridView().floor(0, 2, 3, 0, 0).floor(3, 6, 0, 0, 0);
        assertEquals(PathSearch.State.FOUND, run(ok, new Pos(0, 4, 0), at(5, 1, 0)).state());
        GridView tooDeep = new GridView().floor(0, 2, 3, 0, 0).floor(3, 6, -1, 0, 0);
        assertEquals(PathSearch.State.FAILED, run(tooDeep, new Pos(0, 4, 0), at(5, 0, 0)).state());
    }

    @Test
    void goesAroundLavaAndMagma() {
        GridView g = new GridView().floor(0, 6, 0, 0, 2).lava(3, 0, 1).magma(4, 0, 1);
        PathSearch s = run(g, new Pos(0, 1, 1), at(6, 1, 1));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertFalse(s.path().contains(new Pos(3, 1, 1)));
        assertFalse(s.path().contains(new Pos(4, 1, 1)));
        assertTrue(s.path().stream().allMatch(p -> p.y() == 1), s.path().toString());
    }

    @Test
    void unreachableGoalStopsAtTheNodeCap() {
        GridView g = new GridView().floor(-100, 100, 0, -100, 100);
        PathSearch s = new PathSearch(g, new Pos(0, 1, 0), at(0, 50, 0), 1000);
        s.advance(Integer.MAX_VALUE);
        assertEquals(PathSearch.State.FAILED, s.state());
        assertEquals("no path", s.failure());
        assertTrue(s.expandedCount() <= 1000);
    }

    @Test
    void advanceIsIncremental() {
        PathSearch s = new PathSearch(new GridView().floor(0, 50, 0, 0, 0), new Pos(0, 1, 0), at(50, 1, 0), PathSearch.MAX_NODES);
        assertEquals(PathSearch.State.RUNNING, s.advance(1));
        int calls = 1;
        while (s.advance(5) == PathSearch.State.RUNNING) calls++;
        assertEquals(PathSearch.State.FOUND, s.state());
        assertTrue(calls > 3);
    }

    @Test
    void columnGoalIgnoresHeight() {
        GridView g = new GridView().floor(0, 10, 0, 0, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), new ColumnGoal(8.5, 0.5, 1.0));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertTrue(s.path().get(s.path().size() - 1).x() >= 7);
    }

    @Test
    void cancelStopsTheSearch() {
        PathSearch s = new PathSearch(new GridView().floor(0, 50, 0, 0, 0), new Pos(0, 1, 0), at(50, 1, 0), PathSearch.MAX_NODES);
        s.cancel();
        assertEquals(PathSearch.State.FAILED, s.advance(100));
        assertEquals("cancelled", s.failure());
    }
}
```

`AiCiv/src/test/java/com/aiciv/nav/NavBudgetTest.java`:

```java
package com.aiciv.nav;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.GridView;
import org.junit.jupiter.api.Test;

class NavBudgetTest {
    long fakeNanos;

    /** Unreachable goal (y = 50), so the search keeps running until its node cap. */
    PathSearch longSearch() {
        GridView g = new GridView().floor(-200, 200, 0, -200, 200);
        return new PathSearch(g, new Pos(0, 1, 0), new NearGoal(150.5, 50, 150.5, 0.5), PathSearch.MAX_NODES);
    }

    @Test
    void sharesTheBudgetRoundRobin() {
        NavBudget budget = new NavBudget(10_000, () -> fakeNanos += 1_000);
        PathSearch a = longSearch(), b = longSearch();
        budget.submit(a);
        budget.submit(b);
        budget.tick();
        assertTrue(a.expandedCount() > 0);
        assertTrue(b.expandedCount() > 0);
        assertEquals(2, budget.activeCount());
    }

    @Test
    void finishedAndCancelledSearchesLeave() {
        NavBudget budget = new NavBudget(1_000_000_000L, System::nanoTime);
        PathSearch quick = new PathSearch(new GridView().floor(0, 3, 0, 0, 0), new Pos(0, 1, 0), new NearGoal(3.5, 1, 0.5, 0.5), PathSearch.MAX_NODES);
        PathSearch cancelled = longSearch();
        cancelled.cancel();
        budget.submit(quick);
        budget.submit(cancelled);
        budget.tick();
        assertEquals(PathSearch.State.FOUND, quick.state());
        assertEquals(0, budget.activeCount());
    }
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.nav.*"`
Expected: FAIL, `cannot find symbol: class PathSearch`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/nav/Pos.java`:

```java
package com.aiciv.nav;

/** A block position. For path nodes it is where the feet are. */
public record Pos(int x, int y, int z) {}
```

`AiCiv/src/main/java/com/aiciv/nav/BlockView.java`:

```java
package com.aiciv.nav;

/** What the pathfinder needs to know about blocks. Implementations must be cheap. */
public interface BlockView {
    /** No collision box: a body can be inside this block. */
    boolean passable(int x, int y, int z);

    /** The top of this block can be stood on. */
    boolean standable(int x, int y, int z);

    /** Hurts or traps anything that enters or stands on it: lava, fire, magma, cactus... */
    boolean dangerous(int x, int y, int z);
}
```

`AiCiv/src/main/java/com/aiciv/nav/Goal.java`:

```java
package com.aiciv.nav;

public interface Goal {
    boolean reached(Pos feet);

    /** Estimated remaining path cost from feet. Close to a lower bound, so A* paths stay near-optimal. */
    double estimate(Pos feet);

    String describe();
}
```

`AiCiv/src/main/java/com/aiciv/nav/NearGoal.java`:

```java
package com.aiciv.nav;

import java.util.Locale;

/** Be within radius of a point. Feet nodes are measured from their block centre. */
public record NearGoal(double x, double y, double z, double radius) implements Goal {
    @Override
    public boolean reached(Pos feet) {
        double dx = feet.x() + 0.5 - x, dy = feet.y() - y, dz = feet.z() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    @Override
    public double estimate(Pos feet) {
        double dx = feet.x() + 0.5 - x, dz = feet.z() + 0.5 - z;
        return Math.max(0.0, Math.sqrt(dx * dx + dz * dz) + 0.5 * Math.abs(feet.y() - y) - radius);
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", Math.floor(x), Math.floor(y), Math.floor(z));
    }
}
```

`AiCiv/src/main/java/com/aiciv/nav/ColumnGoal.java`:

```java
package com.aiciv.nav;

import java.util.Locale;

/** Be within radius of a point horizontally, at any height. Used for legs of long trips. */
public record ColumnGoal(double x, double z, double radius) implements Goal {
    @Override
    public boolean reached(Pos feet) {
        double dx = feet.x() + 0.5 - x, dz = feet.z() + 0.5 - z;
        return dx * dx + dz * dz <= radius * radius;
    }

    @Override
    public double estimate(Pos feet) {
        double dx = feet.x() + 0.5 - x, dz = feet.z() + 0.5 - z;
        return Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - radius);
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "column %.0f %.0f", Math.floor(x), Math.floor(z));
    }
}
```

`AiCiv/src/main/java/com/aiciv/nav/PathSearch.java`:

```java
package com.aiciv.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Incremental A* over feet positions. Moves: walk (incl. diagonal), jump up one block,
 * drop down at most three. Never enters or stands on a dangerous block.
 */
public final class PathSearch {
    public enum State { RUNNING, FOUND, FAILED }

    public static final int MAX_NODES = 20_000;
    static final double WALK = 1.0, DIAGONAL = 1.4, JUMP = 2.0, DROP_BASE = 1.0, DROP_PER_BLOCK = 0.5;
    static final int MAX_DROP = 3;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node {
        final Pos pos;
        final double h;
        double g;
        Node parent;
        boolean closed;

        Node(Pos pos, double g, double h, Node parent) {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.parent = parent;
        }

        double f() {
            return g + h;
        }
    }

    private record Entry(Node node, double f) {}

    private final BlockView view;
    private final Goal goal;
    private final int maxNodes;
    private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
    private final Map<Pos, Node> nodes = new HashMap<>();
    private int expanded;
    private State state = State.RUNNING;
    private List<Pos> path = List.of();
    private String failure = "";

    public PathSearch(BlockView view, Pos start, Goal goal, int maxNodes) {
        this.view = view;
        this.goal = goal;
        this.maxNodes = maxNodes;
        Node s = new Node(start, 0.0, goal.estimate(start), null);
        nodes.put(start, s);
        open.add(new Entry(s, s.f()));
    }

    /** Expands at most nodeBudget nodes, then returns the current state. */
    public State advance(int nodeBudget) {
        int budget = nodeBudget;
        while (state == State.RUNNING && budget-- > 0) {
            Entry e = open.poll();
            if (e == null) {
                fail("no path");
                break;
            }
            Node cur = e.node();
            if (cur.closed || e.f() > cur.f() + 1e-9) continue;
            cur.closed = true;
            if (goal.reached(cur.pos)) {
                path = build(cur);
                state = State.FOUND;
                break;
            }
            if (++expanded >= maxNodes) {
                fail("no path");
                break;
            }
            expand(cur);
        }
        return state;
    }

    public void cancel() {
        if (state == State.RUNNING) fail("cancelled");
    }

    public State state() { return state; }
    public List<Pos> path() { return path; }
    public String failure() { return failure; }
    public int expandedCount() { return expanded; }

    private void expand(Node cur) {
        Pos p = cur.pos;
        int y = p.y();
        for (int[] d : DIRECTIONS) {
            int dx = d[0], dz = d[1];
            int nx = p.x() + dx, nz = p.z() + dz;
            if (dx != 0 && dz != 0) {
                if (canStand(nx, y, nz) && clear(p.x() + dx, y, p.z()) && clear(p.x(), y, p.z() + dz)) {
                    relax(cur, new Pos(nx, y, nz), DIAGONAL);
                }
                continue;
            }
            if (canStand(nx, y, nz)) {
                relax(cur, new Pos(nx, y, nz), WALK);
                continue;
            }
            if (canStand(nx, y + 1, nz) && view.passable(p.x(), y + 2, p.z()) && !view.dangerous(p.x(), y + 2, p.z())) {
                relax(cur, new Pos(nx, y + 1, nz), JUMP);
                continue;
            }
            if (clear(nx, y, nz)) {
                for (int drop = 1; drop <= MAX_DROP; drop++) {
                    int ny = y - drop;
                    if (canStand(nx, ny, nz)) {
                        relax(cur, new Pos(nx, ny, nz), DROP_BASE + DROP_PER_BLOCK * drop);
                        break;
                    }
                    if (!view.passable(nx, ny, nz) || view.dangerous(nx, ny, nz)) break;
                }
            }
        }
    }

    private void relax(Node cur, Pos next, double cost) {
        double g = cur.g + cost;
        Node n = nodes.get(next);
        if (n == null) {
            n = new Node(next, g, goal.estimate(next), cur);
            nodes.put(next, n);
            open.add(new Entry(n, n.f()));
        } else if (!n.closed && g < n.g - 1e-9) {
            n.g = g;
            n.parent = cur;
            open.add(new Entry(n, n.f()));
        }
    }

    /** Body fits at (x, y, z): two passable, safe blocks. */
    private boolean clear(int x, int y, int z) {
        return view.passable(x, y, z) && view.passable(x, y + 1, z)
                && !view.dangerous(x, y, z) && !view.dangerous(x, y + 1, z);
    }

    private boolean canStand(int x, int y, int z) {
        return clear(x, y, z) && view.standable(x, y - 1, z) && !view.dangerous(x, y - 1, z);
    }

    private static List<Pos> build(Node end) {
        List<Pos> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) out.add(n.pos);
        Collections.reverse(out);
        return out;
    }

    private void fail(String reason) {
        state = State.FAILED;
        failure = reason;
    }
}
```

`AiCiv/src/main/java/com/aiciv/nav/NavBudget.java`:

```java
package com.aiciv.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Shares a fixed amount of search time per server tick between all running searches. */
public final class NavBudget {
    public static final long DEFAULT_NANOS = 2_000_000L;
    static final int STEP = 200;

    private final long budgetNanos;
    private final LongSupplier nanoClock;
    private final List<PathSearch> active = new ArrayList<>();
    private int cursor;

    public NavBudget(long budgetNanos, LongSupplier nanoClock) {
        this.budgetNanos = budgetNanos;
        this.nanoClock = nanoClock;
    }

    public void submit(PathSearch search) {
        active.add(search);
    }

    public void tick() {
        long deadline = nanoClock.getAsLong() + budgetNanos;
        while (!active.isEmpty() && nanoClock.getAsLong() < deadline) {
            if (cursor >= active.size()) cursor = 0;
            PathSearch s = active.get(cursor);
            if (s.state() != PathSearch.State.RUNNING || s.advance(STEP) != PathSearch.State.RUNNING) {
                active.remove(cursor);
            } else {
                cursor++;
            }
        }
        active.removeIf(s -> s.state() != PathSearch.State.RUNNING);
    }

    public int activeCount() {
        return active.size();
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.nav.*"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/nav AiCiv/src/test/java/com/aiciv
git commit -m "Find walking paths with A*, sliced across ticks so many bots cannot lag the server

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Actions

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/nav/Navigator.java`, `AiCiv/src/main/java/com/aiciv/nav/NavStatus.java`
- Create: `AiCiv/src/main/java/com/aiciv/actions/Action.java`, `ActionStatus.java`, `BotContext.java`, `ActionExecutor.java`, `ActionFactory.java`, `ToolCatalog.java`, `SayAction.java`, `GoToAction.java`, `GoToPlayerAction.java`, `FollowAction.java`, `StopAction.java` (all under `AiCiv/src/main/java/com/aiciv/actions/`)
- Create: `AiCiv/src/test/java/com/aiciv/testutil/FakeNavigator.java`, `AiCiv/src/test/java/com/aiciv/testutil/FakeContext.java`
- Test: `AiCiv/src/test/java/com/aiciv/actions/ActionFactoryTest.java`, `ActionExecutorTest.java`, `MovementActionsTest.java`

**Interfaces:**
- Consumes: `ToolCall` (Task 4); `Goal`, `NearGoal`, `ColumnGoal` (Task 9).
- Produces: `interface Navigator { void goTo(Goal); NavStatus status(); void stop(); }`; `record NavStatus(State state, String reason)` with `enum State { IDLE, RUNNING, ARRIVED, FAILED }`, constants `IDLE`, `RUNNING`, `ARRIVED`, `static NavStatus failed(String)`.
- Produces: `interface BotContext { Vec3 position(); Optional<Vec3> playerPosition(String name); Navigator navigator(); void say(String text); }`.
- Produces: `interface Action { ActionStatus tick(BotContext); String describe(); int timeoutTicks(); default void cancel(BotContext) {} }`; `record ActionStatus(State state, String reason)` with `RUNNING`, `DONE`, `static failed(String)`.
- Produces: `ActionExecutor`: `replace(List<Action>, BotContext)`, `stop(BotContext)`, `boolean busy()`, `String currentDescription()`, `String tick(BotContext)` (summary when the sequence ends, else null).
- Produces: `ActionFactory.create(ToolCall) -> Result` with `Result.Ok(Action)` / `Result.Invalid(String reason)`.
- Produces: `ToolCatalog.tools() -> JsonArray`, `ToolCatalog.guide() -> String`.
- Produces: `new SayAction(String)` with `text()` and `static sanitize(String)`; `new GoToAction(int x, int y, int z)`; `new GoToPlayerAction(String name)`; `new FollowAction(String name, int seconds)`; `new StopAction()`.

- [ ] **Step 1: Write the navigation interfaces and test fakes**

`AiCiv/src/main/java/com/aiciv/nav/NavStatus.java`:

```java
package com.aiciv.nav;

public record NavStatus(State state, String reason) {
    public enum State { IDLE, RUNNING, ARRIVED, FAILED }

    public static final NavStatus IDLE = new NavStatus(State.IDLE, "");
    public static final NavStatus RUNNING = new NavStatus(State.RUNNING, "");
    public static final NavStatus ARRIVED = new NavStatus(State.ARRIVED, "");

    public static NavStatus failed(String reason) {
        return new NavStatus(State.FAILED, reason);
    }
}
```

`AiCiv/src/main/java/com/aiciv/nav/Navigator.java`:

```java
package com.aiciv.nav;

/** Moves one bot toward a goal over many ticks. */
public interface Navigator {
    /** Starts (or restarts) navigation toward the goal. */
    void goTo(Goal goal);

    NavStatus status();

    /** Stops moving and forgets the goal. */
    void stop();
}
```

`AiCiv/src/test/java/com/aiciv/testutil/FakeNavigator.java`:

```java
package com.aiciv.testutil;

import com.aiciv.nav.Goal;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.Navigator;
import java.util.ArrayList;
import java.util.List;

public final class FakeNavigator implements Navigator {
    public final List<Goal> goals = new ArrayList<>();
    public NavStatus status = NavStatus.IDLE;
    public int stops;

    @Override public void goTo(Goal goal) { goals.add(goal); status = NavStatus.RUNNING; }
    @Override public NavStatus status() { return status; }
    @Override public void stop() { stops++; status = NavStatus.IDLE; }
}
```

`AiCiv/src/test/java/com/aiciv/testutil/FakeContext.java`:

```java
package com.aiciv.testutil;

import com.aiciv.actions.BotContext;
import com.aiciv.nav.Navigator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class FakeContext implements BotContext {
    public Vec3 position = new Vec3(0.5, 64, 0.5);
    public final Map<String, Vec3> players = new HashMap<>();
    public final List<String> said = new ArrayList<>();
    public final FakeNavigator navigator = new FakeNavigator();

    @Override public Vec3 position() { return position; }
    @Override public Optional<Vec3> playerPosition(String name) { return Optional.ofNullable(players.get(name)); }
    @Override public Navigator navigator() { return navigator; }
    @Override public void say(String text) { said.add(text); }
}
```

- [ ] **Step 2: Write the failing tests**

`AiCiv/src/test/java/com/aiciv/actions/ActionFactoryTest.java`:

```java
package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.llm.ToolCall;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ActionFactoryTest {
    static ToolCall call(String name, String argsJson) {
        return ToolCall.of(name, JsonParser.parseString(argsJson).getAsJsonObject());
    }

    static Action ok(ToolCall c) {
        return assertInstanceOf(ActionFactory.Result.Ok.class, ActionFactory.create(c)).action();
    }

    static String invalid(ToolCall c) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, ActionFactory.create(c)).reason();
    }

    @Test
    void coordinatesMayBeStringsOrFloats() {
        assertEquals("goTo 12 64 -4", ok(call("goTo", "{\"x\": \"12\", \"y\": 64.0, \"z\": -3.2}")).describe());
        assertEquals("goTo 12 64 3", ok(call("goTo", "{\"x\": 12.7, \"y\": \" 64 \", \"z\": 3}")).describe());
    }

    @Test
    void namesAreForgiving() {
        assertInstanceOf(GoToAction.class, ok(call("go_to", "{\"x\":1,\"y\":64,\"z\":1}")));
        assertInstanceOf(GoToAction.class, ok(call("GoTo", "{\"x\":1,\"y\":64,\"z\":1}")));
        assertInstanceOf(GoToPlayerAction.class, ok(call("go_to_player", "{\"name\":\"Steve\"}")));
        assertInstanceOf(StopAction.class, ok(call("STOP", "{}")));
    }

    @Test
    void unknownActionListsTheRealOnes() {
        String r = invalid(call("fly", "{}"));
        assertTrue(r.contains("unknown action 'fly'"), r);
        assertTrue(r.contains("say, goTo, goToPlayer, follow, stop"), r);
    }

    @Test
    void badArgumentsAreReported() {
        assertTrue(invalid(call("goTo", "{\"x\": \"twelve\", \"y\": 64, \"z\": 0}")).contains("whole numbers"));
        assertTrue(invalid(call("goTo", "{\"x\": 0, \"y\": 999, \"z\": 0}")).contains("between -64 and 320"));
        assertTrue(invalid(call("goTo", "{\"x\": 40000000, \"y\": 64, \"z\": 0}")).contains("world border"));
        assertTrue(invalid(call("say", "{\"text\": \"\\n \\t\"}")).contains("non-empty"));
        assertTrue(invalid(call("goToPlayer", "{}")).contains("name"));
        assertTrue(invalid(new ToolCall("goTo", new JsonObject(), "arguments for goTo were not a valid JSON object")).contains("not a valid JSON"));
    }

    @Test
    void sayIsCleanedAndCapped() {
        SayAction a = (SayAction) ok(call("say", "{\"text\": \"hi\\nthere\\u00a7c  friend\"}"));
        assertEquals("hi there c friend", a.text());
        String longText = "x".repeat(400);
        assertEquals(256, ((SayAction) ok(call("say", "{\"text\": \"" + longText + "\"}"))).text().length());
    }

    @Test
    void followDefaultsAndClamps() {
        assertEquals("follow Steve 60s", ok(call("follow", "{\"name\": \"Steve\"}")).describe());
        assertEquals("follow Steve 300s", ok(call("follow", "{\"name\": \"Steve\", \"seconds\": 999}")).describe());
        assertEquals("follow Steve 1s", ok(call("follow", "{\"name\": \"Steve\", \"seconds\": -5}")).describe());
    }

    @Test
    void catalogNamesMatchTheFactory() {
        var tools = ToolCatalog.tools();
        assertEquals(5, tools.size());
        for (var t : tools) {
            String name = t.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
            assertFalse(ActionFactory.create(ToolCall.of(name, new JsonObject())).toString().contains("unknown action"), name);
        }
    }
}
```

`AiCiv/src/test/java/com/aiciv/actions/ActionExecutorTest.java`:

```java
package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.FakeContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionExecutorTest {
    static final class Scripted implements Action {
        final String name;
        final Deque<ActionStatus> statuses;
        final int timeout;
        int cancels;

        Scripted(String name, int timeout, ActionStatus... statuses) {
            this.name = name;
            this.timeout = timeout;
            this.statuses = new ArrayDeque<>(List.of(statuses));
        }

        @Override public ActionStatus tick(BotContext ctx) { return statuses.isEmpty() ? ActionStatus.RUNNING : statuses.poll(); }
        @Override public String describe() { return name; }
        @Override public int timeoutTicks() { return timeout; }
        @Override public void cancel(BotContext ctx) { cancels++; }
    }

    FakeContext ctx = new FakeContext();
    ActionExecutor ex = new ActionExecutor();

    @Test
    void sequenceRunsInOrderAndReportsOnce() {
        ex.replace(List.of(new Scripted("a", 100, ActionStatus.DONE), new Scripted("b", 100, ActionStatus.RUNNING, ActionStatus.DONE)), ctx);
        assertTrue(ex.busy());
        assertNull(ex.tick(ctx));
        assertNull(ex.tick(ctx));
        assertEquals("b", ex.currentDescription());
        assertEquals("finished: a, b", ex.tick(ctx));
        assertFalse(ex.busy());
        assertNull(ex.tick(ctx));
    }

    @Test
    void failureSkipsTheRest() {
        ex.replace(List.of(new Scripted("a", 100, ActionStatus.failed("no path")), new Scripted("b", 100, ActionStatus.DONE)), ctx);
        assertEquals("failed: a: no path (skipped: b)", ex.tick(ctx));
        assertFalse(ex.busy());
    }

    @Test
    void timeoutCancelsAndFails() {
        Scripted slow = new Scripted("slow", 3);
        ex.replace(List.of(slow), ctx);
        assertNull(ex.tick(ctx));
        assertNull(ex.tick(ctx));
        assertEquals("failed: slow: timed out", ex.tick(ctx));
        assertEquals(1, slow.cancels);
    }

    @Test
    void replaceCancelsTheCurrentAction() {
        Scripted first = new Scripted("first", 100);
        ex.replace(List.of(first), ctx);
        ex.tick(ctx);
        ex.replace(List.of(new Scripted("second", 100, ActionStatus.DONE)), ctx);
        assertEquals(1, first.cancels);
        assertEquals("finished: second", ex.tick(ctx));
    }

    @Test
    void stopClearsEverything() {
        Scripted a = new Scripted("a", 100);
        ex.replace(List.of(a, new Scripted("b", 100)), ctx);
        ex.tick(ctx);
        ex.stop(ctx);
        assertFalse(ex.busy());
        assertEquals("none", ex.currentDescription());
        assertEquals(1, a.cancels);
    }
}
```

`AiCiv/src/test/java/com/aiciv/actions/MovementActionsTest.java`:

```java
package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.testutil.FakeContext;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MovementActionsTest {
    FakeContext ctx = new FakeContext();

    @Test
    void goToNearbyUsesANearGoal() {
        GoToAction a = new GoToAction(10, 64, 0);
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        NearGoal g = assertInstanceOf(NearGoal.class, ctx.navigator.goals.get(0));
        assertEquals(10.5, g.x());
        assertEquals(1.5, g.radius());
        ctx.navigator.status = NavStatus.ARRIVED;
        assertEquals(ActionStatus.DONE, a.tick(ctx));
    }

    @Test
    void goToFarGoesInLegs() {
        GoToAction a = new GoToAction(1000, 64, 0);
        a.tick(ctx);
        ColumnGoal leg = assertInstanceOf(ColumnGoal.class, ctx.navigator.goals.get(0));
        assertEquals(120.5, leg.x(), 0.01);
        ctx.navigator.status = NavStatus.ARRIVED;
        ctx.position = new Vec3(995.5, 64, 0.5);
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertInstanceOf(NearGoal.class, ctx.navigator.goals.get(1));
    }

    @Test
    void goToReportsNavigationFailure() {
        GoToAction a = new GoToAction(10, 64, 0);
        a.tick(ctx);
        ctx.navigator.status = NavStatus.failed("no path");
        assertEquals(ActionStatus.failed("no path"), a.tick(ctx));
    }

    @Test
    void goToPlayerFailsWhenOfflineAndFinishesWhenClose() {
        assertEquals(ActionStatus.State.FAILED, new GoToPlayerAction("Steve").tick(ctx).state());
        ctx.players.put("Steve", new Vec3(20.5, 64, 0.5));
        GoToPlayerAction a = new GoToPlayerAction("Steve");
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertEquals(1, ctx.navigator.goals.size());
        ctx.position = new Vec3(18.5, 64, 0.5);
        assertEquals(ActionStatus.DONE, a.tick(ctx));
    }

    @Test
    void followEndsAfterItsTime() {
        ctx.players.put("Steve", new Vec3(1.5, 64, 0.5));
        FollowAction a = new FollowAction("Steve", 1);
        for (int i = 0; i < 20; i++) assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertEquals(ActionStatus.DONE, a.tick(ctx));
        assertEquals((1 + 5) * 20, a.timeoutTicks());
    }

    @Test
    void followChasesWhenFar() {
        ctx.players.put("Steve", new Vec3(30.5, 64, 0.5));
        FollowAction a = new FollowAction("Steve", 10);
        a.tick(ctx);
        assertEquals(1, ctx.navigator.goals.size());
        ctx.players.put("Steve", new Vec3(40.5, 64, 0.5));
        a.tick(ctx);
        assertEquals(2, ctx.navigator.goals.size());
    }

    @Test
    void sayTalksOnce() {
        assertEquals(ActionStatus.DONE, new SayAction("hello").tick(ctx));
        assertEquals(java.util.List.of("hello"), ctx.said);
    }
}
```

- [ ] **Step 3: Run and watch them fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.actions.*"`
Expected: FAIL, `cannot find symbol: class ActionFactory`.

- [ ] **Step 4: Implement**

`AiCiv/src/main/java/com/aiciv/actions/ActionStatus.java`:

```java
package com.aiciv.actions;

public record ActionStatus(State state, String reason) {
    public enum State { RUNNING, DONE, FAILED }

    public static final ActionStatus RUNNING = new ActionStatus(State.RUNNING, "");
    public static final ActionStatus DONE = new ActionStatus(State.DONE, "");

    public static ActionStatus failed(String reason) {
        return new ActionStatus(State.FAILED, reason);
    }
}
```

`AiCiv/src/main/java/com/aiciv/actions/BotContext.java`:

```java
package com.aiciv.actions;

import com.aiciv.nav.Navigator;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/** What an action may see and do. Implemented by the live world and by test fakes. */
public interface BotContext {
    Vec3 position();

    /** Position of an online, non-spectating player in the bot's dimension. */
    Optional<Vec3> playerPosition(String name);

    Navigator navigator();

    /** Posts a chat line as the bot. */
    void say(String text);
}
```

`AiCiv/src/main/java/com/aiciv/actions/Action.java`:

```java
package com.aiciv.actions;

/** One long-running thing a bot does. Ticked on the server thread until it is done or failed. */
public interface Action {
    ActionStatus tick(BotContext ctx);

    String describe();

    int timeoutTicks();

    /** Called when the action is replaced, stopped or timed out. */
    default void cancel(BotContext ctx) {}
}
```

`AiCiv/src/main/java/com/aiciv/actions/ActionExecutor.java`:

```java
package com.aiciv.actions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Runs one bot's queued actions in order, one at a time. Server thread only. */
public final class ActionExecutor {
    private final Deque<Action> queue = new ArrayDeque<>();
    private final List<String> finished = new ArrayList<>();
    private Action current;
    private int ticksOnCurrent;

    public void replace(List<Action> actions, BotContext ctx) {
        stop(ctx);
        queue.addAll(actions);
    }

    public void stop(BotContext ctx) {
        if (current != null) current.cancel(ctx);
        current = null;
        queue.clear();
        finished.clear();
    }

    public boolean busy() {
        return current != null || !queue.isEmpty();
    }

    public String currentDescription() {
        return current == null ? "none" : current.describe();
    }

    /** Runs one tick. Returns a summary when the whole sequence just ended, otherwise null. */
    public String tick(BotContext ctx) {
        if (current == null) {
            current = queue.poll();
            ticksOnCurrent = 0;
            if (current == null) return null;
        }
        ActionStatus status = current.tick(ctx);
        ticksOnCurrent++;
        if (status.state() == ActionStatus.State.RUNNING && ticksOnCurrent >= current.timeoutTicks()) {
            current.cancel(ctx);
            status = ActionStatus.failed("timed out");
        }
        switch (status.state()) {
            case RUNNING:
                return null;
            case DONE: {
                finished.add(current.describe());
                current = null;
                if (!queue.isEmpty()) return null;
                String summary = "finished: " + String.join(", ", finished);
                finished.clear();
                return summary;
            }
            default: {
                StringBuilder summary = new StringBuilder("failed: ").append(current.describe()).append(": ").append(status.reason());
                if (!queue.isEmpty()) {
                    List<String> skipped = new ArrayList<>();
                    for (Action a : queue) skipped.add(a.describe());
                    summary.append(" (skipped: ").append(String.join(", ", skipped)).append(')');
                }
                current = null;
                queue.clear();
                finished.clear();
                return summary.toString();
            }
        }
    }
}
```

`AiCiv/src/main/java/com/aiciv/actions/SayAction.java`:

```java
package com.aiciv.actions;

public final class SayAction implements Action {
    public static final int MAX_LENGTH = 256;
    private final String text;

    public SayAction(String text) {
        this.text = sanitize(text);
    }

    /** Removes formatting codes and control characters, collapses spaces, caps the length. */
    public static String sanitize(String raw) {
        String s = raw.replace('\u00a7', ' ').replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
    }

    public String text() {
        return text;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (text.isEmpty()) return ActionStatus.failed("nothing to say");
        ctx.say(text);
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return "say \"" + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "\"";
    }

    @Override
    public int timeoutTicks() {
        return 20;
    }
}
```

`AiCiv/src/main/java/com/aiciv/actions/GoToAction.java`:

```java
package com.aiciv.actions;

import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import net.minecraft.world.phys.Vec3;

public final class GoToAction implements Action {
    static final double LEG_LENGTH = 128, LEG_STEP = 120, LEG_RADIUS = 8, ARRIVE_RADIUS = 1.5;
    private final int x, y, z;
    private boolean started;
    private boolean finalLeg;

    public GoToAction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (!started) {
            started = true;
            startLeg(ctx);
            return ActionStatus.RUNNING;
        }
        NavStatus s = ctx.navigator().status();
        return switch (s.state()) {
            case RUNNING -> ActionStatus.RUNNING;
            case FAILED -> ActionStatus.failed(s.reason());
            case IDLE -> ActionStatus.failed("navigation stopped");
            case ARRIVED -> {
                if (finalLeg) yield ActionStatus.DONE;
                startLeg(ctx);
                yield ActionStatus.RUNNING;
            }
        };
    }

    private void startLeg(BotContext ctx) {
        Vec3 p = ctx.position();
        double tx = x + 0.5, tz = z + 0.5;
        double dx = tx - p.x, dz = tz - p.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > LEG_LENGTH) {
            finalLeg = false;
            ctx.navigator().goTo(new ColumnGoal(p.x + dx / dist * LEG_STEP, p.z + dz / dist * LEG_STEP, LEG_RADIUS));
        } else {
            finalLeg = true;
            ctx.navigator().goTo(new NearGoal(tx, y, tz, ARRIVE_RADIUS));
        }
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "goTo " + x + " " + y + " " + z;
    }

    @Override
    public int timeoutTicks() {
        return 2 * 60 * 20;
    }
}
```

`AiCiv/src/main/java/com/aiciv/actions/GoToPlayerAction.java`:

```java
package com.aiciv.actions;

import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class GoToPlayerAction implements Action {
    static final double ARRIVE = 3.0;
    private final String name;
    private Vec3 anchor;

    public GoToPlayerAction(String name) {
        this.name = name;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        Optional<Vec3> target = ctx.playerPosition(name);
        if (target.isEmpty()) return ActionStatus.failed(name + " is not online in this dimension");
        Vec3 t = target.get();
        if (ctx.position().distanceTo(t) <= ARRIVE) {
            ctx.navigator().stop();
            return ActionStatus.DONE;
        }
        NavStatus s = ctx.navigator().status();
        if (anchor != null && s.state() == NavStatus.State.FAILED) {
            return ActionStatus.failed("can't reach " + name + ": " + s.reason());
        }
        if (anchor == null || anchor.distanceTo(t) > ARRIVE || s.state() != NavStatus.State.RUNNING) {
            anchor = t;
            ctx.navigator().goTo(new NearGoal(t.x, t.y, t.z, ARRIVE - 0.5));
        }
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "goToPlayer " + name;
    }

    @Override
    public int timeoutTicks() {
        return 2 * 60 * 20;
    }
}
```

`AiCiv/src/main/java/com/aiciv/actions/FollowAction.java`:

```java
package com.aiciv.actions;

import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class FollowAction implements Action {
    static final double CLOSE = 3.0;
    private final String name;
    private final int seconds;
    private int ticks;
    private Vec3 anchor;

    public FollowAction(String name, int seconds) {
        this.name = name;
        this.seconds = Math.max(1, Math.min(300, seconds));
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (ticks++ >= seconds * 20) {
            ctx.navigator().stop();
            return ActionStatus.DONE;
        }
        Optional<Vec3> target = ctx.playerPosition(name);
        if (target.isEmpty()) {
            ctx.navigator().stop();
            return ActionStatus.failed(name + " is not online in this dimension");
        }
        Vec3 t = target.get();
        if (ctx.position().distanceTo(t) <= CLOSE) {
            if (anchor != null) {
                ctx.navigator().stop();
                anchor = null;
            }
            return ActionStatus.RUNNING;
        }
        NavStatus s = ctx.navigator().status();
        if (anchor != null && s.state() == NavStatus.State.FAILED) {
            return ActionStatus.failed("can't reach " + name + ": " + s.reason());
        }
        if (anchor == null || anchor.distanceTo(t) > CLOSE || s.state() != NavStatus.State.RUNNING) {
            anchor = t;
            ctx.navigator().goTo(new NearGoal(t.x, t.y, t.z, CLOSE - 0.5));
        }
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "follow " + name + " " + seconds + "s";
    }

    @Override
    public int timeoutTicks() {
        return (seconds + 5) * 20;
    }
}
```

`AiCiv/src/main/java/com/aiciv/actions/StopAction.java`:

```java
package com.aiciv.actions;

/** Stands still. Because a decision replaces the queue, a lone stop also cancels everything else. */
public final class StopAction implements Action {
    @Override
    public ActionStatus tick(BotContext ctx) {
        ctx.navigator().stop();
        return ActionStatus.DONE;
    }

    @Override public String describe() { return "stop"; }
    @Override public int timeoutTicks() { return 20; }
}
```

`AiCiv/src/main/java/com/aiciv/actions/ActionFactory.java`:

```java
package com.aiciv.actions;

import com.aiciv.llm.ToolCall;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import java.util.Set;

/** Validates a model's tool call and builds the action, or explains what was wrong. */
public final class ActionFactory {
    public sealed interface Result {
        record Ok(Action action) implements Result {}
        record Invalid(String reason) implements Result {}
    }

    static final String AVAILABLE = "say, goTo, goToPlayer, follow, stop";
    static final int WORLD_LIMIT = 29_999_984;
    private static final Set<String> KNOWN = Set.of("say", "goto", "gotoplayer", "follow", "stop");

    private ActionFactory() {}

    public static Result create(ToolCall call) {
        String key = call.name() == null ? "" : call.name().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (!KNOWN.contains(key)) return invalid("unknown action '" + call.name() + "'. Available: " + AVAILABLE);
        if (call.argsError() != null) return invalid(call.argsError());
        JsonObject a = call.args();
        switch (key) {
            case "say": {
                String text = str(a, "text");
                if (text == null || SayAction.sanitize(text).isEmpty()) return invalid("say needs non-empty \"text\"");
                return new Result.Ok(new SayAction(text));
            }
            case "goto": {
                Integer x = integer(a, "x"), y = integer(a, "y"), z = integer(a, "z");
                if (x == null || y == null || z == null) return invalid("goTo needs whole numbers x, y and z");
                if (y < -64 || y > 320) return invalid("goTo y must be between -64 and 320");
                if (Math.abs(x) > WORLD_LIMIT || Math.abs(z) > WORLD_LIMIT) return invalid("goTo x and z must be inside the world border");
                return new Result.Ok(new GoToAction(x, y, z));
            }
            case "gotoplayer": {
                String name = str(a, "name");
                if (name == null || name.isBlank()) return invalid("goToPlayer needs a player \"name\"");
                return new Result.Ok(new GoToPlayerAction(name.trim()));
            }
            case "follow": {
                String name = str(a, "name");
                if (name == null || name.isBlank()) return invalid("follow needs a player \"name\"");
                Integer seconds = integer(a, "seconds");
                return new Result.Ok(new FollowAction(name.trim(), seconds == null ? 60 : seconds));
            }
            default:
                return new Result.Ok(new StopAction());
        }
    }

    /** A whole number from a JSON number or numeric string; fractions round down to the containing block. */
    static Integer integer(JsonObject a, String k) {
        JsonElement e = a.get(k);
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        try {
            double d = p.isNumber() ? p.getAsDouble() : Double.parseDouble(p.getAsString().trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return (int) Math.floor(d);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String str(JsonObject a, String k) {
        JsonElement e = a.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Result invalid(String reason) {
        return new Result.Invalid(reason);
    }
}
```

`AiCiv/src/main/java/com/aiciv/actions/ToolCatalog.java`:

```java
package com.aiciv.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** The actions offered to models, in OpenAI function-calling format. */
public final class ToolCatalog {
    private ToolCatalog() {}

    public static JsonArray tools() {
        JsonArray tools = new JsonArray();
        tools.add(fn("say", "Say something in public chat. Players nearby hear it.",
                props("text", "string", "What to say. One short chat message."), "text"));
        JsonObject xyz = new JsonObject();
        xyz.add("x", prop("integer", "Block x (east is positive)."));
        xyz.add("y", prop("integer", "Block y (height)."));
        xyz.add("z", prop("integer", "Block z (south is positive)."));
        tools.add(fn("goTo", "Walk to block coordinates. Finishes within 1.5 blocks of them.", xyz, "x", "y", "z"));
        tools.add(fn("goToPlayer", "Walk to a player until you are within 3 blocks of them.",
                props("name", "string", "The player's exact name."), "name"));
        JsonObject follow = props("name", "string", "The player's exact name.");
        follow.add("seconds", prop("integer", "How long to follow, 1 to 300. Default 60."));
        tools.add(fn("follow", "Follow a player for a while, staying close to them.", follow, "name"));
        tools.add(fn("stop", "Stop what you are doing and stand still.", null));
        return tools;
    }

    public static String guide() {
        return """
                Actions: say, goTo, goToPlayer, follow, stop. Each one ends as done or failed and you will be told which.
                If you cannot call functions, reply with only a JSON array instead, for example:
                [{"action": "say", "args": {"text": "hello"}}, {"action": "goToPlayer", "args": {"name": "Steve"}}]""";
    }

    private static JsonObject fn(String name, String description, JsonObject properties, String... required) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        if (properties != null) {
            JsonObject params = new JsonObject();
            params.addProperty("type", "object");
            params.add("properties", properties);
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            params.add("required", req);
            function.add("parameters", params);
        }
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    private static JsonObject props(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.add(name, prop(type, description));
        return p;
    }

    private static JsonObject prop(String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --no-daemon --tests "com.aiciv.actions.*"`
Expected: PASS (19 tests).

- [ ] **Step 6: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/actions AiCiv/src/main/java/com/aiciv/nav AiCiv/src/test/java/com/aiciv
git commit -m "Validate model actions and run them one at a time with timeouts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Walking in the real world

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/nav/LevelBlockView.java`
- Create: `AiCiv/src/main/java/com/aiciv/nav/PathFollower.java`
- Create: `AiCiv/src/main/java/com/aiciv/nav/LevelNavigator.java`
- Modify: `AiCiv/src/main/java/com/aiciv/gametest/AiCivGameTests.java` (add a test)

**Interfaces:**
- Consumes: `AiPlayer` (Task 1); `PathSearch`, `NavBudget`, `Goal`, `Pos`, `BlockView` (Task 9); `Navigator`, `NavStatus` (Task 10).
- Produces: `new LevelBlockView(ServerLevel)`; `new PathFollower(List<Pos>)` with `Step tick(AiPlayer)` and `enum Step { MOVING, ARRIVED, STUCK }`; `new LevelNavigator(AiPlayer, NavBudget)` implementing `Navigator` plus `void tick()` (call once per server tick before entities tick).

- [ ] **Step 1: Write the failing game test**

Add to `AiCivGameTests` (imports: `com.aiciv.nav.LevelNavigator`, `com.aiciv.nav.NavBudget`, `com.aiciv.nav.NavStatus`, `com.aiciv.nav.NearGoal`, `net.minecraft.core.BlockPos`):

```java
    @GameTest(maxTicks = 400)
    public void navigatorCrossesObstacleCourse(GameTestHelper helper) {
        stoneFloor(helper);
        // Raised ground from x = 5: the bot must jump up one block.
        for (int x = 5; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 1, z, Blocks.STONE);
        // Magma across the straight line: the bot must walk around it.
        for (int z = 0; z <= 2; z++) helper.setBlock(3, 0, z, Blocks.MAGMA_BLOCK);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestNavigator", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(0.5, 1, 1.5)), 0.0F);
        NavBudget budget = new NavBudget(NavBudget.DEFAULT_NANOS, System::nanoTime);
        LevelNavigator nav = new LevelNavigator(bot, budget);
        BlockPos goal = helper.absolutePos(new BlockPos(6, 2, 1));
        nav.goTo(new NearGoal(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.5));
        helper.onEachTick(() -> {
            budget.tick();
            nav.tick();
        });
        helper.succeedWhen(() -> {
            NavStatus s = nav.status();
            helper.assertTrue(s.state() == NavStatus.State.ARRIVED, "navigator is " + s.state() + " " + s.reason());
            helper.assertTrue(bot.getY() > goal.getY() - 0.1, "bot is below the raised ground at y " + bot.getY());
            server.getPlayerList().remove(bot);
        });
    }
```

- [ ] **Step 2: Run it and watch it fail to compile**

Run: `./gradlew runGametest --no-daemon`
Expected: FAIL, `cannot find symbol: class LevelNavigator`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/nav/LevelBlockView.java`:

```java
package com.aiciv.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Reads a loaded level for the pathfinder. Unloaded blocks count as solid and dangerous. */
public final class LevelBlockView implements BlockView {
    private final ServerLevel level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LevelBlockView(ServerLevel level) {
        this.level = level;
    }

    private BlockState at(int x, int y, int z) {
        cursor.set(x, y, z);
        return level.isLoaded(cursor) ? level.getBlockState(cursor) : null;
    }

    @Override
    public boolean passable(int x, int y, int z) {
        BlockState s = at(x, y, z);
        return s != null && s.getCollisionShape(level, cursor).isEmpty();
    }

    @Override
    public boolean standable(int x, int y, int z) {
        BlockState s = at(x, y, z);
        if (s == null) return false;
        VoxelShape shape = s.getCollisionShape(level, cursor);
        // Fences and walls are taller than a block: you cannot stand on them at y + 1.
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
    }

    @Override
    public boolean dangerous(int x, int y, int z) {
        BlockState s = at(x, y, z);
        if (s == null) return true;
        return s.getFluidState().is(FluidTags.LAVA)
                || s.is(BlockTags.FIRE)
                || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.CACTUS)
                || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.CAMPFIRE)
                || s.is(Blocks.SOUL_CAMPFIRE)
                || s.is(Blocks.WITHER_ROSE);
    }
}
```

`AiCiv/src/main/java/com/aiciv/nav/PathFollower.java`:

```java
package com.aiciv.nav;

import com.aiciv.bot.AiPlayer;
import java.util.List;

/** Steers a fake player along a path by setting its facing and movement input each tick. */
public final class PathFollower {
    public enum Step { MOVING, ARRIVED, STUCK }

    static final double REACH = 0.35;
    static final int STUCK_TICKS = 60;

    private final List<Pos> path;
    private int index;
    private double best = Double.MAX_VALUE;
    private int idle;

    public PathFollower(List<Pos> path) {
        this.path = path;
    }

    public Step tick(AiPlayer p) {
        while (index < path.size()) {
            Pos n = path.get(index);
            double dx = n.x() + 0.5 - p.getX();
            double dz = n.z() + 0.5 - p.getZ();
            double dy = n.y() - p.getY();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz < REACH && Math.abs(dy) < 0.6) {
                index++;
                best = Double.MAX_VALUE;
                idle = 0;
                continue;
            }
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.yBodyRot = yaw;
            p.xxa = 0.0F;
            p.zza = 1.0F;
            p.setJumping(p.isInWater() || (p.onGround() && (dy > 0.5 || p.horizontalCollision)));
            double remaining = horiz + Math.abs(dy);
            if (remaining < best - 0.1) {
                best = remaining;
                idle = 0;
            } else if (++idle >= STUCK_TICKS) {
                p.stopMoving();
                return Step.STUCK;
            }
            return Step.MOVING;
        }
        p.stopMoving();
        return Step.ARRIVED;
    }
}
```

`AiCiv/src/main/java/com/aiciv/nav/LevelNavigator.java`:

```java
package com.aiciv.nav;

import com.aiciv.bot.AiPlayer;
import net.minecraft.core.BlockPos;

/** Plans with a time-sliced A* search, then walks the path; replans when stuck. */
public final class LevelNavigator implements Navigator {
    static final int MAX_REPLANS = 3;

    private final AiPlayer player;
    private final NavBudget budget;
    private Goal goal;
    private PathSearch search;
    private PathFollower follower;
    private int replans;
    private NavStatus status = NavStatus.IDLE;

    public LevelNavigator(AiPlayer player, NavBudget budget) {
        this.player = player;
        this.budget = budget;
    }

    @Override
    public void goTo(Goal goal) {
        this.goal = goal;
        replans = 0;
        plan();
    }

    @Override
    public NavStatus status() {
        return status;
    }

    @Override
    public void stop() {
        cancelSearch();
        goal = null;
        follower = null;
        status = NavStatus.IDLE;
        player.stopMoving();
    }

    /** Call once per server tick, before entities tick. */
    public void tick() {
        if (goal == null) return;
        if (goal.reached(feet()) && player.onGround()) {
            arrive();
            return;
        }
        if (search != null) {
            switch (search.state()) {
                case RUNNING -> {
                    return;
                }
                case FAILED -> {
                    fail(search.failure());
                    return;
                }
                case FOUND -> {
                    follower = new PathFollower(search.path());
                    search = null;
                }
            }
        }
        if (follower == null) return;
        switch (follower.tick(player)) {
            case MOVING -> { }
            case ARRIVED -> {
                if (goal.reached(feet())) arrive();
                else replanOrFail();
            }
            case STUCK -> replanOrFail();
        }
    }

    private void plan() {
        cancelSearch();
        follower = null;
        player.stopMoving();
        search = new PathSearch(new LevelBlockView(player.level()), feet(), goal, PathSearch.MAX_NODES);
        budget.submit(search);
        status = NavStatus.RUNNING;
    }

    private void replanOrFail() {
        if (++replans > MAX_REPLANS) {
            BlockPos b = player.blockPosition();
            fail("stuck at " + b.getX() + " " + b.getY() + " " + b.getZ());
        } else {
            plan();
        }
    }

    private void arrive() {
        cancelSearch();
        follower = null;
        goal = null;
        status = NavStatus.ARRIVED;
        player.stopMoving();
    }

    private void fail(String reason) {
        cancelSearch();
        follower = null;
        goal = null;
        status = NavStatus.failed(reason);
        player.stopMoving();
    }

    private void cancelSearch() {
        if (search != null) {
            search.cancel();
            search = null;
        }
    }

    private Pos feet() {
        BlockPos b = player.blockPosition();
        return new Pos(b.getX(), b.getY(), b.getZ());
    }
}
```

- [ ] **Step 4: Run the game tests**

Run: `./gradlew runGametest --no-daemon`
Expected: both game tests pass; `build/gametest/junit.xml` shows `failures="0"`.

If the bot stalls at the step, check that `setJumping(true)` happens while `onGround()` is true: log `p.onGround()`, `dy` and `p.horizontalCollision` from `PathFollower.tick` for one run, fix, then remove the log line.

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/nav AiCiv/src/main/java/com/aiciv/gametest
git commit -m "Walk fake players along planned paths, jumping steps and avoiding magma

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Perceiver

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/perception/Perceiver.java`
- Modify: `AiCiv/src/main/java/com/aiciv/gametest/AiCivGameTests.java` (add a test)

**Interfaces:**
- Consumes: `AiPlayer` (Task 1); `SnapshotData`, `Sighting`, `Compass`, `GameTime`, `SnapshotFormatter` (Task 8).
- Produces: `Perceiver.capture(AiPlayer bot, String currentAction, String lastResult, Predicate<ServerPlayer> isAi) -> SnapshotData`.

- [ ] **Step 1: Write the failing game test**

Add to `AiCivGameTests` (imports: `com.aiciv.perception.Perceiver`, `com.aiciv.perception.SnapshotFormatter`):

```java
    @GameTest(maxTicks = 100)
    public void perceiverSeesExposedBlocksOnly(GameTestHelper helper) {
        stoneFloor(helper);
        helper.setBlock(3, 1, 3, Blocks.CRAFTING_TABLE);
        // A diamond ore with stone on all six sides: it must stay invisible.
        for (int x = 5; x <= 7; x++) for (int y = 1; y <= 3; y++) for (int z = 5; z <= 7; z++) helper.setBlock(x, y, z, Blocks.STONE);
        helper.setBlock(6, 2, 6, Blocks.DIAMOND_ORE);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestPerceiver", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)), 0.0F);
        helper.runAfterDelay(5, () -> {
            String text = SnapshotFormatter.format(
                    Perceiver.capture(bot, "none", "nothing yet", p -> p instanceof AiPlayer), SnapshotFormatter.MAX_CHARS);
            server.getPlayerList().remove(bot);
            helper.assertTrue(text.startsWith("You: TestPerceiver"), text);
            helper.assertTrue(text.contains("crafting_table"), "snapshot misses the crafting table:\n" + text);
            helper.assertTrue(!text.contains("diamond_ore"), "snapshot shows a buried ore:\n" + text);
            helper.succeed();
        });
    }
```

- [ ] **Step 2: Run it and watch it fail to compile**

Run: `./gradlew runGametest --no-daemon`
Expected: FAIL, `cannot find symbol: class Perceiver`.

- [ ] **Step 3: Implement**

`AiCiv/src/main/java/com/aiciv/perception/Perceiver.java`:

```java
package com.aiciv.perception;

import com.aiciv.bot.AiPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Builds a bot's snapshot from the world. Fair: only blocks touching air or water are seen. */
public final class Perceiver {
    static final int BLOCK_RADIUS = 24;
    static final int ENTITY_RADIUS = 32;
    private static final Set<String> UTILITY = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker", "chest", "trapped_chest", "barrel",
            "anvil", "enchanting_table", "brewing_stand");

    private Perceiver() {}

    public static SnapshotData capture(AiPlayer bot, String currentAction, String lastResult, Predicate<ServerPlayer> isAi) {
        ServerLevel level = bot.level();
        BlockPos origin = bot.blockPosition();
        long clock = level.getOverworldClockTime();
        ItemStack held = bot.getInventory().getSelectedItem();
        return new SnapshotData(
                bot.getScoreboardName(), origin.getX(), origin.getY(), origin.getZ(),
                Math.round(bot.getHealth()), Math.round(bot.getMaxHealth()), bot.getFoodData().getFoodLevel(),
                GameTime.day(clock), GameTime.phase(clock), stripNamespace(level.getBiome(origin).getRegisteredName()),
                held.isEmpty() ? "nothing" : itemId(held), inventory(bot),
                blocks(level, origin), players(bot, isAi), mobs(bot),
                currentAction, lastResult);
    }

    static List<String> inventory(AiPlayer bot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) counts.merge(itemId(s), s.getCount(), Integer::sum);
        }
        List<String> out = new ArrayList<>();
        counts.forEach((id, n) -> out.add(n > 1 ? id + " x" + n : id));
        return out;
    }

    static List<Sighting> blocks(ServerLevel level, BlockPos origin) {
        // label -> {count, nearest distance squared, dx, dz}
        Map<String, int[]> seen = new HashMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int r = BLOCK_RADIUS, r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > r2) continue;
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.isLoaded(p)) continue;
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    String label = label(s);
                    if (label == null) continue;
                    boolean fluid = label.equals("water") || label.equals("lava");
                    if (!fluid && !exposed(level, p)) continue;
                    int[] agg = seen.computeIfAbsent(label, k -> new int[] {0, Integer.MAX_VALUE, 0, 0});
                    agg[0]++;
                    if (d2 < agg[1]) {
                        agg[1] = d2;
                        agg[2] = dx;
                        agg[3] = dz;
                    }
                }
            }
        }
        List<Sighting> out = new ArrayList<>();
        seen.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue()[1]))
                .forEach(e -> out.add(new Sighting(e.getKey(), e.getValue()[0],
                        (int) Math.round(Math.sqrt(e.getValue()[1])), Compass.direction(e.getValue()[2], e.getValue()[3]))));
        return out;
    }

    static String label(BlockState s) {
        if (s.is(Blocks.WATER)) return "water";
        if (s.is(Blocks.LAVA)) return "lava";
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (id.endsWith("_ore") || id.equals("ancient_debris") || id.endsWith("_log") || id.endsWith("_bed") || UTILITY.contains(id)) {
            return id;
        }
        return null;
    }

    static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (!level.isLoaded(n)) continue;
            BlockState s = level.getBlockState(n);
            if (s.isAir() || s.getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }

    static List<Sighting> players(AiPlayer bot, Predicate<ServerPlayer> isAi) {
        List<Sighting> out = new ArrayList<>();
        for (ServerPlayer other : bot.level().players()) {
            if (other == bot || other.isSpectator()) continue;
            double d = other.distanceTo(bot);
            if (d > ENTITY_RADIUS) continue;
            String tag = isAi.test(other) ? " [AI]" : " [human]";
            out.add(new Sighting(other.getScoreboardName() + tag, 1, (int) Math.round(d),
                    Compass.direction(other.getX() - bot.getX(), other.getZ() - bot.getZ())));
        }
        out.sort(Comparator.comparingInt(Sighting::distance));
        return out;
    }

    static List<Sighting> mobs(AiPlayer bot) {
        AABB box = bot.getBoundingBox().inflate(ENTITY_RADIUS);
        // id -> {count, nearest distance, dx, dz}
        Map<String, double[]> agg = new HashMap<>();
        for (LivingEntity e : bot.level().getEntitiesOfClass(LivingEntity.class, box, e -> !(e instanceof Player) && e.isAlive())) {
            double d = e.distanceTo(bot);
            if (d > ENTITY_RADIUS) continue;
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            double[] a = agg.computeIfAbsent(id, k -> new double[] {0, Double.MAX_VALUE, 0, 0});
            a[0]++;
            if (d < a[1]) {
                a[1] = d;
                a[2] = e.getX() - bot.getX();
                a[3] = e.getZ() - bot.getZ();
            }
        }
        List<Sighting> out = new ArrayList<>();
        agg.forEach((id, a) -> out.add(new Sighting(id, (int) a[0], (int) Math.round(a[1]), Compass.direction(a[2], a[3]))));
        out.sort(Comparator.comparingInt(Sighting::distance));
        return out;
    }

    static String itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
```

- [ ] **Step 4: Run the game tests**

Run: `./gradlew runGametest --no-daemon`
Expected: three game tests pass.

- [ ] **Step 5: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv/perception AiCiv/src/main/java/com/aiciv/gametest
git commit -m "Let bots see nearby players, mobs and exposed blocks, but never through walls

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Bot manager and the think loop

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/bot/Roster.java`
- Create: `AiCiv/src/main/java/com/aiciv/bot/BotException.java`
- Create: `AiCiv/src/main/java/com/aiciv/bot/Bot.java`
- Create: `AiCiv/src/main/java/com/aiciv/bot/LiveContext.java`
- Create: `AiCiv/src/main/java/com/aiciv/bot/BotManager.java`
- Create: `AiCiv/src/main/java/com/aiciv/bot/BrainScheduler.java`
- Create: `AiCiv/src/main/java/com/aiciv/brain/DecisionLog.java`
- Test: `AiCiv/src/test/java/com/aiciv/bot/RosterTest.java`
- Modify: `AiCiv/src/main/java/com/aiciv/gametest/AiCivGameTests.java` (add two tests)

**Interfaces:**
- Consumes: everything from Tasks 1 to 12.
- Produces: `Roster.load(Path)`, `save(Path)`, `Optional<Entry> get(String)`, `Entry getOrAdd(String)`, `boolean remove(String)`, `List<Entry> all()`, `static Roster empty()`; `Roster.Entry{name, model, persona, goal, paused}`.
- Produces: `new DecisionLog(Path dir)`, `append(String bot, JsonObject record)`.
- Produces: `new BotManager(MinecraftServer, AiCivConfig, LlmService, Path rosterFile, DecisionLog, Clock) throws IOException`; `Bot spawn(String name, String modelKeyOrNull, String personaOrNull, ServerLevel, Vec3) throws BotException`; `remove(String)`, `setGoal(String, String)`, `int setPaused(String targetOrNullForAll, boolean)`, `restoreAll()`, `shutdown()`, `reload(AiCivConfig, LlmService)`, `tickStart()`, `tickEnd()`, `onChat(ServerPlayer, String)`, `onDamage(LivingEntity, DamageSource, float)`, `onDeath(LivingEntity)`, `Optional<Bot> bot(String)`, `Collection<String> botNames()`, `Set<String> modelKeys()`, `List<String> listLines()`, `List<String> statusLines(String) throws BotException`, `List<String> budgetLines()`.
- Produces: `Bot` with final fields `name`, `player`, `brain`, `executor`, `navigator` and field `context`.

- [ ] **Step 1: Write the failing roster test**

`AiCiv/src/test/java/com/aiciv/bot/RosterTest.java`:

```java
package com.aiciv.bot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RosterTest {
    @TempDir Path dir;

    @Test
    void roundTripsAndIgnoresCase() throws Exception {
        Path file = dir.resolve("aiciv").resolve("roster.json");
        Roster r = Roster.load(file);
        assertTrue(r.all().isEmpty());
        Roster.Entry e = r.getOrAdd("Ada");
        e.model = "gemini-flash";
        e.persona = "cheerful";
        e.paused = true;
        r.save(file);
        Roster back = Roster.load(file);
        Roster.Entry ada = back.get("ADA").orElseThrow();
        assertEquals("Ada", ada.name);
        assertEquals("gemini-flash", ada.model);
        assertTrue(ada.paused);
        assertEquals("", ada.goal);
        assertSame(back.getOrAdd("ada"), ada);
        assertTrue(back.remove("ada"));
        assertTrue(back.all().isEmpty());
        assertFalse(Files.exists(dir.resolve("aiciv").resolve("roster.json.tmp")));
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew test --no-daemon --tests "com.aiciv.bot.RosterTest"`
Expected: FAIL, `cannot find symbol: class Roster`.

- [ ] **Step 3: Implement Roster, DecisionLog, BotException, Bot**

`AiCiv/src/main/java/com/aiciv/bot/Roster.java`:

```java
package com.aiciv.bot;

import com.aiciv.config.AiCivConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The bots that belong to this world, saved as <world>/aiciv/roster.json. */
public final class Roster {
    public static final class Entry {
        public String name;
        public String model;
        public String persona = "";
        public String goal = "";
        public boolean paused;

        public Entry() {}

        Entry(String name) {
            this.name = name;
        }
    }

    private final List<Entry> entries;

    private Roster(List<Entry> entries) {
        this.entries = entries;
    }

    public static Roster empty() {
        return new Roster(new ArrayList<>());
    }

    public static Roster load(Path file) throws IOException {
        if (Files.notExists(file)) return empty();
        Entry[] arr = AiCivConfig.GSON.fromJson(Files.readString(file), Entry[].class);
        List<Entry> list = new ArrayList<>();
        if (arr != null) {
            for (Entry e : arr) {
                if (e == null || e.name == null) continue;
                if (e.persona == null) e.persona = "";
                if (e.goal == null) e.goal = "";
                list.add(e);
            }
        }
        return new Roster(list);
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, AiCivConfig.GSON.toJson(entries));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Optional<Entry> get(String name) {
        return entries.stream().filter(e -> e.name.equalsIgnoreCase(name)).findFirst();
    }

    public Entry getOrAdd(String name) {
        return get(name).orElseGet(() -> {
            Entry e = new Entry(name);
            entries.add(e);
            return e;
        });
    }

    public boolean remove(String name) {
        return entries.removeIf(e -> e.name.equalsIgnoreCase(name));
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }
}
```

`AiCiv/src/main/java/com/aiciv/brain/DecisionLog.java`:

```java
package com.aiciv.brain;

import com.aiciv.AiCiv;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One JSON line per decision in logs/aiciv/<bot>.jsonl. */
public final class DecisionLog {
    private final Path dir;

    public DecisionLog(Path dir) {
        this.dir = dir;
    }

    public void append(String bot, JsonObject record) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(bot + ".jsonl"), record + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AiCiv.LOG.warn("Could not write the AiCiv decision log for {}", bot, e);
        }
    }

    public Path fileFor(String bot) {
        return dir.resolve(bot + ".jsonl");
    }
}
```

`AiCiv/src/main/java/com/aiciv/bot/BotException.java`:

```java
package com.aiciv.bot;

/** A command could not be carried out; the message is shown to the player who ran it. */
public final class BotException extends Exception {
    public BotException(String message) {
        super(message);
    }
}
```

`AiCiv/src/main/java/com/aiciv/bot/Bot.java`:

```java
package com.aiciv.bot;

import com.aiciv.actions.ActionExecutor;
import com.aiciv.actions.BotContext;
import com.aiciv.brain.BotBrain;
import com.aiciv.nav.LevelNavigator;

/** One online AI player and everything that drives it. */
public final class Bot {
    public final String name;
    public final AiPlayer player;
    public final BotBrain brain;
    public final ActionExecutor executor;
    public final LevelNavigator navigator;
    public BotContext context;

    Bot(String name, AiPlayer player, BotBrain brain, ActionExecutor executor, LevelNavigator navigator) {
        this.name = name;
        this.player = player;
        this.brain = brain;
        this.executor = executor;
        this.navigator = navigator;
    }
}
```

- [ ] **Step 4: Run the roster test**

Run: `./gradlew test --no-daemon --tests "com.aiciv.bot.RosterTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing game tests for the full loop**

Add to `AiCivGameTests` (imports: `com.aiciv.bot.Bot`, `com.aiciv.bot.BotManager`, `com.aiciv.brain.DecisionLog`, `com.aiciv.config.AiCivConfig`, `com.aiciv.llm.LlmService`, `com.aiciv.llm.ScriptedBackend`, `com.google.gson.JsonObject`, `com.google.gson.JsonParser`, `java.nio.file.Files`, `java.nio.file.Path`, `java.time.Clock`, `java.util.List`, `java.util.Map`):

```java
    static AiCivConfig scriptedConfig() {
        return AiCivConfig.GSON.fromJson("""
                {"providers": {"scripted": {"type": "scripted", "script": "unused.json"}},
                 "models": {"scripted": {"provider": "scripted", "model": "script"}},
                 "defaultModel": "scripted"}""", AiCivConfig.class);
    }

    static BotManager scriptedManager(GameTestHelper helper, ScriptedBackend backend, Path dir) throws Exception {
        AiCivConfig config = scriptedConfig();
        LlmService llm = new LlmService(config, Map.of("scripted", backend), Clock.systemUTC());
        return new BotManager(helper.getLevel().getServer(), config, llm,
                dir.resolve("roster.json"), new DecisionLog(dir.resolve("logs")), Clock.systemUTC());
    }

    @GameTest(maxTicks = 400)
    public void scriptedBotSaysHelloAndWalks(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        BlockPos target = helper.absolutePos(new BlockPos(6, 1, 6));
        JsonObject turn = JsonParser.parseString("""
                {"content": "Let me look around.", "tool_calls": [
                  {"id": "1", "type": "function", "function": {"name": "say", "arguments": "{\\"text\\": \\"hello world\\"}"}},
                  {"id": "2", "type": "function", "function": {"name": "goTo", "arguments": "{\\"x\\": %d, \\"y\\": %d, \\"z\\": %d}"}}]}"""
                .formatted(target.getX(), target.getY(), target.getZ())).getAsJsonObject();
        ScriptedBackend backend = new ScriptedBackend(List.of(turn));
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = scriptedManager(helper, backend, dir);
        Bot bot = manager.spawn("TestScripted", "scripted", "a test bot", helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        helper.onEachTick(() -> {
            manager.tickStart();
            manager.tickEnd();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.player.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.6,
                    "bot is at " + bot.player.position() + ", target " + target);
            helper.assertTrue(backend.requests().size() >= 1, "no LLM request was made");
            String system = backend.requests().get(0).getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            helper.assertTrue(system.contains("You are TestScripted") && system.contains("a test bot"), system);
            String log;
            try {
                log = Files.readString(dir.resolve("logs").resolve("TestScripted.jsonl"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            helper.assertTrue(log.contains("\"goTo\"") && log.contains("Let me look around."), log);
            try {
                manager.remove("TestScripted");
            } catch (com.aiciv.bot.BotException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void botDeathLogsOutWithoutMoreCalls(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        ScriptedBackend backend = new ScriptedBackend(List.of());
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = scriptedManager(helper, backend, dir);
        Bot bot = manager.spawn("TestDies", "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        helper.onEachTick(() -> {
            manager.tickStart();
            manager.tickEnd();
        });
        helper.runAfterDelay(5, () -> {
            bot.player.kill(helper.getLevel());
            // The global AiCiv listener only feeds the global manager, so call ours directly.
            manager.onDeath(bot.player);
        });
        helper.runAfterDelay(10, () -> {
            int requestsAfterDeath = backend.requests().size();
            helper.runAfterDelay(60, () -> {
                helper.assertTrue(manager.bot("TestDies").isEmpty(), "dead bot is still managed");
                helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayerByName("TestDies") == null, "dead bot is still in the player list");
                helper.assertTrue(backend.requests().size() == requestsAfterDeath, "LLM was called after death");
                helper.assertTrue(manager.listLines().get(0).startsWith("TestDies [scripted] offline"), manager.listLines().get(0));
                helper.succeed();
            });
        });
    }
```

- [ ] **Step 6: Run and watch them fail to compile**

Run: `./gradlew runGametest --no-daemon`
Expected: FAIL, `cannot find symbol: class BotManager`.

- [ ] **Step 7: Implement LiveContext, BotManager, BrainScheduler**

`AiCiv/src/main/java/com/aiciv/bot/LiveContext.java`:

```java
package com.aiciv.bot;

import com.aiciv.actions.BotContext;
import com.aiciv.nav.Navigator;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class LiveContext implements BotContext {
    private final BotManager manager;
    private final Bot bot;

    LiveContext(BotManager manager, Bot bot) {
        this.manager = manager;
        this.bot = bot;
    }

    @Override
    public Vec3 position() {
        return bot.player.position();
    }

    @Override
    public Optional<Vec3> playerPosition(String name) {
        ServerPlayer p = bot.player.level().getServer().getPlayerList().getPlayerByName(name);
        if (p == null || p.level() != bot.player.level() || p.isSpectator()) return Optional.empty();
        return Optional.of(p.position());
    }

    @Override
    public Navigator navigator() {
        return bot.navigator;
    }

    @Override
    public void say(String text) {
        manager.botSays(bot, text);
    }
}
```

`AiCiv/src/main/java/com/aiciv/bot/BotManager.java`:

```java
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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private final Set<String> warnedProviders = new HashSet<>();

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
        warnedProviders.clear();
    }

    /** Tells online operators, once per provider, that its key is missing or was rejected. Never shows the key. */
    void warnOpsOnce(String provider, String reason) {
        if (!warnedProviders.add(provider)) return;
        AiCiv.LOG.warn("AiCiv provider {} is disabled: {}", provider, reason);
        Component msg = Component.literal("[AiCiv] Provider '" + provider + "' is disabled: " + reason
                + ". Fix keys.json, then run /ai reload.").withStyle(ChatFormatting.RED);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Commands.LEVEL_GAMEMASTERS.check(p.permissions())) p.sendSystemMessage(msg);
        }
    }

    // ---------------------------------------------------------------- commands

    public Bot spawn(String name, String modelKey, String persona, ServerLevel level, Vec3 pos) throws BotException {
        if (!NAME.matcher(name).matches()) throw new BotException("Bot names must be 3 to 16 letters, digits or _");
        if (server.getPlayerList().getPlayerByName(name) != null) throw new BotException(name + " is already online");
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
            if (b.player.isDeadOrDying()) continue;
            b.navigator.tick();
            String ended = b.executor.tick(b.context);
            if (ended != null) b.brain.actionsEnded(stamp(), ended);
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

    /** Milestone 1 has no respawn: a dead bot leaves with health restored so /ai spawn brings it back. */
    public void onDeath(LivingEntity entity) {
        if (!(entity instanceof AiPlayer p)) return;
        Bot b = bots.get(key(p.getScoreboardName()));
        if (b == null || b.player != p) return;
        queue.remove(b.name);
        b.executor.stop(b.context);
        server.execute(() -> {
            if (bots.get(key(b.name)) != b) return;
            p.setHealth(p.getMaxHealth());
            logout(b);
            AiCiv.LOG.info("{} died and left. /ai spawn {} brings them back.", b.name, b.name);
        });
    }

    // ---------------------------------------------------------------- internals

    private Bot join(Roster.Entry e) {
        AiPlayer[] holder = new AiPlayer[1];
        AiPlayer p = AiPlayer.join(server, e.name, () -> server.execute(() -> handleDisconnect(e.name, holder[0])));
        holder[0] = p;
        BotBrain brain = new BotBrain(e.name, e.persona, e.goal, e.model, config.eventWindow,
                config.idleWakeSeconds * 1000L, config.mentionCooldownSeconds * 1000L, clock.millis());
        brain.setPaused(e.paused);
        Bot b = new Bot(e.name, p, brain, new ActionExecutor(), new LevelNavigator(p, navBudget));
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

    private void handleDisconnect(String name, AiPlayer player) {
        Bot b = bots.get(key(name));
        if (b != null && b.player == player) logout(b);
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
            out.add(p.getScoreboardName() + (p instanceof AiPlayer ? " (AI)" : " (human)"));
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
```

`AiCiv/src/main/java/com/aiciv/bot/BrainScheduler.java`:

```java
package com.aiciv.bot;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.SayAction;
import com.aiciv.actions.ToolCatalog;
import com.aiciv.brain.PromptBuilder;
import com.aiciv.brain.ThinkQueue;
import com.aiciv.brain.WakeReason;
import com.aiciv.llm.LlmService;
import com.aiciv.llm.ToolCall;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotFormatter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** Wakes bots, gives queued bots their LLM turn when a provider is free, and applies the replies. */
final class BrainScheduler {
    private final BotManager m;

    BrainScheduler(BotManager m) {
        this.m = m;
    }

    void tick(long now) {
        for (Bot b : m.bots()) {
            if (b.player.isDeadOrDying()) continue;
            WakeReason r = b.brain.pollWake(now, b.executor.busy());
            if (r != null) m.queue().offer(b.name, r, now);
        }
        while (true) {
            Optional<ThinkQueue.Ticket> ticket = m.queue().pollFirst(name -> m.bot(name)
                    .map(b -> m.llm().peek(b.brain.modelKey()).isPresent())
                    .orElse(true));
            if (ticket.isEmpty()) return;
            Optional<Bot> bot = m.bot(ticket.get().bot());
            if (bot.isEmpty() || bot.get().player.isDeadOrDying()) continue;
            if (!think(bot.get(), ticket.get(), now)) return;
        }
    }

    private boolean think(Bot b, ThinkQueue.Ticket ticket, long now) {
        String snapshot = SnapshotFormatter.format(
                Perceiver.capture(b.player, b.executor.currentDescription(), b.brain.lastDecision(), p -> p instanceof AiPlayer),
                SnapshotFormatter.MAX_CHARS);
        String system = PromptBuilder.system(b.name, b.brain.persona(), b.brain.goal(), m.othersFor(b),
                m.config().maxActionsPerDecision, ToolCatalog.guide());
        String user = PromptBuilder.user(List.of(), b.brain.events().lines(), snapshot);
        Optional<CompletableFuture<LlmService.Outcome>> sent = m.llm().trySend(b.brain.modelKey(), system, user, ToolCatalog.tools());
        if (sent.isEmpty()) {
            m.queue().offer(b.name, ticket.reason(), ticket.since());
            return false;
        }
        b.brain.dispatched(now);
        int snapshotChars = snapshot.length();
        sent.get().whenComplete((outcome, err) -> m.server().execute(() -> apply(b, ticket, snapshotChars, outcome, err)));
        return true;
    }

    private void apply(Bot b, ThinkQueue.Ticket ticket, int snapshotChars, LlmService.Outcome outcome, Throwable err) {
        if (m.bot(b.name).orElse(null) != b) return; // removed or replaced while thinking
        JsonObject log = new JsonObject();
        log.addProperty("time", Instant.ofEpochMilli(m.clock().millis()).toString());
        log.addProperty("trigger", ticket.reason().name());
        log.addProperty("snapshotChars", snapshotChars);
        if (outcome != null) log.addProperty("provider", outcome.route().providerKey());

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
        int max = m.config().maxActionsPerDecision;
        List<Action> actions = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        JsonArray calls = new JsonArray();
        for (ToolCall c : reply.reply().calls()) {
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
        if (actions.isEmpty() && reply.reply().plainText() != null) actions.add(new SayAction(reply.reply().plainText()));
        log.add("calls", calls);
        if (reply.reply().plainText() != null) log.addProperty("text", reply.reply().plainText());
        JsonArray problemsJson = new JsonArray();
        problems.forEach(problemsJson::add);
        log.add("problems", problemsJson);

        String stamp = m.stamp();
        if (!problems.isEmpty()) b.brain.note(stamp, "problems with your last turn: " + String.join("; ", problems));
        if (actions.isEmpty()) {
            b.brain.note(stamp, "your last turn did nothing");
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
```

- [ ] **Step 8: Run all tests and game tests**

Run: `./gradlew test runGametest --no-daemon`
Expected: all unit tests pass; five game tests pass.

- [ ] **Step 9: Commit**

```bash
git add AiCiv/src/main/java/com/aiciv AiCiv/src/test/java/com/aiciv/bot
git commit -m "Run the think loop: wake, ask the model, act, and remember the result

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Commands, mod wiring, README and the live run

**Files:**
- Create: `AiCiv/src/main/java/com/aiciv/bot/AiCommands.java`
- Modify: `AiCiv/src/main/java/com/aiciv/AiCiv.java` (replace the body)
- Create: `AiCiv/README.md`
- Modify: `docs/superpowers/specs/2026-10-11-aiciv-design.md` (record the deliberate changes from Global Constraints)

**Interfaces:**
- Consumes: `BotManager` and everything it needs (Task 13); `AiCivConfig`, `KeyStore` (Task 2); `LlmService` (Task 6); `DecisionLog` (Task 13).
- Produces: `AiCiv.manager() -> BotManager` (null while no server runs); `AiCiv.reload() throws IOException -> List<String>` (config problems); `AiCommands.register(CommandDispatcher<CommandSourceStack>)`.

- [ ] **Step 1: Write the command tree**

`AiCiv/src/main/java/com/aiciv/bot/AiCommands.java`:

```java
package com.aiciv.bot;

import com.aiciv.AiCiv;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/** /ai spawn | remove | list | goal | pause | resume | status | budget | reload (needs op level 2). */
public final class AiCommands {
    @FunctionalInterface
    private interface BotOp {
        String apply(BotManager m) throws BotException;
    }

    @FunctionalInterface
    private interface LinesOp {
        List<String> apply(BotManager m) throws BotException;
    }

    private AiCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ai")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> spawn(c, null, null))
                                .then(Commands.argument("model", StringArgumentType.word())
                                        .suggests(AiCommands::suggestModels)
                                        .executes(c -> spawn(c, StringArgumentType.getString(c, "model"), null))
                                        .then(Commands.argument("persona", StringArgumentType.greedyString())
                                                .executes(c -> spawn(c, StringArgumentType.getString(c, "model"),
                                                        unquote(StringArgumentType.getString(c, "persona"))))))))
                .then(Commands.literal("remove").then(botName().executes(c -> run(c, m -> {
                    m.remove(name(c));
                    return "Removed " + name(c);
                }))))
                .then(Commands.literal("list").executes(c -> lines(c, BotManager::listLines)))
                .then(Commands.literal("goal").then(botName()
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> run(c, m -> {
                            m.setGoal(name(c), unquote(StringArgumentType.getString(c, "text")));
                            return "Goal set for " + name(c);
                        })))))
                .then(Commands.literal("pause")
                        .executes(c -> pause(c, null, true))
                        .then(Commands.argument("target", StringArgumentType.word()).suggests(AiCommands::suggestBotsAndAll)
                                .executes(c -> pause(c, StringArgumentType.getString(c, "target"), true))))
                .then(Commands.literal("resume")
                        .executes(c -> pause(c, null, false))
                        .then(Commands.argument("target", StringArgumentType.word()).suggests(AiCommands::suggestBotsAndAll)
                                .executes(c -> pause(c, StringArgumentType.getString(c, "target"), false))))
                .then(Commands.literal("status").then(botName().executes(c -> lines(c, m -> m.statusLines(name(c))))))
                .then(Commands.literal("budget").executes(c -> lines(c, BotManager::budgetLines)))
                .then(Commands.literal("reload").executes(AiCommands::reload)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> botName() {
        return Commands.argument("name", StringArgumentType.word()).suggests(AiCommands::suggestBots);
    }

    private static String name(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "name");
    }

    private static int spawn(CommandContext<CommandSourceStack> c, String model, String persona) {
        return run(c, m -> {
            CommandSourceStack s = c.getSource();
            Bot b = m.spawn(name(c), model, persona, s.getLevel(), s.getPosition());
            return "Spawned " + b.name + " using " + b.brain.modelKey();
        });
    }

    private static int pause(CommandContext<CommandSourceStack> c, String target, boolean paused) {
        return run(c, m -> (paused ? "Paused " : "Resumed ") + m.setPaused(target, paused) + " AI player(s)");
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        try {
            List<String> problems = AiCiv.reload();
            c.getSource().sendSuccess(() -> Component.literal("Reloaded aiciv.json and keys.json"), true);
            for (String p : problems) c.getSource().sendFailure(Component.literal("aiciv.json: " + p));
            return 1;
        } catch (IOException | RuntimeException e) {
            c.getSource().sendFailure(Component.literal("Reload failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int run(CommandContext<CommandSourceStack> c, BotOp op) {
        BotManager m = AiCiv.manager();
        if (m == null) {
            c.getSource().sendFailure(Component.literal("AiCiv is not running; check the server log"));
            return 0;
        }
        try {
            String msg = op.apply(m);
            c.getSource().sendSuccess(() -> Component.literal(msg), true);
            return 1;
        } catch (BotException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int lines(CommandContext<CommandSourceStack> c, LinesOp op) {
        BotManager m = AiCiv.manager();
        if (m == null) {
            c.getSource().sendFailure(Component.literal("AiCiv is not running; check the server log"));
            return 0;
        }
        try {
            for (String line : op.apply(m)) c.getSource().sendSuccess(() -> Component.literal(line), false);
            return 1;
        } catch (BotException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggestModels(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        BotManager m = AiCiv.manager();
        return SharedSuggestionProvider.suggest(m == null ? List.of() : m.modelKeys(), b);
    }

    private static CompletableFuture<Suggestions> suggestBots(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        BotManager m = AiCiv.manager();
        return SharedSuggestionProvider.suggest(m == null ? List.of() : m.botNames(), b);
    }

    private static CompletableFuture<Suggestions> suggestBotsAndAll(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        BotManager m = AiCiv.manager();
        List<String> options = new ArrayList<>();
        options.add("all");
        if (m != null) options.addAll(m.botNames());
        return SharedSuggestionProvider.suggest(options, b);
    }

    static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t;
    }
}
```

- [ ] **Step 2: Wire the mod entrypoint**

Replace `AiCiv/src/main/java/com/aiciv/AiCiv.java` with:

```java
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

    @Override
    public void onInitialize() {
        configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        ServerLifecycleEvents.SERVER_STARTED.register(AiCiv::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            BotManager m = manager;
            if (m != null) m.tickStart();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
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

    /** The running server's bot manager, or null when no server is running. */
    public static BotManager manager() {
        return manager;
    }

    /** Re-reads aiciv.json and keys.json. Returns config problems; empty when clean. */
    public static List<String> reload() throws IOException {
        BotManager m = manager;
        if (m == null) throw new IOException("AiCiv is not running");
        Loaded l = loadServices();
        m.reload(l.config(), l.llm());
        return l.config().validate();
    }

    private static void start(MinecraftServer server) {
        try {
            Loaded l = loadServices();
            Path roster = server.getWorldPath(LevelResource.ROOT).resolve("aiciv").resolve("roster.json");
            DecisionLog log = new DecisionLog(FabricLoader.getInstance().getGameDir().resolve("logs").resolve("aiciv"));
            manager = new BotManager(server, l.config(), l.llm(), roster, log, Clock.systemUTC());
            manager.restoreAll();
        } catch (IOException | RuntimeException e) {
            LOG.error("AiCiv failed to start; /ai commands are unavailable", e);
        }
    }

    private static void stop() {
        BotManager m = manager;
        manager = null;
        if (m != null) m.shutdown();
    }

    private record Loaded(AiCivConfig config, LlmService llm) {}

    private static Loaded loadServices() throws IOException {
        AiCivConfig config = AiCivConfig.loadOrCreate(configDir.resolve("aiciv.json"));
        for (String problem : config.validate()) LOG.warn("aiciv.json: {}", problem);
        KeyStore keys = KeyStore.load(configDir.resolve("keys.json"), System::getenv);
        LlmService llm = LlmService.create(config, keys, configDir, Clock.systemUTC());
        for (String line : llm.budgetLines()) {
            if (line.contains("disabled")) LOG.warn("AiCiv provider {}", line);
        }
        return new Loaded(config, llm);
    }
}
```

- [ ] **Step 3: Build and run every test**

Run: `./gradlew build test runGametest --no-daemon`
Expected: BUILD SUCCESSFUL; all unit tests and all five game tests pass; `build/libs/aiciv-0.1.0.jar` exists.

- [ ] **Step 4: Write the README**

`AiCiv/README.md`:

````markdown
# AiCiv

AI players for Minecraft 26.3, each driven by a language model (Gemini, Groq, OpenRouter,
GitHub Models, Mistral, Cerebras, OpenCode Zen or a local Ollama model). They join as normal
players, chat, and walk around. Milestone 1 of the design in
`docs/superpowers/specs/2026-10-11-aiciv-design.md`.

Server-side only: viewers join with an unmodded 26.3 client. Works in singleplayer too.

## Install

Put `aiciv-0.1.0.jar` and Fabric API 0.161.0+26.3 in `mods/` of a Fabric 26.3 server or client.

## Keys

First start writes `config/aiciv/aiciv.json` and an empty `config/aiciv/keys.json`.
Put keys in `keys.json`, never in `aiciv.json`:

```json
{ "gemini": "your-key", "groq": "your-key" }
```

Or set environment variables instead: `AICIV_KEY_GEMINI`, `AICIV_KEY_GROQ`, and so on.
Run `/ai reload` after changing either file.

## Providers and limits

`aiciv.json` lists providers (`baseUrl`, `key`, `rpm`, `rpd`, `resetZone`, `maxInFlight`)
and models (`provider`, `model`, `fallback`, `maxTokens`, `extra`). The limits in a fresh
file are examples: replace them with your real tier limits. `0` means unlimited. A model
whose provider is down, rate limited or out of daily budget hands its turn to its
`fallback` model.

If a Gemini model keeps returning empty replies, it is spending its token budget on
thinking: raise `maxTokens` or add `"extra": {"reasoning_effort": "none"}` to the model.

## Commands (op level 2)

| Command | What it does |
|---------|--------------|
| `/ai spawn <name> [model] [persona...]` | Adds an AI player at your position |
| `/ai remove <name>` | Removes it from the world and the roster |
| `/ai list` | Every AI player and what it is doing |
| `/ai goal <name> <text>` | Sets its long-term goal |
| `/ai pause [name\|all]`, `/ai resume [name\|all]` | Stops or restarts thinking and acting |
| `/ai status <name>` | Details for one AI player |
| `/ai budget` | Requests used and left per provider, and provider health |
| `/ai reload` | Re-reads `aiciv.json` and `keys.json` |

AI players notice chat within 64 blocks, and any chat that names them from anywhere.

## Logs

Every decision goes to `logs/aiciv/<name>.jsonl`: what woke the bot, which provider and model
answered, the model's thought, the actions, and any problems.

## Testing without spending quota

A provider with `"type": "scripted"` and `"script": "scripted.json"` replays assistant
messages from `config/aiciv/scripted.json` in order, for example
`[{"content": null, "tool_calls": [{"function": {"name": "say", "arguments": "{\"text\": \"hi\"}"}}]}]`.

Developers: `./gradlew test` runs the unit tests, `./gradlew runGametest` the in-game tests.

## Milestone 1 limits

No mining, crafting or building yet, and no respawn: a bot that dies leaves the world, and
`/ai spawn <name>` brings it back.
````

- [ ] **Step 5: Record the deliberate spec changes**

Append to `docs/superpowers/specs/2026-10-11-aiciv-design.md`:

```markdown
## Changes made during milestone 1

- `max_tokens` defaults to 1000, not 400, with per-model `maxTokens` and `extra` request
  fields. Gemini thinking models count reasoning tokens against `max_tokens`.
- Hurt wakes have a 10 s cooldown, so damage over time costs one call per 10 s at most.
- A reply made only of `say` calls is spoken without cancelling the current action.
- `/ai spawn <name>` without a model reuses the roster model or `defaultModel`.
- Until milestone 2 adds respawning, a bot that dies leaves the world with health
  restored; `/ai spawn <name>` brings it back.
```

- [ ] **Step 6: Commit**

```bash
git add AiCiv docs/superpowers/specs/2026-10-11-aiciv-design.md
git commit -m "Add /ai commands, wire AiCiv into the server, and document setup

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Live smoke test with Gemini (needs the user)**

This is the milestone 1 acceptance test from the spec. It needs a human in the game, so hand these steps to the user and wait for their report.

1. The user starts the client once to create the config files: `./gradlew runClient --no-daemon`, then quits.
2. The user puts their Gemini key in `AiCiv/run/config/aiciv/keys.json` as `{"gemini": "..."}`, or sets `AICIV_KEY_GEMINI` with `setx AICIV_KEY_GEMINI "..."` in a new terminal. Do not type the key for them and do not echo it back.
3. Check the model id still exists. With the key in the environment:

```bash
curl -s -H "Authorization: Bearer $AICIV_KEY_GEMINI" https://generativelanguage.googleapis.com/v1beta/openai/models | grep '"id"'
```

   If `gemini-2.5-flash` is not listed, set `models.gemini-flash.model` in `AiCiv/run/config/aiciv/aiciv.json` to the newest `flash` id that is. Do the same for any other provider the user has a key for.
4. The user runs `./gradlew runClient --no-daemon`, creates a singleplayer world with cheats on, and runs `/ai spawn Ada gemini-flash "a cheerful explorer"`.
5. Expected:
   - Ada appears in the tab list and says something in chat within about 5 seconds.
   - Typing `Ada, come here` in chat makes her answer and walk to the player.
   - `/ai budget` shows `gemini: 2 used today` or more and status `ok`.
   - `AiCiv/run/logs/aiciv/Ada.jsonl` has one line per decision with `"provider":"gemini"`.
6. If the decision log shows `"error"` lines:
   - `HTTP 400 (key rejected)` or `disabled: no key`: the key is wrong or missing; fix it and run `/ai reload`.
   - Empty replies (`"calls":[]` and no `text`): raise `maxTokens` or add `"extra": {"reasoning_effort": "none"}` to the model, then `/ai reload`.
   - `HTTP 400` mentioning `parameters` or `tools`: report the exact message. The `stop` tool has no `parameters`, and some providers want `{"type": "object", "properties": {}}` instead.

Record the result (pass, or the exact failure) in the final report. Milestone 1 is done when step 5 passes.
