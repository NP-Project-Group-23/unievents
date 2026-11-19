import net from "node:net";
import { randomUUID } from "node:crypto";

export type TcpResponse = {
  requestId: string;
  status: "SUCCESS" | "ERROR";
  data?: Record<string, unknown>;
  errorCode?: string;
  errorMessage?: string;
};

const MAX_FRAME = 65_536;

export function sendTcp(type: string, payload: unknown, token?: string): Promise<TcpResponse> {
  const host = process.env.TCP_HOST || "127.0.0.1";
  const port = Number(process.env.TCP_PORT || 9090);
  const timeout = Number(process.env.TCP_REQUEST_TIMEOUT_MS || 10_000);
  const request = JSON.stringify({ requestId: randomUUID(), type, ...(token ? { token } : {}), payload: payload ?? {} });

  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    let buffer = "";
    let settled = false;
    const finish = (error?: Error, response?: TcpResponse) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      if (error) reject(error); else resolve(response!);
    };
    socket.setEncoding("utf8");
    socket.setTimeout(timeout);
    socket.on("connect", () => socket.write(`${request}\n`));
    socket.on("data", chunk => {
      buffer += chunk;
      if (Buffer.byteLength(buffer, "utf8") > MAX_FRAME) return finish(new Error("Backend response exceeded the protocol limit."));
      const newline = buffer.indexOf("\n");
      if (newline < 0) return;
      try { finish(undefined, JSON.parse(buffer.slice(0, newline)) as TcpResponse); }
      catch { finish(new Error("Backend returned an invalid response.")); }
    });
    socket.on("timeout", () => finish(new Error("Backend request timed out.")));
    socket.on("error", error => finish(error));
    socket.on("end", () => {
      if (!settled) finish(new Error("Backend closed the connection before responding."));
    });
  });
}
