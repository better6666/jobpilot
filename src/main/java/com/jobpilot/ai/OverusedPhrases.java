package com.jobpilot.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 找出最近话术里被反复说的那几个短语，写进提示词让模型别再说。
 *
 * <p>真实数据：AI 生成的话术每条都在复述「服装陈列与展示设计专业」「两季橱窗陈列实习」
 * 「PS和CAD」——人设里写了什么，模型就原样背一遍。开头不同（{@code avoidOpeners}
 * 管的那层）挡不住这个，因为撞的是句子中段。
 *
 * <p>做法：去掉标点后切 {@value #GRAM} 字的片段，在最近 {@value #WINDOW} 条里
 * 出现过至少 {@value #MIN_DOCS} 条的算"热片段"；同一条话术里相邻的热片段
 * 拼回成完整短语，按出现条数排序取前 {@value #MAX_PHRASES} 个。
 */
final class OverusedPhrases {

    private static final int WINDOW = 10;
    private static final int GRAM = 5;
    private static final int MIN_DOCS = 4;
    private static final int MAX_PHRASES = 4;
    /** 报给模型的短语最长这么多字 */
    private static final int MAX_PHRASE_LEN = 16;

    private OverusedPhrases() {
    }

    static List<String> find(List<String> recent) {
        if (recent == null || recent.size() < MIN_DOCS) {
            return List.of();
        }
        List<String> docs = new ArrayList<>();
        for (String g : recent.subList(0, Math.min(WINDOW, recent.size()))) {
            String n = GreetingService.normalize(g);
            if (n.length() >= GRAM) {
                docs.add(n);
            }
        }
        Map<String, Integer> docFreq = new HashMap<>();
        for (String d : docs) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i + GRAM <= d.length(); i++) {
                seen.add(d.substring(i, i + GRAM));
            }
            for (String g : seen) {
                docFreq.merge(g, 1, Integer::sum);
            }
        }
        // 每条话术里把连续的热片段拼回短语，短语的热度 = 它在几条话术里出现
        Map<String, Integer> phrases = new LinkedHashMap<>();
        for (String d : docs) {
            Set<String> inThisDoc = new HashSet<>();
            int i = 0;
            while (i + GRAM <= d.length()) {
                if (docFreq.getOrDefault(d.substring(i, i + GRAM), 0) < MIN_DOCS) {
                    i++;
                    continue;
                }
                int end = i + GRAM;
                while (end < d.length()
                        && docFreq.getOrDefault(d.substring(end - GRAM + 1, end + 1), 0) >= MIN_DOCS) {
                    end++;
                }
                // 整句都是模板时会拼出一长串，截成前一段：模型只需要知道"别再说这个"
                inThisDoc.add(d.substring(i, Math.min(end, i + MAX_PHRASE_LEN)));
                i = end;
            }
            for (String p : inThisDoc) {
                phrases.merge(p, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(phrases.entrySet());
        ranked.sort((a, b) -> b.getValue() != a.getValue().intValue()
                ? b.getValue() - a.getValue() : b.getKey().length() - a.getKey().length());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ranked) {
            String p = e.getKey();
            if (out.stream().anyMatch(o -> o.contains(p) || p.contains(o))) {
                continue;
            }
            out.add(p);
            if (out.size() >= MAX_PHRASES) {
                break;
            }
        }
        return out;
    }
}
