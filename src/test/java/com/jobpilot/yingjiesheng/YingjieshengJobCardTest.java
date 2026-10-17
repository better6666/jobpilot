package com.jobpilot.yingjiesheng;

import com.jobpilot.delivery.PlatformConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 夹具的字段结构取自 q.yingjiesheng.com /open/noauth/job/search 的真实响应，HR 姓名等个人字段已换成虚构值 */
class YingjieshengJobCardTest {

    private List<YingjieshengJobCard> cards() throws Exception {
        return YingjieshengJobCard.parseList(
                Files.readString(Path.of("src/test/resources/fixtures/yingjiesheng-search.json")));
    }

    @Test
    void 接口字段全部落到卡片上() throws Exception {
        YingjieshengJobCard c = cards().get(0);
        assertEquals("173666568", c.getJobId());
        assertEquals("美团-工具运营", c.getJobName());
        assertEquals("1.5-2.5万", c.getSalaryDesc());
        assertEquals("北京", c.getCityName());
        assertNull(c.getAreaDistrict());
        assertEquals("无需经验", c.getJobExperience());
        assertNull(c.getJobDegree(), "空串学历要当成没有，不能落成空字符串");
        assertEquals("美团", c.getBrandName());
        assertEquals("在线生活服务(O2O)", c.getIndustryName());
        assertEquals("10000人以上", c.getBrandScaleName());
        assertEquals("1588397", c.getBossId());
        assertEquals("李女士", c.getBossName());
        assertEquals("2分钟前回复", c.getBossActiveTimeDesc());
        assertNull(c.getPostDescription());
        assertFalse(c.isReprint());
        assertFalse(c.isApplied());
        assertEquals("https://q.yingjiesheng.com/jobdetail/173666568.html", c.getJobUrl());
    }

    @Test
    void 城市和区拆开_行业缺了用indtype_转载和已申请认得出() throws Exception {
        YingjieshengJobCard c = cards().get(1);
        assertEquals("哈尔滨", c.getCityName());
        assertEquals("南岗区", c.getAreaDistrict());
        assertEquals("环保", c.getIndustryName());
        assertTrue(c.isReprint());
        assertTrue(c.isApplied());
    }

    @Test
    void jobId为0的丢掉_坏响应不抛异常() throws Exception {
        assertEquals(2, cards().size());
        assertTrue(YingjieshengJobCard.parseList("not json").isEmpty());
        assertTrue(YingjieshengJobCard.parseList(null).isEmpty());
    }

    @Test
    void 两种详情链接都提得出jobId() {
        assertEquals("173895519", YingjieshengJobCard.extractJobId(
                "https://q.yingjiesheng.com/jobdetail/173895519.html?property={\"jobId\":\"173895519\"}"));
        assertEquals("173666568", YingjieshengJobCard.extractJobId(
                "https://young.yingjiesheng.com/pc/jobdetail?jobid=173666568&property=x"));
        assertNull(YingjieshengJobCard.extractJobId("https://q.yingjiesheng.com/company/1.html"));
    }

    @Test
    void 本地过滤_转载不投_城市按名字比() throws Exception {
        PlatformConfig config = new PlatformConfig();
        config.setCity("北京");
        assertNull(YingjieshengService.localReject(cards().get(0), config));
        assertEquals("转载岗位，要去原网站投", YingjieshengService.localReject(cards().get(1), config));
        YingjieshengJobCard far = cards().get(0);
        far.setCityName("沈阳");
        assertTrue(YingjieshengService.localReject(far, config).startsWith("城市不符"));
    }

    @Test
    void 搜索地址_关键词进路径且空格不变加号() {
        assertEquals("https://q.yingjiesheng.com/jobs/search/%E8%BF%90%E8%90%A5",
                YingjieshengDriver.searchUrl("运营"));
        assertEquals("https://q.yingjiesheng.com/jobs/search/UI%20%E8%AE%BE%E8%AE%A1",
                YingjieshengDriver.searchUrl(" UI 设计 "));
    }

    @Test
    void 认得出被跳到登录页() {
        assertTrue(YingjieshengDriver.isLoginRedirect(
                "https://q.yingjiesheng.com/login?url=https%3A%2F%2Fq.yingjiesheng.com%2Fjobs%2Fsearch%2F%E8%BF%90%E8%90%A5"));
        assertFalse(YingjieshengDriver.isLoginRedirect("https://q.yingjiesheng.com/jobs/search/%E8%BF%90%E8%90%A5"));
    }
}
