package com.animeviewer.service.media;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.config.NetworkSettings;
import com.animeviewer.service.config.NetworkSettingsProvider;
import com.animeviewer.service.model.Dtos.BangumiEpisodeDto;
import com.animeviewer.service.model.Dtos.BangumiSubjectDto;
import com.animeviewer.service.model.Dtos.MatchOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** S3 Bangumi 匹配器：文件名解析结果 → v0 搜索（type=2 动画）→ 归一化比对 →
 *  高置信（归一化后全等）自动绑定，否则进入「待确认」并保存最佳候选。
 *  服务端直连无 CORS，且使用合规自定义 UA（迭代文档 §3.3-3 浏览器 UA 限制的绕开）。
 *  网络线路：proxy-mode = direct（仅直连）/ proxy（仅代理）/ auto（默认——直连失败自动经代理重试，
 *  可用线路粘性记忆；墙内直连被重置 + 本机 Clash 的典型环境下开箱即用，无需额外启动参数）。
 *  v1.0 补记四：线路运行期可改（设置页 PUT /api/network/settings），ensureClients 按版本号重建。 */
@Component
public class BangumiMatcher {

    private static final Logger log = LoggerFactory.getLogger(BangumiMatcher.class);

    private final ServiceProperties props;
    private final NetworkSettingsProvider network;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> searchCache = new LinkedHashMap<>();
    private long lastRequestAt = 0;
    /** auto 模式的粘性线路记忆：true = 上次成功走的是代理 */
    private volatile boolean useProxy = false;

    /** v1.0 补记四：线路设置运行期可改（设置页保存即生效）——按 Provider 版本号懒重建客户端 */
    private volatile long appliedVersion = -1;
    private volatile RestClient restDirect;
    private volatile RestClient restProxy;
    private volatile boolean proxyOnly;
    private volatile boolean autoFailover;

    private record CacheEntry(List<BangumiSubjectDto> results, long at) {}

    public BangumiMatcher(ServiceProperties props, NetworkSettingsProvider network) {
        this.props = props;
        this.network = network;
        ensureClients();
    }

    /** 线路设置变更（版本号不同）时重建 RestClient；每次请求入口调用，热路径只有一次版本号比较 */
    private void ensureClients() {
        long v = network.version();
        if (v == appliedVersion) return;
        synchronized (this) {
            if (v == appliedVersion) return;
            NetworkSettings s = network.current();
            String mode = s.proxyMode() == null ? "auto" : s.proxyMode();
            boolean hasProxy = s.hasProxy();
            if ("proxy".equals(mode) && !hasProxy) {
                log.warn("网络线路 mode=proxy 但未配置代理地址，退化为 auto 模式");
                mode = "auto";
            }
            this.proxyOnly = "proxy".equals(mode);
            this.autoFailover = !this.proxyOnly && !"direct".equals(mode);
            this.restDirect = buildClient(null, null);
            this.restProxy = hasProxy ? buildClient(s.proxyHost(), s.proxyPort()) : null;
            this.appliedVersion = v;
            log.info("Bangumi 线路模式: {}（代理: {}）", mode, hasProxy ? s.proxyHost() + ":" + s.proxyPort() : "未配置");
        }
    }

    /** v0.27 C1（补记四回退件复用，见 §5N 补记六承诺）：SimpleClientHttpRequestFactory（HttpURLConnection）
     *  经代理访问 api.bgm.tv 稳定 502（curl / JDK HttpClient 同代理均 200 实锤）→ 换 JdkClientHttpRequestFactory
     *  （与 HanimeService 同款 java.net.http.HttpClient）；显式 connect 5s / read 20s——原 Simple 工厂零超时，
     *  直连 DNS 污染时挂 OS 层 SYN 超时 ~21s 才切线路。exchange 尝试次数维持现状（2 次）不变。 */
    private RestClient buildClient(String proxyHost, Integer proxyPort) {
        java.net.http.HttpClient.Builder cb = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5));
        if (proxyHost != null) {
            cb.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(proxyHost, proxyPort)));
        }
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(cb.build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(20));
        return RestClient.builder()
                .baseUrl(props.bangumi().baseUrl())
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.bangumi().userAgent())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /** 统一请求执行：auto 模式下连接级失败（ResourceAccessException）自动切换线路重试一次，成功线路粘性记忆 */
    private String exchange(java.util.function.Function<RestClient, String> call) {
        ensureClients();
        if (!autoFailover) {
            return call.apply(proxyOnly ? restProxy : restDirect);
        }
        boolean tryProxy = useProxy;
        org.springframework.web.client.ResourceAccessException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            RestClient client = tryProxy ? restProxy : restDirect;
            if (client == null) {
                tryProxy = !tryProxy;
                continue;
            }
            try {
                String result = call.apply(client);
                useProxy = tryProxy;
                return result;
            } catch (org.springframework.web.client.ResourceAccessException e) {
                lastError = e;
                log.warn("Bangumi {}失败（{}），切换为{}重试",
                        tryProxy ? "经代理请求" : "直连请求",
                        rootMessage(e),
                        tryProxy ? "直连" : "经代理");
                tryProxy = !tryProxy;
            }
        }
        throw lastError;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? e.getMessage() : cur.getMessage();
    }

    /** 对单个文件执行匹配：返回落库用的结果 */
    public MatchOutcome match(String title, Integer episode) {
        if (title == null || title.isBlank()) {
            return new MatchOutcome("unmatched", null, null, null, false, "文件名未识别出标题");
        }
        List<BangumiSubjectDto> candidates = search(title);
        if (candidates.isEmpty()) {
            return new MatchOutcome("unmatched", null, null, null, false, "搜索无结果");
        }
        BangumiSubjectDto best = candidates.get(0);
        boolean exact = normalizedEquals(title, best)
                || candidates.size() > 1 && normalizedEquals(title, candidates.get(1));
        BangumiSubjectDto target = exact ? findExact(candidates, title) : best;
        if (exact) {
            return new MatchOutcome("bound", target.subjectId(), target.name(), target.nameCn(), true, "标题全等，自动绑定");
        }
        return new MatchOutcome("pending", target.subjectId(), target.name(), target.nameCn(), false, "候选项待人工确认");
    }

    private BangumiSubjectDto findExact(List<BangumiSubjectDto> candidates, String title) {
        for (BangumiSubjectDto c : candidates) {
            if (normalizedEquals(title, c)) return c;
        }
        return candidates.get(0);
    }

    private boolean normalizedEquals(String title, BangumiSubjectDto s) {
        String n = NameParser.normalizeForMatch(title);
        if (n.isEmpty()) return false;
        return n.equals(NameParser.normalizeForMatch(s.nameCn()))
                || n.equals(NameParser.normalizeForMatch(s.name()));
    }

    /** v0 搜索（type=2 动画；内存缓存 10 分钟；限速） */
    public List<BangumiSubjectDto> search(String keyword) {
        String key = NameParser.normalizeForMatch(keyword);
        if (key.isEmpty()) return List.of();
        synchronized (this) {
            CacheEntry c = searchCache.get(key);
            if (c != null && System.currentTimeMillis() - c.at() < 10 * 60_000L) {
                return c.results();
            }
        }
        throttle();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "keyword", keyword,
                    "limit", 8,
                    "filter", Map.of("type", List.of(2))));
            String resp = exchange(client -> client.post()
                    .uri("/v0/search/subjects")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class));
            List<BangumiSubjectDto> list = new ArrayList<>();
            JsonNode root = mapper.readTree(resp);
            for (JsonNode item : root.path("data")) {
                if (item.path("id").isMissingNode()) continue;
                list.add(new BangumiSubjectDto(
                        item.path("id").asLong(),
                        item.path("name").asText(null),
                        item.path("name_cn").asText(null),
                        item.path("date").asText(null),
                        item.path("images").path("common").asText(null)));
            }
            synchronized (this) {
                searchCache.put(key, new CacheEntry(list, System.currentTimeMillis()));
                if (searchCache.size() > 200) {
                    String eldest = searchCache.keySet().iterator().next();
                    searchCache.remove(eldest);
                }
            }
            return list;
        } catch (Exception e) {
            log.warn("Bangumi 搜索失败: {} ({})", keyword, e.toString());
            return List.of();
        }
    }

    /** 条目详情（人工改绑回显名称用） */
    public BangumiSubjectDto subject(long subjectId) {
        throttle();
        try {
            String resp = exchange(client -> client.get()
                    .uri("/v0/subjects/{id}", subjectId)
                    .retrieve().body(String.class));
            JsonNode item = mapper.readTree(resp);
            return new BangumiSubjectDto(
                    item.path("id").asLong(),
                    item.path("name").asText(null),
                    item.path("name_cn").asText(null),
                    item.path("date").asText(null),
                    item.path("images").path("common").asText(null));
        } catch (Exception e) {
            log.warn("Bangumi 条目详情失败: {} ({})", subjectId, e.toString());
            return null;
        }
    }

    /** 条目剧集列表（人工改绑对齐 sort 用） */
    public List<BangumiEpisodeDto> episodes(long subjectId) {
        throttle();
        try {
            String resp = exchange(client -> client.get()
                    .uri(uri -> uri.path("/v0/episodes").queryParam("subject_id", subjectId)
                            .queryParam("limit", 1000).build())
                    .retrieve().body(String.class));
            List<BangumiEpisodeDto> list = new ArrayList<>();
            JsonNode root = mapper.readTree(resp);
            for (JsonNode item : root.path("data")) {
                if (item.path("id").isMissingNode()) continue;
                list.add(new BangumiEpisodeDto(
                        item.path("id").asLong(),
                        item.path("sort").asInt(),
                        item.path("type").asInt(),
                        item.path("name").asText(null),
                        item.path("name_cn").asText(null)));
            }
            return list;
        } catch (Exception e) {
            log.warn("Bangumi 剧集列表失败: {} ({})", subjectId, e.toString());
            return List.of();
        }
    }

    /** 简单限速：两次请求最小间隔（bangumi 限流友好） */
    private void throttle() {
        long gap = Math.max(0, props.bangumi().matchThrottleMs());
        synchronized (this) {
            long wait = lastRequestAt + gap - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }
}
