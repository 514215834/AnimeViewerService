package com.animeviewer.service.resource;

import com.animeviewer.service.model.Dtos.ResourceItemDto;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** v0.17 R1 同构 RSS 站点（acgnx / dmhy）资源解析（纯函数，供单测；WebdavParser 同模式）。
 *  实测 RSS 结构（2026-09-13 curl 采夹具）：标准 RSS 2.0，
 *  <item> 含 title / link（详情页）/ description（「链接 | 大小 | 分类 | infohash」管道段）/
 *  author（发布者——镜像站为「XX镜像」、本尊为发布组名）/ enclosure（url 直含完整磁力）/
 *  pubDate / category。禁 DTD/外部实体防 XXE；解析失败抛 IllegalArgumentException。
 *
 *  v0.24 SE1 种子型站点扩展（nyaa / 蜜柑计划夹具 2026-09-19 实测）：
 *  <ul>
 *    <li>nyaa：无 enclosure，<link> 即 .torrent 下载直链，nyaa:infoHash 40hex 直接构造磁力，nyaa:size（MiB）兜底；</li>
 *    <li>蜜柑：enclosure 为 .torrent 直链 + length 真实字节（contentLength 同值），无任何 infoHash——
 *        磁力只能在入队时抓种子计算 BTIH（BencodeParser），本解析器以 torrentUrl 承载；</li>
 *    <li>磁力优先、torrentUrl 兜底：两者全无的条目仍跳过（对本产品无意义）；</li>
 *    <li>元素查找统一 getElementsByTagNameNS("*", localName)——前缀无关注入（nyaa:/torrent: 命名空间均可命中），
 *        文档序首个命中优先，acgnx 无前缀行为不变。</li>
 *  </ul> */
public final class RssResourceParser {

    private RssResourceParser() {}

    private static final Pattern SIZE_SEG = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(TB|GB|MB|KB|TiB|GiB|MiB|KiB)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX40 = Pattern.compile("(?i)\\b[0-9a-f]{40}\\b");

    /** 磁力 enclosure 的 type 标记 */
    private static final String TORRENT_TYPE = "application/x-bittorrent";

    public static List<ResourceItemDto> parse(String xml, String siteKey) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            var items = doc.getDocumentElement().getElementsByTagName("item");
            List<ResourceItemDto> out = new ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String title = text(item, "title");
                String magnet = enclosureMagnet(item);
                String torrentUrl = torrentUrlOf(item);
                if (title == null || title.isBlank() || (magnet == null && torrentUrl == null)) continue; // 无磁力也无种子的条目对本产品无意义
                String desc = text(item, "description");
                String hash = infoHashOf(magnet, desc);
                if (hash == null) hash = namespacedHash(item);
                // nyaa 形态：仅 namespaced hash 无磁力——由 hash 构造磁力（tracker 由入队 mergeTrackers 注入）
                if (hash != null && magnet == null) magnet = "magnet:?xt=urn:btih:" + hash;
                String size = sizeOf(desc);
                if (size == null) size = sizeFromEnclosure(item);
                out.add(new ResourceItemDto(
                        title.trim(),
                        magnet,
                        hash,
                        siteKey,
                        size,
                        text(item, "category"),
                        text(item, "author"),
                        dateOf(text(item, "pubDate")),
                        text(item, "link"),
                        torrentUrl));
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("RSS 解析失败：" + e.getMessage(), e);
        }
    }

    /** 站点级可用性检查：RSS 根元素存在即视为 RSS（403 反爬页等非 XML 在 parse 处已抛出）。
     *  不要求 <item>——搜索无结果时站方返回合法空 RSS（实测 2026-09-13），误判会错报「反爬」。 */
    public static boolean looksLikeRss(String xml) {
        return xml != null && xml.contains("<rss");
    }

    private static String enclosureMagnet(Element item) {
        var nodes = item.getElementsByTagNameNS("*", "enclosure");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element enc = (Element) nodes.item(i);
            String type = enc.getAttribute("type");
            if (type != null && !type.isBlank() && !type.toLowerCase(Locale.ROOT).contains("torrent")) continue;
            String url = enc.getAttribute("url");
            if (url != null && url.toLowerCase(Locale.ROOT).startsWith("magnet:?")) return url.trim();
        }
        // 无 enclosure 属性兜底：link/description 中出现磁力链时直接取
        for (String probe : new String[]{text(item, "link"), text(item, "description")}) {
            if (probe == null) continue;
            int at = probe.toLowerCase(Locale.ROOT).indexOf("magnet:?xt=urn:btih:");
            if (at >= 0) {
                int end = probe.indexOf('&', at);
                String cand = end > at ? probe.substring(at, end) : probe.substring(at);
                if (cand.length() >= "magnet:?xt=urn:btih:".length() + 40) return cand.trim();
            }
        }
        return null;
    }

    /** v0.24 SE1：.torrent 下载直链提取——enclosure（torrent type 或 .torrent 路径，非磁力）优先，
     *  <link> 路径以 .torrent 结尾兜底（nyaa 形态：<link> 即种子直链）。 */
    private static String torrentUrlOf(Element item) {
        var encs = item.getElementsByTagNameNS("*", "enclosure");
        for (int i = 0; i < encs.getLength(); i++) {
            Element enc = (Element) encs.item(i);
            String url = enc.getAttribute("url");
            if (url == null || url.isBlank()) continue;
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) continue; // 磁力 enclosure 由 enclosureMagnet 消费
            String type = enc.getAttribute("type");
            boolean torrentType = type == null || type.isBlank() || type.toLowerCase(Locale.ROOT).contains("torrent");
            if (torrentType || looksLikeTorrentPath(url)) return url.trim();
        }
        String link = text(item, "link");
        if (link != null && looksLikeTorrentPath(link)) return link.trim();
        return null;
    }

    /** 路径段以 .torrent 结尾（query/hash 之前，大小写不敏感；语义对齐 DownloadService.isTorrentLink） */
    public static boolean looksLikeTorrentPath(String url) {
        if (url == null) return false;
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        return path.toLowerCase(Locale.ROOT).endsWith(".torrent");
    }

    /** v0.24 SE1：任意命名空间下名为 infoHash 的元素（如 nyaa:infoHash）提取 40hex（小写归一） */
    private static String namespacedHash(Element item) {
        var nodes = item.getElementsByTagNameNS("*", "infoHash");
        if (nodes.getLength() == 0) return null;
        String t = textValue(nodes.item(0));
        if (t == null) return null;
        String h = t.trim().toLowerCase(Locale.ROOT);
        return h.length() == 40 && h.chars().allMatch(c -> Character.isDigit(c) || (c >= 'a' && c <= 'f')) ? h : null;
    }

    /** infohash：优先磁力 xt 段（40 hex / 32 base32），否则从 description 中找 40 hex（acgnx 管道段） */
    public static String infoHashOf(String magnet, String description) {
        if (magnet != null) {
            String lower = magnet.toLowerCase(Locale.ROOT);
            int at = lower.indexOf("xt=urn:btih:");
            if (at >= 0) {
                String h = magnet.substring(at + "xt=urn:btih:".length());
                int amp = h.indexOf('&');
                if (amp >= 0) h = h.substring(0, amp);
                h = h.trim();
                if (h.length() == 40 && h.chars().allMatch(c -> Character.isDigit(c) || (c >= 'a' && c <= 'f'))) {
                    return h;
                }
                if (h.length() == 32 && h.chars().allMatch(Character::isLetterOrDigit)) {
                    return h.toUpperCase(Locale.ROOT);
                }
            }
        }
        if (description != null) {
            Matcher m = HEX40.matcher(description);
            if (m.find()) return m.group().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** description 管道段中的体积（acgnx：…</a> | 1.4GB | 動畫 | <hash>）；单位归一为 TB/GB/MB/KB；找不到返回 null */
    public static String sizeOf(String description) {
        if (description == null) return null;
        Matcher m = SIZE_SEG.matcher(description);
        if (!m.find()) return null;
        String unit = m.group(2).toUpperCase(Locale.ROOT);
        if (unit.startsWith("T")) unit = "TB";
        else if (unit.startsWith("G")) unit = "GB";
        else if (unit.startsWith("M")) unit = "MB";
        else unit = "KB";
        return m.group(1) + unit;
    }

    /** v0.24 SE1 size 兜底：description 管道段无值时取 enclosure length 属性 / namespaced contentLength
     *  （蜜柑形态，真实字节）→ bytesOfSize 归一；≤1KB 视为占位垃圾值（acgnx enclosure length=1）不采用 */
    private static String sizeFromEnclosure(Element item) {
        long bytes = -1;
        var encs = item.getElementsByTagNameNS("*", "enclosure");
        for (int i = 0; i < encs.getLength(); i++) {
            Long v = parseLong(((Element) encs.item(i)).getAttribute("length"));
            if (v != null && v >= 1024) {
                bytes = v;
                break;
            }
        }
        if (bytes < 0) {
            var cls = item.getElementsByTagNameNS("*", "contentLength");
            if (cls.getLength() > 0) {
                Long v = parseLong(textValue(cls.item(0)));
                if (v != null) bytes = v;
            }
        }
        return bytes >= 1024 ? bytesOfSize(bytes) : null;
    }

    /** 字节 → TB/GB/MB/KB（二进制 1024 进制，一位小数去尾零；<1KB 返回 null） */
    public static String bytesOfSize(long n) {
        if (n < 1024) return null;
        double tb = 1L << 40, gb = 1L << 30, mb = 1L << 20, kb = 1L << 10;
        return n >= tb ? dec(n / tb) + "TB"
                : n >= gb ? dec(n / gb) + "GB"
                : n >= mb ? dec(n / mb) + "MB"
                : dec(n / kb) + "KB";
    }

    private static String dec(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 前缀无关取文本：getElementsByTagNameNS("*", localName) 文档序首个命中（无前缀与带前缀均命中） */
    private static String text(Element parent, String tag) {
        var nodes = parent.getElementsByTagNameNS("*", tag);
        if (nodes.getLength() == 0) return null;
        return textValue(nodes.item(0));
    }

    private static String textValue(org.w3c.dom.Node node) {
        StringBuilder sb = new StringBuilder();
        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    || children.item(i).getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                sb.append(children.item(i).getNodeValue());
            }
        }
        String t = sb.toString().trim();
        return t.isEmpty() ? null : t;
    }

    /** RFC 822 pubDate（如 Sun, 13 Sep 2026 15:37:11 +0800）宽松解析；
     *  v0.24 ISO 8601 兜底（蜜柑 torrent:pubDate 形态 2026-09-18T23:31:20.561047——无时区按北京时间解析，排序口径一致即可）；
     *  解析失败返回 null */
    static Long dateOf(String text) {
        if (text == null) return null;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
            Date d = fmt.parse(text.trim());
            if (d != null) return d.getTime();
        } catch (Exception ignore) {
            // 降级 ISO 8601
        }
        try {
            return OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli();
        } catch (Exception ignore) {
            // 带毫秒/纳秒但无时区的蜜柑形态
        }
        try {
            return LocalDateTime.parse(text.trim())
                    .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }
}
