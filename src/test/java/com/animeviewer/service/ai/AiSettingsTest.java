package com.animeviewer.service.ai;

import com.animeviewer.service.ServiceProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.22 AI0 设置纯函数：KV JSON 往返 / 校验 / ready 判定（对齐 SubscriptionSettings 测试惯例）。
 *  v0.30 A5/A7 增 maxTokens / aiBindThreshold 字段与 totalCalls 统计纯函数。 */
class AiSettingsTest {

    private static final ServiceProperties NULL_PROPS = new ServiceProperties(null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void defaultsAreDisabled() {
        AiSettings s = AiSettings.defaults(NULL_PROPS);
        assertTrue(!s.enabled());
        assertEquals("https://api.openai.com/v1", s.baseUrl());
        assertEquals(30, s.timeoutSeconds());
        assertEquals(60, s.maxCallsPerHour());
        assertEquals(512, s.maxTokens());
        assertEquals(85, s.aiBindThreshold());
        assertTrue(!s.ready());
    }

    @Test
    void jsonRoundTripPreservesFields() {
        AiSettings s = new AiSettings(true, "http://127.0.0.1:11434/v1", "qwen2.5:7b", "sk-1",
                45, 100, true, "x-opencode-session: sess-1", 1024, 90);
        AiSettings back = AiSettings.load(s.toJson(), AiSettings.defaults(NULL_PROPS));
        assertEquals(s, back);
        assertTrue(back.ready());
    }

    @Test
    void corruptJsonFallsBackToDefaults() {
        AiSettings back = AiSettings.load("{broken", AiSettings.defaults(NULL_PROPS));
        assertEquals(AiSettings.defaults(NULL_PROPS), back);
    }

    @Test
    void partialJsonMergesWithDefaults() {
        AiSettings back = AiSettings.load("{\"enabled\":true,\"model\":\"gpt-4o-mini\"}",
                new AiSettings(false, "https://api.openai.com/v1", "", "", 30, 60, false, "", 512, 85));
        assertTrue(back.enabled());
        assertEquals("gpt-4o-mini", back.model());
        assertEquals("https://api.openai.com/v1", back.baseUrl());
        // v0.30 A5/A7：存量旧 JSON（无 maxTokens/aiBindThreshold 字段）按默认值补齐
        assertEquals(512, back.maxTokens());
        assertEquals(85, back.aiBindThreshold());
    }

    @Test
    void validateRejectsBadInput() {
        assertEquals("已启用 AI 时必须填写接口地址",
                new AiSettings(true, "", "m", "", 30, 60, false, "", 512, 85).validate());
        assertEquals("已启用 AI 时必须填写模型名",
                new AiSettings(true, "https://x/v1", "", "", 30, 60, false, "", 512, 85).validate());
        assertEquals("接口地址需以 http(s):// 开头",
                new AiSettings(true, "ftp://x", "m", "", 30, 60, false, "", 512, 85).validate());
        assertEquals("超时需在 5~120 秒",
                new AiSettings(true, "https://x", "m", "", 3, 60, false, "", 512, 85).validate());
        assertEquals("max_tokens 需在 0~32768（0=不注入，由服务端默认）",
                new AiSettings(true, "https://x", "m", "", 30, 60, false, "", 65536, 85).validate());
        assertEquals("媒体库自动绑定阈值需在 0~100（0=关闭自动绑定）",
                new AiSettings(true, "https://x", "m", "", 30, 60, false, "", 512, 101).validate());
        assertNull(new AiSettings(false, "", "", "", 30, 0, false, "", 0, 0).validate());
    }

    @Test
    void readyRequiresEnabledAndUrlAndModel() {
        assertTrue(new AiSettings(true, "https://x/v1", "m", "", 30, 60, false, "", 512, 85).ready());
        assertTrue(!new AiSettings(false, "https://x/v1", "m", "", 30, 60, false, "", 512, 85).ready());
        assertTrue(!new AiSettings(true, "https://x/v1", "", "", 30, 60, false, "", 512, 85).ready());
        assertTrue(!new AiSettings(true, "", "m", "", 30, 60, false, "", 512, 85).ready());
    }

    @Test
    void extraHeadersParseAndValidate() {
        AiSettings s = new AiSettings(true, "https://x/v1", "m", "", 30, 60, false,
                "x-opencode-session: sess-1\nX-Retry: 2\n\nbadline\nbad name: v", 512, 85);
        AiSettings.HeaderList h = s.parseHeaders();
        assertEquals(2, h.pairs().size());
        assertEquals("x-opencode-session", h.pairs().get(0)[0]);
        assertEquals("sess-1", h.pairs().get(0)[1]);
        assertEquals(2, h.errors().size());
        assertTrue(s.validate() != null); // 非法行 → 校验失败
        assertNull(new AiSettings(true, "https://x/v1", "m", "", 30, 60, false, "x-opencode-session: sess-1", 512, 85).validate());
    }

    @Test
    void verdictParsingToleratesFencesAndVariants() {
        HitVerdict v = HitVerdict.parse("{\"type\":\"episode\",\"episode\":11,\"isMainline\":true,\"reason\":\"含 第11话\"}");
        assertEquals("episode", v.type());
        assertEquals(11, v.episode());
        assertTrue(v.isMainline());
        assertEquals("AI 本篇", v.label());

        HitVerdict fenced = HitVerdict.parse("```json\n{\"type\":\"op\",\"episode\":null,\"isMainline\":false,\"reason\":\"OP 主题曲\"}\n```");
        assertEquals("op", fenced.type());
        assertTrue(fenced.nonMainline());
        assertEquals("AI 主题曲", fenced.label());

        // 非法 type 归一为 other；isMainline 缺省按 type 推断
        HitVerdict loose = HitVerdict.parse("{\"type\":\"book\"}");
        assertEquals("other", loose.type());
        assertTrue(loose.nonMainline());
        assertEquals("AI 非本篇", loose.label());

        // 集数越界（1080 被当集数）→ null
        assertNull(HitVerdict.parse("{\"type\":\"episode\",\"episode\":1080}").episode());
        assertNull(HitVerdict.parse("不是 JSON"));
        assertNull(HitVerdict.parse(null));
    }

    /* ── v0.30 A5 累计调用统计 ── */

    @Test
    void totalCallsStatsRoundTripAndTolerance() {
        assertEquals(0, AiService.readTotalCalls(null));
        assertEquals(0, AiService.readTotalCalls(""));
        assertEquals(0, AiService.readTotalCalls("{broken"));
        assertEquals(0, AiService.readTotalCalls("{\"totalCalls\":-5}"));
        assertEquals(7, AiService.readTotalCalls("{\"totalCalls\":7}"));
        assertEquals(0, AiService.readTotalCalls("{\"other\":1}"));
    }
}
