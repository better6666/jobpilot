package com.jobpilot.system;

import com.jobpilot.license.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 启动尾部把控制台窗口建起来。
 *
 * <p>单独一个监听器而不是塞进 {@link LocalPageOpener}：控制台要拿
 * {@link LicenseService} 和真实端口，那得等 context ready 之后；
 * 而 {@link LocalPageOpener} 已经负责开 Chrome 窗口了，两件事关注点不同。
 *
 * <p>顺序上放在 Chrome 窗口之后：用户第一眼看到的应该还是投递界面，
 * 控制台是"关掉 Chrome 之后还有东西可点"的兜底，不是主角。
 */
@Slf4j
public class AppWindowBootstrap implements ApplicationListener<ApplicationReadyEvent> {

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!AppWindow.supported()) {
            log.info("无头环境，跳过控制台窗口");
            return;
        }
        ApplicationContext context = event.getApplicationContext();
        if (!Boolean.parseBoolean(
                context.getEnvironment().getProperty("jobpilot.console", "true"))) {
            log.info("jobpilot.console=false，跳过控制台窗口");
            return;
        }
        int port = 9527;
        if (context instanceof org.springframework.boot.web.context.WebServerApplicationContext web) {
            port = web.getWebServer().getPort();
        }
        LicenseService licenseService = context.getBean(LicenseService.class);
        AppWindow window = new AppWindow(port, licenseService, () -> quit(context));
        // macOS 上再次双击 .app / 点 Dock 图标时，把控制台带到前台
        AppWindow.installReopenHandler(window::show);
        window.show();
        log.info("控制台窗口已打开（端口 {}）", port);
    }

    private void quit(ApplicationContext context) {
        if (context instanceof ConfigurableApplicationContext configurable) {
            configurable.close();
        }
        System.exit(0);
    }
}
