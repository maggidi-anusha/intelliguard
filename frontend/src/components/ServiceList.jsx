import { useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'

const SERVICE_TYPES = ['HOST', 'CONTAINER', 'MICROSERVICE', 'DATABASE']
const CRITICALITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

export function ServiceList({ services, loading, error, selectedServiceId, onSelect, onServiceCreated }) {
  const { auth, handleAuthError } = useAuth()
  const canWrite = auth.role === 'USER' || auth.role === 'ADMIN'

  const [showForm, setShowForm] = useState(false)
  const [name, setName] = useState('')
  const [type, setType] = useState(SERVICE_TYPES[2])
  const [criticality, setCriticality] = useState(CRITICALITIES[1])
  const [formError, setFormError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleCreate(e) {
    e.preventDefault()
    setFormError(null)
    setSubmitting(true)
    try {
      await api.createService({ name, type, criticality }, auth.token)
      setName('')
      setShowForm(false)
      onServiceCreated()
    } catch (err) {
      if (!handleAuthError(err)) setFormError(err.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="panel services-panel">
      <div className="panel-header">
        <h2>Services</h2>
        {canWrite && (
          <button type="button" onClick={() => setShowForm((v) => !v)}>
            {showForm ? 'Cancel' : 'Register service'}
          </button>
        )}
      </div>

      {showForm && (
        <form className="inline-form" onSubmit={handleCreate}>
          <input
            placeholder="Service name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {SERVICE_TYPES.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
          <select value={criticality} onChange={(e) => setCriticality(e.target.value)}>
            {CRITICALITIES.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
          <button type="submit" disabled={submitting}>
            {submitting ? 'Saving...' : 'Save'}
          </button>
          {formError && <p className="error-text" role="alert">{formError}</p>}
        </form>
      )}

      {loading && <p className="muted">Loading services...</p>}
      {error && <p className="error-text" role="alert">Failed to load services: {error}</p>}
      {!loading && !error && services.length === 0 && (
        <p className="muted">No services registered yet.</p>
      )}

      {!loading && !error && services.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Criticality</th>
            </tr>
          </thead>
          <tbody>
            {services.map((s) => (
              <tr
                key={s.id}
                className={s.id === selectedServiceId ? 'selected' : ''}
                onClick={() => onSelect(s.id)}
              >
                <td>{s.name}</td>
                <td>{s.type}</td>
                <td>
                  <span className={`badge badge-${s.criticality.toLowerCase()}`}>
                    {s.criticality}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
