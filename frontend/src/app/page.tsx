"use client";

import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";

type User = { id: number; name: string; email: string };

export default function Home() {
  const [user, setUser] = useState<User | null>(null);
  const [register, setRegister] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    fetch("/api/auth/me", { cache: "no-store" })
      .then(async response => {
        if (response.ok) setUser(await response.json());
        else if (response.status !== 401) throw new Error();
      })
      .catch(() => setError("Cannot reach the server. Make sure the backend is running."))
      .finally(() => setLoading(false));
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    await mutate(register ? "register" : "login", {
      name: fields.get("name"), email: fields.get("email"), password: fields.get("password"),
    });
  }

  async function mutate(action: string, body?: object) {
    setBusy(true);
    setError("");
    try {
      const csrfResponse = await fetch("/api/auth/csrf", { cache: "no-store" });
      if (!csrfResponse.ok) throw new Error("Cannot reach the server. Please try again.");
      const csrf = await csrfResponse.json();
      const response = await fetch(`/api/auth/${action}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token },
        body: body ? JSON.stringify(body) : undefined,
      });
      if (!response.ok) {
        const messages: Record<number, string> = {
          400: "Check your details. Use a valid email and a password of 8–72 bytes when registering.",
          401: "Incorrect email or password.",
          403: "Your session expired. Please try again.",
          409: "This email already has an account. Sign in instead.",
        };
        throw new Error(messages[response.status] || "Something went wrong. Please try again.");
      }
      setUser(action === "logout" ? null : await response.json());
      setRegister(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Cannot reach the server.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth-shell">
      <section className="auth-card" aria-labelledby="heading">
        <p className="brand">UNIEVENTS</p>
        {loading ? <p role="status">Checking your session…</p> : user ? <>
          <span className="badge">You’re signed in</span>
          <h1 id="heading">Welcome, {user.name}.</h1>
          <p className="subtitle">Your next campus experience starts here.</p>
          <div className="account"><span>Your account</span><strong>{user.email}</strong></div>
          <button disabled={busy} onClick={() => mutate("logout")}>{busy ? "Signing out…" : "Sign out"}</button>
        </> : <>
          <h1 id="heading">{register ? "Join your campus." : "Welcome back."}</h1>
          <p className="subtitle">{register ? "Create your UniEvents account to get started." : "Sign in to your UniEvents account."}</p>
          <form onSubmit={submit} key={register ? "register" : "login"}>
            {register && <label>Full name<input name="name" autoComplete="name" required maxLength={100} placeholder="Your name" /></label>}
            <label>Email address<input name="email" type="email" autoComplete="email" required maxLength={254} placeholder="you@university.edu" /></label>
            <label>Password<input name="password" type="password" autoComplete={register ? "new-password" : "current-password"} required minLength={register ? 8 : 1} maxLength={72} placeholder={register ? "At least 8 characters" : "Your password"} /></label>
            <button disabled={busy} type="submit">{busy ? "Please wait…" : register ? "Create account" : "Sign in"}</button>
          </form>
          <p className="switch">{register ? "Already have an account?" : "New to UniEvents?"} <button className="text-button" disabled={busy} onClick={() => { setRegister(!register); setError(""); }}>{register ? "Sign in" : "Create account"}</button></p>
        </>}
        {error && <p className="error" role="alert">{error}</p>}
      </section>
      <Link href="/monitoring" style={{ marginTop: 18, fontSize: 13, color: "#215d45", textDecoration: "none", fontWeight: 600 }}>
        📊 Live Server & Thread Pool Monitor →
      </Link>
      <p className="footer-note">Campus moments. Shared experiences.</p>
    </main>
  );
}
