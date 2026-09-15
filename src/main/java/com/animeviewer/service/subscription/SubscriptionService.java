package com.animeviewer.service.subscription;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.download.DownloadRepository;
import com.animeviewer.service.download.DownloadService;
import com.animeviewer.service.media.LibraryScanner;
import com.animeviewer.service.model.Dtos.DownloadAddRequest;
import com.animeviewer.service.model.Dtos.DownloadSummaryDto;
import com.animeviewer.service.model.Dtos.DownloadTaskDto;
import com.animeviewer.service.model.Dtos.SubHitDto;
import com.animeviewer.service.model.Dtos.SubscriptionDto;
import com.animeviewer.service.model.Dtos.SubscriptionSettingsDto;
import com.animeviewer.service.model.Dtos.TaskBrief;
import com.animeviewer.service.resource.ResourceService;
import com.animeviewer.service.store.MediaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * v0.19 SU1 订阅自动化（Sonarr-lite）：条目级「自动追下载」订阅 → 定时检索（间隔 30~360 分钟可配）
 * → R2 关键词策略（中文名/原名双查询合并）→ 过滤（集数 > max(观看基线, 已下载最大集)、infohash 跨轮去重、
 * 大小下限滤广告、忽略字幕组）→ 命中入待确认队列（默认）或全自动入队（条目级显式开关 + 三重保护）。
 *
 * SU2：命中即台账（sub_hits），一键下载 / 忽略 / 忽略字幕组在此收口；SU3：summary() 供前端
 * 60s 轮询驱动侧边栏角标与 toast（lastHit/lastCompleted 新于上次所见即通知）。
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 调度 tick 粒度（间隔配置变更最迟 30s 生效） */
    private static final long TICK_MS = 30_000L;
    /** 单条订阅单轮最多产生的新命中（防脏数据刷屏） */
    private static final int MAX_HITS_PER_CYCLE = 10;

    private final SubscriptionRepository repo;
    private final DownloadRepository tasks;
    private final MediaRepository media;
    private final ResourceService resources;
    private final DownloadService downloads;
    private final ServiceProperties props;

    private Thread scheduler;
    private java.util.concurrent.ExecutorService checkExecutor;
    private volatile boolean running = true;
    /** 跨线程并发防护：同一订阅同一时刻只允许一轮检索（调度 tick / 手动触发 / 首次订阅三入口共用） */
    private final Set<Long> checking = ConcurrentHashMap.newKeySet();

    public SubscriptionService(SubscriptionRepository repo, DownloadRepository tasks, MediaRepository media,
                               ResourceService resources, DownloadService downloads, ServiceProperties props) {
        this.repo = repo;
        this.tasks = tasks;
        this.media = media;
        this.resources = resources;
        this.downloads = downloads;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        scheduler = new Thread(this::schedulerLoop, "subscription-scheduler");
        scheduler.setDaemon(true);
        scheduler.start();
        checkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "subscription-check");
            th.setDaemon(true);
            return th;
        });
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (scheduler != null) scheduler.interrupt();
        if (checkExecutor != null) checkExecutor.shutdownNow();
    }

    private void schedulerLoop() {
        while (running) {
            try {
                tick();
            } catch (Exception e) {
                log.warn("订阅调度轮异常: {}", e.toString());
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 到期订阅逐个检索（间隔从设置实时读取——改配置最迟一个 tick 生效） */
    private void tick() {
        SubscriptionSettings settings = currentSettings();
        long intervalMs = settings.intervalMinutes() * 60_000L;
        long now = System.currentTimeMillis();
        for (SubscriptionRepository.SubRow sub : repo.listSubs()) {
            if (sub.lastCheckedAt() != null && now - sub.lastCheckedAt() < intervalMs) continue;
            runCheck(sub, settings);
        }
    }

    /* ── 订阅管理 ── */

    public List<SubscriptionDto> list() {
        return repo.listSubs().stream().map(SubscriptionService::toDto).toList();
    }

    /** 订阅（幂等）：已存在则刷新名称并复活；返回是否新建。首次检索异步执行（RSS 抓取数秒，不阻塞请求） */
    public SubscriptionDto subscribe(long subjectId, String subjectName, String subjectNameCn,
                                     Integer minEpisode, Boolean auto) {
        if (subjectId <= 0) throw new DownloadException(400, "subjectId 不合法");
        Optional<SubscriptionRepository.SubRow> existing = repo.findSubBySubject(subjectId);
        long id;
        if (existing.isPresent()) {
            id = existing.get().id();
            repo.updateSubNames(id, subjectName, subjectNameCn);
        } else {
            id = repo.insertSub(subjectId, subjectName, subjectNameCn,
                    Boolean.TRUE.equals(auto), minEpisode == null ? 0 : Math.max(0, minEpisode));
        }
        SubscriptionRepository.SubRow sub = repo.findSub(id).orElseThrow();
        checkExecutor.submit(() -> runCheck(sub, currentSettings()));
        return toDto(sub);
    }

    /** 可选字段更新：auto 全自动开关 / minEpisode 观看基线抬升（前端上报观看进度） */
    public SubscriptionDto update(long id, Boolean auto, Integer minEpisode) {
        SubscriptionRepository.SubRow sub = repo.findSub(id)
                .orElseThrow(() -> new DownloadException(404, "订阅不存在"));
        if (auto != null) repo.setSubAuto(id, auto);
        if (minEpisode != null) {
            int v = Math.max(0, minEpisode);
            if (v >= sub.minEpisode()) repo.setSubMinEpisode(id, v);
        }
        return toDto(repo.findSub(id).orElseThrow());
    }

    public void unsubscribe(long id) {
        repo.findSub(id).orElseThrow(() -> new DownloadException(404, "订阅不存在"));
        repo.deleteSub(id);
    }

    /* ── 检索与过滤 ── */

    /** 手动全量检索（下载中心「立即检索」）：忽略间隔到期判定，立即跑全部订阅；返回本轮新命中数 */
    public int checkAllNow() {
        SubscriptionSettings settings = currentSettings();
        int hits = 0;
        for (SubscriptionRepository.SubRow sub : repo.listSubs()) {
            hits += runCheck(sub, settings);
        }
        return hits;
    }

    /** 单条订阅一轮检索；返回本轮新命中数（同订阅并发调用直接跳过） */
    private int runCheck(SubscriptionRepository.SubRow sub, SubscriptionSettings settings) {
        if (!checking.add(sub.subjectId())) return 0;
        try {
            return doCheck(sub, settings);
        } catch (Exception e) {
            log.warn("订阅 #{}（{}）检索失败: {}", sub.id(), sub.subjectNameCn(), e.toString());
            return 0;
        } finally {
            checking.remove(sub.subjectId());
        }
    }

    private int doCheck(SubscriptionRepository.SubRow sub, SubscriptionSettings settings) {
        List<com.animeviewer.service.model.Dtos.ResourceItemDto> items = searchMerged(sub);
        if (items.isEmpty()) {
            repo.markChecked(sub.id(), null);
            return 0;
        }
        // 过滤阈值 = max(观看基线, 下载任务最大集, 媒体库文件最大集)——后两者服务端自算，观看基线由前端上报
        int threshold = Math.max(sub.minEpisode(),
                Math.max(tasks.maxEpisodeForSubject(sub.subjectId()), media.maxEpisodeForSubject(sub.subjectId())));
        List<String> ignored = parseFansubs(sub.ignoredFansubsJson());
        long minBytes = settings.minSizeMb() > 0 ? settings.minSizeMb() * 1024L * 1024L : 0;
        long autoMaxBytes = settings.autoMaxSizeMb() > 0 ? settings.autoMaxSizeMb() * 1024L * 1024L : 0;
        long dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        int created = 0;
        Long firstHitAt = null;
        for (var it : items) {
            if (created >= MAX_HITS_PER_CYCLE) break;
            String magnet = it.magnet();
            if (magnet == null || !magnet.startsWith("magnet:?")) continue;
            Integer ep = SubscriptionFilter.parseEpisode(it.title());
            if (ep == null) continue; // 无集数的条目无法判新旧（剧场版/合集），不入队列
            if (ep <= threshold) continue;
            if (it.infoHash() != null) {
                // 跨轮去重：任何状态的历史任务/命中都不重复
                if (tasks.findByInfohash(it.infoHash()).isPresent()) continue;
                if (repo.existsInfohash(it.infoHash())) continue;
            }
            long sizeBytes = optBytes(SubscriptionFilter.parseSizeBytes(it.size()));
            if (minBytes > 0 && sizeBytes > 0 && sizeBytes < minBytes) continue;
            String fansub = SubscriptionFilter.extractFansub(it.title());
            if (fansub != null && ignored.stream().anyMatch(x -> x.equalsIgnoreCase(fansub))) continue;
            if (repo.existsPendingEpisode(sub.subjectId(), ep)) continue;

            String status = "pending";
            String note = null;
            if (sub.auto()) {
                String blocked = autoBlockReason(sub, settings, sizeBytes, autoMaxBytes, dayStart);
                if (blocked == null) {
                    try {
                        downloads.enqueue(new DownloadAddRequest(magnet, sub.subjectId(), sub.subjectName(),
                                sub.subjectNameCn(), ep));
                        status = "auto";
                    } catch (DownloadException e) {
                        if (e.status == 409) {
                            status = "enqueued";
                            note = "该资源已在任务列表";
                        } else {
                            log.warn("订阅 #{} 第 {} 话自动入队失败: {}", sub.id(), ep, e.getMessage());
                        }
                    }
                } else {
                    note = blocked;
                }
            }
            repo.insertHit(new SubscriptionRepository.HitRow(
                    0, sub.subjectId(), sub.subjectName(), sub.subjectNameCn(), ep,
                    it.title(), fansub, magnet, it.infoHash(), it.site(), it.size(), it.pubDate(),
                    status, note, System.currentTimeMillis(), null));
            created++;
            if (firstHitAt == null) firstHitAt = System.currentTimeMillis();
        }
        if (created > 0) {
            log.info("订阅 #{}（{}）命中 {} 条（阈值第 {} 话）", sub.id(), sub.subjectNameCn(), created, threshold);
        }
        repo.markChecked(sub.id(), firstHitAt);
        return created;
    }

    /** 全自动三重保护：命中保护任一条 → 返回降级原因（待确认人工把关），null = 允许自动入队 */
    private String autoBlockReason(SubscriptionRepository.SubRow sub, SubscriptionSettings settings,
                                   long sizeBytes, long autoMaxBytes, long dayStart) {
        if (repo.countAutoSince(dayStart) >= settings.autoDailyLimit()) {
            return "已达每日自动入队上限（" + settings.autoDailyLimit() + "）";
        }
        if (autoMaxBytes > 0 && sizeBytes > 0 && sizeBytes > autoMaxBytes) {
            return "超过单任务大小上限（" + settings.autoMaxSizeMb() + " MB）";
        }
        if (settings.autoOnlyMatched() && !media.hasBoundFile(sub.subjectId())) {
            return "条目尚未在媒体库匹配（仅已匹配条目保护）";
        }
        return null;
    }

    /** R2 关键词策略：中文名/原名双查询合并（infohash 优先、magnet 兜底去重，pubDate 降序） */
    private List<com.animeviewer.service.model.Dtos.ResourceItemDto> searchMerged(SubscriptionRepository.SubRow sub) {
        List<String> keywords = new ArrayList<>();
        for (String kw : new String[]{sub.subjectNameCn(), sub.subjectName()}) {
            if (kw != null && !kw.isBlank() && !keywords.contains(kw.trim())) keywords.add(kw.trim());
        }
        if (keywords.isEmpty()) throw new DownloadException(400, "订阅缺少可检索的关键词（条目名均为空）");
        LinkedHashMap<String, com.animeviewer.service.model.Dtos.ResourceItemDto> merged = new LinkedHashMap<>();
        for (String kw : keywords) {
            try {
                var res = resources.search(kw, null);
                for (var it : res.items()) {
                    String key = it.infoHash() != null ? it.infoHash() : it.magnet();
                    merged.merge(key, it, (a, b) -> newer(a, b));
                }
            } catch (Exception e) {
                log.info("订阅 #{} 关键词「{}」检索失败: {}", sub.id(), kw, e.getMessage());
            }
        }
        List<com.animeviewer.service.model.Dtos.ResourceItemDto> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> Long.compare(b.pubDate() == null ? 0 : b.pubDate(), a.pubDate() == null ? 0 : a.pubDate()));
        return out;
    }

    private static com.animeviewer.service.model.Dtos.ResourceItemDto newer(
            com.animeviewer.service.model.Dtos.ResourceItemDto a, com.animeviewer.service.model.Dtos.ResourceItemDto b) {
        long pa = a.pubDate() == null ? 0 : a.pubDate();
        long pb = b.pubDate() == null ? 0 : b.pubDate();
        return pb > pa ? b : a;
    }

    private static long optBytes(Long v) {
        return v == null ? 0 : v;
    }

    /* ── SU2 命中审核 ── */

    public List<SubHitDto> listHits(String status, int limit) {
        return repo.listHits(status, Math.min(Math.max(limit, 1), 200)).stream()
                .map(SubscriptionService::toHitDto).toList();
    }

    /** 一键下载：复用 v0.16 入队链路（409 去重 / tracker 注入 / 完成闭环全通）；409 时命中同样转已入队 */
    public DownloadTaskDto accept(long hitId) {
        SubscriptionRepository.HitRow h = repo.findHit(hitId)
                .orElseThrow(() -> new DownloadException(404, "命中记录不存在"));
        if (!"pending".equals(h.status())) {
            throw new DownloadException(409, "该命中已处理（" + hitStatusLabel(h.status()) + "）");
        }
        try {
            DownloadTaskDto task = downloads.enqueue(new DownloadAddRequest(
                    h.magnet(), h.subjectId(), h.subjectName(), h.subjectNameCn(), h.episodeSort()));
            repo.setHitStatus(hitId, "enqueued", null);
            return task;
        } catch (DownloadException e) {
            if (e.status == 409) repo.setHitStatus(hitId, "enqueued", "该资源已在任务列表");
            throw e;
        }
    }

    /** 忽略：可选「忽略该字幕组并记忆」（追加到订阅的 ignored_fansubs，后续命中直接过滤） */
    public void ignore(long hitId, Boolean blockFansub) {
        SubscriptionRepository.HitRow h = repo.findHit(hitId)
                .orElseThrow(() -> new DownloadException(404, "命中记录不存在"));
        if (!"pending".equals(h.status())) {
            throw new DownloadException(409, "该命中已处理（" + hitStatusLabel(h.status()) + "）");
        }
        String fansub = h.fansub();
        boolean block = Boolean.TRUE.equals(blockFansub) && fansub != null && !fansub.isBlank();
        repo.setHitStatus(hitId, "ignored", block ? "已忽略字幕组 " + fansub : null);
        if (block) {
            repo.findSubBySubject(h.subjectId()).ifPresent(sub -> {
                List<String> list = parseFansubs(sub.ignoredFansubsJson());
                if (list.stream().noneMatch(x -> x.equalsIgnoreCase(fansub))) {
                    list.add(fansub);
                    repo.setSubFansubs(sub.id(), toJsonFansubs(list));
                }
            });
        }
    }

    /** v0.19 批量忽略（多选/全选取消）：仅处理仍处于待确认的命中，已处理/不存在的跳过 */
    public Map<String, Long> batchIgnore(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new DownloadException(400, "未选择任何命中");
        long ignored = 0;
        long skipped = 0;
        for (Long id : ids.stream().distinct().toList()) {
            Optional<SubscriptionRepository.HitRow> h = id == null ? Optional.empty() : repo.findHit(id);
            if (h.isEmpty() || !"pending".equals(h.get().status())) {
                skipped++;
                continue;
            }
            repo.setHitStatus(id, "ignored", null);
            ignored++;
        }
        return Map.of("ignored", ignored, "skipped", skipped);
    }

    /** v0.19 命中历史批量删除（多选）：仅允许删除已处理命中，待确认/不存在的跳过——审核队列不可从这里绕过 */
    public Map<String, Long> batchDeleteHits(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new DownloadException(400, "未选择任何命中");
        long deleted = 0;
        long skipped = 0;
        for (Long id : ids.stream().distinct().toList()) {
            Optional<SubscriptionRepository.HitRow> h = id == null ? Optional.empty() : repo.findHit(id);
            if (h.isEmpty() || "pending".equals(h.get().status())) {
                skipped++;
                continue;
            }
            repo.deleteHit(id);
            deleted++;
        }
        return Map.of("deleted", deleted, "skipped", skipped);
    }

    /** v0.19 清空命中历史：删除全部非待确认命中，返回删除条数。
     *  副作用提示：sub_hits 同时承担跨轮 infohash 去重记忆，清空后同一资源再次发布仍会重新生成命中。 */
    public long clearHitHistory() {
        return repo.deleteNonPendingHits();
    }

    /* ── SU3 通知汇总 ── */

    public DownloadSummaryDto summary() {
        Optional<SubscriptionRepository.HitRow> hit = repo.latestHit();
        Optional<DownloadRepository.TaskRow> done = tasks.latestCompleted();
        return new DownloadSummaryDto(
                repo.countPending(),
                tasks.listNonTerminal().size(),
                hit.map(SubscriptionService::toHitDto).orElse(null),
                done.map(t -> new TaskBrief(t.id(), t.name(), t.subjectName(), t.subjectNameCn(), t.completedAt()))
                        .orElse(null));
    }

    /* ── 设置 ── */

    public SubscriptionSettingsDto getSettings() {
        return toSettingsDto(currentSettings());
    }

    public SubscriptionSettingsDto updateSettings(SubscriptionSettingsDto dto) {
        SubscriptionSettings merged = new SubscriptionSettings(dto.intervalMinutes(), dto.minSizeMb(),
                dto.autoDailyLimit(), dto.autoMaxSizeMb(), dto.autoOnlyMatched());
        String err = merged.validate();
        if (err != null) throw new DownloadException(400, err);
        repo.putSetting(SubscriptionSettings.STORE_KEY, merged.toJson());
        return getSettings();
    }

    private SubscriptionSettings currentSettings() {
        return SubscriptionSettings.load(
                repo.getSetting(SubscriptionSettings.STORE_KEY).orElse(null),
                SubscriptionSettings.defaults(props));
    }

    /* ── 工具 ── */

    static List<String> parseFansubs(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> out = new ArrayList<>();
            MAPPER.readTree(json).forEach(n -> { if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText()); });
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String toJsonFansubs(List<String> list) {
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static SubscriptionDto toDto(SubscriptionRepository.SubRow s) {
        return new SubscriptionDto(s.id(), s.subjectId(), s.subjectName(), s.subjectNameCn(),
                s.auto(), s.minEpisode(), parseFansubs(s.ignoredFansubsJson()),
                s.lastCheckedAt(), s.lastHitAt(), s.createdAt());
    }

    private static SubHitDto toHitDto(SubscriptionRepository.HitRow h) {
        return new SubHitDto(h.id(), h.subjectId(), h.subjectName(), h.subjectNameCn(), h.episodeSort(),
                h.title(), h.fansub(), h.magnet(), h.infohash(), h.site(), h.size(), h.pubDate(),
                h.status(), h.note(), h.createdAt(), h.decidedAt());
    }

    private static SubscriptionSettingsDto toSettingsDto(SubscriptionSettings s) {
        return new SubscriptionSettingsDto(s.intervalMinutes(), s.minSizeMb(), s.autoDailyLimit(),
                s.autoMaxSizeMb(), s.autoOnlyMatched());
    }

    public static String hitStatusLabel(String status) {
        return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "pending" -> "待确认";
            case "enqueued" -> "已入队";
            case "auto" -> "已自动入队";
            case "ignored" -> "已忽略";
            default -> status;
        };
    }
}
