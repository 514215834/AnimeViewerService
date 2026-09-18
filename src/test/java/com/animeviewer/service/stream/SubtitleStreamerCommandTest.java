package com.animeviewer.service.stream;

import com.animeviewer.service.ServiceProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** v0.23 SB1 字幕提取命令形态（对齐 RemuxStreamer 命令单测惯例）。 */
class SubtitleStreamerCommandTest {

    @Test
    void 命令形态_ffmpeg路径_按字幕轨序号map() {
        SubtitleStreamer s = new SubtitleStreamer(new ServiceProperties(null, "ffmpeg", "ffprobe", null, null, null, null, null, null, null, null));
        String[] cmd = s.buildCommand("G:/media/ep01.mkv", 1);
        assertEquals(
                "[ffmpeg, -hide_banner, -loglevel, error, -i, G:/media/ep01.mkv, -map, 0:s:1, -f, webvtt, pipe:1]",
                Arrays.toString(cmd));
    }

    @Test
    void 首轨序号0_map0s0() {
        SubtitleStreamer s = new SubtitleStreamer(new ServiceProperties(null, "ffmpeg", "ffprobe", null, null, null, null, null, null, null, null));
        String[] cmd = s.buildCommand("G:/media/ep01.mkv", 0);
        assertEquals("0:s:0", cmd[7]);
    }
}
