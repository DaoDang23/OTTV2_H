import React, { useState, useEffect, useCallback } from 'react';
import { filterActiveBots } from '../utils/botStorage.js';
import { api, csrfHeaders, buildApiUrl } from '../utils/api.js';
import BotDetailModal from './BotDetailModal.jsx';

export default function Profile({
  user,
  userStats,
  pvpElo = 1200,
  botElo = 1200,
  myBots = [],
  matches = [],
  matchHistory: propsMatchHistory,
  onDeleteBot,
  onCleanBots,
  onUpdateStudentCode,
  onOpenReplay,
  onToggleLadderActive,
  onToast,
  onRefreshData,
  onResetTestData,
}) {
  const [activeTab, setActiveTab] = useState('PVP'); // 'PVP' | 'BOT' | 'TOURNAMENTS'
  const [isEditingStudentCode, setIsEditingStudentCode] = useState(false);
  const [studentCodeInput, setStudentCodeInput] = useState(user?.studentCode || '');
  const [myTournaments, setMyTournaments] = useState([]);
  const [isLoadingTournaments, setIsLoadingTournaments] = useState(false);
  const [selectedBotIdForDetail, setSelectedBotIdForDetail] = useState(null);

  // Tải danh sách giải đấu người dùng đã tham gia
  const loadMyTournaments = useCallback(async () => {
    if (!user || user.isOffline) {
      setMyTournaments([]);
      return;
    }
    setIsLoadingTournaments(true);
    try {
      const res = await api('/api/tournaments/my');
      if (res && res.ok) {
        const data = await res.json();
        setMyTournaments(Array.isArray(data) ? data : []);
      }
    } catch (e) {
      console.warn('Could not load user tournaments:', e);
    } finally {
      setIsLoadingTournaments(false);
    }
  }, [user]);

  useEffect(() => {
    loadMyTournaments();
  }, [loadMyTournaments]);

  // Helper lấy định danh tài khoản để cô lập cache match history, chống rò rỉ dữ liệu chéo
  const getUserKey = (u) =>
    u?.username || (u?.email ? u.email.split('@')[0] : '') || (u?.id ? String(u.id) : '') || '';

  const currentUserKey = getUserKey(user);
  const userCacheKey = currentUserKey ? `ottv2_match_history_${currentUserKey}` : null;

  // 1. matchHistory state nạp từ cache riêng của user hiện tại (ví dụ: ottv2_match_history_alice)
  const [matchHistory, setMatchHistory] = useState(() => {
    // Xóa bỏ key cũ dùng chung để dọn dẹp dữ liệu rò rỉ
    try {
      localStorage.removeItem('ottv2_match_history');
    } catch {}

    if (userCacheKey) {
      try {
        const cached = localStorage.getItem(userCacheKey);
        if (cached) {
          const parsed = JSON.parse(cached);
          if (Array.isArray(parsed) && parsed.length > 0) return parsed;
        }
      } catch {}
    }
    if (user && Array.isArray(propsMatchHistory) && propsMatchHistory.length > 0) return propsMatchHistory;
    if (user && Array.isArray(matches) && matches.length > 0) return matches;
    return [];
  });

  // 2. Reset state và nạp đúng cache khi user thay đổi (đăng xuất hoặc đổi tài khoản)
  useEffect(() => {
    if (!user) {
      setMatchHistory([]);
      return;
    }
    const currentKey = getUserKey(user);
    if (currentKey) {
      const key = `ottv2_match_history_${currentKey}`;
      try {
        const cached = localStorage.getItem(key);
        if (cached) {
          const parsed = JSON.parse(cached);
          setMatchHistory(Array.isArray(parsed) ? parsed : []);
        } else {
          setMatchHistory([]);
        }
      } catch {
        setMatchHistory([]);
      }
    } else {
      setMatchHistory([]);
    }
  }, [user?.id, user?.username, user?.email]);

  // 3. Hàm loadHistory: Fetch từ API (/api/profile hoặc /api/matches/history) được xác thực theo user hiện tại
  const loadHistory = useCallback(async () => {
    if (!user || user.isOffline) {
      setMatchHistory([]);
      return;
    }
    const activeKey = getUserKey(user);
    const activeCacheKey = activeKey ? `ottv2_match_history_${activeKey}` : null;

    try {
      const res = await api('/api/profile');
      if (res && (res.status === 401 || res.status === 403)) {
        setMatchHistory([]);
        return;
      }
      if (res && res.ok) {
        const contentType = res.headers.get('content-type') || '';
        if (contentType.includes('application/json')) {
          const profileData = await res.json();
          const historyItems = profileData?.eloHistory || [];

          let localCached = [];
          if (activeCacheKey) {
            try {
              const raw = localStorage.getItem(activeCacheKey);
              if (raw) localCached = JSON.parse(raw);
            } catch {}
          }

          const formatted = historyItems.map((item) => {
            const localMatch = localCached.find(
              (m) => String(m.id) === String(item.id) || String(m.replayId) === String(item.id)
            );
            let cachedReplay = localMatch?.replayData || localMatch?.replay || null;
            if (!cachedReplay) {
              try {
                const raw = localStorage.getItem(`ottv2_replay_${item.id}`);
                if (raw) cachedReplay = JSON.parse(raw);
              } catch {}
            }
            if (!cachedReplay && item.replayId) {
              try {
                const raw = localStorage.getItem(`ottv2_replay_${item.replayId}`);
                if (raw) cachedReplay = JSON.parse(raw);
              } catch {}
            }
            if (!cachedReplay && localMatch?.replayId) {
              try {
                const raw = localStorage.getItem(`ottv2_replay_${localMatch.replayId}`);
                if (raw) cachedReplay = JSON.parse(raw);
              } catch {}
            }

            // Nhận diện phe người chơi (A hay B) để cấu hình góc nhìn & tên đấu thủ chuẩn xác
            const myName = user?.displayName || user?.username || 'Bạn';
            const oppName = item.opponent || 'Đối thủ';
            const myIdentifiers = [
              user?.username,
              user?.studentCode,
              user?.displayName,
              user?.email ? user.email.split('@')[0] : null,
            ].filter(Boolean).map((s) => String(s).toLowerCase().trim());

            let userSide = localMatch?.userSide || localMatch?.side;
            if (!userSide && item.winner) {
              const w = String(item.winner).toUpperCase().trim();
              if (w === 'B') {
                userSide = item.userWon ? 'B' : 'A';
              } else if (w === 'A') {
                userSide = item.userWon ? 'A' : 'B';
              }
            }
            if (!userSide && cachedReplay) {
              const bName = (cachedReplay.sideB?.botName || cachedReplay.sideB?.name || '').toLowerCase().trim();
              const bUser = cachedReplay.sideB?.userId || cachedReplay.sideB?.ownerId;
              const aName = (cachedReplay.sideA?.botName || cachedReplay.sideA?.name || '').toLowerCase().trim();
              const aUser = cachedReplay.sideA?.userId || cachedReplay.sideA?.ownerId;

              const isMatchB = (user?.id && bUser && String(user.id) === String(bUser)) ||
                myIdentifiers.some((id) => id && bName && (bName === id || bName.includes(id)));
              const isMatchA = (user?.id && aUser && String(user.id) === String(aUser)) ||
                myIdentifiers.some((id) => id && aName && (aName === id || aName.includes(id)));

              if (isMatchB && !isMatchA) {
                userSide = 'B';
              } else if (isMatchA && !isMatchB) {
                userSide = 'A';
              }
            }

            const isRanked = item.isRanked !== undefined
              ? Boolean(item.isRanked)
              : (localMatch?.isRanked !== undefined
                  ? Boolean(localMatch.isRanked)
                  : (item.change !== 0 && item.eloChange !== 0 && item.mode !== 'PRACTICE' && item.mode !== 'CASUAL'));

            const sideA = cachedReplay?.sideA || localMatch?.sideA || (userSide === 'A' ? { botName: myName, userId: user?.id } : (userSide === 'B' ? { botName: oppName } : { botName: 'Đội Xanh' }));
            const sideB = cachedReplay?.sideB || localMatch?.sideB || (userSide === 'B' ? { botName: myName, userId: user?.id } : (userSide === 'A' ? { botName: oppName } : { botName: 'Đội Đỏ' }));

            let winnerSide = localMatch?.winnerSide || cachedReplay?.result?.winner || localMatch?.winner || (item.winner && item.winner !== 'USER' && item.winner !== 'OPPONENT' ? item.winner : null);
            if (!winnerSide || winnerSide === 'USER' || winnerSide === 'OPPONENT') {
              if (item.userWon === true) {
                winnerSide = userSide || 'A';
              } else if (item.userWon === false) {
                winnerSide = userSide === 'A' ? 'B' : (userSide === 'B' ? 'A' : 'B');
              } else {
                winnerSide = 'DRAW';
              }
            }

            return {
              id: item.id,
              replayId: localMatch?.replayId || item.replayId || item.id,
              mode: (item.mode || 'PVP').toUpperCase(),
              isRanked,
              userSide,
              side: userSide,
              opponent: item.opponent,
              opponentElo: item.opponentElo,
              userWon: item.userWon,
              change: isRanked ? (item.change ?? item.eloChange) : 0,
              newElo: item.newElo,
              timestamp: item.timestamp,
              createdAt: (() => {
                let resolved = item.createdAt || localMatch?.createdAt;
                if (resolved && String(resolved).startsWith('1970-01-01')) {
                  resolved = null;
                }
                if (!resolved && item.timestamp && item.timestamp.includes('/')) {
                  const parts = item.timestamp.split(' ');
                  if (parts.length === 2) {
                    const dateParts = parts[0].split('/');
                    if (dateParts.length === 3) {
                      resolved = `${dateParts[2]}-${dateParts[1].padStart(2, '0')}-${dateParts[0].padStart(2, '0')}T${parts[1]}`;
                    }
                  }
                }
                return resolved || new Date().toISOString();
              })(),
              status: 'FINISHED',
              sideA,
              sideB,
              winner: winnerSide,
              winnerSide,
              replay: cachedReplay,
              replayData: cachedReplay,
            };
          });

          // Server là nguồn sự thật duy nhất khi fetch thành công
          setMatchHistory(formatted);
          if (activeCacheKey) {
            localStorage.setItem(activeCacheKey, JSON.stringify(formatted));
          }
          return;
        }
      }
      throw new Error('API fetch error');
    } catch (err) {
      console.warn('Backend loadHistory error, fallback to user cache:', err);
      if (activeCacheKey) {
        try {
          const cached = localStorage.getItem(activeCacheKey);
          if (cached) {
            const parsed = JSON.parse(cached);
            if (Array.isArray(parsed)) {
              setMatchHistory(parsed);
            }
          }
        } catch {}
      }
    }
  }, [user]);

  // 4. useEffect nạp lịch sử đấu chạy khi component mount hoặc user thay đổi
  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  // 5. Cập nhật khi props matches hoặc propsMatchHistory thay đổi (sau khi kết thúc trận)
  useEffect(() => {
    if (!user) {
      setMatchHistory([]);
      return;
    }
    const incoming = propsMatchHistory || matches;
    if (incoming && incoming.length > 0) {
      setMatchHistory((prev) => {
        const existingIds = new Set(prev.map((m) => String(m.id)));
        const newOnes = incoming.filter((m) => !existingIds.has(String(m.id)));
        if (newOnes.length > 0) {
          const merged = [...newOnes, ...prev];
          const activeKey = getUserKey(user);
          if (activeKey) {
            localStorage.setItem(`ottv2_match_history_${activeKey}`, JSON.stringify(merged));
          }
          return merged;
        }
        return prev;
      });
    }
  }, [propsMatchHistory, matches, user]);

  const username = user?.username || (user?.email ? user.email.split('@')[0] : '') || user?.displayName || 'Chưa cập nhật';
  const email = user?.email || 'Chưa cập nhật';
  const role = user?.role || 'STUDENT';

  const [isResettingData, setIsResettingData] = useState(false);

  const handleResetTestData = async () => {
    const confirmed = window.confirm(
      'Hành động này sẽ xóa toàn bộ lịch sử đấu và đưa ELO mọi người về 1200. Bạn có chắc chắn?'
    );
    if (!confirmed) return;

    try {
      setIsResettingData(true);
      const headers = await csrfHeaders();
      const res = await api('/api/admin/reset-test-data', {
        method: 'POST',
        headers,
      });

      if (res && res.ok) {
        // Clear all relevant caches in localStorage
        try {
          const keysToRemove = [];
          for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (
              k &&
              (k.startsWith('ottv2_match_history') ||
               k.startsWith('ottv2_history') ||
               k.startsWith('ottv2_stats') ||
               k.startsWith('ottv2_replay') ||
               k.startsWith('ottv2_leaderboard'))
            ) {
              keysToRemove.push(k);
            }
          }
          for (const k of keysToRemove) {
            localStorage.removeItem(k);
          }
        } catch {}

        setMatchHistory([]);

        if (onToast) {
          onToast({
            type: 'WIN',
            text: '✅ Đã xóa toàn bộ dữ liệu test và reset ELO về 1200 thành công!',
          });
        }
        if (onResetTestData) {
          await onResetTestData();
        } else if (onRefreshData) {
          await onRefreshData();
        } else {
          window.location.reload();
        }
      } else {
        const err = await res?.json().catch(() => null);
        alert(err?.message || 'Lỗi khi reset dữ liệu!');
      }
    } catch (e) {
      console.error('Lỗi reset dữ liệu:', e);
      alert('Lỗi kết nối máy chủ khi reset dữ liệu!');
    } finally {
      setIsResettingData(false);
    }
  };

  const userBots = filterActiveBots(myBots);
  const activeBots = userBots;
  const botsCount = userBots.length;

  const isPvP = activeTab === 'PVP';

  // Per-Bot Rating: Lấy ELO cao nhất của dàn bot đang sở hữu (mặc định 1200 nếu chưa có bot)
  const highestBotElo = userBots.length > 0 ? Math.max(...userBots.map((b) => b.elo || 1200)) : 1200;
  const activeLadderBot = userBots.find((b) => b.ladderEnabled) || null;

  // 1. Thống Kê PvP Rank Độc Lập (Chỉ gom các trận có isRanked === true vào công thức Tổng số trận = W + D + L)
  const localPvpRankMatches = (matchHistory || matches || []).filter((m) => {
    const isPvp = (m.mode || 'PVP').toUpperCase() === 'PVP' || (m.mode || '').toUpperCase() === 'PVP_QUICK';
    if (!isPvp) return false;
    if (m.isRanked !== undefined) return Boolean(m.isRanked);
    return Boolean(m.change && m.change !== 0) && m.mode !== 'PRACTICE' && m.mode !== 'CASUAL';
  });
  const pvpWins = userStats?.pvpWins ?? localPvpRankMatches.filter((m) => m.userWon === true).length;
  const pvpLosses = userStats?.pvpLosses ?? localPvpRankMatches.filter((m) => m.userWon === false && m.winner !== 'DRAW').length;
  const pvpTotal = userStats?.pvpMatchesCount ?? (pvpWins + pvpLosses + localPvpRankMatches.filter((m) => m.winner === 'DRAW').length);
  const pvpDraws = Math.max(0, pvpTotal - pvpWins - pvpLosses);
  const pvpWinRate = pvpTotal > 0 ? Math.round((pvpWins / pvpTotal) * 100) : 0;
  const pvpRank = user?.pvpRank || userStats?.pvpRank || user?.rank || null;

  // 2. Thống Kê Bot Độc Lập (Tổng hợp từ dàn bot của user)
  const totalBotMatches = userBots.reduce((sum, b) => sum + (b.games ?? b.matchesCount ?? b.matches ?? 0), 0);
  const totalBotWins = userBots.reduce((sum, b) => sum + (b.wins ?? 0), 0);
  const effectiveBotMatches = totalBotMatches > 0 ? totalBotMatches : (userStats?.botMatchesCount || 0);
  const effectiveBotWins = totalBotWins > 0 ? totalBotWins : (userStats?.botWins || 0);
  const averageBotWinRate = effectiveBotMatches > 0
    ? Math.round((effectiveBotWins / effectiveBotMatches) * 100)
    : (userBots.length > 0
        ? Math.round(userBots.reduce((sum, b) => sum + (b.winRate ?? 0), 0) / userBots.length)
        : 0);

  // 3. Thống Kê Giải Đấu
  const activeTournaments = myTournaments.filter(
    (t) => t.tournamentStatus === 'RUNNING' || t.tournamentStatus === 'REGISTRATION_OPEN' || t.status === 'RUNNING'
  ).length;
  const completedTournaments = myTournaments.filter(
    (t) => t.tournamentStatus === 'FINISHED' || t.tournamentStatus === 'COMPLETED' || t.status === 'FINISHED' || t.status === 'COMPLETED'
  ).length;

  // PHÂN TÁCH TRIỆT ĐỂ THEO TAB: Lọc mảng theo activeTab và lấy đúng 10 trận mới nhất
  const sortedHistory = [...(matchHistory || [])].sort((a, b) => {
    const timeB = new Date(b.createdAt || b.playedAt || 0).getTime();
    const timeA = new Date(a.createdAt || a.playedAt || 0).getTime();
    if (!isNaN(timeB) && !isNaN(timeA) && timeB !== timeA) {
      return timeB - timeA;
    }
    return (Number(b.id) || 0) - (Number(a.id) || 0);
  });
  const displayHistory = sortedHistory
    .filter(
      (m) =>
        (m.mode || '').toUpperCase() === activeTab ||
        (activeTab === 'PVP' && ((m.mode || '').toUpperCase() === 'PVP_QUICK' || (m.mode || '').toUpperCase() === 'CASUAL' || (m.mode || '').toUpperCase() === 'PRACTICE')) ||
        (activeTab === 'BOT' && (m.mode || '').toUpperCase() === 'LADDER')
    )
    .slice(0, 10);

  return (
    <div className="max-w-3xl mx-auto space-y-6 animate-fadeIn">
      {/* Thẻ Thông Tin Người Dùng */}
      <div className="bg-zinc-850 p-8 rounded-3xl border border-zinc-750 text-center space-y-5 shadow-2xl">
        <img
          src={`https://api.dicebear.com/7.x/bottts/svg?seed=${user?.displayName || user?.studentCode || 'User'}`}
          alt="avatar"
          className="w-24 h-24 mx-auto rounded-3xl bg-zinc-900 p-2 border-2 border-emerald-500 shadow-lg"
        />
        <div>
          <h2 className="text-2xl font-black text-white">{user?.displayName || user?.studentCode || 'Người chơi'}</h2>
        </div>

        {/* 4 Thẻ Thông Tin Tài Khoản Chuẩn Xác */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-left text-xs">
          <div className="bg-zinc-900/90 p-3.5 rounded-2xl border border-zinc-800">
            <span className="text-zinc-500 block text-[11px] font-medium">👤 Tên đăng nhập</span>
            <span className="font-bold text-white text-sm font-mono mt-0.5 block truncate">{username}</span>
          </div>

          <div className="bg-zinc-900/90 p-3.5 rounded-2xl border border-zinc-800 flex items-center justify-between">
            <div className="flex-1 mr-2 min-w-0">
              <span className="text-zinc-500 block text-[11px] font-medium">🎓 Mã SV</span>
              {isEditingStudentCode ? (
                <div className="flex items-center gap-1.5 mt-1">
                  <input
                    type="text"
                    value={studentCodeInput}
                    onChange={(e) => setStudentCodeInput(e.target.value)}
                    placeholder="Nhập Mã SV"
                    className="bg-zinc-950 border border-emerald-500 text-white rounded-lg px-2 py-1 text-xs w-full focus:outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => {
                      if (onUpdateStudentCode && studentCodeInput.trim()) {
                        onUpdateStudentCode(studentCodeInput.trim());
                      }
                      setIsEditingStudentCode(false);
                    }}
                    className="px-2 py-1 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg text-xs font-bold"
                    title="Lưu"
                  >
                    ✓
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setStudentCodeInput(user?.studentCode || '');
                      setIsEditingStudentCode(false);
                    }}
                    className="px-2 py-1 bg-zinc-800 hover:bg-zinc-750 text-zinc-400 rounded-lg text-xs font-bold"
                    title="Hủy"
                  >
                    ✕
                  </button>
                </div>
              ) : (
                <span className="font-bold text-emerald-400 text-sm font-mono mt-0.5 block truncate">
                  {user?.studentCode || 'Chưa cập nhật'}
                </span>
              )}
            </div>
            {!isEditingStudentCode && (
              <button
                type="button"
                onClick={() => {
                  setStudentCodeInput(user?.studentCode || '');
                  setIsEditingStudentCode(true);
                }}
                className="p-1.5 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-zinc-400 hover:text-white transition-colors text-xs flex-shrink-0"
                title="Cập nhật Mã SV"
              >
                ✏️
              </button>
            )}
          </div>

          <div className="bg-zinc-900/90 p-3.5 rounded-2xl border border-zinc-800">
            <span className="text-zinc-500 block text-[11px] font-medium">✉️ Email</span>
            <span className="font-bold text-white text-sm font-mono mt-0.5 block truncate">{email}</span>
          </div>

          <div className="bg-zinc-900/90 p-3.5 rounded-2xl border border-zinc-800">
            <span className="text-zinc-500 block text-[11px] font-medium">🛡️ Vai trò</span>
            <span className="font-bold text-emerald-400 text-sm font-mono mt-0.5 block truncate">{role}</span>
          </div>
        </div>

        {/* Nút Quản Trị: Reset Toàn Bộ Dữ Liệu Test (Chỉ hiển thị cho ADMIN) */}
        {role === 'ADMIN' && (
          <div className="pt-1">
            <button
              type="button"
              disabled={isResettingData}
              onClick={handleResetTestData}
              className="px-5 py-2.5 bg-red-950/70 hover:bg-red-900/90 border border-red-500/50 hover:border-red-400 text-red-200 hover:text-white rounded-2xl text-xs font-bold font-mono transition-all flex items-center justify-center gap-2 mx-auto cursor-pointer shadow-lg shadow-red-950/40 disabled:opacity-50"
            >
              <span className="text-sm">⚠️</span>
              <span>{isResettingData ? 'Đang thực hiện reset...' : 'Reset Dữ Liệu Test (Admin)'}</span>
            </button>
          </div>
        )}

        {/* 3 Huy Hiệu / Tab Chuyển Đổi Dữ Liệu Độc Lập */}
        <div className="flex flex-wrap items-center justify-center gap-4 pt-2">
          <button
            type="button"
            onClick={() => setActiveTab('PVP')}
            className={`px-5 py-2.5 rounded-2xl font-mono font-bold text-base shadow-inner flex items-center gap-2.5 transition-all cursor-pointer ${
              isPvP
                ? 'bg-blue-950/80 border-2 border-blue-400 text-blue-200 ring-4 ring-blue-500/25 shadow-lg scale-105'
                : 'bg-zinc-900/60 border border-zinc-800 text-zinc-400 hover:border-blue-500/40 hover:text-blue-300 opacity-70 hover:opacity-100'
            }`}
          >
            <span className="text-xl">🎮</span>
            <span>PvP Rating:</span>
            <span className="text-white text-lg font-black tabular-nums">{pvpElo} ELO</span>
            {isPvP && <span className="w-2 h-2 rounded-full bg-blue-400 animate-pulse ml-1" />}
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('BOT')}
            className={`px-5 py-2.5 rounded-2xl font-mono font-bold text-base shadow-inner flex items-center gap-2.5 transition-all cursor-pointer ${
              activeTab === 'BOT'
                ? 'bg-emerald-950/80 border-2 border-emerald-400 text-emerald-200 ring-4 ring-emerald-500/25 shadow-lg scale-105'
                : 'bg-zinc-900/60 border border-zinc-800 text-zinc-400 hover:border-emerald-500/40 hover:text-emerald-300 opacity-70 hover:opacity-100'
            }`}
          >
            <span className="text-xl">🤖</span>
            <span>Bot Rating:</span>
            <span className="text-white text-lg font-black tabular-nums">{highestBotElo} ELO</span>
            {activeTab === 'BOT' && <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse ml-1" />}
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('TOURNAMENTS')}
            className={`px-5 py-2.5 rounded-2xl font-mono font-bold text-base shadow-inner flex items-center gap-2.5 transition-all cursor-pointer ${
              activeTab === 'TOURNAMENTS'
                ? 'bg-amber-950/80 border-2 border-amber-400 text-amber-200 ring-4 ring-amber-500/25 shadow-lg scale-105'
                : 'bg-zinc-900/60 border border-zinc-800 text-zinc-400 hover:border-amber-500/40 hover:text-amber-300 opacity-70 hover:opacity-100'
            }`}
          >
            <span className="text-xl">🏆</span>
            <span>Giải Đấu:</span>
            <span className="text-white text-lg font-black tabular-nums">{myTournaments.length}</span>
            {activeTab === 'TOURNAMENTS' && <span className="w-2 h-2 rounded-full bg-amber-400 animate-pulse ml-1" />}
          </button>
        </div>

        {/* 4 Chỉ Số Thống Kê Tách Biệt Theo Từng Tab */}
        {activeTab === 'PVP' && (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-4 border-t border-zinc-800 text-left text-xs animate-fadeIn">
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Tổng số trận PvP Rank</span>
              <span className="font-bold text-white text-base font-mono tabular-nums">
                {pvpTotal} trận
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Kết quả PvP Rank</span>
              <span className="font-bold text-emerald-400 text-base font-mono tabular-nums">
                {pvpWins}W / {pvpDraws}D / {pvpLosses}L
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Tỉ lệ thắng PvP Rank</span>
              <span className="font-bold text-white text-base font-mono tabular-nums">
                {pvpWinRate}%
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Thứ hạng PvP Rank</span>
              <span className="font-bold text-blue-400 text-base font-mono tabular-nums">
                Hạng #{pvpRank || '-'}
              </span>
            </div>
          </div>
        )}

        {activeTab === 'BOT' && (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-4 border-t border-zinc-800 text-left text-xs animate-fadeIn">
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Số bot sở hữu</span>
              <span className="font-bold text-white text-base font-mono tabular-nums">
                {userBots.length} / 5 bot
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Bot đại diện</span>
              <span
                className="font-bold text-emerald-400 text-base font-mono truncate block"
                title={activeLadderBot?.name || 'Chưa chọn'}
              >
                {activeLadderBot?.name || 'Chưa chọn'}
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Tổng trận đấu Bot</span>
              <span className="font-bold text-white text-base font-mono tabular-nums">
                {effectiveBotMatches} trận
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Tỉ lệ thắng trung bình</span>
              <span className="font-bold text-amber-400 text-base font-mono tabular-nums">
                {averageBotWinRate}%
              </span>
            </div>
          </div>
        )}

        {activeTab === 'TOURNAMENTS' && (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-4 border-t border-zinc-800 text-left text-xs animate-fadeIn">
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Tổng giải tham gia</span>
              <span className="font-bold text-white text-base font-mono tabular-nums">
                {myTournaments.length} giải
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Đang diễn ra</span>
              <span className="font-bold text-indigo-400 text-base font-mono tabular-nums">
                {activeTournaments} giải
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Đã kết thúc</span>
              <span className="font-bold text-sky-400 text-base font-mono tabular-nums">
                {completedTournaments} giải
              </span>
            </div>
            <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-800">
              <span className="text-zinc-500 block text-[11px]">Trạng thái</span>
              <span className="font-bold text-emerald-400 text-base font-mono">
                {myTournaments.length > 0 ? 'Đang tranh tài' : 'Chưa ghi danh'}
              </span>
            </div>
          </div>
        )}
      </div>

      {/* Danh Sách Bot Sở Hữu (MAX_BOTS = 5) khi xem Tab Bot */}
      {activeTab === 'BOT' && (
        <div className="bg-zinc-850 p-6 rounded-3xl border border-zinc-750 space-y-3 shadow-xl">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="text-lg">🤖</span>
              <h3 className="text-sm font-extrabold text-white">
                Danh Sách Bot Của Tôi ({activeBots.length} / 5 Bot)
              </h3>
            </div>
            {activeBots.length > 5 && onCleanBots && (
              <button
                type="button"
                onClick={onCleanBots}
                className="px-2.5 py-1 rounded-lg bg-amber-500/20 hover:bg-amber-500/30 text-amber-200 border border-amber-500/40 font-semibold text-xs transition-all flex items-center gap-1.5"
              >
                <span>🧹</span> Giữ lại 5 Bot mới nhất & Xóa các Bot thừa
              </button>
            )}
          </div>

          {activeBots.length >= 5 && (
            <div className="p-3 bg-amber-950/40 border border-amber-500/30 rounded-2xl text-amber-200 text-xs flex items-center gap-2">
              <span>⚠️</span>
              <span>
                <strong>Bạn đã đạt giới hạn tối đa 5 Bot.</strong> Vui lòng xóa bớt các bot cũ hoặc chọn Cập nhật phiên bản trên bot hiện có!
              </span>
            </div>
          )}

          {activeBots.length === 0 ? (
            <p className="text-xs text-zinc-500 py-4 text-center">
              Bạn chưa tạo bot nào. Hãy vào mục Battle Code để tạo bot đầu tiên!
            </p>
          ) : (
            <div className="divide-y divide-zinc-800 text-xs">
              {activeBots.map((b, idx) => (
                <div key={b.id} className="py-2.5 flex items-center justify-between">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <span className="w-5 text-center font-bold text-zinc-500 flex-shrink-0">#{idx + 1}</span>
                    <button
                      type="button"
                      onClick={() => setSelectedBotIdForDetail(b.id)}
                      className="text-white font-bold hover:text-emerald-400 flex items-center gap-1.5 transition-colors underline decoration-dotted underline-offset-2 text-left truncate"
                      title="Xem hồ sơ & thông kê chi tiết Bot"
                    >
                      <span>🤖</span>
                      <span className="truncate">{b.name}</span>
                      <span className="text-amber-400 font-mono font-bold">({b.elo ?? 1200} ELO)</span>
                    </button>
                    <span className="text-zinc-500 text-[11px] font-mono flex-shrink-0">ID: {b.id}</span>
                  </div>
                  <div className="flex items-center gap-2 flex-shrink-0">
                    <button
                      type="button"
                      onClick={() => onToggleLadderActive && onToggleLadderActive(b.id, !b.ladderEnabled)}
                      className={`px-2.5 py-1 rounded-lg text-xs font-bold transition-all flex items-center gap-1 border ${
                        b.ladderEnabled
                          ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40 hover:bg-emerald-500/30'
                          : 'bg-zinc-800 text-zinc-400 border-zinc-700 hover:text-white'
                      }`}
                      title={b.ladderEnabled ? 'Bấm để tắt đấu Ranked' : 'Bấm để chọn bot này đi đấu Ranked'}
                    >
                      <span>{b.ladderEnabled ? '🟢 Đang Đấu Ranked' : '⚪ Vào Đấu Ranked'}</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setSelectedBotIdForDetail(b.id)}
                      className="px-2.5 py-1 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-xs font-semibold transition-colors flex items-center gap-1"
                      title="Xem chi tiết bot"
                    >
                      📊 Chi tiết
                    </button>
                    {onDeleteBot && (
                      <button
                        type="button"
                        onClick={() => {
                          if (window.confirm(`Bạn có chắc muốn xóa bot "${b.name}"?`)) {
                            onDeleteBot(b.id);
                          }
                        }}
                        className="px-2.5 py-1 rounded-lg bg-rose-950/60 hover:bg-rose-900/80 text-rose-300 border border-rose-800/60 text-xs font-bold transition-colors flex items-center gap-1"
                        title="Xóa bot"
                      >
                        🗑️ Xóa
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* KHU VỰC GIẢI ĐẤU ĐÃ THAM GIA */}
      {activeTab === 'TOURNAMENTS' && (
        <div className="bg-zinc-850 p-6 rounded-3xl border border-zinc-750 space-y-4 shadow-xl">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-extrabold text-white flex items-center gap-2">
              <span>🏆</span>
              <span>Giải Đấu Đã Tham Gia ({myTournaments.length})</span>
            </h3>
            <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded-full border bg-amber-500/10 text-amber-400 border-amber-500/30">
              Tournament Arena
            </span>
          </div>

          {isLoadingTournaments ? (
            <div className="py-8 text-center text-zinc-500 text-xs animate-pulse">
              Đang tải danh sách giải đấu đã tham gia...
            </div>
          ) : myTournaments.length === 0 ? (
            <div className="text-center py-8 text-zinc-500 text-xs space-y-2">
              <p>Bạn chưa đăng ký tham gia giải đấu nào.</p>
              <p className="text-[11px] text-zinc-600">
                Hãy chuyển sang trang Giải Đấu để đăng ký đội và lập trình bot chinh phục bảng vàng!
              </p>
            </div>
          ) : (
            <div className="divide-y divide-zinc-800 text-xs">
              {myTournaments.map((t, idx) => {
                const statusMeta = (() => {
                  switch (t.tournamentStatus) {
                    case 'REGISTRATION_OPEN':
                      return { text: 'Đang mở ĐK', color: 'text-emerald-400 border-emerald-500/30 bg-emerald-500/10' };
                    case 'RUNNING':
                      return { text: 'Đang diễn ra', color: 'text-indigo-400 border-indigo-500/30 bg-indigo-500/10' };
                    case 'COMPLETED':
                    case 'FINISHED':
                      return { text: 'Đã hoàn thành', color: 'text-sky-400 border-sky-500/30 bg-sky-500/10' };
                    default:
                      return { text: t.tournamentStatus || 'Đang đấu', color: 'text-zinc-400 border-zinc-700 bg-zinc-800' };
                  }
                })();

                return (
                  <div key={t.tournamentId || idx} className="py-3 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-white text-sm">{t.tournamentTitle}</span>
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded border ${statusMeta.color}`}>
                          {statusMeta.text}
                        </span>
                      </div>
                      <div className="text-zinc-400 text-xs flex flex-wrap items-center gap-x-3 gap-y-1">
                        <span>🛡️ Đội: <strong className="text-zinc-200">{t.teamName}</strong></span>
                        {t.botId ? (
                          <button
                            type="button"
                            onClick={() => setSelectedBotIdForDetail(t.botId)}
                            className="text-zinc-300 hover:text-emerald-400 underline decoration-dotted underline-offset-2 flex items-center gap-1 transition-colors"
                            title="Xem chi tiết Bot"
                          >
                            <span>🤖</span>
                            <span>{t.botName || 'Bot'} (v{t.versionNo || 1})</span>
                          </button>
                        ) : (
                          <span>🤖 {t.botName || 'Chưa gắn bot'}</span>
                        )}
                        <span>Thể thức: <strong className="text-zinc-300">{t.tournamentFormat || 'Tiêu chuẩn'}</strong></span>
                      </div>
                    </div>

                    <div className="flex items-center gap-3 self-end sm:self-center font-mono">
                      <div className="text-right">
                        <span className="text-[11px] text-zinc-500 block">Thành tích / Hạng</span>
                        <span className="font-bold text-emerald-400 text-sm">
                          {t.seed ? `#${t.seed}` : (t.score != null ? `${t.score} pts` : t.teamStatus || 'Đã ghi danh')}
                        </span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* 1 KHU VỰC DANH SÁCH LỊCH SỬ ĐẤU DUY NHẤT DÀNH CHO TAB PVP */}
      {activeTab === 'PVP' && (
        <div className="bg-zinc-850 p-6 rounded-3xl border border-zinc-750 space-y-3 shadow-xl">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-extrabold text-white flex items-center gap-2">
              <span>📜</span>
              <span>Lịch Sử Đấu PvP (10 trận gần đây)</span>
            </h3>
            <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded-full border bg-blue-500/10 text-blue-400 border-blue-500/30">
              🎮 Chế độ PvP
            </span>
          </div>

          {displayHistory.length === 0 ? (
            <p className="text-xs text-zinc-500 py-6 text-center">
              Chưa có trận đấu PvP nào được ghi nhận. Hãy vào phòng PvP hoặc gạ kèo để tích lũy ELO!
            </p>
          ) : (
            <div className="max-h-72 overflow-y-auto divide-y divide-zinc-800 text-xs">
              {displayHistory.map((item, idx) => {
                const opponentName = item.opponent || item.sideB?.botName || 'Đối thủ';
                const opponentElo = item.opponentElo || 1200;
                const userWon = item.userWon ?? (item.winner === 'USER' || item.winner === 'A');
                const isItemRanked = item.isRanked !== undefined
                  ? Boolean(item.isRanked)
                  : (item.change !== 0 && item.eloChange !== 0 && item.mode !== 'PRACTICE' && item.mode !== 'CASUAL');
                const change = item.change ?? item.eloChange ?? (isItemRanked ? (userWon ? 16 : -16) : 0);
                const itemNewElo = item.newElo ?? pvpElo;
                let formattedTimestamp = item.timestamp || '';
                if (item.createdAt) {
                  const d = new Date(item.createdAt);
                  if (!isNaN(d.getTime()) && d.getFullYear() > 1970) {
                    const day = String(d.getDate()).padStart(2, '0');
                    const month = String(d.getMonth() + 1).padStart(2, '0');
                    const year = d.getFullYear();
                    const hours = String(d.getHours()).padStart(2, '0');
                    const minutes = String(d.getMinutes()).padStart(2, '0');
                    const seconds = String(d.getSeconds()).padStart(2, '0');
                    formattedTimestamp = `${day}/${month}/${year} ${hours}:${minutes}:${seconds}`;
                  }
                }
                const timestamp = formattedTimestamp;

                return (
                  <div key={item.id || idx} className="py-2.5 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <span className={`w-2 h-2 rounded-full ${userWon ? 'bg-emerald-400' : 'bg-rose-400'}`} />
                      <span className="text-white font-bold">{opponentName}</span>
                      <span className="text-zinc-500 text-[11px]">({opponentElo} ELO)</span>
                      <span
                        className={`text-[10px] px-1.5 py-0.5 rounded font-bold border ${
                          isItemRanked
                            ? 'bg-blue-500/10 text-blue-400 border-blue-500/30'
                            : 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                        }`}
                      >
                        {isItemRanked ? 'PvP Rank' : 'Giao Hữu'}
                      </span>
                    </div>

                    <div className="flex items-center gap-3">
                      <div className="flex items-center gap-3 font-mono">
                        {isItemRanked ? (
                          <span className={`font-bold tabular-nums ${userWon ? 'text-emerald-400' : 'text-rose-400'}`}>
                            {change > 0 ? `+${change}` : `${change}`} ELO
                          </span>
                        ) : (
                          <span className="font-bold tabular-nums text-zinc-400">
                            0 ELO
                          </span>
                        )}
                        <span className="text-zinc-400 tabular-nums">{itemNewElo} ELO</span>
                        {timestamp && (
                          <span className="text-zinc-600 text-[10px] hidden sm:inline">{timestamp}</span>
                        )}
                      </div>
                      {onOpenReplay && (
                        <button
                          type="button"
                          onClick={() => onOpenReplay(item, item.replayData || item.replay || null)}
                          className="px-2.5 py-1 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-xs font-semibold text-zinc-200 transition-colors flex items-center gap-1"
                        >
                          <span>▶️</span>
                          <span>Xem Replay</span>
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* Modal Chi Tiết Bot */}
      {selectedBotIdForDetail && (
        <BotDetailModal
          botId={selectedBotIdForDetail}
          currentUser={user}
          onClose={() => setSelectedBotIdForDetail(null)}
          onOpenReplay={onOpenReplay}
        />
      )}
    </div>
  );
}
