package com.unievents.unievents_backend.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Role-aware desktop shell for events, seats, bookings and administrator event management. */
public final class DesktopDashboardView extends BorderPane implements AutoCloseable {
    private final DesktopDataController data;
    private final SessionHolder.ClientSession session;
    private final ObjectMapper json;
    private final ListView<JsonNode> eventList = new ListView<>();
    private final FlowPane seatGrid = new FlowPane(8, 8);
    private final ListView<JsonNode> bookingList = new ListView<>();
    private final Label status = new Label();
    private final ProgressIndicator busyIndicator = new ProgressIndicator();
    private final AtomicInteger busyOperations = new AtomicInteger();
    private final Label seatPageLabel = new Label("Page 1");
    private final Label eventPageLabel = new Label("Page 1");
    private final Label bookingPageLabel = new Label("Page 1");
    private int eventPage;
    private int eventTotalPages;
    private int bookingPage;
    private int bookingTotalPages;
    private int seatPage;
    private int seatTotalPages;
    private JsonNode selectedEvent;
    private Runnable onLoggedOut;

    public DesktopDashboardView(TcpClient tcp, SessionHolder holder, ObjectMapper mapper) {
        this.session = holder.getSession();
        this.json = mapper != null ? mapper : new ObjectMapper();
        this.data = new DesktopDataController(tcp, holder, this.json);
        this.data.setOnSessionInvalidated(() -> FxThreadUtils.runOnFxThread(() -> {
            showStatus("Your session expired. Please sign in again.", true);
            if (onLoggedOut != null) onLoggedOut.run();
        }));
        this.data.setOnReconnected(() -> FxThreadUtils.runOnFxThread(() -> {
            showStatus("Connection restored. Data refreshed.", false);
            refreshEvents();
            refreshBookings();
        }));
        setStyle("-fx-background-color: #f8fafc;");
        setTop(header());
        setCenter(tabs());
        setBottom(status);
        BorderPane.setMargin(status, new Insets(8, 20, 12, 20));
        refreshEvents();
        refreshBookings();
    }

    private HBox header() {
        Label brand = new Label("UniEvents");
        brand.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1e3a8a;");
        Label identity = new Label((session != null ? session.name() : "") + "  •  "
            + (session != null ? session.role() : Role.STUDENT));
        Button logout = new Button("Log out");
        logout.setOnAction(event -> {
            data.stopWatching();
            data.logout().whenComplete((response, error) -> FxThreadUtils.runOnFxThread(() -> {
                if (onLoggedOut != null) onLoggedOut.run();
            }));
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        busyIndicator.setPrefSize(22, 22);
        busyIndicator.setVisible(false);
        busyIndicator.setManaged(false);
        HBox box = new HBox(12, brand, spacer, busyIndicator, identity, logout);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(14, 20, 14, 20));
        box.setStyle("-fx-background-color: white; -fx-border-color: transparent transparent #e2e8f0 transparent;");
        return box;
    }

    private TabPane tabs() {
        var pane = new TabPane();
        pane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        pane.getTabs().add(new Tab("Events & Seats", eventAndSeats()));
        pane.getTabs().add(new Tab("My Bookings", bookingsPane()));
        if (session != null && session.role() == Role.ADMIN) pane.getTabs().add(new Tab("Admin", adminPane()));
        return pane;
    }

    private SplitPane eventAndSeats() {
        eventList.setPlaceholder(new Label("No upcoming events found."));
        eventList.setCellFactory(ignored -> jsonCell(node -> node.path("title").asText() + "\n"
            + node.path("venue").asText() + " • " + node.path("startsAt").asText()));
        eventList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) selectEvent(selected);
        });
        Button refresh = new Button("Refresh events");
        refresh.setOnAction(event -> refreshEvents());
        Button eventPrevious = new Button("Previous");
        Button eventNext = new Button("Next");
        eventPrevious.setOnAction(event -> changeEventPage(eventPage - 1));
        eventNext.setOnAction(event -> changeEventPage(eventPage + 1));
        HBox eventPaging = new HBox(10, eventPrevious, eventPageLabel, eventNext);
        eventPaging.setAlignment(Pos.CENTER);
        VBox left = new VBox(8, refresh, eventList, eventPaging);
        left.setPadding(new Insets(16));
        VBox.setVgrow(eventList, Priority.ALWAYS);

        seatGrid.setPadding(new Insets(8));
        seatGrid.getChildren().add(new Label("Select an event to view its seats."));
        ScrollPane scroll = new ScrollPane(seatGrid);
        scroll.setFitToWidth(true);
        Button previous = new Button("Previous");
        Button next = new Button("Next");
        previous.setOnAction(event -> changeSeatPage(seatPage - 1));
        next.setOnAction(event -> changeSeatPage(seatPage + 1));
        HBox paging = new HBox(10, previous, seatPageLabel, next);
        paging.setAlignment(Pos.CENTER);
        VBox right = new VBox(8, new Label("Select an available seat to book"), scroll, paging);
        right.setPadding(new Insets(16));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        var split = new SplitPane(left, right);
        split.setDividerPositions(0.35);
        return split;
    }

    private VBox bookingsPane() {
        bookingList.setPlaceholder(new Label("You have no bookings yet."));
        bookingList.setCellFactory(ignored -> jsonCell(node -> node.path("bookingReference").asText()
            + "  • Event " + node.path("eventId").asText() + " • Seat " + node.path("seatId").asText()
            + "\n" + node.path("createdAt").asText()));
        Button refresh = new Button("Refresh history");
        refresh.setOnAction(event -> refreshBookings());
        Button previous = new Button("Previous");
        Button next = new Button("Next");
        previous.setOnAction(event -> changeBookingPage(bookingPage - 1));
        next.setOnAction(event -> changeBookingPage(bookingPage + 1));
        HBox paging = new HBox(10, previous, bookingPageLabel, next);
        paging.setAlignment(Pos.CENTER);
        VBox box = new VBox(10, refresh, bookingList, paging);
        box.setPadding(new Insets(16));
        VBox.setVgrow(bookingList, Priority.ALWAYS);
        return box;
    }

    private VBox adminPane() {
        TextField title = field("Title");
        TextField venue = field("Venue");
        DatePicker startDate = new DatePicker(LocalDate.now().plusDays(1));
        TextField startTime = field("Start time (HH:mm)");
        startTime.setText("18:00");
        TextField capacity = field("Capacity (create only)");
        TextArea description = new TextArea();
        description.setPromptText("Description");
        description.setPrefRowCount(3);
        Button load = new Button("Load selected event");
        load.setOnAction(event -> {
            JsonNode selected = eventList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            title.setText(selected.path("title").asText());
            venue.setText(selected.path("venue").asText());
            try {
                OffsetDateTime startsAt = OffsetDateTime.parse(selected.path("startsAt").asText());
                startDate.setValue(startsAt.toLocalDate());
                startTime.setText(startsAt.toLocalTime().withSecond(0).withNano(0).toString());
            } catch (DateTimeParseException ignored) {
                showStatus("The selected event has an invalid start time.", true);
            }
            capacity.setText(selected.path("capacity").asText());
            description.setText(selected.path("description").asText());
        });
        Button create = new Button("Create event");
        create.setOnAction(event -> runAdmin(data.createEvent(title.getText(), description.getText(), venue.getText(),
            startValue(startDate, startTime), parseInt(capacity.getText())), "Event created."));
        Button update = new Button("Update selected");
        update.setOnAction(event -> {
            JsonNode selected = eventList.getSelectionModel().getSelectedItem();
            if (selected != null) runAdmin(data.updateEvent(selected.path("eventId").asLong(), title.getText(),
                description.getText(), venue.getText(), startValue(startDate, startTime)), "Event updated.");
        });
        Button delete = new Button("Delete selected");
        delete.setOnAction(event -> {
            JsonNode selected = eventList.getSelectionModel().getSelectedItem();
            if (selected != null && confirm("Delete event",
                "Delete “" + selected.path("title").asText() + "” and its seats?")) {
                runAdmin(data.deleteEvent(selected.path("eventId").asLong()), "Event deleted.");
            }
        });
        HBox actions = new HBox(8, load, create, update, delete);
        HBox schedule = new HBox(8, startDate, startTime);
        HBox.setHgrow(startTime, Priority.ALWAYS);
        VBox box = new VBox(10, new Label("Event administration"), title, description, venue, schedule, capacity, actions);
        box.setPadding(new Insets(18));
        return box;
    }

    private void selectEvent(JsonNode event) {
        selectedEvent = event;
        seatPage = 0;
        busy(data.watchSeats(event.path("eventId").asLong(), seatPage, 100, this::renderSeats))
            .exceptionally(ex -> { showStatus("Unable to start live seat updates.", true); return null; });
    }

    private void changeEventPage(int page) {
        if (page < 0 || page >= Math.max(1, eventTotalPages)) return;
        eventPage = page;
        refreshEvents();
    }

    private void changeBookingPage(int page) {
        if (page < 0 || page >= Math.max(1, bookingTotalPages)) return;
        bookingPage = page;
        refreshBookings();
    }

    private void changeSeatPage(int page) {
        if (page < 0 || page >= Math.max(1, seatTotalPages)) return;
        seatPage = page;
        data.changeSeatPage(page);
    }

    private void renderSeats(ResponseEnvelope response) {
        FxThreadUtils.runOnFxThread(() -> {
            if (response == null || !response.isSuccess()) {
                showStatus(response != null ? response.errorMessage() : "Seat refresh failed.", true);
                return;
            }
            seatGrid.getChildren().clear();
            seatTotalPages = response.data().path("totalPages").asInt();
            seatPageLabel.setText("Page " + (response.data().path("page").asInt() + 1) + " of " + Math.max(1, seatTotalPages));
            for (JsonNode seat : response.data().path("items")) {
                Button button = new Button(seat.path("seatNumber").asText());
                boolean available = "AVAILABLE".equals(seat.path("status").asText());
                button.setDisable(!available);
                button.setStyle(available ? "-fx-background-color:#dcfce7;-fx-text-fill:#166534;"
                    : "-fx-background-color:#fee2e2;-fx-text-fill:#991b1b;");
                if (available) button.setOnAction(event -> bookSeat(seat.path("seatId").asLong()));
                seatGrid.getChildren().add(button);
            }
        });
    }

    private void bookSeat(long seatId) {
        if (selectedEvent == null) return;
        if (!confirm("Confirm booking", "Book this seat for “" + selectedEvent.path("title").asText() + "”?")) return;
        busy(data.book(selectedEvent.path("eventId").asLong(), seatId)).thenAccept(response ->
            FxThreadUtils.runOnFxThread(() -> {
                if (response != null && response.isSuccess()) {
                    showStatus("Booking confirmed: " + response.data().path("bookingReference").asText(), false);
                    refreshBookings();
                    data.changeSeatPage(seatPage);
                } else showStatus(response != null ? response.errorMessage() : "Booking failed.", true);
            })).exceptionally(ex -> { showStatus("Booking request failed.", true); return null; });
    }

    private void refreshEvents() {
        busy(data.listEvents(eventPage, 20)).thenAccept(response -> FxThreadUtils.runOnFxThread(() -> {
            eventList.getItems().clear();
            if (response != null && response.isSuccess()) {
                response.data().path("items").forEach(eventList.getItems()::add);
                eventTotalPages = response.data().path("totalPages").asInt();
                eventPageLabel.setText("Page " + (response.data().path("page").asInt() + 1)
                    + " of " + Math.max(1, eventTotalPages));
            }
            else showStatus(response != null ? response.errorMessage() : "Unable to load events.", true);
        })).exceptionally(ex -> { showStatus("Unable to load events.", true); return null; });
    }

    private void refreshBookings() {
        busy(data.myBookings(bookingPage, 50)).thenAccept(response -> FxThreadUtils.runOnFxThread(() -> {
            bookingList.getItems().clear();
            if (response != null && response.isSuccess()) {
                response.data().path("items").forEach(bookingList.getItems()::add);
                bookingTotalPages = response.data().path("totalPages").asInt();
                bookingPageLabel.setText("Page " + (response.data().path("page").asInt() + 1)
                    + " of " + Math.max(1, bookingTotalPages));
            }
            else showStatus(response != null ? response.errorMessage() : "Unable to load bookings.", true);
        })).exceptionally(ex -> { showStatus("Unable to load bookings.", true); return null; });
    }

    private void runAdmin(CompletableFuture<ResponseEnvelope> future, String success) {
        busy(future).thenAccept(response -> FxThreadUtils.runOnFxThread(() -> {
            if (response != null && response.isSuccess()) { showStatus(success, false); refreshEvents(); }
            else showStatus(response != null ? response.errorMessage() : "Operation failed.", true);
        })).exceptionally(ex -> { showStatus("Operation failed.", true); return null; });
    }

    private void showStatus(String text, boolean error) {
        FxThreadUtils.runOnFxThread(() -> {
            status.setText(text == null ? "" : text);
            status.setStyle(error ? "-fx-text-fill:#b91c1c;" : "-fx-text-fill:#166534;");
        });
    }

    private static String startValue(DatePicker date, TextField time) {
        try {
            LocalDate day = date.getValue();
            LocalTime clock = LocalTime.parse(time.getText().strip());
            return LocalDateTime.of(day, clock).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private <T> CompletableFuture<T> busy(CompletableFuture<T> future) {
        setBusy(true);
        return future.whenComplete((value, error) -> setBusy(false));
    }

    private void setBusy(boolean value) {
        if (value) busyOperations.incrementAndGet();
        else busyOperations.updateAndGet(count -> Math.max(0, count - 1));
        FxThreadUtils.runOnFxThread(() -> {
            boolean visible = busyOperations.get() > 0;
            busyIndicator.setVisible(visible);
            busyIndicator.setManaged(visible);
        });
    }

    private static TextField field(String prompt) { TextField field = new TextField(); field.setPromptText(prompt); return field; }
    private static int parseInt(String value) { try { return Integer.parseInt(value.strip()); } catch (Exception ex) { return 0; } }
    private static ListCell<JsonNode> jsonCell(ConsumerCell formatter) {
        return new ListCell<>() {
            @Override protected void updateItem(JsonNode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatter.text(item));
            }
        };
    }
    @FunctionalInterface private interface ConsumerCell { String text(JsonNode node); }

    public void setOnLoggedOut(Runnable value) { onLoggedOut = value; }
    public ListView<JsonNode> getEventList() { return eventList; }
    public FlowPane getSeatGrid() { return seatGrid; }
    public ListView<JsonNode> getBookingList() { return bookingList; }
    public Label getStatusLabel() { return status; }
    @Override public void close() { data.close(); }
}
