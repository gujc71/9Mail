package com.ninemail;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 9Mail Enterprise Mail Server
 *
 * Enterprise mail server (SMTP + IMAP)
 * - Netty-based asynchronous protocol server
 * - MyBatis + SQLite persistence
 * - ActiveMQ mail queue
 * - Jakarta Mail message parsing
 * - Reactor (Reactive) async processing
 * - Prometheus metrics monitoring
 */
@SpringBootApplication
@MapperScan("com.ninemail.mapper")
@EnableConfigurationProperties
public class NineMailApplication {

    public static void main(String[] args) {
        SpringApplication.run(NineMailApplication.class, args);
    }
}
