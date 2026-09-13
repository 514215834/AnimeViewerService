package com.animeviewer.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** v0.16 DN1 aria2 JSON-RPC 薄客户端（java.net.http + Jackson，零新依赖）。
 *  数值字段 aria2 一律以字符串返回，asLong 统一转换；gid 不存在抛 GidNotFoundException
 *  供 watcher 触发任务恢复；其余错误归一 Aria2Exception。 */
public class Aria2Client {

    public static class Aria2Exception extends RuntimeException {
        public Aria2Exception(String message) { super(message); }
    }

    /** 引擎侧查无此 gid（引擎重启 / 任务被移除）——watcher 据此重新入队恢复 */
    public static class GidNotFoundException extends Aria2Exception {
        public GidNotFoundException(String message) { super(message); }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final String rpcUrl;
    private final String secret;

    public Aria2Client(String rpcUrl, String secret) {
        this.rpcUrl = rpcUrl;
        this.secret = secret == null ? "" : secret;
    }

    /** 每次调用独立 HttpClient（RPC 低频，1.5s 一次 watcher）：引擎重启后旧 keep-alive 连接半死时，
     *  复用池化连接会在连接获取阶段无限等待（request timeout 不覆盖该阶段），且 synchronized call
     *  会串死所有下载端点（v0.17 验收实测）——不复用即根治。 */
    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    private synchronized JsonNode call(String method, Object... params) {
        try {
            Object[] all = new Object[params.length + 1];
            all[0] = "token:" + secret;
            System.arraycopy(params, 0, all, 1, params.length);
            String body = MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "av", "method", method, "params", all));
            HttpRequest req = HttpRequest.newBuilder(URI.create(rpcUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(res.body());
            JsonNode err = root.get("error");
            if (err != null) {
                String msg = err.path("message").asText("");
                if (msg.contains("is not found") || msg.contains("Cannot be removed")) throw new GidNotFoundException(msg);
                throw new Aria2Exception("aria2 " + method + " 失败: " + msg);
            }
            JsonNode result = root.get("result");
            if (result == null || result.isMissingNode()) {
                // 非 JSON-RPC 响应（如错误页 / RPC 路径不对）——给出可诊断的失败而非 NPE
                throw new Aria2Exception("aria2 " + method + " 响应缺少 result（请检查 RPC 地址与路径）");
            }
            return result;
        } catch (Aria2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new Aria2Exception("aria2 RPC 不可达: " + e.getMessage());
        }
    }

    public String version() {
        return call("aria2.getVersion").path("version").asText("");
    }

    public String addUri(List<String> uris, Map<String, Object> options) {
        return call("aria2.addUri", uris, options).asText();
    }

    /** addTorrent：torrent 为 .torrent 文件内容的 base64（v0.16：服务端直抓种子内容，绕开
     *  addUri(.torrent 链接) 的“种子文件 gid + 负载新 gid”二段行为）；uris 为附加 web seed */
    public String addTorrent(String torrentBase64, List<String> uris, Map<String, Object> options) {
        return call("aria2.addTorrent", torrentBase64, uris, options).asText();
    }

    /** @param keys 空列表 = 全字段 */
    public Map<String, Object> tellStatus(String gid, List<String> keys) {
        JsonNode r = keys == null || keys.isEmpty() ? call("aria2.tellStatus", gid) : call("aria2.tellStatus", gid, keys);
        return MAPPER.convertValue(r, Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> tellList(String method, List<String> keys) {
        JsonNode r = keys == null || keys.isEmpty() ? call(method) : call(method, keys);
        return MAPPER.convertValue(r, List.class);
    }

    /** tellWaiting / tellStopped 带 (offset, num) 分页参数 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> tellPaged(String method, int offset, int num, List<String> keys) {
        JsonNode r = call(method, offset, num, keys);
        return MAPPER.convertValue(r, List.class);
    }

    public void forcePause(String gid) { call("aria2.forcePause", gid); }

    public void unpause(String gid) { call("aria2.unpause", gid); }

    public void forceRemove(String gid) { call("aria2.forceRemove", gid); }

    public void changeOption(String gid, Map<String, Object> options) { call("aria2.changeOption", gid, options); }

    public void purgeDownloadResult() { call("aria2.purgeDownloadResult"); }

    public void shutdown() {
        try {
            call("aria2.shutdown");
        } catch (Exception ignored) {
            // 进程可能已被外部关闭
        }
    }

    /* ── 类型转换（aria2 数值均为字符串）── */

    public static long asLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return 0;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String asString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
