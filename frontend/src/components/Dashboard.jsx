import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'
import { ServiceList } from './ServiceList'
import { ServiceDetail } from './ServiceDetail'
import { SecurityEventsPanel } from './SecurityEventsPanel'

const POLL_INTERVAL_MS = 5000

export function Dashboard() {
  const { auth, logout, handleAuthError } = useAuth()

  const [services, setServices] = useState([])
  const [servicesLoading, setServicesLoading] = useState(true)
  const [servicesError, setServicesError] = useState(null)
  const [selectedServiceId, setSelectedServiceId] = useState(null)

  const [securityEvents, setSecurityEvents] = useState([])
  const [securityEventsError, setSecurityEventsError] = useState(null)

  const loadServices = useCallback(async () => {
    try {
      const data = await api.getServices(auth.token)
      setServices(data)
      setServicesError(null)
    } catch (err) {
      if (!handleAuthError(err)) setServicesError(err.message)
    } finally {
      setServicesLoading(false)
    }
  }, [auth.token, handleAuthError])

  const loadSecurityEvents = useCallback(async () => {
    try {
      const data = await api.getSecurityEvents(auth.token)
      setSecurityEvents(data)
      setSecurityEventsError(null)
    } catch (err) {
      if (!handleAuthError(err)) setSecurityEventsError(err.message)
    }
  }, [auth.token, handleAuthError])

  useEffect(() => {
    loadServices()
    loadSecurityEvents()
    const interval = setInterval(() => {
      loadServices()
      loadSecurityEvents()
    }, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [loadServices, loadSecurityEvents])

  const selectedService = services.find((s) => s.id === selectedServiceId) ?? null

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <h1>IntelliGuard</h1>
        <div className="user-info">
          <span>{auth.username}</span>
          <span className="role-badge">{auth.role}</span>
          <button type="button" onClick={logout}>Log out</button>
        </div>
      </header>

      <main className="dashboard-grid">
        <ServiceList
          services={services}
          loading={servicesLoading}
          error={servicesError}
          selectedServiceId={selectedServiceId}
          onSelect={setSelectedServiceId}
          onServiceCreated={loadServices}
        />

        <ServiceDetail service={selectedService} />

        <SecurityEventsPanel events={securityEvents} error={securityEventsError} />
      </main>
    </div>
  )
}
