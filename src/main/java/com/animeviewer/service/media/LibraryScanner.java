package com.animeviewer.service.media;

import com.animeviewer.service.ServiceProperties;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** S2 媒体库扫描：目录递归扫描（视频扩展名过滤）→ ffprobe 探测 → SQLite upsert；
 *  mtime+size 增量（未变化跳过探测）；消失文件移除；扫描完成后对未绑定文件执行 Bangumi 匹配。
 *  单线程执行（同一时刻至多一次扫描），异步触发立即返回 202。 */
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
    // 扫描状态（volatile 字段组合读；单写线程更新）
    private volatile ScanStatus status = idleStatus();

    public LibraryScanner(ServiceProperties props, MediaRepository repo, FfprobeService ffprobe,
                          BangumiMatcher matcher, ExternalTool externalTool) {
        this.props = props;
        this.repo = repo;
        this.ffprobe = ffprobe;
        this.matcher = matcher;
        this.externalTool = externalTool;
    }

    private static ScanStatus idleStatus() {
        return new ScanStatus(false, "idle", 0, 0, 0, 0, 0, 0, null, null, null, null);
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
                status = withError(e.getMessage());
            } finally {
                running.set(false);
                ScanStatus cur = status;
                status = new ScanStatus(false, "done", cur.scanned(), cur.added(), cur.updated(), cur.removed(),
                        cur.matched(), cur.failed(), null, cur.startedAt(), System.currentTimeMillis(), cur.lastError());
            }
        }, "library-scan");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private ScanStatus withError(String msg) {
        ScanStatus cur = status;
        return new ScanStatus(false, "error", cur.scanned(), cur.added(), cur.updated(), cur.removed(),
                cur.matched(), cur.failed(), null, cur.startedAt(), System.currentTimeMillis(), msg);
    }

    private void scanAll(boolean full) {
        status = new ScanStatus(true, "scanning", 0, 0, 0, 0, 0, 0, null, System.currentTimeMillis(), null, null);
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
                    bump(b -> b.removed++);
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
                    probe == null ? null : probe.error());

            boolean isNew = existing.isEmpty();
            repo.upsertScannedFile(dirId, row);
            bump(b -> {
                b.scanned++;
                if (isNew) b.added++;
                else b.updated++;
            });
            status = withPhase("scanning", file.toAbsolutePath().toString());
        } catch (IOException e) {
            log.warn("文件处理失败: {} ({})", file, e.toString());
            bump(b -> b.failed++);
        }
    }

    /** 扫描后匹配：仅处理非 bound 且有解析标题的文件（匹配只依赖网络与文件名，与 ffmpeg 无关） */
    private void matchUnbound() {
        status = withPhase("matching", null);
        var pending = repo.listFiles(null, null, null, 100000, 0);
        for (var f : pending) {
            if ("bound".equals(f.matchState())) continue;
            if (f.parsedTitle() == null || f.parsedTitle().isBlank()) continue;
            var outcome = matcher.match(f.parsedTitle(), f.parsedEpisode());
            applyMatch(f.id(), outcome, f.parsedEpisode());
            bump(b -> {
                if ("bound".equals(outcome.state())) b.matched++;
            });
        }
    }

    public void applyMatch(long fileId, com.animeviewer.service.model.Dtos.MatchOutcome outcome, Integer parsedEpisode) {
        switch (outcome.state()) {
            case "bound" -> repo.updateMatch(fileId, "bound", outcome.subjectId(), outcome.subjectName(),
                    outcome.subjectNameCn(), parsedEpisode, outcome.exact());
            case "pending" -> repo.updateMatch(fileId, "pending", outcome.subjectId(), outcome.subjectName(),
                    outcome.subjectNameCn(), null, false);
            default -> repo.updateMatch(fileId, "unmatched", null, null, null, null, false);
        }
    }

    private void bump(java.util.function.Consumer<Counter> c) {
        synchronized (this) {
            ScanStatus s = status;
            Counter ctr = new Counter(s.scanned(), s.added(), s.updated(), s.removed(), s.matched(), s.failed());
            c.accept(ctr);
            status = new ScanStatus(s.running(), s.phase(), ctr.scanned, ctr.added, ctr.updated, ctr.removed,
                    ctr.matched, ctr.failed, s.currentPath(), s.startedAt(), s.finishedAt(), s.lastError());
        }
    }

    private ScanStatus withPhase(String phase, String currentPath) {
        synchronized (this) {
            ScanStatus s = status;
            return new ScanStatus(s.running(), phase, s.scanned(), s.added(), s.updated(), s.removed(),
                    s.matched(), s.failed(), currentPath, s.startedAt(), s.finishedAt(), s.lastError());
        }
    }

    private static final class Counter {
        long scanned, added, updated, removed, matched, failed;

        Counter(long scanned, long added, long updated, long removed, long matched, long failed) {
            this.scanned = scanned;
            this.added = added;
            this.updated = updated;
            this.removed = removed;
            this.matched = matched;
            this.failed = failed;
        }
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
