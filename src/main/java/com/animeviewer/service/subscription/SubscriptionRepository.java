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
                         boolean auto, int minEpisode, String ignoredFansubsJson, Integer autoScore,
                         String lastCheckError, Long lastCheckedAt, Long lastHitAt, long createdAt,
                         String aiKeywordsJson, String rssUrl) {}

    private static final String SUB_COLUMNS =
            "id, subject_id, subject_name, subject_name_cn, auto, min_episode, ignored_fansubs, auto_score, " +
                    "last_check_error, last_checked_at, last_hit_at, created_at, ai_keywords, rss_url";

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
                          boolean auto, int minEpisode, Integer autoScore) {
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO subscriptions(subject_id, subject_name, subject_name_cn, auto, min_episode, auto_score, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(subjectId).param(subjectName).param(subjectNameCn).param(auto ? 1 : 0).param(minEpisode)
                .param(autoScore)
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

    /** v0.19 遗留：auto 二值开关（v0.20 起由 auto_score 取代，保留方法仅兼容旧代码路径） */
    public void setSubAuto(long id, boolean auto) {
        db.sql("UPDATE subscriptions SET auto = ? WHERE id = ?").param(auto ? 1 : 0).param(id).update();
    }

    /** v0.20 自动入队阈值：0=全手动（特殊值），1~100=评分达标自动入队 */
    public void setSubScore(long id, Integer autoScore) {
        db.sql("UPDATE subscriptions SET auto_score = ? WHERE id = ?").param(autoScore).param(id).update();
    }

    public void setSubMinEpisode(long id, int minEpisode) {
        db.sql("UPDATE subscriptions SET min_episode = ? WHERE id = ?").param(minEpisode).param(id).update();
    }

    public void setSubFansubs(long id, String ignoredFansubsJson) {
        db.sql("UPDATE subscriptions SET ignored_fansubs = ? WHERE id = ?").param(ignoredFansubsJson).param(id).update();
    }

    /** v0.22 AI2 扩展检索词（JSON 数组字符串；null = 未生成过） */
    public void setSubAiKeywords(long id, String aiKeywordsJson) {
        db.sql("UPDATE subscriptions SET ai_keywords = ? WHERE id = ?").param(aiKeywordsJson).param(id).update();
    }

    /** v0.25 RSS 固定直链订阅源：null 不可走参数（同 setSubLastError 的 sqlite-jdbc null NPE 规避惯例）——
     *  null = 清除直链（回关键词检索）；非空 = 直连源 URL（调用方已净化） */
    public void setSubRssUrl(long id, String rssUrl) {
        if (rssUrl == null) {
            db.sql("UPDATE subscriptions SET rss_url = NULL WHERE id = ?").param(id).update();
            return;
        }
        db.sql("UPDATE subscriptions SET rss_url = ? WHERE id = ?").param(rssUrl).param(id).update();
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
        int score = rs.getInt("auto_score");
        boolean scoreNull = rs.wasNull();
        String lastErr = rs.getString("last_check_error");
        String rssUrl = rs.getString("rss_url");
        return new SubRow(
                rs.getLong("id"), rs.getLong("subject_id"),
                rs.getString("subject_name"), rs.getString("subject_name_cn"),
                rs.getInt("auto") == 1, rs.getInt("min_episode"),
                rs.getString("ignored_fansubs"),
                scoreNull ? null : score, lastErr == null || lastErr.isBlank() ? null : lastErr,
                checkedNull ? null : checked, hitNull ? null : hit, rs.getLong("created_at"),
                rs.getString("ai_keywords"),
                rssUrl == null || rssUrl.isBlank() ? null : rssUrl.trim());
    }

    /* ── 命中台账（待确认队列 + 历史）── */

    public record HitRow(long id, long subjectId, String subjectName, String subjectNameCn,
                         Integer episodeSort, String title, String fansub, String magnet, String infohash,
                         String site, String size, Long pubDate, String status, String note,
                         Integer score, String scoreDetail, long createdAt, Long decidedAt, String aiVerdict) {}

    private static final String HIT_COLUMNS =
            "id, subject_id, subject_name, subject_name_cn, episode_sort, title, fansub, magnet, infohash, " +
                    "site, size, pub_date, status, note, score, score_detail, created_at, decided_at, ai_verdict";

    public long insertHit(HitRow h) {
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO sub_hits(subject_id, subject_name, subject_name_cn, episode_sort, title, fansub,
                                             magnet, infohash, site, size, pub_date, status, note, score, score_detail, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(h.subjectId()).param(h.subjectName()).param(h.subjectNameCn()).param(h.episodeSort())
                .param(h.title()).param(h.fansub()).param(h.magnet()).param(h.infohash())
                .param(h.site()).param(h.size()).param(h.pubDate()).param(h.status()).param(h.note())
                .param(h.score()).param(h.scoreDetail())
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

    /** v0.20 SU6 已入队集数占位：同条目同集数已有 enqueued/auto 命中（开关控制是否过滤——关闭可补收其他字幕组版本） */
    public boolean existsEnqueuedEpisode(long subjectId, int episodeSort) {
        return db.sql("SELECT COUNT(*) FROM sub_hits WHERE subject_id = ? AND episode_sort = ? " +
                        "AND status IN ('enqueued','auto')")
                .param(subjectId).param(episodeSort).query(Long.class).optional().orElse(0L) > 0;
    }

    /** v0.20 待确认评分转队：本订阅的 pending 命中（id 降序=最新优先），单轮处理上限由调用方控制 */
    public List<HitRow> listPendingBySubject(long subjectId, int limit) {
        return db.sql("SELECT " + HIT_COLUMNS + " FROM sub_hits WHERE subject_id = ? AND status = 'pending' ORDER BY id DESC LIMIT ?")
                .param(subjectId).param(limit).query(SubscriptionRepository::mapHit).list();
    }

    /** v0.20 SU8 检索失败显性化：最近一轮检索的错误摘要（null=成功，前端据此显示失败红标）。
     *  注意 null 走 SQL 字面量——sqlite-jdbc 的 PreparedStatement 不支持 getParameterType，
     *  Spring 对未知类型 null 参数会调它导致 NPE（对齐 setHitStatus 的 COALESCE 规避惯例）。 */
    public void setSubLastError(long id, String error) {
        if (error == null) {
            db.sql("UPDATE subscriptions SET last_check_error = NULL WHERE id = ?").param(id).update();
            return;
        }
        db.sql("UPDATE subscriptions SET last_check_error = ? WHERE id = ?").param(error).param(id).update();
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
        int score = rs.getInt("score");
        boolean scoreNull = rs.wasNull();
        Integer sort = (Integer) rs.getObject("episode_sort");
        return new HitRow(
                rs.getLong("id"), rs.getLong("subject_id"),
                rs.getString("subject_name"), rs.getString("subject_name_cn"), sort,
                rs.getString("title"), rs.getString("fansub"), rs.getString("magnet"), rs.getString("infohash"),
                rs.getString("site"), rs.getString("size"), pubNull ? null : pub,
                rs.getString("status"), rs.getString("note"),
                scoreNull ? null : score, rs.getString("score_detail"),
                rs.getLong("created_at"), decidedNull ? null : decided, rs.getString("ai_verdict"));
    }

    /** v0.22 AI1 落库后回填语义判定（JSON 字符串；null 不可走参数——同 setSubLastError 的 COALESCE 规避惯例） */
    public void setHitAiVerdict(long id, String verdictJson) {
        db.sql("UPDATE sub_hits SET ai_verdict = ? WHERE id = ?").param(verdictJson).param(id).update();
    }
}
