package com.github.xingjianluo.springruntimeconfigrolloutjava.web;

import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.ConfigRejectedException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.RejectionReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.service.ConfigManager;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.RollbackRequest;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.SnapshotView;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.UpdateRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 管理接口：来源内容替换（热更新）与按来源回退，全程无需重启。 */
@RestController
public class AdminController {

    private final ConfigManager manager;

    public AdminController(ConfigManager manager) {
        this.manager = manager;
    }

    @PostMapping("/api/admin/config/update")
    public SnapshotView update(@RequestBody UpdateRequest request) {
        if (request == null) {
            throw new ConfigRejectedException(RejectionReason.ILLEGAL_PAYLOAD, "请求体为空");
        }
        if (request.schemaVersion() != null
                && request.schemaVersion() != com.github.xingjianluo.springruntimeconfigrolloutjava
                .registry.ConfigSchemaRegistry.SCHEMA_VERSION) {
            throw new ConfigRejectedException(RejectionReason.SCHEMA_VERSION_INCOMPATIBLE,
                    "声明结构版本 " + request.schemaVersion() + " 与服务端结构版本不一致");
        }
        ConfigSnapshot snapshot = manager.updateSource(
                request.source(), request.entries(), request.expectedVersion());
        return manager.currentView();
    }

    @PostMapping("/api/admin/config/rollback")
    public SnapshotView rollback(@RequestBody RollbackRequest request) {
        if (request == null || request.source() == null) {
            throw new ConfigRejectedException(RejectionReason.ILLEGAL_PAYLOAD,
                    "source 不能为空");
        }
        manager.rollbackSource(request.source(), request.targetVersion());
        return manager.currentView();
    }
}
