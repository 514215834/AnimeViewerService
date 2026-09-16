package com.animeviewer.service.subscription;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.22 AI2 扩展检索词纯函数：合并序（中文名→原名→AI 扩展词）、净化（去空白/去重/上限）、损坏 JSON 兜底。 */
class SubscriptionKeywordsTest {

    @Test
    void mergeKeepsBaseOrderThenAiKeywords() {
        List<String> merged = SubscriptionService.mergeKeywords(
                "转学后班上的清纯可爱美少女，竟是小时候玩在一起的哥们儿",
                "転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件",
                "[\"Tenbin no Ojou-sama\",\"てんびん\",\"轉學後班上的清純可愛美少女\"]");
        assertEquals(5, merged.size());
        assertEquals("転校先の清楚可憐な美少女が、昔男子と思って一緒に遊んだ幼馴染だった件", merged.get(1));
        assertEquals("てんびん", merged.get(3));
    }

    @Test
    void mergeDedupsAcrossSources() {
        List<String> merged = SubscriptionService.mergeKeywords("Name A", "Name A",
                "[\"name a\",\"Name B\"]");
        assertEquals(List.of("Name A", "Name B"), merged);
    }

    @Test
    void nullAiKeywordsStillQueriesBaseNames() {
        assertEquals(2, SubscriptionService.mergeKeywords("中文名", "原名", null).size());
        assertEquals(2, SubscriptionService.mergeKeywords("中文名", "原名", "{broken").size());
    }

    @Test
    void sanitizeTrimsDedupsAndCaps() {
        List<String> out = SubscriptionService.sanitizeKeywords(
                List.of(" 词1 ", "词1", "", "WORD2", "词3", "a".repeat(120), "词4", "词5", "词6", "词7", "词8", "词9", "词10", "词11"));
        assertEquals(10, out.size());
        assertEquals("词1", out.get(0));
        assertEquals("WORD2", out.get(1));
        assertTrue(out.stream().noneMatch(x -> x.length() > 100));
    }
}
