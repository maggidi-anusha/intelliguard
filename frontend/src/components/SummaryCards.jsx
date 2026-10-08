// Aggregate counts for the Overview tab. Data comes from a single GET
// /api/dashboard/summary call owned by Dashboard.jsx (not fetched here) so these totals and
// the per-service health badges in ServiceList can never disagree with each other.
export function SummaryCards({ summary, loading, error }) {
  if (loading && !summary) {
    return (
      <section className="panel summary-panel">
        <p className="muted">Loading dashboard summary...</p>
      </section>
    )
  }

  if (error && !summary) {
    return (
      <section className="panel summary-panel">
        <p className="error-text" role="alert">Failed to load dashboard summary: {error}</p>
      </section>
    )
  }

  if (!summary) {
    return (
      <section className="panel summary-panel">
        <p className="muted">No dashboard summary available yet.</p>
      </section>
    )
  }

  const cards = [
    { label: 'Total services', value: summary.totalServices },
    { label: 'Healthy', value: summary.healthyServices, tone: 'healthy' },
    { label: 'Degraded', value: summary.degradedServices, tone: 'degraded' },
    { label: 'Unknown', value: summary.unknownServices, tone: 'unknown' },
    { label: `Logs (last ${summary.windowMinutes}m)`, value: summary.recentLogCount },
    { label: `Security events (last ${summary.windowMinutes}m)`, value: summary.recentSecurityEventCount },
  ]

  return (
    <section className="panel summary-panel">
      <div className="panel-header">
        <h2>Overview</h2>
      </div>
      {error && (
        <p className="error-text" role="alert">Latest refresh failed: {error} (showing last known values)</p>
      )}
      <div className="summary-cards">
        {cards.map((card) => (
          <div className={`summary-card${card.tone ? ` summary-card-${card.tone}` : ''}`} key={card.label}>
            <span className="summary-card-value">{card.value}</span>
            <span className="summary-card-label">{card.label}</span>
          </div>
        ))}
      </div>
    </section>
  )
}
