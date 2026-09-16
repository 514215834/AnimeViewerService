package com.animeviewer.service.subscription;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.19 SU1 订阅过滤纯函数（对齐前端 mediaService.guessEpisodeSortFromTitle / extractFansub 的行为，
 * 两端口径一致才能保证「确认弹窗预填」与「订阅命中」对同一标题解析出同一集数）：
 *
 * <ul>
 *   <li>{@link #parseEpisode}：标题集数猜测——EP33 等显式标记优先，退化「最后一个独立数字」（排除年份/1080p/
 *       单位尾数 10bit·48kHz·60fps/点分日期·编码名/连字符范围包——见 v0.21 补记）</li>
 *   <li>{@link #parseSizeBytes}：RSS description 管道段的大小文本（1.4GB / 593.7MB）→ 字节数</li>
 *   <li>{@link #extractFansub}：字幕组提取——行首 [组名] 优先 / 镜像站竖线形态（acgnx 官方发布）</li>
 * </ul>
 */
public final class SubscriptionFilter {

    private SubscriptionFilter() {}

    private static final Pattern EP_EXPLICIT = Pattern.compile("EP?\\s*(\\d{1,4})(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EP_CN = Pattern.compile("第\\s*(\\d{1,4})\\s*[话話集]");
    private static final Pattern STANDALONE_NUM = Pattern.compile("\\d{1,4}(?!\\d)");
    private static final Pattern SIZE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(TB|GB|MB|KB|B)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FANSUB_LEAD = Pattern.compile("^\\[([^\\[\\]]{1,30})]");
    private static final Pattern FANSUB_PIPE = Pattern.compile("^([^|｜]{1,24})[|｜]");

    /** 标题集数猜测；解析不出返回 null（订阅过滤：无集数的条目无法判断新旧，直接跳过） */
    public static Integer parseEpisode(String title) {
        if (title == null) return null;
        Matcher m = EP_EXPLICIT.matcher(title);
        boolean explicitHit = m.find();
        if (explicitHit) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= 999) return n;
        } else {
            Matcher cn = EP_CN.matcher(title);
            if (cn.find()) {
                int n = Integer.parseInt(cn.group(1));
                if (n >= 1 && n <= 999) return n;
            }
        }
        // 显式标记存在但超出 1~999（如 1080 被误命中）时与前端一致：跳过第N话分支，走独立数字退化。
        // v0.21 补记（2026-09-16 实测 事故）：裸数字集数标题（LoliHouse「てんびん - 11」无「第N话」）被尾部
        // 发布参数劫持——10bit/48kHz/24bit/日期段(H.264/2026.07.08)/范围包(第01-04巻/1-3) 胜出，
        // 同集数 pending 占位唯一把真集数全挡掉。独立数字退化新增三类排除（与前端同步改）：
        // 单位尾数（bit/kHz/kbps/mbps/fps/Hz）、点分邻接（日期/H.264/Vol.12/1.4GB）、连字符范围成员。
        Integer guess = null;
        Matcher nums = STANDALONE_NUM.matcher(title);
        while (nums.find()) {
            int n = Integer.parseInt(nums.group());
            if (n < 1 || n > 999) continue;
            if (isYear(n)) continue;
            boolean nextIsP = nums.end() < title.length() && Character.toLowerCase(title.charAt(nums.end())) == 'p';
            if (nextIsP || unitTail(title, nums.end())) continue;
            if (dotAdjacent(title, nums.start(), nums.end())) continue;
            if (hyphenRange(title, nums.start(), nums.end())) continue;
            guess = n;
        }
        return guess;
    }

    private static boolean isYear(int n) {
        return n >= 1900 && n <= 2099;
    }

    /** 单位尾数（大小写不敏感）：24bit、48kHz、192kbps、320mbps、60fps——发布参数不是集数 */
    private static boolean unitTail(String title, int end) {
        if (end >= title.length()) return false;
        String rest = title.substring(end).toLowerCase(Locale.ROOT);
        return rest.startsWith("bit") || rest.startsWith("khz") || rest.startsWith("hz")
                || rest.startsWith("kbps") || rest.startsWith("mbps") || rest.startsWith("fps");
    }

    /** 点分邻接：2026.07.08 日期段、H.264/H.265 编码名、Vol.12 卷号、3.4GB 大小——数字两侧的 '.' 说明不是集数 */
    private static boolean dotAdjacent(String title, int start, int end) {
        boolean leftDot = start > 0 && title.charAt(start - 1) == '.';
        boolean rightDot = end < title.length() && title.charAt(end) == '.';
        return leftDot || rightDot;
    }

    /** 连字符范围成员：第01-04巻、1-3 epub 合集包（数字-'-'-数字直接相连）——区间包无法对应单集，整段作废 */
    private static boolean hyphenRange(String title, int start, int end) {
        boolean leftRange = start > 1 && title.charAt(start - 1) == '-' && Character.isDigit(title.charAt(start - 2));
        boolean rightRange = end + 1 < title.length() && title.charAt(end) == '-' && Character.isDigit(title.charAt(end + 1));
        return leftRange || rightRange;
    }

    /** 大小文本 → 字节数（1.4GB / 593.7MB / 512KB）；无大小段返回 null（调用方视为未知，不参与大小过滤） */
    public static Long parseSizeBytes(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = SIZE.matcher(text);
        if (!m.find()) return null;
        double v = Double.parseDouble(m.group(1));
        return switch (m.group(2).toUpperCase(Locale.ROOT)) {
            case "TB" -> (long) (v * 1024L * 1024 * 1024 * 1024);
            case "GB" -> (long) (v * 1024L * 1024 * 1024);
            case "MB" -> (long) (v * 1024L * 1024);
            case "KB" -> (long) (v * 1024);
            default -> (long) v;
        };
    }

    /** 字幕组提取；与前端 extractFansub 同规则（行首 [组名] 优先 / 竖线形态取竖线前缀） */
    public static String extractFansub(String title) {
        if (title == null) return null;
        String t = title.trim();
        Matcher lead = FANSUB_LEAD.matcher(t);
        if (lead.find()) return lead.group(1).trim();
        Matcher pipe = FANSUB_PIPE.matcher(t);
        if (pipe.find()) return pipe.group(1).trim();
        return null;
    }
}
