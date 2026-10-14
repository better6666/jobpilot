package com.jobpilot.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 样本是虚构的，但形态照着真实问题造：模型把人设原样背进每一条话术，
 * 开头各不相同，中段反复是同几个短语
 */
class OverusedPhrasesTest {

    private static final List<String> PARROTED = List.of(
            "我参与了两段新媒体运营实习，用剪映和Excel完成了多次账号数据复盘。",
            "我了解到云帆科技的内容运营岗位，凭借新媒体运营实习积累的剪映和Excel经验，我主动投递此职位。",
            "青禾文化正在招聘活动策划，我作为市场营销专业的毕业生，通过两段新媒体运营实习积累了经验，也熟练运用剪映和Excel。",
            "我注意到木棉家居的品牌专员岗位需要剪映和Excel，我是市场营销专业毕业生，有两段新媒体运营实习经历。",
            "我注意到远山集团招聘市场助理，我是市场营销专业，曾完成两段新媒体运营实习，熟练使用剪映和Excel。",
            "您好，我是市场营销专业的毕业生，曾在两段新媒体运营实习积累了社群和直播经验，同时熟练运用剪映和Excel。",
            "在市场营销专业的实习中，我参与了两个账号的日常运营，使用剪映和Excel做内容和数据。",
            "我注意到星河教育在招聘课程顾问，作为一名有两段新媒体运营实习经验的市场营销专业人士。",
            "我注意到海川电商的店铺运营岗位，我在市场营销专业完成两段新媒体运营实习，并熟练运用剪映和Excel做数据分析。",
            "我了解到北辰传媒的短视频编导职位十分契合我的背景。作为市场营销专业毕业生，我曾完成两段新媒体运营实习，熟练掌握剪映和Excel。");

    @Test
    void 背人设背出来的短语都找得到() {
        List<String> found = OverusedPhrases.find(PARROTED);
        String joined = String.join("|", found);
        assertTrue(joined.contains("两段新媒体运营实习"), joined);
        assertTrue(joined.contains("市场营销专业"), joined);
        assertTrue(found.size() <= 4);
        for (String a : found) {
            for (String b : found) {
                assertTrue(a.equals(b) || !a.contains(b), "互相包含的只报一个：" + found);
            }
        }
    }

    @Test
    void 各说各话的不报_条数太少不报() {
        assertTrue(OverusedPhrases.find(List.of(
                "云帆科技的内容运营岗位我很想了解", "做过两段新媒体运营，想聊聊短视频方向",
                "请问青禾文化的活动策划还在招吗", "木棉家居的店铺运营我可以直接上手")).isEmpty());
        assertTrue(OverusedPhrases.find(PARROTED.subList(0, 3)).isEmpty());
        assertTrue(OverusedPhrases.find(null).isEmpty());
    }
}
