package com.jobpilot.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** JobPilot 的桌面主界面。所有可见控件均为 Swing；HTTP 只用于调用本机业务接口。 */
@Slf4j
public class NativeMainWindow {

    private record Platform(String key, String name, List<String> filters, boolean salaryText) {
        @Override public String toString() { return name; }
    }

    private record Choice(String value, String label) {
        @Override public String toString() { return label; }
    }

    private static final List<Platform> PLATFORMS = List.of(
            new Platform("boss", "Boss 直聘", List.of("jobType", "salary", "experience", "degree", "scale", "stage"), false),
            new Platform("liepin", "猎聘", List.of("salary"), false),
            new Platform("job51", "51job", List.of("salary"), false),
            new Platform("zhilian", "智联招聘", List.of(), true),
            new Platform("shixiseng", "实习僧", List.of(), false));
    private static final Map<String, String> FILTER_NAMES = Map.of(
            "jobType", "职位类型", "salary", "薪资", "experience", "经验",
            "degree", "学历", "scale", "公司规模", "stage", "融资阶段");

    private final String base;
    private final Runnable onQuit;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService io = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "jobpilot-native-ui-io");
        thread.setDaemon(true);
        return thread;
    });
    private JFrame frame;
    private Timer poller;
    private int pollCount;

    private final JComboBox<Platform> platform = new JComboBox<>(PLATFORMS.toArray(Platform[]::new));
    private final JLabel runState = new JLabel("状态：读取中…");
    private final JLabel counters = new JLabel("已扫描 0 · 已投递 0 · 预演 0 · 已过滤 0 · 失败 0");
    private final JButton start = new JButton("开始投递");
    private final JButton stop = new JButton("停止");
    private final JTextArea keywords = new JTextArea(4, 24);
    private final JComboBox<Choice> city = new JComboBox<>();
    private final JPanel filters = new JPanel(new GridLayout(0, 2, 12, 10));
    private final Map<String, JComboBox<Choice>> filterBoxes = new LinkedHashMap<>();
    private final JTextField salaryText = new JTextField();
    private final JSpinner maxJobs = spinner(50, 1, 500);
    private final JSpinner waitSeconds = spinner(10, 3, 120);
    private final JSpinner loginTimeout = spinner(5, 1, 30);
    private final JTextArea sayHi = new JTextArea(3, 24);
    private final JTextArea scoreRules = new JTextArea(8, 24);
    private final JCheckBox dryRun = new JCheckBox("预演模式：不实际投递或发送消息", true);
    private final JCheckBox inactiveHr = new JCheckBox("过滤不活跃 HR");
    private final JTextArea logs = new JTextArea();
    private final DefaultTableModel deliveries = new DefaultTableModel(
            new String[]{"时间", "状态", "公司", "岗位", "薪资", "得分", "原因", "关键词"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };

    private final JCheckBox aiEnabled = new JCheckBox("启用 AI 话术");
    private final JComboBox<Choice> aiMode = new JComboBox<>(new Choice[]{
            new Choice("platform", "平台中转"), new Choice("custom", "自有接口")});
    private final JTextField aiUrl = new JTextField();
    private final JPasswordField aiKey = new JPasswordField();
    private final JTextField aiModel = new JTextField();
    private final JTextArea aiPersona = new JTextArea(6, 30);
    private final JSpinner aiTemperature = new JSpinner(new SpinnerNumberModel(0.9, 0.0, 2.0, 0.1));
    private final JLabel aiInfo = new JLabel(" ");

    private final JLabel licenseState = new JLabel("卡密状态：读取中…");
    private final JTextField cardKey = new JTextField();
    private final JTextArea planInfo = new JTextArea();

    public NativeMainWindow(int port, Runnable onQuit) {
        this.base = "http://127.0.0.1:" + port;
        this.onQuit = onQuit;
    }

    public void show() {
        SwingUtilities.invokeLater(() -> {
            if (frame == null) build();
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
        });
    }

    private void build() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("使用默认 Swing 外观: {}", e.getMessage());
        }
        frame = new JFrame("JobPilot · 简历自动投递");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { quit(); }
        });
        frame.setMinimumSize(new Dimension(900, 680));
        frame.setSize(1120, 850);
        frame.setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("投递", deliveryPanel());
        tabs.addTab("AI 话术", aiPanel());
        tabs.addTab("卡密", licensePanel());
        tabs.addTab("会员套餐", plansPanel());
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 1) loadAi();
            if (tabs.getSelectedIndex() == 2) loadLicense();
            if (tabs.getSelectedIndex() == 3) loadPlans();
        });
        frame.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        footer.add(new JLabel("本机运行 · 投递记录保存在此电脑"), BorderLayout.WEST);
        JButton quit = new JButton("退出 JobPilot");
        quit.addActionListener(e -> quit());
        footer.add(quit, BorderLayout.EAST);
        frame.add(footer, BorderLayout.SOUTH);

        loadPlatform();
        loadLicense();
        poller = new Timer(3000, e -> {
            refreshStatus();
            if (++pollCount % 4 == 0) refreshDeliveries();
        });
        poller.start();
    }

    private JPanel deliveryPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel toolbar = new JPanel(new BorderLayout(8, 8));
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        topRow.add(new JLabel("平台"));
        topRow.add(platform);
        topRow.add(start);
        topRow.add(stop);
        stop.setEnabled(false);
        toolbar.add(topRow, BorderLayout.NORTH);
        JPanel stateRow = new JPanel(new GridLayout(0, 1, 0, 3));
        stateRow.add(runState);
        stateRow.add(counters);
        toolbar.add(stateRow, BorderLayout.CENTER);
        root.add(toolbar, BorderLayout.NORTH);

        JPanel form = vertical();
        form.add(titled("搜索关键词（每行一个）", new JScrollPane(keywords)));
        JPanel basics = new JPanel(new GridLayout(0, 2, 12, 10));
        basics.add(titled("城市", city));
        basics.add(titled("每个关键词最多处理", maxJobs));
        basics.add(titled("岗位间隔秒数", waitSeconds));
        basics.add(titled("登录等待分钟数", loginTimeout));
        form.add(basics);
        form.add(filters);
        form.add(titled("固定打招呼话术", new JScrollPane(sayHi)));
        scoreRules.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        form.add(titled("打分规则（JSON）", new JScrollPane(scoreRules)));
        form.add(dryRun);
        form.add(inactiveHr);
        JButton save = new JButton("保存配置");
        save.addActionListener(e -> saveConfig());
        form.add(save);

        JTabbedPane bottom = new JTabbedPane();
        logs.setEditable(false);
        logs.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        bottom.addTab("运行日志", new JScrollPane(logs));
        JPanel records = new JPanel(new BorderLayout());
        JTable table = new JTable(deliveries);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        records.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel recordActions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refresh = new JButton("刷新记录");
        refresh.addActionListener(e -> refreshDeliveries());
        JButton clear = new JButton("清空本平台记录");
        clear.addActionListener(e -> clearDeliveries());
        recordActions.add(refresh);
        recordActions.add(clear);
        records.add(recordActions, BorderLayout.SOUTH);
        bottom.addTab("投递记录", records);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(form), bottom);
        split.setResizeWeight(0.65);
        split.setDividerLocation(440);
        root.add(split, BorderLayout.CENTER);
        platform.addActionListener(e -> loadPlatform());
        start.addActionListener(e -> runPlatform("start"));
        stop.addActionListener(e -> runPlatform("stop"));
        return root;
    }

    private JPanel aiPanel() {
        JPanel form = vertical();
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        form.add(aiEnabled);
        form.add(titled("话术来源", aiMode));
        form.add(aiInfo);
        form.add(titled("接口地址（自有接口模式）", aiUrl));
        form.add(titled("API Key（留空表示保留原值）", aiKey));
        form.add(titled("模型名称", aiModel));
        form.add(titled("求职者背景", new JScrollPane(aiPersona)));
        form.add(titled("生成温度", aiTemperature));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton save = new JButton("保存 AI 配置");
        save.addActionListener(e -> saveAi());
        JButton test = new JButton("测试连接");
        test.addActionListener(e -> testAi());
        JButton models = new JButton("拉取模型");
        models.addActionListener(e -> loadModels());
        JButton clearKey = new JButton("清除 Key");
        clearKey.addActionListener(e -> {
            aiKey.setText("");
            aiKey.putClientProperty("clear", true);
            message("已标记清除 Key，点击“保存 AI 配置”后生效");
        });
        buttons.add(save);
        buttons.add(test);
        buttons.add(models);
        buttons.add(clearKey);
        form.add(buttons);
        aiMode.addActionListener(e -> updateAiFields());
        JPanel root = new JPanel(new BorderLayout());
        root.add(new JScrollPane(form), BorderLayout.CENTER);
        return root;
    }

    private JPanel licensePanel() {
        JPanel form = vertical();
        form.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        form.add(licenseState);
        form.add(Box.createVerticalStrut(18));
        form.add(titled("卡密", cardKey));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton activate = new JButton("激活卡密");
        activate.addActionListener(e -> {
            String key = cardKey.getText().trim();
            if (key.isEmpty()) { message("请输入卡密"); return; }
            async(() -> api("POST", "/api/license/activate", Map.of("cardKey", key)), data -> {
                cardKey.setText("");
                loadLicense();
                message("卡密已激活");
            });
        });
        JButton unbind = new JButton("解绑本机");
        unbind.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(frame, "确定解绑本机卡密？", "解绑",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            async(() -> api("POST", "/api/license/unbind", null), data -> loadLicense());
        });
        actions.add(activate);
        actions.add(unbind);
        form.add(actions);
        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.NORTH);
        return root;
    }

    private JPanel plansPanel() {
        JPanel root = new JPanel(new BorderLayout());
        planInfo.setEditable(false);
        planInfo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        planInfo.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        root.add(new JScrollPane(planInfo), BorderLayout.CENTER);
        JButton refresh = new JButton("刷新套餐");
        refresh.addActionListener(e -> loadPlans());
        root.add(refresh, BorderLayout.SOUTH);
        return root;
    }

    private void loadPlatform() {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null || frame == null) return;
        async(() -> {
            JsonNode options = api("GET", path(selected, "options"), null);
            JsonNode config = api("GET", path(selected, "config"), null);
            return new JsonNode[]{options, config};
        }, pair -> {
            if (platform.getSelectedItem() != selected) return;
            fillConfig(selected, pair[0], pair[1]);
            refreshStatus();
            refreshDeliveries();
        });
    }

    private void fillConfig(Platform selected, JsonNode options, JsonNode config) {
        keywords.setText(join(config.path("keywords")));
        fillChoices(city, options.path("cities"), config.path("city").asText(""), true);
        filterBoxes.clear();
        filters.removeAll();
        for (String key : selected.filters()) {
            JComboBox<Choice> box = new JComboBox<>();
            fillChoices(box, options.path(key), config.path(key).asText(""), false);
            filterBoxes.put(key, box);
            filters.add(titled(FILTER_NAMES.getOrDefault(key, key), box));
        }
        if (selected.salaryText()) {
            salaryText.setText(config.path("salary").asText(""));
            filters.add(titled("薪资区间（元，例如 12000,20000）", salaryText));
        }
        filters.revalidate();
        filters.repaint();
        maxJobs.setValue(config.path("maxJobsPerKeyword").asInt(50));
        waitSeconds.setValue(config.path("waitSeconds").asInt(10));
        loginTimeout.setValue(config.path("loginTimeoutMinutes").asInt(5));
        sayHi.setText(config.path("sayHi").asText(""));
        sayHi.setEnabled("boss".equals(selected.key()));
        inactiveHr.setSelected(config.path("filterInactiveHr").asBoolean(false));
        inactiveHr.setVisible("boss".equals(selected.key()));
        dryRun.setSelected(config.path("dryRun").asBoolean(true));
        try {
            scoreRules.setText(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config.path("scoreRules")));
        } catch (Exception e) {
            scoreRules.setText("{}");
        }
    }

    private void saveConfig() {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        List<String> words = keywords.getText().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        body.put("keywords", words);
        body.put("city", choiceValue(city));
        body.put("maxJobsPerKeyword", maxJobs.getValue());
        body.put("waitSeconds", waitSeconds.getValue());
        body.put("loginTimeoutMinutes", loginTimeout.getValue());
        body.put("dryRun", dryRun.isSelected());
        try {
            body.put("scoreRules", mapper.readTree(scoreRules.getText()));
        } catch (Exception e) {
            message("打分规则不是合法 JSON：" + e.getMessage());
            return;
        }
        body.put("salary", selected.salaryText() ? salaryText.getText().trim()
                : filterBoxes.containsKey("salary") ? choiceValue(filterBoxes.get("salary")) : "");
        filterBoxes.forEach((key, box) -> {
            if (!"salary".equals(key)) body.put(key, choiceValue(box));
        });
        if ("boss".equals(selected.key())) {
            body.put("sayHi", sayHi.getText());
            body.put("filterInactiveHr", inactiveHr.isSelected());
        }
        async(() -> api("PUT", path(selected, "config"), body), data -> message("配置已保存"));
    }

    private void runPlatform(String action) {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null) return;
        async(() -> api("POST", path(selected, action), null), data -> refreshStatus());
    }

    private void refreshStatus() {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null || frame == null) return;
        asyncQuiet(() -> api("GET", path(selected, "status"), null), status -> {
            if (platform.getSelectedItem() != selected) return;
            String state = status.path("state").asText("IDLE");
            runState.setText("状态：" + state + " · " + status.path("message").asText("")
                    + (status.path("currentKeyword").asText("").isEmpty() ? ""
                    : " · " + status.path("currentKeyword").asText("")));
            counters.setText("已扫描 " + status.path("scanned").asInt() + " · 已投递 "
                    + status.path("delivered").asInt() + " · 预演 " + status.path("previewed").asInt()
                    + " · 已过滤 " + status.path("filtered").asInt() + " · 失败 "
                    + status.path("failed").asInt() + " · 跳过 " + status.path("skipped").asInt());
            boolean running = "RUNNING".equals(state) || "STOPPING".equals(state);
            start.setEnabled(!running);
            stop.setEnabled(running);
            logs.setText(join(status.path("logs")));
            logs.setCaretPosition(logs.getDocument().getLength());
        });
    }

    private void refreshDeliveries() {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null || frame == null) return;
        asyncQuiet(() -> api("GET", path(selected, "deliveries?limit=100"), null), rows -> {
            if (platform.getSelectedItem() != selected) return;
            deliveries.setRowCount(0);
            rows.forEach(row -> deliveries.addRow(new Object[]{
                    row.path("createdAt").asText("").replace('T', ' '),
                    row.path("deliveryStatus").asText(""), row.path("brandName").asText(""),
                    row.path("jobName").asText(""), row.path("salaryDesc").asText(""),
                    row.path("score").isNull() ? "" : row.path("score").asText(""),
                    row.path("failReason").asText("").isEmpty()
                            ? row.path("greeting").asText("") : row.path("failReason").asText(""),
                    row.path("keyword").asText("")}));
        });
    }

    private void clearDeliveries() {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null) return;
        if (JOptionPane.showConfirmDialog(frame,
                "清空后已投岗位可能被重新投递。确定清空 " + selected.name() + " 的记录？",
                "清空投递记录", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        async(() -> api("DELETE", path(selected, "deliveries"), null), data -> refreshDeliveries());
    }

    private void loadAi() {
        asyncQuiet(() -> api("GET", "/api/ai/config", null), config -> {
            aiEnabled.setSelected(config.path("enabled").asBoolean());
            selectChoice(aiMode, config.path("mode").asText("platform"));
            aiUrl.setText(config.path("baseUrl").asText(""));
            aiKey.setText("");
            aiKey.putClientProperty("clear", false);
            aiModel.setText(config.path("model").asText(""));
            aiPersona.setText(config.path("persona").asText(""));
            aiTemperature.setValue(config.path("temperature").asDouble(0.9));
            aiInfo.setText(config.path("platformMessage").asText(""));
            updateAiFields();
        });
    }

    private void updateAiFields() {
        boolean custom = "custom".equals(choiceValue(aiMode));
        aiUrl.setEnabled(custom);
        aiKey.setEnabled(custom);
        aiModel.setEnabled(custom);
    }

    private void saveAi() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", aiEnabled.isSelected());
        body.put("mode", choiceValue(aiMode));
        body.put("baseUrl", aiUrl.getText().trim());
        body.put("model", aiModel.getText().trim());
        body.put("persona", aiPersona.getText());
        body.put("temperature", aiTemperature.getValue());
        String key = new String(aiKey.getPassword()).trim();
        if (!key.isEmpty()) body.put("apiKey", key);
        else if (Boolean.TRUE.equals(aiKey.getClientProperty("clear"))) body.put("apiKey", "");
        async(() -> api("PUT", "/api/ai/config", body), data -> {
            aiKey.setText("");
            aiKey.putClientProperty("clear", false);
            message("AI 配置已保存");
        });
    }

    private void loadModels() {
        if (!"custom".equals(choiceValue(aiMode))) {
            message("平台中转模式由平台指定模型");
            return;
        }
        String url = URLEncoder.encode(aiUrl.getText().trim(), StandardCharsets.UTF_8);
        String key = URLEncoder.encode(new String(aiKey.getPassword()).trim(), StandardCharsets.UTF_8);
        async(() -> api("GET", "/api/ai/models?baseUrl=" + url + "&apiKey=" + key, null), data -> {
            if (!data.path("ok").asBoolean()) {
                message(data.path("error").asText("拉取模型失败"));
                return;
            }
            List<String> names = new ArrayList<>();
            data.path("models").forEach(node -> names.add(node.asText("")));
            if (names.isEmpty()) { message("接口没有返回模型"); return; }
            Object choice = JOptionPane.showInputDialog(frame, "选择模型", "模型列表",
                    JOptionPane.PLAIN_MESSAGE, null, names.toArray(), names.get(0));
            if (choice != null) aiModel.setText(choice.toString());
        });
    }

    private void testAi() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", choiceValue(aiMode));
        body.put("baseUrl", aiUrl.getText().trim());
        body.put("model", aiModel.getText().trim());
        String key = new String(aiKey.getPassword()).trim();
        if (!key.isEmpty()) body.put("apiKey", key);
        async(() -> api("POST", "/api/ai/test", body), data ->
                message(data.path("ok").asBoolean() ? "连接成功：" + data.path("reply").asText("")
                        : "连接失败：" + data.path("error").asText("未知错误")));
    }

    private void loadLicense() {
        asyncQuiet(() -> api("GET", "/api/license/status", null), status -> {
            licenseState.setText("卡密：" + status.path("state").asText("") + " · "
                    + status.path("message").asText("") + " · 剩余天数 "
                    + status.path("remainingDays").asText("-"));
        });
    }

    private void loadPlans() {
        async(() -> api("GET", "/api/license/plans", null), data -> {
            StringBuilder text = new StringBuilder("套餐价格与额度以服务端配置为准。购买或续费后在“卡密”页激活。\n\n");
            JsonNode plans = data.path("plans");
            if (plans.isArray()) {
                plans.forEach(plan -> {
                    text.append(plan.path("name").asText("套餐")).append("\n");
                    JsonNode durations = plan.path("durations");
                    if (durations.isArray()) {
                        durations.forEach(duration -> text.append("  ")
                                .append(duration.path("days").asInt()).append(" 天 · ¥")
                                .append(String.format("%.2f", duration.path("price_cents").asDouble() / 100.0))
                                .append("\n"));
                    }
                    JsonNode quotas = plan.path("quotas");
                    text.append("  每日投递上限：")
                            .append(quotas.path("max_daily_apply").asText("-"))
                            .append("\n\n");
                });
            }
            planInfo.setText(text.toString());
        });
    }

    private JsonNode api(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(30));
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json; charset=utf-8")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            if (response.statusCode() >= 400 || !json.path("success").asBoolean(false)) {
                throw new IllegalStateException(json.path("message").asText("请求失败（HTTP "
                        + response.statusCode() + "）"));
            }
            return json.path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private <T> void async(Supplier<T> task, Consumer<T> done) {
        io.submit(() -> {
            try {
                T result = task.get();
                SwingUtilities.invokeLater(() -> done.accept(result));
            } catch (Exception e) {
                log.warn("桌面操作失败: {}", e.getMessage());
                SwingUtilities.invokeLater(() -> message(e.getMessage()));
            }
        });
    }

    private <T> void asyncQuiet(Supplier<T> task, Consumer<T> done) {
        io.submit(() -> {
            try {
                T result = task.get();
                SwingUtilities.invokeLater(() -> done.accept(result));
            } catch (Exception e) {
                log.debug("桌面状态刷新失败: {}", e.getMessage());
            }
        });
    }

    private void message(String text) {
        JOptionPane.showMessageDialog(frame, text, "JobPilot", JOptionPane.INFORMATION_MESSAGE);
    }

    private void quit() {
        int result = JOptionPane.showConfirmDialog(frame, "退出会中断正在运行的投递任务，确定退出？",
                "退出", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;
        if (poller != null) poller.stop();
        io.shutdownNow();
        onQuit.run();
    }

    private static JSpinner spinner(int value, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(value, min, max, 1));
    }

    private static JPanel vertical() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel titled(String label, Component component) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 3, 5, 3));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static String path(Platform platform, String resource) {
        return "/api/" + platform.key() + "/" + resource;
    }

    private static String join(JsonNode values) {
        List<String> lines = new ArrayList<>();
        if (values.isArray()) values.forEach(value -> lines.add(value.asText("")));
        return String.join("\n", lines);
    }

    private static void fillChoices(JComboBox<Choice> box, JsonNode options,
                                    String selected, boolean cityNames) {
        box.removeAllItems();
        box.addItem(new Choice("", cityNames ? "全国" : "不限"));
        if (options.isArray()) {
            options.forEach(option -> box.addItem(new Choice(
                    cityNames ? option.path("name").asText("") : option.path("code").asText(""),
                    option.path("name").asText(""))));
        }
        if (!selected.isEmpty()) {
            boolean found = false;
            for (int i = 0; i < box.getItemCount(); i++) {
                if (selected.equals(box.getItemAt(i).value())) {
                    box.setSelectedIndex(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                box.addItem(new Choice(selected, selected));
                box.setSelectedIndex(box.getItemCount() - 1);
            }
        }
    }

    private static void selectChoice(JComboBox<Choice> box, String value) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (value.equals(box.getItemAt(i).value())) {
                box.setSelectedIndex(i);
                return;
            }
        }
    }

    private static String choiceValue(JComboBox<Choice> box) {
        Choice choice = (Choice) box.getSelectedItem();
        return choice == null ? "" : choice.value();
    }
}
