package com.animeviewer.service.webdav;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** v0.15 O3 WebDAV 会话：streamId → 连接目标（含凭据），仅存内存、短 TTL 惰性过期。
 *  凭据绝不进 URL / 播放地址——前端只拿 streamId。 */
@Component
public class WebdavSessionStore {

    public record Session(String url, String username, String password, long createdAt) {}

    private final long ttlMs;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public WebdavSessionStore(com.animeviewer.service.ServiceProperties props) {
        this.ttlMs = Math.max(1, props.webdav().sessionTtlMinutes()) * 60_000L;
    }

    public String create(String url, String username, String password) {
        String id = java.util.UUID.randomUUID().toString().replace("-", "");
        sessions.put(id, new Session(url, username, password, System.currentTimeMillis()));
        return id;
    }

    /** 取会话（过期即删，返回 null）；不存在返回 null */
    public Session get(String streamId) {
        if (streamId == null || streamId.isBlank()) return null;
        Session s = sessions.get(streamId);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.createdAt() > ttlMs) {
            sessions.remove(streamId);
            return null;
        }
        return s;
    }
}
