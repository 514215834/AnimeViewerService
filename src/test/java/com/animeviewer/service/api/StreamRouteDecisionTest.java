package com.animeviewer.service.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** v0.28 P1 播放路由决策纯函数单测：直发/转封装/转码三态 + 音轨切换强制管道（探测定案 5）。 */
class StreamRouteDecisionTest {

    private static final StreamController.Route route(String ext, String vcodec, boolean on, Integer audio) {
        return StreamController.decideRoute(ext, vcodec, on, audio);
    }

    @Test
    void 可解编码_关闭转码_保持既有路径() {
        assertEquals(StreamController.Route.DIRECT, route("mkv", "h264", false, null));
        assertEquals(StreamController.Route.DIRECT, route("mp4", "h264", false, null));
        assertEquals(StreamController.Route.REMUX, route("avi", "h264", false, null));
    }

    @Test
    void 不可解编码_开启转码_进转码管道() {
        assertEquals(StreamController.Route.TRANSCODE, route("mkv", "hevc", true, null));
        assertEquals(StreamController.Route.TRANSCODE, route("mp4", "hevc", true, null));
        assertEquals(StreamController.Route.TRANSCODE, route("avi", "mpeg4", true, null));
        assertEquals(StreamController.Route.TRANSCODE, route("ts", "mpeg2video", true, null));
    }

    @Test
    void 不可解编码_关闭转码_保持旧路径不转码() {
        assertEquals(StreamController.Route.DIRECT, route("mkv", "hevc", false, null));
        assertEquals(StreamController.Route.REMUX, route("avi", "vc1", false, null));
    }

    @Test
    void vcodec缺失或空白_保守回退旧路径_未知非空编码转码兜底() {
        assertEquals(StreamController.Route.DIRECT, route("mkv", null, true, null));
        assertEquals(StreamController.Route.REMUX, route("avi", "", true, null));
        // 白名单语义：浏览器视频解码面基本只有 h264/vp8/vp9/av1——未知非空编码转码兜底（扩大可播面）
        assertEquals(StreamController.Route.TRANSCODE, route("mkv", "未知编码", true, null));
    }

    @Test
    void 音轨参数强制管道_direct不可换轨() {
        // 可解编码 + 音轨切换 → 转封装（视频 copy + 音频 aac）
        assertEquals(StreamController.Route.REMUX, route("mkv", "h264", true, 1));
        assertEquals(StreamController.Route.REMUX, route("mkv", "h264", false, 0));
        // 不可解编码 + 音轨切换 → 全转码
        assertEquals(StreamController.Route.TRANSCODE, route("mkv", "hevc", true, 1));
        assertEquals(StreamController.Route.TRANSCODE, route("mkv", "hevc", false, 1));
    }
}
