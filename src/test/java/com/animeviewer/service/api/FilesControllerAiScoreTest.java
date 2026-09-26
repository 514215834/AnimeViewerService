package com.animeviewer.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** v0.30 A7 匹配置信度解析纯函数（0~100 之外视为无效——不自动绑定，对齐 HitVerdict 容错惯例）。 */
class FilesControllerAiScoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();


    @Test
    void acceptsIntegersInRange() throws Exception {
        assertEquals(100, FilesController.parseAiScore(MAPPER.readTree("{\"score\":100}")));
        assertEquals(98, FilesController.parseAiScore(MAPPER.readTree("{\"score\":98}")));
        assertEquals(0, FilesController.parseAiScore(MAPPER.readTree("{\"score\":0}")));
        assertEquals(85, FilesController.parseAiScore(MAPPER.readTree("{\"reason\":\"同一作品\",\"score\":85}")));
    }

    @Test
    void outOfRangeOrMalformedReturnsNull() throws Exception {
        assertNull(FilesController.parseAiScore(null));
        assertNull(FilesController.parseAiScore(MAPPER.readTree("{\"score\":101}")));
        assertNull(FilesController.parseAiScore(MAPPER.readTree("{\"score\":-1}")));
        assertNull(FilesController.parseAiScore(MAPPER.readTree("{\"score\":\"98\"}")));
        assertNull(FilesController.parseAiScore(MAPPER.readTree("{}")));
        assertNull(FilesController.parseAiScore(MAPPER.readTree("[{\"score\":98}]")));
        assertNull(FilesController.parseAiScore(MAPPER.readTree("null")));
        assertNull(FilesController.parseAiScore(MAPPER.readTree("98")));
    }
}
