package com.animeviewer.service;

import com.animeviewer.service.subscription.SubscriptionFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** v0.19 SU1 订阅过滤纯函数用例：夹具标题取 2026-09-13 实测 acgnx RSS（RssResourceParserTest 同源）。
 *  parseEpisode 与前端 mediaService.guessEpisodeSortFromTitle 逐例对齐（含已知启发式尾数陷阱，
 *  两端口径一致是硬要求——确认弹窗预填与订阅命中必须对同一标题解析出同一集数）。 */
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
        // 与前端一致的尾数启发式：1080p 排除后 10（10bit）胜出——已知启发式行为，不修正口径
        assertEquals(10, SubscriptionFilter.parseEpisode("[LoliHouse] 孤独摇滚！ - 08 [WebRip 1080p HEVC-10bit]"));
        // H.264 的 264 为最后一个独立数字——同前端
        assertEquals(264, SubscriptionFilter.parseEpisode("孤独摇滚 Vol.12 2026 H.264"));
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
