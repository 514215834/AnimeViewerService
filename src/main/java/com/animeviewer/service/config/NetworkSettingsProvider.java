package com.animeviewer.service.config;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.download.DownloadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** v1.0 补记四 后端出口网络设置的运行期持有者：启动时从 SQLite settings 表加载覆盖（无覆盖用 yml 默认），
 *  设置页保存即刷新缓存——所有消费方经 current() 拿当前值，无需重启。
 *  version() 随保存递增，懒建代理客户端的消费方（BangumiMatcher/HanimeService/ResourceService/
 *  RangeForwarder/DownloadService）以「版本变了」为重建信号，避免缓存客户端指向旧代理地址。
 *  key=network 只经本组件写，缓存与库一致。 */
@Component
public class NetworkSettingsProvider {

    private static final Logger log = LoggerFactory.getLogger(NetworkSettingsProvider.class);

    private final DownloadRepository repo;
    private final AtomicLong version = new AtomicLong(1);
    private volatile NetworkSettings current;

    public NetworkSettingsProvider(DownloadRepository repo, ServiceProperties props) {
        this.repo = repo;
        this.current = NetworkSettings.load(repo.getSetting(NetworkSettings.STORE_KEY).orElse(null),
                NetworkSettings.defaults(props));
        log.info("后端出口网络线路: {}（代理: {}）", current.proxyMode(),
                current.hasProxy() ? current.proxyHost() + ":" + current.proxyPort() : "未配置");
    }

    /** 当前生效设置 */
    public NetworkSettings current() {
        return current;
    }

    /** 配置版本号（保存即递增；代理客户端缓存的重建信号） */
    public long version() {
        return version.get();
    }

    /** 校验+归一+落库+刷新缓存；校验失败抛 IllegalArgumentException（控制器层转 400） */
    public synchronized NetworkSettings update(NetworkSettings settings) {
        NetworkSettings norm = settings.normalized();
        String err = norm.validate();
        if (err != null) throw new IllegalArgumentException(err);
        repo.putSetting(NetworkSettings.STORE_KEY, norm.toJson());
        this.current = norm;
        version.incrementAndGet();
        log.info("后端出口网络线路更新: {}（代理: {}）", norm.proxyMode(),
                norm.hasProxy() ? norm.proxyHost() + ":" + norm.proxyPort() : "未配置");
        return norm;
    }
}
