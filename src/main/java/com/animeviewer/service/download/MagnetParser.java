package com.animeviewer.service.download;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** v0.16 DN2 磁力/URI 解析纯函数（JUnit 覆盖）：
 *  识别 magnet（btih 40 位 hex / 32 位 base32）与 http(s)/ftp 直链；提取 infohash（任务去重键）、
 *  展示名（dn）与磁力自带 tracker（注入合并基础）。 */
public final class MagnetParser {

    private MagnetParser() {}

    /** infoHash 为 magnet 的 xt=urn:btih 值（hex 小写或原样 base32）；非磁力为 null */
    public record MagnetInfo(String infoHash, String displayName, List<String> trackers) {}

    public static boolean isMagnet(String uri) {
        return uri != null && uri.toLowerCase(Locale.ROOT).startsWith("magnet:?");
    }

    /** aria2 直链范围：http/https/ftp（.torrent 种子链接或普通文件直链均经 addUri） */
    public static boolean isDirectLink(String uri) {
        if (uri == null) return false;
        String u = uri.toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("ftp://");
    }

    public static boolean isSupported(String uri) {
        return isMagnet(uri) || isDirectLink(uri);
    }

    /** 解析 magnet 的 xt/dn/tr；非磁力或无 btih 返回 infoHash=null（非磁力仅 dedupe 用 rawUri） */
    public static MagnetInfo parse(String uri) {
        if (!isMagnet(uri)) return new MagnetInfo(null, null, List.of());
        Map<String, List<String>> params = new LinkedHashMap<>();
        String query = uri.substring(uri.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq);
            String v = decode(pair.substring(eq + 1));
            params.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        String infoHash = null;
        for (String xt : params.getOrDefault("xt", List.of())) {
            String lower = xt.toLowerCase(Locale.ROOT);
            if (lower.startsWith("urn:btih:")) {
                String h = xt.substring(9).trim();
                boolean hex40 = h.length() == 40 && h.chars().allMatch(c -> Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
                boolean base32 = h.length() == 32 && h.chars().allMatch(c -> Character.isLetterOrDigit(c));
                if (hex40) infoHash = h.toLowerCase(Locale.ROOT);
                else if (base32) infoHash = h.toUpperCase(Locale.ROOT);
                if (infoHash != null) break;
            }
        }
        String name = params.getOrDefault("dn", List.of()).stream().filter(s -> !s.isBlank()).findFirst().orElse(null);
        return new MagnetInfo(infoHash, name, List.copyOf(params.getOrDefault("tr", List.of())));
    }

    /** 磁力 tracker 注入合并（原型结论：无 tracker 磁力依赖 DHT，元数据解析极慢）——
     *  对配置列表中磁力尚未包含的 tracker 追加 &tr=；非磁力原样返回 */
    public static String mergeTrackers(String uri, List<String> extraTrackers) {
        if (!isMagnet(uri) || extraTrackers == null || extraTrackers.isEmpty()) return uri;
        MagnetInfo info = parse(uri);
        StringBuilder sb = new StringBuilder(uri);
        for (String t : extraTrackers) {
            String tracker = t.trim();
            if (tracker.isEmpty()) continue;
            if (info.trackers().stream().anyMatch(x -> x.equalsIgnoreCase(tracker))) continue;
            sb.append("&tr=").append(urlEncode(tracker));
        }
        return sb.toString();
    }

    public static String urlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (Character.isLetterOrDigit(c) || "-._~".indexOf(c) >= 0) sb.append(c);
            else sb.append('%').append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
