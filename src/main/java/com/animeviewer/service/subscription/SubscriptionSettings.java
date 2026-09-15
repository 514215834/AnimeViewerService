package com.animeviewer.service.subscription;

import com.animeviewer.service.ServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** v0.19 SU1 订阅设置：yml（av.subscription.*）提供默认值，SQLite settings 表存 JSON 覆盖（key=subscription），
 *  前端设置页读写；字段缺失/类型不符时回退默认（损坏 JSON 整体回退，对齐 DownloadSettings 同款模式）。
 *  v0.20 起：自动入队阈值改为条目级 auto_score（subscriptions 表），此处仅承载全局检索/保护/评分链配置——
 *  defaultAutoScore=新订阅阈值默认值（0=全手动），globalFansubs=全局字幕组偏好（SU5，评分加权），
 *  skipEnqueuedEpisode=已入队同集忽略开关（SU6）。 */
public record SubscriptionSettings(
        int intervalMinutes, int minSizeMb, int autoDailyLimit, int autoMaxSizeMb, boolean autoOnlyMatched,
        int defaultAutoScore, List<String> globalFansubs, boolean skipEnqueuedEpisode) {

    public static final String STORE_KEY = "subscription";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static SubscriptionSettings defaults(ServiceProperties props) {
        ServiceProperties.Subscription s = props.subscription();
        List<String> fansubs = parseFansubList(s.globalFansubs());
        return new SubscriptionSettings(
                clamp(s.intervalMinutes() == null ? 60 : s.intervalMinutes(), 30, 360),
                Math.max(0, s.minSizeMb() == null ? 0 : s.minSizeMb()),
                Math.max(0, s.autoDailyLimit() == null ? 5 : s.autoDailyLimit()),
                Math.max(0, s.autoMaxSizeMb() == null ? 0 : s.autoMaxSizeMb()),
                s.autoOnlyMatched(),
                clamp(s.defaultAutoScore() == null ? 0 : s.defaultAutoScore(), 0, 100),
                fansubs,
                s.skipEnqueuedEpisode() == null || s.skipEnqueuedEpisode());
    }

    public static SubscriptionSettings load(String storedJson, SubscriptionSettings defaults) {
        if (storedJson == null || storedJson.isBlank()) return defaults;
        try {
            JsonNode n = MAPPER.readTree(storedJson);
            List<String> fansubs = new ArrayList<>();
            JsonNode f = n.get("globalFansubs");
            if (f != null && f.isArray()) {
                f.forEach(x -> {
                    if (x.isTextual() && !x.asText().isBlank()) fansubs.add(x.asText().trim());
                });
            }
            return new SubscriptionSettings(
                    clamp(intOf(n, "intervalMinutes", defaults.intervalMinutes()), 30, 360),
                    Math.max(0, intOf(n, "minSizeMb", defaults.minSizeMb())),
                    Math.max(0, intOf(n, "autoDailyLimit", defaults.autoDailyLimit())),
                    Math.max(0, intOf(n, "autoMaxSizeMb", defaults.autoMaxSizeMb())),
                    boolOf(n, "autoOnlyMatched", defaults.autoOnlyMatched()),
                    clamp(intOf(n, "defaultAutoScore", defaults.defaultAutoScore()), 0, 100),
                    fansubs,
                    boolOf(n, "skipEnqueuedEpisode", defaults.skipEnqueuedEpisode()));
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
            node.put("defaultAutoScore", defaultAutoScore);
            var arr = node.putArray("globalFansubs");
            for (String s : globalFansubs) arr.add(s);
            node.put("skipEnqueuedEpisode", skipEnqueuedEpisode);
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
        if (defaultAutoScore < 0 || defaultAutoScore > 100) return "默认匹配度阈值需在 0~100（0=手动确认）";
        return null;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** yml 全局偏好为逗号分隔字符串（av.subscription.global-fansubs: "组A,组B"）→ 列表 */
    static List<String> parseFansubList(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split("[,，]")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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
