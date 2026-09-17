import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'

const POLL_INTERVAL_MS = 5000

const METRIC_UNITS = {
  CPU: '%',
  MEMORY: '%',
  DISK: '%',
  NETWORK: ' Mbps',
  LATENCY: ' ms',
  ERROR_RATE: '%',
}

export function ServiceDetail({ service }) {
  const { auth, handleAuthError } = useAuth()
  const [metrics, setMetrics] = useState([])
  const [logs, setLogs] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const load = useCallback(async (serviceId) => {
    setLoading(true)
    try {
      const [metricData, logData] = await Promise.all([
        api.getServiceMetrics(serviceId, auth.token),
        api.getServiceLogs(serviceId, auth.token),
      ])
      setMetrics(metricData)
      setLogs(logData)
      setError(null)
    } catch (err) {
      if (!handleAuthError(err)) setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [auth.token, handleAuthError])

  useEffect(() => {
    if (!service) return
    load(service.id)
    const interval = setInterval(() => load(service.id), POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [service, load])

  if (!service) {
    return (
      <section className="panel detail-panel">
        <h2>Service detail</h2>
        <p className="muted">Select a service to see its recent metrics and logs.</p>
      </section>
    )
  }

  // Records come back newest-first, so the first record per type is the latest value.
  const latestByType = {}
  for (const m of metrics) {
    if (!latestByType[m.metricType]) latestByType[m.metricType] = m
  }

  return (
    <section className="panel detail-panel">
      <h2>{service.name}</h2>
      <p className="muted">
        {service.type} &middot; {service.criticality}
        {service.hostname ? ` · ${service.hostname}` : ''}
      </p>

      {error && <p className="error-text" role="alert">Failed to load telemetry: {error}</p>}

      <h3>Latest metrics</h3>
      {loading && metrics.length === 0 && <p className="muted">Loading metrics...</p>}
      {!loading && metrics.length === 0 && !error && (
        <p className="muted">No metrics recorded yet for this service.</p>
      )}
      {Object.keys(latestByType).length > 0 && (
        <div className="stat-grid">
          {Object.entries(latestByType).map(([type, m]) => (
            <div className="stat-tile" key={type}>
              <span className="stat-label">{type}</span>
              <span className="stat-value">
                {m.value}{METRIC_UNITS[type] ?? ''}
              </span>
            </div>
          ))}
        </div>
      )}

      <h3>Recent logs</h3>
      {!loading && logs.length === 0 && !error && (
        <p className="muted">No logs recorded yet for this service.</p>
      )}
      {logs.length > 0 && (
        <ul className="log-list">
          {logs.slice(0, 10).map((l) => (
            <li key={l.id} className={`log-${l.level.toLowerCase()}`}>
              <span className="log-level">{l.level}</span>
              <span className="log-message">{l.message}</span>
              <span className="log-time">{new Date(l.timestamp).toLocaleTimeString()}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
