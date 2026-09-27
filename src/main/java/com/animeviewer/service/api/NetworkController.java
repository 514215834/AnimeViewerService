package com.animeviewer.service.api;

import com.animeviewer.service.config.NetworkSettings;
import com.animeviewer.service.config.NetworkSettingsProvider;
import com.animeviewer.service.model.Dtos.NetworkSettingsDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** v1.0 补记四 后端出口网络设置 API：线路模式（auto/direct/proxy）+ 代理地址，设置页读写。
 *  保存即生效（Provider 刷新缓存，消费方按版本号重建代理客户端），无需重启服务。 */
@RestController
@RequestMapping("/api/network")
public class NetworkController {

    private final NetworkSettingsProvider provider;

    public NetworkController(NetworkSettingsProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/settings")
    public NetworkSettingsDto settings() {
        return toDto(provider.current());
    }

    @PutMapping("/settings")
    public NetworkSettingsDto updateSettings(@RequestBody NetworkSettingsDto dto) {
        return toDto(provider.update(new NetworkSettings(dto.proxyMode(), dto.proxyHost(), dto.proxyPort())));
    }

    private static NetworkSettingsDto toDto(NetworkSettings s) {
        return new NetworkSettingsDto(s.proxyMode(), s.proxyHost(), s.proxyPort());
    }

    /** 设置校验失败 → 400 可读文案 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
