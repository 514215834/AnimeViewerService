package com.animeviewer.service;

import com.animeviewer.service.model.Dtos.ResourceItemDto;
import com.animeviewer.service.resource.RssResourceParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.17 R1 RSS 资源解析用例：夹具为 2026-09-13 curl 实测 share.acgnx.se/rss.xml?keyword=Precure 响应裁剪
 *  （标准 RSS 2.0 + enclosure 直含磁力 + description 管道段「链接 | 大小 | 分类 | hash」）。 */
class RssResourceParserTest {

    private static final String SAMPLE = """
            <?xml version="1.0" encoding="utf-8" ?><rss version="2.0">
            <channel>
            <title><![CDATA[關鍵字 Precure 的檢索結果  - 末日動漫資源庫 - Project AcgnX Torrent Asia]]></title>
            <link>https://share.acgnx.se/search.php?keyword=Precure</link>
            <lastBuildDate>Sun, 13 Sep 2026 19:11:47 +0800</lastBuildDate>
            <language>zh-tw</language>
            <item>
                <title><![CDATA[名侦探光之美少女！ - EP33 [简／繁] (1080p H.264 AAC SRTx2) {名偵探光之美少女！ | Meitantei Precure!}]]></title>
                <link>https://share.acgnx.se/show-d5f29133326a46a46bf80164089b731538dd943b.html</link>
                <description><![CDATA[<a href="https://share.acgnx.se/show-d5f29133326a46a46bf80164089b731538dd943b.html">萌番組鏡像 | 名侦探光之美少女！ - EP33</a> | 1.4GB | 動畫 | d5f29133326a46a46bf80164089b731538dd943b]]></description>
                <guid isPermaLink="true">https://share.acgnx.se/show-d5f29133326a46a46bf80164089b731538dd943b.html</guid>
                <author><![CDATA[萌番組鏡像]]></author>
                <enclosure url="magnet:?xt=urn:btih:d5f29133326a46a46bf80164089b731538dd943b&amp;tr=http%3A%2F%2Fopentracker.acgnx.se%2Fannounce" length="1" type="application/x-bittorrent" />
                <pubDate>Sun, 13 Sep 2026 15:37:11 +0800</pubDate>
                <category domain="https://share.acgnx.se/sort-1-1.html"><![CDATA[動畫]]></category>
            </item>
            <item>
                <title><![CDATA[[雪飘工作室][名探偵プリキュア！/Star Detective Precure！/名侦探光之美少女！][1080p][33][简繁日外挂](检索:Q娃)]]></title>
                <link>https://share.acgnx.se/show-d67a2caac0c1a41f8e3d76b60432dbde7aa33dde.html</link>
                <description><![CDATA[<a href="https://share.acgnx.se/show-d67a2caac0c1a41f8e3d76b60432dbde7aa33dde.html">動漫花園鏡像 | [雪飘工作室] 33</a> | 593.7MB | 動畫 | d67a2caac0c1a41f8e3d76b60432dbde7aa33dde]]></description>
                <author><![CDATA[動漫花園鏡像]]></author>
                <enclosure url="magnet:?xt=urn:btih:d67a2caac0c1a41f8e3d76b60432dbde7aa33dde&amp;tr=http%3A%2F%2Fopentracker.acgnx.se%2Fannounce" length="1" type="application/x-bittorrent" />
                <pubDate>Sun, 13 Sep 2026 12:13:00 +0800</pubDate>
                <category><![CDATA[動畫]]></category>
            </item>
            <item>
                <title><![CDATA[无磁力的普通条目应被跳过]]></title>
                <link>https://share.acgnx.se/show-x.html</link>
                <description><![CDATA[普通公告]]></description>
            </item>
            </channel>
            </rss>
            """;

    @Test
    void parsesRealAcgnxFixture() {
        List<ResourceItemDto> items = RssResourceParser.parse(SAMPLE, "acgnx");
        assertEquals(2, items.size()); // 无磁力条目被跳过

        ResourceItemDto first = items.get(0);
        assertEquals("名侦探光之美少女！ - EP33 [简／繁] (1080p H.264 AAC SRTx2) {名偵探光之美少女！ | Meitantei Precure!}", first.title());
        assertEquals("acgnx", first.site());
        assertEquals("magnet:?xt=urn:btih:d5f29133326a46a46bf80164089b731538dd943b&tr=http%3A%2F%2Fopentracker.acgnx.se%2Fannounce",
                first.magnet());
        assertEquals("d5f29133326a46a46bf80164089b731538dd943b", first.infoHash());
        assertEquals("1.4GB", first.size());
        assertEquals("動畫", first.category());
        assertEquals("萌番組鏡像", first.publisher());
        assertEquals("https://share.acgnx.se/show-d5f29133326a46a46bf80164089b731538dd943b.html", first.link());
        assertNotNull(first.pubDate());

        ResourceItemDto second = items.get(1);
        assertEquals("[雪飘工作室][名探偵プリキュア！/Star Detective Precure！/名侦探光之美少女！][1080p][33][简繁日外挂](检索:Q娃)",
                second.title());
        assertEquals("593.7MB", second.size());
        assertEquals("d67a2caac0c1a41f8e3d76b60432dbde7aa33dde", second.infoHash());
    }

    @Test
    void pubDateParsedToEpochMillis() {
        List<ResourceItemDto> items = RssResourceParser.parse(SAMPLE, "acgnx");
        // Sun, 13 Sep 2026 15:37:11 +0800 = 2026-09-13T07:37:11Z
        long expected = java.time.LocalDateTime.of(2026, 9, 13, 7, 37, 11)
                .toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
        assertEquals(expected, items.get(0).pubDate());
    }

    @Test
    void namespaceAgnosticAndMalformedRejected() {
        // 无前缀 RSS（dmhy 本尊形态兜底）同样可解析
        String plain = SAMPLE.replace("<item>", "<item xmlns=\"\">");
        assertTrue(RssResourceParser.parse(plain, "x").size() >= 1);
        // 非 RSS 页面（403 反爬 HTML 等）：无 item → 空列表（站点级拦截由 looksLikeRss 把关）
        assertTrue(RssResourceParser.parse("<html>403 反爬页</html>", "x").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RssResourceParser.parse(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> RssResourceParser.parse("<rss><channel><item><title>&broken;", "x"));
    }

    @Test
    void xxeAttemptRejected() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss version="2.0"><channel><item><title>&xxe;</title>
                <enclosure url="magnet:?xt=urn:btih:%s" type="application/x-bittorrent"/></item></channel></rss>
                """.formatted("a".repeat(40));
        assertThrows(Exception.class, () -> RssResourceParser.parse(xxe, "x"));
    }

    @Test
    void infoHashFallbacksAndSizeUnitNormalization() {
        // base32 infohash 保持大写
        String b32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        assertEquals(b32, RssResourceParser.infoHashOf("magnet:?xt=urn:btih:" + b32, null));
        // description 40hex 兜底
        assertEquals("abcd1234abcd1234abcd1234abcd1234abcd1234",
                RssResourceParser.infoHashOf(null, "xx | abcd1234abcd1234abcd1234abcd1234abcd1234"));
        assertNull(RssResourceParser.infoHashOf("magnet:?dn=x", null));
        // 大小单位归一（TiB → TB）
        assertEquals("2.5TB", RssResourceParser.sizeOf("xx | 2.5TiB | 動畫"));
        assertNull(RssResourceParser.sizeOf("no size here"));
    }

    @Test
    void looksLikeRssGate() {
        assertTrue(RssResourceParser.looksLikeRss(SAMPLE));
        // 搜索无结果的合法空 RSS（无 <item>）也视为 RSS——误判会错报「反爬」（2026-09-13 实测）
        assertTrue(RssResourceParser.looksLikeRss("""
                <?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel>
                <title><![CDATA[關鍵字 X 的檢索結果]]></title></channel></rss>
                """));
        assertTrue(!RssResourceParser.looksLikeRss("<html>blocked</html>"));
    }
}
