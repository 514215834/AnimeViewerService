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
 *  服务端直连无 CORS，且使用合规自定义 UA（迭代文档 §3.3-3 浏览器 UA 限制的绕开）。 */
@Component
public class BangumiMatcher {

    private static final Logger log = LoggerFactory.getLogger(BangumiMatcher.class);

    private final ServiceProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> searchCache = new LinkedHashMap<>();
    private long lastRequestAt = 0;

    private record CacheEntry(List<BangumiSubjectDto> results, long at) {}

    public BangumiMatcher(ServiceProperties props) {
        this.props = props;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // 可选 HTTP 代理（api.bgm.tv 直连被重置的墙内环境）
        if (props.bangumi().proxyHost() != null && !props.bangumi().proxyHost().isBlank()
                && props.bangumi().proxyPort() != null) {
            factory.setProxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(props.bangumi().proxyHost(), props.bangumi().proxyPort())));
        }
        this.rest = RestClient.builder()
                .baseUrl(props.bangumi().baseUrl())
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.bangumi().userAgent())
                .defaultHeader("Accept", "application/json")
                .build();
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
            String resp = rest.post()
                    .uri("/v0/search/subjects")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
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
            String resp = rest.get().uri("/v0/subjects/{id}", subjectId).retrieve().body(String.class);
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
            String resp = rest.get()
                    .uri(uri -> uri.path("/v0/episodes").queryParam("subject_id", subjectId)
                            .queryParam("limit", 1000).build())
                    .retrieve().body(String.class);
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
