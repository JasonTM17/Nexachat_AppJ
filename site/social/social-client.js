const API = '/api/social/v1';

export class SocialApiError extends Error {
  constructor(status, payload, message) {
    super(message || `Social API request failed with status ${status}`);
    this.name = 'SocialApiError';
    this.status = status;
    this.payload = payload || null;
  }
}

export class SocialSessionError extends Error {
  constructor(message = 'The social session is no longer active') {
    super(message);
    this.name = 'SocialSessionError';
    this.status = 401;
  }
}

function isSafeRelativePath(path) {
  return typeof path === 'string'
    && path.startsWith('/')
    && !path.startsWith('//')
    && !path.includes('..')
    && !path.includes(String.fromCharCode(92))
    && !/\s/.test(path);
}

function validApiPath(path) {
  return isSafeRelativePath(path);
}

function validFilePath(path) {
  return isSafeRelativePath(path) && path.startsWith('/api/v1/files/');
}

async function readPayload(response, asBlob) {
  if (response.status === 204) {
    return null;
  }
  if (asBlob) {
    return response.blob();
  }
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function randomIdempotencyKey() {
  if (!globalThis.crypto || typeof globalThis.crypto.randomUUID !== 'function') {
    throw new Error('crypto.randomUUID is required for refresh idempotency');
  }
  return globalThis.crypto.randomUUID();
}

function apiError(response, payload) {
  const message = payload && payload.error && typeof payload.error.message === 'string'
    ? payload.error.message
    : undefined;
  return new SocialApiError(response.status, payload, message);
}

/**
 * Anonymous bootstrap read of the public feature configuration. Not part of
 * the session client on purpose: it must work before login, must never carry
 * credentials, and a malformed or unreachable answer must fail closed — the
 * caller treats any throw as "unavailable".
 */
export async function fetchSocialConfig(fetchImpl = globalThis.fetch) {
  if (typeof fetchImpl !== 'function') {
    throw new TypeError('fetchSocialConfig requires a fetch implementation');
  }
  const response = await fetchImpl(`${API}/config`, {
    method: 'GET',
    credentials: 'omit',
    cache: 'no-store',
    redirect: 'error',
  });
  if (!response.ok) {
    throw new SocialApiError(response.status, null, 'Config request failed');
  }
  const payload = await readPayload(response, false);
  if (!payload || typeof payload.webEnabled !== 'boolean'
      || typeof payload.videoUploadsEnabled !== 'boolean') {
    throw new SocialApiError(response.status, payload, 'Config response was malformed');
  }
  const announcement = payload.announcement == null ? null : payload.announcement;
  if (announcement !== null) {
    const fields = [announcement.titleVi, announcement.bodyVi, announcement.titleEn, announcement.bodyEn];
    if (fields.some((value) => typeof value !== 'string' || !value.trim())) {
      throw new SocialApiError(response.status, payload, 'Config announcement was malformed');
    }
  }
  return {
    webEnabled: payload.webEnabled,
    videoUploadsEnabled: payload.videoUploadsEnabled,
    announcement,
  };
}

export function createSocialClient(fetchImpl = globalThis.fetch) {
  if (typeof fetchImpl !== 'function') {
    throw new TypeError('createSocialClient requires a fetch implementation');
  }

  let session = null;
  let generation = 0;
  let refreshInFlight = null;

  function clearSession() {
    generation += 1;
    session = null;
    refreshInFlight = null;
  }

  function clearMatching(activeGeneration) {
    if (generation === activeGeneration) {
      clearSession();
    }
  }

  function storeSession(payload) {
    const expiresIn = Number(payload && payload.accessTokenExpiresIn);
    session = {
      accessToken: payload.accessToken,
      refreshToken: payload.refreshToken,
      expiresAt: Date.now() + (Number.isFinite(expiresIn) ? expiresIn * 1000 : 0),
      user: payload.user || null,
    };
  }

  function assertFresh(activeGeneration) {
    if (!session || generation !== activeGeneration) {
      throw new SocialSessionError();
    }
  }

  async function parseResponse(response, asBlob) {
    const payload = await readPayload(response, asBlob);
    if (!response.ok) {
      throw apiError(response, payload);
    }
    return payload;
  }

  async function login(identifier, password) {
    const loginGeneration = generation + 1;
    generation = loginGeneration;
    session = null;
    refreshInFlight = null;
    const response = await fetchImpl(`${API}/session/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ identifier, password }),
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
    });
    const payload = await readPayload(response, false);
    if (generation !== loginGeneration) {
      throw new SocialSessionError();
    }
    if (!response.ok) {
      throw apiError(response, payload);
    }
    if (!payload || !payload.accessToken || !payload.refreshToken) {
      throw new SocialApiError(response.status, payload, 'Login response did not include session tokens');
    }
    storeSession(payload);
    return { user: payload.user || null };
  }

  async function refreshAccess(capturedAccessToken, activeGeneration) {
    assertFresh(activeGeneration);
    if (session.accessToken !== capturedAccessToken) {
      return session.accessToken;
    }
    if (!refreshInFlight) {
      const capturedRefreshToken = session.refreshToken;
      const refreshGeneration = generation;
      refreshInFlight = (async () => {
        try {
          let payload;
          try {
            const response = await fetchImpl(`${API}/session/refresh`, {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': randomIdempotencyKey(),
              },
              body: JSON.stringify({ refreshToken: capturedRefreshToken }),
              credentials: 'omit',
              cache: 'no-store',
              redirect: 'error',
            });
            payload = await readPayload(response, false);
            if (!response.ok) {
              clearMatching(refreshGeneration);
              throw new SocialSessionError();
            }
          } catch (error) {
            if (generation === refreshGeneration && !(error instanceof SocialSessionError)) {
              clearMatching(refreshGeneration);
            }
            if (error instanceof SocialSessionError) {
              throw error;
            }
            throw new SocialSessionError();
          }
          if (!payload || !payload.accessToken || !payload.refreshToken) {
            clearMatching(refreshGeneration);
            throw new SocialSessionError('Refresh response did not include session tokens');
          }
          if (generation !== refreshGeneration || !session) {
            throw new SocialSessionError();
          }
          storeSession(payload);
          return payload.accessToken;
        } finally {
          if (generation === refreshGeneration) {
            refreshInFlight = null;
          }
        }
      })();
    }
    return refreshInFlight;
  }

  async function authorizedRequest(fullUrl, { method = 'GET', body, signal, asBlob = false } = {}) {
    const activeGeneration = generation;
    assertFresh(activeGeneration);

    let accessToken = session.accessToken;
    if (session.expiresAt <= Date.now()) {
      accessToken = await refreshAccess(accessToken, activeGeneration);
    }

    const send = (token) => {
      const headers = { Authorization: `Bearer ${token}` };
      const init = {
        method,
        headers,
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
      };
      if (signal) {
        init.signal = signal;
      }
      if (body instanceof FormData) {
        // The browser mints the multipart boundary; setting Content-Type
        // manually would drop it. The same FormData object is reused verbatim
        // when a single 401 replay resends the request.
        init.body = body;
      } else if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
        init.body = JSON.stringify(body);
      }
      return fetchImpl(fullUrl, init);
    };

    let response = await send(accessToken);
    if (response.status === 401) {
      const renewedToken = await refreshAccess(accessToken, activeGeneration);
      assertFresh(activeGeneration);
      response = await send(renewedToken);
      if (response.status === 401) {
        clearMatching(activeGeneration);
        throw new SocialSessionError();
      }
    }
    const payload = await parseResponse(response, asBlob);
    assertFresh(activeGeneration);
    return payload;
  }

  async function request(path, options = {}) {
    if (!validApiPath(path)) {
      throw new TypeError('Social API paths must be relative same-origin paths beneath the API prefix');
    }
    return authorizedRequest(`${API}${path}`, options);
  }

  async function requestFile(path, { signal } = {}) {
    if (!validFilePath(path)) {
      throw new TypeError('File paths must be relative same-origin /api/v1/files/ paths');
    }
    return authorizedRequest(path, { method: 'GET', signal, asBlob: true });
  }

  async function logout() {
    if (!session) {
      return { local: true, revoked: false, status: 0 };
    }
    const captured = {
      accessToken: session.accessToken,
      refreshToken: session.refreshToken,
    };
    clearSession();
    try {
      const response = await fetchImpl(`${API}/session/logout`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${captured.accessToken}`,
        },
        body: JSON.stringify({ refreshToken: captured.refreshToken }),
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
      });
      return { local: true, revoked: response.status === 204, status: response.status };
    } catch {
      return { local: true, revoked: false, status: 0, networkError: true };
    }
  }

  return { login, logout, request, requestFile };
}
