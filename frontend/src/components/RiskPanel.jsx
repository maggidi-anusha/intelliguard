import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'
import { MetricChart } from './MetricChart'

const POLL_INTERVAL_MS = 5000

// Order and wording of the formula's components (see docs/risk-engine.md).
const COMPONENTS = [
  { key: 'ml', label: 'ML score (L1)', describe: (v) => (v == null ? 'unavailable' : `score ${Number(v).toFixed(2)}`) },
  { key: 'l0', label: 'L0 threshold violation', describe: (v, risk) => (v ? `violated (${risk.l0ViolatedMetrics.join(', ')})` : 'none') },
  { key: 'criticality', label: 'Criticality', describe: (v) => v ?? '—' },
  { key: 'errors', label: 'Error logs', describe: (v) => `${Number(v ?? 0).toFixed(1)} / min` },
  { key: 'security', label: 'Security events', describe: (v) => `${v ?? 0} in 5 min` },
]

// Current risk for one service (from the Dashboard's /api/risk/current poll) plus its persisted
// history (/api/risk/history). The breakdown is plain visible text and bars, not a tooltip.
export function RiskPanel({ service, risk, riskLoading, riskError }) {
  const { auth, handleAuthError } = useAuth()
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(true)
  const [historyError, setHistoryError] = useState(null)

  const loadHistory = useCallback(async (serviceId) => {
    try {
      setHistory(await api.getRiskHistory(auth.token, serviceId))
      setHistoryError(null)
    } catch (err) {
      if (!handleAuthError(err)) setHistoryError(err.message)
    } finally {
      setHistoryLoading(false)
    }
  }, [auth.token, handleAuthError])

  useEffect(() => {
    setHistory([])
    setHistoryLoading(true)
    loadHistory(service.id)
    const interval = setInterval(() => loadHistory(service.id), POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [service.id, loadHistory])

  const chartData = history
    .slice()
    .reverse()
    .map((r) => ({ time: new Date(r.calculatedAt).toLocaleTimeString(), value: r.score }))

  return (
    <div className="risk-section">
      <h3>Risk</h3>
      <RiskNow risk={risk} riskLoading={riskLoading} riskError={riskError} />
      <p className="muted risk-caption">
        Risk is a weighted formula over detector outputs and signals, not a learned model.
      </p>

      {historyError && <p className="error-text" role="alert">Failed to load risk history: {historyError}</p>}
      {historyLoading && history.length === 0 && !historyError && <p className="muted">Loading risk history...</p>}
      {!historyLoading && !historyError && history.length === 0 && (
        <p className="muted">No risk history recorded yet for this service.</p>
      )}
      {history.length > 0 && (
        <div className="risk-history">
          <MetricChart metricType="RISK SCORE (0-100)" data={chartData} domain={[0, 100]} />
        </div>
      )}
    </div>
  )
}

function RiskNow({ risk, riskLoading, riskError }) {
  if (riskError && !risk) {
    return <p className="error-text" role="alert">Failed to load risk: {riskError}</p>
  }
  if (!risk) {
    return <p className="muted">{riskLoading ? 'Loading risk...' : 'This service has not been scored yet.'}</p>
  }
  if (risk.score == null || !risk.level) {
    return <p className="muted">Not scored: {risk.reason ?? 'insufficient data'}.</p>
  }

  const components = risk.breakdown?.components ?? {}
  return (
    <>
      {riskError && <p className="error-text" role="alert">Latest risk refresh failed: {riskError} (showing last known values)</p>}
      <div className="risk-now">
        <span className={`risk-score-value risk-text-${risk.level.toLowerCase()}`}>{risk.score.toFixed(1)}</span>
        <span className="risk-score-scale">/ 100</span>
        <span className={`badge badge-${risk.level.toLowerCase()}`}>{risk.level}</span>
      </div>

      {!risk.mlAvailable && (
        <p className="risk-notice" role="status">
          ML scoring unavailable - risk based on thresholds and signals only
        </p>
      )}

      <ul className="risk-breakdown">
        {COMPONENTS.map(({ key, label, describe }) => {
          const c = components[key]
          if (!c) return null
          const max = c.weight * 100
          return (
            <li key={key}>
              <span className="risk-component-label">{label}</span>
              <span className="risk-component-input">{describe(c.input, risk)}</span>
              <span className="risk-bar" aria-hidden="true">
                <span className="risk-bar-fill" style={{ width: `${Math.min(100, c.normalized * 100)}%` }} />
              </span>
              <span className="risk-component-points">
                {c.contribution.toFixed(1)} pts <span className="muted">/ {max.toFixed(0)}</span>
              </span>
            </li>
          )
        })}
      </ul>
      <p className="muted risk-flags">
        L1 flagged: {risk.l1FlaggedMetrics?.length ? risk.l1FlaggedMetrics.join(', ') : 'none'}
        {' · '}L0 violated: {risk.l0ViolatedMetrics?.length ? risk.l0ViolatedMetrics.join(', ') : 'none'}
        {risk.calculatedAt ? ` · scored ${new Date(risk.calculatedAt).toLocaleTimeString()}` : ''}
      </p>
    </>
  )
}
