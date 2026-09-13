package com.animeviewer.service.download;

import com.animeviewer.service.ServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * v0.16 DN1 下载引擎生命周期（双模式，运行配置来自 DownloadSettings）：
 * ① 托管模式——ProcessBuilder 拉起 aria2c 子进程（RPC 仅绑定回环 + 随机 secret）；
 * ② 外部实例模式——直连已有 aria2 的 RPC 地址。ensureRunning() 幂等：先 ping version，
 * 不通且为托管模式时（重新）拉起并等待 RPC 就绪；每次成功就绪递增 generation，
 * DownloadService 据此触发非终止任务恢复（引擎重启后 gid 全部失效）。
 * 原型实测结论：Windows 版 aria2 需 --check-certificate=false，否则 HTTPS tracker 因 schannel
 * 吊销检查握手失败；--file-allocation=none 避免大文件预分配卡顿。
 */
@Component
public class Aria2Engine {

    private static final Logger log = LoggerFactory.getLogger(Aria2Engine.class);

    public record EngineInfo(boolean available, String mode, String version, String downloadDir, String error) {}

    /** 引擎每次（重新）就绪递增——服务据此做任务恢复 */
    private volatile long generation = 0;
    private Process process;
    private Aria2Client client;
    private String clientMode = "";
    private String clientDownloadDir = "";
    private volatile long lastSpawnAttempt = 0;
    /** 可执行文件探测确定性失败的缓存（路径未变时直接返回真实原因，不再被节流文案覆盖；restart 清除） */
    private volatile String probeFailedPath;
    private volatile String probeFailedMsg;

    private final DownloadRepository repo;
    private final ServiceProperties props;

    public Aria2Engine(DownloadRepository repo, ServiceProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public long generation() {
        return generation;
    }

    /** 当前可用客户端（不可用返回 null）；调用前先 ensureRunning */
    public synchronized Aria2Client client() {
        return client;
    }

    public synchronized String downloadDir() {
        return clientDownloadDir;
    }

    /** 幂等：可用则直接返回；托管模式不可用且距上次拉起 ≥ 3s 时重试拉起 */
    public synchronized EngineInfo ensureRunning() {
        DownloadSettings s = currentSettings();
        String dir = absDir(s.downloadDir());
        String mode = s.engineUrl() == null || s.engineUrl().isBlank() ? "managed" : "external";
        try {
            if (client != null && clientMode.equals(mode)) {
                String v = client.version();
                if (v != null && !v.isBlank()) {
                    generation++;
                    return new EngineInfo(true, mode, v, dir, null);
                }
            }
        } catch (Exception ignored) {
            // 落入重建逻辑
        }
        client = null;
        if (mode.equals("external")) {
            try {
                Aria2Client c = new Aria2Client(normalizeRpcUrl(s.engineUrl()), s.engineSecret());
                String v = c.version();
                client = c;
                clientMode = mode;
                clientDownloadDir = dir;
                generation++;
                log.info("已连接外部 aria2 实例 {}（版本 {}）", s.engineUrl(), v);
                return new EngineInfo(true, mode, v, dir, null);
            } catch (Exception e) {
                return new EngineInfo(false, mode, null, dir, "外部 aria2 不可达（" + e.getMessage() + "）");
            }
        }
        // 托管模式
        if (s.enginePath().equals(probeFailedPath)) {
            return new EngineInfo(false, mode, null, dir, probeFailedMsg);
        }
        if (System.currentTimeMillis() - lastSpawnAttempt < 3000) {
            return new EngineInfo(false, mode, null, dir, "aria2 启动中，请稍候");
        }
        lastSpawnAttempt = System.currentTimeMillis();
        try {
            String probe = probeVersion(s.enginePath());
            if (probe == null) {
                probeFailedPath = s.enginePath();
                probeFailedMsg = "aria2c 不可用（路径: " + s.enginePath()
                        + "）——请安装 aria2 或在设置页填入可执行文件完整路径";
                return new EngineInfo(false, mode, null, dir, probeFailedMsg);
            }
            Files.createDirectories(Path.of(dir));
            String secret = randomSecret();
            int rpcPort = s.rpcPort();
            List<String> args = new ArrayList<>(List.of(
                    s.enginePath(),
                    "--enable-rpc", "--rpc-listen-all=false", "--rpc-listen-port=" + rpcPort,
                    "--rpc-secret=" + secret,
                    "--dir=" + dir,
                    "--continue=true",
                    "--file-allocation=none",
                    "--max-concurrent-downloads=" + s.maxConcurrent(),
                    "--seed-time=" + s.seedTimeMinutes(),
                    "--check-certificate=" + s.checkCertificate(),
                    "--dht-listen-port=" + (rpcPort + 81),
                    "--listen-port=" + (rpcPort + 91),
                    "--quiet=true",
                    "--log=" + Path.of(props.dataDir(), "aria2.log").toAbsolutePath(),
                    "--log-level=notice"));
            if (!s.trackers().isEmpty()) {
                args.add("--bt-tracker=" + String.join(",", s.trackers()));
            }
            if (s.uploadLimit() != null && !s.uploadLimit().isBlank()) {
                args.add("--max-overall-upload-limit=" + s.uploadLimit());
            }
            if (process != null && process.isAlive()) process.destroyForcibly();
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    Path.of(props.dataDir(), "aria2-console.log").toAbsolutePath().toFile()));
            process = pb.start();
            Aria2Client c = new Aria2Client("http://127.0.0.1:" + rpcPort + "/jsonrpc", secret);
            // 等 RPC 就绪（≤10s）
            Exception lastErr = null;
            for (int i = 0; i < 20; i++) {
                try {
                    String v = c.version();
                    client = c;
                    clientMode = mode;
                    clientDownloadDir = dir;
                    probeFailedPath = null;
                    probeFailedMsg = null;
                    generation++;
                    log.info("aria2 引擎已拉起（版本 {}，RPC 端口 {}，下载目录 {}）", v, rpcPort, dir);
                    return new EngineInfo(true, mode, v, dir, null);
                } catch (Exception e) {
                    lastErr = e;
                    Thread.sleep(500);
                    if (!process.isAlive()) {
                        return new EngineInfo(false, mode, null, dir,
                                "aria2c 进程退出（exit=" + process.exitValue() + "），详见 data/aria2-console.log");
                    }
                }
            }
            return new EngineInfo(false, mode, null, dir, "aria2 RPC 未就绪（" + (lastErr == null ? "超时" : lastErr.getMessage()) + "）");
        } catch (Exception e) {
            return new EngineInfo(false, mode, null, dir, "aria2 拉起失败: " + e.getMessage());
        }
    }

    /** 设置页「重启引擎」：托管模式销毁子进程重新拉起；外部模式重试连接 */
    public synchronized EngineInfo restart() {
        DownloadSettings s = currentSettings();
        if (s.engineUrl() == null || s.engineUrl().isBlank()) {
            if (client != null) client.shutdown();
            if (process != null && process.isAlive()) {
                process.destroy();
                try { process.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            process = null;
            client = null;
            lastSpawnAttempt = 0;
            probeFailedPath = null;
            probeFailedMsg = null;
        }
        return ensureRunning();
    }

    /** 服务关闭：托管引擎优雅退出 */
    @jakarta.annotation.PreDestroy
    public synchronized void destroy() {
        if (client != null && "managed".equals(clientMode)) {
            try { client.shutdown(); } catch (Exception ignored) { }
        }
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /** v0.18 QB3：切换到 qBt 引擎时停止托管 aria2 子进程（外部实例模式无需处理） */
    public synchronized void stopManagedIfAny() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        if (client != null) {
            try { client.shutdown(); } catch (Exception ignored) { }
        }
        process = null;
        client = null;
        clientMode = "";
    }

    private DownloadSettings currentSettings() {
        return DownloadSettings.load(repo.getSetting(DownloadSettings.STORE_KEY).orElse(null),
                DownloadSettings.defaults(props));
    }

    private String absDir(String dir) {
        try {
            return Path.of(dir).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return dir;
        }
    }

    /**
     * RPC 地址归一化：aria2 仅服务 /jsonrpc。用户习惯写法 http://host:6800/rpc → 替换尾段为
     * /jsonrpc；已带 /jsonrpc 原样；裸地址（scheme://host[:port]）追加 /jsonrpc。
     */
    public static String normalizeRpcUrl(String url) {
        String u = url.trim().replaceAll("/+$", "");
        if (u.endsWith("/jsonrpc")) return u;
        if (u.endsWith("/rpc")) return u.substring(0, u.length() - 4) + "/jsonrpc";
        return u + "/jsonrpc";
    }

    /** PATH / 指定路径可执行文件版本探测（对齐 ExternalTool 的 ffmpeg 探测模式）；null = 不可用 */
    private String probeVersion(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            boolean done = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            if (done && p.exitValue() == 0 && out.contains("aria2 version")) {
                int i = out.indexOf("aria2 version");
                return out.substring(i + "aria2 version".length()).trim().split("\\s+")[0];
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String randomSecret() {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    /** 测试可见：console 重定向文件（引擎排障入口） */
    public File consoleLogFile() {
        return Path.of(props.dataDir(), "aria2-console.log").toAbsolutePath().toFile();
    }
}
