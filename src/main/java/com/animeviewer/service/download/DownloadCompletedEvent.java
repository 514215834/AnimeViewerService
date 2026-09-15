package com.animeviewer.service.download;

/** v0.20 SU7 下载完成事件：入库闭环（扫描+绑定）结束后发布，订阅侧监听并自动抬升观看基线——
 *  解耦设计：DownloadService 与 SubscriptionService 相互依赖会成环（订阅入队依赖下载服务），
 *  经 Spring 事件单向往返，订阅模块只读不回调。subjectId 为 null（手动磁力无关联条目）时无人消费。 */
public record DownloadCompletedEvent(long taskId, Long subjectId) {}
