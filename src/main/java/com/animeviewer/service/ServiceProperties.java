package com.animeviewer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** av.* 配置项（application.yml 全部带默认值，均可用启动参数 / 环境变量覆盖） */
@ConfigurationProperties("av")
public record ServiceProperties(
        String dataDir,
        String ffmpegPath,
        String ffprobePath,
        Bangumi bangumi,
        Scan scan,
        Remux remux
) {
    public record Bangumi(String baseUrl, String userAgent, long matchThrottleMs,
                          String proxyHost, Integer proxyPort) {}

    public record Scan(boolean autoOnStart, int probeTimeoutSeconds) {}

    public record Remux(int maxConcurrent) {}

    public ServiceProperties {
        if (dataDir == null || dataDir.isBlank()) dataDir = "./data";
        if (ffmpegPath == null || ffmpegPath.isBlank()) ffmpegPath = "ffmpeg";
        if (ffprobePath == null || ffprobePath.isBlank()) ffprobePath = "ffprobe";
        if (bangumi == null) bangumi = new Bangumi("https://api.bgm.tv", "AnimeViewerService/0.14", 400, "", null);
        if (scan == null) scan = new Scan(true, 30);
        if (remux == null) remux = new Remux(3);
    }
}
