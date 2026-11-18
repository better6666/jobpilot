package com.jobpilot.resume;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.zip.ZipInputStream;

@Component
public class ResumeTextExtractor {
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_TEXT = 20000;
    private static final int MAX_EXTRACTED = 200000;
    public record Text(String text, String warning) { }

    public Text extract(String extension, byte[] data) {
        if (data.length == 0 || data.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("简历文件须在 0～10 MB 之间");
        }
        try {
            String raw = switch (extension.toLowerCase(Locale.ROOT)) {
                case "pdf" -> pdf(data);
                case "docx" -> docx(data);
                case "doc" -> word(data);
                case "txt" -> decode(data);
                default -> throw new IllegalArgumentException("请选择 PDF、DOC、DOCX 或 TXT 简历");
            };
            String text = normalize(raw);
            if (text.isBlank()) {
                return new Text("", "未识别到文字；可在内容预览中粘贴简历文本后分析，原附件仍完整保留。");
            }
            return text.length() <= MAX_TEXT ? new Text(text, "")
                    : new Text(text.substring(0, MAX_TEXT), "简历较长，分析文本保留前 20000 字；可修正预览内容，原附件仍完整保留。");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("简历暂时无法解析；请确认文件未损坏、未加密，或另存为文字 PDF / DOCX 后重试");
        }
    }

    private static String pdf(byte[] data) throws Exception {
        if (data.length < 5 || !new String(data, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new IllegalArgumentException("文件内容与 PDF 扩展名不一致");
        }
        try (PDDocument document = Loader.loadPDF(data)) {
            if (document.getNumberOfPages() > 50) throw new IllegalArgumentException("简历 PDF 请控制在 50 页以内");
            PDFTextStripper stripper = new PDFTextStripper() {
                private int count;
                @Override protected void writeString(String text) throws java.io.IOException {
                    count += text.length();
                    if (count > MAX_EXTRACTED) throw new java.io.IOException("简历文本过长");
                    super.writeString(text);
                }
            };
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static String word(byte[] data) throws Exception {
        if (data.length < 8 || data[0] != (byte) 0xd0 || data[1] != (byte) 0xcf) {
            throw new IllegalArgumentException("文件内容与 DOC 扩展名不一致");
        }
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(data))) {
            String text = extractor.getText();
            if (text.length() > MAX_EXTRACTED) throw new IllegalArgumentException("简历文本过长，请精简后导入");
            return text;
        }
    }

    private static String docx(byte[] data) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            int count = 0;
            long skipped = 0;
            byte[] discard = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 3000) throw new IllegalArgumentException("Word 文件结构过大");
                if (!"word/document.xml".equals(entry.getName())) {
                    for (int size; (size = zip.read(discard)) != -1;) {
                        skipped += size;
                        if (skipped > 32_000_000) throw new IllegalArgumentException("Word 附件解压内容过大，请精简后导入");
                    }
                    continue;
                }
                byte[] xml = zip.readNBytes(2_000_001);
                if (xml.length > 2_000_000) throw new IllegalArgumentException("Word 正文过大，请精简后导入");
                XMLInputFactory factory = XMLInputFactory.newFactory();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
                factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
                    throw new XMLStreamException("不读取外部实体");
                });
                var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
                StringBuilder text = new StringBuilder();
                try {
                    while (reader.hasNext()) {
                        int event = reader.next();
                        if (event == XMLStreamConstants.DTD) throw new IllegalArgumentException("Word 正文不能含外部文档定义");
                        if (event == XMLStreamConstants.START_ELEMENT) {
                            if ("t".equals(reader.getLocalName())) text.append(reader.getElementText());
                            if ("tab".equals(reader.getLocalName())) text.append(' ');
                            if ("br".equals(reader.getLocalName())) text.append('\n');
                        }
                        if (event == XMLStreamConstants.END_ELEMENT && "p".equals(reader.getLocalName())) text.append('\n');
                        if (text.length() > MAX_EXTRACTED) throw new IllegalArgumentException("简历文本过长，请精简后导入");
                    }
                } finally { reader.close(); }
                return text.toString();
            }
        }
        throw new IllegalArgumentException("不是有效的 DOCX 文档，缺少正文");
    }

    private static String decode(byte[] data) throws Exception {
        if (data.length >= 2 && (data[0] == (byte) 0xff && data[1] == (byte) 0xfe
                || data[0] == (byte) 0xfe && data[1] == (byte) 0xff)) {
            return StandardCharsets.UTF_16.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        }
        for (byte value : data) if (value == 0) throw new IllegalArgumentException("TXT 文件包含二进制内容，请另存为 UTF-8 文本");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException error) {
            return Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        }
    }

    static String normalize(String raw) {
        return raw.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").strip();
    }
}
