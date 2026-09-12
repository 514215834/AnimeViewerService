package com.animeviewer.service;

import com.animeviewer.service.media.NameParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** S3 文件名识别用例（主流命名形态 + 清洗边界） */
class NameParserTest {

    private void assertParse(String name, String title, Integer ep) {
        var p = NameParser.parse(name);
        assertEquals(title, p.title(), "title of: " + name);
        assertEquals(ep, p.episode(), "episode of: " + name);
    }

    @Test
    void groupDashStyle() {
        assertParse("[Nekomoe kissaten] 麻辣教师GTO - 01 [1080p][BDRip][AVC].mkv", "麻辣教师GTO", 1);
        assertParse("[SubGroup] Clannad After Story - 12v2 [720p].mp4", "Clannad After Story", 12);
        assertParse("[Group] White Album 2 - 03 [BDRip].mkv", "White Album 2", 3);
    }

    @Test
    void allBracketStyle() {
        assertParse("[Group][实验品家庭][01][1080p][简体内嵌].mkv", "实验品家庭", 1);
        assertParse("[Sub][Some Title][12][BIG5].mp4", "Some Title", 12);
    }

    @Test
    void seasonEpisodeStyle() {
        assertParse("Attack on Titan S02E05.mkv", "Attack on Titan", 5);
        assertParse("某科学的超电磁炮 S01E10 [1080p].mp4", "某科学的超电磁炮", 10);
    }

    @Test
    void chineseEpisodeStyle() {
        assertParse("麻辣教师GTO 第01话.mkv", "麻辣教师GTO", 1);
        assertParse("第07話 永遠.mak", "永遠", 7);
        assertParse("Great Teacher Onizuka 第 12 集.mp4", "Great Teacher Onizuka", 12);
    }

    @Test
    void dashAndTrailStyle() {
        assertParse("Great Teacher Onizuka - 43 [Final].mkv", "Great Teacher Onizuka", 43);
        assertParse("Welcome to the NHK_09[1080p].mkv", "Welcome to the NHK", 9);
        assertParse("Angel Beats! 03.mkv", "Angel Beats!", 3);
    }

    @Test
    void resolutionAndYearNotEpisode() {
        // 尾部数字被噪声清洗吸收，不误判集数
        assertParse("Movie.2019.1080p.BluRay.mkv", "Movie.2019", null);
        assertParse("Title 1080p.mkv", "Title", null);
    }

    @Test
    void noEpisodeNumber() {
        assertParse("Gekkan Shoujo Nozaki-kun SP.mp4", "Gekkan Shoujo Nozaki-kun SP", null);
        assertParse("不认识的视频.mkv", "不认识的视频", null);
    }

    @Test
    void unrecognized() {
        assertParse("home.movie.wmv", "home.movie", null);
        var p = NameParser.parse("   .mkv");
        assertNull(p.title());
    }

    @Test
    void normalizeForMatch() {
        assertEquals("麻辣教师gto", NameParser.normalizeForMatch("麻辣教师 GTO！"));
        assertEquals(NameParser.normalizeForMatch("麻辣教师GTO"), NameParser.normalizeForMatch("麻辣教师 GTO"));
        assertEquals("neongenesisevangelion1995", NameParser.normalizeForMatch("Neon Genesis Evangelion (1995)"));
    }
}
