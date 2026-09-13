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
        }
        return ds;
    }

    @Bean
    public JdbcClient jdbcClient(SingleConnectionDataSource ds) {
        return JdbcClient.create(ds);
    }
}
