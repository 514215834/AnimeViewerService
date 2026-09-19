package com.animeviewer.service.media;

import com.animeviewer.service.ServiceProperties;
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
 *  可用线路粘性记忆；墙内直连被重置 + 本机 Clash 的典型环境下开箱即用，无需额外启动参数）。 */
@Component
public class BangumiMatcher {

    private static final Logger log = LoggerFactory.getLogger(BangumiMatcher.class);

    private final ServiceProperties props;
    private final RestClient restDirect;
    private final RestClient restProxy;
    private final boolean proxyOnly;
    private final boolean autoFailover;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> searchCache = new LinkedHashMap<>();
    private long lastRequestAt = 0;
    /** auto 模式的粘性线路记忆：true = 上次成功走的是代理 */
    private volatile boolean useProxy = false;

    private record CacheEntry(List<BangumiSubjectDto> results, long at) {}

    public BangumiMatcher(ServiceProperties props) {
        this.props = props;
        String mode = props.bangumi().proxyMode() == null ? "auto" : props.bangumi().proxyMode().trim().toLowerCase();
        String host = props.bangumi().proxyHost();
        boolean hasProxy = host != null && !host.isBlank() && props.bangumi().proxyPort() != null;
        if ("proxy".equals(mode) && !hasProxy) {
            log.warn("av.bangumi.proxy-mode=proxy 但未配置代理地址，退化为 auto 模式");
            mode = "auto";
        }
        this.proxyOnly = "proxy".equals(mode);
        this.autoFailover = !this.proxyOnly && !"direct".equals(mode);
        this.restDirect = buildClient(false);
        this.restProxy = hasProxy ? buildClient(true) : null;
        log.info("Bangumi 线路模式: {}（代理: {}）", mode, hasProxy ? host + ":" + props.bangumi().proxyPort() : "未配置");
    }

    private RestClient buildClient(boolean viaProxy) {
        // v0.26 补记：SimpleClientHttpRequestFactory（HttpURLConnection）经代理访问 api.bgm.tv 稳定 502
        // （curl/JDK HttpClient 同代理均 200，实锤为老连接栈问题）——换 JdkClientHttpRequestFactory
        // （java.net.http.HttpClient，与 HanimeService 同款）。直连 5s 快速失败切代理，读超时 20s。
        var jdk = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5));
        if (viaProxy) {
            jdk.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(
                    props.bangumi().proxyHost(), props.bangumi().proxyPort())));
        }
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(jdk.build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(20));
        return RestClient.builder()
                .baseUrl(props.bangumi().baseUrl())
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.bangumi().userAgent())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /** 统一请求执行：auto 模式下连接级失败（ResourceAccessException）自动切换线路重试一次，成功线路粘性记忆。
     *  v0.26 补记：尝试次数 2→3，并新增网关级瞬时故障（502/503/504）同线路重试——bgm 边缘经部分代理
     *  出口间歇性 502（实测 502 窗口与 200 窗口交替），原「2 次 + 仅连接级切线」会把瞬时 502 直接透传给前端；
     *  4xx/其余 5xx 为确定性失败，立即透传不重试。 */
    private String exchange(java.util.function.Function<RestClient, String> call) {
        if (!autoFailover) {
            return call.apply(proxyOnly ? restProxy : restDirect);
        }
        boolean tryProxy = useProxy;
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
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
            } catch (org.springframework.web.client.RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 502 || status == 503 || status == 504) {
                    // 网关级瞬时故障：bgm 边缘已应答（线路可达），同线路重试
                    lastError = e;
                    log.warn("Bangumi {}收到网关级 {}（bgm 边缘瞬时故障），同线路重试",
                            tryProxy ? "经代理请求" : "直连请求", status);
                    continue;
                }
                throw e;
            }
        }
        throw lastError != null ? lastError : new org.springframework.web.client.RestClientException("Bangumi 请求失败");
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? e.getMessage() : cur.getMessage();
    }

    /** v0.26 补记：GET /v0/* 只读透传执行——浏览器直连 api.bgm.tv 存在环境性故障（预检 OPTIONS 502 /
     *  直连超时），前端把带 Authorization 的 GET 交由服务端执行。走既有直连→代理容灾；authHeader 为
     *  用户 Access Token（可空，公共数据匿名可用）。上游 4xx/5xx 以 RestClientResponseException 抛出，
     *  由控制器透传状态码与响应体。 */
    public String fetchV0(String pathAndQuery, String authHeader) {
        return exchange(rc -> {
            var spec = rc.get().uri(java.net.URI.create(props.bangumi().baseUrl() + pathAndQuery));
            if (authHeader != null && !authHeader.isBlank()) spec = spec.header("Authorization", authHeader);
            return spec.retrieve().body(String.class);
        });
    }

    /** v0.26 补记：OAuth 授权码换 Token 服务端代理——bgm.tv oauth 端点的响应不允许浏览器跨域读取，
     *  浏览器直换必失败；凭据仅随请求体流转，服务端不落库。上游非 2xx 同样以异常抛出由控制器透传。 */
    public String postOauthToken(java.util.Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8) + "=" +
                        java.net.URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        return exchange(rc -> rc.post()
                .uri(java.net.URI.create("https://bgm.tv/oauth/access_token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(body)
                .retrieve()
                .body(String.class));
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
