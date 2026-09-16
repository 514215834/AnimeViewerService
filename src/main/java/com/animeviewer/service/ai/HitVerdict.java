package com.animeviewer.service.ai;

import com.fasterxml.jackson.databind.JsonNode;

/** v0.22 AI1 命中语义判定结果（sub_hits.ai_verdict 列 JSON 的强类型视图；null 列 = 未判定）。
 *  type: episode=本篇正片 / op / ed / other（主题曲·特典·菜单等）；
 *  episode=判定出的集数（与启发式 parseEpisode 不一致时前端展示「建议第 N 话」）；
 *  isMainline=false 时前端红标，评分达标也不会自动入队（autoEnqueuePending 拦截）。 */
public record HitVerdict(String type, Integer episode, boolean isMainline, String reason) {

    public static final String TYPE_EPISODE = "episode";
    public static final String TYPE_OP = "op";
    public static final String TYPE_ED = "ed";
    public static final String TYPE_OTHER = "other";

    public static HitVerdict parse(String json) {
        JsonNode n = AiService.parseLooseJson(json);
        if (n == null || !n.isObject()) return null;
        String type = n.path("type").asText(TYPE_OTHER);
        if (!type.equals(TYPE_EPISODE) && !type.equals(TYPE_OP) && !type.equals(TYPE_ED)) type = TYPE_OTHER;
        Integer ep = n.path("episode").isInt() && n.path("episode").asInt() > 0 && n.path("episode").asInt() <= 999
                ? n.path("episode").asInt() : null;
        boolean mainline = n.path("isMainline").asBoolean(type.equals(TYPE_EPISODE));
        String reason = n.path("reason").asText("").trim();
        return new HitVerdict(type, ep, mainline, reason.isEmpty() ? null : reason);
    }

    public boolean nonMainline() {
        return !isMainline;
    }

    /** 徽章短文案（前端同款语义）：本篇 / 主题曲 / 非本篇 */
    public String label() {
        return switch (type) {
            case TYPE_EPISODE -> "AI 本篇";
            case TYPE_OP, TYPE_ED -> "AI 主题曲";
            default -> "AI 非本篇";
        };
    }
}
