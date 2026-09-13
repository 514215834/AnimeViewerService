package com.animeviewer.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v0.15 O3 WebDAV 端到端冒烟：假上游（JDK HttpServer 模拟 PROPFIND + Range 文件）→
 *  open 签发 streamId → browse 列目录（self 排除/目录在前/解码名）→ stream Range 透传。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"av.data-dir=target/test-data", "av.scan.auto-on-start=false"})
class WebdavSmokeTest {

    private static final String LISTING = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>/dav/sub/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>/dav/anime%20ep01.mp4</D:href><D:propstat><D:prop>
                <D:resourcetype/><D:getcontentlength>100</D:getcontentlength>
              </D:prop></D:propstat></D:response>
            </D:multistatus>
            """;
    private static final byte[] FILE_BODY = new byte[100];

    @Autowired
    private MockMvc mvc;

    private static HttpServer upstream;
    private static int port;

    static {
        for (int i = 0; i < FILE_BODY.length; i++) FILE_BODY[i] = (byte) ('0' + i % 10);
    }

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/dav", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!"Basic dXNlcjpwYXNz".equals(auth)) { // user:pass
                ex.sendResponseHeaders(401, -1);
                return;
            }
            if ("PROPFIND".equals(ex.getRequestMethod())) {
                byte[] body = LISTING.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                ex.sendResponseHeaders(207, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                return;
            }
            if ("GET".equals(ex.getRequestMethod())) {
                String range = ex.getRequestHeaders().getFirst("Range");
                if ("bytes=0-9".equals(range)) {
                    ex.getResponseHeaders().set("Content-Range", "bytes 0-9/100");
                    ex.getResponseHeaders().set("Content-Type", "video/mp4");
                    ex.sendResponseHeaders(206, 10);
                    try (OutputStream os = ex.getResponseBody()) { os.write(FILE_BODY, 0, 10); }
                } else {
                    ex.getResponseHeaders().set("Content-Type", "video/mp4");
                    ex.sendResponseHeaders(200, FILE_BODY.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(FILE_BODY); }
                }
                return;
            }
            ex.sendResponseHeaders(405, -1);
        });
        upstream.start();
        port = upstream.getAddress().getPort();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    private String token() throws Exception {
        return Files.readString(Path.of("target", "test-data", "token")).trim();
    }

    @Test
    void openBrowseAndStreamRoundtrip() throws Exception {
        // 无服务 Token → 401（/api/** 统一门禁）
        mvc.perform(post("/api/webdav/open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:" + port + "/dav\"}"))
                .andExpect(status().isUnauthorized());

        // open：连通校验 + 签发 streamId
        MvcResult opened = mvc.perform(post("/api/webdav/open")
                        .header("X-AV-Token", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:" + port + "/dav\",\"username\":\"user\",\"password\":\"pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamId").isNotEmpty())
                .andReturn();
        String streamId = com.jayway.jsonpath.JsonPath.read(opened.getResponse().getContentAsString(), "$.streamId");
        assertNotNull(streamId);

        // browse：根目录列出 sub/ 与 anime ep01.mp4，self 排除、目录在前
        mvc.perform(post("/api/webdav/browse")
                        .header("X-AV-Token", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:" + port + "/dav\",\"username\":\"user\",\"password\":\"pass\",\"path\":\"/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(2))
                .andExpect(jsonPath("$.list[0].dir").value(true))
                .andExpect(jsonPath("$.list[0].name").value("sub"))
                .andExpect(jsonPath("$.list[1].name").value("anime ep01.mp4"))
                .andExpect(jsonPath("$.list[1].size").value(100));

        // stream：Range 透传
        mvc.perform(get("/api/webdav/stream/" + streamId).param("token", token()).header("Range", "bytes=0-9"))
                .andExpect(status().is(206))
                .andExpect(header().string("Content-Range", "bytes 0-9/100"))
                .andExpect(content().bytes("0123456789".getBytes(StandardCharsets.UTF_8)));

        // 错误凭据 → 400 + message
        mvc.perform(post("/api/webdav/open")
                        .header("X-AV-Token", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:" + port + "/dav\",\"username\":\"bad\",\"password\":\"creds\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("WebDAV 账号或密码不正确（上游 401）"));
    }

    @Test
    void nonHttpUrlRejected() throws Exception {
        mvc.perform(post("/api/webdav/open")
                        .header("X-AV-Token", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://x/y\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browseWithCjkAndSpacePathIsEncoded() throws Exception {
        // 原始路径含中文/空格：服务端逐段百分号编码后 PROPFIND（此前 URI.create 直接炸）
        mvc.perform(post("/api/webdav/browse")
                        .header("X-AV-Token", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:" + port + "/dav\",\"username\":\"user\",\"password\":\"pass\",\"path\":\"/番剧 A/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/番剧 A/"))
                .andExpect(jsonPath("$.list.length()").value(3)); // 假上游返回静态清单（3 条），本用例验证中文/空格路径不再 URI 崩溃
    }
}
