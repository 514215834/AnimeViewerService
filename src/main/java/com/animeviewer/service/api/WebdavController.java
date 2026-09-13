package com.animeviewer.service.api;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.model.Dtos.WebdavBrowseDto;
import com.animeviewer.service.model.Dtos.WebdavBrowseRequest;
import com.animeviewer.service.model.Dtos.WebdavEntryDto;
import com.animeviewer.service.model.Dtos.WebdavOpenDto;
import com.animeviewer.service.model.Dtos.WebdavOpenRequest;
import com.animeviewer.service.stream.RangeForwarder;
import com.animeviewer.service.webdav.WebdavParser;
import com.animeviewer.service.webdav.WebdavSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** v0.15 O3 WebDAV 源（实验）：只读浏览 + 流式播放。
 *  凭据仅随 POST 体进入并缓存在内存会话（streamId 短 TTL），绝不进 URL / 播放地址；
 *  浏览为 PROPFIND Depth:1，播放复用流代理的 Range 转发内核。不做上传/删除等写操作。 */
@RestController
@RequestMapping("/api/webdav")
public class WebdavController {

    private static final Logger log = LoggerFactory.getLogger(WebdavController.class);

    private static final String PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop>
            <D:resourcetype/><D:getcontentlength/><D:getlastmodified/>
            </D:prop></D:propfind>""";

    private final HttpClient client;
    private final String userAgent;
    private final WebdavSessionStore sessions;
    private final RangeForwarder forwarder;

    public WebdavController(ServiceProperties props, HttpClient client,
                            WebdavSessionStore sessions, RangeForwarder forwarder) {
        this.client = client;
        this.userAgent = props.bangumi().userAgent();
        this.sessions = sessions;
        this.forwarder = forwarder;
    }

    /** 列目录：url=WebDAV 根（可含子路径），path=当前目录（/ 开头的原始路径，可含中文/空格——逐段百分号编码后拼接） */
    @PostMapping("/browse")
    public WebdavBrowseDto browse(@RequestBody WebdavBrowseRequest req) throws IOException, InterruptedException {
        String root = requireUrl(req.url());
        String target = joinPath(root, normalizePath(req.path()));
        HttpResponse<String> up = propfind(target, req.username(), req.password(), 1);
        requireSuccess(up, target);

        List<WebdavEntryDto> list = new ArrayList<>();
        String selfPath = pathOf(target);
        for (WebdavParser.Entry e : WebdavParser.parse(up.body())) {
            if (pathOf(e.href()).equals(selfPath)) continue; // Depth:1 自身行排除
            list.add(new WebdavEntryDto(e.name(), e.dir(), e.size(), e.mtime()));
        }
        // 目录在前，名称自然序
        list.sort(Comparator.comparing((WebdavEntryDto x) -> !x.dir()).thenComparing(x -> x.name().toLowerCase(Locale.ROOT)));
        return new WebdavBrowseDto(normalizePath(req.path()), list);
    }

    /** 校验连通与凭据，签发短时 streamId（播放时 GET /api/webdav/stream/{streamId}） */
    @PostMapping("/open")
    public WebdavOpenDto open(@RequestBody WebdavOpenRequest req) throws IOException, InterruptedException {
        String root = requireUrl(req.url());
        HttpResponse<String> up = propfind(root, req.username(), req.password(), 0);
        requireSuccess(up, root);
        String streamId = sessions.create(root, req.username() == null ? "" : req.username(),
                req.password() == null ? "" : req.password());
        log.debug("WebDAV 会话签发: {} → {}", root, streamId);
        return new WebdavOpenDto(streamId);
    }

    /** 播放流：按会话内缓存的凭据直接 GET 上游文件，Range 透传（复用流代理转发内核） */
    @GetMapping("/stream/{streamId}")
    public void stream(@PathVariable String streamId,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException, InterruptedException {
        WebdavSessionStore.Session session = sessions.get(streamId);
        if (session == null) {
            response.sendError(410, "WebDAV 会话已过期，请回播放页重新进入");
            return;
        }
        try {
            String cred = "Basic " + Base64.getEncoder().encodeToString(
                    (session.username() + ":" + session.password()).getBytes(StandardCharsets.UTF_8));
            forwarder.forward(session.url(), request.getHeader("Range"), response, null,
                    session.username().isBlank() ? null : java.util.Map.of("Authorization", cred));
        } catch (IOException e) {
            if (response.isCommitted()) {
                log.debug("WebDAV 流中断: {} ({})", session.url(), e.toString());
            } else {
                response.sendError(502, "WebDAV 上游请求失败：" + e.getMessage());
            }
        }
    }

    /** 参数/上游语义错误 → 400 + message（前端 request() 归一展示） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage() == null ? "请求不合法" : e.getMessage()));
    }

    /* ── 内部 ── */

    private HttpResponse<String> propfind(String url, String username, String password, int depth)
            throws IOException, InterruptedException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Depth", String.valueOf(depth))
                .header("Content-Type", "application/xml")
                .header("User-Agent", userAgent);
        if (username != null && !username.isBlank()) {
            String cred = Base64.getEncoder().encodeToString(
                    (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + cred);
        }
        return client.send(rb.method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void requireSuccess(HttpResponse<String> up, String target) {
        int status = up.statusCode();
        if (status == 207 || status == 200) return;
        if (status == 401) throw new IllegalArgumentException("WebDAV 账号或密码不正确（上游 401）");
        if (status == 403) throw new IllegalArgumentException("WebDAV 拒绝访问（上游 403）");
        if (status == 404) throw new IllegalArgumentException("WebDAV 路径不存在（上游 404）");
        throw new IllegalArgumentException("WebDAV 上游错误（HTTP " + status + "）：" + target);
    }

    private static String requireUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("WebDAV 地址为空");
        String trimmed = url.trim();
        URI u = URI.create(trimmed);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("仅支持 http/https WebDAV 地址");
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || path.equals("/")) return "/";
        String p = path.startsWith("/") ? path : "/" + path;
        return p.endsWith("/") ? p : p + "/";
    }

    /** 根地址 + 原始路径 → 合法 URI：路径逐段百分号编码（URLEncoder 的 + 空格还原为 %20） */
    static String joinPath(String root, String rawPath) {
        String base = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        StringBuilder sb = new StringBuilder(base);
        boolean any = false;
        for (String seg : rawPath.split("/")) {
            if (seg.isEmpty()) continue;
            any = true;
            sb.append('/').append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return any ? sb.toString() : base + "/";
    }

    /** 目标 URL 的解码路径（self 过滤用），尾斜杠归一 */
    private static String pathOf(String href) {
        String s = href;
        try {
            s = URI.create(href.trim()).getPath();
        } catch (IllegalArgumentException ignored) {
        }
        if (s == null) s = href;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
