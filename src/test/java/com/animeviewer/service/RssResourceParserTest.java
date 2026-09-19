package com.animeviewer.service;

import com.animeviewer.service.model.Dtos.ResourceItemDto;
import com.animeviewer.service.resource.RssResourceParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /* ── v0.24 SE1 种子型站点（nyaa / 蜜柑，2026-09-19 实测夹具）── */

    /** nyaa：无 enclosure——<link> 即 .torrent 下载直链；nyaa:infoHash 40hex 直接构造磁力；
     *  nyaa:size（MiB）与 description 管道段均可供 size 提取。 */
    private static final String NYAA_SAMPLE = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss xmlns:atom="http://www.w3.org/2005/Atom" xmlns:nyaa="https://nyaa.si/xmlns/nyaa" version="2.0">
            <channel>
            <title>Nyaa - "spy family" - Torrent File RSS</title>
            <link>https://nyaa.si/</link>
            <item>
                <title>[Naruto-Kun.Hu] Spy X Family 3 - 01 [1080p].mkv</title>
                <link>https://nyaa.si/download/2160516.torrent</link>
                <guid isPermaLink="true">https://nyaa.si/view/2160516</guid>
                <pubDate>Sun, 13 Sep 2026 03:47:52 -0000</pubDate>
                <nyaa:infoHash>e4bdce64a33505e00ddde4b5cb8d8478a2e658a6</nyaa:infoHash>
                <nyaa:categoryId>1_3</nyaa:categoryId>
                <nyaa:category>Anime - Non-English-translated</nyaa:category>
                <nyaa:size>442.1 MiB</nyaa:size>
                <description><![CDATA[<a href="https://nyaa.si/view/2160516">#2160516 | [Naruto-Kun.Hu] Spy X Family 3 - 01 [1080p].mkv</a> | 442.1 MiB | Anime - Non-English-translated | e4bdce64a33505e00ddde4b5cb8d8478a2e658a6]]></description>
            </item>
            <item>
                <title>Spy.x.Family.S02.MULTi.1080p.BluRay.x265-KAF</title>
                <link>https://nyaa.si/download/2154065.torrent</link>
                <guid isPermaLink="true">https://nyaa.si/view/2154065</guid>
                <pubDate>Sun, 30 Aug 2026 17:34:59 -0000</pubDate>
                <nyaa:infoHash>e85482370f04123e06edf616a45f816d85edb4de</nyaa:infoHash>
                <nyaa:category>Anime - Non-English-translated</nyaa:category>
                <nyaa:size>5.0 GiB</nyaa:size>
                <description><![CDATA[<a href="https://nyaa.si/view/2154065">#2154065 | Spy.x.Family.S02.MULTi.1080p.BluRay.x265-KAF</a> | 5.0 GiB | Anime - Non-English-translated | e85482370f04123e06edf616a45f816d85edb4de]]></description>
            </item>
            <item>
                <title>无磁力也无种子的公告条目应被跳过</title>
                <link>https://nyaa.si/view/1</link>
                <description>公告</description>
            </item>
            </channel>
            </rss>
            """;

    /** 蜜柑计划：enclosure 为 .torrent 直链 + length 真实字节；torrent:contentLength / pubDate（ISO8601 无时区）；
     *  无任何 infoHash——磁力只能入队时 BencodeParser 计算 BTIH，本解析器以 torrentUrl 承载。 */
    private static final String MIKAN_SAMPLE = """
            <?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel>
            <title>Mikan Project - 番组</title>
            <link>http://mikanani.me/RSS/Bangumi?bangumiId=3993</link>
            <item>
              <title>[黒ネズミたち] 画完这个再去死 / Kore Kaite Shine - 11 (CR 1920x1080 AVC AAC MKV)</title>
              <link>https://mikanani.me/Home/Episode/fee6ecd3354bc743101c30acedec740d6fe18890</link>
              <description>[黒ネズミたち] 画完这个再去死 / Kore Kaite Shine - 11 (CR 1920x1080 AVC AAC MKV)[885.8 MB]</description>
              <torrent xmlns="https://mikanani.me/0.1/"><link>https://mikanani.me/Home/Episode/fee6ecd3354bc743101c30acedec740d6fe18890</link><contentLength>928828608</contentLength><pubDate>2026-09-18T23:31:20.561047</pubDate></torrent>
              <enclosure type="application/x-bittorrent" length="928828608" url="https://mikanani.me/Download/20260918/fee6ecd3354bc743101c30acedec740d6fe18890.torrent" />
            </item>
            <item>
              <title>[ANi] Kore Kaite Shine /  画完这个再去死 - 11 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]</title>
              <link>https://mikanani.me/Home/Episode/c07a387471186d712c486eace0df9584478e142d</link>
              <description>[ANi] Kore Kaite Shine /  画完这个再去死 - 11 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]</description>
              <torrent xmlns="https://mikanani.me/0.1/"><link>https://mikanani.me/Home/Episode/c07a387471186d712c486eace0df9584478e142d</link><contentLength>447427392</contentLength><pubDate>2026-09-18T23:31:10.972529</pubDate></torrent>
              <enclosure type="application/x-bittorrent" length="447427392" url="https://mikanani.me/Download/20260918/c07a387471186d712c486eace0df9584478e142d.torrent" />
            </item>
            </channel></rss>
            """;

    @Test
    void parsesNyaaTorrentFixture() {
        List<ResourceItemDto> items = RssResourceParser.parse(NYAA_SAMPLE, "nyaa");
        assertEquals(2, items.size()); // 无磁力也无种子的公告条目跳过

        ResourceItemDto first = items.get(0);
        assertEquals("[Naruto-Kun.Hu] Spy X Family 3 - 01 [1080p].mkv", first.title());
        // 无磁力 enclosure——namespaced hash 直接构造磁力
        assertEquals("magnet:?xt=urn:btih:e4bdce64a33505e00ddde4b5cb8d8478a2e658a6", first.magnet());
        assertEquals("e4bdce64a33505e00ddde4b5cb8d8478a2e658a6", first.infoHash());
        // <link> 即 .torrent 直链（nyaa 形态）
        assertEquals("https://nyaa.si/download/2160516.torrent", first.torrentUrl());
        assertEquals("442.1MB", first.size()); // desc 管道段 MiB 归一
        assertEquals("Anime - Non-English-translated", first.category()); // nyaa:category 前缀无关命中
        assertNotNull(first.pubDate()); // RFC822 "-0000" 解析

        ResourceItemDto second = items.get(1);
        assertEquals("magnet:?xt=urn:btih:e85482370f04123e06edf616a45f816d85edb4de", second.magnet());
        assertEquals("5.0GB", second.size()); // 5.0 GiB 归一
    }

    @Test
    void parsesMikanTorrentOnlyFixture() {
        List<ResourceItemDto> items = RssResourceParser.parse(MIKAN_SAMPLE, "mikan");
        assertEquals(2, items.size());

        ResourceItemDto first = items.get(0);
        assertNull(first.magnet()); // 蜜柑无 infoHash，解析器不再产出磁力
        assertEquals("https://mikanani.me/Download/20260918/fee6ecd3354bc743101c30acedec740d6fe18890.torrent",
                first.torrentUrl());
        assertEquals("885.8MB", first.size()); // description 括注段正则优先
        // ISO8601 无时区 pubDate（torrent:pubDate）按北京时间解析
        assertEquals(java.time.OffsetDateTime.of(2026, 9, 18, 23, 31, 20, 561_047_000,
                        java.time.ZoneOffset.ofHours(8)).toInstant().toEpochMilli(),
                first.pubDate());

        ResourceItemDto second = items.get(1);
        assertEquals("https://mikanani.me/Download/20260918/c07a387471186d712c486eace0df9584478e142d.torrent",
                second.torrentUrl());
        // description 无大小注记——enclosure length 真实字节兜底（447427392 B = 426.7MB）
        assertEquals("426.7MB", second.size());
        assertNull(second.category());
    }

    @Test
    void torrentPathAndBytesSizeHelpers() {
        assertTrue(RssResourceParser.looksLikeTorrentPath("https://nyaa.si/download/2160516.torrent"));
        assertTrue(RssResourceParser.looksLikeTorrentPath("https://x.y/a.TORRENT?token=x"));
        assertFalse(RssResourceParser.looksLikeTorrentPath("https://mikanani.me/Home/Episode/fee6ecd3"));
        assertFalse(RssResourceParser.looksLikeTorrentPath(null));
        // 字节 → 单位归一（1024 进制一位小数）
        assertEquals("885.8MB", RssResourceParser.bytesOfSize(928828608L));
        assertEquals("426.7MB", RssResourceParser.bytesOfSize(447427392L));
        assertEquals("1GB", RssResourceParser.bytesOfSize(1073741824L));
        assertEquals("1KB", RssResourceParser.bytesOfSize(1024L));
        assertNull(RssResourceParser.bytesOfSize(1023L));
        assertNull(RssResourceParser.bytesOfSize(0L));
    }
}
