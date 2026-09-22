package com.animeviewer.service.api;

import com.animeviewer.service.download.DownloadException;
import com.animeviewer.service.hanime.HanimeService;
import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.model.Dtos.HanimeConfigDto;
import com.animeviewer.service.model.Dtos.HanimeConfigUpdateRequest;
import com.animeviewer.service.model.Dtos.HanimeSearchResult;
import com.animeviewer.service.model.Dtos.HanimeTestDto;
import com.animeviewer.service.model.Dtos.HanimeWatchDto;
import com.animeviewer.service.stream.RangeForwarder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** v0.26 HN1/HN3 hanime1.me 在线解析 API：配置 / 连通测试 / 搜索 / 视频解析 / 流转发。
 *  鉴权由 TokenAuthFilter 全局兜住（/api/**）——流端点 <video> 无法带自定义请求头，token 走
 *  查询参数（与 /api/stream、字幕端点同口径）。流转发经 RangeForwarder.forwardFailover：
 *  服务端直连→代理容灾（CDN 在 DNS 污染网络直连不可达，实测经代理 206），并携带解析时同款
 *  UA + Referer（签名直链在时效内无需 Referer，带上仅为对齐浏览器行为）。
 *  v0.28 P3（§5O A4 技术债销账）：stream 加并发闸（av.stream.hanime-max-concurrent，默认 2）——
 *  多标签页/异常客户端不再拖垮宿主机；thumb 不限（小流量 + 浏览器签名图缓存）。 */
@RestController
@RequestMapping("/api/hanime")
public class HanimeController {

    private final HanimeService service;
    private final RangeForwarder forwarder;
    private final Semaphore streamPermits;
    private final int maxStreamConcurrent;

    public HanimeController(HanimeService service, RangeForwarder forwarder, ServiceProperties props) {
        this.service = service;
        this.forwarder = forwarder;
        this.maxStreamConcurrent = props.stream() == null || props.stream().hanimeMaxConcurrent() == null
                ? 2 : Math.max(1, props.stream().hanimeMaxConcurrent());
        this.streamPermits = new Semaphore(maxStreamConcurrent);
    }

    @GetMapping("/config")
    public HanimeConfigDto config() {
        return service.configDto();
    }

    @PutMapping("/config")
    public HanimeConfigDto saveConfig(@RequestBody HanimeConfigUpdateRequest req) {
        service.saveConfig(req.enabled(), req.ua(), req.cookie());
        return service.configDto();
    }

    /** 连通测试（恒 200，ok=false 时 message 为三分类原因文案） */
    @PostMapping("/test")
    public HanimeTestDto test() {
        return service.test();
    }

    /** 搜索：query 可空（空 = 按 sort 浏览「最新」）；genre/sort 透传站点原生参数；page 从 1 起 */
    @GetMapping("/search")
    public HanimeSearchResult search(@RequestParam(value = "query", required = false) String query,
                                     @RequestParam(value = "genre", required = false) String genre,
                                     @RequestParam(value = "sort", required = false) String sort,
                                     @RequestParam(value = "page", required = false) Integer page) {
        return service.search(query, genre, sort, page);
    }

    /** 视频解析（签名直链有时效 → 前端播放统一走 /stream，本端点供清晰度菜单与详情展示） */
    @GetMapping("/watch/{videoCode}")
    public HanimeWatchDto watch(@PathVariable String videoCode) {
        return service.watch(videoCode);
    }

    /** 视频流转发：Range 透传 + 直连/代理容灾；res 缺省播最高档。
     *  v0.28 P3 并发闸：acquire 于转发前（等待 10s 后 503——转发会话分钟级、短排队仍有意义），
     *  release 于 finally（客户端断开异常路径同样释放，A1 isClientAbort 上抛不漏）。 */
    @GetMapping("/stream/{videoCode}")
    public void stream(@PathVariable String videoCode,
                       @RequestParam(value = "res", required = false) Integer res,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException, InterruptedException {
        boolean acquired;
        try {
            acquired = streamPermits.tryAcquire(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            response.sendError(503, "在线流转发并发已达上限（" + maxStreamConcurrent + "），请关闭其他播放页后重试");
            return;
        }
        try {
            String url = service.resolveStreamUrl(videoCode, res);
            HanimeService.Config cfg = service.config();
            forwarder.forwardFailover(url, request.getHeader("Range"), response, null, Map.of(
                    "User-Agent", cfg.ua(),
                    "Referer", "https://hanime1.com/"
            ));
        } finally {
            streamPermits.release();
        }
    }

    /** v0.26 补记 缩略图/海报转发：浏览器直连站点 CDN 在 DNS 污染网络不可达——经服务端容灾抓取
     *  （直连→代理，与视频同通道）。宿主白名单防开放代理/SSRF；Cache-Control 让浏览器缓存
     *  同一签名图（签名 ~9 天有效）。 */
    @GetMapping("/thumb")
    public void thumb(@RequestParam("url") String url,
                      HttpServletResponse response) throws IOException, InterruptedException {
        String target = service.validateImageUrl(url);
        HanimeService.Config cfg = service.config();
        response.setHeader("Cache-Control", "public, max-age=86400");
        forwarder.forwardFailover(target, null, response, null, Map.of(
                "User-Agent", cfg.ua(),
                "Referer", "https://hanime1.com/"
        ));
    }

    /** 统一业务错误（400/404/502）——与 ResourceController 同映射 */
    @ExceptionHandler(DownloadException.class)
    public ResponseEntity<?> handleDownload(DownloadException e) {
        return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
    }
}
