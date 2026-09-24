package com.animeviewer.service.subscription;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.ai.AiPrompts;
import com.animeviewer.service.ai.AiService;
import com.animeviewer.service.ai.HitVerdict;
import com.animeviewer.service.download.DownloadCompletedEvent;
import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.download.DownloadRepository;
import com.animeviewer.service.download.DownloadService;
import com.animeviewer.service.media.LibraryScanner;
import com.animeviewer.service.model.Dtos.AiRssResolveDto;
import com.animeviewer.service.model.Dtos.DownloadAddRequest;
import com.animeviewer.service.model.Dtos.DownloadSummaryDto;
import com.animeviewer.service.model.Dtos.DownloadTaskDto;
import com.animeviewer.service.model.Dtos.SubHitDto;
import com.animeviewer.service.model.Dtos.SubscriptionDto;
import com.animeviewer.service.model.Dtos.SubscriptionSettingsDto;
import com.animeviewer.service.model.Dtos.TaskBrief;
import com.animeviewer.service.resource.ResourceService;
import com.animeviewer.service.store.MediaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
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
 * 大小下限滤广告、忽略字幕组）→ 命中评分落库（v0.20 SU4 MatchScore）→ 入待确认队列（默认）
 * 或评分达标自动入队（订阅级阈值 auto_score>0 且 score≥阈值 + 三重保护；v0.20 阈值取代二值 auto 开关）。
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
    private final AiService ai;

    private Thread scheduler;
    private java.util.concurrent.ExecutorService checkExecutor;
    private volatile boolean running = true;
    /** 跨线程并发防护：同一订阅同一时刻只允许一轮检索（调度 tick / 手动触发 / 首次订阅三入口共用） */
    private final Set<Long> checking = ConcurrentHashMap.newKeySet();

    public SubscriptionService(SubscriptionRepository repo, DownloadRepository tasks, MediaRepository media,
                               ResourceService resources, DownloadService downloads, ServiceProperties props,
                               AiService ai) {
        this.repo = repo;
        this.tasks = tasks;
        this.media = media;
        this.resources = resources;
        this.downloads = downloads;
        this.props = props;
        this.ai = ai;
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

    /** 到期订阅逐个检索（间隔从设置实时读取——改配置最迟一个 tick 生效）；
     *  v0.20 优化：开启阈值（autoScore>0）的订阅无论到期与否，每 tick 执行「待确认评分转队」，
     *  阈值/偏好调整后存量 pending 最迟 30s 内重新评估（不依赖下一轮 RSS 检索）。 */
    private void tick() {
        SubscriptionSettings settings = currentSettings();
        long intervalMs = settings.intervalMinutes() * 60_000L;
        long now = System.currentTimeMillis();
        for (SubscriptionRepository.SubRow sub : repo.listSubs()) {
            if (sub.lastCheckedAt() != null && now - sub.lastCheckedAt() < intervalMs) {
                if (sub.autoScore() != null && sub.autoScore() > 0) {
                    autoEnqueuePending(sub, settings);
                }
                continue;
            }
            runCheck(sub, settings);
        }
    }

    /* ── 订阅管理 ── */

    public List<SubscriptionDto> list() {
        return repo.listSubs().stream().map(SubscriptionService::toDto).toList();
    }

    /** 订阅（幂等）：已存在则刷新名称并复活；返回是否新建。首次检索异步执行（RSS 抓取数秒，不阻塞请求）。
     *  v0.20：autoScore 匹配度阈值取代二值全自动开关（0=全手动默认，1~100=评分达标自动入队） */
    public SubscriptionDto subscribe(long subjectId, String subjectName, String subjectNameCn,
                                     Integer minEpisode, Integer autoScore) {
        if (subjectId <= 0) throw new DownloadException(400, "subjectId 不合法");
        // v0.20 SU4：未显式传阈值时取全局默认（设置页「新订阅匹配度阈值」；默认 0=全手动）
        int score = autoScore == null ? currentSettings().defaultAutoScore() : clampScore(autoScore);
        Optional<SubscriptionRepository.SubRow> existing = repo.findSubBySubject(subjectId);
        long id;
        if (existing.isPresent()) {
            id = existing.get().id();
            repo.updateSubNames(id, subjectName, subjectNameCn);
        } else {
            id = repo.insertSub(subjectId, subjectName, subjectNameCn,
                    score > 0, minEpisode == null ? 0 : Math.max(0, minEpisode), score);
        }
        SubscriptionRepository.SubRow sub = repo.findSub(id).orElseThrow();
        checkExecutor.submit(() -> runCheck(sub, currentSettings()));
        return toDto(sub);
    }

    /** 可选字段更新：autoScore 自动入队阈值（v0.20 取代 auto 开关）/ minEpisode 观看基线抬升（前端上报观看进度）/
     *  aiKeywords 扩展检索词（v0.22 AI2，前端编辑传 null=不改，传数组=全量覆盖）/
     *  rssUrl 固定直链订阅源（v0.25：null=不改，空串=清除回关键词检索，非空=设置——须 http(s):// 开头） */
    public SubscriptionDto update(long id, Integer autoScore, Integer minEpisode, List<String> aiKeywords,
                                  String rssUrl) {
        SubscriptionRepository.SubRow sub = repo.findSub(id)
                .orElseThrow(() -> new DownloadException(404, "订阅不存在"));
        if (autoScore != null) repo.setSubScore(id, clampScore(autoScore));
        if (minEpisode != null) {
            int v = Math.max(0, minEpisode);
            if (v >= sub.minEpisode()) repo.setSubMinEpisode(id, v);
        }
        if (aiKeywords != null) repo.setSubAiKeywords(id, toJsonKeywords(sanitizeKeywords(aiKeywords)));
        if (rssUrl != null) {
            String normalized = sanitizeRssUrl(rssUrl);
            repo.setSubRssUrl(id, normalized);
        }
        return toDto(repo.findSub(id).orElseThrow());
    }

    /** v0.25 直链 URL 净化（纯函数，JUnit 护航）：trim；空白 → null（清除）；非法前缀抛 400 */
    static String sanitizeRssUrl(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new DownloadException(400, "RSS 直链需以 http(s):// 开头");
        }
        return t;
    }

    private static int clampScore(Integer autoScore) {
        if (autoScore == null) return 0;
        return Math.max(0, Math.min(100, autoScore));
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
            log.warn("订阅 #{}（{}）检索失败: {}", sub.id(), sub.subjectNameCn(), e.toString(), e);
            return 0;
        } finally {
            checking.remove(sub.subjectId());
        }
    }

    private int doCheck(SubscriptionRepository.SubRow sub, SubscriptionSettings settings) {
        SearchOutcome outcome;
        if (sub.rssUrl() != null && !sub.rssUrl().isBlank()) {
            // v0.25 RSS 固定直链模式：直连源即权威源——跳过关键词检索与 AI 扩展词（蜜柑每番 RSS 等形态），
            // 直链条目走同一条过滤管线（集数解析/基线/infohash 去重/字幕组/大小下限/评分落库）
            outcome = fetchDirectFeed(sub);
        } else {
            // v0.22 AI2 懒生成：扩展检索词从未生成过（null）且 AI 就绪 → 首轮检索前生成一次（失败置 [] 防重试循环）
            if (sub.aiKeywordsJson() == null && ai.settings().ready()) {
                try {
                    List<String> generated = aiKeywords(sub);
                    repo.setSubAiKeywords(sub.id(), toJsonKeywords(generated));
                    sub = repo.findSub(sub.id()).orElse(sub);
                } catch (Exception e) {
                    log.warn("订阅 #{} AI 扩展关键词生成失败（降级原名检索）: {}", sub.id(), e.toString());
                    repo.setSubAiKeywords(sub.id(), "[]");
                    sub = repo.findSub(sub.id()).orElse(sub);
                }
            }
            outcome = searchMerged(sub);
        }
        if (outcome.error() != null) {
            // v0.20 SU8 检索失败显性化：全部关键词查询失败 → 记录最近错误（成功检索即清除）
            repo.setSubLastError(sub.id(), outcome.error());
        } else {
            repo.setSubLastError(sub.id(), null);
        }
        if (outcome.items().isEmpty()) {
            repo.markChecked(sub.id(), null);
            return 0;
        }
        // 过滤阈值 = max(观看基线, 下载任务最大集, 媒体库文件最大集)——后两者服务端自算，观看基线由前端上报
        int threshold = Math.max(sub.minEpisode(),
                Math.max(tasks.maxEpisodeForSubject(sub.subjectId()), media.maxEpisodeForSubject(sub.subjectId())));
        List<String> ignored = parseFansubs(sub.ignoredFansubsJson());
        long minBytes = settings.minSizeMb() > 0 ? settings.minSizeMb() * 1024L * 1024L : 0;
        // v0.20 SU5：评分字幕组偏好（全局偏好列表，与订阅级屏蔽 ignored 互补）
        List<String> preferred = settings.globalFansubs();

        int created = 0;
        Long firstHitAt = null;
        List<Long> createdIds = new ArrayList<>();
        for (var it : outcome.items()) {
            if (created >= MAX_HITS_PER_CYCLE) break;
            // v0.24 SE2：磁力或 .torrent 直链均入命中（种子型站点 nyaa/蜜柑无磁力）——链接列二选一承载，入队直通种子分支
            String uri = it.magnet() != null && !it.magnet().isBlank() ? it.magnet() : it.torrentUrl();
            if (uri == null || uri.isBlank()) continue;
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
            // v0.20 SU6：已入队同集忽略（开关控制——关闭可补收同集其他字幕组/编码版本）
            if (settings.skipEnqueuedEpisode() && repo.existsEnqueuedEpisode(sub.subjectId(), ep)) continue;

            // v0.20 评分修订：评分只在落库时计算并留存（服务端单端口径），**不参与搜索/过滤阶段**——
            // 命中一律先落待确认队列，由 autoEnqueuePending 在待确认阶段按阈值自动转队。
            // 若在此处直接判定，infohash 跨轮去重会让低于阈值的命中永远失去重评机会（阈值后续调整也不生效）。
            MatchScore.Result score = MatchScore.score(sub.subjectNameCn(), sub.subjectName(),
                    it.title(), fansub, preferred);
            long hitId = repo.insertHit(new SubscriptionRepository.HitRow(
                    0, sub.subjectId(), sub.subjectName(), sub.subjectNameCn(), ep,
                    it.title(), fansub, uri, it.infoHash(), it.site(), it.size(), it.pubDate(),
                    "pending", null, score.total(), score.detail(), System.currentTimeMillis(), null, null));
            createdIds.add(hitId);
            created++;
            if (firstHitAt == null) firstHitAt = System.currentTimeMillis();
        }
        if (created > 0) {
            log.info("订阅 #{}（{}）命中 {} 条（阈值第 {} 话）", sub.id(), sub.subjectNameCn(), created, threshold);
        }
        // v0.22 AI1：新命中逐条语义判定（落库后一次、结果落 ai_verdict；AI 失败/关闭静默跳过不阻塞）
        judgeHits(sub, createdIds);
        repo.markChecked(sub.id(), firstHitAt);
        // 待确认阶段自动转队（含本轮新命中与存量 pending——阈值调整后下一轮 tick 即生效）
        autoEnqueuePending(sub, settings);
        return created;
    }

    /**
     * v0.20 优化定案：待确认命中的评分自动转队。
     * 评分机制只在此阶段消费——扫描本订阅的 pending 命中，匹配度 ≥ autoScore 阈值的
     * 经三重保护后自动入队（status=auto + note 记录判分依据），从而：
     * <ul>
     *   <li>搜索/过滤阶段零评分筛选——通过基础过滤的资源 100% 落库可见，「搜索不到数据」的体感根除；</li>
     *   <li>阈值/偏好调整后，存量 pending 在下一轮 tick（≤30s）即被重新评估，不受 infohash 去重固化影响；</li>
     *   <li>单轮上限 MAX_HITS_PER_CYCLE 防脏数据刷屏，三重保护（日限/单限/仅已匹配）照常兜底。</li>
     * </ul>
     */
    private void autoEnqueuePending(SubscriptionRepository.SubRow sub, SubscriptionSettings settings) {
        int threshold = sub.autoScore() == null ? 0 : sub.autoScore();
        if (threshold <= 0) return;
        long autoMaxBytes = settings.autoMaxSizeMb() > 0 ? settings.autoMaxSizeMb() * 1024L * 1024L : 0;
        long dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int converted = 0;
        for (SubscriptionRepository.HitRow h : repo.listPendingBySubject(sub.subjectId(), MAX_HITS_PER_CYCLE)) {
            if (converted >= MAX_HITS_PER_CYCLE) break;
            Integer score = h.score();
            if (score == null || score < threshold) continue;
            // v0.22 AI1 语义拦截：AI 已判定非本篇的命中（OP/ED/书籍/无关资源），评分达标也不自动入队
            HitVerdict verdict = HitVerdict.parse(h.aiVerdict());
            if (verdict != null && verdict.nonMainline()) continue;
            String blocked = autoBlockReason(sub, settings, optBytes(SubscriptionFilter.parseSizeBytes(h.size())),
                    autoMaxBytes, dayStart);
            if (blocked != null) continue; // 保护降级：保持待确认（note 不覆盖——searchFailure/原 note 保留）
            try {
                downloads.enqueue(new DownloadAddRequest(h.magnet(), sub.subjectId(), sub.subjectName(),
                        sub.subjectNameCn(), h.episodeSort()));
                repo.setHitStatus(h.id(), "auto", "匹配度 " + score + "≥阈值 " + threshold + "，自动入队");
                converted++;
            } catch (DownloadException e) {
                if (e.status == 409) {
                    repo.setHitStatus(h.id(), "enqueued", "该资源已在任务列表");
                    converted++;
                } else {
                    log.warn("订阅 #{} 命中 #{} 待确认转自动入队失败: {}", sub.id(), h.id(), e.getMessage());
                }
            }
        }
        if (converted > 0) {
            log.info("订阅 #{}（{}）待确认命中按阈值 {} 自动转队 {} 条", sub.id(), sub.subjectNameCn(), threshold, converted);
        }
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

    /** R2 关键词策略：中文名/原名双查询合并（infohash 优先、magnet 兜底去重，pubDate 降序）。
     *  v0.20 SU8：error=全部关键词查询均失败时的错误摘要；部分站点失败不算（res.error 不算全挂）。
     *  v0.22 AI2：合并订阅级 AI 扩展检索词（罗马字/官方英文名/繁体——RSS 子串匹配下中文名全句常为死权重）。 */
    private SearchOutcome searchMerged(SubscriptionRepository.SubRow sub) {
        List<String> keywords = mergeKeywords(sub.subjectNameCn(), sub.subjectName(), sub.aiKeywordsJson());
        if (keywords.isEmpty()) throw new DownloadException(400, "订阅缺少可检索的关键词（条目名均为空）");
        LinkedHashMap<String, com.animeviewer.service.model.Dtos.ResourceItemDto> merged = new LinkedHashMap<>();
        String lastError = null;
        int failed = 0;
        for (String kw : keywords) {
            try {
                var res = resources.search(kw, null);
                for (var it : res.items()) {
                    // v0.24 SE1 去重键扩展对齐 ResourceService.dedupe：infoHash > magnet > torrentUrl
                    String key = it.infoHash() != null ? it.infoHash()
                            : it.magnet() != null ? it.magnet()
                            : it.torrentUrl();
                    if (key == null) continue;
                    merged.merge(key, it, (a, b) -> newer(a, b));
                }
            } catch (Exception e) {
                failed++;
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                log.info("订阅 #{} 关键词「{}」检索失败: {}", sub.id(), kw, lastError);
            }
        }
        List<com.animeviewer.service.model.Dtos.ResourceItemDto> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> Long.compare(b.pubDate() == null ? 0 : b.pubDate(), a.pubDate() == null ? 0 : a.pubDate()));
        return new SearchOutcome(out, failed >= keywords.size() ? lastError : null);
    }

    /** 检索结果 + 全失败错误摘要（v0.20 SU8：null=至少一路查询成功） */
    private record SearchOutcome(List<com.animeviewer.service.model.Dtos.ResourceItemDto> items, String error) {}

    /** v0.25 固定直链检索：抓订阅绑定的 RSS 直链（容灾在 ResourceService.fetchFeed）→ 复用解析归一化；
     *  失败整轮记为 error（SU8 语义：直链模式只有一路查询） */
    private SearchOutcome fetchDirectFeed(SubscriptionRepository.SubRow sub) {
        try {
            return new SearchOutcome(resources.fetchFeed(sub.rssUrl()), null);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            log.info("订阅 #{} RSS 直链检索失败: {}", sub.id(), reason);
            return new SearchOutcome(List.of(), "RSS 直链：" + reason);
        }
    }

    /* ── v0.22 AI1 命中语义判定 / AI2 扩展检索词 ── */

    /** 新命中逐条语义判定（落库后一次、结果落 ai_verdict；AI 未就绪/失败静默跳过不阻塞检索链路） */
    private void judgeHits(SubscriptionRepository.SubRow sub, List<Long> hitIds) {
        if (hitIds.isEmpty() || !ai.settings().ready()) return;
        for (long id : hitIds) repo.findHit(id).ifPresent(this::judgeHitInternal);
    }

    /** 手动判定入口（命中行「AI 判定」按钮——存量无判定命中/复核用）；返回判定后的命中 DTO */
    public SubHitDto judgeHitNow(long hitId) {
        SubscriptionRepository.HitRow h = repo.findHit(hitId)
                .orElseThrow(() -> new DownloadException(404, "命中记录不存在"));
        if (!ai.settings().ready()) {
            throw new DownloadException(400, "AI 未启用或未配置（设置页「AI 分析」填写接口地址与模型）");
        }
        judgeHitInternal(h);
        return toHitDto(repo.findHit(hitId).orElseThrow());
    }

    private void judgeHitInternal(SubscriptionRepository.HitRow h) {
        var sub = repo.findSubBySubject(h.subjectId());
        JsonNode node = ai.askJson(AiPrompts.hitJudgeSystem(), AiPrompts.hitJudgeUser(
                sub.map(SubscriptionRepository.SubRow::subjectNameCn).orElse(h.subjectNameCn()),
                sub.map(SubscriptionRepository.SubRow::subjectName).orElse(h.subjectName()),
                h.episodeSort() == null ? "null" : String.valueOf(h.episodeSort()),
                h.title()));
        if (node == null || !node.isObject()) return; // AI 失败：verdict 保持空，启发式照常
        String json = node.toString();
        repo.setHitAiVerdict(h.id(), json);
        HitVerdict verdict = HitVerdict.parse(json);
        // 自动忽略开关（默认关）：非本篇命中直接转 ignored——误判可清历史重评
        if (verdict != null && verdict.nonMainline() && ai.settings().autoIgnoreNonEpisode()
                && "pending".equals(h.status())) {
            repo.setHitStatus(h.id(), "ignored",
                    "AI 判定非本篇（" + verdict.type() + "）：" + (verdict.reason() == null ? "" : verdict.reason()));
            log.info("命中 #{} AI 判定非本篇（{}），已自动忽略", h.id(), verdict.type());
        }
    }

    /** v0.22 AI2：生成订阅扩展检索词并落库（手动触发用；首轮懒生成见 doCheck）；AI 未就绪抛 400 */
    public SubscriptionDto generateHitKeywords(long id) {
        SubscriptionRepository.SubRow sub = repo.findSub(id)
                .orElseThrow(() -> new DownloadException(404, "订阅不存在"));
        if (!ai.settings().ready()) {
            throw new DownloadException(400, "AI 未启用或未配置（设置页「AI 分析」填写接口地址与模型）");
        }
        repo.setSubAiKeywords(id, toJsonKeywords(aiKeywords(sub)));
        return toDto(repo.findSub(id).orElseThrow());
    }

    /** v0.30 A6：AI 解析订阅地址（RSS 编辑弹层「AI 解析」）——站点形态规则映射先行（不需 AI 就绪），
     *  规则推不出再走 LLM 兜底（附已启用站点 baseUrl/搜索模板上下文）。结果仅预填，保存仍人工（人工把关不变式）。 */
    public AiRssResolveDto aiResolveRss(long id, String text) {
        repo.findSub(id).orElseThrow(() -> new DownloadException(404, "订阅不存在"));
        String rule = RssUrlExtractor.extract(text);
        if (rule != null) {
            return new AiRssResolveDto("rule", rule, "已从输入识别出 RSS 地址（规则映射，人工确认后保存）");
        }
        if (!ai.settings().ready()) {
            throw new DownloadException(400, "规则未命中，且 AI 未启用或未配置（设置页「AI 分析」填写接口地址与模型）");
        }
        StringBuilder sites = new StringBuilder();
        for (var s : resources.listSites()) {
            if (s.enabled()) sites.append("- ").append(s.baseUrl()).append(" 模板：").append(s.searchTemplate()).append('\n');
        }
        JsonNode node = ai.askJson(AiPrompts.rssResolveSystem(), AiPrompts.rssResolveUser(text, sites.toString()));
        if (node == null || !node.isObject()) {
            return new AiRssResolveDto("none", null, "AI 解析失败（服务不可用或返回格式异常）");
        }
        String url = node.path("rssUrl").isTextual() ? node.path("rssUrl").asText("").trim() : "";
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return new AiRssResolveDto("ai", url, "AI 已从文本解析出 RSS 地址（建议保存前人工核对）");
        }
        String reason = node.path("reason").asText("").trim();
        return new AiRssResolveDto("none", null, "无法解析出 RSS 地址" + (reason.isBlank() ? "" : "：" + reason));
    }

    private List<String> aiKeywords(SubscriptionRepository.SubRow sub) {
        JsonNode node = ai.askJson(AiPrompts.keywordsSystem(),
                AiPrompts.keywordsUser(sub.subjectNameCn(), sub.subjectName()));
        List<String> raw = new ArrayList<>();
        if (node != null && node.path("keywords").isArray()) {
            node.path("keywords").forEach(n -> { if (n.isTextual()) raw.add(n.asText()); });
        }
        return sanitizeKeywords(raw);
    }

    /** 关键词净化（纯函数）：去空白、忽略大小写去重、单条 ≤100 字符、至多 10 条 */
    static List<String> sanitizeKeywords(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty() || t.length() > 100) continue;
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(t))) out.add(t);
            if (out.size() >= 10) break;
        }
        return out;
    }

    /** 检索关键词合并（纯函数，JUnit 护航）：中文名 → 原名 → AI 扩展词（顺序即查询序；大小写不敏感去重） */
    static List<String> mergeKeywords(String nameCn, String name, String aiKeywordsJson) {
        List<String> out = new ArrayList<>();
        for (String kw : new String[]{nameCn, name}) {
            if (kw == null) continue;
            String t = kw.trim();
            if (t.isBlank()) continue;
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(t))) out.add(t);
        }
        for (String kw : parseAiKeywords(aiKeywordsJson)) {
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(kw))) out.add(kw);
        }
        return out;
    }

    static List<String> parseAiKeywords(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            MAPPER.readTree(json).forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText().trim());
            });
        } catch (Exception ignore) {
            // 损坏 JSON：视作无扩展词
        }
        return out;
    }

    private static String toJsonKeywords(List<String> list) {
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
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

    /** v0.20 SU7：下载完成入库绑定后自动抬升观看基线（订阅 min_episode 只升不降）。
     *  基线原本仅由前端订阅时/详情页加载时上报抬升，离线期间滞后；媒体库最大绑定集数为权威值，
     *  与检索阈值 max(基线, 下载最大集, 媒体库最大集) 形成语义双保险。 */
    @EventListener
    public void onDownloadCompleted(DownloadCompletedEvent event) {
        if (event.subjectId() == null) return;
        try {
            Optional<SubscriptionRepository.SubRow> sub = repo.findSubBySubject(event.subjectId());
            if (sub.isEmpty()) return;
            int boundMax = media.maxEpisodeForSubject(event.subjectId());
            if (boundMax > sub.get().minEpisode()) {
                repo.setSubMinEpisode(sub.get().id(), boundMax);
                log.info("订阅 #{}（{}）观看基线随入库自动抬升至第 {} 话",
                        sub.get().id(), sub.get().subjectNameCn(), boundMax);
            }
        } catch (Exception e) {
            log.warn("订阅基线自动抬升失败（任务 #{}）: {}", event.taskId(), e.toString());
        }
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
                dto.autoDailyLimit(), dto.autoMaxSizeMb(), dto.autoOnlyMatched(),
                dto.defaultAutoScore(), dto.globalFansubs() == null ? List.of() : dto.globalFansubs(),
                dto.skipEnqueuedEpisode());
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
                s.autoScore(), s.lastCheckError(),
                s.lastCheckedAt(), s.lastHitAt(), s.createdAt(),
                s.aiKeywordsJson() == null ? null : parseAiKeywords(s.aiKeywordsJson()), s.rssUrl());
    }

    private static SubHitDto toHitDto(SubscriptionRepository.HitRow h) {
        return new SubHitDto(h.id(), h.subjectId(), h.subjectName(), h.subjectNameCn(), h.episodeSort(),
                h.title(), h.fansub(), h.magnet(), h.infohash(), h.site(), h.size(), h.pubDate(),
                h.status(), h.note(), h.score(), h.scoreDetail(), h.createdAt(), h.decidedAt(), h.aiVerdict());
    }

    private static SubscriptionSettingsDto toSettingsDto(SubscriptionSettings s) {
        return new SubscriptionSettingsDto(s.intervalMinutes(), s.minSizeMb(), s.autoDailyLimit(),
                s.autoMaxSizeMb(), s.autoOnlyMatched(), s.defaultAutoScore(), s.globalFansubs(),
                s.skipEnqueuedEpisode());
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
