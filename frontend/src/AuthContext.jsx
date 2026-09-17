import { createContext, useCallback, useContext, useState } from 'react'
import { api, ApiError } from './api'

const AuthContext = createContext(null)

const STORAGE_KEY = 'intelliguard.auth'

function loadStoredAuth() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function AuthProvider({ children }) {
  const [auth, setAuth] = useState(loadStoredAuth)

  const login = useCallback(async (username, password) => {
    const response = await api.login(username, password)
    const nextAuth = { token: response.accessToken, username: response.username, role: response.role }
    setAuth(nextAuth)
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(nextAuth))
    } catch {
      // localStorage unavailable (e.g. private browsing) - session still works for this tab
    }
    return nextAuth
  }, [])

  const logout = useCallback(() => {
    setAuth(null)
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      // ignore
    }
  }, [])

  // 401 means "not authenticated" (missing/invalid/expired token) - the session is gone,
  // so log out. 403 means "authenticated, but this role can't do that" - the token is
  // still perfectly valid, so stay logged in and let the caller show a permission error.
  const handleAuthError = useCallback((error) => {
    if (error instanceof ApiError && error.status === 401) {
      logout()
      return true
    }
    return false
  }, [logout])

  return (
    <AuthContext.Provider value={{ auth, login, logout, handleAuthError }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
