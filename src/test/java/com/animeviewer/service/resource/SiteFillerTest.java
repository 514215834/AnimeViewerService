package com.animeviewer.service.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** v0.30 补记一 站点配置规则映射纯函数（规则集以 §5R 探测定案 1 实抓形态为准 + nyaa 已知预设）。 */
class SiteFillerTest {

    @Test
    void feedSearchUrlDerivesTemplate() {
        // ① 通用反推：RSS 搜索地址 → baseUrl + 模板（关键词值替换 {kw}）
        SiteFiller.Fill f = SiteFiller.guess("https://share.acgnx.se/rss.xml?keyword=frieren");
        assertEquals("acgnx", f.key());
        assertEquals("https://share.acgnx.se", f.baseUrl());
        assertEquals("rss.xml?keyword={kw}", f.searchTemplate());

        // dmhy 带路径模板
        SiteFiller.Fill d = SiteFiller.guess("https://share.dmhy.org/topics/rss/rss.xml?keyword=x");
        assertEquals("dmhy", d.key());
        assertEquals("topics/rss/rss.xml?keyword={kw}", d.searchTemplate());

        // nyaa query 型 RSS（page=rss + q 参数）→ 其他参数原样保留
        SiteFiller.Fill n = SiteFiller.guess("https://nyaa.si/?page=rss&q=frieren&c=0_0&f=0");
        assertEquals("nyaa", n.key());
        assertEquals("https://nyaa.si", n.baseUrl());
        assertEquals("?page=rss&q={kw}&c=0_0&f=0", n.searchTemplate());

        // 任意未知站的 RSS 搜索地址同样反推（贴 feed 地址即得配置）
        SiteFiller.Fill g = SiteFiller.guess("https://torrent.example.org/feed/rss?keyword=abc");
        assertEquals("example", g.key());
        assertEquals("https://torrent.example.org", g.baseUrl());
        assertEquals("feed/rss?keyword={kw}", g.searchTemplate());
    }

    @Test
    void knownHostPresets() {
        // ② 已知 host（首页 URL 无 RSS 形态）→ 内置同款模板
        assertEquals("rss.xml?keyword={kw}", SiteFiller.guess("https://share.acgnx.se").searchTemplate());
        assertEquals("topics/rss/rss.xml?keyword={kw}", SiteFiller.guess("https://share.dmhy.org").searchTemplate());
        SiteFiller.Fill n = SiteFiller.guess("https://nyaa.si/?f=0&c=0_0&q=xxx");
        assertEquals("nyaa", n.key());
        assertEquals("?page=rss&q={kw}&c=0_0&f=0", n.searchTemplate());
        // 蜜柑首页不在预设（无关键词搜索 RSS）→ null 走 LLM 兜底
        assertNull(SiteFiller.guess("https://mikanani.me/"));
    }

    @Test
    void nonResourceSitesReturnNull() {
        assertNull(SiteFiller.guess(null));
        assertNull(SiteFiller.guess(""));
        assertNull(SiteFiller.guess("没有链接"));
        assertNull(SiteFiller.guess("https://bgm.tv/subject/422074"));
        assertNull(SiteFiller.guess("ftp://share.dmhy.org/"));
    }

    @Test
    void deriveKeyFromHost() {
        assertEquals("dmhy", SiteFiller.deriveKey("share.dmhy.org"));
        assertEquals("acgnx", SiteFiller.deriveKey("share.acgnx.se"));
        assertEquals("nyaa", SiteFiller.deriveKey("nyaa.si"));
        assertEquals("bangumi", SiteFiller.deriveKey("bangumi.moe"));
        assertEquals("example", SiteFiller.deriveKey("www2.example.com"));
    }
}
