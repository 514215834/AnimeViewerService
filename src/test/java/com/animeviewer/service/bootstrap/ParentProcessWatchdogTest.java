package com.animeviewer.service.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.0 D2 桌面壳父进程心跳存活判定单测 */
class ParentProcessWatchdogTest {

    @Test
    void 当前进程视为存活() {
        assertTrue(ParentProcessWatchdog.parentAlive(ProcessHandle.current().pid()));
    }

    @Test
    void 不存在的极远pid判定失联() {
        // 2_000_000_000 远超 Windows 正常 pid 空间的活跃值，进程不存在 → Optional.empty
        assertFalse(ParentProcessWatchdog.parentAlive(2_000_000_000L));
    }

    @Test
    void 零与负数直接判失联() {
        assertFalse(ParentProcessWatchdog.parentAlive(0));
        assertFalse(ParentProcessWatchdog.parentAlive(-1));
    }
}
