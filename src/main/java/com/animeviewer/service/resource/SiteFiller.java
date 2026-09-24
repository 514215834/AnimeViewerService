package com.animeviewer.service.resource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.30 补记一：从用户粘贴的站点地址/文本按**规则**推导「站点 key + 名称 + baseUrl + 关键词搜索模板」
 * （纯函数，JUnit 护航）。规则集：
 * <ul>
 *   <li>① 通用反推——输入已是 RSS 搜索地址（路径含 rss / query 含 page=rss，且带关键词参数）→
 *       baseUrl 取 scheme://authority，searchTemplate 取 path+query 并把关键词参数值替换为 {kw}
 *       （acgnx rss.xml?keyword=…、dmhy topics/rss/rss.xml?keyword=…、nyaa ?page=rss&q=… 全覆盖）</li>
 *   <li>② 已知站点形态——host 含 acgnx/dmhy/nyaa → 直接给内置同款模板</li>
 *   <li>③ 其余 → null（调用方走 LLM 兜底，拒绝编造）</li>
 * </ul>
 * key 由 host 核心段推导（剔 www/share/mirror 等泛用段，[a-z0-9_-] 消毒）——与内置站同名时即覆盖内置定义。
 */
public final class SiteFiller {

    private SiteFiller() {}

    /** 规则推导结果（模板已含 {kw}；key 已消毒） */
    public record Fill(String key, String name, String baseUrl, String searchTemplate) {}

    /** 关键词参数名候选（值会被替换为 {kw}） */
    private static final List<String> KW_KEYS = List.of("keyword", "kw", "q", "searchword", "searchterm");

    /** 泛用 host 段（不作为 key 候选） */
    private static final List<String> GENERIC_LABELS = List.of("www", "share", "mirror", "api", "bbs", "forum", "torrent", "bt", "rss", "m", "w");

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final String PLACEHOLDER = "{kw}";

    public static Fill guess(String raw) {
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
        if (host.isBlank()) return null;
        String path = u.getPath() == null ? "" : u.getPath();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String query = u.getQuery() == null ? "" : u.getQuery();

        // ① 通用反推：RSS 搜索地址 → 拆 baseUrl + 模板（关键词值替换 {kw}）
        boolean feedish = lowerPathContainsRss(lowerPath) || query.toLowerCase(Locale.ROOT).contains("page=rss")
                || lowerPath.endsWith(".xml");
        Map<String, String> params = parseQuery(query);
        String kwParam = params.keySet().stream()
                .filter(SiteFiller::isKwParam)
                .findFirst().orElse(null);
        if (feedish && kwParam != null) {
            String template = buildTemplate(path, query, kwParam);
            if (template != null) {
                String base = u.getScheme() + "://" + u.getAuthority();
                return new Fill(deriveKey(host), host, base, template);
            }
        }

        // ② 已知 host 预设（内置同款；key 复用内置名——key 相同覆盖内置定义合法）
        if (host.contains("acgnx")) {
            return new Fill("acgnx", "acgnx", "https://share.acgnx.se", "rss.xml?keyword=" + PLACEHOLDER);
        }
        if (host.contains("dmhy")) {
            return new Fill("dmhy", "dmhy", "https://share.dmhy.org", "topics/rss/rss.xml?keyword=" + PLACEHOLDER);
        }
        if (host.contains("nyaa")) {
            return new Fill("nyaa", "nyaa", "https://nyaa.si", "?page=rss&q=" + PLACEHOLDER + "&c=0_0&f=0");
        }
        return null;
    }

    /** host → key：剔 www/share 等泛用段，取最长非泛用段，消毒为 [a-z0-9_-]{1,24}；全剔时取首段 */
    static String deriveKey(String host) {
        String[] parts = host.split("\\.");
        String best = null;
        for (String p : parts) {
            String seg = sanitize(p);
            if (seg.isEmpty() || GENERIC_LABELS.stream().anyMatch(g -> seg.startsWith(g))) continue;
            if (best == null || seg.length() > best.length()) best = seg;
        }
        if (best == null || best.isEmpty()) best = sanitize(host);
        if (best.length() > 24) best = best.substring(0, 24);
        return best;
    }

    /** baseUrl → host（解析失败/空返回空串，供 LLM key 兜底推导） */
    static String safeHost(String baseUrl) {
        try {
            URI u = URI.create(baseUrl.trim());
            return u.getHost() == null ? "" : u.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /** 模板 = path + query，其中关键词参数值替换 {kw}、其他参数原样保留；返回相对 baseUrl 的形态（无前导 /） */
    private static String buildTemplate(String path, String query, String kwParam) {
        StringBuilder sb = new StringBuilder();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            sb.append(path.startsWith("/") ? path.substring(1) : path);
        }
        if (query != null && !query.isBlank()) {
            StringBuilder q = new StringBuilder();
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) continue;
                int i = pair.indexOf('=');
                String key = i < 0 ? pair : pair.substring(0, i);
                String value = i < 0 ? "" : pair.substring(i + 1);
                if (q.length() > 0) q.append('&');
                if (key.equalsIgnoreCase(kwParam)) {
                    q.append(key).append('=').append(PLACEHOLDER);
                } else {
                    q.append(key).append('=').append(value);
                }
            }
            sb.append(sb.length() == 0 ? "?" : "?").append(q);
        }
        String template = sb.toString();
        if (!template.contains(PLACEHOLDER)) return null;
        return template;
    }

    /** 识别关键词参数名（解码后比名） */
    private static boolean isKwParam(String key) {
        return KW_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return out;
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            String key = pair.substring(0, i);
            String value = pair.substring(i + 1);
            try {
                out.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (Exception e) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static boolean lowerPathContainsRss(String lowerPath) {
        return lowerPath.contains("rss") || lowerPath.contains("feed");
    }

    private static String sanitize(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return t;
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
