import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'
import { IncidentDetail } from './IncidentDetail'
import { formatConfidence, formatTime, IncidentStatusBadge, SeverityBadge } from './incidentUi'

const POLL_INTERVAL_MS = 5000
const STATUSES = ['OPEN', 'INVESTIGATING', 'RESOLVED', 'MERGED']

// Correlated incidents (GET /api/incidents, newest first, capped by the backend). Clicking a row
// opens the incident workspace (IncidentDetail); "Back to incidents" returns to the list.
export function IncidentsPage({ services }) {
  const { auth, handleAuthError } = useAuth()
  const [status, setStatus] = useState('')
  const [incidents, setIncidents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [selectedId, setSelectedId] = useState(null)

  const load = useCallback(async () => {
    try {
      setIncidents(await api.getIncidents(auth.token, { status: status || undefined }))
      setError(null)
    } catch (err) {
      if (!handleAuthError(err)) setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [auth.token, handleAuthError, status])

  useEffect(() => {
    if (selectedId != null) return undefined
    setLoading(true)
    load()
    const interval = setInterval(load, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [load, selectedId])

  const serviceNameById = Object.fromEntries(services.map((s) => [s.id, s.name]))

  if (selectedId != null) {
    return (
      <IncidentDetail id={selectedId} serviceNameById={serviceNameById} onBack={() => setSelectedId(null)} />
    )
  }

  const activeCount = incidents.filter((i) => i.status === 'OPEN' || i.status === 'INVESTIGATING').length

  return (
    <section className="panel incidents-page">
      <div className="panel-header">
        <h2>Incidents</h2>
        <span className="muted">{incidents.length} shown · {activeCount} open or investigating</span>
      </div>
      <p className="muted anomalies-caption">
        Anomalies that are close in time and in the dependency graph are grouped into one incident. The likely
        root cause is a heuristic ranking, not a learned model. Click an incident to open it.
      </p>

      <div className="logs-filter-bar">
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

      {loading && incidents.length === 0 && !error && <p className="muted">Loading incidents...</p>}
      {error && <p className="error-text" role="alert">Failed to load incidents: {error}</p>}
      {!loading && !error && incidents.length === 0 && (
        <p className="muted">No incidents for the selected filter.</p>
      )}

      {incidents.length > 0 && (
        <table className="incidents-table">
          <thead>
            <tr>
              <th>Status</th>
              <th>Severity (peak)</th>
              <th>Title</th>
              <th>Services</th>
              <th>Likely root cause</th>
              <th>Started</th>
              <th>Last activity</th>
            </tr>
          </thead>
          <tbody>
            {incidents.map((i) => (
              <tr key={i.id} className={i.status === 'OPEN' || i.status === 'INVESTIGATING' ? 'incident-active' : ''}
                onClick={() => setSelectedId(i.id)}>
                <td><IncidentStatusBadge status={i.status} /></td>
                <td>
                  <SeverityBadge level={i.severity} />
                  {i.peakSeverity && i.peakSeverity !== i.severity && (
                    <span className="muted incident-peak"> peak <SeverityBadge level={i.peakSeverity} /></span>
                  )}
                </td>
                <td>{i.title}</td>
                <td>{i.serviceIds?.length ?? 0}</td>
                <td>
                  {i.rootServiceId != null ? (
                    <>
                      {serviceNameById[i.rootServiceId] ?? `Service ${i.rootServiceId}`}
                      <span className="muted"> · confidence {formatConfidence(i.rootCause?.confidence)}</span>
                    </>
                  ) : '—'}
                </td>
                <td>{formatTime(i.startedAt)}</td>
                <td>{formatTime(i.lastActivityAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
