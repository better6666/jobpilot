package com.jobpilot.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ResumePanel extends JPanel {
    @FunctionalInterface public interface Api { JsonNode request(String method, String path, Object body); }
    private record Item(String id, String name) { @Override public String toString() { return name; } }
    private final Api api;
    private final String base;
    private final Consumer<String> onPersonaApplied;
    private final Runnable onCatalogChanged;
    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private final JTextArea text = area(true);
    private final JTextArea report = area(false);
    private final JTextField targetRole = new JTextField();
    private final JLabel status = new JLabel("导入简历后，可预览文本并开始 AI 分析");
    private final JLabel metadata = new JLabel("支持 PDF、Word（DOC/DOCX）、TXT · 每份最多 10 MB");
    private final JLabel quota = new JLabel();
    private final JButton analyze = new JButton("AI 分析画像");
    private final JButton save = new JButton("保存文本修正");
    private final JButton apply = new JButton("应用为 AI 话术背景");
    private final JButton replace = new JButton("替换所选简历");
    private final JButton remove = new JButton("删除本地副本");
    private final JButton upload = new JButton("导入简历");
    private JsonNode current;
    private String loadedText = "";
    private boolean loading;
    private boolean busy;
    private final Timer timer;

    public ResumePanel(String base, Api api, Consumer<String> onPersonaApplied, Runnable onCatalogChanged) {
        super(new BorderLayout(0, 14));
        this.base = base;
        this.api = api;
        this.onPersonaApplied = onPersonaApplied;
        this.onCatalogChanged = onCatalogChanged;
        setOpaque(false);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        toolbar.setOpaque(false);
        JButton refresh = new JButton("刷新");
        toolbar.add(upload); toolbar.add(replace); toolbar.add(remove); toolbar.add(refresh); toolbar.add(quota);
        add(toolbar, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(42);
        list.setBorder(new EmptyBorder(5, 8, 5, 8));
        JScrollPane files = new JScrollPane(list);
        files.setPreferredSize(new Dimension(210, 400));
        JPanel detail = new JPanel(new BorderLayout(0, 10));
        detail.setOpaque(false);
        metadata.setForeground(new Color(105, 117, 139));
        detail.add(metadata, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("简历文本 · 可修正", new JScrollPane(text));
        tabs.addTab("职业画像与优化建议", new JScrollPane(report));
        detail.add(tabs, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.setOpaque(false);
        JPanel target = new JPanel(new BorderLayout(12, 0));
        target.setOpaque(false);
        target.add(new JLabel("目标岗位（选填）"), BorderLayout.WEST);
        target.add(targetRole, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        buttons.setOpaque(false);
        buttons.add(save); buttons.add(analyze); buttons.add(apply);
        JLabel privacy = new JLabel("<html>点击分析会将当前文本提交给 AI 话术页所选服务；常见电话、邮箱、证件号会先隐藏。<br>可先修正文本或删除不需要提交的信息，原附件保持完整。</html>");
        privacy.setFont(privacy.getFont().deriveFont(12f));
        privacy.setForeground(new Color(105, 117, 139));
        target.setAlignmentX(LEFT_ALIGNMENT); buttons.setAlignmentX(LEFT_ALIGNMENT); privacy.setAlignmentX(LEFT_ALIGNMENT);
        actions.add(target); actions.add(buttons); actions.add(privacy);
        detail.add(actions, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, files, detail);
        split.setBorder(null); split.setResizeWeight(0); split.setDividerLocation(210);
        add(split, BorderLayout.CENTER);
        status.setBorder(new EmptyBorder(8, 2, 2, 2));
        add(status, BorderLayout.SOUTH);
        text.setText("导入简历后，这里显示提取出的文字。扫描 PDF 也可手动补充文字后分析。");
        report.setText("AI 将给出职业方向、已有技能、优势、逐项优化建议和待补充信息。\n画像不会自动覆盖话术背景，请确认后点击“应用为 AI 话术背景”。");
        upload.addActionListener(event -> choose(false));
        replace.addActionListener(event -> choose(true));
        refresh.addActionListener(event -> refresh());
        list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) loadSelected(false); });
        save.addActionListener(event -> saveText());
        analyze.addActionListener(event -> {
            String id = selectedId();
            if (id.isEmpty()) return;
            String content = text.getText();
            String role = targetRole.getText();
            busy = true; controls();
            execute(() -> {
                if (!content.equals(loadedText)) api.request("PUT", path(id, "/text"), Map.of("text", content));
                return api.request("POST", path(id, "/analyze"), Map.of("targetRole", role));
            }, data -> {
                busy = false;
                loadedText = data.path("text").asText();
                if (id.equals(selectedId())) { fill(data, true); tabs.setSelectedIndex(1); }
                controls();
            });
        });
        apply.addActionListener(event -> {
            String id = selectedId();
            if (current == null || current.path("profile").isNull()) return;
            String persona = current.path("profile").path("persona").asText();
            execute(() -> api.request("POST", path(id, "/apply-persona"), null), data -> {
                onPersonaApplied.accept(persona);
                status.setText("已更新 AI 话术背景；接口、Key 和各平台话术选择保持原设置");
            });
        });
        remove.addActionListener(event -> {
            String id = selectedId();
            if (id.isEmpty()) return;
            if (JOptionPane.showConfirmDialog(this, "删除此简历在 JobPilot 中的副本和分析结果？原文件保留。",
                    "删除本地副本", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            execute(() -> api.request("DELETE", path(id, ""), null), data -> {
                current = null; refresh(); onCatalogChanged.run();
            });
        });
        timer = new Timer(2000, event -> {
            if (isShowing() && !loading && current != null && "RUNNING".equals(current.path("analysisState").asText())) loadSelected(true);
        });
        timer.start();
        controls();
    }

    public void refresh() { refresh(selectedId()); }
    private void refresh(String select) {
        execute(() -> api.request("GET", "/api/resumes", null), catalog -> {
            list.setValueIsAdjusting(true);
            model.clear();
            int selected = 0;
            for (JsonNode node : catalog.path("items")) {
                if (node.path("id").asText().equals(select)) selected = model.size();
                model.addElement(new Item(node.path("id").asText(), node.path("name").asText()));
            }
            int limit = catalog.path("limit").asInt();
            quota.setText("已导入 " + model.size() + " / " + (limit == 0 ? "不限" : limit) + " 份");
            if (!model.isEmpty()) list.setSelectedIndex(selected);
            else { current = null; text.setText(""); report.setText("请先导入简历"); }
            list.setValueIsAdjusting(false);
            controls();
        });
    }

    private void choose(boolean replacing) {
        if (replacing && selectedId().isEmpty()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("简历（PDF / DOC / DOCX / TXT）", "pdf", "doc", "docx", "txt"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) importFile(chooser.getSelectedFile().toPath(), replacing ? selectedId() : "");
    }

    void importFile(Path file, String replaceId) {
        busy = true; controls(); status.setText("正在导入和解析简历…");
        execute(() -> {
            try {
                if (Files.size(file) > 10 * 1024 * 1024) throw new IllegalArgumentException("简历文件不能超过 10 MB");
                String url = base + "/api/resumes/import?filename=" + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8)
                        + (replaceId.isBlank() ? "" : "&replaceId=" + replaceId);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                        .header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofFile(file)).build();
                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode body = new ObjectMapper().readTree(response.body());
                if (!body.path("success").asBoolean()) throw new IllegalStateException(body.path("message").asText("导入失败"));
                return body.path("data");
            } catch (Exception error) { throw new IllegalStateException(error.getMessage()); }
        }, data -> {
            busy = false; refresh(data.path("id").asText()); onCatalogChanged.run();
            status.setText("简历已导入，点击 AI 分析画像可查看建议"); controls();
        });
    }

    private void saveText() {
        String id = selectedId();
        if (id.isEmpty()) return;
        String value = text.getText();
        execute(() -> api.request("PUT", path(id, "/text"), Map.of("text", value)), data -> {
            if (id.equals(selectedId())) fill(data, false);
            status.setText("分析文本已保存，原附件内容未修改");
        });
    }

    private void loadSelected(boolean polling) {
        String id = selectedId();
        if (id.isEmpty()) return;
        if (!polling) { current = null; controls(); }
        loading = true;
        execute(() -> api.request("GET", path(id, ""), null), data -> {
            loading = false;
            if (id.equals(selectedId())) fill(data, polling);
        });
    }

    private void fill(JsonNode data, boolean preserveText) {
        current = data;
        if (!preserveText) {
            loadedText = data.path("text").asText(); text.setText(loadedText); text.setCaretPosition(0);
            targetRole.setText(data.path("targetRole").asText());
        }
        metadata.setText(data.path("name").asText() + " · " + String.format("%.1f KB", data.path("size").asLong() / 1024.0));
        String state = data.path("analysisState").asText();
        status.setText("RUNNING".equals(state) ? "AI 正在分析，请稍候；无需停留在本页"
                : "FAILED".equals(state) ? "分析未完成：" + data.path("analysisError").asText()
                : !data.path("warning").asText().isBlank() ? data.path("warning").asText()
                : "DONE".equals(state) ? "分析完成，确认画像后可应用到话术背景" : "可修正文本后开始 AI 分析");
        report.setText(renderProfile(data.path("profile"))); report.setCaretPosition(0);
        controls();
    }

    public static String renderProfile(JsonNode profile) {
        if (profile.isNull() || profile.isMissingNode()) return "尚未生成画像，点击“AI 分析画像”开始。";
        StringBuilder out = new StringBuilder("职业画像\n" + profile.path("summary").asText() + "\n\n");
        append(out, "适合的岗位方向", profile.path("suitableRoles"));
        append(out, "已有技能", profile.path("skills"));
        append(out, "简历优势", profile.path("strengths"));
        out.append("优先优化点\n");
        for (JsonNode item : profile.path("improvements")) {
            out.append("【").append(item.path("priority").asText()).append("】").append(item.path("issue").asText()).append('\n');
            out.append("依据：").append(item.path("evidence").asText()).append('\n');
            out.append("建议：").append(item.path("suggestion").asText()).append('\n');
            if (!item.path("example").asText().isBlank()) out.append("改写参考：").append(item.path("example").asText()).append('\n');
            out.append('\n');
        }
        append(out, "待补充信息", profile.path("missingInformation"));
        return out.append("可用于 AI 话术的背景（确认后应用）\n").append(profile.path("persona").asText()).toString();
    }

    private static void append(StringBuilder out, String title, JsonNode lines) {
        out.append(title).append('\n');
        lines.forEach(line -> out.append("• ").append(line.asText()).append('\n'));
        out.append('\n');
    }

    private void controls() {
        boolean selected = current != null && !selectedId().isEmpty();
        boolean running = selected && "RUNNING".equals(current.path("analysisState").asText());
        upload.setEnabled(!busy); replace.setEnabled(selected && !busy && !running); remove.setEnabled(selected && !busy && !running);
        save.setEnabled(selected && !busy && !running); analyze.setEnabled(selected && !busy && !running);
        apply.setEnabled(selected && !busy && !running && !current.path("profile").isNull());
        text.setEditable(selected && !busy && !running);
    }

    private String selectedId() { return list.getSelectedValue() == null ? "" : list.getSelectedValue().id(); }
    private static String path(String id, String suffix) { return "/api/resumes/" + id + suffix; }
    private static JTextArea area(boolean editable) {
        JTextArea area = new JTextArea(); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setEditable(editable); area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14)); area.setMargin(new Insets(14, 14, 14, 14));
        return area;
    }

    private void execute(Supplier<JsonNode> task, Consumer<JsonNode> done) {
        new SwingWorker<JsonNode, Void>() {
            @Override protected JsonNode doInBackground() { return task.get(); }
            @Override protected void done() {
                try { done.accept(get()); }
                catch (Exception error) {
                    busy = false; loading = false; controls();
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText(cause.getMessage());
                    JOptionPane.showMessageDialog(ResumePanel.this, cause.getMessage(), "简历与画像", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }.execute();
    }
}
