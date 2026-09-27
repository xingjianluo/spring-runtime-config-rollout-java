package com.github.xingjianluo.springruntimeconfigrolloutjava.web;

import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.DecisionResult;
import com.github.xingjianluo.springruntimeconfigrolloutjava.service.ConfigManager;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.SnapshotView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 配置查询接口：按主体判定灰度人群并返回命中值与判定依据。 */
@RestController
public class ConfigController {

    private final ConfigManager manager;

    public ConfigController(ConfigManager manager) {
        this.manager = manager;
    }

    @GetMapping("/api/config")
    public DecisionResult get(@RequestParam String key, @RequestParam String subject) {
        return manager.resolve(key, subject);
    }

    @GetMapping("/api/config/snapshot")
    public SnapshotView snapshot() {
        return manager.currentView();
    }
}
