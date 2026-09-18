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

    public record ResourceItemDto(String title, String magnet, String infoHash, String site,
                                  String size, String category, String publisher, Long pubDate,
                                  String link) {}

    public record ResourceSiteDto(String key, String name, String baseUrl, String searchTemplate,
                                  boolean builtin) {}

    public record ResourceSearchDto(String keyword, List<ResourceItemDto> items,
                                    List<ResourceSiteDto> sites, String error) {}

    public record ResourceAddRequest(String magnet, Long subjectId, String subjectName,
                                     String subjectNameCn, Integer episodeSort) {}

    /* ── v0.19 SU1/SU2/SU3 订阅自动化（条目级订阅 + 命中审核 + 通知汇总）── */

    public record SubscriptionDto(long id, long subjectId, String subjectName, String subjectNameCn,
                                  boolean auto, int minEpisode, List<String> ignoredFansubs,
                                  Integer autoScore, String lastCheckError,
                                  Long lastCheckedAt, Long lastHitAt, long createdAt,
                                  List<String> aiKeywords) {}

    public record SubscriptionAddRequest(Long subjectId, String subjectName, String subjectNameCn,
                                         Integer minEpisode, Integer autoScore) {}

    /** 可选字段 PATCH 语义：null = 不改；autoScore 为 v0.20 匹配度阈值（0=全手动特殊值，1~100 自动入队）；
     *  aiKeywords 为 v0.22 AI2 扩展检索词（LLM 生成缓存/人工编辑，逐词 ≤100 字符、至多 10 条） */
    public record SubscriptionUpdateRequest(Integer autoScore, Integer minEpisode, List<String> aiKeywords) {}

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
}
