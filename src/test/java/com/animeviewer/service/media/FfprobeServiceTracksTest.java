package com.animeviewer.service.media;

import com.animeviewer.service.model.Dtos.AudioTrackDto;
import com.animeviewer.service.model.Dtos.ChapterDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.28 P2 音轨/章节枚举解析单测（ffprobe 实测输出固化为夹具，2026-09-22 探测定案 2）。 */
class FfprobeServiceTracksTest {

    private static final FfprobeService SERVICE = new FfprobeService(null);

    /** 探测夹具（target/it-v028/sample_multi.mkv 实测输出节选）：jpn/chs 双 aac 轨 */
    private static final String MULTI_AUDIO_JSON = """
            {"streams":[
              {"index":1,"codec_name":"aac","codec_type":"audio","channels":1,
               "tags":{"language":"jpn","title":"日本語 2.0"}},
              {"index":2,"codec_name":"aac","codec_type":"audio","channels":2,
               "tags":{"language":"chs","title":"简体中文 2.0"}}
            ]}
            """;

    /** 无 language/title 的音轨（tags 缺键如实为 null） */
    private static final String BARE_AUDIO_JSON = """
            {"streams":[
              {"index":0,"codec_name":"aac","codec_type":"audio","channels":2,
               "tags":{"ENCODER":"Lavc62.19.100 aac"}},
              {"index":1,"codec_name":"flac","codec_type":"audio","channels":6}
            ]}
            """;

    /** 探测夹具（FFMETADATA 3 章节 mkv 实测输出节选；time_base 1/1000000000） */
    private static final String CHAPTERS_JSON = """
            {"chapters":[
              {"id":1,"time_base":"1/1000000000","start":0,"start_time":"0.000000",
               "end":10000000000,"end_time":"10.000000","tags":{"title":"OP"}},
              {"id":2,"time_base":"1/1000000000","start":10000000000,"start_time":"10.000000",
               "end":25000000000,"end_time":"25.000000","tags":{"title":"第一话 本篇"}},
              {"id":3,"time_base":"1/1000000000","start":25000000000,"start_time":"25.000000",
               "end":30000000000,"end_time":"30.000000","tags":{"title":"ED"}}
            ]}
            """;

    @Test
    void 多音轨枚举_序号按audio出现顺序零基编号() {
        List<AudioTrackDto> tracks = SERVICE.parseAudioTracks(MULTI_AUDIO_JSON);
        assertEquals(2, tracks.size());
        assertEquals(new AudioTrackDto(0, "aac", 1, "jpn", "日本語 2.0"), tracks.get(0));
        assertEquals(new AudioTrackDto(1, "aac", 2, "chs", "简体中文 2.0"), tracks.get(1));
    }

    @Test
    void 音轨tags缺键_字段如实null_非audio流不收() {
        List<AudioTrackDto> tracks = SERVICE.parseAudioTracks(BARE_AUDIO_JSON);
        assertEquals(2, tracks.size());
        assertNull(tracks.get(0).language());
        assertNull(tracks.get(0).title());
        assertEquals("aac", tracks.get(0).codec());
        assertEquals("flac", tracks.get(1).codec());
        assertEquals(6, tracks.get(1).channels());
    }

    @Test
    void 章节解析_start_end转秒_title透出() {
        List<ChapterDto> chapters = SERVICE.parseChapters(CHAPTERS_JSON);
        assertEquals(3, chapters.size());
        assertEquals(new ChapterDto(0.0, 10.0, "OP"), chapters.get(0));
        assertEquals(10.0, chapters.get(1).start());
        assertEquals(25.0, chapters.get(1).end());
        assertEquals("第一话 本篇", chapters.get(1).title());
        assertEquals("ED", chapters.get(2).title());
    }

    @Test
    void 脏章节跳过_start缺失或end不大于start() {
        String dirty = """
                {"chapters":[
                  {"id":1,"start_time":"5.000000","end_time":"3.000000","tags":{"title":"倒挂"}},
                  {"id":2,"start_time":null,"end_time":"8.000000","tags":{"title":"缺起点"}},
                  {"id":3,"start_time":"8.000000","end_time":"12.000000","tags":{"title":"合法"}}
                ]}
                """;
        List<ChapterDto> chapters = SERVICE.parseChapters(dirty);
        assertEquals(1, chapters.size());
        assertEquals("合法", chapters.get(0).title());
    }

    @Test
    void 空章节_空音轨_输出空列表() {
        assertTrue(SERVICE.parseChapters("{\"chapters\":[]}").isEmpty());
        assertTrue(SERVICE.parseAudioTracks("{\"streams\":[]}").isEmpty());
    }
}
