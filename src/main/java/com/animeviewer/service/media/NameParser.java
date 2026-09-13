package com.animeviewer.service.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** S3 文件名识别（纯函数，供单测）：从视频文件名解析「标题 + 集数」。
 *  覆盖主流命名：
 *    [Group] Title - 01 [1080p][BDRip].mkv
 *    [Group][Title][01][1080p].mkv
 *    Title S01E01.mkv
 *    Title 第01话.mkv / 第01話 Title.mkv
 *    Title - 01.mkv / Title 01.mkv / Title_01[1080p].mkv
 *  解析失败返回 title=null（未识别状态，等待人工改绑）。 */
public final class NameParser {

    private NameParser() {}

    public record ParsedName(String title, Integer episode) {}

    /** 分辨率 / 来源 / 编码 / 字幕 / 音频等噪声词（清洗标题时移除） */
    private static final Pattern NOISE = Pattern.compile(
            "(?i)\\b(2160p|1080p|1080i|720p|480p|4k|2k|8bit|10bit|hi10p|x264|x265|h\\.?264|h\\.?265|hevc|avc|"
                    + "bdrip|blu-?ray|b-?global|webrip|web-?dl|hdtv|dvdrip|baha|b-?global|"
                    + "chs|cht|chsc|cht&chs|gb|big5|big 5|简体|繁体|简繁|内嵌|外挂|简日|繁日|"
                    + "aac|flac|mp3|opus|truehd|atmos|dts|ac3|5\\.1|2\\.0|yuv420p|ma10p|"
                    + "v\\d|fin|mkv|mp4)\\b");

    private static final Pattern BRACKET = Pattern.compile("[\\[【（(]([^\\]】）)]*)[\\]】）)]");
    private static final Pattern EP_RANGE = Pattern.compile("EP?(\\d{1,4})\\s*[-~—]\\s*EP?(\\d{1,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SXXEXX = Pattern.compile("(?i)[Ss](\\d{1,2})\\s?[Ee](\\d{1,4})");
    private static final Pattern CN_EP = Pattern.compile("第\\s*(\\d{1,4})\\s*[话話集]");
    private static final Pattern DASH_EP = Pattern.compile("^(.{1,120}?)[\\s_.·]{0,2}[-–—]{1,2}[\\s_.]{0,2}(\\d{1,4})\\s*(?:[Vv]\\d)?\\s*$");
    private static final Pattern TRAIL_EP = Pattern.compile("^(.{2,120}?)\\s*[-_.\\s]\\s*(\\d{1,4})\\s*(?:[Vv]\\d)?$");
    private static final Pattern LEAD_EP = Pattern.compile("^(\\d{1,4})\\s*[话話集]?[\\s_.-]+(.+)$");
    private static final Pattern PURE_NUM = Pattern.compile("\\d{1,4}");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]");
    private static final Pattern SEP_EDGE = Pattern.compile("^[\\s_\\-.·~]+|[\\s_\\-.·~]+$");

    public static ParsedName parse(String fileName) {
        String stem = fileName.replaceFirst("\\.[A-Za-z0-9]{2,4}$", "").trim();
        if (stem.isBlank()) return new ParsedName(null, null);

        // 1) 全括号式 [Group][Title][01][1080p]
        ParsedName allBracket = parseAllBracket(stem);
        if (allBracket != null) return allBracket;

        // 2) SxxExx（季集信息，集数取 E 部分；标题取其前）
        Matcher m = SXXEXX.matcher(stem);
        if (m.find()) {
            String title = cleanTitle(stem.substring(0, m.start()));
            if (!title.isBlank()) return new ParsedName(title, num(m.group(2)));
        }

        // 3) 中文集数 第NN话/話/集
        m = CN_EP.matcher(stem);
        if (m.find()) {
            String before = stem.substring(0, m.start());
            String after = stem.substring(m.end());
            String title = cleanTitle(before + " " + after);
            if (!title.isBlank()) return new ParsedName(title, num(m.group(1)));
        }

        // 4) 清洗后的标题串上跑「- 01」「_01」「 01」式（括号尾巴 [1080p][Final] 等已被清掉）
        String cleaned = cleanTitle(stem);
        if (!cleaned.isBlank()) {
            m = DASH_EP.matcher(cleaned);
            if (m.matches()) {
                String title = cleanTitle(m.group(1));
                Integer ep = num(m.group(2));
                if (!title.isBlank() && ep != null) return new ParsedName(title, ep);
            }
            m = TRAIL_EP.matcher(cleaned);
            if (m.matches()) {
                Integer ep = num(m.group(2));
                if (ep != null && !isYearLike(ep)) {
                    String title = cleanTitle(m.group(1));
                    if (!title.isBlank()) return new ParsedName(title, ep);
                }
            }
            m = LEAD_EP.matcher(cleaned);
            if (m.matches()) {
                Integer ep = num(m.group(1));
                if (ep != null && !isYearLike(ep)) {
                    String title = cleanTitle(m.group(2));
                    if (!title.isBlank()) return new ParsedName(title, ep);
                }
            }
        }

        // 5) 只清洗标题，无集数
        return cleaned.isBlank() ? new ParsedName(null, null) : new ParsedName(cleaned, null);
    }

    /** 19xx / 20xx 形态的尾随数字按年份处理，不作集数（「Movie.2019.1080p」类） */
    private static boolean isYearLike(int n) {
        return n >= 1900 && n <= 2099;
    }

    /** [Group][Title][01][1080p] 全括号式：组名 = 含 CJK 前的第一个非噪声括号之前的那个括号；
     *  纯数字括号 = 集数。 */
    private static ParsedName parseAllBracket(String stem) {
        Matcher bm = BRACKET.matcher(stem);
        List<String> tokens = new ArrayList<>();
        int lastEnd = 0;
        while (bm.find()) {
            if (bm.start() != lastEnd) return null; // 括号之间有散文本 → 不是全括号式
            tokens.add(bm.group(1));
            lastEnd = bm.end();
        }
        if (lastEnd != stem.length() || tokens.size() < 2) return null;

        Integer ep = null;
        List<String> texts = new ArrayList<>();
        for (String t : tokens) {
            if (ep == null && PURE_NUM.matcher(t.trim()).matches()) {
                ep = num(t);
            } else if (!isNoiseToken(t)) {
                texts.add(t.trim());
            }
        }
        if (ep == null || texts.isEmpty()) return null;
        // 第一个为组名（若有多个文本块），标题取含 CJK 的优先
        String title;
        if (texts.size() >= 2) {
            title = texts.stream().filter(t -> CJK.matcher(t).find()).findFirst().orElse(texts.get(1));
        } else {
            title = texts.get(0);
        }
        title = cleanTitle(title);
        return title.isBlank() ? null : new ParsedName(title, ep);
    }

    private static final Pattern GROUP_TAG_UPPER = Pattern.compile("[A-Z]{2,10}");

    private static boolean isNoiseToken(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        // 全大写短词多为字幕组/发布组尾标（JPSC、VCB、NC…）
        if (t.length() >= 2 && t.length() <= 10 && GROUP_TAG_UPPER.matcher(t).matches()) return true;
        return NOISE.matcher(lower).find()
                || lower.contains("kissaten") || lower.contains("sub")
                || t.contains("字幕组") || t.contains("字幕") || t.contains("汉化") || t.contains("搬運") || t.contains("搬运");
    }

    /** 标题清洗：去括号块 / 噪声词 / 分隔符边缘 */
    public static String cleanTitle(String raw) {
        if (raw == null) return "";
        String s = BRACKET.matcher(raw).replaceAll(" ");
        // 连字符式前的组名残留（行首 [xxx] 已由括号清理覆盖；此处清理 「Group] 」漏网形态）
        s = NOISE.matcher(s).replaceAll(" ");
        // 集数范围标记（01-12 全集包）
        s = EP_RANGE.matcher(s).replaceAll(" ");
        s = SEP_EDGE.matcher(s).replaceAll("");
        s = s.replaceAll("\\s{2,}", " ").trim();
        // 仅剩噪声的标题视为未识别
        if (s.length() < 2) return "";
        return s;
    }

    /** 匹配归一化：小写 + 去空白与常见标点（供 Bangumi 名称比对） */
    public static String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}　·～~〔〕\\[\\]()【】「」『』:：,，.。!！?？'’‘\"“”-]", "");
    }

    private static Integer num(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return (v >= 0 && v <= 9999) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }
}
