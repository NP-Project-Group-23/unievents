package com.unievents.unievents_backend.network.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.network.protocol.*;
import com.unievents.unievents_backend.network.server.*;
import org.springframework.stereotype.Component;

/** GET_METRICS adapter; registered and authorized by the shared dispatcher. */
@Component
public class MetricsProtocolHandler implements RequestHandler {
    public static final String OPERATION_TYPE = "GET_METRICS";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ServerMetricsService metricsService;

    public MetricsProtocolHandler(ServerMetricsService metricsService) {
        this.metricsService = java.util.Objects.requireNonNull(metricsService);
    }

    @Override
    public ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context) {
        if (context == null || !context.isAuthenticated()) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "Authentication required.");
        }
        if (!"ADMIN".equals(context.role())) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.FORBIDDEN, "Administrator privilege required.");
        }
        var snapshot = metricsService.getMetricsSnapshot();
        var data = MAPPER.createObjectNode();
        data.put("timestamp", snapshot.timestamp().toString());
        data.put("activeWorkers", snapshot.activeWorkers());
        data.put("currentPoolSize", snapshot.currentPoolSize());
        data.put("corePoolSize", snapshot.corePoolSize());
        data.put("maxPoolSize", snapshot.maxPoolSize());
        data.put("queuedTasks", snapshot.queuedTasks());
        data.put("queueRemainingCapacity", snapshot.queueRemainingCapacity());
        data.put("totalSubmittedTasks", snapshot.totalSubmittedTasks());
        data.put("totalCompletedTasks", snapshot.totalCompletedTasks());
        data.put("totalRejectedTasks", snapshot.totalRejectedTasks());
        data.put("activeConnections", snapshot.activeConnections());
        data.put("peakActiveWorkers", snapshot.peakActiveWorkers());
        data.put("averageExecutionTimeMs", snapshot.averageExecutionTimeMs());
        data.put("maxExecutionTimeMs", snapshot.maxExecutionTimeMs());
        data.put("state", snapshot.state());
        return ResponseEnvelope.success(request.requestId(), data);
    }
}
