package com.animeviewer.service.stream;

import com.animeviewer.service.ServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** S4 ffmpeg 转封装流：mkv/avi/ts/wmv/flv 等容器 → fMP4（frag_keyframe+empty_moov，-c copy 不重编码）。
 *  「直播式」流不支持随机跳转：seek 由前端以 ?t= 参数重拉实现（服务端 -ss 重启转封装）。
 *  并发上限由信号量控制，超限返回 503。 */
@Component
public class RemuxStreamer {

    private static final Logger log = LoggerFactory.getLogger(RemuxStreamer.class);

    private final ServiceProperties props;
    private final Semaphore permits;

    public RemuxStreamer(ServiceProperties props) {
        this.props = props;
        this.permits = new Semaphore(Math.max(1, props.remux().maxConcurrent()));
    }

    public boolean tryAcquire() {
        try {
            return permits.tryAcquire(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        permits.release();
    }

    public int maxConcurrent() {
        return Math.max(1, props.remux().maxConcurrent());
    }

    /** 将转封装流拷贝到响应输出流（阻塞直至完成 / 客户端断开）。返回是否成功产出字节。
     *  v0.28 P2：audioIdx 非空 = 音轨切换——视频仍 copy（廉价），音频转 aac（flac/dts 不能
     *  copy 进 fMP4 稳妥播放；轨序与 ffprobe -select_streams a 枚举同口径）。null = 既有行为。 */
    public boolean pump(String filePath, double seekSeconds, OutputStream out) {
        return pump(filePath, seekSeconds, null, out);
    }

    public boolean pump(String filePath, double seekSeconds, Integer audioIdx, OutputStream out) {
        var pb = new ProcessBuilder(buildCommand(filePath, seekSeconds, audioIdx));
        pb.redirectErrorStream(false);
        Process proc = null;
        boolean wroteAny = false;
        try {
            proc = pb.start();
            // stderr 侧线读取（防止缓冲区塞满阻塞进程），仅保留尾部用于日志
            TailReader tail = new TailReader(proc.getErrorStream());
            Thread tailThread = new Thread(tail, "ffmpeg-stderr");
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
            proc.waitFor(30, TimeUnit.SECONDS);
            if (proc.exitValue() != 0) {
                log.warn("ffmpeg 退出码 {}: {} tail={}", proc.exitValue(), filePath, tail.tail());
                return wroteAny;
            }
            return wroteAny;
        } catch (Exception e) {
            // 客户端中途断开（seek 重拉 / 暂停退出）属正常路径，仅 debug
            log.debug("转封装流中断: {} ({})", filePath, e.toString());
            return wroteAny;
        } finally {
            if (proc != null) proc.destroyForcibly();
        }
    }

    /** 命令组装（包级可见，供单测断言参数形态）。audioIdx 非空 = 音轨切换（-c:v copy + -c:a aac）；
     *  null = 既有 -c copy 行为（零回归）。 */
    String[] buildCommand(String filePath, double seekSeconds, Integer audioIdx) {
        var cmd = new java.util.ArrayList<String>();
        cmd.add(props.ffmpegPath());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        if (seekSeconds > 0) {
            cmd.add("-ss");
            cmd.add(String.valueOf(seekSeconds));
        }
        cmd.add("-i");
        cmd.add(filePath);
        // 仅取首视频轨 + 指定音频轨：字幕/数据轨不能进 mp4（ass 等 copy 会直接报错）
        cmd.add("-map");
        cmd.add("0:v:0");
        cmd.add("-map");
        cmd.add("0:a:" + (audioIdx == null ? "0?" : Math.max(0, Math.min(audioIdx, 63)) + "?"));
        cmd.add("-sn");
        cmd.add("-dn");
        if (audioIdx == null) {
            cmd.add("-c");
            cmd.add("copy");
        } else {
            cmd.add("-c:v");
            cmd.add("copy");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("192k");
        }
        cmd.add("-movflags");
        cmd.add("+frag_keyframe+empty_moov");
        cmd.add("-f");
        cmd.add("mp4");
        cmd.add("pipe:1");
        return cmd.toArray(String[]::new);
    }

    /** stderr 尾部缓冲（≤2KB） */
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
