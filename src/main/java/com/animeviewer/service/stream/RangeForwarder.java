package com.animeviewer.service.stream;

import com.animeviewer.service.ServiceProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.UnaryOperator;

/** v0.15 O2 流代理转发内核（O3 WebDAV streamId 流复用）：
 *  GET 上游，透传 Range 与 206/Content-Range，InputStream→OutputStream 纯流式不整体缓冲；
 *  上游返回 m3u8（扩展名或 Content-Type 识别）时缓冲（≤2MB）→ 交 PlaylistRewriter 重写 → 文本回写。
 *  客户端中途断开（暂停/换集）属正常路径，仅 debug。 */
@Component
public class RangeForwarder {

    private static final Logger log = LoggerFactory.getLogger(RangeForwarder.class);

    /** 清单文本上限：超限视为异常上游，拒绝转发 */
    public static final long MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;

    private final HttpClient client;
    private final String userAgent;

    public RangeForwarder(ServiceProperties props, HttpClient client) {
        this.client = client;
        this.userAgent = props.bangumi().userAgent();
    }

    /** 转发结果：STREAMED（已按 Range 语义流式回写）/ PLAYLIST（已重写为代理清单文本回写） */
    public enum Outcome { STREAMED, PLAYLIST }

    /**
     * @param playlistRewriter 非 null 时启用清单改写（传入「清单文本→重写文本」函数，内部逐 URI 包裹）；null 一律按流转发
     * @param extraHeaders     附加请求头（如 WebDAV Basic 授权）；可为 null
     * @return 本次转发形态
     */
    public Outcome forward(String targetUrl,
                           String rangeHeader,
                           HttpServletResponse response,
                           UnaryOperator<String> playlistRewriter,
                           java.util.Map<String, String> extraHeaders) throws IOException, InterruptedException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", userAgent)
                .header("Accept", "*/*");
        boolean rangeSent = rangeHeader != null && !rangeHeader.isBlank();
        if (rangeSent) rb.header("Range", rangeHeader);
        if (extraHeaders != null) extraHeaders.forEach(rb::header);

        HttpResponse<InputStream> up = client.send(rb.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        String contentType = up.headers().firstValue("Content-Type").orElse("");

        try (InputStream in = up.body()) {
            if (playlistRewriter != null && up.statusCode() / 100 == 2 && looksLikePlaylist(targetUrl, contentType)) {
                String text = readCapped(in);
                response.setStatus(200);
                response.setContentType("application/vnd.apple.mpegurl");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("X-AV-Proxied", "playlist");
                response.getOutputStream().write(playlistRewriter.apply(text).getBytes(StandardCharsets.UTF_8));
                return Outcome.PLAYLIST;
            }

            response.setStatus(up.statusCode());
            if (!contentType.isBlank()) response.setContentType(contentType);
            copyHeader(up, "Content-Length", v -> {
                try { response.setContentLengthLong(Long.parseLong(v.trim())); } catch (NumberFormatException ignored) {}
            });
            copyHeader(up, "Content-Range", v -> response.setHeader("Content-Range", v));
            copyHeader(up, "Accept-Ranges", v -> response.setHeader("Accept-Ranges", v));
            response.setHeader("X-AV-Proxied", "stream");
            in.transferTo(response.getOutputStream());
            return Outcome.STREAMED;
        }
    }

    /** 清单识别：.m3u8 扩展名或上游 Content-Type 含 mpegurl */
    static boolean looksLikePlaylist(String targetUrl, String contentType) {
        if (contentType != null && contentType.toLowerCase().contains("mpegurl")) return true;
        String path = URI.create(targetUrl).getPath();
        return path != null && path.toLowerCase().endsWith(".m3u8");
    }

    private static String readCapped(InputStream in) throws IOException {
        byte[] buf = in.readNBytes((int) MAX_PLAYLIST_BYTES + 1);
        if (buf.length > MAX_PLAYLIST_BYTES) throw new IOException("播放清单超过 2MB 上限");
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void copyHeader(HttpResponse<?> up, String name, java.util.function.Consumer<String> setter) {
        up.headers().firstValue(name).ifPresent(setter);
    }
}
