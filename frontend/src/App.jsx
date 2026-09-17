import { AuthProvider, useAuth } from './AuthContext'
import { LoginForm } from './components/LoginForm'
import { Dashboard } from './components/Dashboard'
import './App.css'

function AppContent() {
  const { auth } = useAuth()
  return auth ? <Dashboard /> : <LoginForm />
}

function App() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  )
}

export default App
