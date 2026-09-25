package com.animeviewer.service.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        // v0.30 补记五：terms 参数（tokyotosho）与大小写保留——完整搜索地址贴上即规则直填
        SiteFiller.Fill tt = SiteFiller.guess("https://www.tokyotosho.info/rss.php?terms=Naruto");
        assertEquals("tokyotosho", tt.key());
        assertEquals("rss.php?terms={kw}", tt.searchTemplate());
        SiteFiller.Fill sk = SiteFiller.guess("https://sukebei.nyaa.si/?page=rss&q=frieren&c=0_0&f=0");
        assertEquals("sukebei", sk.key());
        assertEquals("https://sukebei.nyaa.si", sk.baseUrl());
        assertEquals("?page=rss&q={kw}&c=0_0&f=0", sk.searchTemplate());
    }

    /** v0.30 补记二：acg.rip /.xml?term= 形态（term 入关键词参数表）——完整搜索地址粘贴即免 AI 直填 */
    @Test
    void acgRipTermParamDerivesTemplate() {
        SiteFiller.Fill f = SiteFiller.guess("https://acg.rip/.xml?term=frieren");
        assertEquals("acg", f.key());
        assertEquals("acg.rip", f.name());
        assertEquals("https://acg.rip", f.baseUrl());
        assertEquals(".xml?term={kw}", f.searchTemplate());
    }

    /** v0.30 补记二：LLM 结果模板形态闸——大小写不敏感 + 认可 .xml（初版误杀蜜柑大写路径与 acg.rip .xml 形态） */
    @Test
    void feedTemplateGateCaseInsensitiveAndXml() {
        assertTrue(SiteFiller.looksLikeFeedTemplate("RSS/Search?searchword={kw}")); // 蜜柑大写路径（曾误杀）
        assertTrue(SiteFiller.looksLikeFeedTemplate(".xml?term={kw}"));             // acg.rip 形态（曾误杀）
        assertTrue(SiteFiller.looksLikeFeedTemplate("rss.xml?keyword={kw}"));
        assertTrue(SiteFiller.looksLikeFeedTemplate("?PAGE=RSS&q={kw}"));           // 大小写不敏感
        assertTrue(SiteFiller.looksLikeFeedTemplate("atom.xml?feed={kw}"));
        assertFalse(SiteFiller.looksLikeFeedTemplate("search?query={kw}"));         // 非 feed 形态仍拒绝
        assertFalse(SiteFiller.looksLikeFeedTemplate(""));
        assertFalse(SiteFiller.looksLikeFeedTemplate(null));
    }

    @Test
    void knownHostPresets() {
        // ② 已知 host（首页 URL 无 RSS 形态）→ 内置同款模板
        assertEquals("rss.xml?keyword={kw}", SiteFiller.guess("https://share.acgnx.se").searchTemplate());
        assertEquals("topics/rss/rss.xml?keyword={kw}", SiteFiller.guess("https://share.dmhy.org").searchTemplate());
        SiteFiller.Fill n = SiteFiller.guess("https://nyaa.si/?f=0&c=0_0&q=xxx");
        assertEquals("nyaa", n.key());
        assertEquals("?page=rss&q={kw}&c=0_0&f=0", n.searchTemplate());

        // v0.30 补记五预设扩容（acg.rip/tokyotosho/sukebei 实抓验证，anidex 取公开索引器形态）
        SiteFiller.Fill ar = SiteFiller.guess("https://acg.rip/.atom.xml");
        assertEquals("acg", ar.key());
        assertEquals("https://acg.rip", ar.baseUrl());
        assertEquals(".xml?term={kw}", ar.searchTemplate());
        assertEquals("rss.php?terms={kw}", SiteFiller.guess("https://www.tokyotosho.info/").searchTemplate());
        assertEquals("https://sukebei.nyaa.si", SiteFiller.guess("https://sukebei.nyaa.si/").baseUrl());
        assertEquals("rss/?q={kw}", SiteFiller.guess("https://anidex.info/").searchTemplate());
        // 蜜柑不设预设：/RSS/Search?searchword= 实测恒空频道（无可用关键词搜索 RSS）→ null 走 LLM 兜底拒绝
        assertNull(SiteFiller.guess("https://mikanani.me/"));
        assertNull(SiteFiller.guess("https://mikanani.me/RSS/Bangumi"));
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
        // v0.30 补记二：单字符泛用段(m/w)全等匹配——m/w 开头的正常 host 段不再被误剔（曾得 mikananime）
        assertEquals("mikanani", SiteFiller.deriveKey("mikanani.me"));
        assertEquals("acg", SiteFiller.deriveKey("acg.rip"));
    }
}
