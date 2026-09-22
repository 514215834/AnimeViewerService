package com.animeviewer.service.stream;

import com.animeviewer.service.ServiceProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** v0.28 P2 音轨切换复用转封装管道：视频 copy + 音频 aac；null 音轨保持既有 -c copy 零回归。 */
class RemuxAudioCommandTest {

    private static final RemuxStreamer S =
            new RemuxStreamer(new ServiceProperties(null, "ffmpeg", "ffprobe", null, null, null, null, null, null, null, null, null));

    @Test
    void 无音轨参数_既有copy形态不变() {
        String[] cmd = S.buildCommand("G:/media/ep01.mkv", 0, null);
        assertEquals(
                "[ffmpeg, -hide_banner, -loglevel, error, -i, G:/media/ep01.mkv, -map, 0:v:0, -map, 0:a:0?, "
                        + "-sn, -dn, -c, copy, -movflags, +frag_keyframe+empty_moov, -f, mp4, pipe:1]",
                Arrays.toString(cmd));
    }

    @Test
    void 音轨切换_视频copy_音频aac() {
        String[] cmd = S.buildCommand("G:/media/ep01.mkv", 0, 1);
        assertEquals(
                "[ffmpeg, -hide_banner, -loglevel, error, -i, G:/media/ep01.mkv, -map, 0:v:0, -map, 0:a:1?, "
                        + "-sn, -dn, -c:v, copy, -c:a, aac, -b:a, 192k, -movflags, +frag_keyframe+empty_moov, -f, mp4, pipe:1]",
                Arrays.toString(cmd));
    }

    @Test
    void 音轨序号越界钳制到0_63() {
        assertEquals("0:a:0?", S.buildCommand("f", 0, -5)[9]);
        assertEquals("0:a:63?", S.buildCommand("f", 0, 999)[9]);
    }
}
