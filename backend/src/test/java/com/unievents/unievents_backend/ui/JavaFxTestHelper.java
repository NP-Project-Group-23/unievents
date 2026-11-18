package com.unievents.unievents_backend.ui;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test helper ensuring JavaFX Platform runtime is initialized for UI tests,
 * and providing synchronized invocation on the JavaFX Application Thread.
 */
public final class JavaFxTestHelper {

    private static volatile boolean initialized = false;

    private JavaFxTestHelper() {}

    public static synchronized void initPlatform() {
        if (!initialized) {
            try {
                Platform.startup(() -> {});
                initialized = true;
            } catch (IllegalStateException e) {
                // Platform was already started in this JVM
                initialized = true;
            } catch (Throwable t) {
                // In purely headless environment where toolkit cannot start
            }
        }
    }

    public static void runAndWait(Runnable action) throws InterruptedException {
        initPlatform();
        if (action == null) return;

        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    action.run();
                } finally {
                    latch.countDown();
                }
            });

            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for JavaFX thread execution");
            }
        } catch (IllegalStateException e) {
            // Toolkit not initialized - run synchronously
            action.run();
        }
    }
}
