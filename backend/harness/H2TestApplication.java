package com.dyeing.mill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 本地端到端验证启动类（仅 harness/ 使用：不进 docker 构建、不在 src/test 标准路径，
 * pom 也不引入 test 依赖，保持预热镜像依赖面不变）。
 * 用 H2(MODE=MySQL) 起内存库；提供一个不连接真实 Redis 的 StringRedisTemplate 空壳，
 * 排程模块不触碰 Redis，开缸执行单相关功能不在该验证范围。
 */
@SpringBootApplication
public class H2TestApplication {

    @Bean
    public StringRedisTemplate stringRedisTemplate() {
        // 不调用 afterPropertiesSet()：不初始化连接工厂；排程用例不触发任何 Redis 操作
        return new StringRedisTemplate();
    }

    public static void main(String[] args) {
        SpringApplication.run(H2TestApplication.class, args);
    }
}
