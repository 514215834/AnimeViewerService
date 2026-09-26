package com.animeviewer.service.ai;

import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.model.Dtos.AiSettingsDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** v0.22 AI0 AI 设置 API：前端设置页读写（存 SQLite settings 表 key=ai；yml 提供默认值）。
 *  ready/callsThisHour/totalCalls 为服务端只读回显（前端据此显示「AI 已就绪」与配额用量），PUT 时忽略。
 *  v0.30 A5 增 POST /api/ai/ping 连通性测试（最小 chat 调用，回显 ok/model/时延）。 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService service;

    public AiController(AiService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public AiSettingsDto settings() {
        return toDto(service.settings());
    }

    @PutMapping("/settings")
    public AiSettingsDto update(@RequestBody AiSettingsDto dto) {
        if (dto == null) throw new DownloadException(400, "请求体不能为空");
        AiSettings saved = service.save(new AiSettings(
                dto.enabled(), dto.baseUrl() == null ? "" : dto.baseUrl().trim(),
                dto.model() == null ? "" : dto.model().trim(),
                dto.apiKey() == null ? "" : dto.apiKey(),
                dto.timeoutSeconds(), dto.maxCallsPerHour(), dto.autoIgnoreNonEpisode(),
                dto.extraHeaders() == null ? "" : dto.extraHeaders(),
                dto.maxTokens(), dto.aiBindThreshold()));
        return toDto(saved);
    }

    /** v0.30 A5：连通性测试（最小 chat 调用，计入小时配额与累计统计；未就绪/失败给可读文案） */
    @PostMapping("/ping")
    public AiService.PingResult ping() {
        return service.ping();
    }

    private AiSettingsDto toDto(AiSettings s) {
        return new AiSettingsDto(s.enabled(), s.baseUrl(), s.model(), s.apiKey(),
                s.timeoutSeconds(), s.maxCallsPerHour(), s.autoIgnoreNonEpisode(),
                s.ready(), service.callsThisHour(), s.extraHeaders(),
                s.maxTokens(), s.aiBindThreshold(), service.totalCalls());
    }

    @ExceptionHandler(DownloadException.class)
    public ResponseEntity<?> handleDownload(DownloadException e) {
        return ResponseEntity.status(e.status).body(java.util.Map.of("message", e.getMessage()));
    }
}
