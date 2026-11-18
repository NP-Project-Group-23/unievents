package com.unievents.unievents_backend.network.executor;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Protocol handler for the {@code GET_METRICS} operation specified in the UniEvents
 * team workload agreement (Section 5).
 * <p>
 * Registered with Member 01's TCP request dispatcher to supply live server counters
 * over raw TCP sockets.
 */
@Component
public class MetricsProtocolHandler {

    public static final String OPERATION_TYPE = "GET_METRICS";

    private final ServerMetricsService metricsService;

    public MetricsProtocolHandler(ServerMetricsService metricsService) {
        this.metricsService = Objects.requireNonNull(metricsService, "metricsService must not be null");
    }

    /**
     * Processes a {@code GET_METRICS} request and generates a standard protocol response envelope.
     *
     * @param requestId the correlation ID from the client request
     * @return structured response envelope containing the authentic metrics snapshot
     */
    public MetricsEnvelope handle(String requestId) {
        String id = (requestId != null && !requestId.isBlank()) ? requestId : "req-" + System.currentTimeMillis();
        ServerMetricsSnapshot snapshot = metricsService.getMetricsSnapshot();
        return new MetricsEnvelope(id, OPERATION_TYPE, "SUCCESS", snapshot, null);
    }

    public record MetricsEnvelope(
            String requestId,
            String type,
            String status,
            ServerMetricsSnapshot data,
            String errorCode
    ) {}
}
