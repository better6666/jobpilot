package com.jobpilot.license;

import com.jobpilot.common.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 授权控制器测试。
 *
 * 重点覆盖入参校验：空卡密必须被挡在 Service 之前，
 * 且 HTTP 语义正确（200 + success:false，不是 500）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LicenseControllerTest {

    @Mock
    private LicenseService licenseService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LicenseController(licenseService, null, new LicenseProperties(), null, null)).build();
    }

    @Test
    void 空卡密激活返回失败且不调用Service() throws Exception {
        mockMvc.perform(post("/api/license/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardKey\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("卡密不能为空"));

        verifyNoInteractions(licenseService);
    }

    @Test
    void 请求体缺失时同样被拦截() throws Exception {
        mockMvc.perform(post("/api/license/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(licenseService);
    }

    @Test
    void 激活成功返回状态() throws Exception {
        LicenseStatus status = new LicenseStatus();
        status.setState(LicenseState.ACTIVE);
        status.setAllowed(true);
        when(licenseService.activate("GK-TEST-KEY")).thenReturn(status);

        mockMvc.perform(post("/api/license/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardKey\":\"GK-TEST-KEY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.allowed").value(true));

        verify(licenseService).activate("GK-TEST-KEY");
    }

    @Test
    void 服务端拒绝时转成可读消息() throws Exception {
        when(licenseService.activate("GK-BAD"))
                .thenThrow(new LicenseClient.LicenseServerException(402, "CARD_NOT_FOUND", "卡密不存在"));

        mockMvc.perform(post("/api/license/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardKey\":\"GK-BAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("卡密不存在"));
    }

    @Test
    void 状态查询直接透传() throws Exception {
        LicenseStatus status = new LicenseStatus();
        status.setState(LicenseState.UNACTIVATED);
        status.setAllowed(false);
        when(licenseService.status()).thenReturn(status);

        mockMvc.perform(get("/api/license/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("UNACTIVATED"))
                .andExpect(jsonPath("$.data.allowed").value(false));
    }

    @Test
    void 解绑成功返回状态() throws Exception {
        LicenseStatus status = new LicenseStatus();
        status.setState(LicenseState.UNACTIVATED);
        when(licenseService.unbind()).thenReturn(status);

        mockMvc.perform(post("/api/license/unbind"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").value("UNACTIVATED"));
    }

    @Test
    void 解绑时服务端不可达返回失败消息() throws Exception {
        when(licenseService.unbind())
                .thenThrow(new LicenseClient.LicenseUnavailableException("无法连接卡密服务端，请稍后再试"));

        mockMvc.perform(post("/api/license/unbind"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("无法连接卡密服务端，请稍后再试"));
    }

    @Test
    void 解绑接口不要求请求体() throws Exception {
        when(licenseService.unbind()).thenReturn(new LicenseStatus());

        mockMvc.perform(post("/api/license/unbind").content("{}").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
