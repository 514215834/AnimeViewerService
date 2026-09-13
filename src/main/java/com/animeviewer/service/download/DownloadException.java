package com.animeviewer.service.download;

/** v0.16 下载模块业务异常：status 直接映射 HTTP 状态码（400 参数 / 409 冲突 / 503 引擎不可用） */
public class DownloadException extends RuntimeException {

    public final int status;

    public DownloadException(int status, String message) {
        super(message);
        this.status = status;
    }
}
