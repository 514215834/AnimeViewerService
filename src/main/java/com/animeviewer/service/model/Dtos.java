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
            long matched, long failed, String currentPath, Long startedAt, Long finishedAt, String lastError) {}

    public record DirectoryDto(long id, String path, boolean enabled, long fileCount, Long createdAt) {}

    public record Page<T>(List<T> items, long total, int limit, int offset) {}

    public record MediaFileDto(
            long id, long dirId, String path, String name, String ext, long size, long mtime,
            Double durationSec, String container, String vcodec, String acodec,
            Integer width, Integer height,
            String parsedTitle, Integer parsedEpisode,
            String matchState, Long subjectId, String subjectName, String subjectNameCn,
            Integer episodeSort, boolean autoBound, Long matchedAt, Long probedAt, String error) {}

    public record SubjectFileDto(
            long fileId, int sort, String name, Double durationSec, String ext, boolean direct) {}

    public record BangumiSubjectDto(long subjectId, String name, String nameCn, String date, String image) {}

    public record BangumiEpisodeDto(long episodeId, int sort, int type, String name, String nameCn) {}

    public record MatchOutcome(String state, Long subjectId, String subjectName, String subjectNameCn,
                               boolean exact, String message) {}

    public record MatchRequest(long subjectId, int sort) {}

    public record ScanRequest(Boolean full) {}

    public record DirectoryRequest(String path) {}
}
