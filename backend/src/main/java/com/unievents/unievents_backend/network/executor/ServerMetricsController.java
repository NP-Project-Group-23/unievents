package com.unievents.unievents_backend.network.executor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller exposing real-time server and worker pool metrics for monitoring.
 * Consumed by the monitoring screen (F07) and administrative diagnostics.
 */
@RestController
@RequestMapping({"/api/admin/metrics", "/api/metrics"})
public class ServerMetricsController {

    private final ServerMetricsService metricsService;
    private final ServerEventLogBuffer eventLogBuffer;

    public ServerMetricsController(ServerMetricsService metricsService, ServerEventLogBuffer eventLogBuffer) {
        this.metricsService = metricsService;
        this.eventLogBuffer = eventLogBuffer;
    }

    /**
     * Primary metrics endpoint returning the current snapshot of genuine server and thread pool metrics.
     *
     * @return current {@link ServerMetricsSnapshot}
     */
    @GetMapping
    public ResponseEntity<ServerMetricsSnapshot> getMetrics() {
        return ResponseEntity.ok(metricsService.getMetricsSnapshot());
    }

    /**
     * Retrieves recent genuine server operational and concurrency events for the monitoring log stream.
     *
     * @param limit maximum events to retrieve (default: 30)
     * @return list of {@link ServerEvent}
     */
    @GetMapping("/events")
    public ResponseEntity<List<ServerEvent>> getEvents(@RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(eventLogBuffer.getRecentEvents(limit));
    }

    /**
     * Composite endpoint for the full administrative monitoring screen (F07).
     */
    @GetMapping("/dashboard")
    public ResponseEntity<MonitoringDashboardResponse> getDashboard() {
        return ResponseEntity.ok(new MonitoringDashboardResponse(
                metricsService.getMetricsSnapshot(),
                eventLogBuffer.getRecentEvents(30)
        ));
    }

    public record MonitoringDashboardResponse(
            ServerMetricsSnapshot snapshot,
            List<ServerEvent> recentEvents
    ) {}
}
