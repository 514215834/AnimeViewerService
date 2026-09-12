package com.animeviewer.service.security;

import com.animeviewer.service.bootstrap.Startup;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** S1 鉴权：除 /api/health 与 CORS 预检外，全部 /api/** 要求配对 Token——
 *  请求头 X-AV-Token（前端 fetch 用）或查询参数 token（<video> 标签无法带自定义头，流地址用 query 传递）。 */
@Component
@Order(2)
public class TokenAuthFilter extends OncePerRequestFilter {

    private final Startup startup;

    public TokenAuthFilter(Startup startup) {
        this.startup = startup;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 健康检查放行（连接测试的第一步）；CORS 预检放行；非 /api 路径放行
        if (path.equals("/api/health")) return true;
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader("X-AV-Token");
        if (provided == null || provided.isBlank()) {
            provided = request.getParameter("token");
        }
        if (!matches(provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Token 缺失或不正确\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String provided) {
        String expected = startup.token();
        if (expected == null || expected.isBlank()) return false;
        if (provided == null || provided.isBlank()) return false;
        // 常量时间比较，防时序侧信道
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
