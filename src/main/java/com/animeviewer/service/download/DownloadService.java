package com.animeviewer.service.download;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.media.LibraryScanner;
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
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * v0.16 DN1/DN2/DN5 下载编排：入队（磁力合并 tracker → addUri）、watcher 轮询（1.5s，引擎→DB 单向同步）、
 * 控制（暂停/恢复/文件勾选/删除）、完成闭环（触发增量扫描 → 等待扫描结束 → 预绑定 + 来源任务回填）。
 *
 * 原型实测的两个关键行为已在此消化：
 * ① 磁力元数据完成后 aria2 为负载生成新 gid（原 gid 标记 complete）——按 infoHash 收养后继 gid；
 * ② gid 不存在（引擎重启清空列表）→ 以原始 uri 重新入队恢复（recover_count 上限 3，防循环）。
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int POLL_MS = 1500;
    private static final int MAX_RECOVER = 3;
    private static final int ADOPTION_MAX_MISS = 6;

    private final DownloadRepository repo;
    private final Aria2Engine engine;
    private final MediaRepository mediaRepo;
    private final LibraryScanner scanner;
    private final ServiceProperties props;

    private Thread watcher;
    private java.util.concurrent.ExecutorService completionExecutor;
    private volatile boolean running = true;

    public DownloadService(DownloadRepository repo, Aria2Engine engine, MediaRepository mediaRepo,
                           LibraryScanner scanner, ServiceProperties props) {
        this.repo = repo;
        this.engine = engine;
        this.mediaRepo = mediaRepo;
        this.scanner = scanner;
        this.props = props;
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
        Aria2Engine.EngineInfo info = engine.ensureRunning();
        return new DownloadEngineDto(info.available(), info.mode(), info.version(), info.downloadDir(), info.error());
    }

    /* ── 入队 ── */

    public DownloadTaskDto enqueue(DownloadAddRequest req) {
        String uri = req == null || req.uri() == null ? "" : req.uri().trim();
        if (!MagnetParser.isSupported(uri)) {
            throw new DownloadException(400, "仅支持磁力链接（magnet:?xt=urn:btih:...）或 http(s)/ftp 直链");
        }
        Aria2Engine.EngineInfo info = engine.ensureRunning();
        if (!info.available()) {
            throw new DownloadException(503, "下载引擎不可用：" + info.error());
        }
        Aria2Client client = engine.client();
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
        // 原型结论：无 tracker 磁力依赖 DHT，元数据解析极慢——配置 tracker 全部注入
        String gid;
        String displayName = magnet.displayName();
        if (magnet.infoHash() == null && isTorrentLink(uri)) {
            // .torrent 直链：服务端抓取种子内容走 addTorrent（文件清单立即可知，无二段 gid）
            byte[] torrent = fetchTorrent(uri);
            try {
                gid = client.addTorrent(java.util.Base64.getEncoder().encodeToString(torrent),
                        List.of(), Map.of("dir", info.downloadDir()));
            } catch (Aria2Client.Aria2Exception e) {
                throw new DownloadException(502, "引擎拒绝任务: " + e.getMessage());
            }
            displayName = null;
        } else {
            String finalUri = MagnetParser.mergeTrackers(uri, currentSettings().trackers());
            try {
                gid = client.addUri(List.of(finalUri), Map.of("dir", info.downloadDir()));
            } catch (Aria2Client.Aria2Exception e) {
                throw new DownloadException(502, "引擎拒绝任务: " + e.getMessage());
            }
        }
        long id = repo.insert(new DownloadRepository.TaskRow(
                0, gid, magnet.infoHash(), displayName, uri,
                req.subjectId(), req.subjectName(), req.subjectNameCn(), req.episodeSort(),
                "queued", 0, 0, 0, 0, 0, 0, null, null, 0, false,
                System.currentTimeMillis(), null));
        log.info("下载任务 #{} 已入队（gid={}, uri={}…）", id, gid, uri.substring(0, Math.min(60, uri.length())));
        return get(id);
    }

    /* ── 控制 ── */

    public void pause(long id) {
        Aria2Client client = requireClient();
        DownloadRepository.TaskRow t = requireNonTerminal(id);
        try {
            client.forcePause(t.gid());
        } catch (Aria2Client.GidNotFoundException e) {
            throw new DownloadException(409, "引擎中无此任务（可能已结束），等待状态同步");
        } catch (Aria2Client.Aria2Exception e) {
            throw new DownloadException(502, "暂停失败: " + e.getMessage());
        }
        repo.updateRuntime(id, "paused", t.totalLen(), t.completedLen(), 0, 0, 0, 0, t.filesJson(), null, null);
    }

    public void resume(long id) {
        Aria2Client client = requireClient();
        DownloadRepository.TaskRow t = requireNonTerminal(id);
        try {
            client.unpause(t.gid());
        } catch (Aria2Client.GidNotFoundException e) {
            throw new DownloadException(409, "引擎中无此任务（可能已结束），等待状态同步");
        } catch (Aria2Client.Aria2Exception e) {
            throw new DownloadException(502, "恢复失败: " + e.getMessage());
        }
    }

    /** 文件勾选（select-file）：active 任务先暂停→改选项→恢复（原型验证 changeOption 暂停态可写） */
    public void applySelection(long id, DownloadSelectionRequest req) {
        Aria2Client client = requireClient();
        DownloadRepository.TaskRow t = requireNonTerminal(id);
        List<DownloadFileDto> files = parseFiles(t.filesJson());
        if (files.isEmpty()) throw new DownloadException(400, "文件清单尚未就绪（元数据解析中）");
        String indexes = (req == null ? List.<Integer>of() : req.indexes()).stream()
                .filter(x -> x != null && x >= 1)
                .map(String::valueOf)
                .distinct()
                .collect(Collectors.joining(","));
        if (indexes.isEmpty()) throw new DownloadException(400, "至少选择一个文件");
        boolean wasRunning = "downloading".equals(t.status()) || "queued".equals(t.status()) || "metadata".equals(t.status());
        try {
            if (wasRunning) client.forcePause(t.gid());
            client.changeOption(t.gid(), Map.of("select-file", indexes, "bt-remove-unselected-file", "true"));
            if (wasRunning) client.unpause(t.gid());
            Map<String, Object> st = client.tellStatus(t.gid(), List.of("files"));
            saveFiles(t.id(), filesOf(st));
        } catch (Aria2Client.Aria2Exception e) {
            throw new DownloadException(502, "应用文件选择失败: " + e.getMessage());
        }
    }

    public void remove(long id, boolean deleteFiles) {
        DownloadRepository.TaskRow t = repo.find(id).orElseThrow(() -> new DownloadException(404, "任务不存在"));
        Aria2Client client = engine.client();
        List<DownloadFileDto> files = parseFiles(t.filesJson());
        if (client != null && t.gid() != null) {
            try {
                client.forceRemove(t.gid());
                client.purgeDownloadResult();
            } catch (Exception ignored) {
                // 引擎侧可能已无此任务
            }
        }
        if (deleteFiles && !files.isEmpty()) {
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
                dto.enginePath(), dto.engineUrl(), dto.engineSecret(), dto.rpcPort(),
                dto.downloadDir(), dto.maxConcurrent(), dto.uploadLimit(), dto.trackers(),
                dto.autoScan(), dto.seedTimeMinutes(), dto.checkCertificate());
        String err = merged.validate();
        if (err != null) throw new DownloadException(400, err);
        repo.putSetting(DownloadSettings.STORE_KEY, merged.toJson());
        engine.restart();
        return getSettings();
    }

    /* ── watcher（引擎 → DB 单向同步 + 收养 + 恢复 + 完成闭环） ── */

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
        Aria2Engine.EngineInfo info = engine.ensureRunning();
        List<DownloadRepository.TaskRow> tasks = repo.listNonTerminal();
        if (tasks.isEmpty()) return;
        if (!info.available()) return; // 引擎不可用：保持现状，恢复后自动续传（engine info 已带原因）
        Aria2Client client = engine.client();
        for (DownloadRepository.TaskRow t : tasks) {
            try {
                syncTask(client, t);
            } catch (Aria2Client.GidNotFoundException e) {
                recoverTask(client, t);
            } catch (Aria2Client.Aria2Exception e) {
                log.warn("任务 #{} 状态同步失败: {}", t.id(), e.getMessage());
            }
        }
    }

    private void syncTask(Aria2Client client, DownloadRepository.TaskRow t) {
        Map<String, Object> st = client.tellStatus(t.gid(), List.of(
                "status", "totalLength", "completedLength", "downloadSpeed", "uploadSpeed",
                "connections", "numSeeds", "numSeeders", "bittorrent", "errorMessage", "files", "infoHash"));
        String engineStatus = Aria2Client.asString(st, "status");
        List<DownloadFileDto> files = filesOf(st);
        boolean filesKnown = DownloadStates.filesKnown(files.isEmpty() ? List.of() : List.of(files.get(0).path()));
        String infoHash = Aria2Client.asString(st, "infoHash");
        String btName = btName(st);

        // 原型结论 ②：磁力元数据完成后负载是“新 gid”——按 infoHash 收养
        if ("complete".equals(engineStatus) && !filesKnown) {
            String successor = findSuccessor(client, t);
            if (successor != null) {
                log.info("任务 #{} 元数据完成，收养后继 gid {} → {}", t.id(), t.gid(), successor);
                repo.updateGid(t.id(), successor, t.recoverCount());
                syncTask(client, repo.find(t.id()).orElse(t));
                return;
            }
            int misses = t.recoverCount() + 1;
            if (misses > ADOPTION_MAX_MISS) {
                repo.updateRuntime(t.id(), "error", t.totalLen(), t.completedLen(), 0, 0, 0, 0,
                        t.filesJson(), null, "元数据解析完成后未找到负载任务");
                return;
            }
            repo.updateGid(t.id(), t.gid(), misses);
            return;
        }

        String newStatus = DownloadStates.mapTaskStatus(engineStatus, filesKnown);
        String filesJson = files.isEmpty() ? t.filesJson() : toJson(files);
        repo.updateRuntime(t.id(), newStatus,
                Aria2Client.asLong(st, "totalLength"), Aria2Client.asLong(st, "completedLength"),
                Aria2Client.asLong(st, "downloadSpeed"), Aria2Client.asLong(st, "uploadSpeed"),
                (int) Aria2Client.asLong(st, "connections"),
                (int) Math.max(Aria2Client.asLong(st, "numSeeds"), Aria2Client.asLong(st, "numSeeders")),
                filesJson, btName,
                "error".equals(engineStatus) ? Aria2Client.asString(st, "errorMessage") : null);
        // addTorrent 任务的 infoHash 在文件已知后回填（供后续 infohash 去重）
        if (filesKnown && t.infoHash() == null && infoHash != null && !infoHash.isBlank()) {
            repo.updateInfohash(t.id(), infoHash.toLowerCase(Locale.ROOT));
        }

        if ("completed".equals(newStatus) && !t.postprocessed()) {
            handleCompletion(repo.find(t.id()).orElse(t));
        }
    }

    /** 在引擎 active/waiting 列表中找同 infoHash 的后继负载任务 */
    private String findSuccessor(Aria2Client client, DownloadRepository.TaskRow t) {
        if (t.infoHash() == null) return null;
        List<String> keys = List.of("gid", "infoHash", "status");
        List<Map<String, Object>> candidates = new ArrayList<>(client.tellList("aria2.tellActive", keys));
        candidates.addAll(client.tellPaged("aria2.tellWaiting", 0, 50, keys));
        for (Map<String, Object> c : candidates) {
            String ih = Aria2Client.asString(c, "infoHash");
            String gid = Aria2Client.asString(c, "gid");
            if (ih != null && gid != null && ih.equalsIgnoreCase(t.infoHash()) && !gid.equals(t.gid())) {
                return gid;
            }
        }
        return null;
    }

    /** 引擎重启/ gid 失效 → 以原始 uri 重新入队（上限 MAX_RECOVER 防循环） */
    private void recoverTask(Aria2Client client, DownloadRepository.TaskRow t) {
        if (t.recoverCount() >= MAX_RECOVER) {
            repo.updateRuntime(t.id(), "error", t.totalLen(), t.completedLen(), 0, 0, 0, 0,
                    t.filesJson(), null, "引擎中任务丢失且重试次数已达上限，请手动重新添加");
            return;
        }
        try {
            String finalUri = MagnetParser.mergeTrackers(t.uri(), currentSettings().trackers());
            String gid = client.addUri(List.of(finalUri), Map.of("dir", engine.downloadDir()));
            repo.updateGid(t.id(), gid, t.recoverCount() + 1);
            log.info("任务 #{} 引擎任务丢失，已重新入队（新 gid={}，第 {} 次）", t.id(), gid, t.recoverCount() + 1);
        } catch (Aria2Client.Aria2Exception e) {
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
                String dir = engine.downloadDir();
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
                                // 整季包按文件名解析集数（NameParser）；解析不出才落到任务级 episodeSort
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

    /** 服务端抓取 .torrent 内容（≤10MB；首字节须为 bencode dict 'd'） */
    private static byte[] fetchTorrent(String uri) {
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(uri))
                    .timeout(java.time.Duration.ofSeconds(15)).GET().build();
            var res = FETCH_HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
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
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(400, "种子链接抓取失败: " + e.getMessage());
        }
    }

    private Aria2Client requireClient() {
        Aria2Engine.EngineInfo info = engine.ensureRunning();
        if (!info.available()) throw new DownloadException(503, "下载引擎不可用：" + info.error());
        return engine.client();
    }

    private DownloadRepository.TaskRow requireNonTerminal(long id) {
        DownloadRepository.TaskRow t = repo.find(id).orElseThrow(() -> new DownloadException(404, "任务不存在"));
        if (!DownloadStates.NON_TERMINAL.contains(t.status())) {
            throw new DownloadException(409, "任务已结束（" + statusLabel(t.status()) + "）");
        }
        return t;
    }

    private DownloadSettings currentSettings() {
        return DownloadSettings.load(repo.getSetting(DownloadSettings.STORE_KEY).orElse(null),
                DownloadSettings.defaults(props));
    }

    private static String btName(Map<String, Object> st) {
        Object bt = st.get("bittorrent");
        if (bt instanceof Map<?, ?> m) {
            Object info = m.get("info");
            if (info instanceof Map<?, ?> im) {
                Object name = im.get("name");
                if (name != null) return String.valueOf(name);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawFiles(Map<String, Object> st) {
        Object files = st.get("files");
        return files instanceof List ? (List<Map<String, Object>>) files : List.of();
    }

    private static List<DownloadFileDto> filesOf(Map<String, Object> st) {
        return rawFiles(st).stream()
                .map(f -> new DownloadFileDto(
                        (int) Aria2Client.asLong(f, "index"),
                        Aria2Client.asString(f, "path"),
                        fileName(Aria2Client.asString(f, "path")),
                        Aria2Client.asLong(f, "length"),
                        Aria2Client.asLong(f, "completedLength"),
                        Aria2Client.asLong(f, "selected") == 1))
                .toList();
    }

    private static String fileName(String path) {
        if (path == null) return "";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
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
        return new DownloadSettingsDto(s.enginePath(), s.engineUrl(), s.engineSecret(), s.rpcPort(),
                s.downloadDir(), s.maxConcurrent(), s.uploadLimit(), s.trackers(), s.autoScan(),
                s.seedTimeMinutes(), s.checkCertificate());
    }

    public static String statusLabel(String status) {
        return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "queued" -> "排队中";
            case "metadata" -> "解析元数据";
            case "downloading" -> "下载中";
            case "paused" -> "已暂停";
            case "completed" -> "已完成";
            case "error" -> "失败";
            default -> status;
        };
    }
}
