package com.animeviewer.service.hanime;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.bootstrap.Startup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v0.33 V1（前端迭代文档 §5U）在线解析人机校验反向代理。
 *  背景（2026-10-11 实证，§5T 补记八）：cf_clearance 按「域名区域 + 出口 IP + User-Agent」三重绑定，
 *  手动粘贴 Cookie 只能对齐 UA 一项且体验差；浏览器同源策略又禁止 Web 端读取跨站 Cookie
 *  （cf_clearance 还带 HttpOnly）——纯 Web 形态下唯一可自动回填的路径是让校验流量经过服务端：
 *  浏览器弹窗打开本服务 /api/hanime/verify/{token}/（校验期反向代理），Cloudflare 看到的出口
 *  正是服务端后续抓取所用的同一代理出口（IP 绑定天然一致），UA 用浏览器真实值转发并在成功时
 *  落库（UA 绑定一致），Cookie 从流经的 Set-Cookie 自动捕获入 jar 并落库——用户只需在弹窗里
 *  点一下人机验证。
 *
 *  设计要点：
 *  - 仅转发 hanime1.me（.com 已 301 并入，直接以 .me 为唯一目标）；仅走代理线路（直连 DNS 污染不可达）；
 *  - Cookie jar 种子取自既有配置（保留登录态），捕获 Set-Cookie 增量合并，成功时写回 config（含 cookieUpdatedAt）；
 *  - Location / 页面内站点绝对 URL 重写回本代理路径（带 token 段），HTML 注入 {@code <base>} 让站点
 *    根相对资源（含 Cloudflare /cdn-cgi/**）自动落回代理命名空间；
 *  - 成功判据：HTML 响应不含质询/封锁标记 → 持久化（幂等：与上次保存的 jar+UA 相同则跳过写库）；
 *  - 已知风险（如实登记 §5U）：质询求解请求经 Java HttpClient TLS 转发，若 Cloudflare 按 TLS 指纹
 *    拒绝求解，弹窗内会呈现封锁/循环质询——该形态只能实机判定，失败时回退手动粘贴（字段保留）。 */
@Service
public class HanimeVerifyService {

    private static final Logger log = LoggerFactory.getLogger(HanimeVerifyService.class);

    public static final String VERIFY_PREFIX = "/api/hanime/verify/";
    private static final String SITE_HOST = "https://hanime1.me";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_HTML_BYTES = 4 * 1024 * 1024;
    private static final String CHALLENGE_MARKER = "Just a moment";
    private static final String BLOCKED_MARKER_1 = "you have been blocked";
    private static final String BLOCKED_MARKER_2 = "Attention Required";

    /** 透传上游的请求头白名单（其余一律不透传：Host/Cookie/Referer/Origin 由本服务按语义重建） */
    private static final List<String> PASS_HEADERS = List.of(
            "accept", "accept-language", "cache-control", "pragma", "upgrade-insecure-requests",
            "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-fetch-user",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform");

    private final HanimeService hanimeService;
    private final Startup startup;
    private final ServiceProperties props;
    private final HttpClient proxyClient;

    /** Cookie jar：种子 = 既有配置 cookie；捕获 Set-Cookie 增量合并；成功后整体写回配置 */
    private final Map<String, String> cookieJar = new LinkedHashMap<>();
    private volatile boolean jarSeeded = false;
    private volatile String lastSavedCookie = "";
    private volatile String lastSavedUa = "";

    public HanimeVerifyService(HanimeService hanimeService, Startup startup, ServiceProperties props) {
        this.hanimeService = hanimeService;
        this.startup = startup;
        this.props = props;
        this.proxyClient = buildProxyClient(props);
    }

    private static HttpClient buildProxyClient(ServiceProperties props) {
        String host = props.bangumi().proxyHost();
        Integer port = props.bangumi().proxyPort();
        HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10));
        if (host != null && !host.isBlank() && port != null) {
            b.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
        }
        return b.build();
    }

    /** 常量时间 Token 校验（路径段 token，弹窗地址栏携带；与 TokenAuthFilter 同口径但独立判定） */
    public boolean tokenValid(String provided) {
        String expected = startup.token();
        if (expected == null || expected.isBlank() || provided == null || provided.isBlank()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    /** 校验代理主入口：GET/POST 皆经此转发 */
    public void forward(HttpServletRequest req, HttpServletResponse resp, String subPath) throws IOException {
        String sitePath = subPath == null || subPath.isBlank() ? "/" : (subPath.startsWith("/") ? subPath : "/" + subPath);
        String query = req.getQueryString();
        String target = SITE_HOST + sitePath + (query == null || query.isBlank() ? "" : "?" + query);
        String visitorUa = req.getHeader("User-Agent");
        if (visitorUa == null || visitorUa.isBlank()) visitorUa = HanimeService.DEFAULT_UA;

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target)).timeout(FETCH_TIMEOUT)
                .header("User-Agent", visitorUa)
                .header("Referer", SITE_HOST + "/")
                .header("Cookie", jarCookieHeader())
                .header("Origin", SITE_HOST)
                .GET();
        if ("POST".equalsIgnoreCase(req.getMethod())) {
            byte[] body = req.getInputStream().readAllBytes();
            rb = HttpRequest.newBuilder(URI.create(target)).timeout(FETCH_TIMEOUT)
                    .header("User-Agent", visitorUa)
                    .header("Referer", SITE_HOST + "/")
                    .header("Cookie", jarCookieHeader())
                    .header("Origin", SITE_HOST)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            String ct = req.getHeader("Content-Type");
            if (ct != null && !ct.isBlank()) rb.header("Content-Type", ct);
        }
        for (String h : PASS_HEADERS) {
            String v = req.getHeader(h);
            if (v != null && !v.isBlank()) rb.header(h, v);
        }

        HttpResponse<byte[]> res;
        try {
            res = proxyClient.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("校验代理转发被中断", e);
        } catch (IOException e) {
            log.info("校验代理抓取失败（{}）——{}", target, e.toString());
            resp.sendError(502, "校验代理抓取失败：" + e.getMessage());
            return;
        }

        captureSetCookies(res.headers().allValues("set-cookie"));

        // 3xx：Location 重写回本代理命名空间（保持浏览器全程在代理空间内，jar 才能持续捕获）
        if (res.statusCode() >= 300 && res.statusCode() < 400) {
            String location = res.headers().firstValue("location").orElse("/");
            resp.setStatus(res.statusCode());
            resp.setHeader("Location", rewriteUrl(location, req));
            return;
        }

        String contentType = res.headers().firstValue("content-type").orElse("application/octet-stream");
        if (contentType.contains("text/html")) {
            byte[] body = res.body();
            if (body.length > MAX_HTML_BYTES) {
                resp.sendError(502, "校验代理页面超出大小上限");
                return;
            }
            String html = new String(body, StandardCharsets.UTF_8);
            boolean challenge = html.contains(CHALLENGE_MARKER);
            boolean blocked = html.contains(BLOCKED_MARKER_1) || html.contains(BLOCKED_MARKER_2);
            if (!challenge && !blocked) {
                persistIfChanged(visitorUa);
            } else {
                log.info("校验窗口呈现{}（jar={}）", challenge ? "人机质询" : "封锁页", cookieJar.keySet());
            }
            html = rewriteUrl(html, req);
            html = injectBase(html, req);
            resp.setStatus(res.statusCode());
            resp.setContentType(contentType);
            resp.setCharacterEncoding("UTF-8");
            resp.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
            return;
        }

        // 非 HTML（css/js/图片等站点资源）：透传
        resp.setStatus(res.statusCode());
        if (contentType != null && !contentType.isBlank()) resp.setContentType(contentType);
        byte[] body = res.body();
        resp.setContentLength(body.length);
        try (OutputStream os = resp.getOutputStream()) {
            os.write(body);
        }
    }

    /* ── Cookie jar ── */

    private synchronized String jarCookieHeader() {
        seedJarIfNeeded();
        if (cookieJar.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookieJar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private synchronized void seedJarIfNeeded() {
        if (jarSeeded) return;
        jarSeeded = true;
        HanimeService.Config cfg = hanimeService.config();
        lastSavedCookie = cfg.cookie() == null ? "" : cfg.cookie();
        lastSavedUa = cfg.ua() == null ? "" : cfg.ua();
        mergeCookies(lastSavedCookie);
    }

    private synchronized void mergeCookies(String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (!name.isEmpty()) cookieJar.put(name, value);
        }
    }

    private synchronized void captureSetCookies(List<String> setCookies) {
        for (String sc : setCookies) {
            String pair = sc.contains(";") ? sc.substring(0, sc.indexOf(';')) : sc;
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            // 过期删除语义：Max-Age=0 / 空值时移除
            if (value.isEmpty() || sc.toLowerCase().contains("max-age=0")) cookieJar.remove(name);
            else cookieJar.put(name, value);
        }
    }

    /** 成功判据达成：jar + UA 写回配置（幂等——与上次保存一致则跳过，前端轮询靠 cookieUpdatedAt 变化感知） */
    private synchronized void persistIfChanged(String visitorUa) {
        String cookie = jarCookieHeader();
        if (cookie.isBlank()) return;
        if (cookie.equals(lastSavedCookie) && visitorUa.equals(lastSavedUa)) return;
        lastSavedCookie = cookie;
        lastSavedUa = visitorUa;
        try {
            hanimeService.saveVerifyResult(visitorUa, cookie);
            log.info("校验通过：Cookie（{} 项）与 UA 已自动写入在线解析配置", cookieJar.size());
        } catch (Exception e) {
            log.warn("校验结果写库失败：{}", e.toString());
        }
    }

    /* ── URL 重写 ── */

    /** 站点绝对/协议相对 URL → 本代理路径（HTML 正文与 Location 共用）；其余（CDN 域等）原样 */
    private String rewriteUrl(String s, HttpServletRequest req) {
        // token 段从请求 URI 提取：/api/hanime/verify/{token}/...
        String uri = req.getRequestURI();
        String rest = uri.substring(VERIFY_PREFIX.length());
        String token = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        String prefix = VERIFY_PREFIX + token;
        return s.replace("https://hanime1.me", prefix)
                .replace("https://hanime1.com", prefix)
                .replace("http://hanime1.me", prefix)
                .replace("http://hanime1.com", prefix)
                .replace("https:\\/\\/hanime1.me", prefix.replace("/", "\\/"))
                .replace("https:\\/\\/hanime1.com", prefix.replace("/", "\\/"))
                .replace("//hanime1.me", prefix)
                .replace("//hanime1.com", prefix);
    }

    /** 注入 {@code <base>}：站点根相对资源（/cdn-cgi/**、/assets/**）自动落回代理命名空间 */
    private String injectBase(String html, HttpServletRequest req) {
        String uri = req.getRequestURI();
        String rest = uri.substring(VERIFY_PREFIX.length());
        String token = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        String base = "<base href=\"" + VERIFY_PREFIX + token + "/\">";
        if (html.contains("<base ")) return html;
        int idx = html.indexOf("<head");
        if (idx < 0) return html;
        int gt = html.indexOf('>', idx);
        if (gt < 0) return html;
        return html.substring(0, gt + 1) + base + html.substring(gt + 1);
    }
}
