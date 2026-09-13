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
        Webdav webdav,
        Aria2 aria2
) {
    public record Bangumi(String baseUrl, String userAgent, long matchThrottleMs,
                          String proxyHost, Integer proxyPort, String proxyMode) {}

    public record Scan(boolean autoOnStart, int probeTimeoutSeconds) {}

    public record Remux(int maxConcurrent) {}

    /** v0.15 O2 流代理：allowedHosts 域名白名单（空 = 代理禁用，返回 403）；maxConcurrent 并发上限（超限 503） */
    public record Proxy(List<String> allowedHosts, Integer maxConcurrent) {}

    /** v0.15 O3 WebDAV：streamId 会话有效期（分钟），凭据只在 POST 体流转 */
    public record Webdav(Integer sessionTtlMinutes) {}

    /**
     * v0.16 下载引擎（aria2）yml 默认值——运行期可经 /api/downloads/settings 覆盖（存 SQLite，
     * 前端设置页可改）。externalUrl 非空 = 对接外部 aria2 实例；否则托管拉起 path 指定的可执行文件。
     * checkCertificate 默认 false：Windows 版 aria2 走 schannel，启用吊销检查时 HTTPS tracker
     * 因「吊销服务器不可达」握手失败（v0.16 原型实测 80092013），个人内网工具默认关闭并如实记录。
     */
    public record Aria2(String path, String externalUrl, String externalSecret, Integer rpcPort,
                        String downloadDir, Integer maxConcurrent, String uploadLimit,
                        List<String> trackers, boolean autoScan, Integer seedTimeMinutes,
                        boolean checkCertificate) {}

    public ServiceProperties {
        if (dataDir == null || dataDir.isBlank()) dataDir = "./data";
        if (ffmpegPath == null || ffmpegPath.isBlank()) ffmpegPath = "ffmpeg";
        if (ffprobePath == null || ffprobePath.isBlank()) ffprobePath = "ffprobe";
        if (bangumi == null) bangumi = new Bangumi("https://api.bgm.tv", "AnimeViewerService/0.14", 400, "127.0.0.1", 7897, "auto");
        if (scan == null) scan = new Scan(true, 30);
        if (remux == null) remux = new Remux(3);
        if (proxy == null) proxy = new Proxy(List.of(), 6);
        if (webdav == null) webdav = new Webdav(10);
        if (aria2 == null) aria2 = new Aria2("aria2c", "", "", 16800, "./data/downloads", 2, "",
                List.of(), true, 0, false);
    }
}
