package com.animeviewer.service.bootstrap;

import com.animeviewer.service.ServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.sql.SQLException;

/** SQLite 单连接数据源（单文件零运维）：xerial 驱动对同一 Connection 内部串行化，
 *  个人服务量级（万级以下文件索引 / 低并发请求）足够；WAL + busy_timeout 兜底并发。 */
@Configuration
public class DbConfig {

    @Bean
    public SingleConnectionDataSource dataSource(ServiceProperties props) throws SQLException {
        Path dbPath = Path.of(props.dataDir(), "media.db").toAbsolutePath();
        try {
            java.nio.file.Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("无法创建数据目录: " + dbPath.getParent(), e);
        }
        String url = "jdbc:sqlite:" + dbPath.toString().replace('\\', '/');
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl(url);
        ds.setSuppressClose(true);
        ds.setAutoCommit(true);
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS directories(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      path TEXT NOT NULL UNIQUE,
                      enabled INTEGER NOT NULL DEFAULT 1,
                      created_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS media_files(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      dir_id INTEGER NOT NULL,
                      path TEXT NOT NULL UNIQUE,
                      name TEXT NOT NULL,
                      ext TEXT NOT NULL,
                      size INTEGER NOT NULL,
                      mtime INTEGER NOT NULL,
                      duration_sec REAL,
                      container TEXT,
                      vcodec TEXT,
                      acodec TEXT,
                      width INTEGER,
                      height INTEGER,
                      parsed_title TEXT,
                      parsed_episode INTEGER,
                      match_state TEXT NOT NULL DEFAULT 'unmatched',
                      subject_id INTEGER,
                      subject_name TEXT,
                      subject_name_cn TEXT,
                      episode_sort INTEGER,
                      auto_bound INTEGER NOT NULL DEFAULT 0,
                      matched_at INTEGER,
                      probed_at INTEGER,
                      error TEXT
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_files_dir ON media_files(dir_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_files_subject ON media_files(subject_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_files_state ON media_files(match_state)");
            // v0.16 旧库迁移：媒体文件来源下载任务（下载完成后回填，供「来自下载任务」溯源展示）
            try {
                st.execute("ALTER TABLE media_files ADD COLUMN download_task_id INTEGER");
            } catch (Exception e) {
                // 列已存在（重复启动）——忽略
            }
            // v0.30 A7 AI 解析候选匹配置信度（0~100，null=未评分；reason 为判定依据，供徽章 Tooltip）
            try {
                st.execute("ALTER TABLE media_files ADD COLUMN ai_match_score INTEGER");
            } catch (Exception e) {
                // 列已存在（重复启动）——忽略
            }
            try {
                st.execute("ALTER TABLE media_files ADD COLUMN ai_match_reason TEXT");
            } catch (Exception e) {
                // 列已存在（重复启动）——忽略
            }
            // v0.16 DN1 下载任务表：status=queued/metadata/downloading/paused/completed/error；
            // files_json 为 aria2 files 数组的净化快照（select-file 勾选状态随 watcher 轮询刷新）
            st.execute("""
                    CREATE TABLE IF NOT EXISTS download_tasks(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      gid TEXT,
                      infohash TEXT,
                      name TEXT,
                      uri TEXT NOT NULL,
                      subject_id INTEGER,
                      subject_name TEXT,
                      subject_name_cn TEXT,
                      episode_sort INTEGER,
                      status TEXT NOT NULL DEFAULT 'queued',
                      total_len INTEGER NOT NULL DEFAULT 0,
                      completed_len INTEGER NOT NULL DEFAULT 0,
                      download_speed INTEGER NOT NULL DEFAULT 0,
                      upload_speed INTEGER NOT NULL DEFAULT 0,
                      connections INTEGER NOT NULL DEFAULT 0,
                      seeds INTEGER NOT NULL DEFAULT 0,
                      files_json TEXT,
                      error TEXT,
                      recover_count INTEGER NOT NULL DEFAULT 0,
                      postprocessed INTEGER NOT NULL DEFAULT 0,
                      created_at INTEGER NOT NULL,
                      completed_at INTEGER
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON download_tasks(status)");
            // 同一资源（infohash）同时至多一条非终止任务；终止任务不占索引，允许完成后重新下载
            st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_infohash ON download_tasks(infohash)
                    WHERE infohash IS NOT NULL AND status IN ('queued','metadata','downloading','paused')""");
            // v0.16 DN4 下载设置 KV（JSON 单行；仅存前端可改的引擎配置覆盖，yml 为默认值）
            st.execute("""
                    CREATE TABLE IF NOT EXISTS settings(
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )""");
            // v0.19 SU1 订阅自动化：条目级订阅（一 subject 一行；min_episode=观看进度基线，
            // 过滤阈值=max(min_episode, 已下载最大集)；auto=全自动入队（v0.20 起弃用，改 auto_score 阈值，列保留兼容）
            st.execute("""
                    CREATE TABLE IF NOT EXISTS subscriptions(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      subject_id INTEGER NOT NULL UNIQUE,
                      subject_name TEXT,
                      subject_name_cn TEXT,
                      auto INTEGER NOT NULL DEFAULT 0,
                      min_episode INTEGER NOT NULL DEFAULT 0,
                      ignored_fansubs TEXT NOT NULL DEFAULT '[]',
                      auto_score INTEGER,
                      last_check_error TEXT,
                      last_checked_at INTEGER,
                      last_hit_at INTEGER,
                      created_at INTEGER NOT NULL
                    )""");
            // v0.19 SU2 命中台账（待确认队列与历史共用）：status=pending/enqueued/ignored/auto；
            // 同一资源 infohash 永不重复出现（入队去重由 download_tasks infohash 把关）
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sub_hits(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      subject_id INTEGER NOT NULL,
                      subject_name TEXT,
                      subject_name_cn TEXT,
                      episode_sort INTEGER,
                      title TEXT NOT NULL,
                      fansub TEXT,
                      magnet TEXT NOT NULL,
                      infohash TEXT,
                      site TEXT,
                      size TEXT,
                      pub_date INTEGER,
                      status TEXT NOT NULL DEFAULT 'pending',
                      note TEXT,
                      score INTEGER,
                      score_detail TEXT,
                      created_at INTEGER NOT NULL,
                      decided_at INTEGER
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sub_hits_status ON sub_hits(status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sub_hits_subject ON sub_hits(subject_id, episode_sort)");
            // v0.20 存量库迁移（v0.19 已有数据的库走 ALTER 补列；重复启动列已存在则忽略）
            try {
                st.execute("ALTER TABLE sub_hits ADD COLUMN score INTEGER");
            } catch (Exception e) { /* 列已存在 */ }
            try {
                st.execute("ALTER TABLE sub_hits ADD COLUMN score_detail TEXT");
            } catch (Exception e) { /* 列已存在 */ }
            try {
                st.execute("ALTER TABLE subscriptions ADD COLUMN auto_score INTEGER");
            } catch (Exception e) { /* 列已存在 */ }
            try {
                st.execute("ALTER TABLE subscriptions ADD COLUMN last_check_error TEXT");
            } catch (Exception e) { /* 列已存在 */ }
            // v0.20 阈值取代全自动开关：存量 auto=1 → auto_score=默认阈值（yml av.subscription.default-auto-score），
            // auto=0 → 0（全手动）；仅执行一次（后续启动全部非 NULL 跳过）。值为 int 字面量，无注入面
            int defaultAutoScore = props.subscription().defaultAutoScore() == null
                    ? 0 : Math.max(0, Math.min(100, props.subscription().defaultAutoScore()));
            st.executeUpdate(
                    "UPDATE subscriptions SET auto_score = (CASE WHEN auto = 1 THEN " + defaultAutoScore
                            + " ELSE 0 END) WHERE auto_score IS NULL");
            // v0.22 AI1 命中语义判定：落库时一次判定结果 JSON（{"type","episode","isMainline","reason"}；null=未判定）
            try {
                st.execute("ALTER TABLE sub_hits ADD COLUMN ai_verdict TEXT");
            } catch (Exception e) { /* 列已存在 */ }
            // v0.22 AI2 关键词扩展：订阅级扩展检索词 JSON 数组（LLM 生成缓存/人工编辑；null=未生成）
            try {
                st.execute("ALTER TABLE subscriptions ADD COLUMN ai_keywords TEXT");
            } catch (Exception e) { /* 列已存在 */ }
            // v0.25 RSS 固定直链订阅：订阅级直连源（蜜柑每番 RSS 等；null=关键词检索模式）
            try {
                st.execute("ALTER TABLE subscriptions ADD COLUMN rss_url TEXT");
            } catch (Exception e) { /* 列已存在 */ }
        }
        return ds;
    }

    @Bean
    public JdbcClient jdbcClient(SingleConnectionDataSource ds) {
        return JdbcClient.create(ds);
    }
}
