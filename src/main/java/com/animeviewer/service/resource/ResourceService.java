package com.animeviewer.service.resource;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.download.DownloadRepository;
import com.animeviewer.service.download.DownloadService;
import com.animeviewer.service.model.Dtos.DownloadAddRequest;
import com.animeviewer.service.model.Dtos.DownloadTaskDto;
import com.animeviewer.service.model.Dtos.ResourceAddRequest;
import com.animeviewer.service.model.Dtos.ResourceItemDto;
import com.animeviewer.service.model.Dtos.ResourceSiteDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** v0.17 R1/R2 资源发现：同构 RSS 站点注册制（内置 acgnx，用户可添加 dmhy 本尊等同构源）→
 *  服务端抓取 rss.xml?keyword=（无 CORS；直连失败经 av.bangumi 代理自动容灾）→ RssResourceParser 归一化。
 *  抓取限速 + 5 分钟内存缓存（同关键词重复查询不重复出站）；失败降级为 error 文案（前端引导换词/换源/查代理）。 */
@Service
public class ResourceService {

    private static final Logger log = LoggerFactory.getLogger(ResourceService.class);

    /** 抓取超时 / 缓存有效期 / 全局最小间隔 / 单源单次条目上限 */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private static final long MIN_INTERVAL_MS = 3_000L;
    private static final int MAX_ITEMS_PER_SITE = 50;

    /** 内置站点（实测 RSS 结构同构：acgnx 为 dmhy 镜像；dmhy 本尊同 URL 形态） */
    private static final List<Site> BUILTIN = List.of(
            new Site("acgnx", "末日動漫資源庫（acgnx）", "https://share.acgnx.se", "rss.xml?keyword={kw}"),
            new Site("dmhy", "動漫花園（dmhy）", "https://share.dmhy.org", "topics/rss/rss.xml?keyword={kw}"));

    /** 站点注册制：key → 覆盖定义（key 相同覆盖内置；新 key 追加），存 SQLite settings 表（JSON 数组） */
    public record Site(String key, String name, String baseUrl, String searchTemplate) {
        /** {kw} 占位替换（百分号编码）；模板允许带 query（如 topics/rss/rss.xml?keyword={kw}） */
        String urlFor(String keyword) {
            return baseUrl.replaceAll("/+$", "") + "/"
                    + searchTemplate.replace("{kw}", URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
    }

    private final DownloadRepository repo;
    private final DownloadService downloads;
    private final ServiceProperties props;
    private final HttpClient client;
    /** 代理线路（懒加载；acgnx 实测 DNS 污染直连超时——探测定案：抓取走直连/代理自动容灾） */
    private volatile HttpClient proxyClient;

    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();
    private volatile long lastFetchAt = 0;

    private record CacheEntry(List<ResourceItemDto> items, long at) {}

    public ResourceService(DownloadRepository repo, DownloadService downloads,
                           ServiceProperties props, HttpClient client) {
        this.repo = repo;
        this.downloads = downloads;
        this.props = props;
        this.client = client;
    }

    /* ── 站点管理 ── */

    public List<ResourceSiteDto> listSites() {
        List<ResourceSiteDto> out = new ArrayList<>(BUILTIN.stream().map(ResourceService::toDto).toList());
        for (Site s : customSites()) {
            out.removeIf(x -> x.key().equals(s.key()));
            out.add(toDto(s));
        }
        return out;
    }

    /** 添加/覆盖自定义站点（key 相同即覆盖；url 合法性在此校验） */
    public void saveSite(ResourceSiteDto dto) {
        String key = dto.key() == null ? "" : dto.key().trim().toLowerCase(Locale.ROOT);
        String base = dto.baseUrl() == null ? "" : dto.baseUrl().trim();
        String tpl = dto.searchTemplate() == null ? "" : dto.searchTemplate().trim();
        if (!key.matches("[a-z0-9_-]{1,24}")) throw new DownloadException(400, "站点标识需为 1~24 位小写字母/数字/连字符");
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new DownloadException(400, "站点地址需以 http(s):// 开头");
        }
        if (!tpl.contains("{kw}")) throw new DownloadException(400, "搜索模板需包含 {kw} 占位符");
        List<Map<String, String>> arr = new ArrayList<>();
        for (Site s : customSites()) {
            if (!s.key().equals(key)) {
                arr.add(Map.of("key", s.key(), "name", s.name(), "baseUrl", s.baseUrl(), "searchTemplate", s.searchTemplate()));
            }
        }
        arr.add(Map.of("key", key, "name", dto.name() == null || dto.name().isBlank() ? key : dto.name().trim(),
                "baseUrl", base, "searchTemplate", tpl));
        try {
            repo.putSetting(SITES_KEY, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arr));
        } catch (Exception e) {
            throw new DownloadException(500, "站点配置序列化失败");
        }
        cache.clear();
    }

    public void removeSite(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (BUILTIN.stream().anyMatch(s -> s.key().equals(k))) {
            throw new DownloadException(400, "内置站点不可删除");
        }
        List<Map<String, String>> arr = new ArrayList<>();
        for (Site s : customSites()) {
            if (!s.key().equals(k)) {
                arr.add(Map.of("key", s.key(), "name", s.name(), "baseUrl", s.baseUrl(), "searchTemplate", s.searchTemplate()));
            }
        }
        try {
            repo.putSetting(SITES_KEY, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arr));
        } catch (Exception e) {
            throw new DownloadException(500, "站点配置序列化失败");
        }
        cache.clear();
    }

    static final String SITES_KEY = "resourceSites";

    @SuppressWarnings("unchecked")
    private List<Site> customSites() {
        String json = repo.getSetting(SITES_KEY).orElse(null);
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> arr = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
            List<Site> out = new ArrayList<>();
            for (Map<String, Object> m : arr) {
                String key = String.valueOf(m.get("key"));
                String base = String.valueOf(m.get("baseUrl"));
                String tpl = String.valueOf(m.get("searchTemplate"));
                if (key.isBlank() || base.isBlank() || tpl.isBlank()) continue;
                out.add(new Site(key, String.valueOf(m.getOrDefault("name", key)), base, tpl));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ResourceSiteDto toDto(Site s) {
        boolean builtin = BUILTIN.stream().anyMatch(b -> b.key().equals(s.key()));
        return new ResourceSiteDto(s.key(), s.name(), s.baseUrl(), s.searchTemplate(), builtin);
    }

    /* ── 搜索 ── */

    /** 多源合并搜索：去重（infoHash 优先、magnet 兜底）、按 pubDate 降序；error 汇总各源失败原因 */
    public com.animeviewer.service.model.Dtos.ResourceSearchDto search(String keyword, List<String> siteKeys) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) throw new DownloadException(400, "搜索关键词不能为空");
        List<Site> sites = resolveSites(siteKeys);
        if (sites.isEmpty()) throw new DownloadException(400, "没有可用的资源站点");

        List<ResourceItemDto> merged = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Site s : sites) {
            try {
                merged.addAll(fetchSite(s, kw));
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                errors.add(s.name() + "：" + reason);
                log.warn("资源搜索失败 site={} kw={} : {}", s.key(), kw, reason);
            }
        }
        List<ResourceItemDto> deduped = dedupe(merged);
        return new com.animeviewer.service.model.Dtos.ResourceSearchDto(
                kw, deduped, listSites(), errors.isEmpty() ? null : String.join("；", errors));
    }

    private List<Site> resolveSites(List<String> siteKeys) {
        Map<String, Site> all = new LinkedHashMap<>();
        for (Site s : BUILTIN) all.put(s.key(), s);
        for (Site s : customSites()) all.put(s.key(), s);
        if (siteKeys == null || siteKeys.isEmpty()) return List.copyOf(all.values());
        List<Site> out = new ArrayList<>();
        for (String k : siteKeys) {
            Site s = all.get(k);
            if (s != null) out.add(s);
        }
        return out;
    }

    private List<ResourceItemDto> fetchSite(Site site, String keyword) throws Exception {
        String cacheKey = site.key() + "|" + keyword;
        synchronized (cache) {
            CacheEntry c = cache.get(cacheKey);
            if (c != null && System.currentTimeMillis() - c.at() < CACHE_TTL_MS) return c.items();
            throttle();
        }
        String url = site.urlFor(keyword);
        String body = fetchViaFailover(url);
        if (!RssResourceParser.looksLikeRss(body)) {
            throw new DownloadException(502, "响应不是 RSS（站点可能开启反爬）");
        }
        List<ResourceItemDto> items = RssResourceParser.parse(body, site.key());
        if (items.size() > MAX_ITEMS_PER_SITE) items = items.subList(0, MAX_ITEMS_PER_SITE);
        synchronized (cache) {
            cache.put(cacheKey, new CacheEntry(items, System.currentTimeMillis()));
            if (cache.size() > 100) {
                String eldest = cache.keySet().iterator().next();
                cache.remove(eldest);
            }
        }
        return items;
    }

    /** 直连/代理自动容灾（对齐 BangumiMatcher auto 模式）：直连失败（连接级异常）自动经代理重试一次，
     *  成功线路粘性记忆；proxy-mode=direct 时不走代理。 */
    private String fetchViaFailover(String url) throws Exception {
        boolean proxyOnly = "proxy".equalsIgnoreCase(props.bangumi().proxyMode());
        boolean directOnly = "direct".equalsIgnoreCase(props.bangumi().proxyMode());
        Exception lastError = null;
        // 首选线路：粘性记忆（默认直连）
        boolean tryProxy = lastFetchUsedProxy && !directOnly;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean useProxy = proxyOnly || (tryProxy && !directOnly);
            if (useProxy && proxyHttpClient() == null) {
                tryProxy = false;
                continue;
            }
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                        .timeout(FETCH_TIMEOUT)
                        .header("User-Agent", props.bangumi().userAgent())
                        .header("Accept", "application/rss+xml, application/xml, text/xml, */*");
                HttpClient hc = useProxy ? proxyHttpClient() : client;
                HttpResponse<String> res = hc.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    throw new DownloadException(502, "HTTP " + res.statusCode());
                }
                lastFetchUsedProxy = useProxy;
                return res.body();
            } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException
                     | java.nio.channels.UnresolvedAddressException e) {
                lastError = e;
                log.info("资源抓取{}失败（{}），切换为{}重试", useProxy ? "经代理" : "直连",
                        e.toString(), useProxy ? "直连" : "经代理");
                tryProxy = !useProxy;
            }
        }
        throw lastError != null ? lastError : new DownloadException(502, "抓取失败");
    }

    /** av.bangumi 代理地址的 HttpClient（懒加载；未配置代理返回 null） */
    private HttpClient proxyHttpClient() {
        if (proxyClient != null) return proxyClient;
        String host = props.bangumi().proxyHost();
        Integer port = props.bangumi().proxyPort();
        if (host == null || host.isBlank() || port == null) return null;
        synchronized (this) {
            if (proxyClient == null) {
                proxyClient = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .proxy(ProxySelector.of(new java.net.InetSocketAddress(host, port)))
                        .build();
            }
            return proxyClient;
        }
    }

    /** auto 模式粘性线路记忆：true = 上次成功走的是代理 */
    private volatile boolean lastFetchUsedProxy = false;

    /** 全局抓取限速：两次出站最小间隔（镜像站友好） */
    private void throttle() {
        long wait = lastFetchAt + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastFetchAt = System.currentTimeMillis();
    }

    private static List<ResourceItemDto> dedupe(List<ResourceItemDto> items) {
        LinkedHashMap<String, ResourceItemDto> byKey = new LinkedHashMap<>();
        for (ResourceItemDto it : items) {
            String key = it.infoHash() != null ? it.infoHash() : it.magnet();
            byKey.merge(key, it, (a, b) -> a.pubDate() != null && b.pubDate() != null
                    ? (a.pubDate() >= b.pubDate() ? a : b) : a);
        }
        List<ResourceItemDto> out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> Long.compare(b.pubDate() == null ? 0 : b.pubDate(), a.pubDate() == null ? 0 : a.pubDate()));
        return out;
    }

    /* ── R2 一键入队（复用 v0.16 下载链路，含 409 去重与 tracker 注入） ── */

    public DownloadTaskDto enqueue(ResourceAddRequest req) {
        String magnet = req == null || req.magnet() == null ? "" : req.magnet().trim();
        if (!magnet.startsWith("magnet:?")) throw new DownloadException(400, "仅支持磁力链接入队");
        DownloadAddRequest add = new DownloadAddRequest(magnet, req.subjectId(), req.subjectName(),
                req.subjectNameCn(), req.episodeSort());
        return downloads.enqueue(add);
    }
}
