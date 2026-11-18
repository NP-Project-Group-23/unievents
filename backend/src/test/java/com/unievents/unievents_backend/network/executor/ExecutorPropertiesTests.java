package com.unievents.unievents_backend.network.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorPropertiesTests {

    @Test
    @DisplayName("Default properties provide safe bounded production defaults")
    void defaultProperties() {
        ExecutorProperties props = new ExecutorProperties();
        assertThat(props.getCorePoolSize()).isEqualTo(10);
        assertThat(props.getMaxPoolSize()).isEqualTo(20);
        assertThat(props.getQueueCapacity()).isEqualTo(50);
        assertThat(props.getKeepAliveSeconds()).isEqualTo(60);
        assertThat(props.getShutdownTimeoutSeconds()).isEqualTo(10);
        assertThat(props.getThreadPrefix()).isEqualTo("unievents-worker-");
        props.validate();
    }

    @Test
    @DisplayName("Validation rejects maxPoolSize less than corePoolSize")
    void invalidPoolBounds() {
        ExecutorProperties props = new ExecutorProperties();
        props.setCorePoolSize(15);
        props.setMaxPoolSize(10);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be less than corePoolSize");
    }

    @Test
    @DisplayName("Setters reject non-positive or negative values")
    void nonPositiveValuesRejected() {
        ExecutorProperties props = new ExecutorProperties();
        assertThatThrownBy(() -> props.setCorePoolSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.setMaxPoolSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.setQueueCapacity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.setKeepAliveSeconds(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.setShutdownTimeoutSeconds(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.setThreadPrefix("")).isInstanceOf(IllegalArgumentException.class);
    }
}
