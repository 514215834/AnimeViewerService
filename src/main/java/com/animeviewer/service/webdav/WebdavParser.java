package com.animeviewer.service.webdav;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** v0.15 O3 WebDAV PROPFIND multistatus 解析（纯函数，供单测）。
 *  命名空间前缀不敏感（getElementsByTagNameNS("*", ...)）；禁用外部实体防 XXE；
 *  解析失败抛 IllegalArgumentException（调用方转 502）。 */
public final class WebdavParser {

    private WebdavParser() {}

    /** href 为原始值；name 为 href 末段百分号解码；dir=是否目录；size/mtime 可为 null */
    public record Entry(String href, String name, boolean dir, Long size, Long mtime) {}

    public static List<Entry> parse(String xml) {
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

            NodeList responses = doc.getElementsByTagNameNS("*", "response");
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < responses.getLength(); i++) {
                Element resp = (Element) responses.item(i);
                String href = firstText(resp, "href");
                if (href == null || href.isBlank()) continue;
                boolean dir = hasCollection(resp);
                Long size = longOf(firstText(resp, "getcontentlength"));
                Long mtime = dateOf(firstText(resp, "getlastmodified"));
                out.add(new Entry(href, nameOf(href), dir, size, mtime));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("PROPFIND 响应解析失败：" + e.getMessage(), e);
        }
    }

    /** href 末段为显示名（百分号解码，去尾斜杠后取段） */
    static String nameOf(String href) {
        String path = href;
        try {
            path = URI.create(href.trim()).getPath();
        } catch (IllegalArgumentException ignored) {
            // 非 URI 形态按原文处理
        }
        String s = path == null ? href : path;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        String last = slash >= 0 ? s.substring(slash + 1) : s;
        try {
            return java.net.URLDecoder.decode(last, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return last;
        }
    }

    private static boolean hasCollection(Element resp) {
        NodeList types = resp.getElementsByTagNameNS("*", "resourcetype");
        for (int i = 0; i < types.getLength(); i++) {
            NodeList children = types.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node n = children.item(j);
                if (n.getNodeType() == Node.ELEMENT_NODE && "collection".equals(n.getLocalName())) return true;
            }
        }
        return false;
    }

    private static String firstText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) return null;
        Node n = nodes.item(0);
        StringBuilder sb = new StringBuilder();
        NodeList children = n.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.TEXT_NODE) sb.append(children.item(i).getNodeValue());
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Long longOf(String text) {
        if (text == null) return null;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** RFC 1123（如 Tue, 03 Nov 2026 09:00:00 GMT）宽松解析，失败返回 null */
    private static Long dateOf(String text) {
        if (text == null) return null;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            Date d = fmt.parse(text.trim());
            return d == null ? null : d.getTime();
        } catch (Exception e) {
            return null;
        }
    }
}
