package com.jobpilot.delivery;

import com.jobpilot.job51.Job51JobCard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "我的条件"硬过滤。用例里的学历/经验文案都是五家平台上真实出现过的写法，
 * 归一化一旦漏掉某种写法，表现是"该拦的没拦"或"能投的全被拦"——后者更致命，
 * 所以正反两个方向都要测。
 */
class RequirementFilterTest {

    private static JobCard card(String degree, String experience) {
        Job51JobCard card = new Job51JobCard();
        card.setJobName("陈列设计");
        card.setJobDegree(degree);
        card.setJobExperience(experience);
        return card;
    }

    private static PlatformConfig mine(String degree, String experience) {
        PlatformConfig config = new PlatformConfig();
        config.setMyDegree(degree);
        config.setMyExperience(experience);
        return config;
    }

    @Test
    void 没填我的条件时一个都不拦() {
        assertThat(RequirementFilter.reject(card("硕士", "10年以上"), mine("", ""))).isNull();
    }

    @Test
    void 要求高于我的学历时拦下并写清原因() {
        assertThat(RequirementFilter.reject(card("硕士及以上", "1-3年"), mine("本科", "")))
                .isEqualTo("要求硕士，高于你的本科");
        assertThat(RequirementFilter.reject(card("统招本科", "1-3年"), mine("大专", "")))
                .isEqualTo("要求本科，高于你的大专");
    }

    @Test
    void 要求低于或等于我的学历照样能投() {
        assertThat(RequirementFilter.reject(card("大专", ""), mine("本科", ""))).isNull();
        assertThat(RequirementFilter.reject(card("本科", ""), mine("本科", ""))).isNull();
        assertThat(RequirementFilter.reject(card("高中", ""), mine("本科", ""))).isNull();
    }

    /** 各家写法不一样是常态：漏一种就是整类岗位判不出来，只能放行不能误拦 */
    @Test
    void 学历文案的各种写法都认得() {
        assertThat(RequirementFilter.reject(card("研究生", ""), mine("本科", ""))).isNotNull();
        assertThat(RequirementFilter.reject(card("学士", ""), mine("大专", ""))).isNotNull();
        assertThat(RequirementFilter.reject(card("一本", ""), mine("大专", ""))).isNotNull();
        assertThat(RequirementFilter.reject(card("专科", ""), mine("大专", ""))).isNull();
        assertThat(RequirementFilter.reject(card("中技", ""), mine("大专", ""))).isNull();
    }

    @Test
    void 学历不限和采不到都按没有要求处理() {
        assertThat(RequirementFilter.reject(card("学历不限", ""), mine("初中及以下", ""))).isNull();
        assertThat(RequirementFilter.reject(card("无学历要求", ""), mine("初中及以下", ""))).isNull();
        assertThat(RequirementFilter.reject(card(null, ""), mine("初中及以下", ""))).isNull();
        // "计算机专业"这种根本不是学历字段，认不出来就放行
        assertThat(RequirementFilter.reject(card("计算机", ""), mine("初中及以下", ""))).isNull();
    }

    @Test
    void 经验要求超过我的年限时拦下() {
        assertThat(RequirementFilter.reject(card("大专", "3-5年"), mine("", "1-3年")))
                .isEqualTo("要求3-5年经验，超过你的1-3年");
        assertThat(RequirementFilter.reject(card("大专", "10年以上"), mine("", "1-3年"))).isNotNull();
        // 单值写法"5年"落到 5-10年 档
        assertThat(RequirementFilter.reject(card("大专", "5年"), mine("", "3-5年"))).isNull();
        assertThat(RequirementFilter.reject(card("大专", "5年"), mine("", "1-3年"))).isNotNull();
    }

    @Test
    void 应届和无经验门槛最低谁都能投() {
        assertThat(RequirementFilter.reject(card("大专", "经验不限"), mine("", "1年以下"))).isNull();
        assertThat(RequirementFilter.reject(card("大专", "无需经验"), mine("", "1年以下"))).isNull();
        assertThat(RequirementFilter.reject(card("大专", "在校生/应届生"), mine("", "1年以下"))).isNull();
        assertThat(RequirementFilter.reject(card("大专", "实习"), mine("", "1年以下"))).isNull();
    }

    @Test
    void 我的条件是应届时只放开无门槛岗位() {
        assertThat(RequirementFilter.reject(card("大专", "1-3年"), mine("", "在校生/应届生"))).isNotNull();
        assertThat(RequirementFilter.reject(card("大专", "应届生"), mine("", "在校生/应届生"))).isNull();
    }
}
