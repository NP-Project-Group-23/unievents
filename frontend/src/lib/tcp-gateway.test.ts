import { EventEmitter } from "node:events";
import { beforeEach, describe, expect, it, vi } from "vitest";

class FakeSocket extends EventEmitter {
  written = "";
  destroyed = false;
  setEncoding() { return this; }
  setTimeout() { return this; }
  write(value: string) { this.written = value; return true; }
  destroy() { this.destroyed = true; return this; }
}

let socket: FakeSocket;
vi.mock("node:net", () => ({ default: { createConnection: vi.fn(() => socket) } }));

import { sendTcp } from "./tcp-gateway";

describe("sendTcp", () => {
  beforeEach(() => { socket = new FakeSocket(); });

  it("writes one newline-delimited request and parses the matching response", async () => {
    const pending = sendTcp("PING", {});
    socket.emit("connect");
    const request = JSON.parse(socket.written.trim());
    expect(request.type).toBe("PING");
    expect(request.requestId).toMatch(/[0-9a-f-]{36}/);
    socket.emit("data", `${JSON.stringify({ requestId: request.requestId, status: "SUCCESS", data: { message: "PONG" } })}\n`);
    await expect(pending).resolves.toMatchObject({ status: "SUCCESS", data: { message: "PONG" } });
    expect(socket.destroyed).toBe(true);
  });

  it("sends protected tokens only inside the server-side TCP request", async () => {
    const pending = sendTcp("LIST_EVENTS", { page: 0 }, "secret-token");
    socket.emit("connect");
    expect(JSON.parse(socket.written).token).toBe("secret-token");
    socket.emit("data", `${JSON.stringify({ requestId: "id", status: "SUCCESS", data: { items: [] } })}\n`);
    await pending;
  });

  it("rejects timeouts and oversized frames", async () => {
    const timeout = sendTcp("PING", {});
    socket.emit("timeout");
    await expect(timeout).rejects.toThrow("timed out");

    socket = new FakeSocket();
    const oversized = sendTcp("PING", {});
    socket.emit("data", "x".repeat(65_537));
    await expect(oversized).rejects.toThrow("protocol limit");
  });
});
