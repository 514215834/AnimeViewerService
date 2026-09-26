package com.animeviewer.service.media;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.model.Dtos.MatchOutcome;
import com.animeviewer.service.model.Dtos.MediaFileDto;
import com.animeviewer.service.model.Dtos.ScanStatus;
import com.animeviewer.service.store.MediaRepository;
import com.animeviewer.service.store.MediaRepository.MediaFileRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** S2 媒体库扫描：目录递归扫描（视频扩展名过滤）→ ffprobe 探测 → SQLite upsert；
 *  mtime+size 增量（未变化跳过探测）；消失文件移除；扫描完成后对「本轮新增/更新 + 未识别」文件执行 Bangumi 匹配
 *  （待确认 pending 为人工终态，不自动重搜）。单线程执行（同一时刻至多一次扫描），异步触发立即返回。
 *  扫描/匹配两阶段进度全程写入 ScanStatus（含匹配阶段 matchTotal/matchDone 与正在匹配的文件名）。 */
@Component
@Order(2)
public class LibraryScanner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LibraryScanner.class);

    /** 视频扩展名（与前端 mediaCore.VIDEO_EXTENSIONS 保持一致的超集） */
    private static final Set<String> VIDEO_EXTS = Set.of(
            "mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "m2ts", "wmv", "flv", "ogv");

    private final ServiceProperties props;
    private final MediaRepository repo;
    private final FfprobeService ffprobe;
    private final BangumiMatcher matcher;
    private final ExternalTool externalTool;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScanStatus status = idleStatus();
    /** 本轮新增/更新的文件绝对路径（仅扫描线程访问，startAsync 的 AtomicBoolean 保证无并发扫描） */
    private final Set<String> touchedPaths = new HashSet<>();

    public LibraryScanner(ServiceProperties props, MediaRepository repo, FfprobeService ffprobe,
                          BangumiMatcher matcher, ExternalTool externalTool) {
        this.props = props;
        this.repo = repo;
        this.ffprobe = ffprobe;
        this.matcher = matcher;
        this.externalTool = externalTool;
    }

    private static ScanStatus idleStatus() {
        return new ScanStatus(false, "idle", 0, 0, 0, 0, 0, 0, null, null, null, null, 0, 0);
    }

    public ScanStatus status() {
        return status;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.scan().autoOnStart()) {
            boolean hasDirs = !repo.listDirectories().isEmpty();
            if (hasDirs) {
                log.info("启动自动增量扫描（av.scan.auto-on-start=true）");
                startAsync(false);
            }
        }
    }

    /** 异步触发；已在扫描中时返回 false */
    public boolean startAsync(boolean full) {
        if (!running.compareAndSet(false, true)) return false;
        Thread t = new Thread(() -> {
            try {
                scanAll(full);
            } catch (Exception e) {
                log.error("扫描异常", e);
                withError(e.getMessage());
            } finally {
                running.set(false);
                synchronized (this) {
                    ScanStatus cur = status;
                    status = new ScanStatus(false, "done", cur.scanned(), cur.added(), cur.updated(), cur.removed(),
                            cur.matched(), cur.failed(), null, cur.startedAt(), System.currentTimeMillis(),
                            cur.lastError(), cur.matchTotal(), cur.matchDone());
                }
            }
        }, "library-scan");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void withError(String msg) {
        synchronized (this) {
            ScanStatus cur = status;
            status = copy(cur, "error", null, cur.startedAt(), System.currentTimeMillis(), msg);
        }
    }

    private void scanAll(boolean full) {
        synchronized (this) {
            status = new ScanStatus(true, "scanning", 0, 0, 0, 0, 0, 0, null, System.currentTimeMillis(), null, null, 0, 0);
        }
        touchedPaths.clear();
        boolean canProbe = externalTool.ffprobeAvailable();

        for (var dir : repo.listDirectories()) {
            if (!dir.enabled()) continue;
            Path root = Paths.get(dir.path());
            if (!Files.isDirectory(root)) {
                log.warn("目录不存在，跳过: {}", dir.path());
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> isVideo(p.getFileName().toString()))
                        .forEach(p -> processFile(dir.id(), p, canProbe, full));
            } catch (IOException e) {
                log.warn("目录遍历失败: {} ({})", dir.path(), e.toString());
            }
            // 移除已消失的文件
            for (MediaFileRow row : repo.rowsByDirectory(dir.id())) {
                if (!Files.isRegularFile(Path.of(row.path()))) {
                    repo.deleteFile(row.id());
                    bump(c -> c.removed++);
                }
            }
        }
        // 匹配阶段
        matchUnbound();
    }

    private boolean isVideo(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void processFile(long dirId, Path file, boolean canProbe, boolean forceFull) {
        try {
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            String absPath = file.toAbsolutePath().toString();
            String name = file.getFileName().toString();

            var existing = repo.findRowByPath(absPath);
            // mtime 增量：未变化且已有探测结果 → 跳过
            if (!forceFull && existing.isPresent()) {
                MediaFileRow r = existing.get();
                if (r.size() == size && r.mtime() == mtime && r.probedAt() != null) return;
            }

            FfprobeService.Probe probe = canProbe ? ffprobe.probe(absPath) : null;
            NameParser.ParsedName parsed = NameParser.parse(name);

            MediaFileRow row = new MediaFileRow(
                    0, dirId, absPath, name,
                    ext(name), size, mtime,
                    probe == null ? null : probe.durationSec(),
                    probe == null ? null : probe.container(),
                    probe == null ? null : probe.vcodec(),
                    probe == null ? null : probe.acodec(),
                    probe == null ? null : probe.width(),
                    probe == null ? null : probe.height(),
                    parsed.title(), parsed.episode(),
                    existing.map(MediaFileRow::matchState).orElse("unmatched"),
                    existing.map(MediaFileRow::subjectId).orElse(null),
                    existing.map(MediaFileRow::subjectName).orElse(null),
                    existing.map(MediaFileRow::subjectNameCn).orElse(null),
                    existing.map(MediaFileRow::episodeSort).orElse(null),
                    existing.map(MediaFileRow::autoBound).orElse(false),
                    existing.map(MediaFileRow::matchedAt).orElse(null),
                    probe != null ? System.currentTimeMillis() : existing.map(MediaFileRow::probedAt).orElse(null),
                    probe == null ? null : probe.error(),
                    existing.map(MediaFileRow::downloadTaskId).orElse(null),
                    null,
                    existing.map(MediaFileRow::aiMatchScore).orElse(null),
                    existing.map(MediaFileRow::aiMatchReason).orElse(null));

            boolean isNew = existing.isEmpty();
            repo.upsertScannedFile(dirId, row);
            touchedPaths.add(absPath);
            bump(c -> {
                c.scanned++;
                if (isNew) c.added++;
                else c.updated++;
            });
            withPhase("scanning", absPath);
        } catch (IOException e) {
            log.warn("文件处理失败: {} ({})", file, e.toString());
            bump(c -> c.failed++);
        }
    }

    /** 扫描后匹配（范围收窄，2026-09-13 评审采纳）：仅「本轮新增/更新的文件」+「全部未识别（unmatched）」；
     *  待确认（pending）为人工终态不自动重搜——单文件「匹配」按钮 / 改绑仍可手动触发 */
    private void matchUnbound() {
        withPhase("matching", null);
        Map<Long, MediaFileDto> todo = new LinkedHashMap<>();
        for (String path : touchedPaths) {
            repo.findRowByPath(path).ifPresent(r -> {
                if (!"bound".equals(r.matchState()) && hasTitle(r.parsedTitle())) {
                    todo.putIfAbsent(r.id(), MediaRepository.toDto(r));
                }
            });
        }
        for (MediaFileDto f : repo.listFiles(null, "unmatched", null, 100000, 0)) {
            if (hasTitle(f.parsedTitle())) todo.putIfAbsent(f.id(), f);
        }

        bump(c -> c.matchTotal = todo.size());
        for (MediaFileDto f : todo.values()) {
            withPhase("matching", f.name());
            MatchOutcome outcome = matcher.match(f.parsedTitle(), f.parsedEpisode());
            applyMatch(f.id(), outcome, f.parsedEpisode());
            boolean bound = "bound".equals(outcome.state());
            bump(c -> {
                c.matchDone++;
                if (bound) c.matched++;
            });
        }
    }

    private static boolean hasTitle(String title) {
        return title != null && !title.isBlank();
    }

    public void applyMatch(long fileId, MatchOutcome outcome, Integer parsedEpisode) {
        switch (outcome.state()) {
            case "bound" -> repo.updateMatch(fileId, "bound", outcome.subjectId(), outcome.subjectName(),
                    outcome.subjectNameCn(), parsedEpisode, outcome.exact());
            case "pending" -> repo.updateMatch(fileId, "pending", outcome.subjectId(), outcome.subjectName(),
                    outcome.subjectNameCn(), null, false);
            default -> repo.updateMatch(fileId, "unmatched", null, null, null, null, false);
        }
    }

    /* ── 状态机（单写线程 + synchronized 读改写；前端每 2s 轮询只读） ── */

    private static ScanStatus copy(ScanStatus s, String phase, String currentPath,
                                   Long startedAt, Long finishedAt, String lastError) {
        return new ScanStatus(s.running(), phase, s.scanned(), s.added(), s.updated(), s.removed(),
                s.matched(), s.failed(), currentPath, startedAt, finishedAt, lastError,
                s.matchTotal(), s.matchDone());
    }

    private void bump(java.util.function.Consumer<Counter> c) {
        synchronized (this) {
            Counter ctr = new Counter(status);
            c.accept(ctr);
            status = ctr.toStatus(status);
        }
    }

    private void withPhase(String phase, String currentPath) {
        synchronized (this) {
            ScanStatus s = status;
            status = new ScanStatus(s.running(), phase, s.scanned(), s.added(), s.updated(), s.removed(),
                    s.matched(), s.failed(), currentPath, s.startedAt(), s.finishedAt(), s.lastError(),
                    s.matchTotal(), s.matchDone());
        }
    }

    private static final class Counter {
        long scanned, added, updated, removed, matched, failed, matchTotal, matchDone;

        Counter(ScanStatus s) {
            this.scanned = s.scanned();
            this.added = s.added();
            this.updated = s.updated();
            this.removed = s.removed();
            this.matched = s.matched();
            this.failed = s.failed();
            this.matchTotal = s.matchTotal();
            this.matchDone = s.matchDone();
        }

        ScanStatus toStatus(ScanStatus s) {
            return new ScanStatus(s.running(), s.phase(), scanned, added, updated, removed,
                    matched, failed, s.currentPath(), s.startedAt(), s.finishedAt(), s.lastError(),
                    matchTotal, matchDone);
        }
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
