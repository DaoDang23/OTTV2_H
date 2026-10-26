import React, { useState } from 'react';
import { api, csrfHeaders, DEFAULT_HEADERS, fetchWithTimeout } from '../../utils/api.js';

export default function SignIn({
  onSignedIn,
  onShowRegister,
  onEnterOffline,
  initialEmail = '',
  successMessage = '',
  isBackendOffline = false,
}) {
  const [email, setEmail] = useState(initialEmail);
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const setErrorMessage = setError;
  const [busy, setBusy] = useState(false);

  const OFFLINE_MESSAGE =
    'Không thể kết nối đến máy chủ Backend (Hệ thống đang ngoại tuyến). Bạn có thể bật máy chủ hoặc tiếp tục bằng chế độ Ngoại Tuyến bên dưới.';

  function filterErrorMessage(raw) {
    if (!raw || typeof raw !== 'string') {
      return OFFLINE_MESSAGE;
    }
    const trimmed = raw.trim();
    if (
      trimmed.startsWith('<!DOCTYPE') ||
      trimmed.startsWith('<html') ||
      trimmed.includes('<html') ||
      trimmed.includes('<!DOCTYPE') ||
      trimmed.includes('<div') ||
      trimmed.includes('ERR_NGROK') ||
      trimmed.includes('Whitelabel Error') ||
      trimmed.includes('Failed to fetch') ||
      trimmed.includes('NetworkError') ||
      trimmed.includes('Backend 8080')
    ) {
      return OFFLINE_MESSAGE;
    }
    return trimmed.slice(0, 150);
  }

  const cachedUser = (() => {
    try {
      const raw = localStorage.getItem('ottv2_last_active_user');
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  })();

  async function submit(event) {
    event.preventDefault();
    setErrorMessage('');
    setBusy(true);
    try {
      const csrfH = await csrfHeaders();
      const res = await fetchWithTimeout(
        '/api/auth/login',
        {
          method: 'POST',
          credentials: 'include',
          headers: {
            ...DEFAULT_HEADERS,
            ...csrfH,
          },
          body: JSON.stringify({ email, password }),
        },
        8000
      );

      if (!res || !res.ok) {
        let errorText = 'Đăng nhập thất bại. Vui lòng thử lại.';
        if (!res) {
          setErrorMessage(OFFLINE_MESSAGE);
          return;
        }

        try {
          const contentType = res.headers?.get('content-type') || '';
          if (contentType.includes('application/json')) {
            const data = await res.json();
            errorText = data.message || data.error || errorText;
          } else {
            const rawText = await res.text();
            if (
              rawText.includes('<!DOCTYPE') ||
              rawText.includes('<html') ||
              rawText.includes('<div') ||
              rawText.includes('ERR_NGROK') ||
              res.status === 502 ||
              res.status === 504
            ) {
              errorText = OFFLINE_MESSAGE;
            } else {
              errorText = rawText.slice(0, 100);
            }
          }
        } catch {
          try {
            const rawText = await res.text();
            if (
              rawText.includes('<!DOCTYPE') ||
              rawText.includes('<html') ||
              rawText.includes('<div') ||
              rawText.includes('ERR_NGROK') ||
              res.status === 502 ||
              res.status === 504
            ) {
              errorText = OFFLINE_MESSAGE;
            } else {
              errorText = rawText ? rawText.slice(0, 100) : OFFLINE_MESSAGE;
            }
          } catch {
            errorText = OFFLINE_MESSAGE;
          }
        }

        setErrorMessage(filterErrorMessage(errorText));
        return;
      }

      // Xử lý khi đăng nhập thành công (res.ok)
      try {
        const headerToken = res.headers?.get('x-auth-token') || res.headers?.get('authorization')?.replace(/^Bearer\s+/i, '');
        if (headerToken) {
          localStorage.setItem('ottv2_auth_token', headerToken);
        }
        const contentType = res.headers?.get('content-type') || '';
        if (contentType.includes('application/json')) {
          const u = await res.json();
          if (u?.token) {
            localStorage.setItem('ottv2_auth_token', u.token);
          }
          if (u && u.email) {
            localStorage.setItem('ottv2_last_active_user', JSON.stringify(u));
            localStorage.setItem(`ottv2_user_${u.email}`, JSON.stringify(u));
          }
        }
      } catch {}

      onSignedIn();
    } catch (failure) {
      setErrorMessage(filterErrorMessage(failure?.message));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="min-h-screen bg-zinc-950 flex items-center justify-center p-4">
      <div className="w-full max-w-md bg-zinc-900 border border-zinc-800 rounded-3xl p-8 shadow-2xl space-y-6">
        <div className="text-center space-y-2">
          <div className="w-12 h-12 rounded-2xl bg-emerald-600 text-white flex items-center justify-center text-2xl mx-auto shadow-lg shadow-emerald-950/60 font-black">
            ⚔️
          </div>
          <p className="text-[11px] font-bold text-emerald-400 uppercase tracking-widest font-mono">OTTV2 · BOT ARENA</p>
          <h1 className="text-2xl font-black text-white">Đăng Nhập</h1>
        </div>

        {isBackendOffline && (
          <div className="p-3 bg-amber-500/10 border border-amber-500/30 rounded-xl text-amber-300 text-xs text-center font-medium leading-relaxed">
            ⚠️ Máy chủ Backend (8080) hiện đang ngoại tuyến hoặc phản hồi chậm. Bạn có thể bật máy chủ hoặc tiếp tục bằng tài khoản ngoại tuyến đã lưu bên dưới.
          </div>
        )}

        {successMessage && (
          <div
            className={`p-3 rounded-xl text-xs text-center font-medium ${
              successMessage.includes('hết hạn') || successMessage.includes('Lỗi')
                ? 'bg-rose-500/10 border border-rose-500/30 text-rose-400'
                : 'bg-emerald-500/10 border border-emerald-500/30 text-emerald-400'
            }`}
          >
            {successMessage}
          </div>
        )}

        <form onSubmit={submit} className="space-y-4 text-xs">
          <div className="space-y-1.5">
            <label className="text-zinc-400 font-semibold">Email tài khoản</label>
            <input
              type="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              placeholder="nhap.email@sinhvien.edu.vn"
              className="w-full bg-zinc-950 border border-zinc-700 rounded-xl px-4 py-2.5 text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500"
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-zinc-400 font-semibold">Mật khẩu</label>
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              placeholder="••••••••••••"
              className="w-full bg-zinc-950 border border-zinc-700 rounded-xl px-4 py-2.5 text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500"
            />
          </div>

          {error && (
            <div className="p-3 bg-rose-500/10 border border-rose-500/30 rounded-xl text-rose-400 text-xs font-medium leading-relaxed text-center break-words">
              {error}
            </div>
          )}

          <button
            disabled={busy}
            className="w-full py-3 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-sm shadow-lg shadow-emerald-950/50 transition-all disabled:opacity-50"
          >
            {busy ? 'Đang đăng nhập…' : 'Đăng Nhập Ngay'}
          </button>
        </form>

        {cachedUser && (
          <div className="pt-1">
            <button
              type="button"
              onClick={() => onSignedIn()}
              className="w-full py-2.5 px-3 bg-zinc-800/90 hover:bg-zinc-750 border border-zinc-700 hover:border-emerald-500/50 rounded-xl text-zinc-300 hover:text-white font-medium text-xs transition flex items-center justify-center gap-2 shadow"
            >
              <span>💾</span>
              <span>
                Tiếp tục phiên offline: <strong className="text-emerald-400">{cachedUser.displayName || cachedUser.username || cachedUser.email}</strong>
              </span>
            </button>
          </div>
        )}

        <div className="pt-2 border-t border-zinc-800">
          <button
            type="button"
            onClick={onEnterOffline ? onEnterOffline : onSignedIn}
            className="w-full py-2.5 px-3 bg-zinc-800/80 hover:bg-zinc-700/80 border border-zinc-700 rounded-xl text-zinc-300 hover:text-white font-medium text-xs transition flex items-center justify-center gap-2"
          >
            <span>🎮</span>
            <span>Chơi ngay ở chế độ Ngoại Tuyến (Offline)</span>
          </button>
        </div>

        <p className="text-center text-xs text-zinc-400">
          Chưa có tài khoản?{' '}
          <button
            type="button"
            onClick={onShowRegister}
            className="text-emerald-400 font-bold hover:underline"
          >
            Đăng ký ngay
          </button>
        </p>
      </div>
    </main>
  );
}
