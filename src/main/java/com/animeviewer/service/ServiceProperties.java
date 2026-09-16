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
        Aria2 aria2,
        Subscription subscription,
        Ai ai
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

    /**
     * v0.19 SU1 订阅自动化 yml 默认值——运行期可经 /api/subscriptions/settings 覆盖（存 SQLite）。
     * intervalMinutes 定时检索间隔（30~360 分钟）；minSizeMb 资源大小下限滤广告（0 = 不限）；
     * 全自动三重保护：autoDailyLimit 每日自动入队上限 / autoMaxSizeMb 单任务大小上限（0 = 不限）/
     * autoOnlyMatched 仅已匹配条目（媒体库无绑定文件的全自动命中降级待确认）。
     * v0.20：defaultAutoScore 新订阅匹配度阈值默认值（0=全手动特殊值）；globalFansubs 全局字幕组偏好
     * （逗号分隔，评分加权 SU5）；skipEnqueuedEpisode 已入队同集忽略开关（SU6，null=默认开）。
     */
    public record Subscription(Integer intervalMinutes, Integer minSizeMb, Integer autoDailyLimit,
                               Integer autoMaxSizeMb, boolean autoOnlyMatched, Integer defaultAutoScore,
                               String globalFansubs, Boolean skipEnqueuedEpisode) {}

    /**
     * v0.22 AI0 AI 服务 yml 默认值——运行期可经 /api/ai/settings 覆盖（存 SQLite settings 表 key=ai）。
     * enabled 总开关（默认关：AI 关闭/失败时全链路行为与 v0.21 一致，零回归不变式）；
     * baseUrl OpenAI 兼容接口根地址（可含 /v1；本地 Ollama 形如 http://127.0.0.1:11434/v1）；
     * model 模型名；apiKey 可空（本地 Ollama 无鉴权）；timeoutSeconds 单次请求超时；
     * maxCallsPerHour 小时滚动配额护栏（0=不限）；autoIgnoreNonEpisode 命中语义判定非本篇时自动忽略（默认关）；
     * extraHeaders 逐请求附加头（换行分隔「Name: Value」，如 x-opencode-session: xxx——非标网关通道需要会话头）。
     */
    public record Ai(Boolean enabled, String baseUrl, String model, String apiKey,
                     Integer timeoutSeconds, Integer maxCallsPerHour, Boolean autoIgnoreNonEpisode,
                     String extraHeaders) {}

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
        if (subscription == null) subscription = new Subscription(60, 0, 5, 0, true, 0, "", null);
        if (ai == null) ai = new Ai(false, "https://api.openai.com/v1", "", "", 30, 60, false, "");
    }
}
