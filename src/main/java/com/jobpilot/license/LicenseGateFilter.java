package com.jobpilot.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 卡密门禁。
 *
 * 用 Filter 而不是拦截器：Filter 在 DispatcherServlet 之前执行，
 * 即使目标端点还没实现也能挡住，返回 402 而不是 404，语义更准。
 *
 * 只拦各平台 api 目录下的 start 投递入口；配置读取、卡密激活、健康检查一律放行，
 * 保证卡密失效时用户仍能打开激活页看到剩余天数、换卡、解绑。
 */
@Component
@RequiredArgsConstructor
public class LicenseGateFilter extends OncePerRequestFilter {

    private final LicenseService licenseService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/") && uri.endsWith("/start") && !licenseService.isAllowed()) {
            response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.fail("卡密未激活或已失效，请先激活", licenseService.status())));
            return;
        }
        chain.doFilter(request, response);
    }
}
