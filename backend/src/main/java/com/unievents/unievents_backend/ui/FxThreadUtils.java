package com.unievents.unievents_backend.ui;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for executing UI actions safely on the JavaFX Application Thread.
 * Automatically falls back to synchronous execution in headless test environments
 * where the JavaFX toolkit is not initialized.
 */
public final class FxThreadUtils {

    private static final Logger log = LoggerFactory.getLogger(FxThreadUtils.class);

    private FxThreadUtils() {}

    /**
     * Executes the given action on the JavaFX application thread.
     * If the current thread is already the JavaFX application thread, the action runs immediately.
     * If the JavaFX toolkit is not initialized (e.g., in unit tests), it falls back to running synchronously.
     *
     * @param action runnable to execute
     */
    public static void runOnFxThread(Runnable action) {
        if (action == null) {
            return;
        }

        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException | NoClassDefFoundError | UnsatisfiedLinkError e) {
            // JavaFX toolkit not initialized - fallback to synchronous execution
            log.trace("JavaFX platform not initialized; running action synchronously: {}", e.getMessage());
            action.run();
        }
    }
}
