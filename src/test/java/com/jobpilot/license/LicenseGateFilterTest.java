package com.jobpilot.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门禁过滤器测试。
 *
 * 这是授权体系的安全边界：未激活时必须挡住投递入口（402 而不是漏到 404），
 * 同时必须放行健康检查与激活相关接口，否则用户连激活页都打不开。
 */
@ExtendWith(MockitoExtension.class)
class LicenseGateFilterTest {

    @Mock
    private LicenseService licenseService;

    @Mock
    private FilterChain chain;

    private LicenseGateFilter filter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new LicenseGateFilter(licenseService, new ObjectMapper());
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest post(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setServletPath(uri);
        return request;
    }

    @Test
    void 未激活时投递入口返回402且不到达后续链路() throws Exception {
        when(licenseService.isAllowed()).thenReturn(false);
        when(licenseService.status()).thenReturn(statusOf(LicenseState.UNACTIVATED));

        filter.doFilter(post("/api/boss/start"), response, chain);

        assertEquals(402, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));
        assertTrue(response.getContentAsString().contains("卡密未激活或已失效"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 任意平台投递入口都被拦截() throws Exception {
        when(licenseService.isAllowed()).thenReturn(false);
        when(licenseService.status()).thenReturn(statusOf(LicenseState.EXPIRED));

        filter.doFilter(post("/api/boss/start"), response, chain);
        assertEquals(402, response.getStatus());

        MockHttpServletResponse another = new MockHttpServletResponse();
        filter.doFilter(post("/api/liepin/start"), another, chain);
        assertEquals(402, another.getStatus());

        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 已激活时投递入口放行() throws Exception {
        when(licenseService.isAllowed()).thenReturn(true);

        filter.doFilter(post("/api/boss/start"), response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void 非投递入口即使未激活也放行() throws Exception {
        // /api/license/activate 不以 /start 结尾，门禁条件短路，根本不问授权状态
        filter.doFilter(post("/api/license/activate"), response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(any(), any());
        verify(licenseService, never()).isAllowed();
    }

    @Test
    void 非api路径不经过门禁() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/license.html");
        request.setServletPath("/license.html");
        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(any(), any());
        verify(licenseService, never()).isAllowed();
    }

    private LicenseStatus statusOf(LicenseState state) {
        LicenseStatus status = new LicenseStatus();
        status.setEnabled(true);
        status.setState(state);
        status.setAllowed(false);
        status.setMessage("测试状态");
        return status;
    }
}
