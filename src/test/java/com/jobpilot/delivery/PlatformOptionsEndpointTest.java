package com.jobpilot.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.boss.BossController;
import com.jobpilot.boss.BossOptions;
import com.jobpilot.boss.BossService;
import com.jobpilot.job51.Job51Controller;
import com.jobpilot.job51.Job51Options;
import com.jobpilot.job51.Job51Service;
import com.jobpilot.liepin.LiepinController;
import com.jobpilot.liepin.LiepinOptions;
import com.jobpilot.liepin.LiepinService;
import com.jobpilot.license.LicenseService;
import com.jobpilot.zhilian.ZhilianController;
import com.jobpilot.zhilian.ZhilianOptions;
import com.jobpilot.zhilian.ZhilianService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 四个平台的 /options 接口冒烟。
 *
 * 这条用例抓的是一个真实发生过的 bug：job51-options.json 的顶层键写成 jobArea，
 * 而 PlatformOptions.cities() 按 city 取——管理页城市下拉整个空了，而单个平台
 * 的码表单测发现不了（它只测自己文件里的常量）。这里把四个 /options 全打一遍，
 * 任何平台的码表键名、码位数写错都会红。
 */
@WebMvcTest({
        LiepinController.class, Job51Controller.class, ZhilianController.class, BossController.class
})
@Import({LiepinOptions.class, Job51Options.class, ZhilianOptions.class, BossOptions.class})
class PlatformOptionsEndpointTest {

    @TestConfiguration
    static class RealOptionsConfig {
        @Bean
        LiepinOptions liepinOptions(ObjectMapper om) { return new LiepinOptions(om); }

        @Bean
        Job51Options job51Options(ObjectMapper om) { return new Job51Options(om); }

        @Bean
        ZhilianOptions zhilianOptions(ObjectMapper om) { return new ZhilianOptions(om); }

        @Bean
        BossOptions bossOptions(ObjectMapper om) { return new BossOptions(om); }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LiepinService liepinService;

    @MockitoBean
    private Job51Service job51Service;

    @MockitoBean
    private ZhilianService zhilianService;

    @MockitoBean
    private BossService bossService;

    @MockitoBean
    private LicenseService licenseService;

    private Map<String, Object> data(String platform) throws Exception {
        String body = mvc.perform(get("/api/" + platform + "/options"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> cities(String platform) throws Exception {
        Map<String, Object> data = data(platform);
        assertNotNull(data, platform + " 的 /options 没有 data");
        return (List<Map<String, String>>) data.get("cities");
    }

    @Test
    void 四个平台的城市码表都非空且条目完整唯一() throws Exception {
        for (String platform : List.of("boss", "liepin", "job51", "zhilian")) {
            List<Map<String, String>> list = cities(platform);
            assertFalse(list.isEmpty(), platform + " 城市码表是空的");
            for (Map<String, String> c : list) {
                assertFalse(c.get("name").isBlank(), platform + " 有条目名为空");
                assertFalse(c.get("code").isBlank(), platform + " 有条目码为空");
            }
            long distinct = list.stream().map(c -> c.get("code")).distinct().count();
            assertEquals(list.size(), distinct, platform + " 城市码有重复，管理页下拉会选串");
        }
    }

    @Test
    void 每个平台都有一个不限全国的入口() throws Exception {
        // Boss/51job/智联叫"不限"，猎聘叫"全国"——没有这个入口用户就没法搜全国
        assertTrue(cities("boss").stream().anyMatch(c -> "不限".equals(c.get("name"))));
        assertTrue(cities("liepin").stream().anyMatch(c -> "全国".equals(c.get("name"))));
        assertTrue(cities("job51").stream().anyMatch(c -> "不限".equals(c.get("name"))));
        assertTrue(cities("zhilian").stream().anyMatch(c -> "不限".equals(c.get("name"))));
    }

    @Test
    void 猎聘和51job还带薪资码表智联不带() throws Exception {
        // 智联的薪资是原始金额区间，管理页渲染成文本框，所以 /options 里没有 salary 组
        assertTrue(data("liepin").containsKey("salary"));
        assertTrue(data("job51").containsKey("salary"));
        assertFalse(data("zhilian").containsKey("salary"));
    }

    @Test
    void 各平台城市码位数符合平台自己的约定() throws Exception {
        // Boss 9 位、51job 6 位、智联 3 位；猎聘是内部码，位数不固定所以跳过
        assertDigits("boss", 9);
        assertDigits("job51", 6);
        assertDigits("zhilian", 3);
    }

    private void assertDigits(String platform, int expected) throws Exception {
        for (Map<String, String> c : cities(platform)) {
            if (!"0".equals(c.get("code"))) {
                assertEquals(expected, c.get("code").length(),
                        platform + " 码 " + c.get("code") + " 不是 " + expected + " 位");
            }
        }
    }
}
