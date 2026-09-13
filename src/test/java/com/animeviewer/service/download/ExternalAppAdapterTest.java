package com.animeviewer.service.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.18 外部应用直开纯函数用例：拉起命令构造 + 客户端路径就绪检查 */
class ExternalAppAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void launchArgsCarriesClientAndPayload() {
        assertEquals(
                List.of("G:/qbittorrent/qbittorrent.exe", "magnet:?xt=urn:btih:abc&dn=test"),
                ExternalAppAdapter.launchArgs("G:/qbittorrent/qbittorrent.exe", "magnet:?xt=urn:btih:abc&dn=test"));
    }

    @Test
    void pathProblemValidation() throws Exception {
        assertTrue(ExternalAppAdapter.pathProblem(null).contains("未配置"));
        assertTrue(ExternalAppAdapter.pathProblem("").contains("未配置"));
        assertTrue(ExternalAppAdapter.pathProblem("   ").contains("未配置"));
        assertTrue(ExternalAppAdapter.pathProblem("G:/no/such/qbittorrent.exe").contains("不存在"));
        Path real = tempDir.resolve("qbittorrent.exe");
        Files.writeString(real, "stub");
        assertNull(ExternalAppAdapter.pathProblem(real.toString()));
    }
}
