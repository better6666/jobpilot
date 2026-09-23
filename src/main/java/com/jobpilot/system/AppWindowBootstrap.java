package com.jobpilot.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.context.WebServerApplicationContext;

import javax.swing.SwingUtilities;
import com.formdev.flatlaf.FlatLightLaf;

/** 启动后显示原生 Swing 主窗口。 */
@Slf4j
public class AppWindowBootstrap implements ApplicationListener<ApplicationReadyEvent> {

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!DesktopSupport.supported()) {
            log.info("无头环境，跳过桌面窗口");
            return;
        }
        ApplicationContext context = event.getApplicationContext();
        if (!Boolean.parseBoolean(
                context.getEnvironment().getProperty("jobpilot.console", "true"))) {
            log.info("jobpilot.console=false，跳过桌面窗口");
            return;
        }
        int port = context instanceof WebServerApplicationContext web
                ? web.getWebServer().getPort() : 9527;
        int windowPort = port;
        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
            NativeMainWindow window = new NativeMainWindow(windowPort, () -> quit(context));
            DesktopSupport.installReopenHandler(window::show);
            window.show();
            log.info("原生桌面窗口已打开（端口 {}）", windowPort);
        });
    }

    private void quit(ApplicationContext context) {
        if (context instanceof ConfigurableApplicationContext configurable) {
            configurable.close();
        }
        System.exit(0);
    }
}
