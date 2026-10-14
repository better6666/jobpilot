package com.jobpilot.ai;

import com.jobpilot.yingjiesheng.YingjieshengJobCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 人设是虚构的一段典型写法：专业 + 实习 + 技能 + 意向城市 */
class LocalGreetingComposerTest {

    private static final String PERSONA = "市场营销专业，做过两段新媒体运营实习，会用剪映和 Excel，意向杭州。";

    private static YingjieshengJobCard card(String company, String job) {
        YingjieshengJobCard c = new YingjieshengJobCard();
        c.setBrandName(company);
        c.setJobName(job);
        return c;
    }

    @Test
    void 背景拆成我字开头的短句_意向城市不进话术() {
        List<String> facts = LocalGreetingComposer.facts(PERSONA, null);
        assertEquals(List.of("我是市场营销专业的", "我做过两段新媒体运营实习", "我会用剪映和 Excel"), facts);
    }

    @Test
    void 连拼五十条没有两条一样_也都点到了岗位() {
        List<String> sent = new ArrayList<>();
        Random rnd = new Random(42);
        for (int i = 0; i < 50; i++) {
            String g = LocalGreetingComposer.compose(card("木棉家居", "店铺运营"), PERSONA, sent, rnd);
            assertNotNull(g, "第 " + (i + 1) + " 条拼不出来");
            assertFalse(sent.contains(g), "重复了：" + g);
            assertTrue(g.contains("店铺运营"), g);
            assertFalse(g.contains("杭州"), "意向城市不该写进话术：" + g);
            assertTrue(g.length() <= 90, g);
            assertFalse(g.contains("，。") || g.contains("。。") || g.contains("也我"), "拼接痕迹：" + g);
            sent.add(0, g);
        }
        for (int i = 0; i + 1 < sent.size(); i++) {
            String a = sent.get(i), b = sent.get(i + 1);
            assertNotEquals(a.substring(a.lastIndexOf('。', a.length() - 2) + 1),
                    b.substring(b.lastIndexOf('。', b.length() - 2) + 1), "相邻两条结尾一样：" + a + " / " + b);
        }
        // 同一条背景要点别连着三条都说
        for (int i = 0; i + 2 < sent.size(); i++) {
            boolean allMajor = sent.get(i).contains("营销专业") && sent.get(i + 1).contains("营销专业")
                    && sent.get(i + 2).contains("营销专业");
            boolean allIntern = sent.get(i).contains("新媒体运营实习") && sent.get(i + 1).contains("新媒体运营实习")
                    && sent.get(i + 2).contains("新媒体运营实习");
            assertFalse(allMajor && allIntern, "连着三条背景一模一样：" + sent.subList(i, i + 3));
        }
        Set<String> openers = new HashSet<>();
        for (String g : sent) {
            openers.add(g.substring(0, 3));
        }
        assertTrue(openers.size() >= 4, "开头也要有变化：" + openers);
    }

    @Test
    void 和岗位相关的背景优先说() {
        YingjieshengJobCard c = card("青禾集团", "活动策划");
        c.setPostDescription("会用剪映剪短视频，负责活动海报和社群日常");
        int toolFirst = 0;
        Random rnd = new Random(7);
        for (int i = 0; i < 30; i++) {
            String g = LocalGreetingComposer.compose(c, PERSONA, List.of(), rnd);
            int tool = g.indexOf("剪映");
            int major = g.indexOf("专业");
            int intern = g.indexOf("实习");
            if (tool >= 0 && (major < 0 || tool < major) && (intern < 0 || tool < intern)) {
                toolFirst++;
            }
        }
        assertTrue(toolFirst >= 20, "JD 要剪映时多数话术应先说会剪映，实际 " + toolFirst + "/30");
    }

    @Test
    void 第二条要点是专业时换个接法() {
        Random rnd = new Random(5);
        for (int i = 0; i < 20; i++) {
            String joined = LocalGreetingComposer.joinSecond("我是市场营销专业的", rnd);
            assertTrue(joined.equals("，学的是市场营销专业") || joined.equals("，本身是市场营销专业的"), joined);
        }
        assertTrue(LocalGreetingComposer.joinSecond("我会用剪映和 Excel", rnd).matches("^，(也|另外|还)会用剪映和 Excel$"));
    }

    @Test
    void 没有背景时返回空交给固定话术() {
        assertNull(LocalGreetingComposer.compose(card("云帆科技", "运营"), "  ", List.of(), new Random(1)));
        assertNull(LocalGreetingComposer.compose(card("云帆科技", "运营"), "意向杭州", List.of(), new Random(1)));
    }

    @Test
    void 没有公司名也能拼() {
        String g = LocalGreetingComposer.compose(card(null, "社群助理"), PERSONA, List.of(), new Random(3));
        assertTrue(g.contains("社群助理"), g);
        assertFalse(g.contains("{"), g);
    }
}
