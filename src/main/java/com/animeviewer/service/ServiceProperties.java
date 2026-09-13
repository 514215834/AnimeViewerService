package com.animeviewer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** av.* 配置项（application.yml 全部带默认值，均可用启动参数 / 环境变量覆盖） */
@ConfigurationProperties("av")
public record ServiceProperties(
        String dataDir,
        String ffmpegPath,
        String ffprobePath,
        Bangumi bangumi,
        Scan scan,
        Remux remux,
        Proxy proxy,
        Webdav webdav
) {
    public record Bangumi(String baseUrl, String userAgent, long matchThrottleMs,
                          String proxyHost, Integer proxyPort, String proxyMode) {}

    public record Scan(boolean autoOnStart, int probeTimeoutSeconds) {}

    public record Remux(int maxConcurrent) {}

    /** v0.15 O2 流代理：allowedHosts 域名白名单（空 = 代理禁用，返回 403）；maxConcurrent 并发上限（超限 503） */
    public record Proxy(List<String> allowedHosts, Integer maxConcurrent) {}

    /** v0.15 O3 WebDAV：streamId 会话有效期（分钟），凭据只在 POST 体流转 */
    public record Webdav(Integer sessionTtlMinutes) {}

    public ServiceProperties {
        if (dataDir == null || dataDir.isBlank()) dataDir = "./data";
        if (ffmpegPath == null || ffmpegPath.isBlank()) ffmpegPath = "ffmpeg";
        if (ffprobePath == null || ffprobePath.isBlank()) ffprobePath = "ffprobe";
        if (bangumi == null) bangumi = new Bangumi("https://api.bgm.tv", "AnimeViewerService/0.14", 400, "127.0.0.1", 7897, "auto");
        if (scan == null) scan = new Scan(true, 30);
        if (remux == null) remux = new Remux(3);
        if (proxy == null) proxy = new Proxy(List.of(), 6);
        if (webdav == null) webdav = new Webdav(10);
    }
}
