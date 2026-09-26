package com.animeviewer.service.subscription;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.30 A6：从用户粘贴的页面 URL/文本按**规则**提取 RSS 订阅地址（纯函数，JUnit 护航）。
 *
 * 规则集以 §5R 探测定案 1 实测形态为准（2026-09-24 实抓）：
 * <ul>
 *   <li>输入已是 feed 形态（路径含 rss，如蜜柑 /RSS/Bangumi、acgnx rss.xml）→ 原样返回</li>
 *   <li>蜜柑详情页 {host}/Home/Bangumi/{id}（旧 /Home/Details/{id} 同）→ {host}/RSS/Bangumi?bangumiId={id}（id 同源实测 1:1）</li>
 *   <li>acgnx 搜索页 {host}/search.php?keyword=… → {host}/rss.xml?keyword=…</li>
 *   <li>dmhy 搜索页（topics/list?keyword=… 等任意路径）→ {host}/topics/rss/rss.xml?keyword=…</li>
 * </ul>
 * 规则推不出返回 null → 调用方走 AI 语义兜底（AiPrompts.rssResolveSystem）。
 */
public final class RssUrlExtractor {

    private RssUrlExtractor() {}

    /** 从文本里抓第一个 http(s) URL */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    /** 解析结果：rssUrl=识别出的 feed 地址（null=规则推不出） */
    public static String extract(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        Matcher m = URL_PATTERN.matcher(t);
        if (!m.find()) return null;
        String url = stripTrailingPunct(m.group());
        URI u;
        try {
            u = URI.create(url);
        } catch (Exception e) {
            return null;
        }
        if (!"http".equalsIgnoreCase(u.getScheme()) && !"https".equalsIgnoreCase(u.getScheme())) return null;
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getPath() == null ? "" : u.getPath();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String query = u.getQuery() == null ? "" : u.getQuery();

        // ① 已是 feed 形态（蜜柑 /RSS/Bangumi、acgnx rss.xml、dmhy topics/rss/rss.xml）→ 原样返回
        if (lowerPath.contains("rss")) return url;

        // ② 蜜柑详情页 → 每番 RSS（详情页 id 与 RSS bangumiId 实抓 1:1）
        Matcher detail = Pattern.compile("^/(?:Home/)?(?:Bangumi|Details)/(\\d+)/?$").matcher(path);
        if (host.contains("mikanani") && detail.find()) {
            return u.getScheme() + "://" + u.getAuthority() + "/RSS/Bangumi?bangumiId=" + detail.group(1);
        }

        // ③ acgnx 搜索页 → 关键词 RSS（模板 rss.xml?keyword=）
        String kw = keywordOf(query);
        if (kw != null && !kw.isBlank()) {
            if (host.contains("acgnx")) {
                return u.getScheme() + "://" + u.getAuthority() + "/rss.xml?keyword=" + enc(kw);
            }
            if (host.contains("dmhy")) {
                return u.getScheme() + "://" + u.getAuthority() + "/topics/rss/rss.xml?keyword=" + enc(kw);
            }
        }
        return null;
    }

    /** 从 query 里取 keyword/kw/searchword 搜索词（解码） */
    private static String keywordOf(String query) {
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            String key = pair.substring(0, i).toLowerCase(Locale.ROOT);
            if (key.equals("keyword") || key.equals("kw") || key.equals("searchword")) {
                try {
                    return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return pair.substring(i + 1);
                }
            }
        }
        return null;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** 去掉抓取 URL 带出的尾标点（中英文句号/逗号/引号/括号） */
    private static String stripTrailingPunct(String s) {
        String t = s;
        while (!t.isEmpty() && ".,;，。；》）】”’)]".indexOf(t.charAt(t.length() - 1)) >= 0) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
