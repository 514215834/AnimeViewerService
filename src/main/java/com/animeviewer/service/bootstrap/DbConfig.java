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
        }
        return ds;
    }

    @Bean
    public JdbcClient jdbcClient(SingleConnectionDataSource ds) {
        return JdbcClient.create(ds);
    }
}
