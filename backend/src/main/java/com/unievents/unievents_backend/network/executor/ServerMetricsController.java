package com.unievents.unievents_backend.network.executor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing real-time server and worker pool metrics for monitoring.
 * Consumed by the monitoring screen (F07) and administrative diagnostics.
 */
@RestController
@RequestMapping({"/api/admin/metrics", "/api/metrics"})
public class ServerMetricsController {

    private final ServerMetricsService metricsService;

    public ServerMetricsController(ServerMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * Retrieves the current snapshot of genuine server and thread pool metrics.
     *
     * @return current {@link ServerMetricsSnapshot}
     */
    @GetMapping
    public ResponseEntity<ServerMetricsSnapshot> getMetrics() {
        return ResponseEntity.ok(metricsService.getMetricsSnapshot());
    }
}
