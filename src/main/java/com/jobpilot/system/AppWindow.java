package com.jobpilot.system;

import com.jobpilot.license.LicenseService;
import com.jobpilot.license.LicenseStatus;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.desktop.OpenFilesEvent;
import java.awt.desktop.OpenFilesHandler;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 应用自带的控制台窗口。
 *
 * <p><b>为什么需要它</b>：这个软件的界面在 Chrome 的 {@code --app=} 窗口里，
 * JVM 自己是个没有窗口的后台进程。macOS 上双击一个已在运行的 .app 时，
 * 系统只会去"激活"它——而激活一个没有窗口的进程，结果就是 Dock 图标
 * 一直跳、什么都不出现，用户以为软件打不开。
 *
 * <p>有了这个窗口，激活就有东西可唤出：双击 → 控制台到前台 → 点
 * 「打开投递界面」把 Chrome 窗口再拉起来。顺带它也是个有用的状态面板
 * （端口、卡密、剩余天数），不用再去翻日志。
 *
 * <p>只依赖 JDK 自带的 Swing，不引第三方 UI 库；无头环境（CI、服务器）
 * 直接跳过，不影响启动。
 */
@Slf4j
public class AppWindow {

    private final int port;
    private final LicenseService licenseService;
    private final Runnable onQuit;

    private JFrame frame;
    private JLabel stateLabel;
    private JLabel licenseLabel;
    private JLabel portLabel;
    private final ScheduledExecutorService refresher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "app-window-refresh");
                t.setDaemon(true);
                return t;
            });

    public AppWindow(int port, LicenseService licenseService, Runnable onQuit) {
        this.port = port;
        this.licenseService = licenseService;
        this.onQuit = onQuit;
    }

    /**
     * 无头环境返回 false，调用方据此判断要不要建窗口。
     *
     * <p>注意：Spring Boot 默认会把 {@code java.awt.headless} 设成 true
     * （见 {@code SpringApplication.configureHeadlessProperty}），
     * {@code GraphicsEnvironment} 构造时就会缓存这个值。所以启动类里必须
     * 先 {@code setHeadless(false)}，在这儿才判得准。
     */
    public static boolean supported() {
        return !GraphicsEnvironment.isHeadless();
    }

    /** 在 EDT 上建窗口并显示。幂等：重复调用只激活已有窗口。 */
    public void show() {
        if (!supported()) {
            return;
        }
        SwingUtilities.invokeLater(this::buildAndShow);
    }

    private void buildAndShow() {
        if (frame != null) {
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            return;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignore) {
            // 用默认皮肤也能看，不值得为它失败
        }

        frame = new JFrame("JobPilot");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setSize(420, 260);
        frame.setMinimumSize(new Dimension(420, 260));
        // 不抢焦点：启动时 Chrome 窗口才是主角
        frame.setAutoRequestFocus(false);
        frame.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(16, 16, 16, 16));
        root.setBackground(Color.WHITE);

        JPanel info = new JPanel(new GridLayout(0, 1, 4, 4));
        info.setBackground(Color.WHITE);
        stateLabel = addRow(info, "运行状态", "正在运行");
        portLabel = addRow(info, "本机地址", "http://127.0.0.1:" + port);
        licenseLabel = addRow(info, "卡密", "读取中…");
        root.add(info, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBackground(Color.WHITE);
        JButton open = new JButton("打开投递界面");
        open.addActionListener(e -> openUi());
        JButton quit = new JButton("退出");
        quit.addActionListener(e -> quit());
        buttons.add(open);
        buttons.add(quit);
        root.add(buttons, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setVisible(true);

        // 卡密状态会变（心跳、上报），定时刷一下
        refresher.scheduleAtFixedRate(this::refreshLicense, 0, 15, TimeUnit.SECONDS);
    }

    private JLabel addRow(JPanel panel, String key, String value) {
        JLabel label = new JLabel(key + "：" + value);
        label.setFont(label.getFont().deriveFont(13f));
        panel.add(label);
        return label;
    }

    private void refreshLicense() {
        try {
            LicenseStatus s = licenseService.status();
            String text;
            if (!s.isEnabled()) {
                text = "自用模式（未启用校验）";
            } else if (s.isAllowed()) {
                text = s.getState() + " · " + (s.getMessage() == null ? "" : s.getMessage());
                if (s.getRemainingDays() != null) {
                    text += " · 剩余 " + s.getRemainingDays() + " 天";
                }
            } else {
                text = s.getState() + " · " + (s.getMessage() == null ? "" : s.getMessage());
            }
            setText(licenseLabel, "卡密", text);
            setText(stateLabel, "运行状态", "正在运行");
        } catch (Exception e) {
            log.debug("刷新控制台卡密状态失败: {}", e.getMessage());
        }
    }

    private static void setText(JLabel label, String key, String value) {
        SwingUtilities.invokeLater(() -> label.setText(key + "：" + value));
    }

    /** 把投递界面拉起来。Chrome 窗口被关掉之后靠这个再开一个 */
    private void openUi() {
        String url = "http://127.0.0.1:" + port + "/";
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                new ProcessBuilder("open", "-na", "Google Chrome", "--args", "--app=" + url)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } else {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (IOException e) {
            log.warn("打开投递界面失败: {}", e.getMessage());
            JOptionPane.showMessageDialog(frame, "打开失败，请手动访问 " + url,
                    "JobPilot", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void quit() {
        int r = JOptionPane.showConfirmDialog(frame, "确定退出 JobPilot？未完成的投递会中断。",
                "JobPilot", JOptionPane.OK_CANCEL_OPTION);
        if (r == JOptionPane.OK_OPTION) {
            onQuit.run();
        }
    }

    /** macOS 上双击 Dock 图标/再次打开 .app 时会把控制台带到前台 */
    public static void installReopenHandler(Runnable onReopen) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
                desktop.setOpenFileHandler(new OpenFilesHandler() {
                    @Override
                    public void openFiles(OpenFilesEvent e) {
                        onReopen.run();
                    }
                });
            }
        } catch (Exception ignore) {
            // 老 JDK 或非 macOS 没有这套 API，不影响主流程
        }
    }

    public void dispose() {
        refresher.shutdownNow();
        if (frame != null) {
            SwingUtilities.invokeLater(frame::dispose);
        }
    }

    /** 供测试/日志用 */
    public List<String> summary() {
        return List.of("port=" + port);
    }
}
