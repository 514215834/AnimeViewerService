package com.animeviewer.service.subscription;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** v0.19 SU1/SU2 订阅与命中台账的 SQLite 数据访问（JdbcClient 手写 SQL，对齐 DownloadRepository 风格） */
@Repository
public class SubscriptionRepository {

    private final JdbcClient db;

    public SubscriptionRepository(JdbcClient db) {
        this.db = db;
    }

    /* ── 订阅 ── */

    public record SubRow(long id, long subjectId, String subjectName, String subjectNameCn,
                         boolean auto, int minEpisode, String ignoredFansubsJson,
                         Long lastCheckedAt, Long lastHitAt, long createdAt) {}

    private static final String SUB_COLUMNS =
            "id, subject_id, subject_name, subject_name_cn, auto, min_episode, ignored_fansubs, last_checked_at, last_hit_at, created_at";

    public List<SubRow> listSubs() {
        return db.sql("SELECT " + SUB_COLUMNS + " FROM subscriptions ORDER BY id")
                .query(SubscriptionRepository::mapSub).list();
    }

    public Optional<SubRow> findSub(long id) {
        return db.sql("SELECT " + SUB_COLUMNS + " FROM subscriptions WHERE id = ?").param(id)
                .query(SubscriptionRepository::mapSub).optional();
    }

    public Optional<SubRow> findSubBySubject(long subjectId) {
        return db.sql("SELECT " + SUB_COLUMNS + " FROM subscriptions WHERE subject_id = ?").param(subjectId)
                .query(SubscriptionRepository::mapSub).optional();
    }

    public long insertSub(long subjectId, String subjectName, String subjectNameCn,
                          boolean auto, int minEpisode) {
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO subscriptions(subject_id, subject_name, subject_name_cn, auto, min_episode, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .param(subjectId).param(subjectName).param(subjectNameCn).param(auto ? 1 : 0).param(minEpisode)
                .param(System.currentTimeMillis())
                .update(keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("subscriptions 插入未返回自增主键");
        return key.longValue();
    }

    public void updateSubNames(long id, String subjectName, String subjectNameCn) {
        db.sql("UPDATE subscriptions SET subject_name = ?, subject_name_cn = ? WHERE id = ?")
                .param(subjectName).param(subjectNameCn).param(id).update();
    }

    public void setSubAuto(long id, boolean auto) {
        db.sql("UPDATE subscriptions SET auto = ? WHERE id = ?").param(auto ? 1 : 0).param(id).update();
    }

    public void setSubMinEpisode(long id, int minEpisode) {
        db.sql("UPDATE subscriptions SET min_episode = ? WHERE id = ?").param(minEpisode).param(id).update();
    }

    public void setSubFansubs(long id, String ignoredFansubsJson) {
        db.sql("UPDATE subscriptions SET ignored_fansubs = ? WHERE id = ?").param(ignoredFansubsJson).param(id).update();
    }

    /** 一轮检索结束：更新 last_checked_at；本轮有新命中时同时抬升 last_hit_at */
    public void markChecked(long id, Long hitAt) {
        if (hitAt == null) {
            db.sql("UPDATE subscriptions SET last_checked_at = ? WHERE id = ?")
                    .param(System.currentTimeMillis()).param(id).update();
        } else {
            db.sql("UPDATE subscriptions SET last_checked_at = ?, last_hit_at = ? WHERE id = ?")
                    .param(System.currentTimeMillis()).param(hitAt).param(id).update();
        }
    }

    public void deleteSub(long id) {
        db.sql("DELETE FROM subscriptions WHERE id = ?").param(id).update();
    }

    /* ── 设置 KV（settings 表与 DownloadRepository 共用；订阅设置 JSON 单行 key=subscription）── */

    public Optional<String> getSetting(String key) {
        return db.sql("SELECT value FROM settings WHERE key = ?").param(key).query(String.class).optional();
    }

    public void putSetting(String key, String value) {
        db.sql("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                .param(key).param(value).update();
    }

    private static SubRow mapSub(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long checked = rs.getLong("last_checked_at");
        boolean checkedNull = rs.wasNull();
        long hit = rs.getLong("last_hit_at");
        boolean hitNull = rs.wasNull();
        return new SubRow(
                rs.getLong("id"), rs.getLong("subject_id"),
                rs.getString("subject_name"), rs.getString("subject_name_cn"),
                rs.getInt("auto") == 1, rs.getInt("min_episode"),
                rs.getString("ignored_fansubs"),
                checkedNull ? null : checked, hitNull ? null : hit, rs.getLong("created_at"));
    }

    /* ── 命中台账（待确认队列 + 历史）── */

    public record HitRow(long id, long subjectId, String subjectName, String subjectNameCn,
                         Integer episodeSort, String title, String fansub, String magnet, String infohash,
                         String site, String size, Long pubDate, String status, String note,
                         long createdAt, Long decidedAt) {}

    private static final String HIT_COLUMNS =
            "id, subject_id, subject_name, subject_name_cn, episode_sort, title, fansub, magnet, infohash, " +
                    "site, size, pub_date, status, note, created_at, decided_at";

    public long insertHit(HitRow h) {
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO sub_hits(subject_id, subject_name, subject_name_cn, episode_sort, title, fansub,
                                             magnet, infohash, site, size, pub_date, status, note, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(h.subjectId()).param(h.subjectName()).param(h.subjectNameCn()).param(h.episodeSort())
                .param(h.title()).param(h.fansub()).param(h.magnet()).param(h.infohash())
                .param(h.site()).param(h.size()).param(h.pubDate()).param(h.status()).param(h.note())
                .param(System.currentTimeMillis())
                .update(keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("sub_hits 插入未返回自增主键");
        return key.longValue();
    }

    public List<HitRow> listHits(String status, int limit) {
        if (status != null && !status.isBlank()) {
            return db.sql("SELECT " + HIT_COLUMNS + " FROM sub_hits WHERE status = ? ORDER BY id DESC LIMIT ?")
                    .param(status).param(limit).query(SubscriptionRepository::mapHit).list();
        }
        return db.sql("SELECT " + HIT_COLUMNS + " FROM sub_hits ORDER BY id DESC LIMIT ?")
                .param(limit).query(SubscriptionRepository::mapHit).list();
    }

    public Optional<HitRow> findHit(long id) {
        return db.sql("SELECT " + HIT_COLUMNS + " FROM sub_hits WHERE id = ?").param(id)
                .query(SubscriptionRepository::mapHit).optional();
    }

    public void setHitStatus(long id, String status, String note) {
        db.sql("UPDATE sub_hits SET status = ?, note = COALESCE(?, note), decided_at = ? WHERE id = ?")
                .param(status).param(note).param(System.currentTimeMillis()).param(id).update();
    }

    /** 删除单条命中（命中历史多选/清空用）；返回实际删除行数 */
    public int deleteHit(long id) {
        return db.sql("DELETE FROM sub_hits WHERE id = ?").param(id).update();
    }

    /** 清空命中历史：删除全部非待确认命中；待确认命中必须先经人工审核，不在清理范围 */
    public int deleteNonPendingHits() {
        return db.sql("DELETE FROM sub_hits WHERE status != 'pending'").update();
    }

    /** 跨轮去重 I：该资源历史命中过（任何状态）→ 不再生成新命中 */
    public boolean existsInfohash(String infohash) {
        return db.sql("SELECT COUNT(*) FROM sub_hits WHERE infohash = ?").param(infohash)
                .query(Long.class).optional().orElse(0L) > 0;
    }

    /** 跨轮去重 II：同条目同集数已有待确认命中（忽略的不占位——忽略字幕组 A 后同集来自字幕组 B 仍应出现） */
    public boolean existsPendingEpisode(long subjectId, int episodeSort) {
        return db.sql("SELECT COUNT(*) FROM sub_hits WHERE subject_id = ? AND episode_sort = ? AND status = 'pending'")
                .param(subjectId).param(episodeSort).query(Long.class).optional().orElse(0L) > 0;
    }

    /** 全自动保护 I：当日（本地时区 0 点起）已自动入队条数 */
    public long countAutoSince(long since) {
        return db.sql("SELECT COUNT(*) FROM sub_hits WHERE status = 'auto' AND created_at >= ?").param(since)
                .query(Long.class).optional().orElse(0L);
    }

    public long countPending() {
        return db.sql("SELECT COUNT(*) FROM sub_hits WHERE status = 'pending'").query(Long.class).optional().orElse(0L);
    }

    /** 最近一条命中（任意状态，供 toast「新命中」判定） */
    public Optional<HitRow> latestHit() {
        return db.sql("SELECT " + HIT_COLUMNS + " FROM sub_hits ORDER BY id DESC LIMIT 1")
                .query(SubscriptionRepository::mapHit).optional();
    }

    private static HitRow mapHit(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long pub = rs.getLong("pub_date");
        boolean pubNull = rs.wasNull();
        long decided = rs.getLong("decided_at");
        boolean decidedNull = rs.wasNull();
        Integer sort = (Integer) rs.getObject("episode_sort");
        return new HitRow(
                rs.getLong("id"), rs.getLong("subject_id"),
                rs.getString("subject_name"), rs.getString("subject_name_cn"), sort,
                rs.getString("title"), rs.getString("fansub"), rs.getString("magnet"), rs.getString("infohash"),
                rs.getString("site"), rs.getString("size"), pubNull ? null : pub,
                rs.getString("status"), rs.getString("note"),
                rs.getLong("created_at"), decidedNull ? null : decided);
    }
}
