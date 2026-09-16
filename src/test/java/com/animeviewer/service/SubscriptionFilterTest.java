package com.animeviewer.service;

import com.animeviewer.service.subscription.SubscriptionFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** v0.19 SU1 订阅过滤纯函数用例：夹具标题取 2026-09-13 实测 acgnx RSS（RssResourceParserTest 同源）。
 *  parseEpisode 与前端 mediaService.guessEpisodeSortFromTitle 逐例对齐（两端口径一致是硬要求——
 *  确认弹窗预填与订阅命中必须对同一标题解析出同一集数）。
 *  v0.21 补记（2026-09-16）：独立数字退化的尾数陷阱（10bit/48kHz/H.264/日期段/范围包）按实测事故
 *  两端同步修正为排除（此前为「不修正口径」的已知陷阱，事故见订阅 #29 LoliHouse 11 条全解析成 10）。 */
class SubscriptionFilterTest {

    @Test
    void parsesExplicitEpisodeMark() {
        assertEquals(33, SubscriptionFilter.parseEpisode("名侦探光之美少女！ - EP33 [简／繁] (1080p H.264 AAC SRTx2)"));
        assertEquals(8, SubscriptionFilter.parseEpisode("孤独摇滚！ EP08 [WebRip 1080p HEVC-10bit]"));
        assertEquals(33, SubscriptionFilter.parseEpisode("名侦探柯南 第33话 消失的作家"));
        assertEquals(33, SubscriptionFilter.parseEpisode("第33話 消失的作家")); // 話 繁体
        assertEquals(33, SubscriptionFilter.parseEpisode("第 33 集 消失的作家"));
    }

    @Test
    void fallsBackToLastStandaloneNumber() {
        // 无显式标记：1080（后缀 p）与年份被排除，独立数字 33 命中
        assertEquals(33, SubscriptionFilter.parseEpisode("[雪飘工作室][名探偵プリキュア！][1080p][2026][33][简繁日外挂]"));
        // v0.21 补记（2026-09-16 实测事故修复，两端同步）：HEVC-10bit 尾数不再胜出，裸数字集数 08 正确解析
        assertEquals(8, SubscriptionFilter.parseEpisode("[LoliHouse] 孤独摇滚！ - 08 [WebRip 1080p HEVC-10bit]"));
        // H.264/Vol.12（点分邻接）与年份全部排除后无候选——作废跳过（无集数不入队列）
        assertNull(SubscriptionFilter.parseEpisode("孤独摇滚 Vol.12 2026 H.264"));
        // 60fps 单位尾数不劫持集数
        assertEquals(5, SubscriptionFilter.parseEpisode("[Group] Title - 05 [1080p 60fps]"));
    }

    /** 2026-09-16 订阅 #29（転校先の清楚可憐な美少女…）实测事故夹具：dmhy/acgnx RSS 原标题逐字。
     *  修复前 LoliHouse 第 01~11 话全部解析成 10（HEVC-10bit 尾数），同集数 pending 占位唯一把其余 10 条全挡掉；
     *  JMAX OP/ED 解析成 24（FLAC 96kHz/24bit）、raw 卷解析成 4（第01-04巻）、小说 epub 解析成 3（1-3 epub）。 */
    @Test
    void parsesLoliHouseBareNumberEpisodeTitles() {
        assertEquals(11, SubscriptionFilter.parseEpisode(
                "[LoliHouse] 转校生美少女竟是我曾经认为是男孩子的青梅竹马 / 轉學後班上的清純可愛美少女，竟是小時候玩在一起的哥兒們"
                        + " / 転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件 / てんびん - 11"
                        + " [WebRip 1080p HEVC-10bit AAC][简繁内封字幕]"));
        assertEquals(9, SubscriptionFilter.parseEpisode(
                "[LoliHouse] 转校生美少女竟是我曾经认为是男孩子的青梅竹马 / 転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件"
                        + " / てんびん - 09 [WebRip 1080p HEVC-10bit AAC][简繁内封字幕]"));
        assertEquals(1, SubscriptionFilter.parseEpisode(
                "[LoliHouse] 转校生美少女竟是我曾经认为是男孩子的青梅竹马 / 転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件"
                        + " / てんびん - 01 [WebRip 1080p HEVC-10bit AAC][简繁内封字幕]"));
    }

    @Test
    void rejectsNonEpisodeReleases() {
        // JMAX 主题曲单曲：日期段点分邻接 + kHz/24bit 单位尾数全部排除 → null（不入队列）
        assertNull(SubscriptionFilter.parseEpisode(
                "[JMAX] [2026.07.08] TVアニメ「転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件」"
                        + "EDテーマ「Tilt」／harmoe [FLAC 48kHz/24bit]"));
        assertNull(SubscriptionFilter.parseEpisode(
                "[JMAX] [2026.07.08] TVアニメ「転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件」"
                        + "OPテーマ「夏に重ねて」／DIALOGUE+ [FLAC 96kHz/24bit]"));
        // raw 卷 / epub 合集包：连字符范围成员排除 → null
        assertNull(SubscriptionFilter.parseEpisode("転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件 raw 第01-04巻"));
        assertNull(SubscriptionFilter.parseEpisode("(ラノベ)[雲雀湯] 転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件 1-3 epub"));
    }

    @Test
    void explicitOutOfRangeFallsBackLikeFrontend() {
        // 「1080」先被 EP? 规则误命中且超出 1~999：与前端一致跳过第N话分支走独立数字退化
        assertNull(SubscriptionFilter.parseEpisode("EP1080 特别篇"));
        assertEquals(12, SubscriptionFilter.parseEpisode("EP1080 特别篇 第12话"));
    }

    @Test
    void unparseableTitlesReturnNull() {
        assertNull(SubscriptionFilter.parseEpisode(null));
        assertNull(SubscriptionFilter.parseEpisode("無年份無集數的公告"));
        assertNull(SubscriptionFilter.parseEpisode("仅 resolution 1080p"));
    }

    @Test
    void parsesSizeText() {
        assertEquals((long) (1.4 * 1024L * 1024 * 1024), SubscriptionFilter.parseSizeBytes("萌番組鏡像 | xx | 1.4GB | 動畫"));
        assertEquals((long) (593.7 * 1024L * 1024), SubscriptionFilter.parseSizeBytes("593.7MB"));
        assertEquals(1024L, SubscriptionFilter.parseSizeBytes("1KB"));
        assertNull(SubscriptionFilter.parseSizeBytes(null));
        assertNull(SubscriptionFilter.parseSizeBytes("无大小段"));
    }

    @Test
    void extractsFansub() {
        assertEquals("雪飘工作室", SubscriptionFilter.extractFansub("[雪飘工作室][名探偵プリキュア！][33]"));
        assertEquals("萌番組鏡像", SubscriptionFilter.extractFansub("萌番組鏡像 | 名侦探光之美少女！ - EP33"));
        assertNull(SubscriptionFilter.extractFansub("名侦探光之美少女！ - EP33"));
    }
}
