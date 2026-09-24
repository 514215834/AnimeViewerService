package com.animeviewer.service.subscription;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** v0.30 A6 订阅地址规则映射纯函数（规则集以 §5R 探测定案 1 实抓形态为准，2026-09-24）。 */
class RssUrlExtractorTest {

    @Test
    void alreadyFeedUrlPassesThrough() {
        assertEquals("https://mikanani.me/RSS/Bangumi?bangumiId=3015",
                RssUrlExtractor.extract("https://mikanani.me/RSS/Bangumi?bangumiId=3015"));
        assertEquals("https://share.acgnx.se/rss.xml?keyword=frieren",
                RssUrlExtractor.extract("https://share.acgnx.se/rss.xml?keyword=frieren"));
        assertEquals("https://share.dmhy.org/topics/rss/rss.xml?keyword=x",
                RssUrlExtractor.extract("https://share.dmhy.org/topics/rss/rss.xml?keyword=x"));
    }

    @Test
    void mikanDetailPageMapsToPerShowRss() {
        // §5R 探测实抓：首页详情链接形态 /Home/Bangumi/{id} → /RSS/Bangumi?bangumiId={id}
        assertEquals("https://mikanani.me/RSS/Bangumi?bangumiId=3015",
                RssUrlExtractor.extract("https://mikanani.me/Home/Bangumi/3015"));
        assertEquals("http://mikanani.me/RSS/Bangumi?bangumiId=3015",
                RssUrlExtractor.extract("http://mikanani.me/Home/Bangumi/3015"));
        // 兼容旧详情页路径 /Home/Details/{id}
        assertEquals("https://mikanani.me/RSS/Bangumi?bangumiId=3015",
                RssUrlExtractor.extract("https://mikanani.me/Home/Details/3015"));
        // 带尾斜杠 / 尾句号（从句子里抓出的 URL）
        assertEquals("https://mikanani.me/RSS/Bangumi?bangumiId=3015",
                RssUrlExtractor.extract("https://mikanani.me/Home/Bangumi/3015/"));
        assertEquals("https://mikanani.me/RSS/Bangumi?bangumiId=3015",
                RssUrlExtractor.extract("详情页：https://mikanani.me/Home/Bangumi/3015。"));
        // 镜像域同样映射
        assertEquals("https://www.mikanani.me/RSS/Bangumi?bangumiId=3015",
                RssUrlExtractor.extract("https://www.mikanani.me/Home/Bangumi/3015"));
    }

    @Test
    void acgnxSearchPageMapsToKeywordRss() {
        assertEquals("https://share.acgnx.se/rss.xml?keyword=frieren",
                RssUrlExtractor.extract("https://share.acgnx.se/search.php?keyword=frieren"));
        assertEquals("https://share.acgnx.se/rss.xml?keyword=%E8%91%AC%E7%A9%BA%E7%9A%84%E8%8A%99%E8%8E%89%E8%8E%B2",
                RssUrlExtractor.extract("https://share.acgnx.se/search.php?keyword=%E8%91%AC%E7%A9%BA%E7%9A%84%E8%8A%99%E8%8E%89%E8%8E%B2"));
    }

    @Test
    void dmhySearchPageMapsToKeywordRss() {
        assertEquals("https://share.dmhy.org/topics/rss/rss.xml?keyword=frieren",
                RssUrlExtractor.extract("https://share.dmhy.org/topics/list?keyword=frieren"));
    }

    @Test
    void nonRssPagesReturnNull() {
        assertNull(RssUrlExtractor.extract(null));
        assertNull(RssUrlExtractor.extract(""));
        assertNull(RssUrlExtractor.extract("没有链接的文本"));
        assertNull(RssUrlExtractor.extract("https://bangumi.tv/subject/422074"));
        assertNull(RssUrlExtractor.extract("https://mikanani.me/"));
        assertNull(RssUrlExtractor.extract("ftp://mikanani.me/Home/Bangumi/3015"));
        // 非 acgnx/dmhy 域名的 keyword 搜索页不在规则内（交给 AI 兜底）
        assertNull(RssUrlExtractor.extract("https://example.com/search.php?keyword=x"));
    }
}
