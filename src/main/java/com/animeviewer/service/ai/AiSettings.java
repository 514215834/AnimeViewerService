package com.animeviewer.service.ai;

import com.animeviewer.service.ServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

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
 *   <li>maxTokens 单请求 max_tokens 上限（0=不注入；v0.30 A5——思考型模型勿配过小，否则 content 被截空）</li>
 *   <li>aiBindThreshold 媒体库 AI 解析自动绑定阈值（0=关闭仅预填；v0.30 A7——置信度达标直接绑定）</li>
 * </ul>
 */
public record AiSettings(
        boolean enabled, String baseUrl, String model, String apiKey,
        int timeoutSeconds, int maxCallsPerHour, boolean autoIgnoreNonEpisode,
        String extraHeaders, int maxTokens, int aiBindThreshold) {

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
                s.autoIgnoreNonEpisode() != null && s.autoIgnoreNonEpisode(),
                or(s.extraHeaders(), ""),
                Math.max(0, s.maxTokens() == null ? 512 : s.maxTokens()),
                clamp(s.aiBindThreshold() == null ? 85 : s.aiBindThreshold(), 0, 100));
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
                    boolOf(n, "autoIgnoreNonEpisode", defaults.autoIgnoreNonEpisode()),
                    or(textOf(n, "extraHeaders"), defaults.extraHeaders()),
                    Math.max(0, intOf(n, "maxTokens", defaults.maxTokens())),
                    clamp(intOf(n, "aiBindThreshold", defaults.aiBindThreshold()), 0, 100));
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
            node.put("extraHeaders", extraHeaders);
            node.put("maxTokens", maxTokens);
            node.put("aiBindThreshold", aiBindThreshold);
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
        if (maxTokens < 0 || maxTokens > 32_768) return "max_tokens 需在 0~32768（0=不注入，由服务端默认）";
        if (aiBindThreshold < 0 || aiBindThreshold > 100) return "媒体库自动绑定阈值需在 0~100（0=关闭自动绑定）";
        for (String err : parseHeaders().errors()) return err;
        return null;
    }

    /** 解析逐行附加头（「Name: Value」）；errors 非空 = 存在非法行（validate 消费）。 */
    public record HeaderList(List<String[]> pairs, List<String> errors) {}

    public HeaderList parseHeaders() {
        List<String[]> pairs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (extraHeaders == null) return new HeaderList(pairs, errors);
        for (String line : extraHeaders.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int i = t.indexOf(':');
            String name = i < 0 ? "" : t.substring(0, i).trim();
            String value = i < 0 ? "" : t.substring(i + 1).trim();
            if (i < 0 || name.isEmpty() || value.isEmpty() || !name.matches("[A-Za-z0-9-]{1,32}")) {
                errors.add("自定义请求头需为「名称: 值」每行一条（名称限字母/数字/连字符）: " + t);
                continue;
            }
            pairs.add(new String[]{name, value});
        }
        return new HeaderList(pairs, errors);
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
