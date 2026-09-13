package com.animeviewer.service;

import com.animeviewer.service.download.DownloadStates;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.16 任务状态机纯函数用例（含原型实测结论：元数据 complete ≠ 任务完成） */
class DownloadStatesTest {

    @Test
    void mapEngineStatus() {
        assertEquals("downloading", DownloadStates.mapTaskStatus("active", true));
        assertEquals("metadata", DownloadStates.mapTaskStatus("active", false));
        assertEquals("queued", DownloadStates.mapTaskStatus("waiting", false));
        assertEquals("paused", DownloadStates.mapTaskStatus("paused", true));
        assertEquals("error", DownloadStates.mapTaskStatus("error", false));
        assertEquals("completed", DownloadStates.mapTaskStatus("complete", true));
        // 元数据 gid 的 complete：等待收养后继负载 gid，状态停留 metadata
        assertEquals("metadata", DownloadStates.mapTaskStatus("complete", false));
        assertEquals("queued", DownloadStates.mapTaskStatus(null, false));
    }

    @Test
    void filesKnownDetection() {
        assertFalse(DownloadStates.filesKnown(List.of("[METADATA]ubuntu.iso")));
        assertFalse(DownloadStates.filesKnown(List.<String>of()));
        assertFalse(DownloadStates.filesKnown(null));
        assertTrue(DownloadStates.filesKnown(List.of("G:/dl/ubuntu.iso")));
    }

    @Test
    void nonTerminalSet() {
        assertEquals(4, DownloadStates.NON_TERMINAL.size());
        assertTrue(DownloadStates.NON_TERMINAL.containsAll(List.of("queued", "metadata", "downloading", "paused")));
        assertFalse(DownloadStates.NON_TERMINAL.contains("completed"));
        assertFalse(DownloadStates.NON_TERMINAL.contains("error"));
    }
}
