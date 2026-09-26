package com.animeviewer.service.ai;

import com.animeviewer.service.download.DownloadRepository;
import com.animeviewer.service.ServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v0.22 AI0 Provider 抽象：OpenAI 兼容 chat/completions 客户端（可配 baseUrl/model/apiKey，
 * 兼容本地 Ollama /v1 无鉴权）。
 *
 * 设计不变式（§5J 评审定案）：
 * <ul>
 *   <li>判定一次落库缓存——调用方负责「同一条目只问一次」，不重复烧钱（对齐 infohash 去重哲学）</li>
 *   <li>离线/失败/未开启**静默降级**——返回 null，调用方沿用既有启发式，主链路永不因 AI 中断</li>
 *   <li>小时滚动配额护栏：超限跳过（0=不限）</li>
 * </ul>
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DownloadRepository repo;
    private final HttpClient client;
    private final ServiceProperties props;

    /** 小时滚动配额：窗口起点 + 窗口内调用计数 */
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger windowCalls = new AtomicInteger();

    public AiService(DownloadRepository repo, HttpClient client, ServiceProperties props) {
        this.repo = repo;
        this.client = client;
        this.props = props;
    }

    /** 当前设置（每调用实时读取——设置页改完即时生效） */
    public AiSettings settings() {
        return AiSettings.load(repo.getSetting(AiSettings.STORE_KEY).orElse(null),
                AiSettings.defaults(props));
    }

    public AiSettings save(AiSettings settings) {
        String err = settings.validate();
        if (err != null) throw new com.animeviewer.service.download.DownloadException(400, err);
        repo.putSetting(AiSettings.STORE_KEY, settings.toJson());
        return settings;
    }

    public boolean ready() {
        return settings().ready();
    }

    /** 小时滚动配额：true = 本调用仍在配额内；顺带滚动过期窗口 */
    private boolean quotaAvailable(AiSettings s) {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= 3_600_000L) {
            if (windowStart.compareAndSet(start, now)) windowCalls.set(0);
        }
        if (s.maxCallsPerHour() <= 0) return true;
        if (windowCalls.get() >= s.maxCallsPerHour()) {
            log.info("AI 小时配额已满（{}），跳过本次调用", s.maxCallsPerHour());
            return false;
        }
        return true;
    }

    private void countCall() {
        windowCalls.incrementAndGet();
    }

    public int callsThisHour() {
        long now = System.currentTimeMillis();
        if (now - windowStart.get() >= 3_600_000L) return 0;
        return windowCalls.get();
    }

    /* ── v0.30 A5 累计调用统计（成功 2xx 次数，SQLite settings key=ai_stats 持久化，重启不丢）── */

    public static final String STATS_KEY = "ai_stats";
    private final Object statsLock = new Object();

    /** 纯函数：从统计 JSON 读累计调用数（损坏/缺字段回退 0） */
    static int readTotalCalls(String json) {
        if (json == null || json.isBlank()) return 0;
        try {
            return Math.max(0, MAPPER.readTree(json).path("totalCalls").asInt(0));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 累计成功调用数（服务端只读回显） */
    public int totalCalls() {
        return readTotalCalls(repo.getSetting(STATS_KEY).orElse(null));
    }

    private void bumpTotalCalls() {
        synchronized (statsLock) {
            int total = readTotalCalls(repo.getSetting(STATS_KEY).orElse(null)) + 1;
            repo.putSetting(STATS_KEY, "{\"totalCalls\":" + total + "}");
        }
    }

    /* ── v0.30 A5 连通性测试 ── */

    /** ping 结果：ok=HTTP 2xx 即连通（思考型模型 max_tokens 小会截空 content，故不要求 content 非空） */
    public record PingResult(boolean ok, String model, long latencyMs, String detail) {}

    /** 最小 chat 调用探测通道连通性；计入小时配额与累计统计；未就绪抛 400（控制器转 400 文案） */
    public PingResult ping() {
        AiSettings s = settings();
        if (!s.ready()) throw new com.animeviewer.service.download.DownloadException(
                400, "AI 未启用或未配置（设置页「AI 分析」填写接口地址与模型）");
        if (!quotaAvailable(s)) {
            return new PingResult(false, s.model(), 0, "本小时配额已满（" + s.maxCallsPerHour() + "），未发起调用");
        }
        long t0 = System.currentTimeMillis();
        try {
            HttpResponse<String> resp = client.send(
                    buildRequest(s, "你是连通性探针。无论用户说什么，只回复 pong", "ping", 200),
                    HttpResponse.BodyHandlers.ofString());
            countCall();
            long ms = System.currentTimeMillis() - t0;
            if (resp.statusCode() / 100 == 2) {
                bumpTotalCalls();
                String content = contentOf(resp.body());
                return new PingResult(true, s.model(), ms,
                        content == null || content.isBlank() ? "连通正常（模型未返回文本内容）" : content);
            }
            return new PingResult(false, s.model(), ms, "HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        } catch (Exception e) {
            return new PingResult(false, s.model(), System.currentTimeMillis() - t0, e.toString());
        }
    }

    /**
     * OpenAI 兼容 chat 补全；返回 assistant 文本，失败/超时/非 2xx 返回 null（静默降级，调用方自行兜底）。
     * baseUrl 规范：调用方填根地址（可含 /v1）；此处统一拼 {base}/chat/completions——
     * base 不以 /v1 结尾时自动补 /v1（容忍用户填 https://api.openai.com 与 https://api.openai.com/v1 两种形态）。
     */
    public String chat(String systemPrompt, String userPrompt) {
        AiSettings s = settings();
        if (!s.ready() || !quotaAvailable(s)) return null;
        try {
            HttpResponse<String> resp = client.send(
                    buildRequest(s, systemPrompt, userPrompt, null),
                    HttpResponse.BodyHandlers.ofString());
            countCall();
            if (resp.statusCode() / 100 != 2) {
                log.warn("AI 请求非 2xx: {} {}", resp.statusCode(), truncate(resp.body()));
                return null;
            }
            bumpTotalCalls();
            String content = contentOf(resp.body());
            if (content == null || content.isBlank()) {
                log.warn("AI 响应无 choices[0].message.content: {}", truncate(resp.body()));
                return null;
            }
            return content;
        } catch (Exception e) {
            log.warn("AI 调用失败（静默降级为启发式）: {}", e.toString());
            return null;
        }
    }

    /** 组装 OpenAI 兼容请求体与头（chat 与 ping 共用；maxTokens 0=不注入） */
    private HttpRequest buildRequest(AiSettings s, String system, String user, Integer maxTokensOverride) throws Exception {
        String base = s.baseUrl().replaceAll("/+$", "");
        String url = base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", s.model());
        body.put("temperature", 0);
        int tokens = maxTokensOverride != null ? maxTokensOverride : s.maxTokens();
        if (tokens > 0) body.put("max_tokens", tokens);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        msgs.addObject().put("role", "user").put("content", user);
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(s.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (!s.apiKey().isBlank()) rb.header("Authorization", "Bearer " + s.apiKey());
        // v0.22 自定义请求头（非标网关通道，如 opencode zen 需 x-opencode-session）
        for (String[] h : s.parseHeaders().pairs()) rb.header(h[0], h[1]);
        return rb.build();
    }

    /** 提取 choices[0].message.content（缺失/非文本/非 JSON 返回 null） */
    private static String contentOf(String body) {
        try {
            JsonNode content = MAPPER.readTree(body)
                    .path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 要求模型输出 JSON 的提问：剥 ```json 围栏后解析；失败返回 null */
    public JsonNode askJson(String systemPrompt, String userPrompt) {
        String text = chat(systemPrompt, userPrompt);
        if (text == null) return null;
        return parseLooseJson(text);
    }

    /** 解析 LLM 回文里的 JSON：容忍 ```json 围栏、前后说明文字（取首个 { 起的平衡段）；失败 null */
    public static JsonNode parseLooseJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        // 围栏优先
        java.util.regex.Matcher fence = java.util.regex.Pattern
                .compile("```(?:json)?\\s*([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (fence.find()) t = fence.group(1).trim();
        try {
            return MAPPER.readTree(t);
        } catch (Exception ignore) {
            // 退化：取首个 { 到与之配对的 }（简单深度扫描）
            int start = t.indexOf('{');
            if (start < 0) return null;
            int depth = 0;
            for (int i = start; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        try {
                            return MAPPER.readTree(t.substring(start, i + 1));
                        } catch (Exception e) {
                            return null;
                        }
                    }
                }
            }
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
