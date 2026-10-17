package com.jobpilot.job51;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 码义见 Job51Codes 的注释（2026-10-17 真站逐码实测） */
class Job51CodesTest {

    @Test
    void 按我的学历换算_不高于我的各档加学历不限() {
        assertEquals("07,01,02,03", Job51Codes.degree("", "大专"));
        assertEquals("07,01,02,03", Job51Codes.degree("0", "专科"));
        assertEquals("07,01,02,03,04", Job51Codes.degree(null, "本科"));
        assertEquals("07,01,02", Job51Codes.degree("", "高中/中专"));
        assertEquals("07,01,02,03,04,05,06", Job51Codes.degree("", "博士"));
        assertNull(Job51Codes.degree("", ""));
        assertNull(Job51Codes.degree("0", null));
    }

    @Test
    void 下拉框选了新码就用下拉框的() {
        assertEquals("07,01,02,03,04", Job51Codes.degree("07,01,02,03,04", "大专"));
    }

    @Test
    void 旧码表的学历值全部当没选() {
        for (String legacy : new String[]{"02,03,04,05,06,07", "03,04,05,06,07", "04,05,06,07", "05,06,07", "06,07", "07"}) {
            assertNull(Job51Codes.degree(legacy, ""), legacy);
            assertEquals("07,01,02,03", Job51Codes.degree(legacy, "大专"), legacy);
        }
    }

    @Test
    void 经验旧值补上无需经验() {
        assertEquals("01,06", Job51Codes.experience("01"));
        assertEquals("01,06,07,02", Job51Codes.experience("01,02"));
        assertEquals("01,06", Job51Codes.experience("01,06"));
        assertNull(Job51Codes.experience("0"));
        assertNull(Job51Codes.experience(""));
    }
}
