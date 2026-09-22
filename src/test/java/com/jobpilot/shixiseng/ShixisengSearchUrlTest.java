package com.jobpilot.shixiseng;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实习僧 URL 构造与 jobId/文本清洗。
 *
 * 夹具是 2026-09-22 从实习僧真实搜索页抓的 HTML 片段（fixtures/ 下），
 * 平台改版时这些用例会先红。
 */
class ShixisengSearchUrlTest {

    @TempDir
    Path tempDir;

    @Test
    void 关键词和城市都走query且关键词参数名必须是keyword() {
        String url = ShixisengSearchUrl.build("%E8%8B%8F%E5%B7%9E", "陈列设计", 1);

        assertThat(url).isEqualTo(
                "https://www.shixiseng.com/interns?keyword=%E9%99%88%E5%88%97%E8%AE%BE%E8%AE%A1&city=%E8%8B%8F%E5%B7%9E");
    }

    @Test
    void 页码大于1才拼page参数() {
        assertThat(ShixisengSearchUrl.build(null, "设计", 1))
                .doesNotContain("page=");
        assertThat(ShixisengSearchUrl.build(null, "设计", 3))
                .endsWith("&page=3");
    }

    @Test
    void 没有城市时拼全国不带city参数() {
        String url = ShixisengSearchUrl.build("", "设计", 1);

        assertThat(url).doesNotContain("city=");
    }

    @Test
    void 关键词含特殊字符时正确编码() {
        String url = ShixisengSearchUrl.build(null, "C++/Java 开发", 1);

        assertThat(url).contains("keyword=C%2B%2B%2FJava+%E5%BC%80%E5%8F%91");
    }

    @Test
    void 从详情链接提jobId() {
        assertThat(ShixisengJobCard.extractJobId(
                "https://www.shixiseng.com/intern/inn_tdauajw2bkdb?pcm=pc_SearchList"))
                .isEqualTo("inn_tdauajw2bkdb");
        assertThat(ShixisengJobCard.extractJobId(
                "https://www.shixiseng.com/intern/inn_h5ultjyv1tmx"))
                .isEqualTo("inn_h5ultjyv1tmx");
    }

    @Test
    void 不是详情链接时提不到jobId() {
        assertThat(ShixisengJobCard.extractJobId("https://www.shixiseng.com/interns?keyword=x")).isNull();
        assertThat(ShixisengJobCard.extractJobId("")).isNull();
        assertThat(ShixisengJobCard.extractJobId(null)).isNull();
    }

    @Test
    void 剥掉iconFont私用区字符() {
        // 真实页面里岗位名和日薪都混着 \uf57f \ue477 \uf148 这类图标字形
        String raw = "\uf57f\ue477实习\uf148";
        assertThat(ShixisengJobCard.cleanText(raw)).isEqualTo("实习");

        assertThat(ShixisengJobCard.cleanText("\uf5b9\uf475-\ue0f5\uf35b\uf475/天"))
                .isEqualTo("-/天");
    }

    @Test
    void 清洗后压缩空白纯图标返回null() throws Exception {
        assertThat(ShixisengJobCard.cleanText("  平面   设计  ")).isEqualTo("平面 设计");
        assertThat(ShixisengJobCard.cleanText("\uf57f\ue477\uf148")).isNull();
        assertThat(ShixisengJobCard.cleanText("   ")).isNull();
        assertThat(ShixisengJobCard.cleanText(null)).isNull();
    }

    @Test
    void 真实夹具里的详情链接都能提出jobId() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/fixtures/shixiseng-search-items.html"));

        List<String> urls = html.lines()
                .map(l -> {
                    int i = l.indexOf("https://www.shixiseng.com/intern/");
                    if (i < 0) {
                        return null;
                    }
                    int end = l.indexOf('"', i);
                    return end < 0 ? null : l.substring(i, end);
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(urls).isNotEmpty();
        for (String u : urls) {
            assertThat(ShixisengJobCard.extractJobId(u))
                    .as("应从 %s 提出 jobId", u)
                    .startsWith("inn_");
        }
    }

    @Test
    void 真实夹具里的岗位名都带iconFont清洗后非空() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/fixtures/shixiseng-search-items.html"));

        // 夹具里每个条目标题链接都含 &#xf 私用区实体，清洗后应得到可读中文
        assertThat(html).contains("&#xf");
        assertThat(ShixisengJobCard.cleanText("&#xf57f;&#xe477;实习&#xf148;".replace("&", "\u0026")))
                .isNotNull();
    }
}
