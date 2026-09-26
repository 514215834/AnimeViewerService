package com.animeviewer.service.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** v1.0 D2 桌面壳父进程心跳：仅桌面版生效——壳（Tauri）启动本服务时注入
 *  {@code --av.parent-pid=<壳进程pid>}，本组件周期探测壳进程存活，
 *  壳进程死亡（崩溃/被杀）后服务端**优雅退出**（Spring context close →
 *  Aria2Engine 等 @PreDestroy 顺带收尾托管 aria2c 子进程），
 *  防止孤儿进程长期占用 8787 端口。Web 部署形态不注入该参数（默认 0），组件零行为。 */
@Component
public class ParentProcessWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ParentProcessWatchdog.class);

    /** 首查延迟：给壳启动留裕量（壳进程必然先于本服务存活，仅为防御性设计） */
    static final long INITIAL_DELAY_SECONDS = 10;
    /** 检查周期：壳退出后最迟 ~5s 内自杀，期间端口占用窗口可接受 */
    static final long PERIOD_SECONDS = 5;

    private final ApplicationContext context;
    private final long parentPid;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "parent-pid-watchdog");
                t.setDaemon(true);
                return t;
            });

    public ParentProcessWatchdog(ApplicationContext context,
                                 @Value("${av.parent-pid:0}") long parentPid) {
        this.context = context;
        this.parentPid = parentPid;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (parentPid <= 0) {
            return;
        }
        log.info("父进程心跳已启用：壳进程 pid={}，失联 {}s 后本服务将自动退出", parentPid, PERIOD_SECONDS);
        scheduler.scheduleWithFixedDelay(this::check, INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    void check() {
        if (parentAlive(parentPid)) {
            return;
        }
        log.warn("壳进程 {} 已退出，媒体服务随之关闭（@PreDestroy 收尾托管子进程）……", parentPid);
        scheduler.shutdownNow();
        // 独立线程执行优雅退出：SpringApplication.exit 关闭 context（@PreDestroy 生效）后 System.exit 收尾 JVM
        Thread killer = new Thread(() -> {
            int code = SpringApplication.exit(context);
            System.exit(code);
        }, "parent-pid-terminator");
        killer.setDaemon(true);
        killer.start();
    }

    /** 供单测：目标进程存活判定（pid 不存在返回 false；0/负数直接 false） */
    static boolean parentAlive(long pid) {
        if (pid <= 0) {
            return false;
        }
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent();
    }
}
