package com.animeviewer.service.resource;

import com.animeviewer.service.model.Dtos.ResourceItemDto;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.text.SimpleDateFormat;
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
 *  pubDate / category。禁 DTD/外部实体防 XXE；解析失败抛 IllegalArgumentException。 */
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
                if (title == null || title.isBlank() || magnet == null) continue; // 无磁力的条目对本产品无意义
                String desc = text(item, "description");
                String hash = infoHashOf(magnet, desc);
                out.add(new ResourceItemDto(
                        title.trim(),
                        magnet,
                        hash,
                        siteKey,
                        sizeOf(desc),
                        text(item, "category"),
                        text(item, "author"),
                        dateOf(text(item, "pubDate")),
                        text(item, "link")));
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
        var nodes = item.getElementsByTagName("enclosure");
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

    private static String text(Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        StringBuilder sb = new StringBuilder();
        var children = nodes.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    || children.item(i).getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                sb.append(children.item(i).getNodeValue());
            }
        }
        String t = sb.toString().trim();
        return t.isEmpty() ? null : t;
    }

    /** RFC 822 pubDate（如 Sun, 13 Sep 2026 15:37:11 +0800）宽松解析，失败返回 null */
    static Long dateOf(String text) {
        if (text == null) return null;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
            Date d = fmt.parse(text.trim());
            return d == null ? null : d.getTime();
        } catch (Exception e) {
            return null;
        }
    }
}
