package com.animeviewer.service.download;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.media.LibraryScanner;
import com.animeviewer.service.download.DownloadEngine.TaskSnapshot;
import com.animeviewer.service.model.Dtos.DownloadAddRequest;
import com.animeviewer.service.model.Dtos.DownloadEngineDto;
import com.animeviewer.service.model.Dtos.DownloadFileDto;
import com.animeviewer.service.model.Dtos.DownloadSelectionRequest;
import com.animeviewer.service.model.Dtos.DownloadSettingsDto;
import com.animeviewer.service.model.Dtos.DownloadTaskDto;
import com.animeviewer.service.store.MediaRepository;
import com.animeviewer.service.store.MediaRepository.MediaFileRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * v0.16 DN1/DN2/DN5 下载编排：入队（磁力合并 tracker → 引擎入队）、watcher 轮询（1.5s，aria2→DB 单向同步）、
 * 控制（暂停/恢复/文件勾选/删除）、完成闭环（触发增量扫描 → 等待扫描结束 → 预绑定 + 来源任务回填）。
 *
 * 原型实测的两个关键行为已在此消化：
 * ① 磁力元数据完成后 aria2 为负载生成新 gid（原 gid 标记 complete）——按 infoHash 收养后继 gid；
 * ② gid 不存在（引擎重启清空列表）→ 以原始 uri 重新入队恢复（recover_count 上限 3，防循环）。
 *
 * v0.18 引擎抽象：经 DownloadEngineRouter 分派 aria2（托管/外部实例）与 qBittorrent 外部应用直开——
 * 直开任务入队即拉起外部应用（无 RPC），状态定格 external，不参与 watcher 同步，
 * 暂停/恢复/文件勾选被拒绝并提示到 qBittorrent 中操作。
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int POLL_MS = 1500;
    private static final int MAX_RECOVER = 3;
    private static final int ADOPTION_MAX_MISS = 6;

    private final DownloadRepository repo;
    private final Aria2Engine aria2Engine;
    private final Aria2Adapter aria2;
    private final DownloadEngineRouter router;
    private final MediaRepository mediaRepo;
    private final LibraryScanner scanner;
    private final ServiceProperties props;
    private final ApplicationEventPublisher events;

    private Thread watcher;
    private java.util.concurrent.ExecutorService completionExecutor;
    private volatile boolean running = true;

    public DownloadService(DownloadRepository repo, Aria2Engine aria2Engine, Aria2Adapter aria2,
                           DownloadEngineRouter router, MediaRepository mediaRepo,
                           LibraryScanner scanner, ServiceProperties props,
                           ApplicationEventPublisher events) {
        this.repo = repo;
        this.aria2Engine = aria2Engine;
        this.aria2 = aria2;
        this.router = router;
        this.mediaRepo = mediaRepo;
        this.scanner = scanner;
        this.props = props;
        this.events = events;
    }

    /** 任务控制/同步所属引擎（按当前设置） */
    private DownloadEngine engineFor(DownloadRepository.TaskRow t) {
        return router.current(currentSettings());
    }

    @PostConstruct
    public void start() {
        watcher = new Thread(this::watchLoop, "download-watcher");
        watcher.setDaemon(true);
        watcher.start();
        completionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "download-completion");
            th.setDaemon(true);
            return th;
        });
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (watcher != null) watcher.interrupt();
        if (completionExecutor != null) completionExecutor.shutdownNow();
    }

    /* ── 查询 ── */

    public List<DownloadTaskDto> list() {
        return repo.listAll(200).stream().map(this::toDto).toList();
    }

    public DownloadTaskDto get(long id) {
        return repo.find(id).map(this::toDto)
                .orElseThrow(() -> new DownloadException(404, "任务不存在"));
    }

    public DownloadEngineDto engineInfo() {
        DownloadEngine engine = router.current(currentSettings());
        DownloadEngine.EngineInfo info = engine.ensureRunning();
        return new DownloadEngineDto(info.available(), info.mode(), info.version(), info.downloadDir(), info.error());
    }

    /** 设置页「重启引擎/重试连接」：按当前引擎类型分派 */
    public DownloadEngineDto restartEngine() {
        DownloadEngine engine = router.current(currentSettings());
        DownloadEngine.EngineInfo info = engine.restart();
        return new DownloadEngineDto(info.available(), info.mode(), info.version(), info.downloadDir(), info.error());
    }

    /* ── 入队 ── */

    public DownloadTaskDto enqueue(DownloadAddRequest req) {
        String uri = req == null || req.uri() == null ? "" : req.uri().trim();
        if (!MagnetParser.isSupported(uri)) {
            throw new DownloadException(400, "仅支持磁力链接（magnet:?xt=urn:btih:...）或 http(s)/ftp 直链");
        }
        DownloadSettings settings = currentSettings();
        DownloadEngine engine = router.current(settings);
        DownloadEngine.EngineInfo info = engine.ensureRunning();
        if (!info.available()) {
            throw new DownloadException(503, "下载引擎不可用：" + info.error());
        }
        MagnetParser.MagnetInfo magnet = MagnetParser.parse(uri);
        if (magnet.infoHash() != null) {
            repo.findByInfohash(magnet.infoHash()).ifPresent(t -> {
                throw new DownloadException(409, "该资源已在任务列表（任务 #" + t.id() + " " + statusLabel(t.status()) + "）");
            });
        } else {
            repo.findByUri(uri).ifPresent(t -> {
                throw new DownloadException(409, "该链接已在任务列表（任务 #" + t.id() + " " + statusLabel(t.status()) + "）");
            });
        }
        // 原型结论：无 tracker 磁力依赖 DHT，元数据解析极慢——配置 tracker 全部注入磁力 uri
        String taskKey;
        String displayName = magnet.displayName();
        String infoHash = magnet.infoHash();
        if (infoHash == null && isTorrentLink(uri)) {
            // .torrent 直链：服务端抓取种子内容入队（文件清单立即可知，无二段 gid）
            byte[] torrent = fetchTorrent(uri);
            // v0.24 SE2：入队时由种子内容计算 BTIH 回填——种子任务获得跨轮 infoHash 去重与 qBt 直开任务键
            infoHash = BencodeParser.infoHashHex(torrent);
            if (infoHash != null) {
                String hash = infoHash;
                repo.findByInfohash(infoHash).ifPresent(t -> {
                    throw new DownloadException(409, "该资源已在任务列表（任务 #" + t.id() + " " + statusLabel(t.status()) + "）");
                });
            }
            taskKey = enqueueTorrent(engine, torrent, infoHash, info.downloadDir());
            displayName = null;
        } else {
            String finalUri = MagnetParser.mergeTrackers(uri, settings.trackers());
            try {
                taskKey = engine.enqueue(finalUri, magnet.infoHash(), info.downloadDir());
            } catch (DownloadException e) {
                throw e;
            } catch (Exception e) {
                throw new DownloadException(502, "引擎拒绝任务: " + e.getMessage());
            }
        }
        boolean externalHandoff = engine instanceof ExternalAppAdapter;
        long id = repo.insert(new DownloadRepository.TaskRow(
                0, taskKey, infoHash, displayName, uri,
                req.subjectId(), req.subjectName(), req.subjectNameCn(), req.episodeSort(),
                externalHandoff ? "external" : "queued", 0, 0, 0, 0, 0, 0, null, null, 0, false,
                System.currentTimeMillis(), null));
        log.info("下载任务 #{} {}（key={}, uri={}…）", id,
                externalHandoff ? "已拉起 qBittorrent 下载" : "已入队",
                taskKey, uri.substring(0, Math.min(60, uri.length())));
        return get(id);
    }

    /** .torrent 内容入队：aria2 走 addTorrent；直开=暂存临时种子文件后拉起 */
    private String enqueueTorrent(DownloadEngine engine, byte[] torrent, String infoHash, String downloadDir) {
        if (engine instanceof Aria2Adapter a) {
            return a.enqueueTorrent(torrent, infoHash, downloadDir);
        }
        if (engine instanceof ExternalAppAdapter e) {
            return e.enqueueTorrent(torrent, infoHash, downloadDir);
        }
        throw new DownloadException(502, "未知引擎类型");
    }

    /* ── 控制 ── */

    public void pause(long id) {
        DownloadRepository.TaskRow t = requireControllable(id);
        try {
            engineFor(t).pause(t.gid());
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(502, "暂停失败: " + e.getMessage());
        }
        repo.updateRuntime(id, "paused", t.totalLen(), t.completedLen(), 0, 0, 0, 0, t.filesJson(), null, null);
    }

    public void resume(long id) {
        DownloadRepository.TaskRow t = requireControllable(id);
        try {
            engineFor(t).resume(t.gid());
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(502, "恢复失败: " + e.getMessage());
        }
    }

    /** 文件勾选（aria2=select-file） */
    public void applySelection(long id, DownloadSelectionRequest req) {
        DownloadRepository.TaskRow t = requireControllable(id);
        List<DownloadFileDto> files = parseFiles(t.filesJson());
        if (files.isEmpty()) throw new DownloadException(400, "文件清单尚未就绪（元数据解析中）");
        List<Integer> indexes = (req == null ? List.<Integer>of() : req.indexes()).stream()
                .filter(x -> x != null && x >= 1)
                .distinct()
                .toList();
        if (indexes.isEmpty()) throw new DownloadException(400, "至少选择一个文件");
        try {
            engineFor(t).applySelection(t.gid(), indexes);
            // 勾选状态由 aria2 tellStatus 下一轮 watcher 回读
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(502, "应用文件选择失败: " + e.getMessage());
        }
    }

    public void remove(long id, boolean deleteFiles) {
        DownloadRepository.TaskRow t = repo.find(id).orElseThrow(() -> new DownloadException(404, "任务不存在"));
        List<DownloadFileDto> files = parseFiles(t.filesJson());
        boolean aria2Engine = engineFor(t) instanceof Aria2Adapter;
        try {
            // aria2 引擎侧移除（文件由服务端删）；直开台账删除（文件由 qBittorrent 管理）
            engineFor(t).remove(t.gid(), !aria2Engine && deleteFiles);
        } catch (Exception e) {
            log.warn("任务 #{} 引擎侧删除失败（继续清理记录）: {}", id, e.toString());
        }
        if (deleteFiles && aria2Engine && !files.isEmpty()) {
            String root = Path.of(currentSettings().downloadDir()).toAbsolutePath().normalize().toString();
            for (DownloadFileDto f : files) {
                try {
                    Path p = Path.of(f.path()).toAbsolutePath().normalize();
                    if (!p.toString().startsWith(root)) continue; // 安全护栏：只删下载目录内文件
                    Files.deleteIfExists(p);
                    // aria2 断点续传控制文件一并清理
                    Files.deleteIfExists(p.resolveSibling(p.getFileName().toString() + ".aria2"));
                    Path parent = p.getParent();
                    while (parent != null && !parent.toString().equals(root)) {
                        try (var list = Files.list(parent)) {
                            if (list.findAny().isPresent()) break;
                        }
                        Files.deleteIfExists(parent);
                        parent = parent.getParent();
                    }
                } catch (Exception e) {
                    log.warn("删除文件失败 {}: {}", f.path(), e.toString());
                }
            }
        }
        repo.delete(id);
    }

    /* ── 设置 ── */

    public DownloadSettingsDto getSettings() {
        return toSettingsDto(currentSettings());
    }

    public DownloadSettingsDto updateSettings(DownloadSettingsDto dto) {
        DownloadSettings merged = new DownloadSettings(
                dto.engineType(), dto.enginePath(), dto.engineUrl(), dto.engineSecret(), dto.rpcPort(),
                dto.qbPath(),
                dto.downloadDir(), dto.maxConcurrent(), dto.uploadLimit(), dto.trackers(),
                dto.autoScan(), dto.seedTimeMinutes(), dto.checkCertificate(), dto.proxy());
        String err = merged.validate();
        if (err != null) throw new DownloadException(400, err);
        repo.putSetting(DownloadSettings.STORE_KEY, merged.toJson());
        // 当前引擎重启；切换引擎类型时另一引擎的子进程也停止（aria2 托管进程不再需要时销毁）
        router.current(merged).restart();
        if (!merged.aria2Managed()) {
            aria2Engine.stopManagedIfAny();
        }
        return getSettings();
    }

    /* ── watcher（aria2 引擎 → DB 单向同步 + 收养 + 恢复 + 完成闭环；直开任务不进入） ── */

    private void watchLoop() {
        while (running) {
            try {
                pollOnce();
            } catch (Exception e) {
                log.warn("下载轮询异常: {}", e.toString());
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pollOnce() {
        DownloadSettings settings = currentSettings();
        DownloadEngine engine = router.current(settings);
        List<DownloadRepository.TaskRow> tasks = repo.listNonTerminal();
        // 直开模式无 RPC 可同步；旧 aria2 任务在直开模式下也保持原状（引擎切换不自动接管）
        if (tasks.isEmpty() || engine instanceof ExternalAppAdapter) return;
        DownloadEngine.EngineInfo info = engine.ensureRunning();
        if (!info.available()) return; // 引擎不可用：保持现状，恢复后自动续传（engine info 已带原因）

        for (DownloadRepository.TaskRow t : tasks) {
            try {
                TaskSnapshot snap = aria2.status(t.gid());
                syncFromSnapshot(t, snap, settings);
            } catch (DownloadException e) {
                if (e.status == 409) {
                    recoverTask(t, settings);
                } else {
                    log.warn("任务 #{} 状态同步失败: {}", t.id(), e.getMessage());
                }
            } catch (Exception e) {
                log.warn("任务 #{} 状态同步失败: {}", t.id(), e.toString());
            }
        }
    }

    /** 统一快照落库（aria2 原生状态 → 统一状态映射） */
    private void syncFromSnapshot(DownloadRepository.TaskRow t, TaskSnapshot snap, DownloadSettings settings) {
        List<DownloadFileDto> files = snap.files();
        boolean filesKnown = DownloadStates.filesKnown(files.isEmpty() ? List.of() : List.of(files.get(0).path()));

        // 原型结论 ②：磁力元数据完成后负载是“新 gid”——按 infoHash 收养
        if ("complete".equals(snap.engineStatus()) && !filesKnown) {
            adoptOrMark(t, snap, settings);
            return;
        }
        String newStatus = DownloadStates.mapTaskStatus(snap.engineStatus(), filesKnown);
        String filesJson = files.isEmpty() ? t.filesJson() : toJson(files);
        repo.updateRuntime(t.id(), newStatus, snap.totalLength(), snap.completedLength(),
                snap.downloadSpeed(), snap.uploadSpeed(), snap.connections(), snap.seeds(),
                filesJson, snap.btName(), snap.errorMessage());
        if (filesKnown && t.infoHash() == null && snap.infoHash() != null && !snap.infoHash().isBlank()) {
            repo.updateInfohash(t.id(), snap.infoHash().toLowerCase(Locale.ROOT));
        }
        if ("completed".equals(newStatus) && !t.postprocessed()) {
            handleCompletion(repo.find(t.id()).orElse(t));
        }
    }

    /** aria2 元数据收养：active/waiting 列表找同 infoHash 后继 gid（v0.16 逻辑） */
    private void adoptOrMark(DownloadRepository.TaskRow t, TaskSnapshot snap, DownloadSettings settings) {
        String successor = findSuccessor(t);
        if (successor != null) {
            log.info("任务 #{} 元数据完成，收养后继 gid {} → {}", t.id(), t.gid(), successor);
            repo.updateGid(t.id(), successor, t.recoverCount());
            return;
        }
        int misses = t.recoverCount() + 1;
        if (misses > ADOPTION_MAX_MISS) {
            repo.updateRuntime(t.id(), "error", t.totalLen(), t.completedLen(), 0, 0, 0, 0,
                    t.filesJson(), null, "元数据解析完成后未找到负载任务");
            return;
        }
        repo.updateGid(t.id(), t.gid(), misses);
    }

    /** 在引擎 active/waiting 列表中找同 infoHash 的后继负载任务 */
    private String findSuccessor(DownloadRepository.TaskRow t) {
        if (t.infoHash() == null) return null;
        try {
            for (String gid : aria2.activeTaskKeys()) {
                TaskSnapshot s = aria2.status(gid);
                if (s.infoHash() != null && s.infoHash().equalsIgnoreCase(t.infoHash()) && !gid.equals(t.gid())) {
                    return gid;
                }
            }
        } catch (Exception e) {
            log.warn("任务 #{} 收养探测失败: {}", t.id(), e.toString());
        }
        return null;
    }

    /** 引擎重启/ gid 失效 → 以原始 uri 重新入队（上限 MAX_RECOVER 防循环） */
    private void recoverTask(DownloadRepository.TaskRow t, DownloadSettings settings) {
        if (t.recoverCount() >= MAX_RECOVER) {
            repo.updateRuntime(t.id(), "error", t.totalLen(), t.completedLen(), 0, 0, 0, 0,
                    t.filesJson(), null, "引擎中任务丢失且重试次数已达上限，请手动重新添加");
            return;
        }
        try {
            String finalUri = MagnetParser.mergeTrackers(t.uri(), settings.trackers());
            String gid = aria2.enqueue(finalUri, t.infoHash(), aria2Engine.downloadDir());
            repo.updateGid(t.id(), gid, t.recoverCount() + 1);
            log.info("任务 #{} 引擎任务丢失，已重新入队（新 gid={}，第 {} 次）", t.id(), gid, t.recoverCount() + 1);
        } catch (Exception e) {
            log.warn("任务 #{} 恢复失败: {}", t.id(), e.getMessage());
        }
    }

    /* ── DN5 完成闭环：增量扫描 → 等待结束 → 预绑定 + 来源任务回填 ── */

    private void handleCompletion(DownloadRepository.TaskRow t) {
        repo.markCompleted(t.id());
        log.info("任务 #{} 下载完成（{}），进入入库闭环", t.id(), t.name());
        if (!currentSettings().autoScan()) {
            log.info("任务 #{} 未开启完成后自动扫描（av 设置），跳过入库", t.id());
            return;
        }
        completionExecutor.submit(() -> {
            try {
                // 下载目录自动纳入媒体库（幂等）：未注册时补登记，扫描器才会走该目录
                String dir = Path.of(currentSettings().downloadDir()).toAbsolutePath().normalize().toString();
                if (dir != null && mediaRepo.findDirectoryByPath(dir).isEmpty()) {
                    mediaRepo.insertDirectory(dir);
                    log.info("下载目录已自动纳入媒体库扫描: {}", dir);
                }
                scanner.startAsync(false);
                // 等待本轮扫描结束：startedAt 代际判定（startAsync 与扫描线程置位 running 之间有窗口，
                // 且秒级快扫描可能整体错过 running 边沿——对齐 v0.14 抽屉轮询的同款结论），上限 5 分钟
                Long baselineStarted = scanner.status().startedAt();
                for (int i = 0; i < 600; i++) {
                    var s = scanner.status();
                    if (!s.running() && s.startedAt() != null && !s.startedAt().equals(baselineStarted)) break;
                    Thread.sleep(500);
                }
                List<DownloadFileDto> files = parseFiles(t.filesJson());
                for (DownloadFileDto f : files) {
                    try {
                        String path = Path.of(f.path()).toAbsolutePath().normalize().toString();
                        Long fileId = mediaRepo.findFileIdByPath(path).orElse(null);
                        if (fileId == null) continue;
                        mediaRepo.markFromTask(fileId, t.id());
                        if (t.subjectId() != null) {
                            MediaFileRow row = mediaRepo.findRowByPath(path).orElse(null);
                            if (row != null && !"bound".equals(row.matchState())) {
                                // 整季包按文件名解析集数（NameParser）；解析不出才落到任务级集数
                                int sort = row.parsedEpisode() != null ? row.parsedEpisode()
                                        : t.episodeSort() != null ? t.episodeSort() : 0;
                                if (sort > 0) {
                                    mediaRepo.updateMatch(fileId, "bound", t.subjectId(), t.subjectName(),
                                            t.subjectNameCn(), sort, true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("任务 #{} 文件入库处理失败 {}: {}", t.id(), f.path(), e.toString());
                    }
                }
                log.info("任务 #{} 入库闭环结束（文件 {} 个）", t.id(), files.size());
                if (t.subjectId() != null) {
                    events.publishEvent(new DownloadCompletedEvent(t.id(), t.subjectId()));
                }
            } catch (Exception e) {
                log.warn("任务 #{} 入库闭环异常: {}", t.id(), e.toString());
            }
        });
    }

    /* ── 工具 ── */

    /** http(s) 链接且路径以 .torrent 结尾（query 之前的路径段，大小写不敏感） */
    static boolean isTorrentLink(String uri) {
        if (uri == null) return false;
        String lower = uri.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        String path = uri.substring(uri.indexOf("://") + 3);
        int slash = path.indexOf('/');
        if (slash < 0) return false;
        path = path.substring(slash);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        return path.endsWith(".torrent");
    }

    private static final java.net.http.HttpClient FETCH_HTTP = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();

    /** v0.24 SE2 种子抓取容灾（对齐 ResourceService.fetchViaFailover 同款模式，无粘性记忆——入队一次性）：
     *  直连失败（DNS 污染连接级异常，含 TLS 握手被掐断）自动经代理重试一次；proxy-mode=direct 时不走代理。 */
    private byte[] fetchTorrent(String uri) {
        Exception lastError = null;
        boolean tryProxy = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean directOnly = "direct".equalsIgnoreCase(props.bangumi().proxyMode());
            boolean useProxy = !directOnly && tryProxy;
            if (useProxy && torrentProxyClient() == null) {
                tryProxy = false;
                continue;
            }
            try {
                java.net.http.HttpClient hc = useProxy ? torrentProxyClient() : FETCH_HTTP;
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(uri))
                        .timeout(java.time.Duration.ofSeconds(15)).GET().build();
                var res = hc.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) {
                    throw new DownloadException(400, "种子链接返回 HTTP " + res.statusCode());
                }
                byte[] body = res.body();
                if (body.length == 0 || body.length > 10 * 1024 * 1024) {
                    throw new DownloadException(400, "种子文件大小超出范围（0~10MB）");
                }
                if (body[0] != 'd') {
                    throw new DownloadException(400, "链接内容不是有效的 .torrent 种子文件（非 bencode）");
                }
                return body;
            } catch (java.io.IOException e) {
                // 行级失败切线重试一次（DNS 污染连接拒绝/超时/TLS 握手被掐断——nyaa 种子直链实测 2026-09-19）
                lastError = e;
                log.info("种子抓取{}失败（{}），切换为{}重试", useProxy ? "经代理" : "直连",
                        e.toString(), useProxy ? "直连" : "经代理");
                tryProxy = !useProxy;
            } catch (DownloadException e) {
                throw e;
            } catch (Exception e) {
                throw new DownloadException(400, "种子链接抓取失败: " + e.getMessage());
            }
        }
        throw new DownloadException(400, "种子链接抓取失败: "
                + (lastError == null ? "未知错误" : lastError.getMessage() == null ? lastError.toString() : lastError.getMessage()));
    }

    /** av.bangumi 代理地址的 HttpClient（懒加载，复用服务代理配置；未配置返回 null） */
    private volatile java.net.http.HttpClient torrentProxyClient;

    private java.net.http.HttpClient torrentProxyClient() {
        if (torrentProxyClient != null) return torrentProxyClient;
        String host = props.bangumi().proxyHost();
        Integer port = props.bangumi().proxyPort();
        if (host == null || host.isBlank() || port == null) return null;
        synchronized (this) {
            if (torrentProxyClient == null) {
                torrentProxyClient = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(host, port)))
                        .build();
            }
            return torrentProxyClient;
        }
    }

    /** 暂停/恢复/文件勾选要求任务可被引擎操控：直开台账任务拒绝并给出操作指引 */
    private DownloadRepository.TaskRow requireControllable(long id) {
        DownloadRepository.TaskRow t = repo.find(id).orElseThrow(() -> new DownloadException(404, "任务不存在"));
        if ("external".equals(t.status())) {
            throw new DownloadException(400, "已交给 qBittorrent 直开下载——请在 qBittorrent 中操作（此处仅任务台账）");
        }
        if (!DownloadStates.NON_TERMINAL.contains(t.status())) {
            throw new DownloadException(409, "任务已结束（" + statusLabel(t.status()) + "）");
        }
        return t;
    }

    private DownloadSettings currentSettings() {
        return DownloadSettings.load(repo.getSetting(DownloadSettings.STORE_KEY).orElse(null),
                DownloadSettings.defaults(props));
    }

    private void saveFiles(long id, List<DownloadFileDto> files) {
        if (files.isEmpty()) return;
        repo.updateFilesJson(id, toJson(files));
    }

    private String toJson(List<DownloadFileDto> files) {
        try {
            return MAPPER.writeValueAsString(files);
        } catch (Exception e) {
            return null;
        }
    }

    private List<DownloadFileDto> parseFiles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, DownloadFileDto.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private DownloadTaskDto toDto(DownloadRepository.TaskRow t) {
        return new DownloadTaskDto(t.id(), t.gid(), t.infoHash(), t.name(), t.uri(),
                t.subjectId(), t.subjectName(), t.subjectNameCn(), t.episodeSort(),
                t.status(), t.totalLen(), t.completedLen(), t.downloadSpeed(), t.uploadSpeed(),
                t.connections(), t.seeds(), parseFiles(t.filesJson()), t.error(),
                t.createdAt(), t.completedAt());
    }

    private DownloadSettingsDto toSettingsDto(DownloadSettings s) {
        return new DownloadSettingsDto(s.engineType(), s.enginePath(), s.engineUrl(), s.engineSecret(), s.rpcPort(),
                s.qbPath(),
                s.downloadDir(), s.maxConcurrent(), s.uploadLimit(), s.trackers(), s.autoScan(),
                s.seedTimeMinutes(), s.checkCertificate(), s.proxy());
    }

    public static String statusLabel(String status) {
        return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "queued" -> "排队中";
            case "metadata" -> "解析元数据";
            case "downloading" -> "下载中";
            case "paused" -> "已暂停";
            case "external" -> "已交给下载器";
            case "completed" -> "已完成";
            case "error" -> "失败";
            default -> status;
        };
    }
}
