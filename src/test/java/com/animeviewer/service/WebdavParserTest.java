package com.animeviewer.service;

import com.animeviewer.service.webdav.WebdavParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.15 O3 PROPFIND multistatus 解析用例：命名空间前缀不敏感、目录/文件/尺寸/时间、href 百分号解码。 */
class WebdavParserTest {

    private static final String SAMPLE = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/%E7%95%AA%E5%89%A7%20A/</D:href>
                <D:propstat><D:prop>
                  <D:resourcetype><D:collection/></D:resourcetype>
                  <D:getlastmodified>Tue, 03 Nov 2026 09:00:00 GMT</D:getlastmodified>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/video%20one.mp4</D:href>
                <D:propstat><D:prop>
                  <D:resourcetype/>
                  <D:getcontentlength>123456</D:getcontentlength>
                  <D:getlastmodified>Tue, 03 Nov 2026 09:00:00 GMT</D:getlastmodified>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
            """;

    @Test
    void parsesEntriesWithDecodedNamesAndTypes() {
        List<WebdavParser.Entry> list = WebdavParser.parse(SAMPLE);
        assertEquals(3, list.size());

        WebdavParser.Entry self = list.get(0);
        assertEquals("/dav/", self.href());
        assertTrue(self.dir());
        assertNull(self.size());

        WebdavParser.Entry dir = list.get(1);
        assertEquals("番剧 A", dir.name());
        assertTrue(dir.dir());
        assertNull(dir.size());
        assertEquals(1793696400000L, dir.mtime()); // 2026-11-03T09:00:00Z

        WebdavParser.Entry file = list.get(2);
        assertEquals("video one.mp4", file.name());
        assertFalse(file.dir());
        assertEquals(123456L, file.size());
    }

    @Test
    void worksWithoutNamespacePrefix() {
        String plain = """
                <?xml version="1.0"?>
                <multistatus xmlns="DAV:">
                  <response>
                    <href>/x/movie.mkv</href>
                    <propstat><prop><resourcetype/><getcontentlength>7</getcontentlength></prop></propstat>
                  </response>
                </multistatus>
                """;
        List<WebdavParser.Entry> list = WebdavParser.parse(plain);
        assertEquals(1, list.size());
        assertEquals("movie.mkv", list.get(0).name());
        assertEquals(7L, list.get(0).size());
        assertFalse(list.get(0).dir());
    }

    @Test
    void malformedXmlThrowsIllegalState() {
        try {
            WebdavParser.parse("<not-xml");
            assertTrue(false, "应抛出异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("解析失败"));
        }
    }
}
