package com.animeviewer.service;

import com.animeviewer.service.download.Aria2Engine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.16 外部实例 RPC 地址归一化用例（aria2 仅服务 /jsonrpc）；
 *  v1.0 桌面化追加：相对下载目录锚定运行数据目录（数据劈叉修复，§5 v1.0 修复补记三） */
class Aria2EngineTest {

    @Test
    void normalizeRpcUrlVariants() {
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800/rpc"));
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800/jsonrpc"));
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800"));
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800/"));
        assertEquals("http://127.0.0.1:6800/jsonrpc", Aria2Engine.normalizeRpcUrl("http://127.0.0.1:6800/rpc/"));
    }

    @Test
    void 绝对下载目录原样归一不受锚定影响() {
        String winAbs = "G:" + java.io.File.separator + "dl" + java.io.File.separator + "bt";
        assertEquals(Path.of(winAbs).toString(), Path.of(Aria2Engine.absDir(winAbs, "C:" + java.io.File.separator + "any" + java.io.File.separator + "data")).toString());
    }

    @Test
    void 相对下载目录锚定数据目录父层_桌面形态与索引库同址() {
        // 桌面形态：dataDir 绝对（%APPDATA%/AnimeViewer/data）→ 下载目录应落 dataDir 旁的同名 data 下，
        // 与 media.db/token 同址（修复前锚 CWD 劈叉到安装目录）
        String sep = java.io.File.separator;
        String dataDir = "C:" + sep + "Users" + sep + "u" + sep + "AppData" + sep + "Roaming" + sep + "AnimeViewer" + sep + "data";
        String got = Aria2Engine.absDir("." + sep + "data" + sep + "downloads", dataDir);
        assertEquals(Path.of(dataDir, "downloads").toString(), Path.of(got).toString());
        assertFalse(got.contains("AnimeViewer" + sep + "data" + sep + "data"), "不应出现 data/data 双层");
    }

    @Test
    void 相对下载目录锚定数据目录父层_Web形态与既有行为一致() {
        // Web 形态：dataDir=./data（相对 CWD）→ 父层恰为 CWD，下载目录与旧锚 CWD 行为一致
        String sep = java.io.File.separator;
        String got = Aria2Engine.absDir("." + sep + "data" + sep + "downloads", "." + sep + "data");
        assertTrue(got.endsWith("data" + sep + "downloads"), "实际: " + got);
        assertFalse(got.contains("data" + sep + "data" + sep + "downloads"), "不应出现 data/data 双层: " + got);
    }
}
