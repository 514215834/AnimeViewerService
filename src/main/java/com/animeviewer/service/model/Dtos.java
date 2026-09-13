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

    public record DownloadSettingsDto(String enginePath, String engineUrl, String engineSecret,
                                      Integer rpcPort, String downloadDir, Integer maxConcurrent,
                                      String uploadLimit, List<String> trackers, boolean autoScan,
                                      int seedTimeMinutes, boolean checkCertificate) {}
}
