package com.unievents.unievents_backend.network.executor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Spring configuration registering the bounded thread pool executor, thread factory,
 * rejection handler, and metrics service for UniEvents.
 */
@Configuration
@EnableConfigurationProperties(ExecutorProperties.class)
public class ExecutorConfiguration {

    @Bean
    public NamedThreadFactory workerThreadFactory(ExecutorProperties properties) {
        return new NamedThreadFactory(properties.getThreadPrefix());
    }

    @Bean
    public ServerRejectedExecutionHandler serverRejectedExecutionHandler() {
        return new ServerRejectedExecutionHandler();
    }

    @Bean
    public ServerThreadPoolExecutor serverThreadPoolExecutor(ExecutorProperties properties,
                                                           NamedThreadFactory threadFactory,
                                                           ServerRejectedExecutionHandler rejectionHandler) {
        properties.validate();
        return new ServerThreadPoolExecutor(
                properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                properties.getQueueCapacity(),
                threadFactory,
                rejectionHandler
        );
    }
}
