package com.jobpilot.resume;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class ResumeTextExtractorTest {
    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    static byte[] pdf(boolean text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); document.addPage(page);
            if (text) try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700); content.showText("Java developer with Spring Boot and database project experience."); content.endText();
            }
            document.save(output); return output.toByteArray();
        }
    }

    static byte[] docx(String xml) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return output.toByteArray();
    }

    @Test void readsRealPdfAndFlagsImageOnlyPdfWithoutInventingText() throws Exception {
        assertThat(extractor.extract("pdf", pdf(true)).text()).contains("Java developer", "Spring Boot");
        var scan = extractor.extract("pdf", pdf(false));
        assertThat(scan.text()).isEmpty(); assertThat(scan.warning()).contains("未识别到文字", "原附件");
    }

    @Test void readsParagraphsAndTablesFromDocxInChinese() throws Exception {
        byte[] data = docx("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body>"
                + "<w:p><w:r><w:t>张同学 Java 开发</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>项目：订单管理系统</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "</w:body></w:document>");
        assertThat(extractor.extract("docx", data).text()).contains("张同学 Java 开发\n项目：订单管理系统");
    }

    @Test void readsActualLegacyWordFile() throws Exception {
        byte[] data;
        try (var input = getClass().getResourceAsStream("/fixtures/apache-poi-simple.doc")) { data = input.readAllBytes(); }
        assertThat(extractor.extract("doc", data).text()).containsIgnoringCase("simple");
    }

    @Test void supportsCommonWindowsTextEncodingsAndReportsTruncation() {
        String text = "项目经历：负责订单服务，使用 Java、Spring Boot 和 MySQL。";
        for (Charset charset : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16, Charset.forName("GB18030")}) {
            assertThat(extractor.extract("txt", text.getBytes(charset)).text()).isEqualTo(text);
        }
        var longText = extractor.extract("txt", "项目经历".repeat(6000).getBytes(StandardCharsets.UTF_8));
        assertThat(longText.text()).hasSize(20000); assertThat(longText.warning()).contains("原附件仍完整保留");
    }

    @Test void rejectsFalseFormatsOversizeFilesAndExternalEntities() throws Exception {
        assertThatThrownBy(() -> extractor.extract("pdf", "not a pdf".getBytes())).hasMessageContaining("不一致");
        assertThatThrownBy(() -> extractor.extract("doc", new byte[]{1, 2, 3})).hasMessageContaining("不一致");
        assertThatThrownBy(() -> extractor.extract("txt", new byte[ResumeTextExtractor.MAX_FILE_BYTES + 1])).hasMessageContaining("10 MB");
        byte[] xml = docx("<!DOCTYPE w:document [<!ENTITY secret SYSTEM 'file:///never-read-this-resume-test'>]>"
                + "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:t>&secret;</w:t></w:document>");
        assertThatThrownBy(() -> extractor.extract("docx", xml)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsOverexpandedZipEntriesBeforeTheBody() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/media/unrelated.bin"));
            byte[] chunk = new byte[8192];
            for (int i = 0; i < 4000; i++) zip.write(chunk);
            zip.closeEntry();
        }
        assertThatThrownBy(() -> extractor.extract("docx", output.toByteArray())).hasMessageContaining("解压内容过大");
    }
}
