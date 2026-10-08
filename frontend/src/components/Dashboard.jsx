import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '../AuthContext'
import { api } from '../api'
import { ServiceList } from './ServiceList'
import { ServiceDetail } from './ServiceDetail'
import { SecurityEventsPanel } from './SecurityEventsPanel'
import { SummaryCards } from './SummaryCards'
import { LogsPage } from './LogsPage'

const POLL_INTERVAL_MS = 5000

export function Dashboard() {
  const { auth, logout, handleAuthError } = useAuth()

  const [activeTab, setActiveTab] = useState('overview')

  const [services, setServices] = useState([])
  const [servicesLoading, setServicesLoading] = useState(true)
  const [servicesError, setServicesError] = useState(null)
  const [selectedServiceId, setSelectedServiceId] = useState(null)

  const [securityEvents, setSecurityEvents] = useState([])
  const [securityEventsError, setSecurityEventsError] = useState(null)

  const [dashboardSummary, setDashboardSummary] = useState(null)
  const [dashboardSummaryLoading, setDashboardSummaryLoading] = useState(true)
  const [dashboardSummaryError, setDashboardSummaryError] = useState(null)

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

  const loadDashboardSummary = useCallback(async () => {
    try {
      const data = await api.getDashboardSummary(auth.token)
      setDashboardSummary(data)
      setDashboardSummaryError(null)
    } catch (err) {
      if (!handleAuthError(err)) setDashboardSummaryError(err.message)
    } finally {
      setDashboardSummaryLoading(false)
    }
  }, [auth.token, handleAuthError])

  useEffect(() => {
    loadServices()
    loadSecurityEvents()
    loadDashboardSummary()
    const interval = setInterval(() => {
      loadServices()
      loadSecurityEvents()
      loadDashboardSummary()
    }, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [loadServices, loadSecurityEvents, loadDashboardSummary])

  const selectedService = services.find((s) => s.id === selectedServiceId) ?? null

  // Single source of truth for per-service health: derived from the same summary response
  // that backs the aggregate cards, so the ServiceList badges can never disagree with them.
  const healthByServiceId = useMemo(() => {
    const map = {}
    for (const s of dashboardSummary?.services ?? []) {
      map[s.id] = s
    }
    return map
  }, [dashboardSummary])

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

      <nav className="dashboard-tabs">
        <button
          type="button"
          className={activeTab === 'overview' ? 'active' : ''}
          onClick={() => setActiveTab('overview')}
        >
          Overview
        </button>
        <button
          type="button"
          className={activeTab === 'logs' ? 'active' : ''}
          onClick={() => setActiveTab('logs')}
        >
          Logs
        </button>
      </nav>

      {activeTab === 'overview' && (
        <>
          <div className="dashboard-summary">
            <SummaryCards
              summary={dashboardSummary}
              loading={dashboardSummaryLoading}
              error={dashboardSummaryError}
            />
          </div>

          <main className="dashboard-grid">
            <ServiceList
              services={services}
              loading={servicesLoading}
              error={servicesError}
              selectedServiceId={selectedServiceId}
              onSelect={setSelectedServiceId}
              onServiceCreated={() => {
                loadServices()
                loadDashboardSummary()
              }}
              healthByServiceId={healthByServiceId}
            />

            <ServiceDetail service={selectedService} />

            <SecurityEventsPanel events={securityEvents} error={securityEventsError} />
          </main>
        </>
      )}

      {activeTab === 'logs' && <LogsPage services={services} />}
    </div>
  )
}
