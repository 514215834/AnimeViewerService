package com.animeviewer.service.stream;

import com.animeviewer.service.ServiceProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.28 P1 转码命令形态（对齐 SubtitleStreamerCommandTest 惯例；探测定案 1：三档 preset 全实时达标）。 */
class TranscodeStreamerCommandTest {

    private static final TranscodeStreamer S =
            new TranscodeStreamer(new ServiceProperties(null, "ffmpeg", "ffprobe", null, null, null, null, null, null, null, null, null));

    @Test
    void 命令形态_libx264_强制8bit_aac_默认superfast() {
        String[] cmd = S.buildCommand("G:/media/ep01.mkv", 0, null, null);
        assertEquals(
                "[ffmpeg, -hide_banner, -loglevel, error, -i, G:/media/ep01.mkv, -map, 0:v:0, -map, 0:a:0?, "
                        + "-sn, -dn, -c:v, libx264, -preset, superfast, -crf, 23, -pix_fmt, yuv420p, "
                        + "-c:a, aac, -b:a, 192k, -movflags, +frag_keyframe+empty_moov, -f, mp4, pipe:1]",
                Arrays.toString(cmd));
    }

    @Test
    void seek定位_ss置于输入前() {
        String[] cmd = S.buildCommand("G:/media/ep01.mkv", 123.5, null, "fast");
        int i = Arrays.asList(cmd).indexOf("-ss");
        assertTrue(i > 0);
        assertEquals("123.5", cmd[i + 1]);
        assertEquals("-i", cmd[i + 2]);
        assertEquals("fast", cmd[Arrays.asList(cmd).indexOf("-preset") + 1]);
    }

    @Test
    void 音轨切换_map0aN可选语义() {
        String[] cmd = S.buildCommand("G:/media/ep01.mkv", 0, 2, "superfast");
        int i = Arrays.asList(cmd).indexOf("-map");
        assertEquals("0:a:2?", cmd[i + 3]);
    }

    @Test
    void preset白名单越界回退superfast() {
        assertEquals("superfast", TranscodeStreamer.normalizePreset(null));
        assertEquals("superfast", TranscodeStreamer.normalizePreset(""));
        assertEquals("superfast", TranscodeStreamer.normalizePreset("ultrafast"));
        assertEquals("medium", TranscodeStreamer.normalizePreset("MEDIUM"));
    }

    @Test
    void 并发闸默认1_即时获取() {
        assertTrue(S.tryAcquire());
        assertEquals(1, S.maxConcurrent());
        S.release();
    }
}
