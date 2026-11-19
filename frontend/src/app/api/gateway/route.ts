import { NextRequest, NextResponse } from "next/server";
import { sendTcp } from "@/lib/tcp-gateway";

const COOKIE = "unievents_tcp_session";
const PUBLIC = new Set(["LOGIN", "REGISTER"]);
const ALLOWED = new Set([
  ...PUBLIC, "LOGOUT", "SESSION_CHECK", "LIST_EVENTS", "GET_EVENT", "GET_SEATS",
  "BOOK_SEAT", "MY_BOOKINGS", "GET_BOOKING", "CREATE_EVENT", "UPDATE_EVENT",
  "DELETE_EVENT", "GET_METRICS",
]);

function statusFor(code?: string) {
  if (code === "UNAUTHENTICATED" || code === "SESSION_EXPIRED" || code === "INVALID_CREDENTIALS") return 401;
  if (code === "FORBIDDEN") return 403;
  if (code === "EVENT_NOT_FOUND" || code === "SEAT_NOT_FOUND" || code === "BOOKING_NOT_FOUND") return 404;
  if (code === "DUPLICATE_EMAIL" || code === "SEAT_ALREADY_BOOKED") return 409;
  if (code === "SERVER_OVERLOAD") return 503;
  return 400;
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json() as { type?: string; payload?: unknown };
    const type = body.type?.toUpperCase();
    if (!type || !ALLOWED.has(type)) return NextResponse.json({ errorCode: "INVALID_REQUEST", errorMessage: "Unsupported operation." }, { status: 400 });
    const token = request.cookies.get(COOKIE)?.value;
    if (!PUBLIC.has(type) && !token) return NextResponse.json({ errorCode: "UNAUTHENTICATED", errorMessage: "Please sign in." }, { status: 401 });

    const result = await sendTcp(type, body.payload ?? {}, token);
    const responseBody = result.status === "SUCCESS"
      ? { status: result.status, data: { ...(result.data ?? {}) } }
      : { status: result.status, errorCode: result.errorCode, errorMessage: result.errorMessage };
    if (result.status === "SUCCESS") delete (responseBody.data as Record<string, unknown>).token;
    const response = NextResponse.json(responseBody, { status: result.status === "SUCCESS" ? 200 : statusFor(result.errorCode) });

    if (type === "LOGIN" && result.status === "SUCCESS" && typeof result.data?.token === "string") {
      response.cookies.set(COOKIE, result.data.token, { httpOnly: true, sameSite: "strict", secure: process.env.NODE_ENV === "production", path: "/", maxAge: 30 * 60 });
    }
    if (type === "LOGOUT" || result.errorCode === "UNAUTHENTICATED" || result.errorCode === "SESSION_EXPIRED") {
      response.cookies.set(COOKIE, "", { httpOnly: true, sameSite: "strict", path: "/", maxAge: 0 });
    }
    return response;
  } catch (error) {
    return NextResponse.json({ errorCode: "GATEWAY_UNAVAILABLE", errorMessage: error instanceof Error ? error.message : "TCP gateway unavailable." }, { status: 502 });
  }
}
