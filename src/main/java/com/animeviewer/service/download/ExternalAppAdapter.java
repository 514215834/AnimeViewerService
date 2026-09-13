package com.animeviewer.service.download;

import com.animeviewer.service.ServiceProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * v0.18 qBittorrent 外部应用直开引擎（用户定案：不用 WebUI 对接）：入队即用系统进程拉起
 * 本机 qBittorrent 可执行文件并携带磁力/种子文件参数（qBt 已有实例在运行时由其单实例机制
 * 转发参数），下载、进度、文件管理全部由 qBt 自身负责——本服务不建立任何 RPC 连接，
 * 仅记录任务台账（status=external，不参与 watcher 同步）。任务键 = infoHash（磁力）/
 * 临时种子文件路径（.torrent 直链）。
 */
@Component
public class ExternalAppAdapter implements DownloadEngine {

    private final DownloadRepository repo;
    private final ServiceProperties props;

    public ExternalAppAdapter(DownloadRepository repo, ServiceProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Override
    public String type() {
        return "qbittorrent";
    }

    /** 拉起命令（纯函数供单测）：[客户端可执行文件, 磁力或种子文件参数] */
    static List<String> launchArgs(String clientPath, String payload) {
        return List.of(clientPath, payload);
    }

    /** 客户端路径就绪检查（纯函数供单测）：返回具体原因，null = 通过 */
    static String pathProblem(String clientPath) {
        if (clientPath == null || clientPath.isBlank()) return "未配置 qBittorrent 可执行文件路径";
        if (!Files.isRegularFile(Path.of(clientPath))) return "qBittorrent 可执行文件不存在: " + clientPath;
        return null;
    }

    @Override
    public EngineInfo ensureRunning() {
        DownloadSettings s = currentSettings();
        String problem = pathProblem(s.qbPath());
        if (problem != null) return new EngineInfo(false, "external-app", null, s.downloadDir(), problem);
        return new EngineInfo(true, "external-app", null, s.downloadDir(), null);
    }

    /** 重启引擎 = 重新校验路径配置（无进程/连接需要重启） */
    @Override
    public EngineInfo restart() {
        return ensureRunning();
    }

    @Override
    public String enqueue(String uri, String infoHash, String downloadDir) throws DownloadException {
        ensureReady();
        if (!uri.startsWith("magnet:?")) {
            throw new DownloadException(400, "qBittorrent 直开模式仅支持磁力链接（http/ftp 直链请切换 aria2 引擎）");
        }
        launch(launchArgs(currentSettings().qbPath(), uri));
        return infoHash != null ? infoHash.toLowerCase(Locale.ROOT) : uri;
    }

    /** .torrent 直链：内容暂存为临时种子文件后以文件路径拉起 qBt（qBt 支持命令行种子文件参数） */
    public String enqueueTorrent(byte[] torrentBytes, String infoHash, String downloadDir) throws DownloadException {
        ensureReady();
        try {
            Path file = Files.createTempFile("animeviewer-", ".torrent");
            Files.write(file, torrentBytes);
            launch(launchArgs(currentSettings().qbPath(), file.toAbsolutePath().toString()));
            return infoHash != null ? infoHash : file.toAbsolutePath().toString();
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(502, "种子文件暂存失败: " + e.getMessage());
        }
    }

    /** 引擎侧任务状态不可得（无 RPC）——watcher 不消费 external 任务，此处仅为接口兜底 */
    @Override
    public TaskSnapshot status(String taskKey) throws DownloadException {
        throw new DownloadException(502, "qBittorrent 直开模式无任务状态（进度请在 qBittorrent 中查看）");
    }

    @Override
    public List<String> activeTaskKeys() {
        return List.of();
    }

    @Override
    public void pause(String taskKey) throws DownloadException {
        throw new DownloadException(400, "已交给 qBittorrent 直开下载——暂停/恢复请在 qBittorrent 中操作");
    }

    @Override
    public void resume(String taskKey) throws DownloadException {
        throw new DownloadException(400, "已交给 qBittorrent 直开下载——暂停/恢复请在 qBittorrent 中操作");
    }

    @Override
    public void applySelection(String taskKey, List<Integer> indexes) throws DownloadException {
        throw new DownloadException(400, "已交给 qBittorrent 直开下载——文件勾选请在 qBittorrent 中操作");
    }

    @Override
    public void remove(String taskKey, boolean deleteFiles) {
        // 台账删除：文件由 qBittorrent 管理，服务端不做任何删除动作
    }

    private void ensureReady() throws DownloadException {
        EngineInfo info = ensureRunning();
        if (!info.available()) throw new DownloadException(503, "外部应用不可用：" + info.error());
    }

    /** 拉起外部应用：GUI 子进程不回传输出（丢弃防管道阻塞），不等待其退出 */
    private void launch(List<String> cmd) throws DownloadException {
        try {
            new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception e) {
            throw new DownloadException(502, "拉起 qBittorrent 失败: " + e.getMessage());
        }
    }

    private DownloadSettings currentSettings() {
        return DownloadSettings.load(repo.getSetting(DownloadSettings.STORE_KEY).orElse(null),
                DownloadSettings.defaults(props));
    }
}
