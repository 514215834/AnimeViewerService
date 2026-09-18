package com.animeviewer.service.media;

import com.animeviewer.service.model.Dtos.SubtitleTrackDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.23 SB1 字幕轨枚举解析单测（ffprobe -select_streams s JSON 形态，2026-09-18 真实输出固化为夹具）。 */
class FfprobeServiceSubtitleTest {

    private static final FfprobeService SERVICE = new FfprobeService(null);

    /** 探测夹具（testdata/sb23/sub-demo-h264.mkv 实测输出节选）：ass 双语轨 + eng srt 轨 */
    private static final String MULTI_TRACK_JSON = """
            {"streams":[
              {"index":2,"codec_name":"ass","codec_type":"subtitle",
               "tags":{"language":"chs","title":"简体中文","ENCODER":"Lavc62.34.102 ass"}},
              {"index":3,"codec_name":"ass","codec_type":"subtitle",
               "tags":{"language":"zht","title":"繁體中文"}},
              {"index":4,"codec_name":"subrip","codec_type":"subtitle",
               "tags":{"language":"eng","title":"English"}}
            ]}
            """;

    /** 无字幕 mkv：streams 为空数组 */
    private static final String NO_SUB_JSON = """
            {"streams":[]}
            """;

    /** 探测夹具（testdata/sb23/nolang.mkv 实测）：language 缺失时 tags 无 language 键 */
    private static final String NO_LANG_JSON = """
            {"streams":[
              {"index":1,"codec_name":"subrip","codec_type":"subtitle",
               "tags":{"ENCODER":"Lavc62.34.102 srt","DURATION":"00:00:08.000000000"}}
            ]}
            """;

    @Test
    void 多轨枚举_序号按字幕轨出现顺序零基编号_字段如实透出() {
        List<SubtitleTrackDto> tracks = SERVICE.parseSubtitleTracks(MULTI_TRACK_JSON);
        assertEquals(3, tracks.size());
        assertEquals(new SubtitleTrackDto(0, "ass", "chs", "简体中文"), tracks.get(0));
        assertEquals(new SubtitleTrackDto(1, "ass", "zht", "繁體中文"), tracks.get(1));
        assertEquals(new SubtitleTrackDto(2, "subrip", "eng", "English"), tracks.get(2));
    }

    @Test
    void 无字幕轨_返回空列表() {
        assertTrue(SERVICE.parseSubtitleTracks(NO_SUB_JSON).isEmpty());
    }

    @Test
    void 无输出_返回空列表() {
        assertTrue(SERVICE.parseSubtitleTracks("").isEmpty());
        assertTrue(SERVICE.parseSubtitleTracks("不是 JSON").isEmpty());
    }

    @Test
    void language_tag缺失_如实为null() {
        List<SubtitleTrackDto> tracks = SERVICE.parseSubtitleTracks(NO_LANG_JSON);
        assertEquals(1, tracks.size());
        assertEquals(0, tracks.get(0).index());
        assertEquals("subrip", tracks.get(0).codec());
        assertNull(tracks.get(0).language());
        assertNull(tracks.get(0).title());
    }
}
