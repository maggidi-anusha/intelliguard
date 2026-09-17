export function SecurityEventsPanel({ events, error }) {
  return (
    <section className="panel security-panel">
      <h2>Recent security activity</h2>
      {error && (
        <p className="error-text" role="alert">Failed to load security events: {error}</p>
      )}
      {!error && events.length === 0 && <p className="muted">No security events recorded yet.</p>}
      {events.length > 0 && (
        <ul className="event-list">
          {events.slice(0, 15).map((e) => (
            <li key={e.id}>
              <span className="event-type">{e.eventType}</span>
              <span className={`badge badge-${e.severity.toLowerCase()}`}>{e.severity}</span>
              <span className="event-time">{new Date(e.timestamp).toLocaleTimeString()}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
