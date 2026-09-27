package com.github.xingjianluo.springruntimeconfigrolloutjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用运行时配置属性绑定。 */
@Configuration
@EnableConfigurationProperties(RuntimeConfigProperties.class)
public class RuntimeConfigConfiguration {
}
