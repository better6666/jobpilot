package com.jobpilot.delivery;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局运行权。
 *
 * 四个平台共用同一个浏览器上下文和同一条 dispatcher 线程（见 BrowserManager），
 * 所以同一时刻只允许一个平台在跑。不串起来的话，Boss 跑批里再点"智联开始"，
 * 两个任务会排在一条线程上先后执行，而两个管理页都显示"运行中"——
 * 用户以为在同时跑，实际第二个要等第一个跑完，中间没有任何提示。
 *
 * 谁 start() 谁 acquire，跑批结束（无论正常结束还是抛异常）必须 release，
 * 所以释放放在 submitAsync 任务体的 finally 里，不依赖调用方记得。
 */
@Component
public class RunCoordinator {

    private final AtomicReference<String> running = new AtomicReference<>();

    /** 占用运行权。返回 null = 抢到了；返回非 null = 被这个平台占着 */
    public String acquire(String platform) {
        return running.compareAndSet(null, platform) ? null : running.get();
    }

    public void release(String platform) {
        running.compareAndSet(platform, null);
    }

    /** 当前正在跑的平台标识，没有则 null */
    public String current() {
        return running.get();
    }
}
