package com.animeviewer.service.model;

import java.util.List;

/** API DTO 集中定义（record 直出 JSON，字段名与前端 TypeScript 类型一一对应） */
public final class Dtos {

    private Dtos() {}

    public record Health(String name, String version, boolean ffmpeg, boolean ffprobe) {}

    public record ServiceStatus(
            String version, boolean ffmpeg, boolean ffprobe,
            long directories, long files, long bound, long pending, long unmatched,
            ScanStatus scan) {}

    public record ScanStatus(
            boolean running, String phase, long scanned, long added, long updated, long removed,
            long matched, long failed, String currentPath, Long startedAt, Long finishedAt, String lastError,
            long matchTotal, long matchDone) {}

    public record DirectoryDto(long id, String path, boolean enabled, long fileCount, Long createdAt) {}

    public record Page<T>(List<T> items, long total, int limit, int offset) {}

    public record MediaFileDto(
            long id, long dirId, String path, String name, String ext, long size, long mtime,
            Double durationSec, String container, String vcodec, String acodec,
            Integer width, Integer height,
            String parsedTitle, Integer parsedEpisode,
            String matchState, Long subjectId, String subjectName, String subjectNameCn,
            Integer episodeSort, boolean autoBound, Long matchedAt, Long probedAt, String error,
            Long downloadTaskId, String downloadTaskName) {}

    public record SubjectFileDto(
            long fileId, int sort, String name, Double durationSec, String ext, boolean direct) {}

    public record BangumiSubjectDto(long subjectId, String name, String nameCn, String date, String image) {}

    public record BangumiEpisodeDto(long episodeId, int sort, int type, String name, String nameCn) {}

    public record MatchOutcome(String state, Long subjectId, String subjectName, String subjectNameCn,
                               boolean exact, String message) {}

    public record MatchRequest(long subjectId, int sort) {}

    public record ScanRequest(Boolean full) {}

    public record DirectoryRequest(String path) {}

    /* ── v0.15 O3 WebDAV（凭据只随 POST 体流转，不出现在响应/URL）── */

    public record WebdavBrowseRequest(String url, String username, String password, String path) {}

    public record WebdavOpenRequest(String url, String username, String password) {}

    public record WebdavEntryDto(String name, boolean dir, Long size, Long mtime) {}

    public record WebdavBrowseDto(String path, List<WebdavEntryDto> list) {}

    public record WebdavOpenDto(String streamId) {}

    /* ── v0.16 DN1/DN2 下载中心（aria2 引擎 + 任务）── */

    public record DownloadFileDto(int index, String path, String name, long length,
                                  long completedLength, boolean selected) {}

    public record DownloadTaskDto(
            long id, String gid, String infoHash, String name, String uri,
            Long subjectId, String subjectName, String subjectNameCn, Integer episodeSort,
            String status, long totalLength, long completedLength, long downloadSpeed, long uploadSpeed,
            int connections, int seeds, List<DownloadFileDto> files, String error,
            Long createdAt, Long completedAt) {}

    public record DownloadAddRequest(String uri, Long subjectId, String subjectName,
                                     String subjectNameCn, Integer episodeSort) {}

    public record DownloadSelectionRequest(List<Integer> indexes) {}

    public record DownloadRemoveRequest(Boolean deleteFiles) {}

    public record DownloadEngineDto(boolean available, String mode, String version,
                                    String downloadDir, String error) {}

    /** v0.18：engineType=aria2-managed/aria2-external/qbittorrent；qbPath 仅直开模式消费 */
    public record DownloadSettingsDto(String engineType, String enginePath, String engineUrl, String engineSecret,
                                      Integer rpcPort, String qbPath,
                                      String downloadDir, Integer maxConcurrent,
                                      String uploadLimit, List<String> trackers, boolean autoScan,
                                      int seedTimeMinutes, boolean checkCertificate) {}

    /* ── v0.17 R1/R2 资源发现（RSS 站点源 + 条目找资源）── */

    /** v0.24 SE1：magnet 可空（种子型站点 nyaa/蜜柑 enclosure 为 .torrent 直链，无磁力）；
     *  torrentUrl 兜底承载种子直链，入队/订阅链路对二选一自适应（有磁力优先磁力）。 */
    public record ResourceItemDto(String title, String magnet, String infoHash, String site,
                                  String size, String category, String publisher, Long pubDate,
                                  String link, String torrentUrl) {}

    /** v0.24 SM5：enabled 启停（null=缺省启用，兼容旧 JSON）；写路径 saveSite 消费、listSites 回显 */
    public record ResourceSiteDto(String key, String name, String baseUrl, String searchTemplate,
                                  boolean builtin, Boolean enabled) {}

    public record ResourceSearchDto(String keyword, List<ResourceItemDto> items,
                                    List<ResourceSiteDto> sites, String error) {}

    /** v0.24 SM3 站点测试连通（不入库）：ok=false 时 error 为失败原因；samples 为解析样例（前 3 条） */
    public record ResourceSiteTestDto(boolean ok, String error, int itemCount,
                                      List<ResourceItemDto> samples) {}

    public record ResourceAddRequest(String magnet, Long subjectId, String subjectName,
                                     String subjectNameCn, Integer episodeSort) {}

    /* ── v0.19 SU1/SU2/SU3 订阅自动化（条目级订阅 + 命中审核 + 通知汇总）── */

    public record SubscriptionDto(long id, long subjectId, String subjectName, String subjectNameCn,
                                  boolean auto, int minEpisode, List<String> ignoredFansubs,
                                  Integer autoScore, String lastCheckError,
                                  Long lastCheckedAt, Long lastHitAt, long createdAt,
                                  List<String> aiKeywords, String rssUrl) {}

    public record SubscriptionAddRequest(Long subjectId, String subjectName, String subjectNameCn,
                                         Integer minEpisode, Integer autoScore) {}

    /** 可选字段 PATCH 语义：null = 不改；autoScore 为 v0.20 匹配度阈值（0=全手动特殊值，1~100 自动入队）；
     *  aiKeywords 为 v0.22 AI2 扩展检索词（LLM 生成缓存/人工编辑，逐词 ≤100 字符、至多 10 条）；
     *  rssUrl 为 v0.25 RSS 固定直链订阅源（null = 不改；空串 = 清除回关键词检索；非空 = 设置，须 http(s)://） */
    public record SubscriptionUpdateRequest(Integer autoScore, Integer minEpisode, List<String> aiKeywords,
                                            String rssUrl) {}

    public record SubHitDto(long id, long subjectId, String subjectName, String subjectNameCn,
                            Integer episodeSort, String title, String fansub, String magnet, String infoHash,
                            String site, String size, Long pubDate, String status, String note,
                            Integer score, String scoreDetail, Long createdAt, Long decidedAt,
                            String aiVerdict) {}

    public record HitIgnoreRequest(Boolean blockFansub) {}

    public record HitBatchIgnoreRequest(List<Long> ids) {}

    public record HitBatchDeleteRequest(List<Long> ids) {}

    public record SubscriptionSettingsDto(int intervalMinutes, int minSizeMb, int autoDailyLimit,
                                          int autoMaxSizeMb, boolean autoOnlyMatched, int defaultAutoScore,
                                          List<String> globalFansubs, boolean skipEnqueuedEpisode) {}

    /** SU3 通知汇总（前端 60s 轮询）：待确认数 → 侧边栏角标；lastHit/lastCompleted 新于上次所见 → toast */
    public record DownloadSummaryDto(long pendingHits, long activeTasks, SubHitDto lastHit, TaskBrief lastCompleted) {}

    public record TaskBrief(long id, String name, String subjectName, String subjectNameCn, Long completedAt) {}

    /* ── v0.22 AI 分析剧集（AI0 Provider 设置 + AI1 命中语义判定）── */

    /** AI 设置（ready/callsThisHour 服务端只读回显，PUT 时忽略）；extraHeaders 每行「Name: Value」附加头 */
    public record AiSettingsDto(boolean enabled, String baseUrl, String model, String apiKey,
                                int timeoutSeconds, int maxCallsPerHour, boolean autoIgnoreNonEpisode,
                                boolean ready, int callsThisHour, String extraHeaders) {}

    /** AI3 文件名语义解析结果（LLM 判定 → 落 pending 待人工复核） */
    public record FileAnalyzeDto(String title, Integer episode) {}

    /* ── v0.23 SB1 内封字幕（枚举 + VTT 提取）── */

    /** 字幕轨：index 为字幕轨序号（0 基，字幕轨内排序，非流 index）；codec/language/title 可能缺省 */
    public record SubtitleTrackDto(int index, String codec, String language, String title) {}

    /* ── v0.26 HN1/HN2 hanime1.me 在线解析（配置 / 搜索 / 视频解析）── */

    /** 配置回显：cookie 不回传明文（只回 hasCookie） */
    public record HanimeConfigDto(boolean enabled, String ua, boolean hasCookie) {}

    /** 配置更新：null=不改动（cookie 空串=清除） */
    public record HanimeConfigUpdateRequest(Boolean enabled, String ua, String cookie) {}

    /** 搜索结果条目：videoCode 为 watch?v= 参数（播放/绑定的稳定键）；likes/views 为站点原始文案（如 "100%" / "38.2萬次"） */
    public record HanimeSearchItem(String videoCode, String title, String thumbnail, String duration,
                                   String likes, String views, String brand) {}

    public record HanimeSearchResult(int page, boolean hasNext, List<HanimeSearchItem> items) {}

    /** 视频源：label 为分辨率显示名（如 "1080p"），url 为带签名的 mp4 直链（有时效，勿长期缓存） */
    public record HanimeSource(String label, int res, String url) {}

    /** v0.27 A2 系列合集条目（watch 页侧栏播放列表项）：current=当前播放条目（前端高亮） */
    public record HanimePlaylistItem(String videoCode, String title, String thumbnail, String duration,
                                     boolean current) {}

    /** 侧栏播放列表（站点以「社團/系列」二态承载系列合集）：category 为顶部块分类文案（社團/系列），
     *  name 为列表归属名（上传者名等），total 为站点计数的影片数；无播放列表时为 null */
    public record HanimePlaylist(String category, String name, int total, List<HanimePlaylistItem> items) {}

    /** watch 页解析结果：sources 按分辨率降序（默认取首档）；brand 缺省时从标题前缀 [组名] 提取；
     *  playlist 为侧栏系列/社团合集（v0.27 A2，无侧栏时 null） */
    public record HanimeWatchDto(String videoCode, String title, String poster, String brand,
                                 List<String> tags, List<HanimeSource> sources, HanimePlaylist playlist) {}

    /** 连通测试：恒 200，ok=false 时 message 给三分类原因 */
    public record HanimeTestDto(boolean ok, String message, int itemCount) {}
}
