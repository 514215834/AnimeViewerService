package com.animeviewer.service.download;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** v0.24 SE2 bencode/BTIH 用例：真值对拍 = nyaa #2160516 种子（站点 RSS 自带 nyaa:infoHash 为独立真值，
 *  2026-09-19 curl 采种 + 采 RSS，另以 Python sha1 独立复算一致）；手造最小 dict 验证定位边界；
 *  非法输入容错返回 null（入队链路容错优先）。 */
class BencodeParserTest {

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = BencodeParserTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name + " 应存在于 test resources");
            return in.readAllBytes();
        }
    }

    /** 真值对拍：种子 BTIH == nyaa RSS 自带 nyaa:infoHash（e4bdce64…） */
    @Test
    void realTorrentMatchesNyaaDeclaredInfoHash() throws Exception {
        assertEquals("e4bdce64a33505e00ddde4b5cb8d8478a2e658a6",
                BencodeParser.infoHashHex(resource("/v024-spyfamily-2160516.torrent")));
    }

    /** 手造最小 dict：d 4:info d 6:length i5e 4:name 5:a.txt e e——
     *  info 值段含 bencode 闭合 'e'（与 nyaa 真值对拍口径一致）= "d6:lengthi5e4:name5:a.txte"（sha1 独立预算） */
    @Test
    void handBuiltMinimalTorrent() {
        assertEquals("fa3671e2915fe9e91a61556fb0e70ee4a44a87bb",
                BencodeParser.infoHashHex("d4:infod6:lengthi5e4:name5:a.txtee".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void locateInfoOffsets() {
        byte[] b = "d4:infod6:lengthi5e4:name5:a.txtee".getBytes(StandardCharsets.US_ASCII);
        int[] seg = BencodeParser.locateInfo(b);
        assertEquals("d6:lengthi5e4:name5:a.txte",
                new String(b, seg[0], seg[1] - seg[0], StandardCharsets.US_ASCII));
    }

    @Test
    void malformedInputsReturnNull() {
        assertNull(BencodeParser.infoHashHex(null));
        assertNull(BencodeParser.infoHashHex(new byte[0]));
        assertNull(BencodeParser.infoHashHex("i5e".getBytes(StandardCharsets.US_ASCII)));             // 非顶层 dict
        assertNull(BencodeParser.infoHashHex("d4:name4:teste".getBytes(StandardCharsets.US_ASCII)));  // 无 info 键
        assertNull(BencodeParser.infoHashHex("d4:infod6:lengthi5e".getBytes(StandardCharsets.US_ASCII))); // 截断
    }
}
