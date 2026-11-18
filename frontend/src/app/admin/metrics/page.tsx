"use client";

import { useEffect, useState, useCallback } from "react";
import Link from "next/link";

interface MetricsSnapshot {
  timestamp: string;
  activeWorkers: number;
  currentPoolSize: number;
  corePoolSize: number;
  maxPoolSize: number;
  queuedTasks: number;
  queueRemainingCapacity: number;
  totalSubmittedTasks: number;
  totalCompletedTasks: number;
  totalRejectedTasks: number;
  activeConnections: number;
  peakActiveWorkers: number;
  averageExecutionTimeMs: number;
  maxExecutionTimeMs: number;
  state: string;
}

interface ServerEvent {
  timestamp: string;
  eventType: string;
  message: string;
  details: string;
}

interface DashboardResponse {
  snapshot: MetricsSnapshot;
  recentEvents: ServerEvent[];
}

export default function MetricsMonitoringPage() {
  const [data, setData] = useState<DashboardResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchMetrics = useCallback(async () => {
    try {
      const res = await fetch("/api/admin/metrics/dashboard", { cache: "no-store" });
      if (!res.ok) {
        // Fallback to /api/admin/metrics if dashboard endpoint not available
        const fallbackRes = await fetch("/api/admin/metrics", { cache: "no-store" });
        if (!fallbackRes.ok) throw new Error(`HTTP ${fallbackRes.status}`);
        const snapshot: MetricsSnapshot = await fallbackRes.json();
        setData({ snapshot, recentEvents: [] });
      } else {
        const payload: DashboardResponse = await res.json();
        setData(payload);
      }
      setLastUpdated(new Date());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to connect to backend server");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchMetrics();
    if (!autoRefresh) return;
    const interval = setInterval(fetchMetrics, 2000);
    return () => clearInterval(interval);
  }, [fetchMetrics, autoRefresh]);

  const snap = data?.snapshot;
  const events = data?.recentEvents ?? [];

  const queueTotal = snap ? snap.queuedTasks + snap.queueRemainingCapacity : 50;
  const queuePercent = snap && queueTotal > 0 ? Math.round((snap.queuedTasks / queueTotal) * 100) : 0;
  const workerPercent = snap && snap.maxPoolSize > 0 ? Math.round((snap.activeWorkers / snap.maxPoolSize) * 100) : 0;

  return (
    <main style={{ minHeight: "100vh", background: "#0e1a16", color: "#e3ede6", padding: "32px 24px", fontFamily: "sans-serif" }}>
      <div style={{ maxWidth: 1100, margin: "0 auto" }}>
        
        {/* Header */}
        <header style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 16, marginBottom: 32 }}>
          <div>
            <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 8 }}>
              <span style={{ fontSize: 11, fontWeight: 800, letterSpacing: "0.2em", color: "#48bb78", textTransform: "uppercase" }}>
                UniEvents Network Diagnostics
              </span>
              <span style={{
                background: snap?.state === "RUNNING" ? "#1c4532" : "#742a2a",
                color: snap?.state === "RUNNING" ? "#68d391" : "#feb2b2",
                padding: "3px 10px", borderRadius: 12, fontSize: 11, fontWeight: 700
              }}>
                ● {snap?.state ?? "OFFLINE"}
              </span>
            </div>
            <h1 style={{ fontSize: 28, fontWeight: 800, margin: 0, color: "#f7fafc" }}>
              Server & Worker Pool Monitor
            </h1>
            <p style={{ margin: "6px 0 0", color: "#a0aec0", fontSize: 14 }}>
              Live measurements from Spring Boot bounded ExecutorService (Member 5, Tasks B16/B17)
            </p>
          </div>

          {/* Controls */}
          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <Link href="/" style={{
              background: "#1a2c24", color: "#cbd5e0", textDecoration: "none",
              padding: "8px 16px", borderRadius: 8, fontSize: 13, fontWeight: 600, border: "1px solid #2d4a3e"
            }}>
              ← Back to Portal
            </Link>
            <button
              onClick={() => setAutoRefresh(!autoRefresh)}
              style={{
                background: autoRefresh ? "#22543d" : "#2d3748",
                color: "white", border: "none", padding: "8px 14px",
                borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer"
              }}
            >
              {autoRefresh ? "Auto-refresh: ON (2s)" : "Auto-refresh: PAUSED"}
            </button>
            <button
              onClick={fetchMetrics}
              style={{
                background: "#319795", color: "white", border: "none",
                padding: "8px 14px", borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer"
              }}
            >
              Refresh Now
            </button>
          </div>
        </header>

        {error && (
          <div style={{ background: "#742a2a", color: "#fff5f5", padding: "14px 18px", borderRadius: 10, marginBottom: 24 }}>
            <strong>Connection Error:</strong> {error}. Ensure the Spring Boot backend is running on <code>http://localhost:8080</code>.
          </div>
        )}

        {/* Overload Alert Banner */}
        {snap && snap.totalRejectedTasks > 0 && (
          <div style={{ background: "#975a16", border: "1px solid #d69e2e", color: "#fffff0", padding: "14px 18px", borderRadius: 10, marginBottom: 24 }}>
            <strong>⚠️ Overload Backpressure Active:</strong> {snap.totalRejectedTasks} task(s) rejected with <code>SERVER_OVERLOAD</code>. Worker pool and bounded queue reached capacity.
          </div>
        )}

        {loading && !snap ? (
          <p style={{ textAlign: "center", padding: 60, color: "#a0aec0" }}>Connecting to backend metrics endpoint…</p>
        ) : snap ? (
          <>
            {/* Metric Cards Grid */}
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))", gap: 20, marginBottom: 32 }}>
              
              {/* Card 1: Worker Pool */}
              <div style={{ background: "#14251e", border: "1px solid #243f32", borderRadius: 14, padding: 20 }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: "#81e6d9", textTransform: "uppercase" }}>Worker Threads</span>
                <div style={{ fontSize: 32, fontWeight: 800, margin: "8px 0", color: "#ffffff" }}>
                  {snap.activeWorkers} <span style={{ fontSize: 18, color: "#718096" }}>/ {snap.maxPoolSize} max</span>
                </div>
                <div style={{ height: 6, background: "#243f32", borderRadius: 3, overflow: "hidden", marginBottom: 12 }}>
                  <div style={{ height: "100%", width: `${workerPercent}%`, background: workerPercent > 80 ? "#e53e3e" : "#38b2ac" }} />
                </div>
                <div style={{ fontSize: 12, color: "#a0aec0", display: "grid", gap: 4 }}>
                  <div>Core pool size: <strong>{snap.corePoolSize}</strong></div>
                  <div>Current threads: <strong>{snap.currentPoolSize}</strong></div>
                  <div>Peak active workers: <strong>{snap.peakActiveWorkers}</strong></div>
                </div>
              </div>

              {/* Card 2: Bounded Task Queue */}
              <div style={{ background: "#14251e", border: "1px solid #243f32", borderRadius: 14, padding: 20 }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: "#81e6d9", textTransform: "uppercase" }}>Bounded Task Queue</span>
                <div style={{ fontSize: 32, fontWeight: 800, margin: "8px 0", color: "#ffffff" }}>
                  {snap.queuedTasks} <span style={{ fontSize: 18, color: "#718096" }}>/ {queueTotal} slots</span>
                </div>
                <div style={{ height: 6, background: "#243f32", borderRadius: 3, overflow: "hidden", marginBottom: 12 }}>
                  <div style={{ height: "100%", width: `${queuePercent}%`, background: queuePercent > 80 ? "#dd6b20" : "#48bb78" }} />
                </div>
                <div style={{ fontSize: 12, color: "#a0aec0", display: "grid", gap: 4 }}>
                  <div>Remaining capacity: <strong>{snap.queueRemainingCapacity}</strong></div>
                  <div>Queue utilization: <strong>{queuePercent}%</strong></div>
                  <div>Backpressure policy: <strong>ServerRejectedExecution</strong></div>
                </div>
              </div>

              {/* Card 3: Task Throughput */}
              <div style={{ background: "#14251e", border: "1px solid #243f32", borderRadius: 14, padding: 20 }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: "#81e6d9", textTransform: "uppercase" }}>Task Throughput</span>
                <div style={{ fontSize: 32, fontWeight: 800, margin: "8px 0", color: "#ffffff" }}>
                  {snap.totalCompletedTasks}
                </div>
                <div style={{ fontSize: 12, color: "#a0aec0", display: "grid", gap: 6, marginTop: 16 }}>
                  <div>Total submitted: <strong>{snap.totalSubmittedTasks}</strong></div>
                  <div>Completed: <strong style={{ color: "#68d391" }}>{snap.totalCompletedTasks}</strong></div>
                  <div>Rejected (overload): <strong style={{ color: snap.totalRejectedTasks > 0 ? "#fc8181" : "#a0aec0" }}>{snap.totalRejectedTasks}</strong></div>
                </div>
              </div>

              {/* Card 4: Connections & Latency */}
              <div style={{ background: "#14251e", border: "1px solid #243f32", borderRadius: 14, padding: 20 }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: "#81e6d9", textTransform: "uppercase" }}>Clients & Latency</span>
                <div style={{ fontSize: 32, fontWeight: 800, margin: "8px 0", color: "#ffffff" }}>
                  {snap.activeConnections} <span style={{ fontSize: 16, color: "#718096" }}>clients</span>
                </div>
                <div style={{ fontSize: 12, color: "#a0aec0", display: "grid", gap: 6, marginTop: 16 }}>
                  <div>Average task time: <strong>{snap.averageExecutionTimeMs} ms</strong></div>
                  <div>Peak task time: <strong>{snap.maxExecutionTimeMs} ms</strong></div>
                  <div>Metrics polled at: <strong>{lastUpdated?.toLocaleTimeString()}</strong></div>
                </div>
              </div>
            </div>

            {/* Live Operational Events Table */}
            <div style={{ background: "#14251e", border: "1px solid #243f32", borderRadius: 14, padding: 24 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 18 }}>
                <div>
                  <h2 style={{ fontSize: 18, fontWeight: 700, margin: 0, color: "#f7fafc" }}>
                    Live Operational Events Stream
                  </h2>
                  <p style={{ margin: "4px 0 0", color: "#a0aec0", fontSize: 13 }}>
                    Genuine concurrency and connection events from <code>ServerEventLogBuffer</code>
                  </p>
                </div>
                <span style={{ fontSize: 12, color: "#718096" }}>{events.length} recorded</span>
              </div>

              {events.length === 0 ? (
                <p style={{ color: "#718096", fontSize: 13, fontStyle: "italic", textAlign: "center", padding: 24 }}>
                  No operational events recorded yet. Connect a client or trigger requests to observe genuine events.
                </p>
              ) : (
                <div style={{ overflowX: "auto" }}>
                  <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13, textAlign: "left" }}>
                    <thead>
                      <tr style={{ borderBottom: "1px solid #243f32", color: "#718096" }}>
                        <th style={{ padding: "8px 12px" }}>Timestamp</th>
                        <th style={{ padding: "8px 12px" }}>Event Type</th>
                        <th style={{ padding: "8px 12px" }}>Message</th>
                        <th style={{ padding: "8px 12px" }}>Details</th>
                      </tr>
                    </thead>
                    <tbody>
                      {events.map((evt, idx) => {
                        const isOverload = evt.eventType === "OVERLOAD_REJECTED";
                        const isConn = evt.eventType.startsWith("CONNECTION");
                        const badgeColor = isOverload ? "#9b2c2c" : isConn ? "#2c5282" : "#285e61";
                        const textColor = isOverload ? "#fed7d7" : isConn ? "#bee3f8" : "#b2f5ea";

                        return (
                          <tr key={idx} style={{ borderBottom: "1px solid #1a3026" }}>
                            <td style={{ padding: "10px 12px", color: "#a0aec0", fontFamily: "monospace", fontSize: 12 }}>
                              {new Date(evt.timestamp).toLocaleTimeString()}
                            </td>
                            <td style={{ padding: "10px 12px" }}>
                              <span style={{
                                background: badgeColor, color: textColor,
                                padding: "2px 8px", borderRadius: 4, fontSize: 11, fontWeight: 700
                              }}>
                                {evt.eventType}
                              </span>
                            </td>
                            <td style={{ padding: "10px 12px", color: "#edf2f7", fontWeight: 500 }}>{evt.message}</td>
                            <td style={{ padding: "10px 12px", color: "#a0aec0", fontSize: 12 }}>{evt.details}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        ) : null}

        {/* Footer note */}
        <p style={{ marginTop: 24, fontSize: 12, color: "#718096", textAlign: "center" }}>
          UniEvents Network Programming Project • Evaluated on raw ExecutorService, TCP sockets, multithreading, and synchronization concepts.
        </p>
      </div>
    </main>
  );
}
