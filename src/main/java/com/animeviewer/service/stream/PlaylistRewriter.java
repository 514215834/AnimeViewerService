package com.animeviewer.service.stream;

import java.net.URI;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** v0.15 O2 m3u8 清单重写器（纯函数，供单测）。
 *  把清单内全部资源 URI（分片行 / 子清单行 / EXT-X-KEY 与 EXT-X-MAP 的 URI 属性 / EXT-X-MEDIA 的 URI 属性）
 *  相对→绝对解析后交由 proxyUrlOf 包裹为代理地址；其他标签与未知行原样透传，解析失败不抛出。
 *  EXT-X-STREAM-INF 本身不含 URI（地址在下一行），走「裸行」分支天然覆盖。 */
public final class PlaylistRewriter {

    private PlaylistRewriter() {}

    /** 带 URI 属性的标签（属性值双引号包裹） */
    private static final Pattern URI_ATTR = Pattern.compile("(\\bURI\\s*=\\s*\")([^\"]*)(\")", Pattern.CASE_INSENSITIVE);

    public static String rewrite(String playlistText, String baseUrl, UnaryOperator<String> proxyUrlOf) {
        if (playlistText == null || playlistText.isBlank() || baseUrl == null || baseUrl.isBlank() || proxyUrlOf == null) {
            return playlistText;
        }
        StringBuilder out = new StringBuilder(playlistText.length() + 256);
        for (String raw : playlistText.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (line.startsWith("#")) {
                out.append(line.startsWith("#EXT-X-KEY:")
                        || line.startsWith("#EXT-X-MAP:")
                        || line.startsWith("#EXT-X-MEDIA:") ? rewriteUriAttrs(line, baseUrl, proxyUrlOf) : line).append('\n');
            } else {
                // 裸行：分片或子清单地址
                String absolute = toAbsolute(line, baseUrl);
                out.append(absolute == null ? line : proxyUrlOf.apply(absolute)).append('\n');
            }
        }
        return out.toString();
    }

    /** 重写 #EXT-X-KEY / #EXT-X-MAP / #EXT-X-MEDIA 行内全部 URI="..." 属性 */
    private static String rewriteUriAttrs(String line, String baseUrl, UnaryOperator<String> proxyUrlOf) {
        Matcher m = URI_ATTR.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String absolute = toAbsolute(m.group(2), baseUrl);
            String replaced = absolute == null ? m.group(0)
                    : m.group(1) + proxyUrlOf.apply(absolute) + m.group(3);
            m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 相对 → 绝对（基于清单自身地址）；非法 URI / 已是 data: 等非 http 形态返回 null（透传原样） */
    static String toAbsolute(String uri, String baseUrl) {
        String trimmed = uri.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("data:") || trimmed.startsWith("blob:")) return null;
        try {
            URI u = URI.create(trimmed);
            if (u.isAbsolute()) return trimmed;
            return URI.create(baseUrl).resolve(trimmed).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
