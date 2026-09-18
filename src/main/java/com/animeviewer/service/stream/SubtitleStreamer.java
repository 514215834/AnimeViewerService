package com.animeviewer.service.stream;

import com.animeviewer.service.ServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/** v0.23 SB1 内封字幕提取：ffmpeg -map 0:s:N -f webvtt pipe:1 将文本字幕轨（srt/ass/ssa/subrip/
 *  mov_text 等可转轨）转为 WebVTT 流式输出。已知取舍（探测定案，2026-09-18）：ass 的 {\pos}/{\fad}
 *  等特效标签被剥除、斜体保留为 <i>——可读性优先，特效丢失如实记录。
 *  结构与 RemuxStreamer 同模式（信号量限并发 + stderr 尾部日志），独立组件避免触碰 v0.14 稳定路径。 */
@Component
public class SubtitleStreamer {

    private static final Logger log = LoggerFactory.getLogger(SubtitleStreamer.class);

    /** 文本字幕提取为短时小流量输出，并发 2 足够 */
    private final Semaphore permits = new Semaphore(2);

    private final ServiceProperties props;

    public SubtitleStreamer(ServiceProperties props) {
        this.props = props;
    }

    public boolean tryAcquire() {
        try {
            return permits.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        permits.release();
    }

    /** 将第 trackIdx 条字幕轨（0 基）转成 WebVTT 拷贝到输出流。返回是否产出内容（false = 轨不存在或转换失败）。 */
    public boolean pump(String filePath, int trackIdx, OutputStream out) {
        var pb = new ProcessBuilder(buildCommand(filePath, trackIdx));
        pb.redirectErrorStream(false);
        Process proc = null;
        boolean wroteAny = false;
        try {
            proc = pb.start();
            TailReader tail = new TailReader(proc.getErrorStream());
            Thread tailThread = new Thread(tail, "ffmpeg-sub-stderr");
            tailThread.setDaemon(true);
            tailThread.start();

            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (!wroteAny && n > 0) wroteAny = true;
                }
            }
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (proc.exitValue() != 0) {
                log.warn("字幕提取退出码 {}: {} track={} tail={}", proc.exitValue(), filePath, trackIdx, tail.tail());
                return wroteAny;
            }
            return wroteAny;
        } catch (Exception e) {
            // 客户端中途断开（换轨/关闭播放页）属正常路径
            log.debug("字幕提取流中断: {} track={} ({})", filePath, trackIdx, e.toString());
            return wroteAny;
        } finally {
            if (proc != null) proc.destroyForcibly();
        }
    }

    /** 命令组装（包级可见，供单测断言参数形态）：-map 0:s:N 中 N 为第 N 条字幕轨（非流 index） */
    String[] buildCommand(String filePath, int trackIdx) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.ffmpegPath());
        cmd.addAll(List.of(
                "-hide_banner", "-loglevel", "error",
                "-i", filePath,
                "-map", "0:s:" + trackIdx,
                "-f", "webvtt",
                "pipe:1"));
        return cmd.toArray(String[]::new);
    }

    /** stderr 尾部缓冲（≤2KB），与 RemuxStreamer.TailReader 同实现 */
    private static final class TailReader implements Runnable {
        private final InputStream in;
        private final StringBuilder tail = new StringBuilder();

        TailReader(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    tail.append(new String(buf, 0, n));
                    if (tail.length() > 2048) tail.delete(0, tail.length() - 2048);
                }
            } catch (Exception ignored) {
            }
        }

        String tail() {
            return tail.toString().strip();
        }
    }
}
