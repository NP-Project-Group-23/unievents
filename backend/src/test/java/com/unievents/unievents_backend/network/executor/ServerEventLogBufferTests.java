package com.unievents.unievents_backend.network.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerEventLogBufferTests {

    @Test
    @DisplayName("ServerEventLogBuffer records events newest first and respects capacity bounds")
    void recordsAndBoundsEvents() {
        ServerEventLogBuffer buffer = new ServerEventLogBuffer(3);

        buffer.record("EVT_1", "Message 1", "Detail 1");
        buffer.record("EVT_2", "Message 2", "Detail 2");
        buffer.record("EVT_3", "Message 3", "Detail 3");

        assertThat(buffer.size()).isEqualTo(3);

        // Recording 4th should drop the oldest (EVT_1)
        buffer.record("EVT_4", "Message 4", "Detail 4");
        assertThat(buffer.size()).isEqualTo(3);

        List<ServerEvent> recent = buffer.getRecentEvents(10);
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).eventType()).isEqualTo("EVT_4"); // newest
        assertThat(recent.get(1).eventType()).isEqualTo("EVT_3");
        assertThat(recent.get(2).eventType()).isEqualTo("EVT_2");
    }

    @Test
    @DisplayName("ServerEventLogBuffer rejects non-positive capacity")
    void rejectsInvalidCapacity() {
        assertThatThrownBy(() -> new ServerEventLogBuffer(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
