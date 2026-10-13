import { useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'

const MAX_QUESTION = 500
const SUGGESTIONS = [
  'Why is this the likely root cause?',
  'What should I check first?',
  'Which services are affected?',
  'What happened?',
]

// "Ask about this incident": POST /api/incidents/{id}/copilot/ask. The answer is retrieval-based
// (templated from this incident's data or quoted from a runbook), not free-form text. Answers are
// kept only in this page for the session; nothing is stored. Any role may ask.
export function CopilotPanel({ incidentId }) {
  const { auth, handleAuthError } = useAuth()
  const [question, setQuestion] = useState('')
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState(null)
  const [answers, setAnswers] = useState([])

  async function ask(text) {
    const q = text.trim()
    if (!q || asking) return
    setError(null)
    setAsking(true)
    try {
      const result = await api.askCopilot(auth.token, incidentId, q)
      // The answer is a snapshot of the incident at this moment; the panel shows when it was generated.
      setAnswers((prev) => [{ key: `${prev.length}-${q}`, question: q, askedAt: new Date(), ...result }, ...prev])
      setQuestion('')
    } catch (err) {
      if (!handleAuthError(err)) setError(err.message)
    } finally {
      setAsking(false)
    }
  }

  return (
    <div className="incident-section copilot-panel">
      <h3>Ask about this incident</h3>
      <p className="muted incident-subcaption">
        Retrieval-based answer built only from this incident's data and runbooks. It is not a free-form chat model
        and adds no facts of its own.
      </p>

      <div className="copilot-chips">
        {SUGGESTIONS.map((s) => (
          <button type="button" key={s} onClick={() => ask(s)} disabled={asking}>{s}</button>
        ))}
      </div>

      <form className="copilot-form" onSubmit={(e) => { e.preventDefault(); ask(question) }}>
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          maxLength={MAX_QUESTION}
          placeholder="Ask a question about this incident..."
        />
        <span className="muted copilot-counter">{question.length} / {MAX_QUESTION}</span>
        <button type="submit" disabled={asking || question.trim().length === 0}>
          {asking ? 'Asking...' : 'Ask'}
        </button>
      </form>
      {error && <p className="error-text" role="alert">Could not get an answer: {error}</p>}
      {asking && answers.length === 0 && <p className="muted">Looking through this incident's data and the runbooks...</p>}

      {answers.map((a) => <CopilotAnswer key={a.key} answer={a} />)}
    </div>
  )
}

function CopilotAnswer({ answer }) {
  const [open, setOpen] = useState(() => new Set())
  const toggle = (id) => setOpen((prev) => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })

  return (
    <div className={`copilot-answer${answer.refused ? ' copilot-refused' : ''}`}>
      <p className="copilot-question">
        <strong>Q:</strong> {answer.question}
        <span className="muted copilot-asof"> · as of {formatClock(answer.askedAt)}</span>
      </p>
      {answer.refused && <p className="copilot-refused-label">Not answered ({(answer.reason ?? 'refused').replace(/_/g, ' ')})</p>}
      <p className="copilot-text">{answer.answer}</p>
      {!answer.refused && (
        <p className="muted copilot-meta">
          Mode: {answer.mode} · intent: {(answer.intent ?? '').replace(/_/g, ' ')} · grounding check: {answer.grounded ? 'passed' : 'FAILED'}
        </p>
      )}
      {answer.citations?.length > 0 && (
        <ul className="copilot-citations">
          {answer.citations.map((c) => (
            <li key={`${c.type}-${c.id}`}>
              <button type="button" className="citation-toggle" onClick={() => toggle(c.id)}>
                {open.has(c.id) ? '▾' : '▸'} [{c.type === 'RUNBOOK' ? 'Runbook' : 'Incident data'}] {c.title}
                <span className="muted"> ({c.id})</span>
              </button>
              {open.has(c.id) && <pre className="citation-excerpt">{c.excerpt}</pre>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function formatClock(date) {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}
