package com.animeviewer.service.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.20 SU4 命中匹配度评分纯函数（0~100 + 逐维度明细 JSON）：订阅命中在落库时评分，
 * 分数与明细随 sub_hits 落库供前端展示（服务端单端口径，前端纯展示不重算）。
 *
 * <p>维度与权重（合计恰好 100）：
 * <ul>
 *   <li>基础有效性 20：集数解析成功（调用方已过滤，恒得分——「有效命中」底分）</li>
 *   <li>标题匹配 25：规范化后标题含中文名 25 ＞ 含原名 20 ＞ 都不含 0</li>
 *   <li>季号一致 20：标题显式季号（第N季/部、Nth Season、Season N）与条目季号一致 20 / 显式冲突 0 /
 *       标题无显式季号 10（中性——SxxEyy 的 Sxx 属弱信号，CR/流媒体按季独立编号易误判，不参与冲突）</li>
 *   <li>字幕组偏好 15：命中订阅级或全局偏好列表（大小写不敏感）</li>
 *   <li>清晰度 10：1080p/i 10 ＞ 720p 5 ＞ 未知 0</li>
 *   <li>字幕语言 10：标题含「简/繁/簡/繁体/CHT/CHS」标记 10，无标记 0</li>
 * </ul>
 */
public final class MatchScore {

    private MatchScore() {}

    /** 评分结果：总分 0~100 + 明细 JSON（{"total":N,"parts":["维度 得分",…]}，前端直接渲染） */
    public record Result(int total, String detail) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final int W_BASE = 20;
    static final int W_TITLE_CN = 25;
    static final int W_TITLE_ORIG = 20;
    static final int W_SEASON = 20;
    static final int W_FANSUB = 15;
    static final int W_RESOLUTION = 10;
    static final int W_SUBTITLE = 10;

    /** 强季号：第N季/第N部/第N期（中文数字或阿拉伯）/ Nth Season / Season N——资源标题与条目名通用 */
    private static final Pattern STRONG_CN = Pattern.compile("第\\s*([一二三四五六七八九十]{1,3}|\\d{1,2})\\s*[季部期]");
    private static final Pattern ORDINAL_SEASON = Pattern.compile("(\\d{1,2})\\s*(?:st|nd|rd|th)\\s*[Ss]eason");
    private static final Pattern SEASON_N = Pattern.compile("[Ss]eason\\s*(\\d{1,2})(?!\\d)");

    private static final Map<String, Integer> CN_NUM = Map.ofEntries(
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("三", 3), Map.entry("四", 4),
            Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8),
            Map.entry("九", 9), Map.entry("十", 10));

    /** 标题/条目名中的显式季号集合（强信号） */
    public static Set<Integer> strongSeasons(String text) {
        Set<Integer> out = new TreeSet<>();
        if (text == null || text.isBlank()) return out;
        Matcher cn = STRONG_CN.matcher(text);
        while (cn.find()) {
            Integer s = cnNumOrDigit(cn.group(1));
            if (s != null && s >= 1 && s <= 30) out.add(s);
        }
        Matcher en = ORDINAL_SEASON.matcher(text);
        while (en.find()) {
            int s = Integer.parseInt(en.group(1));
            if (s >= 1 && s <= 30) out.add(s);
        }
        Matcher season = SEASON_N.matcher(text);
        while (season.find()) {
            int s = Integer.parseInt(season.group(1));
            if (s >= 1 && s <= 30) out.add(s);
        }
        return out;
    }

    /** 中文数字（一~十九的常见季度表述）或阿拉伯数字 → int；解析不出返回 null */
    private static Integer cnNumOrDigit(String raw) {
        if (raw.matches("\\d{1,2}")) return Integer.parseInt(raw);
        if (raw.equals("十")) return 10;
        if (raw.startsWith("十") && raw.length() == 2) {
            Integer ones = CN_NUM.get(raw.substring(1, 2));
            return ones == null ? null : 10 + ones;
        }
        if (raw.length() == 2 && raw.charAt(1) == '十') {
            Integer tens = CN_NUM.get(raw.substring(0, 1));
            return tens == null ? null : tens * 10;
        }
        if (raw.length() == 3 && raw.charAt(1) == '十') {
            Integer tens = CN_NUM.get(raw.substring(0, 1));
            Integer ones = CN_NUM.get(raw.substring(2, 3));
            return tens == null || ones == null ? null : tens * 10 + ones;
        }
        return CN_NUM.get(raw);
    }

    /** 季号一致性得分（含判定依据文本） */
    static int seasonScore(String subjectNameCn, String subjectName, String title, List<String> parts) {
        Set<Integer> subSeasons = new TreeSet<>();
        subSeasons.addAll(strongSeasons(subjectNameCn));
        subSeasons.addAll(strongSeasons(subjectName));
        Set<Integer> titleSeasons = strongSeasons(title);
        if (!titleSeasons.isEmpty()) {
            Set<Integer> expected = subSeasons.isEmpty() ? Set.of(1) : subSeasons;
            if (titleSeasons.stream().anyMatch(expected::contains)) {
                parts.add("季号一致 " + W_SEASON);
                return W_SEASON;
            }
            parts.add("季号冲突（标题" + joinNums(titleSeasons) + "，条目" + joinNums(expected) + "）0");
            return 0;
        }
        parts.add("标题无显式季号（中性）10");
        return 10;
    }

    private static String joinNums(Set<Integer> set) {
        StringBuilder sb = new StringBuilder();
        for (int n : set) {
            if (sb.length() > 0) sb.append("/");
            sb.append(n);
        }
        return sb.length() == 0 ? "无季号" : "第" + sb + "季";
    }

    private static final Pattern RES_1080 = Pattern.compile("1080[pi]", Pattern.CASE_INSENSITIVE);
    private static final Pattern RES_720 = Pattern.compile("720[pi]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBTITLE_MARK = Pattern.compile("简|繁|簡|簡体|CHT|CHS", Pattern.CASE_INSENSITIVE);

    /** 评分主入口；episode 非空由调用方过滤保证（恒给基础分）。返回总分（0~100）与明细 JSON */
    public static Result score(String subjectNameCn, String subjectName, String title,
                               String fansub, List<String> preferredFansubs) {
        List<String> parts = new ArrayList<>();
        int total = 0;

        total += W_BASE;
        parts.add("集数解析成功 " + W_BASE);

        String t = normalize(title);
        int titleScore = 0;
        if (subjectNameCn != null && !subjectNameCn.isBlank() && t.contains(normalize(subjectNameCn))) {
            titleScore = W_TITLE_CN;
            parts.add("标题含中文名 " + W_TITLE_CN);
        } else if (subjectName != null && !subjectName.isBlank() && t.contains(normalize(subjectName))) {
            titleScore = W_TITLE_ORIG;
            parts.add("标题含原名 " + W_TITLE_ORIG);
        } else {
            parts.add("标题未含条目名 0");
        }
        total += titleScore;

        total += seasonScore(subjectNameCn, subjectName, title, parts);

        if (fansub != null && !fansub.isBlank() && preferredFansubs != null
                && preferredFansubs.stream().anyMatch(x -> x.equalsIgnoreCase(fansub.trim()))) {
            total += W_FANSUB;
            parts.add("字幕组「" + fansub + "」命中偏好 " + W_FANSUB);
        } else {
            parts.add("字幕组偏好 " + 0);
        }

        if (RES_1080.matcher(title == null ? "" : title).find()) {
            total += W_RESOLUTION;
            parts.add("1080p " + W_RESOLUTION);
        } else if (RES_720.matcher(title == null ? "" : title).find()) {
            total += 5;
            parts.add("720p 5");
        } else {
            parts.add("清晰度未知 0");
        }

        if (SUBTITLE_MARK.matcher(title == null ? "" : title).find()) {
            total += W_SUBTITLE;
            parts.add("含简繁字幕标记 " + W_SUBTITLE);
        } else {
            parts.add("无字幕标记 0");
        }

        int capped = Math.max(0, Math.min(100, total));
        return new Result(capped, toJson(capped, parts));
    }

    /** 规范化：去空白 + 小写（contains 口径统一，与过滤层同用） */
    static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String toJson(int total, List<String> parts) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("total", total);
            ArrayNode arr = root.putArray("parts");
            for (String p : parts) arr.add(p);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"total\":" + total + ",\"parts\":[]}";
        }
    }
}
