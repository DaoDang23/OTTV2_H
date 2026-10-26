/**
 * Room API utility for real-time PvP room creation and joining
 */

const DEFAULT_HEADERS = {
  'Content-Type': 'application/json',
  'ngrok-skip-browser-warning': 'true',
};

async function fetchWithTimeout(url, options = {}, timeoutMs = 6000) {
  const controller = new AbortController();
  const id = setTimeout(() => {
    try {
      controller.abort();
    } catch {}
  }, timeoutMs);

  try {
    const response = await fetch(url, {
      ...options,
      headers: {
        ...DEFAULT_HEADERS,
        ...(options?.headers || {}),
      },
      signal: options?.signal || controller.signal,
    });
    return response;
  } finally {
    clearTimeout(id);
  }
}

/**
 * Fetch CSRF token if enabled on Spring backend
 */
async function getCsrfHeaders() {
  try {
    const res = await fetchWithTimeout('/api/auth/csrf', {
      headers: { ...DEFAULT_HEADERS },
    }, 2000);
    if (!res || !res.ok) return {};
    const data = await res.json().catch(() => null);
    if (data && data.headerName && data.token) {
      return { [data.headerName]: data.token };
    }
  } catch {
    // ignore
  }
  return {};
}

/**
 * Create a new PvP room on backend
 * @param {string} hostName
 * @param {object|null} timeControl
 * @returns {Promise<{code: string, roomCode: string, isHost: boolean, status: string}>}
 */
export async function createRoomApi(hostName = 'Chủ Phòng', timeControl = null) {
  const csrf = await getCsrfHeaders();
  const response = await fetchWithTimeout('/api/rooms/create', {
    method: 'POST',
    headers: {
      ...DEFAULT_HEADERS,
      ...csrf,
    },
    body: JSON.stringify({ hostName, timeControl }),
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || 'Không thể tạo phòng lúc này. Vui lòng thử lại!');
  }

  const data = await response.json();
  return {
    code: data.code || data.roomCode,
    roomCode: data.code || data.roomCode,
    isHost: true,
    status: data.status || 'WAITING',
    timeControl: data.timeControl || timeControl,
    playerToken: data.playerToken,
  };
}

/**
 * Join an existing PvP room by 6-digit code
 * @param {string} code
 * @param {string} guestName
 * @returns {Promise<{code: string, roomCode: string, isHost: boolean, status: string, timeControl: any, playerToken?: string}>}
 */
export async function joinRoomApi(code, guestName = 'Khách') {
  const cleanCode = (code || '').trim().toUpperCase();
  if (!cleanCode) {
    throw new Error('Vui lòng nhập mã phòng!');
  }

  const csrf = await getCsrfHeaders();
  const response = await fetchWithTimeout(`/api/rooms/join/${encodeURIComponent(cleanCode)}`, {
    method: 'POST',
    headers: {
      ...DEFAULT_HEADERS,
      ...csrf,
    },
    body: JSON.stringify({ guestName }),
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (response.status === 404 || body.error === 'ROOM_NOT_FOUND') {
      throw new Error('Mã phòng không tồn tại hoặc đã đầy!');
    }
    if (response.status === 400 || body.error === 'ROOM_FULL') {
      throw new Error('Mã phòng không tồn tại hoặc đã đầy!');
    }
    throw new Error(body.message || 'Mã phòng không tồn tại hoặc đã đầy!');
  }

  const data = await response.json();
  return {
    code: data.code || data.roomCode || cleanCode,
    roomCode: data.code || data.roomCode || cleanCode,
    isHost: false,
    status: data.status || 'PLAYING',
    hostName: data.hostName,
    timeControl: data.timeControl,
    playerToken: data.playerToken,
  };
}

/**
 * Get room info by code
 * @param {string} code
 * @param {{userId?: number|string, username?: string}} [userParams]
 */
export async function getRoomApi(code, userParams = {}) {
  const cleanCode = (code || '').trim().toUpperCase();
  const token = typeof window !== 'undefined'
    ? (localStorage.getItem('ottv2_auth_token') || localStorage.getItem('ottv2_jwt_token'))
    : '';

  const headers = { ...DEFAULT_HEADERS };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
    headers['X-Auth-Token'] = token;
  }

  const query = new URLSearchParams();
  if (userParams?.userId) query.set('userId', String(userParams.userId));
  if (userParams?.username) query.set('username', String(userParams.username));
  const queryString = query.toString() ? `?${query.toString()}` : '';

  const response = await fetchWithTimeout(`/api/rooms/${encodeURIComponent(cleanCode)}${queryString}`, {
    method: 'GET',
    headers,
  });
  if (!response.ok) {
    return null;
  }
  return response.json().catch(() => null);
}
