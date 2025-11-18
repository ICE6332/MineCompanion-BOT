package com.aicompanion.network.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.UUID;

/**
 * 紧凑JSON消息构造器
 * 使用缩写字段减少传输体积
 */
public class CompactMessage {
    
    /**
     * 构造对话请求消息
     * @param playerName 玩家名
     * @param text 消息内容
     * @return 紧凑格式JSON: {i, t, p, m}
     */
    public static JsonObject conversation(String playerName, String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("i", UUID.randomUUID().toString());
        msg.addProperty("t", "cr");  // conversation_request
        msg.addProperty("p", playerName);
        msg.addProperty("m", text);
        return msg;
    }
    
    /**
     * 构造游戏状态消息
     * @param player 玩家实体
     * @return 紧凑格式JSON: {t, p, hp, pos}
     */
    public static JsonObject gameState(ServerPlayerEntity player) {
        JsonObject msg = new JsonObject();
        msg.addProperty("t", "gs");  // game_state_update
        msg.addProperty("p", player.getName().getString());
        msg.addProperty("hp", (int)player.getHealth());
        
        // 位置用数组（省空间）
        JsonArray pos = new JsonArray();
        pos.add(player.getBlockX());
        pos.add(player.getBlockY());
        pos.add(player.getBlockZ());
        msg.add("pos", pos);
        
        return msg;
    }
}

