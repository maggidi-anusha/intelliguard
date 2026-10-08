import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'

const POLL_INTERVAL_MS = 5000
const LOG_LEVELS = ['INFO', 'WARN', 'ERROR']

// Dedicated, filterable, cross-service log view (GET /api/logs). `services` is passed down
// from Dashboard.jsx, which already polls the service list - this avoids a second fetch of
// the same data just to populate the service filter dropdown.
export function LogsPage({ services }) {
  const { auth, handleAuthError } = useAuth()
  const [serviceId, setServiceId] = useState('')
  const [level, setLevel] = useState('')
  const [logs, setLogs] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      const data = await api.getLogs(auth.token, {
        serviceId: serviceId || undefined,
        level: level || undefined,
      })
      setLogs(data)
      setError(null)
    } catch (err) {
      if (!handleAuthError(err)) setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [auth.token, handleAuthError, serviceId, level])

  useEffect(() => {
    setLoading(true)
    load()
    const interval = setInterval(load, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [load])

  const serviceNameById = Object.fromEntries(services.map((s) => [s.id, s.name]))

  return (
    <section className="panel logs-page">
      <div className="panel-header">
        <h2>Logs</h2>
      </div>

      <div className="logs-filter-bar">
        <label>
          Service
          <select value={serviceId} onChange={(e) => setServiceId(e.target.value)}>
            <option value="">All services</option>
            {services.map((s) => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
        </label>
        <label>
          Level
          <select value={level} onChange={(e) => setLevel(e.target.value)}>
            <option value="">All levels</option>
            {LOG_LEVELS.map((l) => (
              <option key={l} value={l}>{l}</option>
            ))}
          </select>
        </label>
      </div>

      {loading && logs.length === 0 && <p className="muted">Loading logs...</p>}
      {error && <p className="error-text" role="alert">Failed to load logs: {error}</p>}
      {!loading && !error && logs.length === 0 && (
        <p className="muted">No log records found for the selected filters.</p>
      )}

      {logs.length > 0 && (
        <ul className="log-list logs-page-list">
          {logs.map((l) => (
            <li key={l.id} className={`log-${l.level.toLowerCase()}`}>
              <span className="log-level">{l.level}</span>
              <span className="log-service">{serviceNameById[l.serviceId] ?? `Service ${l.serviceId}`}</span>
              <span className="log-message">{l.message}</span>
              <span className="log-time">{new Date(l.timestamp).toLocaleString()}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
