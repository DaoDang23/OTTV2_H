import React, { useState, useEffect } from 'react';
import { TIME_CONTROL_CONFIGS } from './ChallengeModal.jsx';
import { createRoomApi, joinRoomApi } from '../utils/roomApi.js';
import { buildApiUrl } from '../utils/api.js';

export default function GameModes({
  onNavigateToBattleCode,
  onOpenReplay,
  matches = [],
  matchHistory = matches,
  onStartLocalGame,
  onStartRoomGame,
  user = null,
  pvpElo = 1200,
  onToast = null,
  onMatchFound = null,
}) {
  const [roomCode, setRoomCode] = useState('');
  const [selectedLocalTcKey, setSelectedLocalTcKey] = useState('blitz_3_2');
  const [selectedQuickTcKey, setSelectedQuickTcKey] = useState('blitz_3_2');
  const [isJoining, setIsJoining] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [errorNotice, setErrorNotice] = useState('');

  const QUICK_TC_OPTIONS = {
    blitz_3_2: { label: 'Chớp 3+2', desc: '03:00 + 2s', icon: '⚡' },
    rapid_5_3: { label: 'Nhanh 5+3', desc: '05:00 + 3s', icon: '🔥' },
    bullet_1_1: { label: 'Siêu Chớp 1+1', desc: '01:00 + 1s', icon: '🚀' },
    auto: { label: 'Tự Động', desc: 'Ghép bất kỳ ai', icon: '🎲' },
  };

  // Trạng thái tìm kiếm tự động PvP Quick Match
  const [isSearchingQuickMatch, setIsSearchingQuickMatch] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  // Bộ đếm thời gian tìm kiếm
  useEffect(() => {
    let timer = null;
    if (isSearchingQuickMatch) {
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
  }, [isSearchingQuickMatch]);

  // Lắng nghe sự kiện MATCH_FOUND từ WebSocket STOMP
  useEffect(() => {
    const handleFound = (e) => {
      if (isSearchingQuickMatch) {
        setIsSearchingQuickMatch(false);
        if (onMatchFound) {
          onMatchFound(e.detail);
        }
      }
    };
    window.addEventListener('ottv2_match_found', handleFound);
    return () => {
      window.removeEventListener('ottv2_match_found', handleFound);
    };
  }, [isSearchingQuickMatch, onMatchFound]);

  // Heartbeat ping định kỳ mỗi 3 giây khi đang tìm trận nhanh để chống "bóng ma" hàng đợi
  useEffect(() => {
    if (!isSearchingQuickMatch) return undefined;

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
            mode: 'PVP_QUICK',
          }),
        });

        if (!isMounted) return;

        if (res.ok) {
          const data = await res.json();
          // Nếu đã ghép thành công đối thủ
          if (data?.matchFound) {
            setIsSearchingQuickMatch(false);
            window.dispatchEvent(new CustomEvent('ottv2_match_found', { detail: data.matchFound }));
            if (onMatchFound) {
              onMatchFound(data.matchFound);
            }
            return;
          }
          // Nếu bị loại khỏi hàng đợi (ví dụ timeout 180s)
          if (data && data.inQueue === false && !data.matchFound) {
            setIsSearchingQuickMatch(false);
            if (onToast) {
              onToast({ type: 'INFO', text: '⏱️ Đã hết thời gian chờ ghép trận nhanh (quá 3 phút). Vui lòng thử lại!' });
            }
          }
        }
      } catch (_) {
        // Không spam log console khi ping định kỳ gặp lỗi mạng
      } finally {
        inFlight = false;
      }
    };

    const intervalId = window.setInterval(sendHeartbeat, 3000);

    const handleBeforeUnload = () => {
      try {
        const leavePayload = JSON.stringify({ mode: 'PVP_QUICK', userId: user?.id });
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
  }, [isSearchingQuickMatch, user?.id, onMatchFound, onToast]);

  const recentMatches = [...(matchHistory || matches || [])]
    .sort((a, b) => new Date(b.createdAt || b.playedAt || 0) - new Date(a.createdAt || a.playedAt || 0))
    .slice(0, 5);

  const handleQuickMatch = async () => {
    setIsSearchingQuickMatch(true);
    setErrorNotice('');
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
          mode: 'PVP_QUICK',
          timeControlKey: selectedQuickTcKey,
          userId: user?.id,
          username: user?.studentCode || user?.username || user?.email,
          studentCode: user?.studentCode,
          email: user?.email,
          displayName: user?.displayName || user?.studentCode || 'Người chơi',
          pvpElo: user?.pvpElo || pvpElo || 1200,
        }),
      });

      if (res.ok) {
        const data = await res.json();
        if (data.status === 'MATCHED') {
          setIsSearchingQuickMatch(false);
          window.dispatchEvent(new CustomEvent('ottv2_match_found', { detail: data }));
          if (onMatchFound) {
            onMatchFound(data);
          }
          return;
        }
        // status === 'QUEUED': Chờ sự kiện WebSocket STOMP broadcast MATCH_FOUND
      } else {
        const err = await res.json().catch(() => null);
        const msg = err?.message || 'Không thể tham gia hàng đợi ghép trận!';
        if (onToast) onToast({ type: 'LOSE', text: `⚠️ ${msg}` });
        setIsSearchingQuickMatch(false);
      }
    } catch (err) {
      console.error('Lỗi khi tham gia hàng đợi ghép nhanh:', err);
      if (onToast) onToast({ type: 'LOSE', text: '⚠️ Lỗi kết nối máy chủ ghép trận!' });
      setIsSearchingQuickMatch(false);
    }
  };

  const handleCancelQuickMatch = async () => {
    setIsSearchingQuickMatch(false);
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
          mode: 'PVP_QUICK',
          userId: user?.id,
          username: user?.studentCode || user?.username || user?.email,
        }),
      });
      if (onToast) onToast({ type: 'INFO', text: '⚪ Đã hủy tìm kiếm ghép trận nhanh.' });
    } catch (e) {
      console.warn('Lỗi khi hủy hàng đợi ghép nhanh:', e);
    }
  };

  const handleCreateRoom = async () => {
    try {
      setIsCreating(true);
      setErrorNotice('');
      const data = await createRoomApi('Chủ Phòng');
      onStartRoomGame?.({ roomCode: data.code, isHost: true, isQuickMatch: false, playerToken: data.playerToken });
    } catch (err) {
      console.error('Lỗi tạo phòng:', err);
      alert('Không thể tạo phòng lúc này. Vui lòng kiểm tra kết nối mạng!');
    } finally {
      setIsCreating(false);
    }
  };

  const handleJoinRoom = async () => {
    const trimmed = roomCode.trim().toUpperCase();
    if (!trimmed) return;
    try {
      setIsJoining(true);
      setErrorNotice('');
      const data = await joinRoomApi(trimmed, 'Khách');
      onStartRoomGame?.({
        roomCode: data.code,
        isHost: false,
        isQuickMatch: false,
        timeControl: data.timeControl,
        playerToken: data.playerToken,
      });
    } catch (err) {
      console.error('Lỗi vào phòng:', err);
      setErrorNotice('Mã phòng không tồn tại hoặc đã đầy!');
      alert('Mã phòng không tồn tại hoặc đã đầy!');
    } finally {
      setIsJoining(false);
    }
  };

  return (
    <div className="max-w-6xl mx-auto space-y-8 animate-fadeIn">
      {/* Banner chào mừng */}
      <div className="relative overflow-hidden rounded-3xl bg-gradient-to-r from-zinc-850 via-zinc-900 to-zinc-850 border border-zinc-800 p-8 shadow-2xl">
        <div className="relative z-10 max-w-2xl">
          <span className="inline-block px-3 py-1 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 text-xs font-bold uppercase tracking-wider mb-3">
            Mùa giải 2026 · Kỳ 5 Lập Trình Mạng
          </span>
          <h2 className="text-3xl lg:text-4xl font-black text-white tracking-tight leading-tight">
            Đấu Trường Oẳn Tù Tì Chiến Thuật 9x9
          </h2>
          <p className="text-zinc-400 mt-2 text-sm leading-relaxed">
            Chọn chế độ chơi yêu thích: Thách đấu bạn bè qua mã phòng, solo đối kháng trên cùng một máy, hoặc lập trình bot để leo bảng xếp hạng Elo!
          </p>
        </div>
        <div className="absolute right-6 -bottom-6 text-9xl opacity-10 select-none pointer-events-none">
          🎮
        </div>
      </div>

      {/* 3 Thẻ Card Lớn Chế Độ Chơi */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {/* Card 1: Chơi qua mã phòng / Ghép Trận PvP */}
        <div className="bg-zinc-850 hover:bg-zinc-800/90 border border-zinc-750 hover:border-emerald-500/50 rounded-3xl p-6 flex flex-col justify-between transition-all duration-300 hover:shadow-2xl hover:shadow-emerald-950/20 group">
          <div className="space-y-4">
            <div className="w-14 h-14 rounded-2xl bg-indigo-500/10 border border-indigo-500/30 flex items-center justify-center text-3xl group-hover:scale-110 transition-transform">
              ⚔️
            </div>
            <div>
              <h3 className="text-xl font-bold text-white group-hover:text-emerald-400 transition-colors">
                Ghép Trận & Mã Phòng PvP
              </h3>
              <p className="text-sm text-zinc-400 mt-1.5 leading-relaxed">
                Ghép trận trực tuyến với người chơi khác, tạo phòng riêng hoặc đấu với Bot dự phòng nếu không có ai trực tuyến.
              </p>
            </div>

            {/* Lựa Chọn Thể Thức Thời Gian Fischer Clock */}
            <div className="bg-zinc-900/90 p-3 rounded-2xl border border-zinc-800 space-y-2">
              <label className="text-xs font-bold text-indigo-400 flex items-center justify-between">
                <span>⏱️ Thể Thức Thời Gian (Fischer Clock):</span>
                <span className="text-[10px] font-mono text-zinc-500">Chuẩn FIDE</span>
              </label>

              <div className="grid grid-cols-2 gap-1.5">
                {Object.entries(QUICK_TC_OPTIONS).map(([key, tc]) => {
                  const isSelected = selectedQuickTcKey === key;
                  return (
                    <button
                      key={key}
                      type="button"
                      onClick={() => setSelectedQuickTcKey(key)}
                      className={`p-2 rounded-xl border text-left transition-all ${
                        isSelected
                          ? 'bg-indigo-950/70 border-indigo-500 text-white shadow-md ring-1 ring-indigo-500/50'
                          : 'bg-zinc-950 border-zinc-800 text-zinc-400 hover:border-zinc-700 hover:text-zinc-200'
                      }`}
                    >
                      <span className="text-xs font-bold block">{tc.icon} {tc.label}</span>
                      <span className="text-[10px] font-mono text-zinc-400 block mt-0.5 leading-tight">{tc.desc}</span>
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="space-y-3 pt-1">
              <button
                onClick={handleQuickMatch}
                className="w-full py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-bold text-sm transition-all shadow-md shadow-indigo-950/40 flex items-center justify-center gap-2"
              >
                <span>⚡</span>
                <span>Ghép Trận PvP Nhanh ({QUICK_TC_OPTIONS[selectedQuickTcKey]?.label || 'Chớp 3+2'})</span>
              </button>

              <div className="relative flex items-center justify-center">
                <div className="border-t border-zinc-800 w-full"></div>
                <span className="bg-zinc-850 px-2 text-[11px] text-zinc-500 uppercase font-mono">hoặc vào mã phòng</span>
                <div className="border-t border-zinc-800 w-full"></div>
              </div>

              <div className="flex gap-2">
                <input
                  type="text"
                  placeholder="Mã phòng (ví dụ: 882914)"
                  value={roomCode}
                  onChange={(e) => {
                    setRoomCode(e.target.value.toUpperCase());
                    setErrorNotice('');
                  }}
                  className="w-full bg-zinc-900 border border-zinc-700 rounded-xl px-3 py-2 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500 transition-colors font-mono tracking-wider"
                />
                <button
                  disabled={!roomCode.trim() || isJoining}
                  onClick={handleJoinRoom}
                  className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 disabled:opacity-40 disabled:cursor-not-allowed text-white font-bold text-xs transition-all shadow-md shadow-emerald-950/40 whitespace-nowrap"
                >
                  {isJoining ? 'Đang vào...' : 'Vào'}
                </button>
              </div>

              {errorNotice && (
                <div className="p-2 rounded-xl bg-rose-950/60 border border-rose-800 text-rose-300 text-[11px] font-medium text-center animate-shake">
                  ⚠️ {errorNotice}
                </div>
              )}
            </div>
          </div>

          <div className="pt-4 border-t border-zinc-800/80 mt-4">
            <button
              disabled={isCreating}
              onClick={handleCreateRoom}
              className="w-full py-2 rounded-xl bg-zinc-750 hover:bg-zinc-700 disabled:opacity-50 text-zinc-200 font-semibold text-xs transition-colors border border-zinc-700 shadow"
            >
              {isCreating ? '⏳ Đang tạo phòng...' : '+ Tạo Phòng Riêng Có Mã Mời'}
            </button>
          </div>
        </div>

        {/* Card 2: Chơi 2 người trên 1 máy (Local) */}
        <div className="bg-zinc-850 hover:bg-zinc-800/90 border border-zinc-750 hover:border-blue-500/50 rounded-3xl p-6 flex flex-col justify-between transition-all duration-300 hover:shadow-2xl hover:shadow-blue-950/20 group">
          <div className="space-y-4">
            <div className="w-14 h-14 rounded-2xl bg-blue-500/10 border border-blue-500/30 flex items-center justify-center text-3xl group-hover:scale-110 transition-transform">
              👥
            </div>
            <div>
              <h3 className="text-xl font-bold text-white group-hover:text-blue-400 transition-colors">
                2 Người Cùng Máy (Local)
              </h3>
              <p className="text-sm text-zinc-400 mt-1.5 leading-relaxed">
                Chế độ chia đôi chuột/bàn phím. Hai người chơi trực tiếp trên cùng một màn hình để giao lưu thử nghiệm chiến thuật.
              </p>
            </div>

            {/* 3 Lựa Chọn Thể Thức Thời Gian Fischer Chess Clock (Tương tự PvP Xếp Hạng) */}
            <div className="bg-zinc-900/90 p-3.5 rounded-2xl border border-zinc-800 space-y-2.5">
              <label className="text-xs font-bold text-blue-400 flex items-center justify-between">
                <span>⏱️ Thể Thức Thời Gian (Fischer Clock):</span>
                <span className="text-[10px] font-mono text-zinc-500">Chuẩn FIDE</span>
              </label>

              <div className="grid grid-cols-3 gap-2">
                {Object.entries(TIME_CONTROL_CONFIGS).map(([key, tc]) => {
                  const isSelected = selectedLocalTcKey === key;
                  return (
                    <button
                      key={key}
                      type="button"
                      onClick={() => setSelectedLocalTcKey(key)}
                      className={`p-2.5 rounded-xl border text-left transition-all ${
                        isSelected
                          ? 'bg-blue-950/60 border-blue-500 text-white shadow-md ring-1 ring-blue-500/50'
                          : 'bg-zinc-950 border-zinc-800 text-zinc-400 hover:border-zinc-700 hover:text-zinc-200'
                      }`}
                    >
                      <span className="text-xs font-bold block">{tc.label}</span>
                      <span className="text-[10px] font-mono text-zinc-400 block mt-0.5 leading-tight">{tc.desc}</span>
                    </button>
                  );
                })}
              </div>
            </div>

            <ul className="text-xs text-zinc-400 space-y-1.5 pt-1">
              <li className="flex items-center gap-2">✓ Không cần kết nối internet</li>
              <li className="flex items-center gap-2">✓ Chuẩn luật Kéo - Búa - Bao 9x9</li>
            </ul>
          </div>

          <div className="pt-4 border-t border-zinc-800/80 mt-4">
            <button
              onClick={() => onStartLocalGame?.(TIME_CONTROL_CONFIGS[selectedLocalTcKey])}
              className="w-full py-3 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-bold text-sm transition-all shadow-lg shadow-blue-950/50"
            >
              Bắt Đầu Trận Local
            </button>
          </div>
        </div>

        {/* Card 3: Đấu Bot (Battle Code) */}
        <div className="bg-gradient-to-b from-zinc-850 to-zinc-900 border-2 border-emerald-500/40 hover:border-emerald-500 rounded-3xl p-6 flex flex-col justify-between transition-all duration-300 hover:shadow-2xl hover:shadow-emerald-950/30 group relative overflow-hidden">
          <div className="absolute top-4 right-4 bg-emerald-500 text-zinc-950 font-black text-[10px] uppercase px-2.5 py-1 rounded-full tracking-wider shadow">
            RANKED ELO
          </div>

          <div className="space-y-4">
            <div className="w-14 h-14 rounded-2xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-3xl group-hover:scale-110 transition-transform">
              🤖
            </div>
            <div>
              <h3 className="text-xl font-bold text-white group-hover:text-emerald-400 transition-colors">
                Đấu Bot (Battle Code)
              </h3>
              <p className="text-sm text-zinc-400 mt-1.5 leading-relaxed">
                Nạp thuật toán bot của bạn (Python / Java / C++). Hệ thống tự động biên dịch và xếp cặp đấu ladder tính điểm Elo.
              </p>
            </div>
            <div className="bg-zinc-900/90 rounded-2xl p-3 border border-zinc-800 space-y-1">
              <div className="flex justify-between text-xs font-semibold">
                <span className="text-zinc-400">Khung giờ mở Ladder:</span>
                <span className="text-emerald-400">24/7 Tự Động</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-zinc-500">Giới hạn tài nguyên:</span>
                <span className="text-zinc-300 font-mono">256MB · 1s/turn</span>
              </div>
            </div>
          </div>

          <div className="pt-6 border-t border-zinc-800/80 mt-6">
            <button
              onClick={onNavigateToBattleCode}
              className="w-full py-3 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-500 hover:brightness-110 text-white font-black text-sm tracking-wide transition-all shadow-lg shadow-emerald-950/60 flex items-center justify-center gap-2"
            >
              <span>Vào Đấu Trường Bot</span>
              <span>→</span>
            </button>
          </div>
        </div>
      </div>

      {/* Lịch sử trận đấu gần đây */}
      {recentMatches.length > 0 && (
        <div className="bg-zinc-850 border border-zinc-750 rounded-3xl p-6 shadow-xl">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-lg font-bold text-white flex items-center gap-2">
              <span>📜</span> Lịch Sử Trận Đấu Gần Đây
            </h3>
            <span className="text-xs text-zinc-400 font-mono">5 trận mới nhất</span>
          </div>

          <div className="divide-y divide-zinc-800">
            {recentMatches.map((match) => (
              <div key={match.id} className="py-3 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <span className="font-bold text-sm text-white">{match.sideA?.botName || 'Bot A'}</span>
                  <span className="text-xs text-zinc-500">vs</span>
                  <span className="font-bold text-sm text-white">{match.sideB?.botName || 'Bot B'}</span>
                </div>
                <div className="flex items-center gap-3">
                  <span
                    className={`text-xs px-2 py-0.5 rounded font-mono font-bold ${
                      match.status === 'FINISHED' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-zinc-800 text-zinc-400'
                    }`}
                  >
                    {match.status}
                  </span>
                  {onOpenReplay && (
                    <button
                      onClick={() => onOpenReplay(match)}
                      className="px-3 py-1 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-xs font-semibold text-zinc-200 transition-colors"
                    >
                      Xem Replay
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Modal Đang Tìm Kiếm Người Chơi Trực Tuyến (PvP Quick Match) */}
      {isSearchingQuickMatch && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-md animate-fadeIn select-none">
          <div className="relative w-full max-w-md bg-zinc-900 border border-zinc-750 rounded-3xl shadow-2xl p-6 sm:p-8 space-y-6 text-center overflow-hidden">
            {/* Hiệu ứng hào quang nền */}
            <div className="absolute -top-24 -left-24 w-56 h-56 bg-indigo-500/15 rounded-full blur-3xl pointer-events-none" />
            <div className="absolute -bottom-24 -right-24 w-56 h-56 bg-cyan-500/15 rounded-full blur-3xl pointer-events-none" />

            <div className="relative w-28 h-28 mx-auto flex items-center justify-center">
              <div className="absolute inset-0 rounded-full border-2 border-indigo-500/40 animate-ping opacity-60" />
              <div className="absolute inset-2 rounded-full border-2 border-cyan-400/60 animate-pulse" />
              <div className="w-16 h-16 rounded-full bg-gradient-to-tr from-indigo-600 to-cyan-500 flex items-center justify-center text-3xl shadow-xl shadow-indigo-950/60 text-white font-black">
                🎮
              </div>
            </div>

            <div className="space-y-2">
              <span className="inline-block px-3 py-1 rounded-full bg-indigo-500/15 text-indigo-300 border border-indigo-500/30 text-[11px] font-mono font-bold uppercase tracking-wider">
                PvP Auto-Matchmaking
              </span>
              <h3 className="text-lg font-black text-white animate-pulse">
                Đang Tìm Kiếm Người Chơi Trực Tuyến...
              </h3>
              <p className="text-xs text-zinc-400">
                Chế độ: <strong className="text-indigo-300">Ghép Trận Nhanh (Giao Hữu)</strong> · {QUICK_TC_OPTIONS[selectedQuickTcKey]?.icon} {QUICK_TC_OPTIONS[selectedQuickTcKey]?.label} ({QUICK_TC_OPTIONS[selectedQuickTcKey]?.desc})
              </p>
              <div className="inline-block px-3.5 py-1 bg-zinc-800/90 rounded-full border border-zinc-700 text-xs font-mono font-bold text-amber-300 mt-2 shadow-inner">
                ⏱️ Thời gian chờ: {elapsedSeconds}s
              </div>
              <p className="text-[11px] text-zinc-500 max-w-xs mx-auto pt-1 leading-relaxed">
                Hệ thống đang tự động tìm đối thủ trực tuyến phù hợp để ghép cặp. Khi tìm thấy đối thủ, màn hình đối đầu VS sẽ tự động hiển thị và kết nối vào phòng thi đấu!
              </p>
            </div>

            <button
              type="button"
              onClick={handleCancelQuickMatch}
              className="w-full py-2.5 rounded-xl bg-zinc-800 hover:bg-rose-950/60 text-zinc-300 hover:text-rose-300 border border-zinc-700 hover:border-rose-700/60 font-bold text-xs transition-all shadow-lg"
            >
              ✕ Hủy Tìm Kiếm
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
