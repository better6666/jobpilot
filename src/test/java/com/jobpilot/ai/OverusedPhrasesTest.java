package com.jobpilot.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 样本取自本机库 2026-10-13 连续 10 条 Boss 话术（全都在背同一段人设） */
class OverusedPhrasesTest {

    private static final List<String> REAL = List.of(
            "我参与了两季橱窗陈列实习，用PS和CAD工具完成了多次产品展示设计。",
            "我了解到贵公司南康区云栖家具电商美工岗位招聘信息后，凭借服装展示设计实习积累的PS和CAD经验，以及对视觉创意的热情，我主动投递此职位。",
            "文汇文化正在招聘广告设计师，我作为服装陈列与展示设计专业的毕业生，通过两季橱窗陈列实习积累了展示设计经验，也熟练运用PS和CAD工具。",
            "我注意到赣州莳蔓婚礼策划的平面设计岗位需要PS和AI，我是服装陈列与展示设计专业毕业生，有两季橱窗陈列实习经历并会使用PS和CAD软件。",
            "我注意到华昕集团招聘综合平面设计师职位，我是服装陈列与展示设计专业，曾完成两季橱窗陈列实习，熟练使用PS和CAD进行平面设计。",
            "您好，我是服装陈列与展示设计专业的毕业生，曾在两季橱窗陈列实习积累了商场美陈和展厅设计经验，同时熟练运用PS和CAD软件。",
            "在服装陈列与展示专业的实习中，我参与了两季橱窗的布置设计，使用PS和CAD工具精心呈现服装产品。",
            "我注意到石城县小斌电子产品店在招聘校长副校长，作为一名有两季橱窗陈列实习经验的服装陈列与展示设计专业人士。",
            "我注意到赣州吾刻科技有限公司的电商美工设计岗位，我在服装陈列与展示设计专业完成两季橱窗陈列实习，并熟练运用PS和CAD工具进行视觉设计。",
            "我了解到海悦家居的电商视觉设计主管职位十分契合我的背景。作为服装陈列与展示设计专业毕业生，我曾完成两季橱窗陈列实习，熟练掌握PS和CAD软件。");

    @Test
    void 背人设背出来的短语都找得到() {
        List<String> found = OverusedPhrases.find(REAL);
        String joined = String.join("|", found);
        assertTrue(joined.contains("两季橱窗陈列实习"), joined);
        assertTrue(joined.contains("服装陈列与展示设计专业"), joined);
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
                "美团的工具运营岗位我很想了解", "做过两季橱窗陈列，想聊聊视觉方向",
                "请问华昕的平面设计还在招吗", "海悦家居的电商主图我可以直接上手")).isEmpty());
        assertTrue(OverusedPhrases.find(REAL.subList(0, 3)).isEmpty());
        assertTrue(OverusedPhrases.find(null).isEmpty());
    }
}
