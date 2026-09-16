package com.animeviewer.service.bootstrap;

import com.animeviewer.service.ServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/** S1 启动初始化（@Order(1)，先于 Scanner 的自动扫描）：data 目录 / 首次启动生成随机 Token
 *  （控制台打印 + 写入 data/token）。 */
@Component
@Order(1)
public class Startup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Startup.class);
    public static final String VERSION = "0.22.0";

    private final ServiceProperties props;
    private String token = "";

    public Startup(ServiceProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path dataDir = Path.of(props.dataDir()).toAbsolutePath();
        Files.createDirectories(dataDir);

        Path tokenFile = dataDir.resolve("token");
        if (Files.exists(tokenFile)) {
            token = Files.readString(tokenFile).trim();
        }
        if (token.isBlank()) {
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            token = HexFormat.of().formatHex(raw);
            Files.writeString(tokenFile, token);
        }
        log.info("──────────────────────────────────────────────");
        log.info(" AnimeViewerService v{} 已启动", VERSION);
        log.info(" 配对 Token: {}", token);
        log.info(" （同时写入文件: {}）", tokenFile);
        log.info(" ffmpeg: {} / ffprobe: {}",
                ffmpegAvailableQuiet() ? "可用" : "未找到（mp4/webm 直连流仍可用，mkv 转封装不可用）",
                ffprobeAvailableQuiet() ? "可用" : "未找到（无法探测媒体信息）");
        log.info(" 默认地址: http://127.0.0.1:{} （局域网监听请加启动参数 --server.address=0.0.0.0）", portQuiet());
        log.info("──────────────────────────────────────────────");
    }

    public String token() {
        return token;
    }

    private boolean ffmpegAvailableQuiet() {
        try {
            var p = new ProcessBuilder(props.ffmpegPath(), "-version");
            p.redirectErrorStream(true);
            var proc = p.start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ffprobeAvailableQuiet() {
        try {
            var p = new ProcessBuilder(props.ffprobePath(), "-version");
            p.redirectErrorStream(true);
            var proc = p.start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String portQuiet() {
        try {
            return System.getProperty("server.port", "8787");
        } catch (Exception e) {
            return "8787";
        }
    }
}
