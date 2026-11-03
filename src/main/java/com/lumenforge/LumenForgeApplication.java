package com.lumenforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Java 后端启动入口；定时任务调度器由 Spring Scheduler 自动驱动。 */
@SpringBootApplication
@EnableScheduling
public class LumenForgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(LumenForgeApplication.class, args);
    }
}




