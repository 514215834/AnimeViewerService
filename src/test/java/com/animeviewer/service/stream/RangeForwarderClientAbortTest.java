package com.animeviewer.service.stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.27 A1 客户端断开判定：浏览器关闭/换集/seek 中断连接产生的异常不得被误判为线路故障切线
 *  （用户实测：关闭在线视频时 INFO 日志「直连转发失败…切换为经代理重试」——白拉一次代理流）。 */
class RangeForwarderClientAbortTest {

    @Test
    void springAsyncNotUsableIsClientAbort() {
        // Spring 包装形态（v0.26 用户实测日志原样）：AsyncRequestNotUsableException extends IOException
        IOException e = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write: java.io.IOException: 你的主机中的软件中止了一个已建立的连接。");
        assertTrue(RangeForwarder.isClientAbort(e));
    }

    @Test
    void rawBrokenPipeMessagesAreClientAbort() {
        assertTrue(RangeForwarder.isClientAbort(new IOException("你的主机中的软件中止了一个已建立的连接")));
        assertTrue(RangeForwarder.isClientAbort(new IOException("Broken pipe")));
        // WSAECONNABORTED 英文形态（中文「中止了一个已建立的连接」的原文）：写侧独有，客户端主动中断
        assertTrue(RangeForwarder.isClientAbort(new IOException("Software caused connection abort: write failed")));
    }

    @Test
    void upstreamLineFailuresAreNotClientAbort() {
        // 真实线路故障（DNS 污染超时/拒绝/TLS 掐断/重置）必须继续走切线容灾
        assertFalse(RangeForwarder.isClientAbort(new IOException("connect timed out")));
        assertFalse(RangeForwarder.isClientAbort(new IOException("Connection refused")));
        assertFalse(RangeForwarder.isClientAbort(new IOException("Remote host terminated the handshake")));
        // 「Connection reset」可能来自上游读侧——不归客户端断开（宁可多试一次切线）
        assertFalse(RangeForwarder.isClientAbort(new IOException("Connection reset by peer")));
        // 消息为空的异常不误判
        assertFalse(RangeForwarder.isClientAbort(new IOException((String) null)));
    }
}
