package com.jobpilot.delivery;

import com.jobpilot.job51.Job51JobCard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanySizeFilterTest {
    private String reject(String published, int minimum, int maximum) {
        Job51JobCard card = new Job51JobCard();
        card.setBrandScaleName(published);
        PlatformConfig config = new PlatformConfig();
        config.setMinCompanySize(minimum);
        config.setMaxCompanySize(maximum);
        return RequirementFilter.reject(card, config);
    }

    @Test
    void disabledFilterPreservesExistingBehavior() {
        assertThat(reject(null, 0, 0)).isNull();
        assertThat(reject("10000人以上", 0, 0)).isNull();
    }

    @Test
    void wholePublishedRangeMustFit() {
        assertThat(reject("100-499人", 100, 499)).isNull();
        assertThat(reject("50-150人", 100, 499)).contains("不在所选人数范围内");
        assertThat(reject("500-999人", 100, 499)).isNotNull();
        assertThat(reject("1000人以上", 100, 0)).isNull();
        assertThat(reject("1000人以上", 100, 9999)).isNotNull();
        assertThat(reject("20人以下", 0, 99)).isNull();
    }

    @Test
    void recognizedPlatformFormats() {
        assertThat(reject("1,000—9,999人", 1000, 9999)).isNull();
        assertThat(reject("１－２万人", 10000, 20000)).isNull();
        assertThat(reject("1.5万以上", 15000, 0)).isNull();
        assertThat(reject("500人", 500, 500)).isNull();
    }

    @Test
    void activeFilterSkipsUnknownAndInvalidRanges() {
        for (String scale : new String[]{null, "", "保密", "1000-100人", "规模不限", "500家分店"}) {
            assertThat(reject(scale, 100, 0)).contains("未公开或无法识别");
        }
    }
}
