package com.animeviewer.service.store;

import com.animeviewer.service.model.Dtos.DirectoryDto;
import com.animeviewer.service.model.Dtos.MediaFileDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** SQLite 数据访问（JdbcClient，手写 SQL——万级索引不需要 ORM） */
@Repository
public class MediaRepository {

    private final JdbcClient db;

    public MediaRepository(JdbcClient db) {
        this.db = db;
    }

    /* ── 目录 ── */

    public List<DirectoryDto> listDirectories() {
        return db.sql("""
                        SELECT d.id, d.path, d.enabled, d.created_at,
                               (SELECT COUNT(*) FROM media_files f WHERE f.dir_id = d.id) AS file_count
                        FROM directories d ORDER BY d.id
                        """)
                .query((rs, i) -> new DirectoryDto(
                        rs.getLong("id"), rs.getString("path"), rs.getInt("enabled") == 1,
                        rs.getLong("file_count"), rs.getLong("created_at")))
                .list();
    }

    public Optional<Long> findDirectoryByPath(String path) {
        return db.sql("SELECT id FROM directories WHERE path = ?")
                .param(path).query(Long.class).optional();
    }

    public long insertDirectory(String path) {
        return db.sql("INSERT INTO directories(path, enabled, created_at) VALUES (?, 1, ?)")
                .param(path).param(System.currentTimeMillis())
                .update();
    }

    public void deleteDirectory(long id) {
        db.sql("DELETE FROM media_files WHERE dir_id = ?").param(id).update();
        db.sql("DELETE FROM directories WHERE id = ?").param(id).update();
    }

    /* ── 媒体文件 ── */

    private static final String FILE_COLUMNS = """
            id, dir_id, path, name, ext, size, mtime, duration_sec, container, vcodec, acodec,
            width, height, parsed_title, parsed_episode, match_state, subject_id, subject_name,
            subject_name_cn, episode_sort, auto_bound, matched_at, probed_at, error
            """;

    public List<MediaFileDto> listFiles(Long dirId, String state, String q, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + FILE_COLUMNS + " FROM media_files WHERE 1=1");
        var query = db.sql(appendFilters(sql, dirId, state, q) + " ORDER BY id DESC LIMIT ? OFFSET ?");
        bindFilters(query, dirId, state, q);
        query.param(limit).param(offset);
        return query.query(MediaRepository::mapFile).list();
    }

    public long countFiles(Long dirId, String state, String q) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM media_files WHERE 1=1");
        var query = db.sql(appendFilters(sql, dirId, state, q));
        bindFilters(query, dirId, state, q);
        return query.query(Long.class).single();
    }

    public long countByState(String state) {
        return db.sql("SELECT COUNT(*) FROM media_files WHERE match_state = ?")
                .param(state).query(Long.class).single();
    }

    public long countAll() {
        return db.sql("SELECT COUNT(*) FROM media_files").query(Long.class).single();
    }

    public Optional<MediaFileDto> findFile(long id) {
        return db.sql("SELECT " + FILE_COLUMNS + " FROM media_files WHERE id = ?")
                .param(id).query(MediaRepository::mapFile).optional();
    }

    public Optional<Long> findFileIdByPath(String path) {
        return db.sql("SELECT id FROM media_files WHERE path = ?").param(path).query(Long.class).optional();
    }

    public Optional<MediaFileRow> findRowByPath(String path) {
        return db.sql("SELECT " + FILE_COLUMNS + " FROM media_files WHERE path = ?")
                .param(path).query(MediaRepository::mapRow).optional();
    }

    public List<MediaFileRow> rowsByDirectory(long dirId) {
        return db.sql("SELECT " + FILE_COLUMNS + " FROM media_files WHERE dir_id = ?")
                .param(dirId).query(MediaRepository::mapRow).list();
    }

    public List<MediaFileDto> filesBySubject(long subjectId) {
        return db.sql("SELECT " + FILE_COLUMNS + " FROM media_files WHERE subject_id = ? AND match_state = 'bound' ORDER BY episode_sort")
                .param(subjectId).query(MediaRepository::mapFile).list();
    }

    /** 扫描 upsert：新文件插入，已有文件按 path 更新媒体信息与解析结果（match 绑定信息保留） */
    public void upsertScannedFile(long dirId, MediaFileRow row) {
        db.sql("""
                        INSERT INTO media_files(dir_id, path, name, ext, size, mtime, duration_sec, container,
                                                vcodec, acodec, width, height, parsed_title, parsed_episode,
                                                match_state, probed_at, error)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'unmatched', ?, ?)
                        ON CONFLICT(path) DO UPDATE SET
                          dir_id = excluded.dir_id, name = excluded.name, ext = excluded.ext,
                          size = excluded.size, mtime = excluded.mtime, duration_sec = excluded.duration_sec,
                          container = excluded.container, vcodec = excluded.vcodec, acodec = excluded.acodec,
                          width = excluded.width, height = excluded.height,
                          parsed_title = excluded.parsed_title, parsed_episode = excluded.parsed_episode,
                          probed_at = excluded.probed_at, error = excluded.error
                        """)
                .param(dirId).param(row.path()).param(row.name()).param(row.ext())
                .param(row.size()).param(row.mtime())
                .param(row.durationSec()).param(row.container()).param(row.vcodec()).param(row.acodec())
                .param(row.width()).param(row.height())
                .param(row.parsedTitle()).param(row.parsedEpisode())
                .param(row.probedAt()).param(row.error())
                .update();
    }

    public void updateMatch(long id, String state, Long subjectId, String subjectName, String subjectNameCn,
                            Integer episodeSort, boolean autoBound) {
        db.sql("""
                        UPDATE media_files SET match_state = ?, subject_id = ?, subject_name = ?, subject_name_cn = ?,
                               episode_sort = ?, auto_bound = ?, matched_at = ?
                        WHERE id = ?
                        """)
                .param(state).param(subjectId).param(subjectName).param(subjectNameCn)
                .param(episodeSort).param(autoBound ? 1 : 0)
                .param(subjectId != null ? System.currentTimeMillis() : null)
                .param(id)
                .update();
    }

    public void deleteFile(long id) {
        db.sql("DELETE FROM media_files WHERE id = ?").param(id).update();
    }

    /* ── 行映射 ── */

    public record MediaFileRow(
            long id, long dirId, String path, String name, String ext, long size, long mtime,
            Double durationSec, String container, String vcodec, String acodec,
            Integer width, Integer height, String parsedTitle, Integer parsedEpisode,
            String matchState, Long subjectId, String subjectName, String subjectNameCn,
            Integer episodeSort, boolean autoBound, Long matchedAt, Long probedAt, String error) {}

    public static MediaFileDto mapFile(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        MediaFileRow r = mapRow(rs, i);
        return new MediaFileDto(r.id(), r.dirId(), r.path(), r.name(), r.ext(), r.size(), r.mtime(),
                r.durationSec(), r.container(), r.vcodec(), r.acodec(), r.width(), r.height(),
                r.parsedTitle(), r.parsedEpisode(), r.matchState(), r.subjectId(), r.subjectName(),
                r.subjectNameCn(), r.episodeSort(), r.autoBound(), r.matchedAt(), r.probedAt(), r.error());
    }

    public static MediaFileRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double dur = rs.getDouble("duration_sec");
        boolean durNull = rs.wasNull();
        long matched = rs.getLong("matched_at");
        boolean matchedNull = rs.wasNull();
        long probed = rs.getLong("probed_at");
        boolean probedNull = rs.wasNull();
        long subject = rs.getLong("subject_id");
        boolean subjectNull = rs.wasNull();
        return new MediaFileRow(
                rs.getLong("id"), rs.getLong("dir_id"), rs.getString("path"), rs.getString("name"),
                rs.getString("ext"), rs.getLong("size"), rs.getLong("mtime"),
                durNull ? null : dur, rs.getString("container"), rs.getString("vcodec"), rs.getString("acodec"),
                (Integer) rs.getObject("width"), (Integer) rs.getObject("height"),
                rs.getString("parsed_title"), (Integer) rs.getObject("parsed_episode"),
                rs.getString("match_state"), subjectNull ? null : subject,
                rs.getString("subject_name"), rs.getString("subject_name_cn"),
                (Integer) rs.getObject("episode_sort"), rs.getInt("auto_bound") == 1,
                matchedNull ? null : matched, probedNull ? null : probed, rs.getString("error"));
    }

    /* ── 过滤条件拼装 ── */

    private static String appendFilters(StringBuilder sql, Long dirId, String state, String q) {
        if (dirId != null) sql.append(" AND dir_id = ?");
        if (state != null && !state.isBlank()) sql.append(" AND match_state = ?");
        if (q != null && !q.isBlank()) sql.append(" AND (name LIKE ? OR parsed_title LIKE ? OR subject_name LIKE ? OR subject_name_cn LIKE ?)");
        return sql.toString();
    }

    private static void bindFilters(org.springframework.jdbc.core.simple.JdbcClient.StatementSpec query, Long dirId, String state, String q) {
        if (dirId != null) query.param(dirId);
        if (state != null && !state.isBlank()) query.param(state);
        if (q != null && !q.isBlank()) {
            String like = "%" + q + "%";
            query.param(like).param(like).param(like).param(like);
        }
    }
}
