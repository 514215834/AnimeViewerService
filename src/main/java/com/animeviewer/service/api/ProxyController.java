package com.animeviewer.service.api;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.bootstrap.Startup;
import com.animeviewer.service.stream.PlaylistRewriter;
import com.animeviewer.service.stream.ProxyGuard;
import com.animeviewer.service.stream.RangeForwarder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/** v0.15 O2 流代理：GET /api/proxy?url=（token 经 ?token=，与 stream 一致）。
 *  仅转发用户在前端手动添加的源（服务端不存任何源清单、无内置源）；域名白名单空 = 代理禁用（403），
 *  防开放代理/SSRF。直链按 Range 语义流式透传，m3u8 清单重写为代理地址（含 token，子清单递归经代理）。 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final ServiceProperties props;
    private final Startup startup;
    private final RangeForwarder forwarder;
    private final Semaphore permits;

    public ProxyController(ServiceProperties props, Startup startup, RangeForwarder forwarder) {
        this.props = props;
        this.startup = startup;
        this.forwarder = forwarder;
        this.permits = new Semaphore(Math.max(1, props.proxy().maxConcurrent()));
    }

    @GetMapping("/api/proxy")
    public void proxy(@RequestParam("url") String url,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException, InterruptedException {
        URI target;
        try {
            target = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            response.sendError(400, "URL 不合法");
            return;
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!ProxyGuard.schemeAllowed(scheme)) {
            response.sendError(400, "仅支持 http/https 源");
            return;
        }
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        if (!ProxyGuard.hostAllowed(host, props.proxy().allowedHosts())) {
            if (props.proxy().allowedHosts() == null || props.proxy().allowedHosts().stream().allMatch(String::isBlank)) {
                response.sendError(403, "流代理未启用：请在服务配置 av.proxy.allowed-hosts 配置域名白名单");
            } else {
                response.sendError(403, "该域名不在代理白名单内（av.proxy.allowed-hosts）");
            }
            return;
        }

        if (!permits.tryAcquire()) {
            response.sendError(503, "代理并发已达上限（" + maxConcurrent() + "），请稍后重试");
            return;
        }
        try {
            RangeForwarder.Outcome outcome = forwarder.forward(
                    target.toString(),
                    request.getHeader("Range"),
                    response,
                    // 清单文本 → 重写文本：内部对每个资源 URI 调 proxyUrlOf 包裹为本服务代理地址
                    text -> PlaylistRewriter.rewrite(text, target.toString(), absolute -> proxyUrlOf(request, absolute)),
                    null);
            log.debug("代理转发 {} → {}", outcome, target);
        } catch (IOException e) {
            if (response.isCommitted()) {
                // 客户端断开 / 响应已部分写出：无法再改状态码，仅记录
                log.debug("代理转发中断: {} ({})", target, e.toString());
            } else {
                response.sendError(502, "上游请求失败：" + e.getMessage());
            }
        } finally {
            permits.release();
        }
    }

    /** 重写函数：相对地址已由重写器转绝对，此处包一层本服务代理地址（token 带入，<video>/hls.js 无法带自定义头）。
     *  基地址取自本次请求（scheme://host:port），局域网多设备访问时自动适配。 */
    private String proxyUrlOf(HttpServletRequest request, String absoluteUrl) {
        StringBuilder base = new StringBuilder()
                .append(request.getScheme()).append("://").append(request.getServerName());
        int port = request.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(request.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(request.getScheme()) && port == 443);
        if (!defaultPort) base.append(':').append(port);
        return base.append("/api/proxy?token=").append(startup.token())
                .append("&url=").append(URLEncoder.encode(absoluteUrl, StandardCharsets.UTF_8))
                .toString();
    }

    private int maxConcurrent() {
        return Math.max(1, props.proxy().maxConcurrent());
    }
}
