package com.animeviewer.service.download;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** v0.16 DN1 下载任务与设置 KV 的 SQLite 数据访问（JdbcClient 手写 SQL，对齐 MediaRepository 风格） */
@Repository
public class DownloadRepository {

    private final JdbcClient db;

    public DownloadRepository(JdbcClient db) {
        this.db = db;
    }

    public record TaskRow(
            long id, String gid, String infoHash, String name, String uri,
            Long subjectId, String subjectName, String subjectNameCn, Integer episodeSort,
            String status, long totalLen, long completedLen, long downloadSpeed, long uploadSpeed,
            int connections, int seeds, String filesJson, String error,
            int recoverCount, boolean postprocessed, long createdAt, Long completedAt) {}

    private static final String COLUMNS = """
            id, gid, infohash, name, uri, subject_id, subject_name, subject_name_cn, episode_sort,
            status, total_len, completed_len, download_speed, upload_speed, connections, seeds,
            files_json, error, recover_count, postprocessed, created_at, completed_at
            """;

    /* ── 任务 ── */

    public long insert(TaskRow r) {
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO download_tasks(gid, infohash, name, uri, subject_id, subject_name, subject_name_cn,
                                                   episode_sort, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(r.gid()).param(r.infoHash()).param(r.name()).param(r.uri())
                .param(r.subjectId()).param(r.subjectName()).param(r.subjectNameCn()).param(r.episodeSort())
                .param(r.status()).param(System.currentTimeMillis())
                .update(keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("download_tasks 插入未返回自增主键");
        return key.longValue();
    }

    public Optional<TaskRow> find(long id) {
        return db.sql("SELECT " + COLUMNS + " FROM download_tasks WHERE id = ?").param(id)
                .query(DownloadRepository::mapRow).optional();
    }

    public Optional<TaskRow> findByInfohash(String infoHash) {
        return db.sql("SELECT " + COLUMNS + " FROM download_tasks WHERE infohash = ?")
                .param(infoHash).query(DownloadRepository::mapRow).optional();
    }

    public Optional<TaskRow> findByUri(String uri) {
        return db.sql("SELECT " + COLUMNS + " FROM download_tasks WHERE uri = ?")
                .param(uri).query(DownloadRepository::mapRow).optional();
    }

    /** 非终止任务（watcher 轮询范围） */
    public List<TaskRow> listNonTerminal() {
        return db.sql("SELECT " + COLUMNS + " FROM download_tasks WHERE status IN ('queued','metadata','downloading','paused') ORDER BY id")
                .query(DownloadRepository::mapRow).list();
    }

    public List<TaskRow> listAll(int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM download_tasks ORDER BY id DESC LIMIT ?")
                .param(limit).query(DownloadRepository::mapRow).list();
    }

    public void delete(long id) {
        db.sql("DELETE FROM download_tasks WHERE id = ?").param(id).update();
    }

    public void updateRuntime(long id, String status, long totalLen, long completedLen,
                              long downloadSpeed, long uploadSpeed, int connections, int seeds,
                              String filesJson, String name, String error) {
        db.sql("""
                        UPDATE download_tasks SET status = ?, total_len = ?, completed_len = ?, download_speed = ?,
                               upload_speed = ?, connections = ?, seeds = ?, files_json = ?,
                               name = COALESCE(?, name), error = ?
                        WHERE id = ?
                        """)
                .param(status).param(totalLen).param(completedLen).param(downloadSpeed)
                .param(uploadSpeed).param(connections).param(seeds).param(filesJson)
                .param(name).param(error)
                .param(id)
                .update();
    }

    public void markCompleted(long id) {
        db.sql("UPDATE download_tasks SET completed_at = ?, postprocessed = 1 WHERE id = ?")
                .param(System.currentTimeMillis()).param(id).update();
    }

    /** 仅刷新文件清单（select-file 勾选状态），不触碰状态与进度字段 */
    public void updateFilesJson(long id, String filesJson) {
        db.sql("UPDATE download_tasks SET files_json = ? WHERE id = ?").param(filesJson).param(id).update();
    }

    public void updateGid(long id, String gid, int recoverCount) {
        db.sql("UPDATE download_tasks SET gid = ?, recover_count = ? WHERE id = ?")
                .param(gid).param(recoverCount).param(id).update();
    }

    /** addTorrent 任务的 infoHash 回填（仅首写；infohash 唯一部分索引冲突时忽略） */
    public void updateInfohash(long id, String infoHash) {
        try {
            db.sql("UPDATE download_tasks SET infohash = ? WHERE id = ? AND infohash IS NULL")
                    .param(infoHash).param(id).update();
        } catch (Exception e) {
            // 与已存在任务 infohash 撞唯一索引——去重语义由 enqueue 把关
        }
    }

    /* ── 设置 KV ── */

    public Optional<String> getSetting(String key) {
        return db.sql("SELECT value FROM settings WHERE key = ?").param(key).query(String.class).optional();
    }

    public void putSetting(String key, String value) {
        db.sql("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                .param(key).param(value).update();
    }

    private static TaskRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long completed = rs.getLong("completed_at");
        boolean completedNull = rs.wasNull();
        long subject = rs.getLong("subject_id");
        boolean subjectNull = rs.wasNull();
        String gid = rs.getString("gid");
        String info = rs.getString("infohash");
        String name = rs.getString("name");
        String sName = rs.getString("subject_name");
        String sNameCn = rs.getString("subject_name_cn");
        Integer sort = (Integer) rs.getObject("episode_sort");
        String files = rs.getString("files_json");
        String error = rs.getString("error");
        return new TaskRow(
                rs.getLong("id"), gid, info, name, rs.getString("uri"),
                subjectNull ? null : subject, sName, sNameCn, sort,
                rs.getString("status"), rs.getLong("total_len"), rs.getLong("completed_len"),
                rs.getLong("download_speed"), rs.getLong("upload_speed"),
                rs.getInt("connections"), rs.getInt("seeds"), files, error,
                rs.getInt("recover_count"), rs.getInt("postprocessed") == 1,
                rs.getLong("created_at"), completedNull ? null : completed);
    }
}
