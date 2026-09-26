package com.animeviewer.service.hanime;

import com.animeviewer.service.model.Dtos.HanimePlaylist;
import com.animeviewer.service.model.Dtos.HanimePlaylistItem;
import com.animeviewer.service.model.Dtos.HanimeSearchItem;
import com.animeviewer.service.model.Dtos.HanimeSearchResult;
import com.animeviewer.service.model.Dtos.HanimeSource;
import com.animeviewer.service.model.Dtos.HanimeWatchDto;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** v0.26 HN2 hanime1.com 页面解析（Jsoup 纯函数，选择器按 2026-09-19 真实抓包定案，夹具 = 真实响应裁剪）。
 *  搜索条目：div.video-item-container[title=完整标题] > a.video-link[href=watch?v=code]，
 *  内含 img.main-thumb（缩略图）/ div.duration / .stats-container .stat-item（好评率、观看数）/
 *  div.title（短标题）/ .subtitle a（品牌名）/ .subtitle-time（发布时间文案）；
 *  分页：ul.pagination li.page-item:not(.disabled) a[rel=next] 存在即 hasNext；
 *  watch 页：video#player 下 source[type=video/mp4][size=分辨率] 为带签名 mp4 直链（无 Referer 校验，
 *  约 9 天时效）；标题 h3#shareBtn-title；海报 video[poster]；标签 .single-video-tag a（文案带 # 前缀）。
 *  解析失败不抛异常：条目级容错（缺链接/缺 code 跳过），整体结构崩坏返回空集合由上层给空态。 */
public final class HanimeParser {

    private HanimeParser() {}

    /** watch?v= 视频码：站点为短数字/字母码（实测 408185 等数字形态，兼容字母与连字符） */
    static final Pattern CODE_IN_URL = Pattern.compile("[?&]v=([A-Za-z0-9_-]{1,32})");
    /** 品牌名兜底：标题前缀 [组名]（与前端 extractFansub 同口径） */
    static final Pattern BRAND_IN_TITLE = Pattern.compile("^\\[([^\\[\\]]{1,40})]");

    /** 从任意 URL/链接文本提取视频码；无 v= 参数返回 null */
    public static String videoCodeOf(String url) {
        if (url == null) return null;
        var m = CODE_IN_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** 搜索页 → 条目列表 + 是否有下一页（page 仅透传回显） */
    public static HanimeSearchResult parseSearch(String html, int page) {
        Document doc = Jsoup.parse(html);
        List<HanimeSearchItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element container : doc.select("div.video-item-container")) {
            Element link = container.selectFirst("a.video-link");
            if (link == null) continue;
            String code = videoCodeOf(link.attr("href"));
            if (code == null || !seen.add(code)) continue;
            String fullTitle = container.attr("title").trim();
            String title = !fullTitle.isBlank() ? fullTitle : text(container.selectFirst("div.title"));
            if (title.isBlank()) continue;
            String thumbnail = "";
            Element img = container.selectFirst("img.main-thumb");
            if (img != null) thumbnail = img.attr("src").trim();
            String likes = "";
            String views = "";
            for (Element stat : container.select(".stats-container .stat-item")) {
                // ownText 只取直接文本：排除 material-icons 图标名（<i>thumb_up</i> 会混入 text()）
                String t = stat.ownText().trim();
                if (t.contains("%") && likes.isBlank()) likes = t;
                else if (views.isBlank()) views = t;
            }
            items.add(new HanimeSearchItem(code, title, thumbnail,
                    text(container.selectFirst(".duration")),
                    likes, views, text(container.selectFirst(".subtitle a"))));
        }
        boolean hasNext = !doc.select("ul.pagination li.page-item:not(.disabled) a[rel=next]").isEmpty();
        return new HanimeSearchResult(page, hasNext, items);
    }

    /** watch 页 → 播放详情；sources 按分辨率降序（默认播最高档） */
    public static HanimeWatchDto parseWatch(String html, String videoCode) {
        Document doc = Jsoup.parse(html);
        String title = text(doc.selectFirst("h3#shareBtn-title"));
        if (title.isBlank()) title = doc.title().replaceFirst("\\s*-\\s*H動漫.*$", "").trim();
        String poster = "";
        Element video = doc.selectFirst("video#player");
        if (video != null) poster = video.attr("poster").trim();
        List<HanimeSource> sources = new ArrayList<>();
        for (Element s : doc.select("video#player source[type=video/mp4]")) {
            String url = s.attr("src").trim();
            if (url.isBlank()) continue;
            int res = 0;
            try {
                res = Integer.parseInt(s.attr("size").trim());
            } catch (NumberFormatException ignored) {
                // size 缺省（站点个别旧条目）：仍保留该源，label 归「默认」
            }
            sources.add(new HanimeSource(res > 0 ? res + "p" : "默认", res, url));
        }
        sources.sort((a, b) -> Integer.compare(b.res(), a.res()));
        List<String> tags = new ArrayList<>();
        for (Element t : doc.select(".single-video-tag a")) {
            String tag = t.text().trim();
            if (tag.startsWith("#")) tag = tag.substring(1).trim();
            if (!tag.isBlank() && !tags.contains(tag)) tags.add(tag);
        }
        String brand = extractFansub(title);
        return new HanimeWatchDto(videoCode, title, poster, brand, tags, sources, parsePlaylist(doc));
    }

    /** v0.27 A2 侧栏播放列表（系列/社团合集）解析：
     *  站点无独立「系列」区块——watch 页右栏 .video-playlist-wrapper 统一承载（顶部块 #playlist-top-block
     *  的分类 span 文案「社團/系列」+ h4 内归属链接名 + 计数「N 部影片」；条目 div.playlist-hover-wrap
     *  [data-href=watch?v=code]，h4.video-title a 标题 + .duration 时长 + img.main-thumb 缩略图，
     *  当前播放条目 wrap 带 videos-scroll 类）。条目按 DOM 顺序原样返回（站点即播放顺序），码去重；
     *  顶部块/条目缺失（无侧栏）返回 null。 */
    static HanimePlaylist parsePlaylist(Document doc) {
        List<HanimePlaylistItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element wrap : doc.select("div.playlist-hover-wrap")) {
            String href = wrap.attr("data-href");
            if (href.isBlank()) {
                Element a = wrap.selectFirst("h4.video-title a");
                href = a == null ? "" : a.attr("href");
            }
            String code = videoCodeOf(href);
            if (code == null || !seen.add(code)) continue;
            String title = text(wrap.selectFirst("h4.video-title a"));
            if (title.isBlank()) continue;
            String thumbnail = "";
            Element img = wrap.selectFirst("img.main-thumb");
            if (img != null) thumbnail = img.attr("src").trim();
            items.add(new HanimePlaylistItem(code, title, thumbnail, text(wrap.selectFirst(".duration")),
                    wrap.hasClass("videos-scroll")));
        }
        if (items.isEmpty()) return null;
        String category = "";
        String name = "";
        int total = 0;
        Element top = doc.selectFirst("#playlist-top-block");
        if (top != null) {
            category = text(top.selectFirst("h4 > span"));
            Element nameLink = top.selectFirst("h4 a");
            if (nameLink != null) name = nameLink.text().trim();
            for (Element span : top.select("span")) {
                String t = span.ownText().trim();
                if (t.contains("部影片")) {
                    var m = java.util.regex.Pattern.compile("(\\d+)").matcher(t);
                    if (m.find()) total = Integer.parseInt(m.group(1));
                }
            }
        }
        return new HanimePlaylist(category, name, total, items);
    }

    static String extractFansub(String title) {
        if (title == null) return "";
        var m = BRAND_IN_TITLE.matcher(title.trim());
        return m.find() ? m.group(1).trim() : "";
    }

    private static String text(Element e) {
        return e == null ? "" : e.text().trim();
    }
}
