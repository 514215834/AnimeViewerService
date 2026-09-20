package com.animeviewer.service.hanime;

import com.animeviewer.service.model.Dtos.HanimePlaylist;
import com.animeviewer.service.model.Dtos.HanimePlaylistItem;
import com.animeviewer.service.model.Dtos.HanimeSearchItem;
import com.animeviewer.service.model.Dtos.HanimeSearchResult;
import com.animeviewer.service.model.Dtos.HanimeWatchDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.26 HN2 解析夹具单测：夹具为 2026-09-19 经代理抓取的真实 hanime1.com 响应裁剪
 *  （hanime1.com/search 与 /watch 页，对齐 RssResourceParserTest 内联 text block 惯例）。 */
class HanimeParserTest {

    /* 真实搜索页裁剪：两个完整条目 + 第三个与第一条同 code（验去重）+ 分页（有 next） */
    private static final String SEARCH_HTML = """
            <div class="content-padding-new"><div class="row no-gutter">
            <div title="[Ubermation] Mona Full 4K/1080 + Alt ver." class="video-item-container">
            <div class="horizontal-card">
            <a href="https://hanime1.com/watch?v=408185" class="video-link">
            <div class="thumb-container">
            <img class="main-thumb" src="https://vdownload.hembed.com/image/thumbnail/408185l.jpg?secure=h9U8_zn-tOlCST-zrtlLnA==,1792031712" loading="lazy">
            <div class="duration">
            05:34
            </div>
            <div class="stats-container">
            <div class="stat-item"><i class="material-icons">thumb_up</i> 100%</div>
            <div class="stat-item">38.2萬次</div>
            </div>
            </div>
            <div class="title">Mona Full 4K/1080 + Alt ver.</div>
            </a>
            <div class="subtitle"><a href="https://hanime1.com/search?query=Ubermation">Ubermation</a>
            <span class="subtitle-time">&nbsp;• 1週前</span></div>
            </div>
            </div>
            <div title="純愛調教記 後編" class="video-item-container">
            <div class="horizontal-card">
            <a href="/watch?v=408191" class="video-link">
            <div class="thumb-container">
            <img class="main-thumb" src="https://vdownload.hembed.com/image/thumbnail/408191l.jpg?secure=xx==,1792031712" loading="lazy">
            <div class="duration">
            16:02
            </div>
            <div class="stats-container">
            <div class="stat-item"><i class="material-icons">thumb_up</i> 97%</div>
            <div class="stat-item">5.1萬次</div>
            </div>
            </div>
            <div class="title">純愛調教記 後編</div>
            </a>
            <div class="subtitle"><a href="https://hanime1.com/search?query=%E5%B7%A8%E4%B9%B3">麗</a>
            <span class="subtitle-time">&nbsp;• 2日前</span></div>
            </div>
            </div>
            <div title="重复条目" class="video-item-container">
            <a href="https://hanime1.com/watch?v=408185" class="video-link"></a>
            </div>
            </div></div>
            <ul class="pagination" role="navigation">
            <li class="page-item disabled" aria-disabled="true"><span class="page-link">&lsaquo;</span></li>
            <li class="page-item active" aria-current="page"><span class="page-link">1</span></li>
            <li class="page-item"><a class="page-link" href="?sort=a&amp;page=2" rel="next" aria-label="pagination.next">&rsaquo;</a></li>
            </ul>
            """;

    /* 末页：next 为 disabled 态（无 a[rel=next]）→ hasNext=false */
    private static final String SEARCH_LAST_PAGE_HTML = """
            <ul class="pagination" role="navigation">
            <li class="page-item"><a class="page-link" href="?page=1" rel="prev" aria-label="pagination.previous">&lsaquo;</a></li>
            <li class="page-item active" aria-current="page"><span class="page-link">7</span></li>
            <li class="page-item disabled" aria-disabled="true"><span class="page-link">&rsaquo;</span></li>
            </ul>
            """;

    /* 真实 watch 页裁剪：source 顺序与站点一致（720/480/1080 乱序，断言解析后降序） */
    private static final String WATCH_HTML = """
            <h3 id="shareBtn-title" class="video-details-wrapper" style="font-weight: bold;">[Ubermation] Mona Full 4K/1080 + Alt ver.</h3>
            <video style="width: 100%;" id="player" controls crossorigin playsinline preload="auto" poster="https://vdownload.hembed.com/image/thumbnail/408185h.jpg?secure=zuHfEiik3YAVTlnS7UdXOA==,1792031712" loop>
            <source src="https://vdownload.hembed.com/408185-720p.mp4?secure=P2PGTFIrcSYiWk8CUtn_-g==,1789837206" type="video/mp4" size="720">
            <source src="https://vdownload.hembed.com/408185-480p.mp4?secure=rkolLEsxu9jHCokqMp-NdQ==,1789837206" type="video/mp4" size="480">
            <source src="https://vdownload.hembed.com/408185-1080p.mp4?secure=uTb9-qVMOmypekgmxu73gg==,1789837206" type="video/mp4" size="1080">
            </video>
            <div class="video-details-wrapper video-tags-wrapper">
            <div class="single-video-tag"><a href="/search?query=原神"><span>#</span>&nbsp;原神</a></div>
            <div class="single-video-tag"><a href="/search?query=莫娜"><span>#</span>&nbsp;莫娜</a></div>
            </div>
            """;

    @Test
    void searchParse_extractsItemsAndHasNext() {
        HanimeSearchResult r = HanimeParser.parseSearch(SEARCH_HTML, 1);
        assertEquals(1, r.page());
        assertTrue(r.hasNext());
        assertEquals(2, r.items().size());

        HanimeSearchItem first = r.items().get(0);
        assertEquals("408185", first.videoCode());
        assertEquals("[Ubermation] Mona Full 4K/1080 + Alt ver.", first.title());
        assertTrue(first.thumbnail().contains("thumbnail/408185l.jpg"));
        assertEquals("05:34", first.duration());
        assertEquals("100%", first.likes());
        assertEquals("38.2萬次", first.views());
        assertEquals("Ubermation", first.brand());

        HanimeSearchItem second = r.items().get(1);
        assertEquals("408191", second.videoCode());
        assertEquals("純愛調教記 後編", second.title());
        assertEquals("97%", second.likes());
        assertEquals("5.1萬次", second.views());
    }

    @Test
    void searchParse_lastPageHasNoNext() {
        HanimeSearchResult r = HanimeParser.parseSearch(SEARCH_LAST_PAGE_HTML, 7);
        assertEquals(7, r.page());
        assertTrue(!r.hasNext());
        assertTrue(r.items().isEmpty());
    }

    @Test
    void searchParse_dedupesSameVideoCode() {
        // 第三块容器与第一条同 code → 去重后仍 2 条
        assertEquals(2, HanimeParser.parseSearch(SEARCH_HTML, 1).items().size());
    }

    @Test
    void watchParse_sourcesSortedDescAndTagsStripped() {
        HanimeWatchDto d = HanimeParser.parseWatch(WATCH_HTML, "408185");
        assertEquals("408185", d.videoCode());
        assertEquals("[Ubermation] Mona Full 4K/1080 + Alt ver.", d.title());
        assertTrue(d.poster().contains("thumbnail/408185h.jpg"));
        assertEquals(3, d.sources().size());
        // 分辨率降序：默认（取首档）= 最高档
        assertEquals(1080, d.sources().get(0).res());
        assertEquals("1080p", d.sources().get(0).label());
        assertEquals(720, d.sources().get(1).res());
        assertEquals(480, d.sources().get(2).res());
        assertTrue(d.sources().get(0).url().endsWith("408185-1080p.mp4?secure=uTb9-qVMOmypekgmxu73gg==,1789837206"));
        assertEquals("Ubermation", d.brand());
        assertEquals(2, d.tags().size());
        assertEquals("原神", d.tags().get(0));
        assertEquals("莫娜", d.tags().get(1));
    }

    @Test
    void watchParse_withoutSourcesYieldsEmptyList() {
        HanimeWatchDto d = HanimeParser.parseWatch("<html><body><h3 id=\"shareBtn-title\">无源视频</h3></body></html>", "1");
        assertEquals("无源视频", d.title());
        assertTrue(d.sources().isEmpty());
        assertTrue(d.brand().isEmpty());
        assertTrue(d.playlist() == null);
    }

    /* v0.27 A2 真实侧栏播放列表裁剪（watch/408286 社團形态）：顶部块（社團 + 上传者链接 + 计数）
       + 三个条目（首个 = 当前播放条目带 videos-scroll 类；末条缺 data-href 走 h4 a href 兜底） */
    private static final String PLAYLIST_HTML = """
            <div class="video-playlist-wrapper">
            <div id="playlist-top-block" class="single-icon-wrapper video-playlist-top">
            <h4 style="font-weight: bold;">
            <span style="font-size: 12px; color: #aaa;">社團</span>
            <a href="https://hanime1.com/user/644581/uploaded" style="color: white;">Anryms4c41</a>
            </h4>
            <div style="font-size: 12px; color: #aaa;">
            <a href="https://hanime1.com/user/644581">Anryms4c41</a>
            <span style="font-size: 10px;">&bull;</span>
            <span style="flex-shrink: 0;">52 部影片</span>
            </div>
            </div>
            <div class="playlist-hover-wrap clickable-row videos-scroll" data-href="https://hanime1.com/watch?v=408286">
            <div class="playlist-video-card video-item-container no-select">
            <div class="video-thumb-container horizontal-card">
            <div class="thumb-container">
            <a href="https://hanime1.com/watch?v=408286">
            <img class="main-thumb" src="https://vdownload.hembed.com/image/thumbnail/408286l.jpg?secure=aaa==,1792031639" loading="lazy">
            <div class="duration">08:41</div>
            </a>
            </div>
            </div>
            <div class="video-info-container">
            <h4 class="video-title"><a href="https://hanime1.com/watch?v=408286">Navia Screwed【GI】</a></h4>
            <div class="video-meta-data"><div class="meta-author"><a href="https://hanime1.com/search?query=Anryms4c41">Anryms4c41</a></div></div>
            </div>
            </div>
            </div>
            <div class="playlist-hover-wrap clickable-row" data-href="https://hanime1.com/watch?v=407784">
            <div class="playlist-video-card video-item-container no-select">
            <div class="video-thumb-container horizontal-card">
            <div class="thumb-container">
            <a href="https://hanime1.com/watch?v=407784">
            <img class="main-thumb" src="https://vdownload.hembed.com/image/thumbnail/407784l.jpg?secure=bbb==,1792031639" loading="lazy">
            <div class="duration">07:07</div>
            </a>
            </div>
            </div>
            <div class="video-info-container">
            <h4 class="video-title"><a href="https://hanime1.com/watch?v=407784">(07:06) 娅佐夫 Le Cadeau</a></h4>
            </div>
            </div>
            </div>
            <div class="playlist-hover-wrap clickable-row">
            <div class="playlist-video-card video-item-container no-select">
            <div class="video-info-container">
            <h4 class="video-title"><a href="https://hanime1.com/watch?v=406892">缺 data-href 条目</a></h4>
            </div>
            </div>
            </div>
            </div>
            """;

    @Test
    void watchParse_playlistSeriesAndCurrentMarker() {
        HanimeWatchDto d = HanimeParser.parseWatch(WATCH_HTML + PLAYLIST_HTML, "408286");
        HanimePlaylist p = d.playlist();
        assertEquals("社團", p.category());
        assertEquals("Anryms4c41", p.name());
        assertEquals(52, p.total());
        assertEquals(3, p.items().size());
        // 首个 = 当前播放条目（videos-scroll 类）→ current=true，其余 false
        HanimePlaylistItem cur = p.items().get(0);
        assertEquals("408286", cur.videoCode());
        assertEquals("Navia Screwed【GI】", cur.title());
        assertEquals("08:41", cur.duration());
        assertTrue(cur.thumbnail().contains("thumbnail/408286l.jpg"));
        assertTrue(cur.current());
        assertFalse(p.items().get(1).current());
        assertEquals("407784", p.items().get(1).videoCode());
        // 缺 data-href 的条目走 h4.video-title a[href] 兜底
        assertEquals("406892", p.items().get(2).videoCode());
    }

    @Test
    void videoCodeOf_extractsFromWatchUrls() {
        assertEquals("408185", HanimeParser.videoCodeOf("https://hanime1.com/watch?v=408185"));
        assertEquals("abc123", HanimeParser.videoCodeOf("/watch?sort=x&v=abc123"));
        assertNull(HanimeParser.videoCodeOf("https://hanime1.com/search?query=x"));
        assertNull(HanimeParser.videoCodeOf(null));
    }

    @Test
    void brandFallbackFromTitlePrefix() {
        assertEquals("Ubermation", HanimeParser.extractFansub("[Ubermation] Mona"));
        assertEquals("", HanimeParser.extractFansub("无前缀标题"));
        assertEquals("", HanimeParser.extractFansub(null));
    }
}
