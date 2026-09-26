package com.animeviewer.service.api;

import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.model.Dtos.AiSiteFillDto;
import com.animeviewer.service.model.Dtos.AiSiteFillRequest;
import com.animeviewer.service.model.Dtos.DownloadTaskDto;
import com.animeviewer.service.model.Dtos.ResourceAddRequest;
import com.animeviewer.service.model.Dtos.ResourceSearchDto;
import com.animeviewer.service.model.Dtos.ResourceSiteDto;
import com.animeviewer.service.model.Dtos.ResourceSiteTestDto;
import com.animeviewer.service.resource.ResourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** v0.17 R1/R2 资源发现 API：RSS 多源搜索 / 站点注册制管理 / 结果一键入下载队列。 */
@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    /** 搜索：keyword 必填；sites 可选（缺省全部已注册源）；返回 items（去重降序）+ sites（含自定义）+ error（各源失败原因汇总） */
    @GetMapping("/search")
    public ResourceSearchDto search(@RequestParam("keyword") String keyword,
                                    @RequestParam(value = "sites", required = false) List<String> sites) {
        return service.search(keyword, sites);
    }

    @GetMapping("/sites")
    public Map<String, List<ResourceSiteDto>> sites() {
        return Map.of("sites", service.listSites());
    }

    @PostMapping("/sites")
    public Map<String, List<ResourceSiteDto>> saveSite(@RequestBody ResourceSiteDto site) {
        service.saveSite(site);
        return Map.of("sites", service.listSites());
    }

    /** v0.24 SM3 站点测试连通（不入库）：抓取 + 解析 → ok/条目数/前 3 条样例，失败原因透传（恒 200） */
    @PostMapping("/sites/test")
    public ResourceSiteTestDto testSite(@RequestBody ResourceSiteDto site) {
        return service.testSite(site.baseUrl(), site.searchTemplate(), null);
    }

    /** v0.30 补记一：AI 解析站点接入配置（站点管理「AI 解析」按钮——规则映射优先 + LLM 兜底，结果仅预填人工把关） */
    @PostMapping("/sites/ai-fill")
    public AiSiteFillDto aiFillSite(@RequestBody(required = false) AiSiteFillRequest req) {
        if (req == null || req.text() == null || req.text().isBlank()) {
            throw new DownloadException(400, "请先粘贴站点地址");
        }
        return service.aiFillSite(req.text());
    }

    @DeleteMapping("/sites/{key}")
    public Map<String, List<ResourceSiteDto>> removeSite(@PathVariable String key) {
        service.removeSite(key);
        return Map.of("sites", service.listSites());
    }

    /** 资源一键入队（复用 v0.16 下载链路；202 = 已受理，409 = 已存在任务） */
    @PostMapping("/enqueue")
    public ResponseEntity<DownloadTaskDto> enqueue(@RequestBody ResourceAddRequest req) {
        return ResponseEntity.accepted().body(service.enqueue(req));
    }

    /** 统一业务错误（400/404/409/502/503）——与 DownloadController 同映射 */
    @ExceptionHandler(DownloadException.class)
    public ResponseEntity<?> handleDownload(DownloadException e) {
        return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
    }
}
