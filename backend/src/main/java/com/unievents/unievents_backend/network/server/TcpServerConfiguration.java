package com.unievents.unievents_backend.network.server;

import com.unievents.unievents_backend.network.executor.ClientExecutionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "unievents.tcp.enabled", havingValue = "true", matchIfMissing = true)
public class TcpServerConfiguration {
    @Bean(destroyMethod = "close")
    TcpServer tcpServer(RequestDispatcher dispatcher, ClientExecutionManager manager,
                        @Value("${unievents.tcp.bind-address:127.0.0.1}") String address,
                        @Value("${unievents.tcp.port:9090}") int port,
                        @Value("${unievents.tcp.idle-timeout-ms:30000}") int idleTimeout) {
        if (address.isBlank() || port < 0 || port > 65535 || idleTimeout <= 0) {
            throw new IllegalArgumentException("Invalid TCP bind address, port or idle timeout");
        }
        return new TcpServer(address, port, dispatcher, manager, idleTimeout);
    }

    @Bean
    SmartLifecycle tcpServerLifecycle(TcpServer server) {
        return new SmartLifecycle() {
            @Override public void start() {
                try { server.start(); }
                catch (java.io.IOException ex) { throw new IllegalStateException("Unable to start TCP listener", ex); }
            }
            @Override public void stop() { server.stop(); }
            @Override public boolean isRunning() { return server.isRunning(); }
            // Stop admission and sockets before bean destruction shuts down the worker pool.
            @Override public int getPhase() { return Integer.MAX_VALUE; }
        };
    }
}
