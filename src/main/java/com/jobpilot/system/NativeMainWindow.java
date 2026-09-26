package com.jobpilot.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.border.EmptyBorder;
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
    private final JLabel counters = new JLabel("失败 0 · 跳过 0");
    private final JLabel modeBadge = new JLabel("预演模式");
    private final JLabel[] metricValues = new JLabel[4];
    private JScrollPane settingsScrollLeft;
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
    private final JPanel logCards = new JPanel(new CardLayout());
    private final JPanel recordCards = new JPanel(new CardLayout());
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
    private JScrollPane aiScroll;

    private final JLabel licenseState = new JLabel("卡密状态：读取中…");
    private final JTextField cardKey = new JTextField();
    private final JPanel planCards = new JPanel(new GridLayout(0, 2, 16, 16));
    private final JPanel planComparison = new JPanel(new BorderLayout());
    private JScrollPane plansScroll;

    private static final Color CANVAS = new Color(246, 248, 251);
    private static final Color WHITE = Color.WHITE;
    private static final Color INK = new Color(30, 40, 60);
    private static final Color MUTED = new Color(101, 113, 132);
    private static final Color LINE = new Color(226, 232, 240);
    private static final Color ACCENT = new Color(68, 81, 192);
    private static final Color NAV = new Color(24, 32, 52);
    private static final Color MINT = new Color(27, 139, 105);

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
        frame = new JFrame("JobPilot · 简历自动投递");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { quit(); }
        });
        frame.setMinimumSize(new Dimension(1050, 720));
        frame.setSize(1280, 880);
        frame.setLocationRelativeTo(null);

        JPanel pages = new JPanel(new CardLayout());
        pages.add(deliveryPanel(), "delivery");
        pages.add(aiPanel(), "ai");
        pages.add(licensePanel(), "license");
        pages.add(plansPanel(), "plans");
        frame.getContentPane().setBackground(CANVAS);
        frame.add(sidebar(pages), BorderLayout.WEST);
        frame.add(pages, BorderLayout.CENTER);

        loadPlatform();
        loadLicense();
        poller = new Timer(3000, e -> {
            refreshStatus();
            if (++pollCount % 4 == 0) refreshDeliveries();
        });
        poller.start();
    }

    private JPanel deliveryPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(CANVAS);
        root.setBorder(new EmptyBorder(28, 30, 24, 30));
        JPanel overview = new JPanel(new BorderLayout(0, 18));
        overview.setOpaque(false);
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(pageTitle("投递工作台", "为每一次投递设定清晰目标"), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        platform.setPreferredSize(new Dimension(166, 40));
        actions.add(platform);
        primary(start);
        secondary(stop);
        actions.add(start);
        actions.add(stop);
        stop.setEnabled(false);
        heading.add(actions, BorderLayout.EAST);
        overview.add(heading, BorderLayout.NORTH);

        JPanel statusBar = card(new BorderLayout(18, 0));
        statusBar.setBorder(new EmptyBorder(16, 20, 16, 20));
        JPanel stateText = new JPanel(new GridLayout(2, 1, 0, 3));
        stateText.setOpaque(false);
        runState.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        runState.setForeground(INK);
        counters.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        counters.setForeground(MUTED);
        stateText.add(runState);
        stateText.add(counters);
        statusBar.add(stateText, BorderLayout.CENTER);
        modeBadge.setOpaque(true);
        modeBadge.setHorizontalAlignment(SwingConstants.CENTER);
        modeBadge.setForeground(MINT);
        modeBadge.setBackground(new Color(229, 247, 240));
        modeBadge.setBorder(new EmptyBorder(8, 14, 8, 14));
        statusBar.add(modeBadge, BorderLayout.EAST);
        overview.add(statusBar, BorderLayout.CENTER);

        JPanel summary = new JPanel(new GridLayout(1, 4, 12, 0));
        summary.setOpaque(false);
        String[] names = {"已扫描", "已投递", "预演", "已过滤"};
        for (int i = 0; i < names.length; i++) {
            metricValues[i] = label("0", 26, i == 1 ? ACCENT : INK, Font.BOLD);
            JPanel tile = card(new BorderLayout(0, 6));
            tile.setBorder(new EmptyBorder(14, 18, 14, 18));
            tile.add(label(names[i], 12, MUTED, Font.PLAIN), BorderLayout.NORTH);
            tile.add(metricValues[i], BorderLayout.CENTER);
            summary.add(tile);
        }
        overview.add(summary, BorderLayout.SOUTH);
        root.add(overview, BorderLayout.NORTH);

        JPanel views = new JPanel(new CardLayout());
        views.setBackground(CANVAS);
        views.add(settingsView(), "settings");
        views.add(recordsView(), "records");
        views.add(logsView(), "logs");
        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setOpaque(false);
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tabs.setOpaque(false);
        String[][] items = {{"投递设置", "settings"}, {"投递记录", "records"}, {"运行日志", "logs"}};
        List<TabButton> tabButtons = new ArrayList<>();
        for (String[] item : items) {
            TabButton button = new TabButton(item[0]);
            button.addActionListener(e -> {
                ((CardLayout) views.getLayout()).show(views, item[1]);
                tabButtons.forEach(tab -> tab.setSelected(tab == button));
                if ("records".equals(item[1])) refreshDeliveries();
            });
            tabButtons.add(button);
            tabs.add(button);
        }
        tabButtons.get(0).setSelected(true);
        content.add(tabs, BorderLayout.NORTH);
        content.add(views, BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);
        platform.addActionListener(e -> loadPlatform());
        start.addActionListener(e -> runPlatform("start"));
        stop.addActionListener(e -> runPlatform("stop"));
        return root;
    }

    private JPanel settingsView() {
        JPanel left = stack();
        JPanel search = card(new BorderLayout(0, 16));
        search.add(sectionHead("搜索目标", "每行输入一个关键词，按顺序检索"), BorderLayout.NORTH);
        JPanel searchFields = new JPanel(new BorderLayout(0, 10));
        searchFields.setOpaque(false);
        keywords.setRows(3);
        searchFields.add(field("关键词", scroll(keywords)), BorderLayout.NORTH);
        JPanel basics = grid2();
        basics.add(field("城市", city));
        basics.add(field("每个关键词最多处理", maxJobs));
        basics.add(field("岗位间隔 · 秒", waitSeconds));
        basics.add(field("登录等待 · 分钟", loginTimeout));
        searchFields.add(basics, BorderLayout.CENTER);
        search.add(searchFields, BorderLayout.CENTER);
        left.add(search);
        left.add(Box.createVerticalStrut(14));
        JPanel filterCard = card(new BorderLayout(0, 16));
        filterCard.add(sectionHead("岗位筛选", "按平台提供的条件收窄结果"), BorderLayout.NORTH);
        filters.setOpaque(false);
        filterCard.add(filters, BorderLayout.CENTER);
        left.add(filterCard);

        JPanel right = stack();
        JPanel strategy = card(new BorderLayout(0, 16));
        strategy.add(sectionHead("投递策略", "开始前会自动保存当前设置"), BorderLayout.NORTH);
        JPanel fields = stack();
        fields.setBackground(WHITE);
        dryRun.setOpaque(false);
        inactiveHr.setOpaque(false);
        fields.add(dryRun);
        fields.add(inactiveHr);
        dryRun.addActionListener(e -> updateModeBadge());
        sayHi.setRows(3);
        sayHi.setLineWrap(true);
        sayHi.setWrapStyleWord(true);
        fields.add(field("固定打招呼话术 · Boss 直聘", scroll(sayHi)));
        JToggleButton advanced = new JToggleButton("▸  高级筛选规则");
        advanced.setHorizontalAlignment(SwingConstants.LEFT);
        advanced.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        advanced.setForeground(MUTED);
        advanced.setContentAreaFilled(false);
        advanced.setBorder(new EmptyBorder(13, 0, 8, 0));
        fields.add(advanced);
        scoreRules.setRows(9);
        scoreRules.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel advancedField = field("打分规则 · JSON", scroll(scoreRules));
        advancedField.setVisible(false);
        advanced.addActionListener(e -> {
            advancedField.setVisible(advanced.isSelected());
            advanced.setText(advanced.isSelected() ? "▾  收起高级筛选规则" : "▸  高级筛选规则");
            fields.revalidate();
        });
        fields.add(advancedField);
        strategy.add(fields, BorderLayout.CENTER);
        JButton save = new JButton("保存设置");
        primary(save);
        save.addActionListener(e -> saveConfig());
        JPanel saveRow = new JPanel(new BorderLayout(12, 0));
        saveRow.setOpaque(false);
        saveRow.add(label("修改设置后点击保存，或直接开始投递", 12, MUTED, Font.PLAIN), BorderLayout.CENTER);
        saveRow.add(save, BorderLayout.EAST);
        strategy.add(saveRow, BorderLayout.SOUTH);
        strategy.setMaximumSize(new Dimension(Integer.MAX_VALUE, strategy.getPreferredSize().height));
        right.add(strategy);

        JPanel columns = new JPanel(new GridLayout(1, 2, 16, 0));
        columns.setBackground(CANVAS);
        settingsScrollLeft = scroll(left);
        columns.add(settingsScrollLeft);
        columns.add(scroll(right));
        return columns;
    }

    private JPanel recordsView() {
        JPanel view = card(new BorderLayout(0, 18));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(sectionHead("投递记录", "查看已处理岗位、状态和原因"), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton refresh = new JButton("刷新");
        refresh.addActionListener(e -> refreshDeliveries());
        JButton clear = new JButton("清空本平台记录");
        clear.addActionListener(e -> clearDeliveries());
        actions.add(refresh);
        actions.add(clear);
        head.add(actions, BorderLayout.EAST);
        view.add(head, BorderLayout.NORTH);
        JTable table = new JTable(deliveries);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(36);
        table.setShowVerticalLines(false);
        table.setSelectionBackground(new Color(232, 236, 253));
        table.setSelectionForeground(INK);
        table.getTableHeader().setReorderingAllowed(false);
        TableColumnModel columns = table.getColumnModel();
        int[] widths = {150, 85, 150, 180, 105, 65, 260, 120};
        for (int i = 0; i < widths.length; i++) columns.getColumn(i).setPreferredWidth(widths[i]);
        recordCards.add(emptyState("暂无投递记录", "开始任务后，处理过的岗位会出现在这里。"), "empty");
        recordCards.add(scroll(table), "table");
        view.add(recordCards, BorderLayout.CENTER);
        ((CardLayout) recordCards.getLayout()).show(recordCards, "empty");
        return view;
    }

    private JPanel logsView() {
        JPanel view = card(new BorderLayout(0, 18));
        view.add(sectionHead("运行日志", "实时查看登录、检索和投递过程"), BorderLayout.NORTH);
        logs.setEditable(false);
        logs.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        logs.setBackground(new Color(26, 36, 55));
        logs.setForeground(new Color(218, 230, 244));
        logs.setCaretColor(WHITE);
        logs.setBorder(new EmptyBorder(18, 18, 18, 18));
        logCards.add(emptyState("还没有运行日志", "启动任务后，这里会显示每一步进展。"), "empty");
        logCards.add(scroll(logs), "logs");
        view.add(logCards, BorderLayout.CENTER);
        ((CardLayout) logCards.getLayout()).show(logCards, "empty");
        return view;
    }

    private JPanel aiPanel() {
        JPanel intro = card(new BorderLayout(0, 12));
        intro.add(sectionHead("个性化沟通", "按求职背景生成更贴合岗位的消息"), BorderLayout.NORTH);
        aiEnabled.setOpaque(false);
        intro.add(aiEnabled, BorderLayout.CENTER);
        JPanel source = card(new BorderLayout(0, 12));
        source.add(sectionHead("模型与接口", "选择平台中转或填写自己的接口"), BorderLayout.NORTH);
        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setOpaque(false);
        fields.add(field("话术来源", aiMode));
        aiInfo.setForeground(MUTED);
        aiInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.add(aiInfo);
        fields.add(field("接口地址 · 自有接口模式", aiUrl));
        fields.add(field("API Key · 留空保留原值", aiKey));
        fields.add(field("模型名称", aiModel));
        source.add(fields, BorderLayout.CENTER);
        JPanel content = card(new BorderLayout(0, 12));
        content.add(sectionHead("个人背景", "写出相关经历，生成内容会更贴近你"), BorderLayout.NORTH);
        JPanel personaFields = new JPanel(new BorderLayout(0, 10));
        personaFields.setOpaque(false);
        aiPersona.setLineWrap(true);
        aiPersona.setWrapStyleWord(true);
        personaFields.add(field("求职者背景", scroll(aiPersona)), BorderLayout.CENTER);
        personaFields.add(field("生成温度", aiTemperature), BorderLayout.SOUTH);
        content.add(personaFields, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        buttons.setOpaque(false);
        JButton save = new JButton("保存 AI 配置");
        primary(save);
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
        aiMode.addActionListener(e -> updateAiFields());
        aiScroll = scroll(formColumn(760, intro, source, content, buttons));
        return page("AI 话术", "管理个性化沟通方式", aiScroll);
    }

    private JPanel licensePanel() {
        JPanel panel = card(new BorderLayout(0, 16));
        panel.add(sectionHead("设备授权", "查看状态，激活卡密或解绑当前电脑"), BorderLayout.NORTH);
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        licenseState.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        licenseState.setForeground(INK);
        content.add(licenseState);
        content.add(field("输入卡密", cardKey));
        panel.add(content, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        JButton activate = new JButton("激活卡密");
        primary(activate);
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
        panel.add(actions, BorderLayout.SOUTH);
        return page("卡密", "管理这台电脑的使用授权",
                scroll(formColumn(760, panel)));
    }

    private JPanel plansPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 20));
        root.setBackground(CANVAS);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(sectionHead("找到适合你的节奏", "所有价格、功能与额度均来自实时套餐配置"), BorderLayout.WEST);
        JButton refresh = new JButton("刷新套餐");
        refresh.addActionListener(e -> loadPlans());
        head.add(refresh, BorderLayout.EAST);
        root.add(head, BorderLayout.NORTH);
        JPanel sections = new JPanel(new BorderLayout(0, 18));
        sections.setBackground(CANVAS);
        planCards.setLayout(new GridLayout(1, 0, 16, 0));
        planCards.setBackground(CANVAS);
        planCards.setPreferredSize(new Dimension(820, 380));
        planCards.add(emptyState("正在读取套餐", "请稍候。"));
        sections.add(planCards, BorderLayout.NORTH);
        planComparison.setOpaque(false);
        planComparison.setPreferredSize(new Dimension(820, 460));
        planComparison.add(emptyState("套餐对比", "正在读取功能和额度。"));
        sections.add(planComparison, BorderLayout.CENTER);
        plansScroll = scroll(sections);
        root.add(plansScroll, BorderLayout.CENTER);
        return page("会员套餐", "价格清晰，权益看得见", root);
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
        keywords.setCaretPosition(0);
        fillChoices(city, options.path("cities"), config.path("city").asText(""), true);
        filterBoxes.clear();
        filters.removeAll();
        for (String key : selected.filters()) {
            JComboBox<Choice> box = new JComboBox<>();
            fillChoices(box, options.path(key), config.path(key).asText(""), false);
            filterBoxes.put(key, box);
            filters.add(field(FILTER_NAMES.getOrDefault(key, key), box));
        }
        if (selected.salaryText()) {
            salaryText.setText(config.path("salary").asText(""));
            filters.add(field("薪资区间 · 元，如 12000,20000", salaryText));
        }
        filters.revalidate();
        filters.repaint();
        maxJobs.setValue(config.path("maxJobsPerKeyword").asInt(50));
        waitSeconds.setValue(config.path("waitSeconds").asInt(10));
        loginTimeout.setValue(config.path("loginTimeoutMinutes").asInt(5));
        sayHi.setText(config.path("sayHi").asText(""));
        sayHi.setCaretPosition(0);
        sayHi.setEnabled("boss".equals(selected.key()));
        inactiveHr.setSelected(config.path("filterInactiveHr").asBoolean(false));
        inactiveHr.setVisible("boss".equals(selected.key()));
        dryRun.setSelected(config.path("dryRun").asBoolean(true));
        updateModeBadge();
        try {
            scoreRules.setText(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config.path("scoreRules")));
        } catch (Exception e) {
            scoreRules.setText("{}");
        }
        SwingUtilities.invokeLater(() -> {
            if (settingsScrollLeft != null) settingsScrollLeft.getVerticalScrollBar().setValue(0);
        });
    }

    private void saveConfig() {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null) return;
        Map<String, Object> body = configPayload(selected);
        if (body != null) {
            async(() -> api("PUT", path(selected, "config"), body), data -> message("设置已保存"));
        }
    }

    private Map<String, Object> configPayload(Platform selected) {
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
            return null;
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
        return body;
    }

    private void runPlatform(String action) {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null) return;
        if ("start".equals(action)) {
            Map<String, Object> body = configPayload(selected);
            if (body == null) return;
            start.setEnabled(false);
            async(() -> {
                api("PUT", path(selected, "config"), body);
                return api("POST", path(selected, "start"), null);
            }, data -> refreshStatus());
        } else {
            async(() -> api("POST", path(selected, "stop"), null), data -> refreshStatus());
        }
    }

    private void refreshStatus() {
        Platform selected = (Platform) platform.getSelectedItem();
        if (selected == null || frame == null) return;
        asyncQuiet(() -> api("GET", path(selected, "status"), null), status -> {
            if (platform.getSelectedItem() != selected) return;
            String state = status.path("state").asText("IDLE");
            String stateLabel = switch (state) {
                case "RUNNING" -> "运行中";
                case "STOPPING" -> "正在停止";
                case "FINISHED" -> "已完成";
                default -> "准备就绪";
            };
            runState.setText(stateLabel + "  ·  " + status.path("message").asText("")
                    + (status.path("currentKeyword").asText("").isEmpty() ? ""
                    : " · " + status.path("currentKeyword").asText("")));
            counters.setText("失败 " + status.path("failed").asInt()
                    + "   ·   跳过 " + status.path("skipped").asInt());
            metricValues[0].setText(String.valueOf(status.path("scanned").asInt()));
            metricValues[1].setText(String.valueOf(status.path("delivered").asInt()));
            metricValues[2].setText(String.valueOf(status.path("previewed").asInt()));
            metricValues[3].setText(String.valueOf(status.path("filtered").asInt()));
            boolean running = "RUNNING".equals(state) || "STOPPING".equals(state);
            start.setEnabled(!running);
            stop.setEnabled(running);
            logs.setText(join(status.path("logs")));
            logs.setCaretPosition(logs.getDocument().getLength());
            ((CardLayout) logCards.getLayout()).show(logCards,
                    logs.getText().isBlank() ? "empty" : "logs");
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
                    deliveryLabel(row.path("deliveryStatus").asText("")), row.path("brandName").asText(""),
                    row.path("jobName").asText(""), row.path("salaryDesc").asText(""),
                    row.path("score").isNull() ? "" : row.path("score").asText(""),
                    row.path("failReason").asText("").isEmpty()
                            ? row.path("greeting").asText("") : row.path("failReason").asText(""),
                    row.path("keyword").asText("")}));
            ((CardLayout) recordCards.getLayout()).show(recordCards,
                    deliveries.getRowCount() == 0 ? "empty" : "table");
        });
    }

    private static String deliveryLabel(String status) {
        return switch (status) {
            case "DELIVERED" -> "已投递";
            case "PREVIEW" -> "预演";
            case "FAILED" -> "失败";
            case "LIMIT" -> "达到上限";
            default -> status;
        };
    }

    private void updateModeBadge() {
        boolean preview = dryRun.isSelected();
        modeBadge.setText(preview ? "●  预演模式" : "●  正式投递");
        modeBadge.setForeground(preview ? MINT : new Color(153, 86, 20));
        modeBadge.setBackground(preview ? new Color(229, 247, 240) : new Color(255, 242, 224));
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
            SwingUtilities.invokeLater(() -> {
                if (aiScroll != null) aiScroll.getVerticalScrollBar().setValue(0);
            });
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
            String state = status.path("state").asText("");
            String stateText = switch (state.toUpperCase()) {
                case "OFF" -> "未启用卡密校验";
                case "ACTIVE" -> "已激活";
                case "EXPIRED" -> "已过期";
                default -> state.isBlank() ? "状态未知" : state;
            };
            String days = status.path("remainingDays").asText("");
            licenseState.setText(stateText + (days.isBlank() || "null".equals(days)
                    ? "" : "  ·  剩余 " + days + " 天"));
        });
    }

    private void loadPlans() {
        io.submit(() -> {
          try {
            JsonNode data = api("GET", "/api/license/plans", null);
            SwingUtilities.invokeLater(() -> showPlans(data));
          } catch (Exception e) {
            log.debug("套餐读取失败: {}", e.getMessage());
            SwingUtilities.invokeLater(() -> {
                planCards.removeAll();
                planCards.setLayout(new GridLayout(1, 1));
                planCards.add(emptyState("暂时无法读取套餐", "请稍后点击刷新重试。"));
                planComparison.removeAll();
                planCards.revalidate();
                planCards.repaint();
                planComparison.revalidate();
                planComparison.repaint();
            });
          }
        });
    }

    private void showPlans(JsonNode data) {
            JsonNode plans = data.path("plans");
            planCards.removeAll();
            if (!plans.isArray() || plans.isEmpty()) {
                planCards.setLayout(new GridLayout(1, 1));
                planCards.add(emptyState("暂无套餐", "请稍后刷新。"));
                planComparison.removeAll();
            } else {
                planCards.setLayout(new GridLayout(1, plans.size(), 16, 0));
                plans.forEach(plan -> planCards.add(planCard(plan)));
                showPlanComparison(plans);
            }
            planCards.revalidate();
            planCards.repaint();
            planComparison.revalidate();
            planComparison.repaint();
            SwingUtilities.invokeLater(() -> plansScroll.getVerticalScrollBar().setValue(0));
    }

    private JPanel planCard(JsonNode plan) {
        boolean recommended = plan.path("recommended").asBoolean(false);
        JPanel card = new PlanCard(recommended);
        card.setLayout(new BorderLayout(0, 16));
        card.setBorder(new EmptyBorder(21, 22, 20, 22));
        String key = plan.path("plan").asText("");
        String title = plan.path("name").asText("套餐");
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(label(recommended ? "最受推荐" : "JOBPILOT PLAN", 10,
                recommended ? ACCENT : MUTED, Font.BOLD), BorderLayout.NORTH);
        top.add(label(title, 19, INK, Font.BOLD), BorderLayout.CENTER);
        String summary = switch (key) {
            case "trial" -> "先体验求职投递流程";
            case "standard" -> "日常求职与 AI 沟通";
            case "advanced" -> "多方向求职与智能策略";
            default -> "按自己的节奏选择";
        };
        top.add(label(summary, 11, MUTED, Font.PLAIN), BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);

        JPanel middle = new JPanel();
        middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
        middle.setOpaque(false);
        JsonNode durations = plan.path("durations");
        if (durations.isArray() && !durations.isEmpty()) {
            JsonNode first = durations.get(0);
            JLabel price = label(money(first.path("price_cents").asInt()), 31, INK, Font.BOLD);
            price.setAlignmentX(Component.LEFT_ALIGNMENT);
            middle.add(price);
            JLabel term = label("起 · " + first.path("days").asInt() + " 天", 11, MUTED, Font.PLAIN);
            term.setAlignmentX(Component.LEFT_ALIGNMENT);
            middle.add(term);
            middle.add(Box.createVerticalStrut(15));
            for (JsonNode duration : durations) {
                JPanel row = new JPanel(new BorderLayout());
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 27));
                row.add(label(duration.path("days").asInt() + " 天", 12, MUTED, Font.PLAIN), BorderLayout.WEST);
                row.add(label(money(duration.path("price_cents").asInt()), 12, INK, Font.BOLD), BorderLayout.EAST);
                middle.add(row);
            }
        }
        card.add(middle, BorderLayout.CENTER);
        JsonNode quotas = plan.path("quotas");
        JPanel bottom = new JPanel(new GridLayout(2, 1, 0, 4));
        bottom.setOpaque(false);
        bottom.add(label("每平台每日投递  " + quota(quotas, "max_daily_apply", "个"),
                12, recommended ? ACCENT : INK, Font.BOLD));
        bottom.add(label("每日 AI 分析  " + quota(quotas, "max_daily_ai_analysis", "次"),
                11, MUTED, Font.PLAIN));
        card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    private void showPlanComparison(JsonNode plans) {
        planComparison.removeAll();
        JPanel card = card(new BorderLayout(0, 17));
        card.add(sectionHead("一眼看清套餐差异", "投递额度按平台各算一份、按天重置；功能以当前服务端配置为准"), BorderLayout.NORTH);
        JPanel matrix = new JPanel(new GridLayout(0, 4, 0, 0));
        matrix.setOpaque(false);
        matrix.add(matrixCell("功能与额度", true, false));
        plans.forEach(plan -> matrix.add(matrixCell(plan.path("name").asText("套餐"), true, true)));
        String[][] quotaRows = {
                {"每平台每日投递", "max_daily_apply", "个"},
                {"每日 AI 分析", "max_daily_ai_analysis", "次"},
                {"可用简历", "max_resume_count", "份"},
                {"岗位画像", "max_job_profile_count", "个"}
        };
        for (String[] row : quotaRows) {
            matrix.add(matrixCell(row[0], false, false));
            plans.forEach(plan -> matrix.add(matrixCell(
                    quota(plan.path("quotas"), row[1], row[2]), false, true)));
        }
        String[][] featureRows = {
                {"高级筛选", "advanced_filter"},
                {"AI 打招呼", "ai_greeting"},
                {"AI 匹配理由", "ai_explanation"},
                {"智能投递", "smart_apply"},
                {"多简历管理", "multi_resume"},
                {"自然语言规则", "natural_language_rule"}
        };
        for (String[] row : featureRows) {
            matrix.add(matrixCell(row[0], false, false));
            plans.forEach(plan -> {
                JsonNode features = plan.path("features");
                matrix.add(matrixCell(features.isObject()
                        ? features.path(row[1]).asBoolean(false) ? "✓  支持" : "—"
                        : "未公布", false, true));
            });
        }
        card.add(matrix, BorderLayout.CENTER);
        planComparison.add(card, BorderLayout.CENTER);
    }

    private static JLabel matrixCell(String value, boolean heading, boolean centered) {
        JLabel cell = label(value, heading ? 12 : 11,
                heading ? INK : value.startsWith("✓") ? ACCENT : MUTED,
                heading || value.startsWith("✓") ? Font.BOLD : Font.PLAIN);
        cell.setHorizontalAlignment(centered ? SwingConstants.CENTER : SwingConstants.LEFT);
        cell.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                new EmptyBorder(9, centered ? 3 : 0, 9, 3)));
        return cell;
    }

    private static String quota(JsonNode quotas, String key, String unit) {
        JsonNode value = quotas.path(key);
        return value.isNumber() ? value.asInt() + " " + unit : "未公布";
    }

    private static String money(int cents) {
        return String.format(java.util.Locale.CHINA, "¥%.2f", cents / 100.0);
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

    private JPanel sidebar(JPanel pages) {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(NAV);
        side.setPreferredSize(new Dimension(212, 0));
        side.setBorder(new EmptyBorder(28, 17, 22, 17));
        JPanel top = stack();
        top.setBackground(NAV);
        JLabel mark = label("◈  JobPilot", 22, WHITE, Font.BOLD);
        mark.setBorder(new EmptyBorder(0, 10, 3, 0));
        top.add(mark);
        JLabel tagline = label("求职投递助手", 12, new Color(149, 164, 191), Font.PLAIN);
        tagline.setBorder(new EmptyBorder(0, 11, 30, 0));
        top.add(tagline);
        JLabel menu = label("工作区", 11, new Color(125, 143, 174), Font.BOLD);
        menu.setBorder(new EmptyBorder(0, 11, 10, 0));
        top.add(menu);
        String[][] items = {{"▦  投递工作台", "delivery"}, {"✦  AI 话术", "ai"},
                {"▣  卡密管理", "license"}, {"◇  会员套餐", "plans"}};
        List<JButton> buttons = new ArrayList<>();
        for (String[] item : items) {
            JButton button = new NavButton(item[0]);
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setFocusPainted(false);
            button.setBorder(new EmptyBorder(12, 15, 12, 10));
            button.setBackground(NAV);
            button.setForeground(new Color(186, 197, 216));
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.putClientProperty("JButton.buttonType", "roundRect");
            button.addActionListener(e -> {
                ((CardLayout) pages.getLayout()).show(pages, item[1]);
                buttons.forEach(other -> {
                    boolean selected = other == button;
                    other.setBackground(selected ? ACCENT : NAV);
                    other.setForeground(selected ? WHITE : new Color(186, 197, 216));
                });
                if ("ai".equals(item[1])) loadAi();
                if ("license".equals(item[1])) loadLicense();
                if ("plans".equals(item[1])) loadPlans();
            });
            buttons.add(button);
            top.add(button);
            top.add(Box.createVerticalStrut(4));
        }
        buttons.get(0).setBackground(ACCENT);
        buttons.get(0).setForeground(WHITE);
        side.add(top, BorderLayout.NORTH);
        JPanel bottom = stack();
        bottom.setBackground(NAV);
        JLabel local = label("●  本机运行", 12, new Color(145, 224, 186), Font.BOLD);
        local.setBorder(new EmptyBorder(0, 10, 10, 0));
        bottom.add(local);
        JButton quit = new JButton("退出 JobPilot");
        quit.setHorizontalAlignment(SwingConstants.LEFT);
        quit.setForeground(new Color(186, 197, 216));
        quit.setBackground(NAV);
        quit.setContentAreaFilled(false);
        quit.setBorderPainted(false);
        quit.setBorder(new EmptyBorder(10, 12, 10, 10));
        quit.addActionListener(e -> quit());
        bottom.add(quit);
        side.add(bottom, BorderLayout.SOUTH);
        return side;
    }

    private static JPanel page(String title, String subtitle, Component body) {
        JPanel page = new JPanel(new BorderLayout(0, 22));
        page.setBackground(CANVAS);
        page.setBorder(new EmptyBorder(30, 32, 30, 32));
        page.add(pageTitle(title, subtitle), BorderLayout.NORTH);
        page.add(body, BorderLayout.CENTER);
        return page;
    }

    private static JPanel formColumn(int width, JComponent... components) {
        JPanel column = new JPanel(new GridBagLayout());
        column.setBackground(CANVAS);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < components.length; i++) {
            c.gridy = i;
            c.insets = new Insets(0, 0, 14, 0);
            column.add(components[i], c);
        }
        c.gridy = components.length;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 0);
        column.add(Box.createVerticalGlue(), c);
        column.setPreferredSize(new Dimension(width, column.getPreferredSize().height));
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(CANVAS);
        outer.add(column, BorderLayout.WEST);
        return outer;
    }

    private static JPanel pageTitle(String title, String subtitle) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        panel.add(label(title, 25, INK, Font.BOLD), BorderLayout.NORTH);
        panel.add(label(subtitle, 13, MUTED, Font.PLAIN), BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel sectionHead(String title, String subtitle) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        panel.add(label(title, 16, INK, Font.BOLD), BorderLayout.NORTH);
        panel.add(label(subtitle, 12, MUTED, Font.PLAIN), BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel emptyState(String title, String detail) {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(WHITE);
        JPanel message = new JPanel(new GridLayout(0, 1, 0, 10));
        message.setOpaque(false);
        JLabel icon = label("◇", 32, new Color(166, 176, 194), Font.PLAIN);
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel heading = label(title, 17, INK, Font.BOLD);
        heading.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel explanation = label(detail, 12, MUTED, Font.PLAIN);
        explanation.setHorizontalAlignment(SwingConstants.CENTER);
        message.add(icon);
        message.add(heading);
        message.add(explanation);
        outer.add(message);
        return outer;
    }

    private static JLabel label(String text, int size, Color color, int weight) {
        JLabel result = new JLabel(text);
        result.setFont(new Font(Font.SANS_SERIF, weight, size));
        result.setForeground(color);
        return result;
    }

    private static JPanel card(LayoutManager layout) {
        JPanel panel = new RoundedCard(layout);
        panel.setBorder(new EmptyBorder(20, 22, 20, 22));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static final class RoundedCard extends JPanel {
        private RoundedCard(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(WHITE);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class PlanCard extends JPanel {
        private final boolean recommended;
        private PlanCard(boolean recommended) {
            this.recommended = recommended;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(WHITE);
            g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 18, 18);
            g.setStroke(new BasicStroke(recommended ? 2f : 1f));
            g.setColor(recommended ? ACCENT : LINE);
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class NavButton extends JButton {
        private NavButton(String title) {
            super(title);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getBackground());
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class TabButton extends JButton {
        private boolean selected;
        private TabButton(String title) {
            super(title);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            setBorder(new EmptyBorder(10, 18, 10, 18));
            setForeground(MUTED);
        }
        @Override public void setSelected(boolean value) {
            super.setSelected(value);
            selected = value;
            setForeground(value ? WHITE : MUTED);
            repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            if (selected) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(ACCENT);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static JPanel stack() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(CANVAS);
        return panel;
    }

    private static JPanel grid2() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 14, 8));
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel field(String title, Component component) {
        JPanel panel = new JPanel(new BorderLayout(0, 7));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(5, 0, 8, 0));
        panel.add(label(title, 12, MUTED, Font.BOLD), BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JScrollPane scroll(Component content) {
        JScrollPane pane = new JScrollPane(content);
        pane.setBorder(null);
        pane.getVerticalScrollBar().setUnitIncrement(18);
        return pane;
    }

    private static void primary(JButton button) {
        button.setBackground(ACCENT);
        button.setForeground(WHITE);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        button.setPreferredSize(new Dimension(Math.max(104, button.getPreferredSize().width + 20), 38));
        button.putClientProperty("JButton.buttonType", "roundRect");
    }

    private static void secondary(JButton button) {
        button.setPreferredSize(new Dimension(Math.max(80, button.getPreferredSize().width + 18), 38));
        button.putClientProperty("JButton.buttonType", "roundRect");
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
        box.addItem(new Choice(cityNames ? "" : "0", cityNames ? "全国" : "不限"));
        if (options.isArray()) {
            options.forEach(option -> {
                String value = cityNames ? option.path("name").asText("")
                        : option.path("code").asText("");
                if (!value.isEmpty() && !"不限".equals(option.path("name").asText(""))) {
                    box.addItem(new Choice(value, option.path("name").asText("")));
                }
            });
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
