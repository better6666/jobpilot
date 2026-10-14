package com.jobpilot.ai;

import com.jobpilot.yingjiesheng.YingjieshengJobCard;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 兜底话术的活用。样本是最常见的那种通用固定话术 */
class FallbackGreetingTest {

    private static final String GENERIC_SAY_HI = "您好！我对这个岗位很感兴趣，我的经历与岗位要求比较匹配，方便发一份简历给您看看吗？期待您的回复。";

    private static YingjieshengJobCard card(String company, String job) {
        YingjieshengJobCard c = new YingjieshengJobCard();
        c.setBrandName(company);
        c.setJobName(job);
        return c;
    }

    @Test
    void 原话里的这个岗位换成真实岗位名() {
        String out = FallbackGreeting.render(GENERIC_SAY_HI, card("云帆科技", "内容运营"), List.of());
        assertEquals("您好！我对内容运营这个岗位很感兴趣，我的经历与岗位要求比较匹配，方便发一份简历给您看看吗？期待您的回复。", out);
    }

    @Test
    void 不同岗位出来的话术不再一样() {
        Set<String> outs = new HashSet<>();
        for (String[] job : new String[][]{{"云帆科技", "内容运营"}, {"青禾集团", "活动策划"}, {"木棉家居", "店铺运营"}}) {
            outs.add(FallbackGreeting.render(GENERIC_SAY_HI, card(job[0], job[1]), List.of()));
        }
        assertEquals(3, outs.size());
    }

    @Test
    void 占位符直接填() {
        assertEquals("您好，我想应聘青禾集团的活动策划，作品集可以随时发您。",
                FallbackGreeting.render("您好，我想应聘{公司}的{岗位}，作品集可以随时发您。", card("青禾集团", "活动策划"), List.of()));
        assertEquals("您好，我想应聘贵司的这个岗位。",
                FallbackGreeting.render("您好，我想应聘{公司}的{岗位}。", card(null, null), List.of()));
    }

    @RepeatedTest(5)
    void 没有泛指也没占位符时补一句点名_您好留在最前面() {
        String out = FallbackGreeting.render("您好！做过两段新媒体运营，可以看看我的作品集。", card("云帆科技", "内容运营"), List.of());
        assertTrue(out.startsWith("您好，"), out);
        assertTrue(out.contains("内容运营"), out);
        assertTrue(out.endsWith("做过两段新媒体运营，可以看看我的作品集。"), out);
        assertFalse(out.contains("您好！"), "问候只留一个：" + out);
    }

    @Test
    void 已经点了名的不再补() {
        String raw = "您好，云帆科技的内容运营我很感兴趣。";
        assertEquals(raw, FallbackGreeting.render(raw, card("云帆科技", "内容运营"), List.of()));
    }

    @Test
    void 多行轮换且避开刚用过的那条() {
        String sayHi = "第一条话术\n第二条话术\n";
        for (int i = 0; i < 20; i++) {
            String out = FallbackGreeting.render(sayHi, card(null, null), List.of("第一条话术"));
            assertEquals("第二条话术", out);
        }
        assertEquals(List.of("第一条话术", "第二条话术"), FallbackGreeting.variants(sayHi));
    }

    @Test
    void 空话术原样返回交给调用方拦() {
        assertNull(FallbackGreeting.render(null, card("云帆科技", "运营"), List.of()));
        assertEquals("", FallbackGreeting.render("", card("云帆科技", "运营"), List.of()));
    }
}
