package com.animeviewer.service;

import com.animeviewer.service.download.MagnetParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.16 DN2 磁力解析纯函数用例 */
class MagnetParserTest {

    @Test
    void parseStandardMagnet() {
        var info = MagnetParser.parse(
                "magnet:?xt=urn:btih:D160B8D8EA35A5B4E52837468FC8F03D55CEF1F7&dn=%E5%90%8D%E4%BE%A6%E6%8E%A2+33"
                        + "&tr=" + MagnetParserTest.class.getName());
        assertEquals("d160b8d8ea35a5b4e52837468fc8f03d55cef1f7", info.infoHash());
        assertEquals("名侦探 33", info.displayName());
        assertEquals(1, info.trackers().size());
    }

    @Test
    void parseMinimalMagnet() {
        var info = MagnetParser.parse("magnet:?xt=urn:btih:" + "a".repeat(40));
        assertEquals("a".repeat(40), info.infoHash());
        assertNull(info.displayName());
        assertTrue(info.trackers().isEmpty());
    }

    @Test
    void parseBase32InfoHashKeptAsIs() {
        String b32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var info = MagnetParser.parse("magnet:?xt=urn:btih:" + b32);
        assertEquals(b32, info.infoHash());
    }

    @Test
    void invalidInfoHashIsNull() {
        assertNull(MagnetParser.parse("magnet:?xt=urn:btih:short123").infoHash());
        assertNull(MagnetParser.parse("magnet:?dn=nolinks").infoHash());
    }

    @Test
    void nonMagnetUris() {
        assertNull(MagnetParser.parse("https://example.com/a.torrent").infoHash());
        assertTrue(MagnetParser.isDirectLink("ftp://host/file.mkv"));
        assertFalse(MagnetParser.isSupported("smb://host/share"));
        assertFalse(MagnetParser.isSupported(""));
    }

    @Test
    void mergeTrackersAppendsMissingOnly() {
        String uri = "magnet:?xt=urn:btih:" + "a".repeat(40) + "&tr=" + MagnetParser.urlEncode("http://t1/announce");
        String merged = MagnetParser.mergeTrackers(uri, List.of("http://t1/announce", "http://t2/announce", " "));
        // 原 tr 保留 + 追加 1 个缺失 tracker；已存在（t1）与空白项跳过
        assertTrue(merged.contains("tr=http%3A%2F%2Ft1%2Fannounce"));
        assertEquals(2, merged.split("&tr=", -1).length - 1);
        assertTrue(merged.endsWith("tr=http%3A%2F%2Ft2%2Fannounce"));
    }

    @Test
    void mergeTrackersSkipsNonMagnetAndEmptyList() {
        String url = "https://example.com/a.torrent";
        assertEquals(url, MagnetParser.mergeTrackers(url, List.of("http://t1/announce")));
        String uri = "magnet:?xt=urn:btih:" + "a".repeat(40);
        assertEquals(uri, MagnetParser.mergeTrackers(uri, List.of()));
    }
}
