package com.jobpilot.resume;

import java.util.List;

public record ResumeProfile(String summary, List<String> suitableRoles, List<String> skills,
                            List<String> strengths, List<Improvement> improvements,
                            List<String> missingInformation, String persona) {
    public record Improvement(String priority, String issue, String evidence, String suggestion, String example) { }

    public void validate() {
        bounded(summary, 1800, true);
        bounded(persona, 4000, true);
        list(suitableRoles, 12);
        list(skills, 30);
        list(strengths, 12);
        list(missingInformation, 15);
        if (improvements == null || improvements.isEmpty() || improvements.size() > 15) invalid();
        for (Improvement item : improvements) {
            if (item == null) invalid();
            bounded(item.priority(), 20, true);
            bounded(item.issue(), 500, true);
            bounded(item.evidence(), 900, false);
            bounded(item.suggestion(), 1400, true);
            bounded(item.example(), 1400, false);
        }
    }

    private static void list(List<String> items, int max) {
        if (items == null || items.size() > max) invalid();
        items.forEach(item -> bounded(item, 800, true));
    }

    private static void bounded(String value, int max, boolean required) {
        if (value == null || value.length() > max || required && value.isBlank()) invalid();
    }

    private static void invalid() {
        throw new IllegalArgumentException("AI 返回的画像格式不完整，请重试或更换模型");
    }
}
