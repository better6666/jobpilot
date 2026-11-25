package com.jobpilot.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 本机界面同源访问；下载站的激活页只允许访问授权接口，避免外部页面读取本地简历和 AI 配置。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest request = (HttpServletRequest) req;
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        String host = request.getServerName();
        if (!java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null) {
            String own = request.getScheme() + "://" + host + ":" + request.getServerPort();
            boolean same = origin.equals(own) || request.getServerPort() == 80 && origin.equals("http://" + host);
            boolean activation = origin.equals("https://cheshi.dpdns.org") && request.getRequestURI().startsWith("/api/license/");
            if (!same && !activation) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        chain.doFilter(req, res);
    }
}
