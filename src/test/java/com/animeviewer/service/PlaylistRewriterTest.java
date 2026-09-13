package com.animeviewer.service;

import com.animeviewer.service.stream.PlaylistRewriter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.15 O2 清单重写器用例：master/media 两级、KEY/MAP/MEDIA URI 属性、相对/绝对地址、未知标签透传。 */
class PlaylistRewriterTest {

    private final AtomicInteger seq = new AtomicInteger();

    /** 假代理包裹函数：p://<序号>/<绝对URL>，可断言包裹次数与目标 */
    private String proxy(String absolute) {
        return "p://" + seq.incrementAndGet() + "/" + absolute;
    }

    @Test
    void mediaPlaylistRewritesSegmentsKeysAndMap() {
        String playlist = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:10
                #EXT-X-MAP:URI="init.mp4"
                #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x9c7db8778570d05c3177c349fd9236aa
                #EXTINF:9.009,
                seg-1.ts
                #EXTINF:9.009,
                sub/dir/seg-2.ts?token=abc
                https://cdn.example.com/abs/seg-3.ts
                #EXT-X-ENDLIST
                """;
        String out = PlaylistRewriter.rewrite(playlist, "https://up.stream/hls/index.m3u8", this::proxy);
        String[] lines = out.split("\n");
        assertEquals("#EXTM3U", lines[0]);
        // 未知/普通标签透传
        assertEquals("#EXT-X-VERSION:3", lines[1]);
        assertEquals("#EXT-X-TARGETDURATION:10", lines[2]);
        // MAP URI 重写、IV 保留
        assertTrue(lines[3].startsWith("#EXT-X-MAP:URI=\"p://1/https://up.stream/hls/init.mp4\""), lines[3]);
        // KEY URI 重写、其余属性保留
        assertTrue(lines[4].startsWith("#EXT-X-KEY:METHOD=AES-128,URI=\"p://2/https://up.stream/hls/enc.key\""), lines[4]);
        assertTrue(lines[4].contains("IV=0x9c7db8778570d05c3177c349fd9236aa"));
        // 相对分片 → 绝对 → 包裹
        assertEquals("p://3/https://up.stream/hls/seg-1.ts", lines[6]);
        assertEquals("p://4/https://up.stream/hls/sub/dir/seg-2.ts?token=abc", lines[8]);
        // 绝对地址直接包裹
        assertEquals("p://5/https://cdn.example.com/abs/seg-3.ts", lines[9]);
        assertEquals("#EXT-X-ENDLIST", lines[10]);
        assertEquals(5, seq.get());
    }

    @Test
    void masterPlaylistRewritesVariantAndAudioUris() {
        String playlist = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="ja",URI="audio/ja/index.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aud"
                1080p/index.m3u8
                """;
        String out = PlaylistRewriter.rewrite(playlist, "https://up.stream/master.m3u8", this::proxy);
        assertTrue(out.contains("p://1/https://up.stream/audio/ja/index.m3u8"), out);
        assertTrue(out.contains("p://2/https://up.stream/1080p/index.m3u8"), out);
        assertTrue(out.contains("AUDIO=\"aud\""), out);
    }

    @Test
    void uriAttributeCaseInsensitiveAndMultipleAttrs() {
        String out = PlaylistRewriter.rewrite(
                "#EXT-X-MAP:uri=\"init.mp4\",BYTERANGE=\"0@100\"",
                "https://up.stream/a.m3u8", this::proxy);
        assertTrue(out.contains("p://1/https://up.stream/init.mp4"), out);
        assertTrue(out.contains("BYTERANGE=\"0@100\""), out);
    }

    @Test
    void dataUriAndIllegalLinePassThroughUnwrapped() {
        String playlist = """
                #EXTM3U
                data:text/plain,hello
                seg with space.ts
                """;
        String out = PlaylistRewriter.rewrite(playlist, "https://up.stream/x.m3u8", this::proxy);
        assertEquals(0, seq.get());
        assertTrue(out.contains("data:text/plain,hello"));
        assertTrue(out.contains("seg with space.ts"));
    }

    @Test
    void nullOrBlankInputReturnsAsIs() {
        assertEquals(null, PlaylistRewriter.rewrite(null, "https://up.stream/x.m3u8", this::proxy));
        assertEquals("", PlaylistRewriter.rewrite("", "https://up.stream/x.m3u8", this::proxy));
        assertEquals("x", PlaylistRewriter.rewrite("x", null, this::proxy));
    }

    @Test
    void crlfInputNormalizedToLf() {
        String out = PlaylistRewriter.rewrite("#EXTM3U\r\nseg-1.ts\r\n", "https://up.stream/i.m3u8", this::proxy);
        assertTrue(out.contains("p://1/https://up.stream/seg-1.ts"));
    }
}
