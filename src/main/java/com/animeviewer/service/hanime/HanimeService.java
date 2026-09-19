package com.animeviewer.service.hanime;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.download.DownloadRepository;
import com.animeviewer.service.model.Dtos.HanimeConfigDto;
import com.animeviewer.service.model.Dtos.HanimeSearchResult;
import com.animeviewer.service.model.Dtos.HanimeTestDto;
import com.animeviewer.service.model.Dtos.HanimeWatchDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v0.26 HN1 hanime1.me 在线解析访问层。
 *  抓取宿主探测定案（2026-09-19）：主站 hanime1.me 的 Cloudflare WAF 拦机房出口与非浏览器指纹
 *  （代理 / 直连钉 CF IP + 全浏览器头均 403 "Sorry, you have been blocked"），备用域 hanime1.com
 *  无此规则（经代理实测 200）——故以备用域为主、主站兜底；线路在 直连 / av.bangumi 代理 间容灾
 *  （对齐 ResourceService.fetchViaFailover：IOException 行级切线 + 粘性线路记忆）。
 *  403 三分类判据对齐 Han1meViewer NetworkRepo："you have been blocked"=IP 封禁 /
 *  "Just a moment"=JS 质询（程序化无解，Cookie 注入是唯一后门）/ 其他=资源不存在。
 *  站点对无数据请求习惯返回 500 而非 404（同源码注释「v 数很大时报 500」）——按「无数据」归一给前端。
 *  watch 解析结果短 TTL 内存缓存（签名直链约 9 天有效，5 分钟缓存覆盖一次观影会话且避免反复打站点）。 */
@Service
public class HanimeService {

    private static final Logger log = LoggerFactory.getLogger(HanimeService.class);

    static final String CONFIG_KEY = "hanime";
    private static final String HOST_PRIMARY = "https://hanime1.com";
    private static final String HOST_FALLBACK = "https://hanime1.me";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
    private static final long WATCH_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int WATCH_CACHE_MAX = 30;

    /** 默认浏览器 UA（Chrome 126 桌面，与抓包夹具一致；站点 WAF 对自报家门的机器人 UA 直接 403） */
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final String CHALLENGE_MSG =
            "站点触发 Cloudflare 人机质询——请用浏览器打开 hanime1.com 通过验证后，把 Cookie 粘贴到设置页「在线解析」";
    private static final String BLOCKED_MSG =
            "站点 WAF 拒绝了服务端出口——请为服务端配置住宅出口代理；若浏览器可正常访问，粘贴浏览器 Cookie 后重试";

    /** KV 配置（settings 表 key=hanime）：cookie 为浏览器复制的原始 Cookie 串（含 cf_clearance / 登录态） */
    public record Config(boolean enabled, String ua, String cookie) {
        static Config defaults() {
            return new Config(false, DEFAULT_UA, "");
        }
    }

    private final DownloadRepository repo;
    private final HttpClient client;
    private final ServiceProperties props;
    private final ObjectMapper mapper;
    private volatile HttpClient proxyClient;
    private volatile boolean lastUsedProxy = false;
    private volatile String lastGoodHost = HOST_PRIMARY;
    private final Map<String, CachedWatch> watchCache = new LinkedHashMap<>();

    private record CachedWatch(HanimeWatchDto detail, long at) {}

    public HanimeService(DownloadRepository repo, HttpClient client, ServiceProperties props, ObjectMapper mapper) {
        this.repo = repo;
        this.client = client;
        this.props = props;
        this.mapper = mapper;
    }

    /* ── 配置（settings KV，对齐 resourceSites 惯例）── */

    public Config config() {
        try {
            String raw = repo.getSetting(CONFIG_KEY).orElse(null);
            if (raw == null || raw.isBlank()) return Config.defaults();
            JsonNode n = mapper.readTree(raw);
            String ua = n.path("ua").asText("").trim();
            return new Config(n.path("enabled").asBoolean(false),
                    ua.isEmpty() ? DEFAULT_UA : ua,
                    n.path("cookie").asText(""));
        } catch (Exception e) {
            log.warn("hanime 配置读取失败（{}），按默认处理", e.toString());
            return Config.defaults();
        }
    }

    public Config saveConfig(Boolean enabled, String ua, String cookie) {
        Config cur = config();
        boolean en = enabled != null ? enabled : cur.enabled();
        String nextUa = ua != null ? ua.trim() : cur.ua();
        String nextCookie = cookie != null ? cookie.trim() : cur.cookie();
        if (nextUa.isEmpty()) nextUa = DEFAULT_UA;
        try {
            repo.putSetting(CONFIG_KEY, mapper.writeValueAsString(
                    Map.of("enabled", en, "ua", nextUa, "cookie", nextCookie)));
        } catch (Exception e) {
            throw new DownloadException(500, "配置写入失败：" + e.getMessage());
        }
        return new Config(en, nextUa, nextCookie);
    }

    public HanimeConfigDto configDto() {
        Config c = config();
        return new HanimeConfigDto(c.enabled(), c.ua(), !c.cookie().isBlank());
    }

    /* ── 抓取（host × 线路四组合容灾）── */

    /** 200 返回；403 视为「该宿主被 WAF 拒」换下一宿主（IOException 才在同宿主内切线）；
     *  成功组合粘性记忆。两宿主全败时抛三分类 DownloadException。 */
    private String fetch(String pathAndQuery) throws Exception {
        String[] hosts = HOST_PRIMARY.equals(lastGoodHost)
                ? new String[]{HOST_PRIMARY, HOST_FALLBACK}
                : new String[]{HOST_FALLBACK, HOST_PRIMARY};
        boolean proxyOnly = "proxy".equalsIgnoreCase(props.bangumi().proxyMode());
        boolean directOnly = "direct".equalsIgnoreCase(props.bangumi().proxyMode());
        Exception lastError = null;
        Config cfg = config();
        for (String host : hosts) {
            boolean tryProxy = lastUsedProxy && !directOnly;
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean useProxy = proxyOnly || (tryProxy && !directOnly);
                if (useProxy && proxyClient() == null) {
                    tryProxy = false;
                    continue;
                }
                HttpClient hc = useProxy ? proxyClient() : client;
                try {
                    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(host + pathAndQuery))
                            .timeout(FETCH_TIMEOUT)
                            .header("User-Agent", cfg.ua())
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .header("Referer", host + "/");
                    if (!cfg.cookie().isBlank()) rb.header("Cookie", cfg.cookie());
                    HttpResponse<String> res = hc.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 200) {
                        lastGoodHost = host;
                        lastUsedProxy = useProxy;
                        return res.body();
                    }
                    if (res.statusCode() == 403) {
                        String body = res.body() == null ? "" : res.body();
                        if (body.contains("Just a moment")) throw new DownloadException(502, CHALLENGE_MSG);
                        if (body.contains("you have been blocked") || body.contains("Attention Required")) {
                            lastError = new DownloadException(502, BLOCKED_MSG);
                        } else {
                            lastError = new DownloadException(502, "HTTP 403");
                        }
                        log.info("hanime {} 被拒（403），尝试下一宿主", host);
                        break; // 403 是「宿主+出口」组合被 WAF 拒：换宿主，不在同宿主内换线
                    }
                    if (res.statusCode() == 500) {
                        // 站点对无数据请求习惯炸 500（非 404）——归一为无数据
                        throw new DownloadException(404, "站点无此数据（视频可能已下架或参数无效）");
                    }
                    throw new DownloadException(502, "HTTP " + res.statusCode());
                } catch (java.io.IOException e) {
                    // 行级失败一律切线：DNS 污染超时/拒绝、TLS 掐断、重置（对齐 fetchViaFailover 口径）
                    lastError = e;
                    log.info("hanime{}抓取失败（{}），切换为{}重试",
                            useProxy ? "经代理" : "直连", e.toString(), useProxy ? "直连" : "经代理");
                    tryProxy = !useProxy;
                }
            }
        }
        if (lastError instanceof DownloadException de) throw de;
        throw new DownloadException(502, "抓取失败：" + (lastError != null ? lastError.getMessage() : "未知原因"));
    }

    private HttpClient proxyClient() {
        if (proxyClient != null) return proxyClient;
        String host = props.bangumi().proxyHost();
        Integer port = props.bangumi().proxyPort();
        if (host == null || host.isBlank() || port == null) return null;
        synchronized (this) {
            if (proxyClient == null) {
                proxyClient = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .proxy(ProxySelector.of(new InetSocketAddress(host, port)))
                        .build();
            }
            return proxyClient;
        }
    }

    private void requireEnabled() {
        if (!config().enabled()) {
            throw new DownloadException(400, "在线解析未启用——请到设置页「在线解析」开启");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /* ── 业务端点 ── */

    /** 搜索（空 query + sort=最新上市 即「最新」浏览）；genre/sort 透传站点原生参数 */
    public HanimeSearchResult search(String query, String genre, String sort, Integer page) {
        requireEnabled();
        StringBuilder sb = new StringBuilder("/search?query=").append(enc(query == null ? "" : query.trim()));
        if (genre != null && !genre.isBlank()) sb.append("&genre=").append(enc(genre.trim()));
        if (sort != null && !sort.isBlank()) sb.append("&sort=").append(enc(sort.trim()));
        int p = page == null ? 1 : Math.max(1, page);
        if (p > 1) sb.append("&page=").append(p);
        try {
            return HanimeParser.parseSearch(fetch(sb.toString()), p);
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(502, "解析失败：" + e.getMessage());
        }
    }

    public HanimeWatchDto watch(String videoCode) {
        requireEnabled();
        validateCode(videoCode);
        synchronized (watchCache) {
            CachedWatch c = watchCache.get(videoCode);
            if (c != null && System.currentTimeMillis() - c.at() < WATCH_CACHE_TTL_MS) return c.detail();
        }
        HanimeWatchDto detail;
        try {
            detail = HanimeParser.parseWatch(fetch("/watch?v=" + videoCode), videoCode);
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(502, "解析失败：" + e.getMessage());
        }
        synchronized (watchCache) {
            watchCache.put(videoCode, new CachedWatch(detail, System.currentTimeMillis()));
            if (watchCache.size() > WATCH_CACHE_MAX) {
                watchCache.remove(watchCache.keySet().iterator().next());
            }
        }
        return detail;
    }

    /** 播放转发目标直链：指定分辨率（缺省最高档，解析时已降序）；签名时效内无需 Referer */
    public String resolveStreamUrl(String videoCode, Integer res) {
        HanimeWatchDto d = watch(videoCode);
        if (d.sources().isEmpty()) {
            throw new DownloadException(404, "未解析到可播放的视频源（站点结构可能变化）");
        }
        if (res != null) {
            for (var s : d.sources()) {
                if (s.res() == res) return s.url();
            }
        }
        return d.sources().get(0).url();
    }

    /** 连通测试（不要求已启用，供开启前验证）：抓一次搜索首页并解析计数 */
    public HanimeTestDto test() {
        try {
            HanimeSearchResult r = HanimeParser.parseSearch(fetch("/search?query=&sort=" + enc("最新上市")), 1);
            String msg = r.items().isEmpty()
                    ? "可达，但未解析出条目（站点结构可能变化，需更新解析器）"
                    : "可达：解析出 " + r.items().size() + " 条";
            return new HanimeTestDto(!r.items().isEmpty(), msg, r.items().size());
        } catch (DownloadException e) {
            return new HanimeTestDto(false, e.getMessage(), 0);
        } catch (Exception e) {
            return new HanimeTestDto(false, "抓取失败：" + e.getMessage(), 0);
        }
    }

    private static void validateCode(String videoCode) {
        if (videoCode == null || !videoCode.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new DownloadException(400, "videoCode 不合法");
        }
    }

    /* ── v0.26 补记：缩略图/海报经服务端容灾转发（用户实测反馈：弹窗图片加载不出来）──
       原因：缩略图 <img> 由浏览器直连 vdownload.hembed.com，该 CDN 与视频直链同因——本网络
       DNS 污染直连超时；视频正常是因为走了服务端流转发，图片缺同等通道。 */

    /** 图片宿主白名单（站点自家 CDN 与主备域，子域放行）——防开放代理/SSRF */
    private static final List<String> IMAGE_ALLOWED_HOSTS = List.of("hembed.com", "hanime1.com", "hanime1.me");

    /** 校验图片 URL（http(s) + 宿主白名单），通过则原样返回 */
    public String validateImageUrl(String url) {
        if (url == null || url.isBlank()) throw new DownloadException(400, "图片地址为空");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new DownloadException(400, "图片地址不合法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new DownloadException(400, "仅支持 http/https 图片地址");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean allowed = IMAGE_ALLOWED_HOSTS.stream()
                .anyMatch(a -> host.equals(a) || host.endsWith("." + a));
        if (!allowed) throw new DownloadException(403, "图片域名不在允许范围（站点 CDN 白名单）");
        return url.trim();
    }
}
