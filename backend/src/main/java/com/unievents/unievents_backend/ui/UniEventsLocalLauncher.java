package com.unievents.unievents_backend.ui;

/** Plain main-class wrapper so the JavaFX application can be packaged in a Spring Boot fat JAR. */
public final class UniEventsLocalLauncher {
    private UniEventsLocalLauncher() {}
    public static void main(String[] args) {
        javafx.application.Application.launch(UniEventsLocalApplication.class, args);
    }
}
