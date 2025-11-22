package com.aicompanion.state;

import com.aicompanion.config.AICompanionConfig;
import com.aicompanion.network.ConnectionManager;
import com.aicompanion.player.AIFakePlayerManager;
import com.aicompanion.player.AIPlayerController;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 游戏状态收集器
 * 单例模式，负责收集游戏状态并发送给 AI Service
 */
public class GameStateCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameStateCollector");
    private static GameStateCollector instance;
    private static final double POSITION_THRESHOLD = 0.1;
    private static final int FORCE_SEND_MULTIPLIER = 10;

    private MinecraftServer server;
    private int tickCounter = 0;
    private final Map<UUID, CachedState> cachedStates = new HashMap<>();

    private GameStateCollector() {
    }

    public static GameStateCollector getInstance() {
        if (instance == null) {
            instance = new GameStateCollector();
        }
        return instance;
    }

    /**
     * 初始化收集器
     */
    public void initialize(MinecraftServer server) {
        this.server = server;
        LOGGER.info("GameStateCollector initialized");
    }

    /**
     * 每个游戏 Tick 调用一次
     */
    public void tick() {
        if (server == null || !AICompanionConfig.getInstance().isWebSocketEnabled()) {
            return;
        }

        tickCounter++;

        int interval = AICompanionConfig.getInstance().getStateUpdateIntervalTicks();
        if (tickCounter >= interval) {
            tickCounter = 0;
            collectAndSend();
        }
    }

    /**
     * 收集状态并发送到 AI Service
     */
    private void collectAndSend() {
        if (!ConnectionManager.getInstance().isConnected()) {
            return;
        }

        try {
            HashSet<UUID> activePlayers = new HashSet<>();
            // 为每个真实玩家收集状态（有 AI 伙伴跟随的玩家）
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                // 检查该玩家是否有 AI 伙伴
                if (hasCompanions(player)) {
                    UUID uuid = player.getUuid();
                    activePlayers.add(uuid);

                    CachedState cached = cachedStates.get(uuid);
                    if (cached == null) {
                        GameStateData state = collectStateForPlayer(player);
                        cached = new CachedState(state);
                        cachedStates.put(uuid, cached);
                        cached.pendingChanges++; // ensure first send
                    } else {
                        cached.pendingChanges += updateStateForPlayer(cached.state, player);
                    }

                    cached.idleIntervals++;

                    if (cached.pendingChanges > 0 || cached.idleIntervals >= FORCE_SEND_MULTIPLIER) {
                        cached.state.timestamp = System.currentTimeMillis() / 1000;
                        ConnectionManager.getInstance().sendGameState(cached.state);
                        cached.pendingChanges = 0;
                        cached.idleIntervals = 0;
                    }
                }
            }

            // 清理无活跃伙伴的缓存
            cachedStates.keySet().removeIf(uuid -> !activePlayers.contains(uuid));
        } catch (Exception e) {
            LOGGER.error("Error collecting game state", e);
        }
    }

    private int updateStateForPlayer(GameStateData state, ServerPlayerEntity player) {
        int changes = 0;

        if (state.player == null) {
            state.player = collectPlayerState(player);
            changes++;
        } else {
            changes += updatePlayerState(state.player, player) ? 1 : 0;
        }

        if (state.environment == null) {
            state.environment = collectEnvironmentState((ServerWorld) player.getEntityWorld());
            changes++;
        } else {
            changes += updateEnvironmentState(state.environment, (ServerWorld) player.getEntityWorld()) ? 1 : 0;
        }

        changes += updateCompanionStates(state.companions, player);

        return changes;
    }

    /**
     * 检查玩家是否有 AI 伙伴
     */
    private boolean hasCompanions(ServerPlayerEntity player) {
        // 遍历所有 AI 玩家，检查是否有跟随该玩家的
        for (String aiName : AIFakePlayerManager.getAllPlayerNames()) {
            AIPlayerController controller = AIFakePlayerManager.getPlayerByName(aiName);
            if (controller != null &&
                controller.getMovementController().isFollowing() &&
                controller.getMovementController().getFollowTarget() == player) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集指定玩家的完整游戏状态
     */
    private GameStateData collectStateForPlayer(ServerPlayerEntity player) {
        GameStateData state = new GameStateData();

        state.player = collectPlayerState(player);
        state.companions = collectCompanionStates(player);
        // In Minecraft 1.21.10, use getEntityWorld() method
        state.environment = collectEnvironmentState((ServerWorld) player.getEntityWorld());

        return state;
    }

    private boolean updatePlayerState(PlayerStateData data, ServerPlayerEntity player) {
        boolean changed = false;

        if (!data.username.equals(player.getName().getString())) {
            data.username = player.getName().getString();
            changed = true;
        }

        double health = player.getHealth();
        if (Double.compare(data.health, health) != 0) {
            data.health = health;
            changed = true;
        }

        int hunger = player.getHungerManager().getFoodLevel();
        if (data.hunger != hunger) {
            data.hunger = hunger;
            changed = true;
        }

        if (data.position == null) {
            data.position = new Position(player.getX(), player.getY(), player.getZ());
            changed = true;
        } else {
            changed |= updatePosition(data.position, player.getX(), player.getY(), player.getZ());
        }

        Identifier dimensionId = ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue();
        String dimension = dimensionId.toString();
        if (!dimension.equals(data.dimension)) {
            data.dimension = dimension;
            changed = true;
        }

        String gameMode = player.interactionManager.getGameMode().name().toLowerCase();
        if (!gameMode.equals(data.gameMode)) {
            data.gameMode = gameMode;
            changed = true;
        }

        // 目前保持 lookingAt 简化版
        return changed;
    }

    /**
     * 收集玩家状态
     */
    private PlayerStateData collectPlayerState(ServerPlayerEntity player) {
        PlayerStateData data = new PlayerStateData();

        data.username = player.getName().getString();
        data.health = player.getHealth();
        data.hunger = player.getHungerManager().getFoodLevel();
        data.position = new Position(
            player.getX(),
            player.getY(),
            player.getZ()
        );

        Identifier dimensionId = ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue();
        data.dimension = dimensionId.toString();
        data.gameMode = player.interactionManager.getGameMode().name().toLowerCase();
        data.lookingAt = "unknown";

        return data;
    }

    /**
     * 收集 AI 伙伴状态
     */
    private List<AIStateData> collectCompanionStates(ServerPlayerEntity player) {
        List<AIStateData> companions = new ArrayList<>();

        // 遍历所有 AI 玩家
        for (String aiName : AIFakePlayerManager.getAllPlayerNames()) {
            AIPlayerController controller = AIFakePlayerManager.getPlayerByName(aiName);

            if (controller == null) {
                continue;
            }

            // 只收集跟随该玩家的 AI
            if (controller.getMovementController().isFollowing() &&
                controller.getMovementController().getFollowTarget() == player) {

                PlayerEntity aiPlayer = controller.getFakePlayer();
                if (aiPlayer != null) {
                    AIStateData data = new AIStateData();
                    data.name = aiName;
                    data.position = new Position(
                        aiPlayer.getX(),
                        aiPlayer.getY(),
                        aiPlayer.getZ()
                    );
                    data.health = aiPlayer.getHealth();
                    data.currentAction = determineCurrentAction(controller);

                    companions.add(data);
                }
            }
        }

        return companions;
    }

    private int updateCompanionStates(List<AIStateData> cachedCompanions, ServerPlayerEntity player) {
        Map<String, AIStateData> cachedByName = new HashMap<>();
        for (AIStateData cached : cachedCompanions) {
            cachedByName.put(cached.name, cached);
        }

        List<AIStateData> updated = new ArrayList<>();
        int changes = 0;

        for (String aiName : AIFakePlayerManager.getAllPlayerNames()) {
            AIPlayerController controller = AIFakePlayerManager.getPlayerByName(aiName);

            if (controller == null ||
                !controller.getMovementController().isFollowing() ||
                controller.getMovementController().getFollowTarget() != player) {
                continue;
            }

            PlayerEntity aiPlayer = controller.getFakePlayer();
            if (aiPlayer == null) {
                continue;
            }

            AIStateData existing = cachedByName.get(aiName);
            if (existing == null) {
                AIStateData data = new AIStateData();
                data.name = aiName;
                data.position = new Position(
                    aiPlayer.getX(),
                    aiPlayer.getY(),
                    aiPlayer.getZ()
                );
                data.health = aiPlayer.getHealth();
                data.currentAction = determineCurrentAction(controller);
                updated.add(data);
                changes++;
            } else {
                boolean changed = false;
                if (existing.position == null) {
                    existing.position = new Position(aiPlayer.getX(), aiPlayer.getY(), aiPlayer.getZ());
                    changed = true;
                } else {
                    changed |= updatePosition(existing.position, aiPlayer.getX(), aiPlayer.getY(), aiPlayer.getZ());
                }

                double health = aiPlayer.getHealth();
                if (Double.compare(existing.health, health) != 0) {
                    existing.health = health;
                    changed = true;
                }

                String action = determineCurrentAction(controller);
                if (!action.equals(existing.currentAction)) {
                    existing.currentAction = action;
                    changed = true;
                }

                updated.add(existing);
                if (changed) {
                    changes++;
                }
            }
        }

        if (cachedCompanions.size() != updated.size()) {
            changes++;
        }

        cachedCompanions.clear();
        cachedCompanions.addAll(updated);

        return changes;
    }

    /**
     * 判断 AI 当前的动作
     */
    private String determineCurrentAction(AIPlayerController controller) {
        if (controller.getMovementController().isFollowing()) {
            return "following";
        } else if (controller.getViewController().isLooking()) {
            return "looking";
        } else {
            return "idle";
        }
    }

    /**
     * 收集环境状态
     */
    private EnvironmentStateData collectEnvironmentState(ServerWorld world) {
        if (!AICompanionConfig.getInstance().isCollectEnvironment()) {
            return new EnvironmentStateData("unknown", "unknown", "unknown");
        }

        EnvironmentStateData data = new EnvironmentStateData();
        updateEnvironmentState(data, world);
        return data;
    }

    private boolean updateEnvironmentState(EnvironmentStateData data, ServerWorld world) {
        if (!AICompanionConfig.getInstance().isCollectEnvironment()) {
            return false;
        }

        boolean changed = false;

        String time;
        long timeOfDay = world.getTimeOfDay() % 24000;
        if (timeOfDay < 12000) {
            time = "day";
        } else if (timeOfDay < 13000) {
            time = "sunset";
        } else if (timeOfDay < 23000) {
            time = "night";
        } else {
            time = "sunrise";
        }

        if (!time.equals(data.time)) {
            data.time = time;
            changed = true;
        }

        String weather;
        if (world.isThundering()) {
            weather = "thunder";
        } else if (world.isRaining()) {
            weather = "rain";
        } else {
            weather = "clear";
        }

        if (!weather.equals(data.weather)) {
            data.weather = weather;
            changed = true;
        }

        // 生物群系（简化版）
        String biome = "unknown";
        if (!biome.equals(data.biome)) {
            data.biome = biome;
            changed = true;
        }

        return changed;
    }

    private boolean updatePosition(Position position, double x, double y, double z) {
        double dx = Math.abs(position.x - x);
        double dy = Math.abs(position.y - y);
        double dz = Math.abs(position.z - z);

        if (dx > POSITION_THRESHOLD || dy > POSITION_THRESHOLD || dz > POSITION_THRESHOLD) {
            position.x = x;
            position.y = y;
            position.z = z;
            return true;
        }

        return false;
    }

    private static class CachedState {
        final GameStateData state;
        int pendingChanges = 0;
        int idleIntervals = 0;

        CachedState(GameStateData state) {
            this.state = state;
        }
    }
}
