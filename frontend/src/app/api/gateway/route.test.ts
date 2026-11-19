import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { sendTcp } = vi.hoisted(() => ({ sendTcp: vi.fn() }));
vi.mock("@/lib/tcp-gateway", () => ({ sendTcp }));
import { POST } from "./route";

function request(type: string, payload: object = {}, cookie?: string) {
  return new NextRequest("http://localhost/api/gateway", {
    method: "POST",
    headers: { "content-type": "application/json", ...(cookie ? { cookie: `unievents_tcp_session=${cookie}` } : {}) },
    body: JSON.stringify({ type, payload }),
  });
}

describe("TCP gateway route", () => {
  beforeEach(() => sendTcp.mockReset());

  it("rejects unsupported and unauthenticated protected operations", async () => {
    expect((await POST(request("DROP_DATABASE"))).status).toBe(400);
    expect((await POST(request("LIST_EVENTS"))).status).toBe(401);
    expect(sendTcp).not.toHaveBeenCalled();
  });

  it("stores login token in an HttpOnly cookie and removes it from JSON", async () => {
    sendTcp.mockResolvedValue({ status: "SUCCESS", data: { token: "server-secret", userId: 2, role: "STUDENT" } });
    const response = await POST(request("LOGIN", { email: "student@test", password: "password" }));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "SUCCESS", data: { userId: 2, role: "STUDENT" } });
    expect(response.headers.get("set-cookie")).toContain("unievents_tcp_session=server-secret");
    expect(response.headers.get("set-cookie")).toContain("HttpOnly");
  });

  it("forwards cookie token to TCP but never returns tokens to the browser", async () => {
    sendTcp.mockResolvedValue({ status: "SUCCESS", data: { token: "server-secret", valid: true, userId: 2 } });
    const response = await POST(request("SESSION_CHECK", {}, "cookie-token"));
    expect(sendTcp).toHaveBeenCalledWith("SESSION_CHECK", {}, "cookie-token");
    expect(await response.json()).toEqual({ status: "SUCCESS", data: { valid: true, userId: 2 } });
  });

  it("maps booking conflicts and clears expired sessions", async () => {
    sendTcp.mockResolvedValueOnce({ status: "ERROR", errorCode: "SEAT_ALREADY_BOOKED", errorMessage: "Taken" });
    expect((await POST(request("BOOK_SEAT", {}, "token"))).status).toBe(409);
    sendTcp.mockResolvedValueOnce({ status: "ERROR", errorCode: "SESSION_EXPIRED", errorMessage: "Expired" });
    const expired = await POST(request("LIST_EVENTS", {}, "token"));
    expect(expired.status).toBe(401);
    expect(expired.headers.get("set-cookie")).toContain("Max-Age=0");
  });
});
