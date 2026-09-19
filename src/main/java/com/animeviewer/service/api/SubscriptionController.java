package com.animeviewer.service.api;

import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.model.Dtos.DownloadTaskDto;
import com.animeviewer.service.model.Dtos.HitBatchDeleteRequest;
import com.animeviewer.service.model.Dtos.HitBatchIgnoreRequest;
import com.animeviewer.service.model.Dtos.HitIgnoreRequest;
import com.animeviewer.service.model.Dtos.SubHitDto;
import com.animeviewer.service.model.Dtos.SubscriptionAddRequest;
import com.animeviewer.service.model.Dtos.SubscriptionDto;
import com.animeviewer.service.model.Dtos.SubscriptionSettingsDto;
import com.animeviewer.service.model.Dtos.SubscriptionUpdateRequest;
import com.animeviewer.service.subscription.SubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** v0.19 SU1/SU2/SU3 订阅自动化 API：条目级订阅 CRUD / 手动全量检索 / 命中审核（下载·忽略·忽略字幕组）/
 *  命中台账查询 / 全局设置；通知汇总挂 /api/downloads/summary（下载入口侧边栏角标 + toast 数据源）。 */
@RestController
public class SubscriptionController {

    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/api/subscriptions")
    public Map<String, List<SubscriptionDto>> list() {
        return Map.of("items", service.list());
    }

    @PostMapping("/api/subscriptions")
    public SubscriptionDto subscribe(@RequestBody SubscriptionAddRequest req) {
        if (req == null || req.subjectId() == null) throw new DownloadException(400, "subjectId 必填");
        return service.subscribe(req.subjectId(), req.subjectName(), req.subjectNameCn(),
                req.minEpisode(), req.autoScore());
    }

    @PutMapping("/api/subscriptions/{id}")
    public SubscriptionDto update(@PathVariable long id, @RequestBody(required = false) SubscriptionUpdateRequest req) {
        return service.update(id, req == null ? null : req.autoScore(), req == null ? null : req.minEpisode(),
                req == null ? null : req.aiKeywords(), req == null ? null : req.rssUrl());
    }

    @DeleteMapping("/api/subscriptions/{id}")
    public Map<String, String> unsubscribe(@PathVariable long id) {
        service.unsubscribe(id);
        return Map.of("message", "已取消订阅");
    }

    /** 手动全量检索（忽略间隔到期判定）；返回 { checked, hits } */
    @PostMapping("/api/subscriptions/check")
    public Map<String, Integer> checkAllNow() {
        return Map.of("hits", service.checkAllNow());
    }

    @GetMapping("/api/subscriptions/hits")
    public Map<String, List<SubHitDto>> hits(@RequestParam(value = "status", required = false) String status,
                                             @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return Map.of("items", service.listHits(status, limit));
    }

    /** 一键下载命中资源（202 = 已受理入队） */
    @PostMapping("/api/subscriptions/hits/{id}/accept")
    public ResponseEntity<DownloadTaskDto> accept(@PathVariable long id) {
        return ResponseEntity.accepted().body(service.accept(id));
    }

    @PostMapping("/api/subscriptions/hits/{id}/ignore")
    public Map<String, String> ignore(@PathVariable long id, @RequestBody(required = false) HitIgnoreRequest req) {
        service.ignore(id, req == null ? null : req.blockFansub());
        return Map.of("message", "已忽略");
    }

    /** 批量忽略（多选/全选取消）：返回 { ignored, skipped }（skipped=不存在或已处理的条数） */
    @PostMapping("/api/subscriptions/hits/batch-ignore")
    public Map<String, Long> batchIgnore(@RequestBody(required = false) HitBatchIgnoreRequest req) {
        return service.batchIgnore(req == null ? null : req.ids());
    }

    /** 批量删除命中历史（多选）：仅删除已处理命中，待确认跳过；返回 { deleted, skipped } */
    @PostMapping("/api/subscriptions/hits/batch-delete")
    public Map<String, Long> batchDelete(@RequestBody(required = false) HitBatchDeleteRequest req) {
        return service.batchDeleteHits(req == null ? null : req.ids());
    }

    /** 清空命中历史（全部非待确认命中）：返回 { deleted } */
    @PostMapping("/api/subscriptions/hits/clear-history")
    public Map<String, Long> clearHistory() {
        return Map.of("deleted", service.clearHitHistory());
    }

    /** v0.22 AI1：手动判定命中语义（存量无判定命中/复核用）；返回判定后的命中 DTO */
    @PostMapping("/api/subscriptions/hits/{id}/ai-judge")
    public SubHitDto aiJudgeHit(@PathVariable long id) {
        return service.judgeHitNow(id);
    }

    /** v0.22 AI2：AI 生成订阅扩展检索词（LLM 候选缓存到订阅行，前端可再编辑）；返回更新后的订阅 DTO */
    @PostMapping("/api/subscriptions/{id}/ai-keywords")
    public SubscriptionDto aiKeywords(@PathVariable long id) {
        return service.generateHitKeywords(id);
    }

    @GetMapping("/api/subscriptions/settings")
    public SubscriptionSettingsDto settings() {
        return service.getSettings();
    }

    @PutMapping("/api/subscriptions/settings")
    public SubscriptionSettingsDto updateSettings(@RequestBody SubscriptionSettingsDto dto) {
        return service.updateSettings(dto);
    }

    /** SU3 通知汇总：待确认数（侧边栏角标）+ 最近命中/最近完成（toast 判定） */
    @GetMapping("/api/downloads/summary")
    public Object summary() {
        return service.summary();
    }

    /** 统一业务错误（400/404/409/502/503）——与 DownloadController 同映射 */
    @ExceptionHandler(DownloadException.class)
    public ResponseEntity<?> handleDownload(DownloadException e) {
        return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
    }
}
