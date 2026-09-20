package com.animeviewer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S1 鉴权与健康检查冒烟：/api/health 免 Token；其余 /api/** 无 Token 401、带 Token 200。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"av.data-dir=target/test-data", "av.scan.auto-on-start=false"})
class AuthSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthOpenAndOthersRequireToken() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("AnimeViewerService"))
                .andExpect(jsonPath("$.version").value("0.27.0"));

        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/files")).andExpect(status().isUnauthorized());

        Path tokenFile = Path.of("target", "test-data", "token");
        String token = Files.readString(tokenFile).trim();

        mvc.perform(get("/api/status").header("X-AV-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.27.0"));
        mvc.perform(get("/api/files?limit=10").param("token", token))
                .andExpect(status().isOk());
    }
}
