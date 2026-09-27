package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.RuntimeConfigService;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.source.ConfigSource;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 测试夹具：脱离 Spring 容器构造 {@link RuntimeConfigService}，
 * 手动触发 @PostConstruct 初始化并完成根版本引导。
 */
final class RuntimeConfigServiceFixture {

    final RuntimeConfigService service;

    private RuntimeConfigServiceFixture(RuntimeConfigService service) {
        this.service = service;
    }

    static RuntimeConfigServiceFixture fresh() {
        try {
            RuntimeConfigService service = new RuntimeConfigService();
            Method init = RuntimeConfigService.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(service);
            return new RuntimeConfigServiceFixture(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法初始化 RuntimeConfigService", e);
        }
    }

    static RuntimeConfigServiceFixture bootstrapped(ConfigSource... sources) {
        RuntimeConfigServiceFixture fixture = fresh();
        try {
            fixture.service.bootstrap(List.of(sources));
        } catch (Exception e) {
            throw new IllegalStateException("测试根版本引导失败", e);
        }
        return fixture;
    }
}
