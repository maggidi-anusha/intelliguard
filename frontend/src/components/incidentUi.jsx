// Small display helpers shared by the incident list and the incident workspace.

export function IncidentStatusBadge({ status }) {
  return <span className={`badge badge-incident-${(status ?? 'unknown').toLowerCase()}`}>{status ?? '—'}</span>
}

export function SeverityBadge({ level }) {
  if (!level) return <span className="muted">—</span>
  return <span className={`badge badge-${level.toLowerCase()}`}>{level}</span>
}

export function formatConfidence(value) {
  return typeof value === 'number' ? value.toFixed(2) : '—'
}

export function formatTime(iso) {
  return iso ? new Date(iso).toLocaleTimeString() : '—'
}
