package com.animeviewer.service.hanime;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** v0.33（§5U）校验代理根网关：把 hanime1.me 的根相对 URL 空间整体铺到本服务根路径。
 *  背景：CF 编排脚本 history.replaceState 到根路径后，动态创建的脚本 src 按「站点根」解析，
 *  `<base>` 注入管不住（脚本落到 /cdn-cgi/** 于本服务 404 → 质询页正文全靠脚本注入 → 空白页，
 *  2026-10-11 真浏览器实测复现）。根网关让 /cdn-cgi/**、/?__cf_chl_tk=… 等天然落在代理里——
 *  与真实站点的 URL 形态一致。
 *
 *  匹配面：/** 兜底（Spring 特定模式优先，/api/** 业务控制器不受影响；/error 错误转发显式排除）。
 *  鉴权边界（如实登记）：非 /api 路径本就不过 TokenAuthFilter，根网关无 Token（弹窗内站点
 *  自构造的 URL 无法携带凭据）——转发目标硬编码 hanime1.me（无开放代理/SSRF 面），服务默认仅
 *  绑定 127.0.0.1，局域网暴露时该网关允许同网段设备经此浏览该站点，部署指南已提示。 */
@RestController
public class HanimeGatewayController {

    private final HanimeVerifyService verifyService;

    public HanimeGatewayController(HanimeVerifyService verifyService) {
        this.verifyService = verifyService;
    }

    @RequestMapping(value = "/**", method = { RequestMethod.GET, RequestMethod.POST })
    public void gateway(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Spring 错误转发（/error）不是站点路径：非网关职责，交回默认错误呈现
        if (request.getAttribute("jakarta.servlet.error.request_uri") != null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String path = request.getRequestURI();
        verifyService.forward(request, response, path == null || path.isBlank() ? "/" : path);
    }
}
