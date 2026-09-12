package com.animeviewer.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/** AnimeViewerService v0.14 —— AnimeViewer 本地媒体服务（迭代文档 §5A S1~S4）。
 *  职责：媒体库扫描（SQLite 索引）/ 文件名识别与 Bangumi 匹配 / mp4 直连 Range 流与 mkv 转封装 fMP4 流。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class AnimeViewerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnimeViewerServiceApplication.class, args);
    }
}
