import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Home from "./page";

function gateway(data: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } }));
}

describe("UniEvents portal", () => {
  beforeEach(() => {
    vi.stubGlobal("confirm", vi.fn(() => true));
  });

  it("shows login and registration states when no session exists", async () => {
    vi.stubGlobal("fetch", vi.fn(() => gateway({ errorCode: "UNAUTHENTICATED", errorMessage: "Please sign in" }, 401)));
    const user = userEvent.setup();
    render(<Home />);
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create an account" }));
    expect(screen.getByRole("heading", { name: "Create your account" })).toBeInTheDocument();
    expect(screen.getByLabelText("Full name")).toBeInTheDocument();
  });

  it("loads events and displays a backend booking conflict without hiding the seat view", async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const request = JSON.parse(String(init?.body));
      if (request.type === "SESSION_CHECK") return gateway({ status: "SUCCESS", data: { userId: 7, email: "student@test", role: "STUDENT" } });
      if (request.type === "LIST_EVENTS") return gateway({ status: "SUCCESS", data: { items: [{ eventId: 10, title: "Campus Concert", description: "Live music", venue: "Main Hall", startsAt: "2030-01-01T18:00:00Z", capacity: 2 }], page: 0, totalPages: 1, totalElements: 1 } });
      if (request.type === "MY_BOOKINGS") return gateway({ status: "SUCCESS", data: { items: [], page: 0, totalPages: 0, totalElements: 0 } });
      if (request.type === "GET_SEATS") return gateway({ status: "SUCCESS", data: { items: [{ seatId: 1, eventId: 10, seatNumber: "A1", status: "AVAILABLE" }], page: 0, totalPages: 1, totalElements: 1 } });
      if (request.type === "BOOK_SEAT") return gateway({ status: "ERROR", errorCode: "SEAT_ALREADY_BOOKED", errorMessage: "Seat is already booked." }, 409);
      return gateway({ status: "SUCCESS", data: {} });
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<Home />);
    expect((await screen.findAllByText("student")).length).toBeGreaterThanOrEqual(1);
    expect(await screen.findByText("Campus Concert")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "View seats" }));
    const seat = await screen.findByRole("button", { name: "A1" });
    await user.click(seat);
    expect(await screen.findByText("Seat is already booked.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Choose your seat" })).toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
  });
});
