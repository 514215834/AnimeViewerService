package com.animeviewer.service.api;

import com.animeviewer.service.download.DownloadEngine;
import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.download.DownloadService;
import com.animeviewer.service.model.Dtos.DownloadAddRequest;
import com.animeviewer.service.model.Dtos.DownloadRemoveRequest;
import com.animeviewer.service.model.Dtos.DownloadSelectionRequest;
import com.animeviewer.service.model.Dtos.DownloadSettingsDto;
import com.animeviewer.service.model.Dtos.DownloadTaskDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** v0.16 DN1/DN3 下载中心 API：任务列表/入队/详情/控制/文件勾选/删除 + 引擎状态与设置。
 *  v0.18 QB1：引擎操作经 DownloadService 内部路由（aria2/qBittorrent），控制器不再感知具体引擎。 */
@RestController
@RequestMapping("/api/downloads")
public class DownloadController {

    private final DownloadService service;

    public DownloadController(DownloadService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("tasks", service.list());
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody DownloadAddRequest req) {
        return ResponseEntity.accepted().body(service.enqueue(req));
    }

    @GetMapping("/{id}")
    public DownloadTaskDto detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/pause")
    public Map<String, String> pause(@PathVariable long id) {
        service.pause(id);
        return Map.of("message", "已暂停");
    }

    @PostMapping("/{id}/resume")
    public Map<String, String> resume(@PathVariable long id) {
        service.resume(id);
        return Map.of("message", "已恢复");
    }

    @PostMapping("/{id}/selection")
    public Map<String, String> selection(@PathVariable long id, @RequestBody DownloadSelectionRequest req) {
        service.applySelection(id, req);
        return Map.of("message", "文件选择已应用");
    }

    @DeleteMapping("/{id}")
    public Map<String, String> remove(@PathVariable long id,
                                      @RequestBody(required = false) DownloadRemoveRequest req) {
        boolean deleteFiles = req != null && Boolean.TRUE.equals(req.deleteFiles());
        service.remove(id, deleteFiles);
        return Map.of("message", deleteFiles ? "任务已删除（文件已一并删除）" : "任务已删除（文件保留）");
    }

    @GetMapping("/engine")
    public Object engine() {
        return service.engineInfo();
    }

    @PostMapping("/engine/restart")
    public Object restart() {
        return service.restartEngine();
    }

    @GetMapping("/settings")
    public DownloadSettingsDto settings() {
        return service.getSettings();
    }

    @PutMapping("/settings")
    public DownloadSettingsDto updateSettings(@RequestBody DownloadSettingsDto dto) {
        return service.updateSettings(dto);
    }

    /** 统一业务错误（400/404/409/502/503） */
    @ExceptionHandler(DownloadException.class)
    public ResponseEntity<?> handleDownload(DownloadException e) {
        return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
    }
}
