package com.jobpilot.zhilian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZhilianSearchUrlTest {

    private ZhilianOptions options;

    @BeforeEach
    void setUp() throws Exception {
        options = new ZhilianOptions(new com.fasterxml.jackson.databind.ObjectMapper());
        options.load();
    }

    private ZhilianProperties.ZhilianConfig config() {
        return new ZhilianProperties.ZhilianConfig();
    }

    @Test
    void 城市码走query参数jl且页码也在query里() {
        String url = ZhilianSearchUrl.build("639", "陈列设计", config(), 1);
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("陈列设计"), url);
    }

    @Test
    void 城市码为空时是全国不是jl0() {
        // 智联没有全国这个码，jl0 会搜出空结果，必须整个省掉 jl
        String url = ZhilianSearchUrl.build("", "陈列设计", config(), 3);
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&kw=" + encoded("陈列设计")
                + "&page=3", url);

        url = ZhilianSearchUrl.build(null, "陈列设计", config(), 1);
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&kw=" + encoded("陈列设计"), url);
    }

    @Test
    void 关键词必须走query() {
        // 旧版靠输入框触发，SPA 一跳就把 sl 丢了；现在关键词也拼在 query 上，服务端渲染
        String url = ZhilianSearchUrl.build("639", "陈列设计", config(), 1);
        assertTrue(url.contains("kw=" + encoded("陈列设计")), url);

        // 有空格和 & 也不能把 URL 打断
        String weird = ZhilianSearchUrl.build("639", "AI 运营&增长 #1", config(), 1);
        assertTrue(weird.contains("kw=" + encoded("AI 运营&增长 #1")), weird);
        assertEquals(1, weird.chars().filter(c -> c == '?').count(), weird);
    }

    @Test
    void 第一页省略page() {
        assertFalse(ZhilianSearchUrl.build("639", "x", config(), 1).contains("page="),
                ZhilianSearchUrl.build("639", "x", config(), 1));
        assertTrue(ZhilianSearchUrl.build("639", "x", config(), 2).endsWith("page=2"));
    }

    @Test
    void 页码从1起且0和负数都归到1() {
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("x"),
                ZhilianSearchUrl.build("639", "x", config(), 0));
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("x"),
                ZhilianSearchUrl.build("639", "x", config(), -5));
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("x")
                + "&page=7",
                ZhilianSearchUrl.build("639", "x", config(), 7));
    }

    @Test
    void 薪资是原始金额区间且逗号被编码() {
        ZhilianProperties.ZhilianConfig config = config();
        config.setSalary("12000, 20000");
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("陈列设计")
                + "&sl=12000%2C20000",
                ZhilianSearchUrl.build("639", "陈列设计", config, 1));
    }

    @Test
    void 中文逗号和万元单位都被归一() {
        ZhilianProperties.ZhilianConfig config = config();
        config.setSalary("1.2万，2万");
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("陈列设计")
                + "&sl=12000%2C20000",
                ZhilianSearchUrl.build("639", "陈列设计", config, 1));
    }

    @Test
    void 区间写法和k单位都能读() {
        ZhilianProperties.ZhilianConfig config = config();
        config.setSalary("8000-15000");
        assertTrue(ZhilianSearchUrl.build("639", "x", config, 1).endsWith("&sl=8000%2C15000"));

        config.setSalary("12k-20k");
        assertTrue(ZhilianSearchUrl.build("639", "x", config, 1).endsWith("&sl=12000%2C20000"));
    }

    @Test
    void 薪资为不限或空时省略sl() {
        ZhilianProperties.ZhilianConfig config = config();
        config.setSalary("不限");
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("陈列设计"),
                ZhilianSearchUrl.build("639", "陈列设计", config, 1));

        config.setSalary("");
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("陈列设计"),
                ZhilianSearchUrl.build("639", "陈列设计", config, 1));

        config.setSalary("面议");
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("陈列设计"),
                ZhilianSearchUrl.build("639", "陈列设计", config, 1));

        config.setSalary(null);
        assertEquals("https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=" + encoded("陈列设计"),
                ZhilianSearchUrl.build("639", "陈列设计", config, 1));
    }

    private static String encoded(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 城市码原样返回() {
        assertEquals("639", ZhilianSearchUrl.resolveCityCode(options, "639"));
        assertEquals("530", ZhilianSearchUrl.resolveCityCode(options, " 530 "));
    }

    @Test
    void 城市名查码表() {
        assertEquals("639", ZhilianSearchUrl.resolveCityCode(options, "苏州"));
        assertEquals("530", ZhilianSearchUrl.resolveCityCode(options, "北京"));
    }

    @Test
    void 不存在的城市返回null() {
        assertNull(ZhilianSearchUrl.resolveCityCode(options, "月球基地"));
        assertNull(ZhilianSearchUrl.resolveCityCode(options, ""));
        assertNull(ZhilianSearchUrl.resolveCityCode(options, null));
    }

    @Test
    void 其他平台位数的码粘过来一律查码表而不是原样透传() {
        // Boss 的 9 位、51job 的 6 位都不是 3 位，必须查码表（查不到就报错）
        assertNull(ZhilianSearchUrl.resolveCityCode(options, "101020100"));
        assertNull(ZhilianSearchUrl.resolveCityCode(options, "070300"));
        // 猎聘的码恰好也是 3 位，从位数上区分不出来，只能由城市名校验兜底
        assertEquals("010", ZhilianSearchUrl.resolveCityCode(options, "010"));
    }
}
