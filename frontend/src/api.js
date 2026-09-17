const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

async function request(path, { method = 'GET', body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`

  let res
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    })
  } catch {
    throw new ApiError(0, 'Could not reach the server. Check that backend-core is running.')
  }

  if (!res.ok) {
    // Spring Security's default access-denied response for a wrong-role request carries no
    // "message" field, just the generic reason phrase ("Forbidden") - a fixed, clear message
    // is more useful here than surfacing that verbatim.
    if (res.status === 403) {
      throw new ApiError(403, "You don't have permission to do that.")
    }

    let message = `Request failed (${res.status})`
    try {
      const data = await res.json()
      // "message" is the specific reason (e.g. "A service named 'x' already exists");
      // "error" is just the generic HTTP reason phrase (e.g. "Conflict") - prefer the former.
      message = data.message || data.error || message
    } catch {
      // response body wasn't JSON - keep the generic message
    }
    throw new ApiError(res.status, message)
  }

  if (res.status === 204) return null
  return res.json()
}

export const api = {
  login: (username, password) =>
    request('/api/auth/login', { method: 'POST', body: { username, password } }),

  getServices: (token) => request('/api/services', { token }),

  createService: (payload, token) =>
    request('/api/services', { method: 'POST', body: payload, token }),

  getServiceMetrics: (serviceId, token) =>
    request(`/api/services/${serviceId}/metrics`, { token }),

  getServiceLogs: (serviceId, token) =>
    request(`/api/services/${serviceId}/logs`, { token }),

  getSecurityEvents: (token) => request('/api/security-events', { token }),
}
