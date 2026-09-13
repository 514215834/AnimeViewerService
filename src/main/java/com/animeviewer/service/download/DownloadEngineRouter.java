package com.animeviewer.service.download;

import org.springframework.stereotype.Component;

/**
 * v0.18 引擎路由：按 settings.engineType 把引擎操作分派到 Aria2Adapter（托管/外部实例，
 * Aria2Engine 内部已按 engineUrl 区分）/ ExternalAppAdapter（qBittorrent 外部应用直开）。
 */
@Component
public class DownloadEngineRouter {

    private final Aria2Adapter aria2;
    private final ExternalAppAdapter externalApp;

    public DownloadEngineRouter(Aria2Adapter aria2, ExternalAppAdapter externalApp) {
        this.aria2 = aria2;
        this.externalApp = externalApp;
    }

    public DownloadEngine current(DownloadSettings s) {
        return s.qbittorrent() ? externalApp : aria2;
    }
}
