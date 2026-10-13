import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'
import { CopilotPanel } from './CopilotPanel'
import { formatConfidence, formatTime, IncidentStatusBadge, SeverityBadge } from './incidentUi'

const POLL_INTERVAL_MS = 5000
const MAX_NOTE_LENGTH = 2000

// The incident workspace (GET /api/incidents/{id}, polled): ranked root-cause candidates with their
// evidence, affected services with current risk, the timeline, the anomaly episodes and the notes.
// USER and ADMIN can add notes and acknowledge; for VIEWER both controls are hidden. Every role can
// ask the incident copilot (CopilotPanel).
export function IncidentDetail({ id, serviceNameById, onBack }) {
  const { auth, handleAuthError } = useAuth()
  const canWrite = auth.role === 'USER' || auth.role === 'ADMIN'
  const [incident, setIncident] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      setIncident(await api.getIncident(auth.token, id))
      setError(null)
    } catch (err) {
      if (!handleAuthError(err)) setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [auth.token, handleAuthError, id])

  useEffect(() => {
    load()
    const interval = setInterval(load, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [load])

  return (
    <section className="panel incident-detail">
      <div className="panel-header">
        <button type="button" onClick={onBack}>← Back to incidents</button>
      </div>

      {loading && !incident && !error && <p className="muted">Loading incident...</p>}
      {error && !incident && <p className="error-text" role="alert">Failed to load incident {id}: {error}</p>}
      {incident && (
        <>
          {error && <p className="error-text" role="alert">Latest refresh failed: {error} (showing last known values)</p>}
          <IncidentHeader incident={incident} />
          {canWrite && <AcknowledgeAction incident={incident} onDone={load} />}
          {!canWrite && (
            <p className="muted incident-readonly">Read-only view: notes and acknowledgement need a USER or ADMIN account.</p>
          )}

          <CopilotPanel key={incident.id} incidentId={incident.id} />

          <div className="incident-grid">
            <RootCause incident={incident} />
            <AffectedServices services={incident.services ?? []} />
          </div>

          <div className="incident-grid">
            <Timeline entries={incident.timeline ?? []} />
            <Notes incident={incident} canWrite={canWrite} onAdded={load} />
          </div>

          <Anomalies anomalies={incident.anomalies ?? []} serviceNameById={serviceNameById} />
        </>
      )}
    </section>
  )
}

function IncidentHeader({ incident }) {
  return (
    <div className="incident-header">
      <h2>
        {incident.title} <span className="muted">#{incident.id}</span>
      </h2>
      <div className="incident-header-badges">
        <IncidentStatusBadge status={incident.status} />
        <span className="muted">Severity</span> <SeverityBadge level={incident.severity} />
        <span className="muted">Peak</span> <SeverityBadge level={incident.peakSeverity} />
      </div>
      <p className="incident-times">
        Started {formatTime(incident.startedAt)} · Last activity {formatTime(incident.lastActivityAt)}
        {' · '}Resolved {incident.resolvedAt ? formatTime(incident.resolvedAt) : '—'}
        {incident.acknowledgedBy && (
          <> · Acknowledged by <strong>{incident.acknowledgedBy}</strong> at {formatTime(incident.acknowledgedAt)}</>
        )}
      </p>
      {incident.status === 'MERGED' && (
        <p className="risk-notice" role="status">Merged into incident #{incident.mergedIntoId}.</p>
      )}
      <p className="muted risk-caption">
        Heuristic ranking, not a learned model. Confidence = how far rank 1 is ahead of rank 2.
      </p>
    </div>
  )
}

function AcknowledgeAction({ incident, onDone }) {
  const { auth, handleAuthError } = useAuth()
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState(null)

  if (incident.status !== 'OPEN' && incident.status !== 'INVESTIGATING') return null

  async function acknowledge() {
    setActionError(null)
    setSaving(true)
    try {
      await api.acknowledgeIncident(auth.token, incident.id)
      await onDone()
    } catch (err) {
      if (!handleAuthError(err)) setActionError(err.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="incident-actions">
      {incident.status === 'OPEN' ? (
        <button type="button" onClick={acknowledge} disabled={saving}>
          {saving ? 'Acknowledging...' : 'Acknowledge'}
        </button>
      ) : (
        <span className="muted incident-ack-note">Acknowledged - status INVESTIGATING until the incident resolves.</span>
      )}
      {actionError && <p className="error-text" role="alert">Acknowledge failed: {actionError}</p>}
    </div>
  )
}

function RootCause({ incident }) {
  const candidates = incident.rootCause?.candidates ?? []
  return (
    <div className="incident-section">
      <h3>Likely root cause</h3>
      <p className="muted incident-subcaption">
        Confidence {formatConfidence(incident.rootCause?.confidence)} · each score combines onset order, dependent
        services, peak anomaly score, security events and criticality.
      </p>
      {candidates.length === 0 && <p className="muted">No ranking available yet.</p>}
      <ol className="candidate-list">
        {candidates.map((c) => (
          <li key={c.serviceId} className={c.rank === 1 ? 'candidate-top' : ''}>
            <div className="candidate-row">
              <span className="candidate-rank">#{c.rank}</span>
              <span className="candidate-name">{c.serviceName ?? `Service ${c.serviceId}`}</span>
              <span className="risk-bar" aria-hidden="true">
                <span className="risk-bar-fill" style={{ width: `${Math.min(100, Math.max(0, (c.score ?? 0) * 100))}%` }} />
              </span>
              <span className="candidate-score">{typeof c.score === 'number' ? c.score.toFixed(2) : '—'}</span>
            </div>
            <ul className="candidate-evidence">
              {(c.evidence ?? []).map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          </li>
        ))}
      </ol>
    </div>
  )
}

function AffectedServices({ services }) {
  return (
    <div className="incident-section">
      <h3>Affected services</h3>
      {services.length === 0 && <p className="muted">No services linked.</p>}
      {services.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Service</th>
              <th>Type</th>
              <th>Criticality</th>
              <th>Current risk</th>
            </tr>
          </thead>
          <tbody>
            {services.map((s) => (
              <tr key={s.serviceId} className="static-row">
                <td>{s.name ?? `Service ${s.serviceId}`}</td>
                <td>{s.type ?? '—'}</td>
                <td><SeverityBadge level={s.criticality} /></td>
                <td>
                  {s.currentRiskLevel ? (
                    <span className={`badge badge-${s.currentRiskLevel.toLowerCase()} risk-badge`}>
                      {s.currentRiskLevel} {s.currentRiskScore != null ? Math.round(s.currentRiskScore) : ''}
                    </span>
                  ) : (
                    <span className="muted">not scored</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function Timeline({ entries }) {
  return (
    <div className="incident-section">
      <h3>Timeline</h3>
      {entries.length === 0 && <p className="muted">No events yet.</p>}
      <ul className="incident-timeline">
        {entries.map((t, n) => (
          <li key={`${t.at}-${t.type}-${t.anomalyId ?? n}`}
            className={`timeline-${t.type.toLowerCase()}${t.anomalyId == null ? ' timeline-incident-event' : ''}`}>
            <span className="event-time">{formatTime(t.at)}</span>
            <span className="timeline-detail">
              {t.serviceName ? `${t.serviceName}: ` : ''}{capitalize(t.detail ?? t.type)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function Notes({ incident, canWrite, onAdded }) {
  const { auth, handleAuthError } = useAuth()
  const [text, setText] = useState('')
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState(null)
  const notes = incident.notes ?? []
  const canAdd = canWrite && incident.status !== 'MERGED'

  async function submit(e) {
    e.preventDefault()
    setFormError(null)
    setSaving(true)
    try {
      await api.addIncidentNote(auth.token, incident.id, text)
      setText('')
      await onAdded()
    } catch (err) {
      if (!handleAuthError(err)) setFormError(err.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="incident-section">
      <h3>Notes</h3>
      {incident.notesTotal > notes.length && (
        <p className="muted">Showing the newest {notes.length} of {incident.notesTotal} notes.</p>
      )}
      {notes.length === 0 && <p className="muted">No notes yet.</p>}
      <ul className="note-list">
        {notes.map((n) => (
          <li key={n.id}>
            <div className="note-meta">
              <strong>{n.author}</strong> <span className="muted">{formatTime(n.createdAt)}</span>
            </div>
            <p className="note-text">{n.text}</p>
          </li>
        ))}
      </ul>
      {canAdd && (
        <form className="note-form" onSubmit={submit}>
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            maxLength={MAX_NOTE_LENGTH}
            rows={3}
            placeholder="Add a note for the team (what you checked, what you changed)..."
          />
          <div className="note-form-row">
            <span className="muted">{text.trim().length} / {MAX_NOTE_LENGTH}</span>
            <button type="submit" disabled={saving || text.trim().length === 0}>
              {saving ? 'Saving...' : 'Add note'}
            </button>
          </div>
          {formError && <p className="error-text" role="alert">Could not add the note: {formError}</p>}
        </form>
      )}
    </div>
  )
}

function Anomalies({ anomalies, serviceNameById }) {
  return (
    <div className="incident-section">
      <h3>Anomaly episodes</h3>
      {anomalies.length === 0 && <p className="muted">No anomalies linked.</p>}
      {anomalies.length > 0 && (
        <table className="anomalies-table">
          <thead>
            <tr>
              <th>Service</th>
              <th>Metric</th>
              <th>Detector</th>
              <th>Status</th>
              <th>Start</th>
              <th>End</th>
              <th>Peak</th>
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
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function capitalize(text) {
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : text
}

// Same convention as the Anomalies tab: L1 score 0-1 (0.5 = threshold); L0 = value / limit.
function formatPeak(a) {
  if (a.anomalyScore == null) return '—'
  return a.detector === 'L0' ? `${a.anomalyScore.toFixed(2)}× limit` : a.anomalyScore.toFixed(3)
}
