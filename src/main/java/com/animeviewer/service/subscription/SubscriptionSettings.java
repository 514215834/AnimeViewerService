package com.animeviewer.service.subscription;

import com.animeviewer.service.ServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** v0.19 SU1 订阅设置：yml（av.subscription.*）提供默认值，SQLite settings 表存 JSON 覆盖（key=subscription），
 *  前端设置页读写；字段缺失/类型不符时回退默认（损坏 JSON 整体回退，对齐 DownloadSettings 同款模式）。
 *  全自动为条目级显式开关（subscriptions.auto 列），此处仅承载全局检索与三重保护配置。 */
public record SubscriptionSettings(
        int intervalMinutes, int minSizeMb, int autoDailyLimit, int autoMaxSizeMb, boolean autoOnlyMatched) {

    public static final String STORE_KEY = "subscription";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static SubscriptionSettings defaults(ServiceProperties props) {
        ServiceProperties.Subscription s = props.subscription();
        return new SubscriptionSettings(
                clamp(s.intervalMinutes() == null ? 60 : s.intervalMinutes(), 30, 360),
                Math.max(0, s.minSizeMb() == null ? 0 : s.minSizeMb()),
                Math.max(0, s.autoDailyLimit() == null ? 5 : s.autoDailyLimit()),
                Math.max(0, s.autoMaxSizeMb() == null ? 0 : s.autoMaxSizeMb()),
                s.autoOnlyMatched());
    }

    public static SubscriptionSettings load(String storedJson, SubscriptionSettings defaults) {
        if (storedJson == null || storedJson.isBlank()) return defaults;
        try {
            JsonNode n = MAPPER.readTree(storedJson);
            return new SubscriptionSettings(
                    clamp(intOf(n, "intervalMinutes", defaults.intervalMinutes()), 30, 360),
                    Math.max(0, intOf(n, "minSizeMb", defaults.minSizeMb())),
                    Math.max(0, intOf(n, "autoDailyLimit", defaults.autoDailyLimit())),
                    Math.max(0, intOf(n, "autoMaxSizeMb", defaults.autoMaxSizeMb())),
                    boolOf(n, "autoOnlyMatched", defaults.autoOnlyMatched()));
        } catch (Exception e) {
            return defaults;
        }
    }

    public String toJson() {
        try {
            var node = MAPPER.createObjectNode();
            node.put("intervalMinutes", intervalMinutes);
            node.put("minSizeMb", minSizeMb);
            node.put("autoDailyLimit", autoDailyLimit);
            node.put("autoMaxSizeMb", autoMaxSizeMb);
            node.put("autoOnlyMatched", autoOnlyMatched);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 校验（控制器层转 400）；返回错误消息，null = 通过 */
    public String validate() {
        if (intervalMinutes < 30 || intervalMinutes > 360) return "检索间隔需在 30~360 分钟";
        if (minSizeMb < 0 || minSizeMb > 1_000_000) return "大小下限需在 0~1000000 MB";
        if (autoDailyLimit < 0 || autoDailyLimit > 1000) return "每日自动入队上限需在 0~1000";
        if (autoMaxSizeMb < 0 || autoMaxSizeMb > 10_000_000) return "单任务大小上限需在 0~10000000 MB";
        return null;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int intOf(JsonNode n, String field, int def) {
        JsonNode v = n.get(field);
        return v != null && v.isInt() ? v.asInt() : def;
    }

    private static boolean boolOf(JsonNode n, String field, boolean def) {
        JsonNode v = n.get(field);
        return v != null && v.isBoolean() ? v.asBoolean() : def;
    }
}
