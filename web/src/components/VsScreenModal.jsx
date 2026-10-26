import React, { useState, useEffect } from 'react';

export default function VsScreenModal({
  matchInfo,
  user,
  myBots = [],
  onComplete,
  onClose,
}) {
  const [countdown, setCountdown] = useState(() => {
    if (matchInfo?.countdownSeconds) return matchInfo.countdownSeconds;
    if (matchInfo?.startsAt) {
      const diffSec = Math.ceil((matchInfo.startsAt - Date.now()) / 1000);
      if (diffSec > 0 && diffSec <= 8) return diffSec;
    }
    return 5;
  });

  // Phân định Phe Bạn (bên trái) và Phe Đối Thủ (bên phải)
  const isSideA = Boolean(
    (user?.username && matchInfo?.sideA?.username === user.username) ||
    (user?.studentCode && matchInfo?.sideA?.username === user.studentCode) ||
    (user?.id && matchInfo?.sideA?.userId === user.id) ||
    (myBots?.some((b) => String(b.id) === String(matchInfo?.sideA?.botId)))
  );

  const defaultUserObj = {
    name: user?.displayName || user?.studentCode || user?.username || 'Bạn',
    username: user?.username || user?.studentCode,
    elo: user?.pvpElo || 1200,
  };
  const defaultOpponentObj = {
    name: 'Đối thủ',
    username: 'opponent',
    elo: 1200,
  };

  const friendly = isSideA ? (matchInfo?.sideA || defaultUserObj) : (matchInfo?.sideB || defaultUserObj);
  const enemy = isSideA ? (matchInfo?.sideB || defaultOpponentObj) : (matchInfo?.sideA || defaultOpponentObj);
  const mode = matchInfo?.mode || 'PVP';

  const timeControl = matchInfo?.timeControl;
  const tcBadgeText = (() => {
    if (timeControl?.name) return timeControl.name;
    if (timeControl?.label) {
      return `Fischer Clock ${timeControl.label}`;
    }
    if (timeControl?.initialSeconds) {
      const mins = Math.floor(timeControl.initialSeconds / 60);
      const inc = timeControl.incrementSeconds ?? 0;
      return `Fischer Clock ${mins}m + ${inc}s`;
    }
    return 'Fischer Clock 3m + 2s';
  })();

  useEffect(() => {
    const timer = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(timer);
          setTimeout(() => {
            if (onComplete) onComplete(matchInfo);
          }, 350);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(timer);
  }, [matchInfo, onComplete]);

  if (!matchInfo) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-black/95 backdrop-blur-2xl select-none animate-fadeIn overflow-hidden">
      {/* Hiệu ứng nền sấm sét & hào quang năng lượng */}
      <div className="absolute inset-0 pointer-events-none opacity-40">
        <div className="absolute top-1/2 -left-40 -translate-y-1/2 w-96 h-96 bg-cyan-600/30 rounded-full blur-[120px]" />
        <div className="absolute top-1/2 -right-40 -translate-y-1/2 w-96 h-96 bg-rose-600/30 rounded-full blur-[120px]" />
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-80 h-80 bg-amber-500/15 rounded-full blur-[100px]" />
      </div>

      <div className="relative w-full max-w-5xl flex flex-col items-center justify-between space-y-8 z-10">
        {/* Badge đỉnh đầu: Chế độ thi đấu */}
        <div className="flex flex-col items-center gap-2">
          <div className="px-5 py-1.5 rounded-full bg-gradient-to-r from-amber-500/20 via-amber-400/30 to-amber-500/20 border border-amber-400/50 text-amber-300 font-mono font-black text-xs sm:text-sm tracking-widest uppercase shadow-lg shadow-amber-950/60 animate-pulse flex items-center gap-2">
            <span>⚔️</span>
            <span>
              {mode === 'BOT'
                ? 'ĐẤU TRƯỜNG RANKED BOT LADDER'
                : mode === 'PVP_QUICK'
                ? 'GHÉP TRẬN PVP NHANH (GIAO HỮU)'
                : 'TRẬN ĐẤU XẾP HẠNG RANKED PVP'}
            </span>
            <span>⚔️</span>
          </div>
          <p className="text-xs text-zinc-400 font-mono">
            {mode === 'BOT'
              ? 'Hệ thống tính điểm Per-Bot độc lập'
              : mode === 'PVP_QUICK'
              ? 'Đấu giao hữu tự do · Ghép tự động không tính điểm ELO'
              : 'Tính điểm ELO PvP chính thức (Zero-Sum K=32)'}
          </p>
        </div>

        {/* Khu Vực Đối Đầu (Face-off 3 Khối: Bạn - VS - Đối Thủ) */}
        <div className="w-full grid grid-cols-1 md:grid-cols-[1fr_auto_1fr] items-center gap-6 sm:gap-10">
          {/* 1. THẺ PHE BẠN (XANH CYAN) */}
          <div className="p-6 sm:p-8 rounded-3xl bg-gradient-to-b from-cyan-950/40 via-zinc-900/90 to-zinc-950 border-2 border-cyan-500/50 shadow-2xl shadow-cyan-950/60 flex flex-col items-center text-center space-y-4 relative overflow-hidden group hover:border-cyan-400 transition-all">
            <div className="absolute top-3 left-4 text-[10px] font-mono font-bold tracking-wider px-2.5 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/40">
              🔵 PHE BẠN (ĐỘI XANH)
            </div>

            <div className="relative mt-4">
              <img
                src={friendly?.avatar || `https://api.dicebear.com/7.x/bottts/svg?seed=${friendly?.name || 'Player'}`}
                alt="Friendly Avatar"
                className="w-28 h-28 sm:w-32 sm:h-32 rounded-3xl bg-zinc-900 p-2 border-4 border-cyan-400 shadow-[0_0_35px_rgba(34,211,238,0.5)] transform group-hover:scale-105 transition-transform"
              />
              <span className="absolute -bottom-2 -right-2 text-xl p-1 bg-cyan-950 border border-cyan-400 rounded-xl shadow">
                {mode === 'BOT' ? '🤖' : '🎮'}
              </span>
            </div>

            <div className="space-y-1 w-full">
              <h2 className="text-xl sm:text-2xl font-black text-white truncate px-2" title={friendly?.name}>
                {friendly?.name || friendly?.displayName || 'Bạn'}
              </h2>
              {friendly?.username && (
                <p className="text-xs text-cyan-300/80 font-mono truncate">
                  @{friendly.username}
                </p>
              )}
            </div>

            <div className="w-full pt-3 border-t border-zinc-800 flex items-center justify-center gap-3">
              <div className="px-4 py-1.5 rounded-xl bg-cyan-950/80 border border-cyan-400/50 text-cyan-200 font-mono font-black text-base shadow-inner">
                ⚡ {friendly?.elo ?? 1200} ELO
              </div>
            </div>
          </div>

          {/* 2. CHỮ VS KHỔNG LỒ & ĐẾM NGƯỢC */}
          <div className="flex flex-col items-center justify-center text-center py-2 md:py-0 space-y-4">
            <div className="relative">
              <h1 className="text-7xl sm:text-8xl md:text-9xl font-black italic tracking-tighter bg-gradient-to-br from-amber-300 via-rose-500 to-indigo-500 bg-clip-text text-transparent drop-shadow-[0_0_45px_rgba(244,63,94,0.9)] animate-pulse transform -skew-x-6">
                VS
              </h1>
            </div>

            <div className="space-y-1">
              <span className="text-xs text-zinc-400 font-semibold uppercase tracking-wider block">
                Trận đấu bắt đầu sau
              </span>
              <div className="text-4xl sm:text-5xl font-mono font-black text-amber-400 drop-shadow-[0_0_20px_rgba(251,191,36,0.8)] animate-bounce">
                {countdown > 0 ? countdown : 'BẮT ĐẦU!'}
              </div>
            </div>
          </div>

          {/* 3. THẺ ĐỐI THỦ (ĐỎ CRIMSON) */}
          <div className="p-6 sm:p-8 rounded-3xl bg-gradient-to-b from-rose-950/40 via-zinc-900/90 to-zinc-950 border-2 border-rose-500/50 shadow-2xl shadow-rose-950/60 flex flex-col items-center text-center space-y-4 relative overflow-hidden group hover:border-rose-400 transition-all">
            <div className="absolute top-3 right-4 text-[10px] font-mono font-bold tracking-wider px-2.5 py-0.5 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/40">
              🔴 ĐỐI THỦ (ĐỘI ĐỎ)
            </div>

            <div className="relative mt-4">
              <img
                src={enemy?.avatar || `https://api.dicebear.com/7.x/bottts/svg?seed=${enemy?.name || 'Enemy'}`}
                alt="Enemy Avatar"
                className="w-28 h-28 sm:w-32 sm:h-32 rounded-3xl bg-zinc-900 p-2 border-4 border-rose-500 shadow-[0_0_35px_rgba(244,63,94,0.5)] transform group-hover:scale-105 transition-transform"
              />
              <span className="absolute -bottom-2 -left-2 text-xl p-1 bg-rose-950 border border-rose-500 rounded-xl shadow">
                {mode === 'BOT' ? '🤖' : '🎮'}
              </span>
            </div>

            <div className="space-y-1 w-full">
              <h2 className="text-xl sm:text-2xl font-black text-white truncate px-2" title={enemy?.name}>
                {enemy?.name || enemy?.displayName || 'Đối thủ'}
              </h2>
              {enemy?.username && (
                <p className="text-xs text-rose-300/80 font-mono truncate">
                  @{enemy.username}
                </p>
              )}
            </div>

            <div className="w-full pt-3 border-t border-zinc-800 flex items-center justify-center gap-3">
              <div className="px-4 py-1.5 rounded-xl bg-rose-950/80 border border-rose-500/50 text-rose-200 font-mono font-black text-base shadow-inner">
                ⚡ {enemy?.elo ?? 1200} ELO
              </div>
            </div>
          </div>
        </div>

        {/* Footer ghi chú quy tắc bàn cờ & Thể thức thời gian to nổi bật */}
        <div className="flex flex-col items-center gap-2.5">
          <div className="px-5 py-2 rounded-2xl bg-amber-500/15 border-2 border-amber-400/60 text-amber-300 font-mono font-black text-sm sm:text-base tracking-wide shadow-xl shadow-amber-950/60 flex items-center gap-2.5 animate-pulse">
            <span className="text-lg">⏱️</span>
            <span>{tcBadgeText}</span>
          </div>

          <div className="text-center text-[11px] text-zinc-400 font-mono flex items-center justify-center gap-2">
            <span>⚡ Bàn cờ 9x9</span>
            <span>·</span>
            <span>Đua đích hoặc tiêu diệt toàn bộ quân địch để chiến thắng</span>
          </div>
        </div>
      </div>
    </div>
  );
}
