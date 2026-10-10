import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'

const POLL_INTERVAL_MS = 5000
const STATUSES = ['OPEN', 'CLOSED']

// Detected anomaly episodes (GET /api/anomalies, newest first, capped by the backend). One row
// per episode per detector - a metric caught by both L0 and L1 appears twice, once for each.
export function AnomaliesPage({ services }) {
  const { auth, handleAuthError } = useAuth()
  const [serviceId, setServiceId] = useState('')
  const [status, setStatus] = useState('')
  const [anomalies, setAnomalies] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      const data = await api.getAnomalies(auth.token, {
        serviceId: serviceId || undefined,
        status: status || undefined,
      })
      setAnomalies(data)
      setError(null)
    } catch (err) {
      if (!handleAuthError(err)) setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [auth.token, handleAuthError, serviceId, status])

  useEffect(() => {
    setLoading(true)
    load()
    const interval = setInterval(load, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [load])

  const serviceNameById = Object.fromEntries(services.map((s) => [s.id, s.name]))
  const openCount = anomalies.filter((a) => a.status === 'OPEN').length

  return (
    <section className="panel anomalies-page">
      <div className="panel-header">
        <h2>Anomalies</h2>
        <span className="muted">{anomalies.length} shown · {openCount} open</span>
      </div>
      <p className="muted anomalies-caption">
        L1 = rolling robust z-score. L0 = fixed thresholds. Episode end times can run late; start times are accurate.
      </p>

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
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All statuses</option>
            {STATUSES.map((st) => (
              <option key={st} value={st}>{st}</option>
            ))}
          </select>
        </label>
      </div>

      {loading && anomalies.length === 0 && !error && <p className="muted">Loading anomalies...</p>}
      {error && <p className="error-text" role="alert">Failed to load anomalies: {error}</p>}
      {!loading && !error && anomalies.length === 0 && (
        <p className="muted">No anomalies detected for the selected filters.</p>
      )}

      {anomalies.length > 0 && (
        <table className="anomalies-table">
          <thead>
            <tr>
              <th>Service</th>
              <th>Metric</th>
              <th>Detector</th>
              <th>Status</th>
              <th>Start</th>
              <th>Ended</th>
              <th>Peak score</th>
              <th>Peak detail</th>
            </tr>
          </thead>
          <tbody>
            {anomalies.map((a) => (
              <tr key={a.id} className={a.status === 'OPEN' ? 'anomaly-open' : ''}>
                <td>{serviceNameById[a.serviceId] ?? `Service ${a.serviceId}`}</td>
                <td>{a.metricType ?? '—'}</td>
                <td><span className="badge">{a.detector ?? '—'}</span></td>
                <td>
                  <span className={`badge ${a.status === 'OPEN' ? 'badge-status-degraded' : 'badge-status-unknown'}`}>
                    {a.status ?? '—'}
                  </span>
                </td>
                <td>{formatTime(a.detectedAt)}</td>
                <td>{a.endedAt ? formatTime(a.endedAt) : a.status === 'OPEN' ? 'ongoing' : '—'}</td>
                <td>{formatPeak(a)}</td>
                <td className="anomaly-detail">{a.rawReference ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

function formatTime(iso) {
  return iso ? new Date(iso).toLocaleTimeString() : '—'
}

// L1 peak = ml-service score (0-1, 0.5 = its threshold); L0 peak = value / threshold.
function formatPeak(a) {
  if (a.anomalyScore == null) return '—'
  return a.detector === 'L0' ? `${a.anomalyScore.toFixed(2)}× limit` : a.anomalyScore.toFixed(3)
}
