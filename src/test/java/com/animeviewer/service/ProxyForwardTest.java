package com.animeviewer.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v0.15 O2 流代理转发集成用例：Range 透传（206/后缀区间/越界 416）、m3u8 重写、白名单与 scheme 准入。
 *  上游用 JDK 内置 HttpServer 模拟（Range 手工实现），代理目标 host 配置进白名单。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "av.data-dir=target/test-data",
        "av.scan.auto-on-start=false",
        "av.proxy.allowed-hosts=127.0.0.1,*.trusted-cdn.test"})
class ProxyForwardTest {

    @Autowired
    private MockMvc mvc;

    private static HttpServer upstream;
    private static int port;
    /** 1000 字节可预测 body：body[i] = (byte)('A' + i % 26) */
    private static final byte[] VIDEO_BODY = new byte[1000];
    private static final String PLAYLIST = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0xabc
            #EXTINF:9.009,
            seg-1.ts
            """;

    static {
        for (int i = 0; i < VIDEO_BODY.length; i++) VIDEO_BODY[i] = (byte) ('A' + i % 26);
    }

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/video.bin", ex -> serveRange(ex, VIDEO_BODY, "video/mp4"));
        upstream.createContext("/index.m3u8", ex -> {
            byte[] body = PLAYLIST.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.createContext("/enc.key", ex -> {
            byte[] body = "K".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.createContext("/seg-1.ts", ex -> serveRange(ex, VIDEO_BODY, "video/mp2t"));
        upstream.start();
        port = upstream.getAddress().getPort();
    }

    /** 模拟上游 Range：仅单区间（与浏览器媒体播放实际形态一致） */
    private static void serveRange(HttpExchange ex, byte[] body, String contentType) throws java.io.IOException {
        String range = ex.getRequestHeaders().getFirst("Range");
        String contentRange = null;
        int start = 0, end = body.length - 1, status = 200;
        if (range != null && range.matches("bytes=\\d*-\\d*")) {
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            String s = spec.substring(0, dash).trim();
            String e = spec.substring(dash + 1).trim();
            if (!s.isEmpty()) {
                start = Integer.parseInt(s);
                end = e.isEmpty() ? body.length - 1 : Math.min(Integer.parseInt(e), body.length - 1);
                if (start >= body.length) {
                    ex.getResponseHeaders().set("Content-Range", "bytes */" + body.length);
                    ex.sendResponseHeaders(416, -1);
                    return;
                }
                status = 206;
                contentRange = "bytes " + start + "-" + end + "/" + body.length;
            } else if (!e.isEmpty()) {
                long n = Long.parseLong(e);
                start = (int) Math.max(0, body.length - n);
                status = 206;
                contentRange = "bytes " + start + "-" + (body.length - 1) + "/" + body.length;
                end = body.length - 1;
            }
        }
        ex.getResponseHeaders().set("Content-Type", contentType);
        if (contentRange != null) ex.getResponseHeaders().set("Content-Range", contentRange);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        byte[] slice = java.util.Arrays.copyOfRange(body, start, end + 1);
        if (status == 200) ex.sendResponseHeaders(200, body.length);
        else ex.sendResponseHeaders(206, slice.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(status == 200 ? body : slice); }
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    private String token() throws Exception {
        return Files.readString(Path.of("target", "test-data", "token")).trim();
    }

    @Test
    void rangePassthroughReturnsSlicedBody() throws Exception {
        mvc.perform(get("/api/proxy").param("token", token())
                        .param("url", "http://127.0.0.1:" + port + "/video.bin")
                        .header("Range", "bytes=100-199"))
                .andExpect(status().is(206))
                .andExpect(header().string("Content-Range", "bytes 100-199/1000"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().bytes(java.util.Arrays.copyOfRange(VIDEO_BODY, 100, 200)));
    }

    @Test
    void suffixRangePassthrough() throws Exception {
        mvc.perform(get("/api/proxy").param("token", token())
                        .param("url", "http://127.0.0.1:" + port + "/video.bin")
                        .header("Range", "bytes=-100"))
                .andExpect(status().is(206))
                .andExpect(header().string("Content-Range", "bytes 900-999/1000"))
                .andExpect(content().bytes(java.util.Arrays.copyOfRange(VIDEO_BODY, 900, 1000)));
    }

    @Test
    void fullRequestReturnsAllAndProxiedHeader() throws Exception {
        mvc.perform(get("/api/proxy").param("token", token())
                        .param("url", "http://127.0.0.1:" + port + "/video.bin"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-AV-Proxied", "stream"))
                .andExpect(content().bytes(VIDEO_BODY));
    }

    @Test
    void playlistRewrittenWithProxyUrlsAndToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/proxy").param("token", token())
                        .param("url", "http://127.0.0.1:" + port + "/index.m3u8"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-AV-Proxied", "playlist"))
                .andExpect(content().contentTypeCompatibleWith("application/vnd.apple.mpegurl"))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // KEY 与分片均被改写为本服务代理地址且带 token；IV 保留
        assertTrue(body.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"http://localhost/api/proxy?token=" + token() + "&url="), body);
        assertTrue(body.contains("%3A" + "%2F%2F"), body);
        assertTrue(body.contains("IV=0xabc"), body);
        assertTrue(body.contains("#EXTINF:9.009,"), body);
        assertTrue(Pattern.compile("seg-1\\.ts", Pattern.CASE_INSENSITIVE).matcher(body).find());
        // 明文上游地址不应再出现在清单中
        assertTrue(!body.contains("URI=\"enc.key\""), body);
    }

    @Test
    void hostOutsideWhitelistRejected() throws Exception {
        mvc.perform(get("/api/proxy").param("token", token())
                        .param("url", "http://evil.example.net/video.bin"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wildcardWhitelistAcceptedThenUpstreamFailureIs502() throws Exception {
        // *.trusted-cdn.test 在白名单：通过准入后因上游不可达失败（502），证明不是 403 拦截
        mvc.perform(get("/api/proxy").param("token", token())
                        .param("url", "http://a.trusted-cdn.test:1/x"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void nonHttpSchemeRejected() throws Exception {
        mvc.perform(get("/api/proxy").param("token", token())
                        .param("url", "ftp://127.0.0.1/file"))
                .andExpect(status().isBadRequest());
    }
}
