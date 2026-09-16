package com.animeviewer.service.ai;

import com.animeviewer.service.ServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * v0.22 AI0 AI 设置：yml（av.ai.*）提供默认值，SQLite settings 表存 JSON 覆盖（key=ai），
 * 前端设置页读写；字段缺失/类型不符时回退默认（损坏 JSON 整体回退，对齐 SubscriptionSettings 同款模式）。
 *
 * <ul>
 *   <li>enabled 总开关（默认关——AI 关闭/失败时全链路行为与 v0.21 一致，零回归不变式）</li>
 *   <li>baseUrl OpenAI 兼容接口根地址（含 /v1，如 https://api.openai.com/v1 或 http://127.0.0.1:11434/v1）</li>
 *   <li>model 模型名；apiKey 可空（本地 Ollama 无鉴权）</li>
 *   <li>maxCallsPerHour 小时滚动配额护栏（0=不限；超限静默跳过判定，不影响主链路）</li>
 *   <li>autoIgnoreNonEpisode AI1 自动忽略非本篇命中（默认关——误判可清历史重评，谨慎开启）</li>
 * </ul>
 */
public record AiSettings(
        boolean enabled, String baseUrl, String model, String apiKey,
        int timeoutSeconds, int maxCallsPerHour, boolean autoIgnoreNonEpisode) {

    public static final String STORE_KEY = "ai";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AiSettings defaults(ServiceProperties props) {
        ServiceProperties.Ai s = props.ai();
        return new AiSettings(
                s.enabled() != null && s.enabled(),
                or(s.baseUrl(), ""),
                or(s.model(), ""),
                or(s.apiKey(), ""),
                clamp(s.timeoutSeconds() == null ? 30 : s.timeoutSeconds(), 5, 120),
                Math.max(0, s.maxCallsPerHour() == null ? 60 : s.maxCallsPerHour()),
                s.autoIgnoreNonEpisode() != null && s.autoIgnoreNonEpisode());
    }

    public static AiSettings load(String storedJson, AiSettings defaults) {
        if (storedJson == null || storedJson.isBlank()) return defaults;
        try {
            JsonNode n = MAPPER.readTree(storedJson);
            return new AiSettings(
                    boolOf(n, "enabled", defaults.enabled()),
                    or(textOf(n, "baseUrl"), defaults.baseUrl()),
                    or(textOf(n, "model"), defaults.model()),
                    or(textOf(n, "apiKey"), defaults.apiKey()),
                    clamp(intOf(n, "timeoutSeconds", defaults.timeoutSeconds()), 5, 120),
                    Math.max(0, intOf(n, "maxCallsPerHour", defaults.maxCallsPerHour())),
                    boolOf(n, "autoIgnoreNonEpisode", defaults.autoIgnoreNonEpisode()));
        } catch (Exception e) {
            return defaults;
        }
    }

    public String toJson() {
        try {
            var node = MAPPER.createObjectNode();
            node.put("enabled", enabled);
            node.put("baseUrl", baseUrl);
            node.put("model", model);
            node.put("apiKey", apiKey);
            node.put("timeoutSeconds", timeoutSeconds);
            node.put("maxCallsPerHour", maxCallsPerHour);
            node.put("autoIgnoreNonEpisode", autoIgnoreNonEpisode);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 校验（控制器层转 400）；返回错误消息，null = 通过 */
    public String validate() {
        if (baseUrl.isBlank() && enabled) return "已启用 AI 时必须填写接口地址";
        if (enabled && model.isBlank()) return "已启用 AI 时必须填写模型名";
        if (!baseUrl.isBlank() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return "接口地址需以 http(s):// 开头";
        }
        if (timeoutSeconds < 5 || timeoutSeconds > 120) return "超时需在 5~120 秒";
        if (maxCallsPerHour < 0 || maxCallsPerHour > 10_000) return "每小时调用上限需在 0~10000（0=不限）";
        return null;
    }

    /** AI 是否就绪（可发起判定）：开 + 地址 + 模型 */
    public boolean ready() {
        return enabled && !baseUrl.isBlank() && !model.isBlank();
    }

    private static String or(String v, String def) {
        return v == null ? def : v;
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

    private static String textOf(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
