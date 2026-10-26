import React, { useState, useEffect } from 'react';
import { buildApiUrl } from '../utils/api.js';

export default function MatchmakingModal({
  isOpen,
  onClose,
  user,
  pvpElo = 1200,
  myBots = [],
  onToast,
  onMatchFound,
}) {
  const [mode, setMode] = useState('PVP'); // 'PVP' | 'BOT'
  const [selectedBotId, setSelectedBotId] = useState('');
  const [selectedPvpTcKey, setSelectedPvpTcKey] = useState('blitz_3_2');
  const [isSearching, setIsSearching] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const rankedBots = (myBots || []).filter((bot) => bot?.ladderEnabled);

  const PVP_TC_OPTIONS = {
    blitz_3_2: { label: 'Chớp 3+2', desc: '03:00 + 2s', icon: '⚡' },
    rapid_5_3: { label: 'Nhanh 5+3', desc: '05:00 + 3s', icon: '🔥' },
    bullet_1_1: { label: 'Siêu Chớp 1+1', desc: '01:00 + 1s', icon: '🚀' },
    auto: { label: 'Tự Động', desc: 'Ghép bất kỳ ai', icon: '🎲' },
  };

  // Ranked queue only accepts bots explicitly enabled for the ladder.
  useEffect(() => {
    if (rankedBots.length > 0) {
      const selectedIsEligible = rankedBots.some((bot) => String(bot.id) === String(selectedBotId));
      if (!selectedIsEligible) setSelectedBotId(String(rankedBots[0].id));
    } else {
      setSelectedBotId('');
    }
  }, [myBots, selectedBotId]);

  // Bộ đếm thời gian tìm kiếm
  useEffect(() => {
    let timer = null;
    if (isSearching) {
      setElapsedSeconds(0);
      timer = setInterval(() => {
        setElapsedSeconds((prev) => prev + 1);
      }, 1000);
    } else {
      setElapsedSeconds(0);
    }
    return () => {
      if (timer) clearInterval(timer);
    };
  }, [isSearching]);

  // Lắng nghe sự kiện MATCH_FOUND từ WebSocket STOMP
  useEffect(() => {
    const handleFound = (e) => {
      setIsSearching(false);
      if (onMatchFound) {
        onMatchFound(e.detail);
      }
      onClose();
    };
    window.addEventListener('ottv2_match_found', handleFound);
    return () => {
      window.removeEventListener('ottv2_match_found', handleFound);
    };
  }, [onMatchFound, onClose]);

  // Heartbeat ping định kỳ mỗi 3 giây khi đang tìm trận để ngăn "bóng ma" hàng đợi
  useEffect(() => {
    if (!isSearching) return undefined;

    let isMounted = true;
    let inFlight = false;

    const sendHeartbeat = async () => {
      if (!isMounted || inFlight) return;
      inFlight = true;
      try {
        const token = localStorage.getItem('ottv2_auth_token') || localStorage.getItem('ottv2_jwt_token');
        const headers = {
          'Content-Type': 'application/json',
          'ngrok-skip-browser-warning': 'true',
        };
        if (token) {
          headers['Authorization'] = `Bearer ${token}`;
          headers['X-Auth-Token'] = token;
        }

        const res = await fetch(buildApiUrl('/api/matchmaking/heartbeat'), {
          method: 'POST',
          credentials: 'include',
          headers,
          body: JSON.stringify({
            userId: user?.id,
            username: user?.studentCode || user?.username || user?.email,
            mode,
          }),
        });

        if (!isMounted) return;

        if (res.ok) {
          const data = await res.json();
          // Nếu đã ghép thành công đối thủ (fallback STOMP)
          if (data?.matchFound) {
            setIsSearching(false);
            if (onMatchFound) {
              onMatchFound(data.matchFound);
            }
            onClose();
            return;
          }
          // Nếu bị loại khỏi hàng đợi (ví dụ TTL quá 180s timeout)
          if (data && data.inQueue === false && !data.matchFound) {
            setIsSearching(false);
            if (onToast) {
              onToast({ type: 'INFO', text: '⏱️ Đã hết thời gian chờ ghép trận (quá 3 phút). Vui lòng thử lại!' });
            }
          }
        }
      } catch (_) {
        // Lỗi mạng hoặc server không khả dụng, không spam log console
      } finally {
        inFlight = false;
      }
    };

    const intervalId = window.setInterval(sendHeartbeat, 3000);

    // Bắt sự kiện thoát tab/mất kết nối đột ngột để giải phóng hàng đợi tức thì
    const handleBeforeUnload = () => {
      try {
        const leavePayload = JSON.stringify({ mode, userId: user?.id });
        if (navigator.sendBeacon) {
          const blob = new Blob([leavePayload], { type: 'application/json' });
          navigator.sendBeacon(buildApiUrl('/api/matchmaking/leave'), blob);
        } else {
          fetch(buildApiUrl('/api/matchmaking/leave'), {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: leavePayload,
            keepalive: true,
          }).catch(() => {});
        }
      } catch (_) {}
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    window.addEventListener('pagehide', handleBeforeUnload);

    return () => {
      isMounted = false;
      window.clearInterval(intervalId);
      window.removeEventListener('beforeunload', handleBeforeUnload);
      window.removeEventListener('pagehide', handleBeforeUnload);
    };
  }, [isSearching, mode, user?.id, onMatchFound, onClose, onToast]);

  if (!isOpen) return null;

  const handleStartSearch = async () => {
    if (mode === 'BOT' && rankedBots.length === 0) {
      if (onToast) onToast({ type: 'LOSE', text: '⚠️ Chưa có bot nào bật “Đang Đấu Ranked”. Hãy bật chế độ này trong Bot Của Tôi trước!' });
      return;
    }

    setIsSearching(true);
    try {
      const csrfRes = await fetch(buildApiUrl('/api/auth/csrf'), { credentials: 'include' }).catch(() => null);
      let headers = {
        'Content-Type': 'application/json',
        'ngrok-skip-browser-warning': 'true',
      };
      if (csrfRes && csrfRes.ok) {
        const csrfData = await csrfRes.json();
        if (csrfData?.headerName && csrfData?.token) {
          headers[csrfData.headerName] = csrfData.token;
        }
      }

      const token = localStorage.getItem('ottv2_auth_token') || localStorage.getItem('ottv2_jwt_token');
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
        headers['X-Auth-Token'] = token;
      }

      const res = await fetch(buildApiUrl('/api/matchmaking/join'), {
        method: 'POST',
        credentials: 'include',
        headers,
        body: JSON.stringify({
          mode,
          timeControlKey: mode === 'PVP' ? selectedPvpTcKey : 'blitz_3_2',
          botId: mode === 'BOT' ? (selectedBotId ? Number(selectedBotId) : null) : null,
          userId: user?.id,
          username: user?.studentCode || user?.username || user?.email,
          studentCode: user?.studentCode,
          email: user?.email,
          displayName: user?.displayName,
          pvpElo,
        }),
      });

      if (res.ok) {
        const data = await res.json();
        if (data.status === 'MATCHED') {
          setIsSearching(false);
          if (onMatchFound) {
            onMatchFound(data);
          }
          onClose();
          return;
        }
        // status === 'QUEUED' -> Tiếp tục hiển thị vòng xoay tìm trận chờ WebSocket STOMP
      } else {
        const err = await res.json().catch(() => null);
        const msg = err?.message || 'Không thể tham gia hàng đợi ghép trận!';
        if (onToast) onToast({ type: 'LOSE', text: `⚠️ ${msg}` });
        setIsSearching(false);
      }
    } catch (err) {
      console.error('Lỗi khi tham gia hàng đợi:', err);
      if (onToast) onToast({ type: 'LOSE', text: '⚠️ Lỗi kết nối máy chủ ghép trận!' });
      setIsSearching(false);
    }
  };

  const handleCancelSearch = async () => {
    setIsSearching(false);
    try {
      const csrfRes = await fetch(buildApiUrl('/api/auth/csrf'), { credentials: 'include' }).catch(() => null);
      let headers = {
        'Content-Type': 'application/json',
        'ngrok-skip-browser-warning': 'true',
      };
      if (csrfRes && csrfRes.ok) {
        const csrfData = await csrfRes.json();
        if (csrfData?.headerName && csrfData?.token) {
          headers[csrfData.headerName] = csrfData.token;
        }
      }

      const token = localStorage.getItem('ottv2_auth_token') || localStorage.getItem('ottv2_jwt_token');
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
        headers['X-Auth-Token'] = token;
      }

      await fetch(buildApiUrl('/api/matchmaking/leave'), {
        method: 'POST',
        credentials: 'include',
        headers,
        body: JSON.stringify({
          mode,
          userId: user?.id,
          username: user?.studentCode || user?.username || user?.email,
        }),
      });
      if (onToast) onToast({ type: 'INFO', text: '⚪ Đã hủy tìm kiếm trận đấu.' });
    } catch (e) {
      console.warn('Lỗi khi hủy hàng đợi:', e);
    }
  };

  const selectedBot = myBots.find((b) => String(b.id) === String(selectedBotId));

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fadeIn select-none">
      <div className="relative w-full max-w-xl bg-zinc-900 border border-zinc-750 rounded-3xl shadow-2xl p-6 sm:p-8 space-y-6 overflow-hidden">
        {/* Nền hiệu ứng hào quang */}
        <div className="absolute -top-24 -left-24 w-64 h-64 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute -bottom-24 -right-24 w-64 h-64 bg-blue-500/10 rounded-full blur-3xl pointer-events-none" />

        {/* Header Modal */}
        <div className="flex items-center justify-between pb-4 border-b border-zinc-800">
          <div className="flex items-center gap-3">
            <span className="text-2xl p-2 bg-zinc-800 rounded-2xl border border-zinc-700">🔍</span>
            <div>
              <h2 className="text-lg font-black text-white tracking-wide">Tìm Trận Đấu Xếp Hạng</h2>
              <p className="text-xs text-zinc-400">Chọn chế độ thi đấu chính thức (Ranked Match)</p>
            </div>
          </div>
          {!isSearching && (
            <button
              type="button"
              onClick={onClose}
              className="p-2 text-zinc-400 hover:text-white rounded-xl hover:bg-zinc-800 transition-colors"
              title="Đóng"
            >
              ✕
            </button>
          )}
        </div>

        {/* Nội dung khi Đang tìm kiếm đối thủ */}
        {isSearching ? (
          <div className="py-8 text-center space-y-6 animate-fadeIn">
            <div className="relative w-28 h-28 mx-auto flex items-center justify-center">
              {/* Vòng sóng radar quét */}
              <div className="absolute inset-0 rounded-full border-2 border-emerald-500/40 animate-ping opacity-60" />
              <div className="absolute inset-2 rounded-full border-2 border-emerald-400/60 animate-pulse" />
              <div className="w-16 h-16 rounded-full bg-gradient-to-tr from-emerald-600 to-teal-500 flex items-center justify-center text-3xl shadow-xl shadow-emerald-950/60 font-black text-white">
                {mode === 'PVP' ? '🎮' : '🤖'}
              </div>
            </div>

            <div className="space-y-2">
              <h3 className="text-base font-extrabold text-white animate-pulse">
                Đang tìm đối thủ phù hợp...
              </h3>
              <p className="text-xs text-zinc-400">
                {mode === 'PVP' ? (
                  <span>
                    Chế độ: <strong className="text-blue-300">PvP Xếp Hạng</strong> ({pvpElo} ELO) · {PVP_TC_OPTIONS[selectedPvpTcKey]?.icon} {PVP_TC_OPTIONS[selectedPvpTcKey]?.label}
                  </span>
                ) : (
                  <span>
                    Bot: <strong className="text-emerald-300">"{selectedBot?.name || 'Bot của bạn'}"</strong> ({selectedBot?.elo ?? 1200} ELO)
                  </span>
                )}
              </p>
              <div className="inline-block px-3 py-1 bg-zinc-800/80 rounded-full border border-zinc-700 text-xs font-mono font-bold text-amber-300 mt-2">
                ⏱️ Thời gian chờ: {elapsedSeconds}s
              </div>
              <p className="text-[11px] text-zinc-500 max-w-sm mx-auto pt-1">
                Hệ thống đang quét người chơi có chênh lệch ELO gần nhất (khoảng mở rộng dần theo thời gian).
              </p>
            </div>

            <button
              type="button"
              onClick={handleCancelSearch}
              className="px-6 py-2.5 rounded-xl bg-zinc-800 hover:bg-rose-950/60 text-zinc-300 hover:text-rose-300 border border-zinc-700 hover:border-rose-700/60 font-bold text-xs transition-all shadow-lg"
            >
              ✕ Hủy Tìm Kiếm
            </button>
          </div>
        ) : (
          /* Nội dung khi Chọn Chế Độ */
          <div className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
              {/* Lựa chọn 1: Đấu PvP */}
              <div
                onClick={() => setMode('PVP')}
                className={`p-5 rounded-2xl border-2 transition-all cursor-pointer relative flex flex-col justify-between space-y-3 ${
                  mode === 'PVP'
                    ? 'bg-blue-950/40 border-blue-500 shadow-xl shadow-blue-950/50 ring-2 ring-blue-500/20'
                    : 'bg-zinc-850/80 border-zinc-750 hover:border-zinc-650 opacity-80 hover:opacity-100'
                }`}
              >
                <div className="flex items-center justify-between">
                  <span className="text-3xl p-2 bg-blue-900/40 rounded-xl border border-blue-500/30">🎮</span>
                  <div className={`w-5 h-5 rounded-full border-2 flex items-center justify-center ${
                    mode === 'PVP' ? 'border-blue-400 bg-blue-500' : 'border-zinc-600'
                  }`}>
                    {mode === 'PVP' && <span className="w-2 h-2 rounded-full bg-white" />}
                  </div>
                </div>
                <div>
                  <h3 className="text-sm font-black text-white">Đấu PvP (Tự Cầm Quân)</h3>
                  <p className="text-[11px] text-zinc-400 mt-1 leading-relaxed">
                    Đấu trí 1 vs 1 trực tiếp trên bàn cờ 9x9 với đồng môn theo thể thức Fischer Clock.
                  </p>
                </div>
                <div className="pt-2 border-t border-zinc-800/80 flex items-center justify-between">
                  <span className="text-[11px] text-zinc-400">PvP Rating:</span>
                  <span className="font-mono font-bold text-blue-300 text-sm bg-blue-900/50 px-2 py-0.5 rounded-lg border border-blue-500/30">
                    {pvpElo} ELO
                  </span>
                </div>
              </div>

              {/* Lựa chọn 2: Đấu Bot */}
              <div
                onClick={() => setMode('BOT')}
                className={`p-5 rounded-2xl border-2 transition-all cursor-pointer relative flex flex-col justify-between space-y-3 ${
                  mode === 'BOT'
                    ? 'bg-emerald-950/40 border-emerald-500 shadow-xl shadow-emerald-950/50 ring-2 ring-emerald-500/20'
                    : 'bg-zinc-850/80 border-zinc-750 hover:border-zinc-650 opacity-80 hover:opacity-100'
                }`}
              >
                <div className="flex items-center justify-between">
                  <span className="text-3xl p-2 bg-emerald-900/40 rounded-xl border border-emerald-500/30">🤖</span>
                  <div className={`w-5 h-5 rounded-full border-2 flex items-center justify-center ${
                    mode === 'BOT' ? 'border-emerald-400 bg-emerald-500' : 'border-zinc-600'
                  }`}>
                    {mode === 'BOT' && <span className="w-2 h-2 rounded-full bg-white" />}
                  </div>
                </div>
                <div>
                  <h3 className="text-sm font-black text-white">Đấu Bot (Bot Tự Đánh)</h3>
                  <p className="text-[11px] text-zinc-400 mt-1 leading-relaxed">
                    Cử thuật toán đại diện vào Đấu trường Ranked Bot tính điểm Per-Bot độc lập.
                  </p>
                </div>
                <div className="pt-2 border-t border-zinc-800/80 flex items-center justify-between">
                  <span className="text-[11px] text-zinc-400">Số bot sẵn sàng:</span>
                  <span className="font-mono font-bold text-emerald-300 text-sm bg-emerald-900/50 px-2 py-0.5 rounded-lg border border-emerald-500/30">
                    {myBots.length} Bot
                  </span>
                </div>
              </div>
            </div>

            {/* Lựa Chọn Thể Thức Thời Gian Fischer Clock khi chọn Mode PVP */}
            {mode === 'PVP' && (
              <div className="p-4 bg-zinc-950/80 border border-zinc-800 rounded-2xl space-y-2 animate-fadeIn">
                <label className="text-xs font-bold text-blue-400 flex items-center justify-between">
                  <span>⏱️ Thể Thức Thời Gian (Fischer Clock):</span>
                  <span className="text-[10px] font-mono text-zinc-500">Chuẩn FIDE</span>
                </label>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                  {Object.entries(PVP_TC_OPTIONS).map(([key, tc]) => {
                    const isSelected = selectedPvpTcKey === key;
                    return (
                      <button
                        key={key}
                        type="button"
                        onClick={() => setSelectedPvpTcKey(key)}
                        className={`p-2.5 rounded-xl border text-left transition-all ${
                          isSelected
                            ? 'bg-blue-950/70 border-blue-500 text-white shadow-md ring-1 ring-blue-500/50'
                            : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700 hover:text-zinc-200'
                        }`}
                      >
                        <span className="text-xs font-bold block">{tc.icon} {tc.label}</span>
                        <span className="text-[10px] font-mono text-zinc-400 block mt-0.5 leading-tight">{tc.desc}</span>
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Dropdown Chọn Bot nếu chọn Mode BOT */}
            {mode === 'BOT' && (
              <div className="p-4 bg-zinc-950/80 border border-zinc-800 rounded-2xl space-y-2 animate-fadeIn">
                <label className="text-xs font-bold text-zinc-300 flex items-center justify-between">
                  <span>🤖 Chọn Bot Tham Gia Thi Đấu Ranked:</span>
                  <span className="text-[10px] text-zinc-500">Mỗi bot có ELO riêng</span>
                </label>
                {rankedBots.length === 0 ? (
                  <div className="p-3 bg-rose-950/40 border border-rose-500/30 rounded-xl text-xs text-rose-300">
                    ⚠️ Chỉ bot đang bật <strong>Đang Đấu Ranked</strong> mới xuất hiện ở đây. Hãy bật chế độ này trong tab <strong>Bot Của Tôi</strong>.
                  </div>
                ) : (
                  <div className="relative">
                    <select
                      value={selectedBotId}
                      onChange={(e) => setSelectedBotId(e.target.value)}
                      className="w-full bg-zinc-900 border border-zinc-700 text-white rounded-xl px-3.5 py-2.5 text-xs font-mono font-semibold focus:outline-none focus:border-emerald-500 transition-colors"
                    >
                      {rankedBots.map((b) => (
                        <option key={b.id} value={b.id}>
                          {b.name} ({b.elo ?? 1200} ELO) - ⭐ Đang Đấu Ranked
                        </option>
                      ))}
                    </select>
                  </div>
                )}
              </div>
            )}

            {/* Nút Bắt Đầu Tìm Kiếm */}
            <div className="pt-3">
              <button
                type="button"
                onClick={handleStartSearch}
                disabled={mode === 'BOT' && (rankedBots.length === 0 || !selectedBotId)}
                className="w-full py-3.5 rounded-2xl bg-gradient-to-r from-emerald-600 via-teal-500 to-blue-600 hover:from-emerald-500 hover:via-teal-400 hover:to-blue-500 text-white font-black text-sm shadow-xl shadow-emerald-950/50 flex items-center justify-center gap-2 transition-all transform hover:scale-[1.01] active:scale-[0.99] disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              >
                <span>🔍</span>
                <span>Bắt Đầu Tìm Kiếm Đối Thủ</span>
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
