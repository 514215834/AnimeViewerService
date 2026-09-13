package com.animeviewer.service;

import com.animeviewer.service.download.Aria2Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** v0.16 外部实例 RPC 地址归一化用例（aria2 仅服务 /jsonrpc） */
class Aria2EngineTest {

    @Test
    void normalizeRpcUrlVariants() {
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800/rpc"));
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800/jsonrpc"));
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800"));
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800/"));
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800/rpc/"));
    }
}
