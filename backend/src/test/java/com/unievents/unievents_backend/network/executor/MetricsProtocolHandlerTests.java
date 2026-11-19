package com.unievents.unievents_backend.network.executor;

import com.unievents.unievents_backend.network.protocol.*;
import com.unievents.unievents_backend.network.server.ConnectionContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetricsProtocolHandlerTests {
    @Test
    void usesCommonEnvelopeAndIsoTimestamp() {
        var service = mock(ServerMetricsService.class);
        var snapshot = ServerMetricsSnapshot.create(2,5,10,20,0,50,50,48,0,3,4,8.5,25,"RUNNING");
        when(service.getMetricsSnapshot()).thenReturn(snapshot);
        var handler = new MetricsProtocolHandler(service);
        var request = new RequestEnvelope("metrics-1", "GET_METRICS", "token", null);
        var result = handler.handle(request, new ConnectionContext("c",1L,"admin@example.com","ADMIN",null));
        assertEquals("metrics-1", result.requestId());
        assertTrue(result.isSuccess());
        assertEquals(2, result.data().path("activeWorkers").asInt());
        assertEquals(snapshot.timestamp().toString(), result.data().path("timestamp").asText());
        assertEquals(48, result.data().path("totalCompletedTasks").asLong());
    }

    @Test
    void deniesNonAdminsWithoutReadingMetrics() {
        var service = mock(ServerMetricsService.class);
        var handler = new MetricsProtocolHandler(service);
        var request = RequestEnvelope.of("GET_METRICS", null);
        assertEquals("UNAUTHENTICATED", handler.handle(request,null).errorCode());
        assertEquals("FORBIDDEN", handler.handle(request,
            new ConnectionContext("c",2L,"student@example.com","STUDENT",null)).errorCode());
        verifyNoInteractions(service);
    }
}
