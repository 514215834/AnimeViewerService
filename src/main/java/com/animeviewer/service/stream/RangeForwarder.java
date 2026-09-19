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
    private final ServiceProperties props;
    private final String userAgent;
    /** v0.26 HN3 在线视频容灾转发的粘性线路记忆（true = 上次成功走代理） */
    private volatile boolean failoverUsedProxy = false;
    private volatile HttpClient proxyClient;

    public RangeForwarder(ServiceProperties props, HttpClient client) {
        this.props = props;
        this.client = client;
        this.userAgent = props.bangumi().userAgent();
    }

    /** 转发结果：STREAMED（已按 Range 语义流式回写）/ PLAYLIST（已重写为代理清单文本回写） */
    public enum Outcome { STREAMED, PLAYLIST }

    /**
     * @param playlistRewriter 非 null 时启用清单改写（传入「清单文本→重写文本」函数，内部逐 URI 包裹）；null 一律按流转发
     * @param extraHeaders     附加请求头（如 WebDAV Basic 授权；同名覆盖内置头）；可为 null
     * @return 本次转发形态
     */
    public Outcome forward(String targetUrl,
                           String rangeHeader,
                           HttpServletResponse response,
                           UnaryOperator<String> playlistRewriter,
                           java.util.Map<String, String> extraHeaders) throws IOException, InterruptedException {
        return forwardWith(client, targetUrl, rangeHeader, response, playlistRewriter, extraHeaders);
    }

    /** v0.26 HN3 直连→代理容灾转发（仅 hanime 在线视频使用，既有 forward() 行为不变）：
     *  线路口径对齐 ResourceService.fetchViaFailover——IOException 行级失败（DNS 污染超时/拒绝、
     *  TLS 握手掐断、重置）切线重试一次，成功线路粘性记忆；HTTP 层非 200 原样透传给客户端（不切线）。 */
    public Outcome forwardFailover(String targetUrl,
                                   String rangeHeader,
                                   HttpServletResponse response,
                                   UnaryOperator<String> playlistRewriter,
                                   java.util.Map<String, String> extraHeaders) throws IOException, InterruptedException {
        boolean proxyOnly = "proxy".equalsIgnoreCase(props.bangumi().proxyMode());
        boolean directOnly = "direct".equalsIgnoreCase(props.bangumi().proxyMode());
        IOException last = null;
        boolean tryProxy = failoverUsedProxy && !directOnly;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean useProxy = proxyOnly || (tryProxy && !directOnly);
            HttpClient hc = client;
            if (useProxy) {
                hc = proxyClient();
                if (hc == null) {
                    tryProxy = false;
                    continue;
                }
            }
            try {
                Outcome out = forwardWith(hc, targetUrl, rangeHeader, response, playlistRewriter, extraHeaders);
                failoverUsedProxy = useProxy;
                return out;
            } catch (IOException e) {
                last = e;
                log.info("在线视频{}失败（{}），切换为{}重试",
                        useProxy ? "经代理转发" : "直连转发", e.toString(), useProxy ? "直连" : "经代理");
                tryProxy = !useProxy;
            }
        }
        throw last != null ? last : new IOException("转发失败");
    }

    /** av.bangumi 代理地址的 HttpClient（懒加载；未配置代理返回 null） */
    private HttpClient proxyClient() {
        if (proxyClient != null) return proxyClient;
        String host = props.bangumi().proxyHost();
        Integer port = props.bangumi().proxyPort();
        if (host == null || host.isBlank() || port == null) return null;
        synchronized (this) {
            if (proxyClient == null) {
                proxyClient = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(host, port)))
                        .build();
            }
            return proxyClient;
        }
    }

    private Outcome forwardWith(HttpClient hc,
                                String targetUrl,
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
        // setHeader = 同名覆盖内置头（hanime 流转发以浏览器 UA/Referer 覆盖默认 UA；
        // 既有 WebDAV 授权单值头行为不变）
        if (extraHeaders != null) extraHeaders.forEach(rb::setHeader);

        HttpResponse<InputStream> up = hc.send(rb.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
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
