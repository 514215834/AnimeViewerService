package com.animeviewer.service;

import com.animeviewer.service.subscription.MatchScore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.20 SU4 命中匹配度评分纯函数用例：夹具标题取 2026-09-15 实测 acgnx/dmhy RSS
 *  （碧蓝之海第三季/相反的你和我）；季号提取为错季拦截核心，逐例断言口径。 */
class MatchScoreTest {

    private static final List<String> PREFS = List.of("LoliHouse", "北宇治字幕组");

    @Test
    void strongSeasonsCnAndEn() {
        assertEquals(Set.of(2), MatchScore.strongSeasons("相反的你和我 第二季"));
        assertEquals(Set.of(2), MatchScore.strongSeasons("正反対な君と仆 第2期"));
        assertEquals(Set.of(3), MatchScore.strongSeasons("碧蓝之海 第三季"));
        assertEquals(Set.of(3), MatchScore.strongSeasons("第 3 部"));
        assertEquals(Set.of(2), MatchScore.strongSeasons("Grand Blue 2nd Season"));
        assertEquals(Set.of(3), MatchScore.strongSeasons("ぐらんぶる Season 3"));
        assertEquals(Set.of(11), MatchScore.strongSeasons("第十一季"));
        assertEquals(Set.of(), MatchScore.strongSeasons("碧蓝之海"));
        // S01E22 为弱信号（CR 按季独立编号），不提取
        assertEquals(Set.of(), MatchScore.strongSeasons("Seihantai na Kimi to Boku S01E16"));
    }

    @Test
    void idealHitScoresFull() {
        // 含中文名 + 季号一致（第三季条目 vs Season 3）+ 偏好组 + 1080p + 简繁标记 = 100
        MatchScore.Result r = MatchScore.score("碧蓝之海 第三季", "ぐらんぶる Season 3",
                "[LoliHouse] 碧蓝之海 第三季 / Grand Blue S3 - 10 [WebRip 1080p HEVC-10bit][简繁内封]",
                "LoliHouse", PREFS);
        assertEquals(100, r.total());
        assertTrue(r.detail().contains("\"total\":100"));
        assertTrue(r.detail().contains("标题含中文名"));
        assertTrue(r.detail().contains("季号一致"));
        assertTrue(r.detail().contains("命中偏好"));
    }

    @Test
    void wrongSeasonScoreIsPenalized() {
        // v0.19 实测痛点：无季号条目（第一季）命中「第二季」资源 → 季号显式冲突 0 分
        MatchScore.Result r = MatchScore.score("相反的你和我", "正反対な君と仆",
                "[Nix-Raws] 相反的你和我 第二季 / 正反対な君と仆 第2期 / Seihantai na Kimi to Boku S01E16",
                "Nix-Raws", PREFS);
        assertTrue(r.detail().contains("季号冲突"), r.detail());
        // 20(基础)+25(含中文名)+0(季号冲突)+0(偏好)+0(无分辨率)+0(无字幕标记) = 45
        assertEquals(45, r.total());
    }

    @Test
    void titleOnlyMatchesOriginalName() {
        MatchScore.Result r = MatchScore.score("碧蓝之海 第三季", "ぐらんぶる Season 3",
                "[Nix-Raws] ぐらんぶる Season 3 / Grand Blue S03E11 [CR WEB-DL 1080p AVC AAC][简繁内封]",
                "Nix-Raws", PREFS);
        // 20+20(含原名)+20(季号一致：Season 3)+0+10+10 = 80
        assertEquals(80, r.total());
        assertTrue(r.detail().contains("标题含原名"));
    }

    @Test
    void titleWithoutSubjectNameScoresZero() {
        MatchScore.Result r = MatchScore.score("碧蓝之海 第三季", "ぐらんぶる Season 3",
                "[某组] 完全无关的标题 - 05 [720p]",
                null, PREFS);
        // 20+0+0(标题无季号中性10→wait)+... 重新算：基础20+标题0+季号10(无显式季号)+偏好0+清晰度5+字幕0 = 35
        assertEquals(35, r.total());
        assertTrue(r.detail().contains("标题未含条目名"));
    }

    @Test
    void fansubPreferenceCaseInsensitive() {
        MatchScore.Result r = MatchScore.score("碧蓝之海 第三季", "ぐらんぶる Season 3",
                "[lolihouse] 碧蓝之海 第三季 - 09 [1080p][简繁内封]",
                "lolihouse", PREFS);
        assertTrue(r.detail().contains("命中偏好"));
    }

    @Test
    void resolutionAndSubtitleMarks() {
        // 720p → 5 分；含「简」标记 → 10
        MatchScore.Result r = MatchScore.score("碧蓝之海 第三季", "ぐらんぶる Season 3",
                "[绿茶字幕组] 碧蓝之海 第三季 [07][720p][简日内嵌]", "绿茶字幕组", PREFS);
        // 20+25+20+0+5+10 = 80
        assertEquals(80, r.total());
        // 无分辨率无字幕标记
        MatchScore.Result r2 = MatchScore.score("碧蓝之海 第三季", "ぐらんぶる Season 3",
                "[绿茶字幕组] 碧蓝之海 第三季 [07][WebRip]",
                "绿茶字幕组", PREFS);
        // 20+25+20+0+0+0 = 65
        assertEquals(65, r2.total());
    }

    @Test
    void detailJsonIsRenderable() {
        MatchScore.Result r = MatchScore.score("碧蓝之海 第三季", "ぐらんぶる Season 3",
                "[LoliHouse] 碧蓝之海 第三季 - 10 [1080p][简繁内封]", "LoliHouse", PREFS);
        // 明细 JSON 结构：total + parts 数组，前端直接渲染
        assertTrue(r.detail().startsWith("{\"total\":"));
        assertTrue(r.detail().contains("\"parts\":["));
        // 满分链路：20+25+20+15+10+10
        assertEquals(100, r.total());
    }

    @Test
    void nullAndBlankSafety() {
        // 条目名/标题为空不抛异常
        MatchScore.Result r = MatchScore.score(null, null, null, null, null);
        // 20(基础)+0(标题)+10(季号中性)+0+0+0 = 30
        assertEquals(30, r.total());
    }
}
