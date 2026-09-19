package com.animeviewer.service.resource;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.download.DownloadRepository;
import com.animeviewer.service.download.DownloadService;
import com.animeviewer.service.download.MagnetParser;
import com.animeviewer.service.model.Dtos.DownloadAddRequest;
import com.animeviewer.service.model.Dtos.DownloadTaskDto;
import com.animeviewer.service.model.Dtos.ResourceAddRequest;
import com.animeviewer.service.model.Dtos.ResourceItemDto;
import com.animeviewer.service.model.Dtos.ResourceSiteDto;
import com.animeviewer.service.model.Dtos.ResourceSiteTestDto;
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

    /** 站点注册制：key → 覆盖定义（key 相同覆盖内置；新 key 追加），存 SQLite settings 表（JSON 数组）。
     *  v0.24 SM5：enabled 启停（false=停用，不参与混合搜索与订阅检索；缺省 true 兼容旧 JSON） */
    record Site(String key, String name, String baseUrl, String searchTemplate, boolean enabled) {
        Site(String key, String name, String baseUrl, String searchTemplate) {
            this(key, name, baseUrl, searchTemplate, true);
        }

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

    /** 添加/覆盖自定义站点（key 相同即覆盖；url 合法性在此校验；enabled 缺省启用——兼容旧客户端 JSON） */
    public void saveSite(ResourceSiteDto dto) {
        String key = dto.key() == null ? "" : dto.key().trim().toLowerCase(Locale.ROOT);
        String base = dto.baseUrl() == null ? "" : dto.baseUrl().trim();
        String tpl = dto.searchTemplate() == null ? "" : dto.searchTemplate().trim();
        validateSiteFields(key, base, tpl);
        boolean enabled = !Boolean.FALSE.equals(dto.enabled());
        List<Map<String, Object>> arr = new ArrayList<>();
        for (Site s : customSites()) {
            if (!s.key().equals(key)) {
                arr.add(siteJson(s.key(), s.name(), s.baseUrl(), s.searchTemplate(), s.enabled()));
            }
        }
        arr.add(siteJson(key, dto.name() == null || dto.name().isBlank() ? key : dto.name().trim(),
                base, tpl, enabled));
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
        List<Map<String, Object>> arr = new ArrayList<>();
        for (Site s : customSites()) {
            if (!s.key().equals(k)) {
                arr.add(siteJson(s.key(), s.name(), s.baseUrl(), s.searchTemplate(), s.enabled()));
            }
        }
        try {
            repo.putSetting(SITES_KEY, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arr));
        } catch (Exception e) {
            throw new DownloadException(500, "站点配置序列化失败");
        }
        cache.clear();
    }

    private static Map<String, Object> siteJson(String key, String name, String baseUrl, String template, boolean enabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("baseUrl", baseUrl);
        m.put("searchTemplate", template);
        m.put("enabled", enabled); // 布尔值落库（读侧兼容历史字符串形态）
        return m;
    }

    /** key/baseUrl/searchTemplate 合法性（saveSite 与 SM3 测试连通共用；文案即端点 400 响应） */
    private static void validateSiteFields(String key, String base, String tpl) {
        if (!key.matches("[a-z0-9_-]{1,24}")) throw new DownloadException(400, "站点标识需为 1~24 位小写字母/数字/连字符");
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new DownloadException(400, "站点地址需以 http(s):// 开头");
        }
        if (!tpl.contains("{kw}")) throw new DownloadException(400, "搜索模板需包含 {kw} 占位符");
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
                // 旧 JSON 缺 enabled 视为启用；兼容布尔与字符串两种历史落库形态
                Object e = m.get("enabled");
                boolean enabled = e == null || Boolean.parseBoolean(String.valueOf(e));
                out.add(new Site(key, String.valueOf(m.getOrDefault("name", key)), base, tpl, enabled));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ResourceSiteDto toDto(Site s) {
        boolean builtin = BUILTIN.stream().anyMatch(b -> b.key().equals(s.key()));
        return new ResourceSiteDto(s.key(), s.name(), s.baseUrl(), s.searchTemplate(), builtin, s.enabled());
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
        // v0.24 SM5：启停统一过滤——停用站点不参与混合搜索（null=全部）与显式站点检索（重新启用入口在管理弹窗）
        if (siteKeys == null || siteKeys.isEmpty()) {
            return all.values().stream().filter(Site::enabled).toList();
        }
        List<Site> out = new ArrayList<>();
        for (String k : siteKeys) {
            Site s = all.get(k);
            if (s != null && s.enabled()) out.add(s);
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
            } catch (java.io.IOException e) {
                // 行级失败一律切线重试：DNS 污染的连接拒绝/超时（ConnectException/HttpConnectTimeoutException）、
                // TLS 握手被掐断（JSSE 抛裸 IOException "Remote host terminated the handshake"，nyaa 直连实测 2026-09-19）、
                // 连接重置（SocketException）——均属网络层症状，与业务级 HTTP 非 200（上方 DownloadException）区分
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
            // v0.24 SE1 去重键扩展：infoHash 优先 > magnet > torrentUrl（种子型站点无磁力/无 hash 仍可去重）
            String key = it.infoHash() != null ? it.infoHash()
                    : it.magnet() != null ? it.magnet()
                    : it.torrentUrl();
            if (key == null) continue;
            byKey.merge(key, it, (a, b) -> a.pubDate() != null && b.pubDate() != null
                    ? (a.pubDate() >= b.pubDate() ? a : b) : a);
        }
        List<ResourceItemDto> out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> Long.compare(b.pubDate() == null ? 0 : b.pubDate(), a.pubDate() == null ? 0 : a.pubDate()));
        return out;
    }

    /* ── v0.25 RSS 固定直链订阅：抓取任意 RSS 直链（复用容灾与解析，供订阅直连源） ── */

    /** 抓取 + 解析一条 RSS 直链（不做站点级缓存——直链源每轮订阅检索只取一次）；
     *  失败抛 DownloadException（502 非 RSS/抓取失败，400 种子类错误不涉及）。 */
    public List<ResourceItemDto> fetchFeed(String url) {
        try {
            String body = fetchViaFailover(url);
            if (!RssResourceParser.looksLikeRss(body)) {
                throw new DownloadException(502, "响应不是 RSS（站点可能开启反爬）");
            }
            List<ResourceItemDto> items = RssResourceParser.parse(body, "rss");
            if (items.size() > MAX_ITEMS_PER_SITE) items = items.subList(0, MAX_ITEMS_PER_SITE);
            return items;
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(502, "RSS 直链抓取失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /* ── R2 一键入队（复用 v0.16 下载链路，含 409 去重与 tracker 注入） ── */

    /** v0.24 SE2：磁力或 .torrent/http(s)/ftp 直链均可入队（种子直链直通 DownloadService 种子分支——
     *  aria2 addTorrent / qBt 临时种子文件；校验口径对齐 MagnetParser.isSupported） */
    public DownloadTaskDto enqueue(ResourceAddRequest req) {
        String magnet = req == null || req.magnet() == null ? "" : req.magnet().trim();
        if (!MagnetParser.isSupported(magnet)) {
            throw new DownloadException(400, "仅支持磁力链接（magnet:?xt=urn:btih:...）或 http(s)/ftp 直链入队");
        }
        DownloadAddRequest add = new DownloadAddRequest(magnet, req.subjectId(), req.subjectName(),
                req.subjectNameCn(), req.episodeSort());
        return downloads.enqueue(add);
    }

    /* ── v0.24 SM3 站点测试连通（不入库）：抓取 + 解析预览，供添加表单「测试」按钮 ── */

    /** 测试站点可用性与解析结果：ok=false 时 error 为失败原因（非 RSS/反爬/HTTP 非 200/解析失败）；
     *  样例取前 3 条（title/size/磁力或种子）。测试词缺省「新番」（保证站方检索有泛结果可验）。 */
    public ResourceSiteTestDto testSite(String baseUrl, String searchTemplate, String keyword) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        String tpl = searchTemplate == null ? "" : searchTemplate.trim();
        String key = "test";
        validateSiteFields(key, base, tpl);
        String kw = keyword == null || keyword.isBlank() ? "新番" : keyword.trim();
        try {
            String body = fetchViaFailover(new Site(key, key, base, tpl).urlFor(kw));
            if (!RssResourceParser.looksLikeRss(body)) {
                return new ResourceSiteTestDto(false, "响应不是 RSS（站点可能开启反爬）", 0, List.of());
            }
            List<ResourceItemDto> items = RssResourceParser.parse(body, key);
            int count = items.size();
            return new ResourceSiteTestDto(true, null, count, items.subList(0, Math.min(3, count)));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            return new ResourceSiteTestDto(false, reason, 0, List.of());
        }
    }
}
