import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

// Renders the history for a single metric type. `data` must already be filtered to one
// metricType and sorted chronologically (oldest first) by the caller - this component does
// no fetching or transformation of its own.
// domain (optional): fixed Y-axis range, e.g. [0, 100] for the risk score; default auto-scales.
export function MetricChart({ metricType, unit = '', data, domain }) {
  if (!data || data.length === 0) {
    return (
      <div className="metric-chart metric-chart-empty">
        <span className="stat-label">{metricType}</span>
        <p className="muted">Not enough history yet.</p>
      </div>
    )
  }

  return (
    <div className="metric-chart">
      <span className="stat-label">{metricType}</span>
      <ResponsiveContainer width="100%" height={140}>
        <LineChart data={data} margin={{ top: 8, right: 12, left: -12, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis dataKey="time" tick={{ fontSize: 11, fill: 'var(--text)' }} minTickGap={24} />
          <YAxis tick={{ fontSize: 11, fill: 'var(--text)' }} width={48} {...(domain ? { domain } : {})} />
          <Tooltip
            formatter={(value) => [`${value}${unit}`, metricType]}
            contentStyle={{ background: 'var(--bg)', border: '1px solid var(--border)', fontSize: 12 }}
          />
          <Line
            type="monotone"
            dataKey="value"
            stroke="var(--accent)"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
