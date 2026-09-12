package com.animeviewer.service.media;

import com.animeviewer.service.ServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 外部进程可用性探测（ffmpeg / ffprobe），结果缓存（进程启动探测只在首次与健康检查时进行） */
@Component
public class ExternalTool {

    private static final Logger log = LoggerFactory.getLogger(ExternalTool.class);

    private final ServiceProperties props;
    private volatile Boolean ffmpegOk;
    private volatile Boolean ffprobeOk;

    public ExternalTool(ServiceProperties props) {
        this.props = props;
    }

    public boolean ffmpegAvailable() {
        Boolean v = ffmpegOk;
        if (v == null) {
            v = probe(props.ffmpegPath());
            ffmpegOk = v;
            if (!v) log.warn("ffmpeg 不可用（路径: {}）——mkv 等容器转封装不可用，mp4/webm 直连流不受影响", props.ffmpegPath());
        }
        return v;
    }

    public boolean ffprobeAvailable() {
        Boolean v = ffprobeOk;
        if (v == null) {
            v = probe(props.ffprobePath());
            ffprobeOk = v;
            if (!v) log.warn("ffprobe 不可用（路径: {}）——媒体探测与扫描降级", props.ffprobePath());
        }
        return v;
    }

    private boolean probe(String cmd) {
        try {
            var p = new ProcessBuilder(cmd, "-version");
            p.redirectErrorStream(true);
            var proc = p.start();
            proc.getInputStream().readAllBytes();
            boolean done = proc.waitFor(10, TimeUnit.SECONDS);
            if (!done) proc.destroyForcibly();
            return done && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
