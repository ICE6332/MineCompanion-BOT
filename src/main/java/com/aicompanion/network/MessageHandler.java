package com.aicompanion.network;

import com.aicompanion.config.AICompanionConfig;
import com.aicompanion.player.AIFakePlayerManager;
import com.aicompanion.player.AIPlayerController;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息处理器
 * 负责处理从 AI Service 接收到的消息
 */
public class MessageHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MessageHandler");

    private MinecraftServer server;

    public MessageHandler() {
        // No longer needs AIFakePlayerManager instance - uses static methods
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    @FunctionalInterface
    private interface MessageAction {
        void run(MinecraftServer server);
    }

    /**
     * 处理接收到的 JSON 消息
     */
    public void handleMessage(String jsonMessage) {
        try {
            JsonObject json = JsonParser.parseString(jsonMessage).getAsJsonObject();

            if (!json.has("type")) {
                LOGGER.warn("Message missing 'type' field");
                return;
            }

            String type = json.get("type").getAsString();
            MessageAction action = prepareAction(type, json);

            if (action == null) {
                return;
            }

            // 必须在主线程执行游戏操作
            if (server != null) {
                server.execute(() -> {
                    try {
                        action.run(server);
                    } catch (Exception e) {
                        LOGGER.error("Error handling message of type: {}", type, e);
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse message:{}", jsonMessage, e);
        }
    }

    private MessageAction prepareAction(String type, JsonObject json) {
        return switch (type) {
            case "action_command" -> json.has("data")
                ? prepareActionCommand(json.getAsJsonObject("data"))
                : missingField("action_command", "data");
            case "conversation_message" -> json.has("data")
                ? prepareConversation(json.getAsJsonObject("data"))
                : missingField("conversation_message", "data");
            case "conversation_response" -> {
                JsonObject payload = json.has("data") ? json.getAsJsonObject("data") : json;
                yield prepareConversationResponse(payload);
            }
            case "config_sync" -> json.has("data")
                ? (srv) -> handleConfigSync(json.getAsJsonObject("data"))
                : missingField("config_sync", "data");
            case "connection_ack", "connection_accept" -> (srv) -> {
                LOGGER.info("Connection accepted by AI Service");
                NotificationManager.getInstance().sendConnectionSuccess();
            };
            case "game_state_ack" -> (srv) -> LOGGER.debug("Game state acknowledged by AI Service");
            case "error_notification" -> (srv) -> handleError(json.getAsJsonObject("data"));
            default -> {
                LOGGER.warn("Unknown message type: {} " , type);
                yield null;
            }
        };
    }

    /**
     * 处理动作指令
     */
    private MessageAction prepareActionCommand(JsonObject data) {
        if (!data.has("companionName") || !data.has("action")) {
            LOGGER.warn("action_command missing required fields");
            return null;
        }

        String companionName = data.get("companionName").getAsString();
        String action = data.get("action").getAsString();

        if (AICompanionConfig.getInstance().isDebugMode()) {
            LOGGER.debug("Preparing action: {} for {}", action, companionName);
        }

        return switch (action) {
            case "follow" -> data.has("target")
                ? (srv) -> handleFollowAction(srv, companionName, data.get("target").getAsString())
                : missingField("follow", "target");
            case "look" -> data.has("target")
                ? (srv) -> handleLookAction(srv, companionName, data.get("target").getAsString())
                : missingField("look", "target");
            case "stop" -> (srv) -> handleStopAction(srv, companionName);
            case "move_to" -> data.has("position")
                ? createMoveAction(companionName, data.getAsJsonObject("position"))
                : missingField("move_to", "position");
            case "jump" -> (srv) -> handleJumpAction(srv, companionName);
            case "mine_block" -> data.has("position")
                ? createMineAction(companionName, data.getAsJsonObject("position"))
                : missingField("mine_block", "position");
            case "place_block" -> data.has("position")
                ? createPlaceAction(companionName, data.getAsJsonObject("position"))
                : missingField("place_block", "position");
            case "use_item" -> (srv) -> handleUseItemAction(srv, companionName);
            case "attack_entity" -> data.has("targetPlayer")
                ? (srv) -> handleAttackAction(srv, companionName, data.get("targetPlayer").getAsString())
                : missingField("attack_entity", "targetPlayer");
            default -> {
                LOGGER.warn("Unknown action: {} " , action);
                yield null;
            }
        };
    }

    private MessageAction missingField(String action, String field) {
        LOGGER.warn("{} action missing '{}' field", action, field);
        return null;
    }

    private MessageAction createMoveAction(String companionName, JsonObject posObj) {
        double x = posObj.get("x").getAsDouble();
        double y = posObj.get("y").getAsDouble();
        double z = posObj.get("z").getAsDouble();

        return server -> {
            AIPlayerController controller = findController(companionName);
            if (controller == null) {
                return;
            }

            controller
                .getMovementController()
                .moveTo(new net.minecraft.util.math.Vec3d(x, y, z));
            LOGGER.info("{} moving to position ({}, {}, {})", companionName, x, y, z);
        };
    }

    private MessageAction createMineAction(String companionName, JsonObject posObj) {
        int x = posObj.get("x").getAsInt();
        int y = posObj.get("y").getAsInt();
        int z = posObj.get("z").getAsInt();
        BlockPos pos = new BlockPos(x, y, z);

        return server -> {
            AIPlayerController controller = findController(companionName);
            if (controller == null) {
                return;
            }
            controller.getInteractionController().mineBlock(pos);
            LOGGER.info("{} mining block at ({}, {}, {})", companionName, x, y, z);
        };
    }

    private MessageAction createPlaceAction(String companionName, JsonObject posObj) {
        int x = posObj.get("x").getAsInt();
        int y = posObj.get("y").getAsInt();
        int z = posObj.get("z").getAsInt();
        BlockPos pos = new BlockPos(x, y, z);

        return server -> {
            AIPlayerController controller = findController(companionName);
            if (controller == null) {
                return;
            }
            controller.getInteractionController().placeBlock(pos);
            LOGGER.info("{} placing block at ({}, {}, {})", companionName, x, y, z);
        };
    }

    private void handleFollowAction(MinecraftServer srv, String companionName, String targetName) {
        AIPlayerController controller = findController(companionName);
        if (controller == null) {
            return;
        }

        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(targetName);
        if (target != null) {
            controller.getMovementController().setFollowTarget(target);
            LOGGER.info("{} now following {}", companionName, targetName);
        } else {
            LOGGER.warn("Follow target player not found: {}", targetName);
        }
    }

    private void handleLookAction(MinecraftServer srv, String companionName, String targetName) {
        AIPlayerController controller = findController(companionName);
        if (controller == null) {
            return;
        }

        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(targetName);
        if (target != null) {
            controller.getViewController().setLookTarget(target);
            LOGGER.info("{} now looking at {}", companionName, targetName);
        } else {
            LOGGER.warn("Look target player not found: {}", targetName);
        }
    }

    private void handleStopAction(MinecraftServer srv, String companionName) {
        AIPlayerController controller = findController(companionName);
        if (controller == null) {
            return;
        }

        controller.getMovementController().stop();
        controller.getMovementController().setFollowTarget(null);
        controller.getViewController().stopLooking();
        LOGGER.info("{} stopped all behaviors", companionName);
    }

    private void handleJumpAction(MinecraftServer srv, String companionName) {
        AIPlayerController controller = findController(companionName);
        if (controller == null) {
            return;
        }
        controller.getMovementController().jump();
        LOGGER.info("{} jumping", companionName);
    }

    private void handleUseItemAction(MinecraftServer srv, String companionName) {
        AIPlayerController controller = findController(companionName);
        if (controller == null) {
            return;
        }
        controller.getInteractionController().useItemInAir();
        LOGGER.info("{} using item in air", companionName);
    }

    private void handleAttackAction(MinecraftServer srv, String companionName, String targetName) {
        AIPlayerController controller = findController(companionName);
        if (controller == null) {
            return;
        }

        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(targetName);
        if (target != null) {
            controller.getCombatController().setTarget(target);
            LOGGER.info("{} targeting player {} for attack", companionName, targetName);
        } else {
            LOGGER.warn("attack_entity target player not found: {}", targetName);
        }
    }

    private AIPlayerController findController(String companionName) {
        AIPlayerController controller = AIFakePlayerManager.getPlayerByName(companionName);
        if (controller == null) {
            LOGGER.warn("AI companion not found: {}", companionName);
        }
        return controller;
    }

    /**
     * 处理对话响应（新协议）
     * 优先兼容文档中的 conversation_response 结构：
     * {
     *   "type": "conversation_response",
     *   "data": {
     *     "ai": "AICompanion",
     *     "message": "内容"
     *   }
     * }
     * 同时兼容旧的 conversation_message 结构：
     * {
     *   "companionName": "...",
     *   "text": "..."
     * }
     */
    private MessageAction prepareConversationResponse(JsonObject data) {
        List<String> commands = parseActionCommands(data);

        JsonObject normalized = new JsonObject();
        if (data.has("c") && data.has("m")) {
            normalized.addProperty("companionName", data.get("c").getAsString());
            normalized.addProperty("text", data.get("m").getAsString());
            LOGGER.debug("Parsed compact conversation_response");
        } else if (data.has("companionName") && data.has("message")) {
            normalized.addProperty("companionName", data.get("companionName").getAsString());
            normalized.addProperty("text", data.get("message").getAsString());
            LOGGER.debug("Parsed standard conversation_response (new format)");
        } else if (data.has("ai") && data.has("message")) {
            normalized.addProperty("companionName", data.get("ai").getAsString());
            normalized.addProperty("text", data.get("message").getAsString());
            LOGGER.debug("Parsed standard conversation_response (old format)");
        } else if (data.has("companionName") && data.has("text")) {
            normalized = data;
        } else {
            LOGGER.warn("conversation_response missing required fields, received: {}", data.toString());
            return null;
        }

        String companionName = normalized.get("companionName").getAsString();
        String text = normalized.get("text").getAsString();

        return server -> {
            executeCommands(commands, server);
            handleConversation(server, companionName, text);
        };
    }

    /**
     * 处理 AI 返回的动作列表。
     *
     * @param data JSON 数组，包含 action 或 a 字段的动作对象
     */
    private List<String> parseActionCommands(JsonObject data) {
        com.google.gson.JsonArray actions = null;
        if (data.has("action") && data.get("action").isJsonArray()) {
            actions = data.getAsJsonArray("action");
        } else if (data.has("a") && data.get("a").isJsonArray()) {
            actions = data.getAsJsonArray("a");
        }

        List<String> commands = new ArrayList<>();
        if (actions == null || actions.isEmpty()) {
            return commands;
        }

        for (int i = 0; i < actions.size(); i++) {
            if (!actions.get(i).isJsonObject()) {
                LOGGER.warn("Action at index {} is not an object", i);
                continue;
            }

            JsonObject actionObj = actions.get(i).getAsJsonObject();
            String type = null;

            if (actionObj.has("type")) {
                type = actionObj.get("type").getAsString();
            } else if (actionObj.has("t")) {
                type = actionObj.get("t").getAsString();
            }

            if (type == null) {
                LOGGER.warn("Action at index {} missing type field", i);
                continue;
            }

            if ("command".equals(type)) {
                String command = null;
                if (actionObj.has("command")) {
                    command = actionObj.get("command").getAsString();
                } else if (actionObj.has("c")) {
                    command = actionObj.get("c").getAsString();
                }

                if (command == null || command.isBlank()) {
                    LOGGER.warn("Command action at index {} missing 'command' text", i);
                    continue;
                }

                commands.add(command.trim());
            } else {
                LOGGER.warn("Unsupported action type '{}' at index {}", type, i);
            }
        }

        return commands;
    }

    /**
     * 执行经过白名单的服务器命令。
     *
     * @param rawCommand 完整命令字符串（可含前导 /）
     */
    private void executeServerCommand(String rawCommand, MinecraftServer srv) {
        if (srv == null) {
            LOGGER.warn("Server is null, cannot execute command: {}", rawCommand);
            return;
        }

        String command = rawCommand.trim();
        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        String[] parts = command.split("\\s+");
        if (parts.length == 0) {
            LOGGER.warn("Empty command received from AI actions");
            return;
        }

        String base = parts[0].toLowerCase(java.util.Locale.ROOT);

        try {
            switch (base) {
                case "/follow":
                    if (parts.length < 3) {
                        LOGGER.warn("Follow command missing arguments: {}", command);
                        return;
                    }
                    handleFollowCommand(srv, parts[1], parts[2]);
                    break;
                case "/stop":
                    if (parts.length < 2) {
                        LOGGER.warn("Stop command missing companion name: {}", command);
                        return;
                    }
                    handleStopCommand(srv, parts[1]);
                    break;
                case "/say":
                    handleSayCommand(srv, command.substring(4).trim());
                    break;
                case "/look":
                    if (parts.length < 3) {
                        LOGGER.warn("Look command missing arguments: {}", command);
                        return;
                    }
                    handleLookCommand(srv, parts[1], parts[2]);
                    break;
                default:
                    LOGGER.warn("Rejected non-whitelisted command from AI: {}", command);
            }
        } catch (Exception e) {
            LOGGER.error("Error executing AI command: {}", command, e);
        }
    }

    private void handleFollowCommand(MinecraftServer srv, String companionName, String targetName) {
        AIPlayerController controller = AIFakePlayerManager.getPlayerByName(companionName);
        if (controller == null) {
            LOGGER.warn("Companion not found for follow: {}", companionName);
            return;
        }

        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(targetName);
        if (target == null) {
            LOGGER.warn("Follow target player not found: {}", targetName);
            return;
        }

        controller.getMovementController().setFollowTarget(target);
        LOGGER.info("{} now following {}", companionName, targetName);
    }

    private void handleStopCommand(MinecraftServer srv, String companionName) {
        AIPlayerController controller = AIFakePlayerManager.getPlayerByName(companionName);
        if (controller == null) {
            LOGGER.warn("Companion not found for stop: {}", companionName);
            return;
        }

        controller.getMovementController().stop();
        controller.getMovementController().setFollowTarget(null);
        controller.getViewController().stopLooking();
        LOGGER.info("{} stopped all behaviors", companionName);
    }

    private void handleSayCommand(MinecraftServer srv, String message) {
        if (message.isBlank()) {
            LOGGER.warn("Say command missing message content");
            return;
        }
        srv.getPlayerManager().broadcast(Text.literal(message), false);
        LOGGER.info("Broadcasted AI message: {}", message);
    }

    private void handleLookCommand(MinecraftServer srv, String companionName, String targetName) {
        AIPlayerController controller = AIFakePlayerManager.getPlayerByName(companionName);
        if (controller == null) {
            LOGGER.warn("Companion not found for look: {}", companionName);
            return;
        }

        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(targetName);
        if (target == null) {
            LOGGER.warn("Look target player not found: {}", targetName);
            return;
        }

        controller.getViewController().setLookTarget(target);
        LOGGER.info("{} now looking at {}", companionName, targetName);
    }

    /**
     * 处理对话消息
     */
    private MessageAction prepareConversation(JsonObject data) {
        if (!data.has("text")) {
            LOGGER.warn("conversation_message missing 'text' field");
            return null;
        }

        boolean hasCompanionField = data.has("companionName");
        String companionName = hasCompanionField
            ? data.get("companionName").getAsString()
            : "AICompanion";
        String text = data.get("text").getAsString();

        return server -> handleConversation(server, companionName, text, hasCompanionField);
    }

    private void handleConversation(MinecraftServer srv, String companionName, String text) {
        handleConversation(srv, companionName, text, true);
    }

    private void handleConversation(MinecraftServer srv, String companionName, String text, boolean hasCompanionField) {
        if (srv == null) {
            LOGGER.warn(
                "Server is null, cannot deliver conversation for {}.",
                companionName
            );
            return;
        }

        boolean sentViaCommand = false;
        String fallbackReason = hasCompanionField
            ? "companion not found"
            : "companion field missing";

        if (!hasCompanionField) {
            LOGGER.warn(
                "conversation_message missing 'companionName', defaulting to broadcast"
            );
        } else {
            AIPlayerController controller = AIFakePlayerManager.getPlayerByName(
                companionName
            );

            if (controller != null) {
                fallbackReason = "command execution failed";
                try {
                    srv
                        .getCommandManager()
                        .getDispatcher()
                        .execute(
                            "say " + text,
                            controller.getFakePlayer().getCommandSource()
                        );
                    sentViaCommand = true;
                    LOGGER.info(
                        "AI chat sent via fake player command [{}]: {}",
                        companionName,
                        text
                    );
                } catch (CommandSyntaxException e) {
                    fallbackReason = "command syntax error";
                    LOGGER.warn(
                        "Failed to execute chat command for {}: {}",
                        companionName,
                        e.getMessage()
                    );
                } catch (Exception e) {
                    fallbackReason = "unexpected command error";
                    LOGGER.warn(
                        "Unexpected error executing chat command for {}",
                        companionName,
                        e
                    );
                }
            } else {
                LOGGER.warn("Companion not found for conversation: {}", companionName);
            }
        }

        if (!sentViaCommand) {
            srv.getPlayerManager().broadcast(
                Text.literal("<" + companionName + "> " + text),
                false
            );
            LOGGER.info(
                "AI chat broadcast via server fallback [{}] (reason: {}): {}",
                companionName,
                fallbackReason,
                text
            );
        }
    }

    private void executeCommands(List<String> commands, MinecraftServer srv) {
        if (commands.isEmpty()) {
            return;
        }
        for (String cmd : commands) {
            executeServerCommand(cmd, srv);
        }
    }

    /**
     * 处理配置同步
     */
    private void handleConfigSync(JsonObject data) {
        LOGGER.info("Config sync received: {} " , data.toString());
        // 未来扩展：运行时配置更新
    }

    /**
     * 处理错误通知
     */
    private void handleError(JsonObject data) {
        if (data.has("message")) {
            LOGGER.error("AI Service error: {} " , data.get("message").getAsString());
        }
    }
}
