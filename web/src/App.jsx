import React, { useEffect, useState, useCallback } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import Layout from './components/Layout.jsx';
import GameModes from './components/GameModes.jsx';
import BattleCode from './components/BattleCode.jsx';
import Leaderboard from './components/Leaderboard.jsx';
import Tournaments from './components/Tournaments.jsx';
import Profile from './components/Profile.jsx';
import VsScreenModal from './components/VsScreenModal.jsx';
import SignIn from './components/auth/SignIn.jsx';
import SignUp from './components/auth/SignUp.jsx';
import {
  DEFAULT_HEADERS,
  fetchWithTimeout,
  checkBackendOnline,
  registerForceLogoutHandler,
  api,
  csrfHeaders,
  buildApiUrl,
  buildWsUrl,
  createStompWebSocket,
} from './utils/api.js';
import { simulateMatch } from './utils/matchSimulator.js';
import {
  addDeletedBotId,
  addDeletedBotIds,
  filterActiveBots,
  getBotSource,
  saveBotSource,
} from './utils/botStorage.js';
import { createRoomApi } from './utils/roomApi.js';
import SockJS from 'sockjs-client/dist/sockjs';
import { Client } from '@stomp/stompjs';

function getMatchCacheKey(u) {
  const id = u?.username || (u?.email ? u.email.split('@')[0] : '') || (u?.id ? String(u.id) : '');
  return id ? `ottv2_match_history_${id}` : null;
}

const DEFAULT_OFFLINE_USER = {
  studentCode: 'B22DCCN000',
  displayName: 'Kỹ Sư Ngoại Tuyến (Offline)',
  email: 'offline.engineer@ptit.edu.vn',
  role: 'STUDENT',
  pvpElo: 1200,
  botElo: 1200,
  isOffline: true,
};

function AppContent() {
  const navigate = useNavigate();

  const [user, setUser] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [authMode, setAuthMode] = useState('login');
  const [authEmail, setAuthEmail] = useState('');
  const [authMessage, setAuthMessage] = useState('');

  // Progressive Ladder ELO States: Split into pvpElo and botElo
  const [pvpElo, setPvpElo] = useState(1200);
  const [botElo, setBotElo] = useState(1200);

  const [eloToast, setEloToast] = useState(null);

  // Auto-dismiss eloToast after duration (default 4000ms)
  useEffect(() => {
    if (!eloToast) return undefined;
    const duration = eloToast.autoClose || eloToast.duration || 4000;
    const timer = setTimeout(() => {
      setEloToast(null);
    }, duration);
    return () => clearTimeout(timer);
  }, [eloToast]);

  // Modals state
  const [isLocalGameOpen, setIsLocalGameOpen] = useState(false);
  const [localTimeControl, setLocalTimeControl] = useState(null);
  const [pvpGameConfig, setPvpGameConfig] = useState(null); // { target, timeControl }
  const [roomGameConfig, setRoomGameConfig] = useState(null); // { roomCode, isHost }
  const [incomingChallenge, setIncomingChallenge] = useState(null);
  const [vsMatchInfo, setVsMatchInfo] = useState(null);

  const handleCloseRoomGame = useCallback(() => {
    setRoomGameConfig(null);
    try {
      const raw = sessionStorage.getItem('ottv2_pending_elo_toast');
      if (raw) {
        sessionStorage.removeItem('ottv2_pending_elo_toast');
        const toastData = JSON.parse(raw);
        setEloToast(toastData);
      }
    } catch {}
  }, []);

  // App data
  const [bots, setBots] = useState([]);
  const [submissions] = useState([]);
  const [matches, setMatches] = useState([]);
  const [challengeTarget, setChallengeTarget] = useState(null);
  const [eloHistory, setEloHistory] = useState([]);
  const [serverStats, setServerStats] = useState({
    matchesCount: 0,
    wins: 0,
    losses: 0,
    winRate: 0,
    pvpMatchesCount: 0,
    pvpWins: 0,
    pvpLosses: 0,
    pvpDistinctOpponents: 0,
    botMatchesCount: 0,
    botWins: 0,
    botLosses: 0,
  });

  // Replay
  const [selectedMatch, setSelectedMatch] = useState(null);
  const [replayData, setReplayData] = useState(null);

  const [isBackendOffline, setIsBackendOffline] = useState(false);

  // Synchronized userStats
  const localBotMatches = matches.filter(
    (m) => (m.mode || '').toUpperCase() === 'BOT' || (m.mode || '').toUpperCase() === 'LADDER'
  );

  const localBotWins = localBotMatches.filter((m) => m.winner === 'USER' || m.winner === 'A').length;
  const localBotLosses = localBotMatches.filter(
    (m) => m.winner && m.winner !== 'USER' && m.winner !== 'A' && m.winner !== 'DRAW'
  ).length;

  // Ranked PvP stats come from backend records only. Local/reported results are
  // not authoritative and must not affect the PvP leaderboard.
  const pvpWins = serverStats.pvpWins || 0;
  const pvpLosses = serverStats.pvpLosses || 0;
  const pvpMatchesCount = Math.max(serverStats.pvpMatchesCount || 0, pvpWins + pvpLosses);
  const pvpDistinctOpponents = serverStats.pvpDistinctOpponents || 0;

  const botWins = Math.max(serverStats.botWins || 0, localBotWins);
  const botLosses = Math.max(serverStats.botLosses || 0, localBotLosses);
  const botMatchesCount = botWins + botLosses;

  const totalWins = pvpWins + botWins;
  const totalLosses = pvpLosses + botLosses;
  const totalMatchesCount = totalWins + totalLosses;

  const userStats = {
    pvpElo,
    botElo,
    elo: pvpElo,
    matchesCount: totalMatchesCount,
    wins: totalWins,
    losses: totalLosses,
    winRate: totalMatchesCount > 0 ? Math.round((totalWins / totalMatchesCount) * 100) : 0,
    botsCount: bots.length,
    eloHistory,
    pvpMatchesCount,
    pvpWins,
    pvpLosses,
    pvpDistinctOpponents,
    botMatchesCount,
    botWins,
    botLosses,
    distinctOpponents: serverStats.distinctOpponents || 0,
    isProvisional: serverStats.isProvisional ?? ((pvpMatchesCount < 10) && (pvpDistinctOpponents < 5)),
  };

  const handleForceLogout = useCallback((message = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại!') => {
    setUser(null);
    setMatches([]);
    setEloHistory([]);
    setSelectedMatch(null);
    try {
      localStorage.removeItem('ottv2_last_active_user');
      localStorage.removeItem('ottv2_match_history');
      sessionStorage.clear();
    } catch {}
    setAuthMessage(message);
    setEloToast({
      type: 'LOSE',
      text: `⚠️ ${message}`,
    });
  }, []);

  useEffect(() => {
    registerForceLogoutHandler(handleForceLogout);
    const handleAuthExpired = (e) => {
      const message = e?.detail?.message || 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại!';
      handleForceLogout(message);
    };
    window.addEventListener('ottv2_auth_expired', handleAuthExpired);
    return () => {
      registerForceLogoutHandler(null);
      window.removeEventListener('ottv2_auth_expired', handleAuthExpired);
    };
  }, [handleForceLogout]);

  useEffect(() => {
    const handleOfflineEvent = (e) => {
      setIsBackendOffline(Boolean(e.detail?.offline));
    };
    window.addEventListener('ottv2_backend_offline', handleOfflineEvent);
    return () => {
      window.removeEventListener('ottv2_backend_offline', handleOfflineEvent);
    };
  }, []);

  // Lắng nghe STOMP notifications cá nhân (/topic/user/{username}) để nhận lời mời thách đấu thời gian thực
  useEffect(() => {
    if (!user || user.isOffline) return undefined;
    const topicKey = user.username || user.studentCode || (user.email ? user.email.split('@')[0] : '') || (user.id ? String(user.id) : null);
    if (!topicKey) return undefined;

    let isSubscribed = true;
    let stompClient = null;

    try {
      stompClient = new Client({
        webSocketFactory: () => createStompWebSocket('/api/ws'),
        connectHeaders: {
          'ngrok-skip-browser-warning': 'true',
        },
        reconnectDelay: 5000,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
        debug: () => {},
      });

      stompClient.onConnect = () => {
        if (!isSubscribed) return;

        const handleInvite = (message) => {
          try {
            const data = JSON.parse(message.body);
            if (data?.type === 'CHALLENGE_INVITE') {
              setIncomingChallenge(data);
            } else if (data?.type === 'MATCH_FOUND') {
              window.dispatchEvent(new CustomEvent('ottv2_match_found', { detail: data }));
              setVsMatchInfo(data);
            } else if (data?.type === 'MATCH_FINISHED') {
              // Nhận kết quả trận đấu bot chính thức từ máy chủ - cách ly hoàn toàn giải đấu khỏi Ranked ELO
              const isTournament = data.matchType === 'TOURNAMENT' || data.mode === 'TOURNAMENT';
              const isRanked = data.isRanked !== false && !isTournament;
              if (isRanked && data.sideA && data.sideB) {
                setBots((prevBots) => {
                  const updated = prevBots.map((b) => {
                    if (String(b.id) === String(data.sideA?.botId)) {
                      return { ...b, elo: data.sideA.eloAfter ?? b.elo };
                    }
                    if (String(b.id) === String(data.sideB?.botId)) {
                      return { ...b, elo: data.sideB.eloAfter ?? b.elo };
                    }
                    return b;
                  });
                  try {
                    const userKey = user?.email ? `ottv2_bots_${user.email}` : 'ottv2_user_bots';
                    localStorage.setItem(userKey, JSON.stringify(updated));
                  } catch {}
                  return updated;
                });
              }

              // MATCH_FINISHED là nguồn kết quả/replay có thẩm quyền. MATCH_FOUND
              // chỉ là thông báo ghép trận, không được tạo replay hoặc ELO mô phỏng.
              if (data.mode === 'BOT' && data.matchPublicId) {
                // Cập nhật ngay kết quả và ELO mới nhất vào selectedMatch nếu đang mở modal xem trực tiếp
                setSelectedMatch((prev) => {
                  if (!prev) return null;
                  const prevId = prev.matchPublicId || prev.publicId || prev.id;
                  if (String(prevId) !== String(data.matchPublicId)) return prev;
                  return {
                    ...prev,
                    sideA: data.sideA || prev.sideA,
                    sideB: data.sideB || prev.sideB,
                    winner: data.winnerSide || prev.winner,
                    winnerSide: data.winnerSide || prev.winnerSide,
                    endReason: data.endReason || prev.endReason,
                    totalTurns: data.totalTurns || prev.totalTurns,
                  };
                });

                (async () => {
                  try {
                    const response = await api(`/api/matches/${data.matchPublicId}/replay`);
                    if (!response?.ok) return;
                    const replay = await response.json();
                    const turns = replay?.turns || replay?.moves || replay?.movesHistory || [];
                    const hasInitialStateAndResult =
                      Array.isArray(replay?.initialPieces) && replay.initialPieces.length > 0 && Boolean(replay?.result);
                    if (!replay || (turns.length === 0 && !hasInitialStateAndResult) || replay.matchId !== data.matchPublicId) return;

                    const match = {
                      id: data.matchPublicId,
                      matchPublicId: data.matchPublicId,
                      matchId: data.matchId,
                      mode: 'BOT',
                      matchType: data.matchType,
                      isRanked: isRanked,
                      isLiveSpectator: true,
                      sideA: data.sideA,
                      sideB: data.sideB,
                      winner: data.winnerSide || 'DRAW',
                      winnerSide: data.winnerSide || 'DRAW',
                      endReason: data.endReason,
                      totalTurns: data.totalTurns,
                    };
                    setSelectedMatch(match);
                    setReplayData(replay);
                  } catch (error) {
                    console.warn('Không tải được replay chính thức của trận bot:', error);
                  }
                })();
              }
            }
          } catch (e) {
            console.error('Lỗi parse gói tin thông báo STOMP:', e);
          }
        };

        stompClient.subscribe(`/topic/user/${topicKey}`, handleInvite);

        if (user.studentCode && user.studentCode !== topicKey) {
          stompClient.subscribe(`/topic/user/${user.studentCode}`, handleInvite);
        }
      };

      stompClient.activate();
    } catch (err) {
      console.warn('Lỗi khởi tạo STOMP Notification Client:', err);
    }

    return () => {
      isSubscribed = false;
      if (stompClient) {
        try {
          stompClient.deactivate();
        } catch {}
      }
    };
  }, [user?.username, user?.studentCode, user?.email, user?.id, user?.isOffline]);

  // Lắng nghe sự kiện MATCH_FOUND toàn cục (từ WebSocket hoặc MatchmakingModal)
  useEffect(() => {
    const handleMatchFound = (e) => {
      if (e?.detail) {
        setVsMatchInfo(e.detail);
      }
    };
    window.addEventListener('ottv2_match_found', handleMatchFound);
    return () => {
      window.removeEventListener('ottv2_match_found', handleMatchFound);
    };
  }, []);

  // Recover queue matches when the one-time STOMP notification was sent before
  // this client finished subscribing or while its WebSocket was reconnecting.
  useEffect(() => {
    if (!user?.id || user?.isOffline) return undefined;
    let active = true;
    let requestInFlight = false;
    const pollQueueStatus = async () => {
      if (!active || requestInFlight) return;
      requestInFlight = true;
      try {
        const response = await fetchWithTimeout('/api/matchmaking/status', {}, 5000);
        if (!response || !response.ok) return;
        const data = await response.json();
        if (active && data?.matchFound) {
          window.dispatchEvent(new CustomEvent('ottv2_match_found', { detail: data.matchFound }));
        }
      } catch {
        // STOMP remains the primary notification path; polling is only recovery.
      } finally {
        requestInFlight = false;
      }
    };
    pollQueueStatus();
    const timer = window.setInterval(pollQueueStatus, 3000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [user?.id, user?.isOffline]);

  const handleVsComplete = useCallback((matchInfo) => {
    setVsMatchInfo(null);
    if (!matchInfo) return;

    if ((matchInfo.mode === 'PVP' || matchInfo.mode === 'PVP_QUICK') && matchInfo.roomCode) {
      const isHost = matchInfo.isHost !== undefined
        ? matchInfo.isHost
        : matchInfo.sideA?.username === user?.username ||
          matchInfo.sideA?.userId === user?.id ||
          matchInfo.sideA?.username === user?.studentCode;
      setRoomGameConfig({
        roomCode: matchInfo.roomCode,
        matchId: matchInfo.matchPublicId || matchInfo.matchId || matchInfo.id,
        matchPublicId: matchInfo.matchPublicId || matchInfo.matchId,
        isHost,
        isQuickMatch: true,
        targetOpponent: isHost ? matchInfo.sideB : matchInfo.sideA,
        initialTimeControl: matchInfo.timeControl,
        timeControl: matchInfo.timeControl,
        playerToken: matchInfo.playerToken,
      });
    } else if (matchInfo.mode === 'BOT') {
      // MATCH_FOUND only confirms that the worker match was queued. The match result,
      // replay and rating must come from MATCH_FINISHED and the persisted server replay.
      const matchPublicId = matchInfo.matchPublicId || matchInfo.publicId || matchInfo.matchId;
      setSelectedMatch({
        id: matchPublicId,
        matchId: matchInfo.matchId,
        matchPublicId,
        mode: 'BOT',
        matchType: 'LADDER',
        isRanked: true,
        isLiveSpectator: true,
        sideA: matchInfo.sideA,
        sideB: matchInfo.sideB,
        winner: null,
        winnerSide: null,
        endReason: null,
      });
      setReplayData(null);
      setEloToast({
        type: 'INFO',
        text: 'Đang chờ worker hoàn tất trận. Replay chính thức sẽ tự phát khi sẵn sàng.',
        autoClose: 5000,
      });
    }
  }, [user]);

  function restoreOfflineData(parsed) {
    if (!parsed) {
      setUser(DEFAULT_OFFLINE_USER);
      return false;
    }
    try {
      const u = typeof parsed === 'string' ? JSON.parse(parsed) : parsed;
      if (!u) {
        setUser(DEFAULT_OFFLINE_USER);
        return false;
      }
      setUser(u);
      if (u?.pvpElo !== undefined) setPvpElo(u.pvpElo);
      if (u?.botElo !== undefined) setBotElo(u.botElo);

      if (u?.email) {
        try {
          const cachedBots = localStorage.getItem(`ottv2_bots_${u.email}`);
          if (cachedBots) {
            const active = filterActiveBots(JSON.parse(cachedBots)).map((b) => {
              const s = getBotSource(b?.id, b?.name);
              return { ...b, sourceCode: s?.code || '', language: s?.language || 'PYTHON' };
            });
            setBots(active);
          }
        } catch {}

        try {
          const cachedHistory = localStorage.getItem(`ottv2_history_${u.email}`);
          if (cachedHistory) setEloHistory(JSON.parse(cachedHistory));
        } catch {}

        try {
          const cachedStats = localStorage.getItem(`ottv2_stats_${u.email}`);
          if (cachedStats) setServerStats(JSON.parse(cachedStats));
        } catch {}

        try {
          const matchKey = getMatchCacheKey(u);
          const cachedMatches = matchKey ? localStorage.getItem(matchKey) : null;
          if (cachedMatches) setMatches(JSON.parse(cachedMatches));
          else setMatches([]);
        } catch {
          setMatches([]);
        }
      }
      return true;
    } catch {
      setUser(DEFAULT_OFFLINE_USER);
      setMatches([]);
      setEloHistory([]);
      return false;
    }
  }

  async function loadUser() {
    try {
      const online = await checkBackendOnline(2500);
      setIsBackendOffline(!online);

      if (online) {
        try {
          const response = await fetchWithTimeout(
            '/api/auth/me',
            {
              credentials: 'same-origin',
              headers: {
                ...DEFAULT_HEADERS,
              },
            },
            3000
          );
          if (response && response.ok) {
            const contentType = response.headers?.get('content-type') || '';
            if (contentType.includes('application/json')) {
              const userData = await response.json();
              if (userData) {
                setUser(userData);
                setMatches([]);
                setEloHistory([]);
                setSelectedMatch(null);
                try {
                  localStorage.setItem('ottv2_last_active_user', JSON.stringify(userData));
                  if (userData?.email) {
                    localStorage.setItem(`ottv2_user_${userData.email}`, JSON.stringify(userData));
                  }
                  const matchKey = getMatchCacheKey(userData);
                  const cachedMatches = matchKey ? localStorage.getItem(matchKey) : null;
                  if (cachedMatches) setMatches(JSON.parse(cachedMatches));
                } catch {}
                if (userData?.pvpElo !== undefined) setPvpElo(userData.pvpElo);
                if (userData?.botElo !== undefined) setBotElo(userData.botElo);
                refreshData(userData).catch(() => {});
                return;
              }
            }
          }

          if (response && (response.status === 401 || response.status === 403)) {
            try {
              localStorage.removeItem('ottv2_last_active_user');
              localStorage.removeItem('ottv2_auth_token');
              localStorage.removeItem('ottv2_match_history');
              sessionStorage.clear();
            } catch {}
            setUser(null);
            setMatches([]);
            setEloHistory([]);
            setSelectedMatch(null);
            return;
          }

          // Fallback offline only for network unreachable / server error
          setIsBackendOffline(true);
          const cached = localStorage.getItem('ottv2_last_active_user');
          if (cached) {
            try {
              restoreOfflineData(JSON.parse(cached));
              return;
            } catch {}
          }
          restoreOfflineData(DEFAULT_OFFLINE_USER);
          return;
        } catch (err) {
          // Handle 401 or abort gracefully without console.error
          try {
            localStorage.removeItem('ottv2_last_active_user');
            localStorage.removeItem('ottv2_auth_token');
            localStorage.removeItem('ottv2_match_history');
            sessionStorage.clear();
          } catch {}
          setUser(null);
          return;
        }
      } else {
        const cached = localStorage.getItem('ottv2_last_active_user');
        if (cached) {
          try {
            restoreOfflineData(JSON.parse(cached));
            return;
          } catch {}
        }
        restoreOfflineData(DEFAULT_OFFLINE_USER);
      }
    } catch (topErr) {
      setUser(null);
      setIsBackendOffline(true);
      const cached = localStorage.getItem('ottv2_last_active_user');
      if (cached) {
        try {
          restoreOfflineData(JSON.parse(cached));
        } catch {
          restoreOfflineData(DEFAULT_OFFLINE_USER);
        }
      } else {
        restoreOfflineData(DEFAULT_OFFLINE_USER);
      }
    } finally {
      setIsLoading(false);
    }
  }

  async function refreshData(activeUser) {
    const targetUser = activeUser !== undefined ? activeUser : user;
    if (!targetUser || !targetUser.email || targetUser.isOffline) {
      return;
    }
    const targetEmail = targetUser.email;

    try {
      const profileRes = await api('/api/profile');
      if (!profileRes) return;
      const profile = await profileRes.json();
      if (profile.pvpElo !== undefined) setPvpElo(profile.pvpElo);
      if (profile.botElo !== undefined) setBotElo(profile.botElo);
      if (profile.eloHistory) setEloHistory(profile.eloHistory);
      const newStats = {
        matchesCount: profile.matchesCount ?? 0,
        wins: profile.wins ?? 0,
        losses: profile.losses ?? 0,
        winRate: profile.winRate ?? 0,
        pvpMatchesCount: profile.pvpMatchesCount ?? 0,
        pvpWins: profile.pvpWins ?? 0,
        pvpLosses: profile.pvpLosses ?? 0,
        pvpDistinctOpponents: profile.pvpDistinctOpponents ?? 0,
        botMatchesCount: profile.botMatchesCount ?? 0,
        botWins: profile.botWins ?? 0,
        botLosses: profile.botLosses ?? 0,
        distinctOpponents: profile.distinctOpponents ?? 0,
        isProvisional: profile.isProvisional ?? false,
      };
      setServerStats(newStats);

      if (targetEmail) {
        localStorage.setItem(`ottv2_history_${targetEmail}`, JSON.stringify(profile.eloHistory || []));
        localStorage.setItem(`ottv2_stats_${targetEmail}`, JSON.stringify(newStats));
        if (profile.studentCode) {
          setUser((prev) => {
            if (!prev) return prev;
            const updated = { ...prev, studentCode: profile.studentCode };
            localStorage.setItem('ottv2_last_active_user', JSON.stringify(updated));
            localStorage.setItem(`ottv2_user_${targetEmail}`, JSON.stringify(updated));
            return updated;
          });
        }
      }
    } catch (err) {
      if (err?.message?.includes('hết hạn') || !user) return;
    }

    if (!user && !activeUser) return;

    try {
      const matchRes = await api('/api/matches/history');
      if (!matchRes) return;
      const backendMatches = await matchRes.json();
      setMatches(() => {
        let cached = [];
        const matchKey = getMatchCacheKey(targetUser);
        if (matchKey) {
          try {
            const raw = localStorage.getItem(matchKey);
            if (raw) cached = JSON.parse(raw);
          } catch {}
        }
        const replayMap = new Map();
        for (const m of cached) {
          if (m?.id && (m.replay || m.replayData)) {
            replayMap.set(String(m.id), m.replay || m.replayData);
          }
        }
        const combined = (backendMatches || []).map((bm) => {
          const replay = replayMap.get(String(bm.id));
          return replay ? { ...bm, replay, replayData: replay } : bm;
        });

        // Giữ lại các trận PvP/Local trong cache để hiển thị ở "Lịch Sử Trận Đấu Gần Đây"
        const localAndPvpMatches = cached.filter(
          (m) =>
            m?.mode === 'PVP' ||
            m?.mode === 'PVP_QUICK' ||
            m?.mode === 'PVP_UNVERIFIED' ||
            m?.mode === 'PRACTICE' ||
            m?.opponentType
        );
        for (const lm of localAndPvpMatches) {
          if (!combined.some((x) => String(x.id) === String(lm.id))) {
            combined.push(lm);
          }
        }
        combined.sort(
          (a, b) => new Date(b.createdAt || b.playedAt || 0) - new Date(a.createdAt || a.playedAt || 0)
        );

        if (matchKey) {
          localStorage.setItem(matchKey, JSON.stringify(combined));
        }
        return combined;
      });
    } catch (err) {
      if (err?.message?.includes('hết hạn') || !user) return;
    }

    if (!user && !activeUser) return;

    try {
      const botRes = await api('/api/bots');
      if (!botRes) return;
      const owned = await botRes.json();
      const activeBots = filterActiveBots(owned).map((b) => {
        const s = getBotSource(b.id, b.name);
        return { ...b, sourceCode: s?.code || '', language: s?.language || 'PYTHON' };
      });
      setBots(activeBots);
      if (targetEmail) {
        localStorage.setItem(`ottv2_bots_${targetEmail}`, JSON.stringify(activeBots));
      }
    } catch (err) {
      if (err?.message?.includes('hết hạn') || !user) return;
    }
  }

  useEffect(() => {
    if (!user || user.isOffline) return undefined;
    const syncInterval = setInterval(() => {
      setUser((current) => {
        if (!current || current.isOffline) {
          clearInterval(syncInterval);
          return current;
        }
        refreshData(current).catch(() => {});
        return current;
      });
    }, 45000);
    return () => clearInterval(syncInterval);
  }, [user?.email, user?.isOffline]);

  async function handleDeleteBot(botId) {
    addDeletedBotId(botId);
    setBots((prev) => {
      const next = prev.filter((b) => String(b.id) !== String(botId));
      if (user?.email) {
        localStorage.setItem(`ottv2_bots_${user.email}`, JSON.stringify(next));
      }
      return next;
    });

    try {
      await api(`/api/bots/${botId}`, {
        method: 'DELETE',
        headers: await csrfHeaders(),
      });
    } catch (err) {
      console.warn('Backend delete bot offline or failed:', err);
    }
    setEloToast({ type: 'INFO', text: '🗑️ Đã xóa Bot thành công!' });
  }

  async function handleToggleLadderActive(botId, requestedActive = null) {
    try {
      const res = await api(`/api/bots/${botId}/set-ladder-active`, {
        method: 'POST',
        headers: await csrfHeaders(),
        body: JSON.stringify(requestedActive !== null ? { active: requestedActive } : {}),
      });
      if (res) {
        const updated = await res.json();
        setBots((prev) => {
          const next = prev.map((b) => {
            if (String(b.id) === String(botId)) {
              return { ...b, ...updated, ladderEnabled: updated.ladderEnabled };
            }
            if (updated.ladderEnabled) {
              return { ...b, ladderEnabled: false };
            }
            return b;
          });
          if (user?.email) {
            localStorage.setItem(`ottv2_bots_${user.email}`, JSON.stringify(next));
          }
          return next;
        });
        setEloToast({
          type: 'INFO',
          text: updated.ladderEnabled
            ? `🟢 Đã kích hoạt Bot "${updated.name}" (${updated.elo ?? 1200} ELO) tham gia Đấu Ranked!`
            : `⚪ Đã tắt chế độ Đấu Ranked cho Bot "${updated.name}".`,
        });
        return;
      }
    } catch (err) {
      console.warn('Backend set-ladder-active offline, fallback locally:', err);
    }

    setBots((prev) => {
      const targetBot = prev.find((b) => String(b.id) === String(botId));
      const nextActive = requestedActive !== null ? requestedActive : !targetBot?.ladderEnabled;
      const next = prev.map((b) => {
        if (String(b.id) === String(botId)) {
          return { ...b, ladderEnabled: nextActive };
        }
        if (nextActive) {
          return { ...b, ladderEnabled: false };
        }
        return b;
      });
      if (user?.email) {
        localStorage.setItem(`ottv2_bots_${user.email}`, JSON.stringify(next));
      }
      setEloToast({
        type: 'INFO',
        text: nextActive
          ? `🟢 Đã kích hoạt Bot "${targetBot?.name || botId}" tham gia Đấu Ranked!`
          : `⚪ Đã tắt chế độ Đấu Ranked cho Bot "${targetBot?.name || botId}".`,
      });
      return next;
    });
  }

  async function handleCleanBots(keepCount = 5) {
    const sortedBots = [...bots].sort((a, b) => Number(b.id) - Number(a.id));
    if (sortedBots.length <= keepCount) return;

    const toKeep = sortedBots.slice(0, keepCount);
    const toDelete = sortedBots.slice(keepCount);

    addDeletedBotIds(toDelete.map((b) => b.id));
    setBots(toKeep);
    if (user?.email) {
      localStorage.setItem(`ottv2_bots_${user.email}`, JSON.stringify(toKeep));
    }

    for (const b of toDelete) {
      try {
        await api(`/api/bots/${b.id}`, {
          method: 'DELETE',
          headers: await csrfHeaders(),
        });
      } catch (err) {
        console.warn('Backend delete bot offline or failed:', err);
      }
    }

    setEloToast({
      type: 'WIN',
      text: `🧹 Đã dọn dẹp thành công! Đã xóa ${toDelete.length} bot cũ và giữ lại đúng ${toKeep.length} bot mới nhất.`,
    });
  }

  async function handleUpdateStudentCode(newCode) {
    try {
      await api('/api/profile', {
        method: 'PUT',
        headers: await csrfHeaders(),
        body: JSON.stringify({ studentCode: newCode }),
      });
    } catch (err) {
      console.warn('Backend update profile offline or failed:', err);
    }
    setUser((prev) => {
      const next = { ...prev, studentCode: newCode };
      localStorage.setItem('ottv2_last_active_user', JSON.stringify(next));
      if (next.email) {
        localStorage.setItem(`ottv2_user_${next.email}`, JSON.stringify(next));
      }
      return next;
    });
    setEloToast({ type: 'WIN', text: '🎓 Cập nhật Mã Sinh Viên thành công!' });
  }

  useEffect(() => {
    const failSafeTimer = setTimeout(() => {
      console.warn("Cầu dao an toàn: Ép tắt loading sau 5 giây");
      setIsLoading(false);
    }, 5000);

    loadUser().finally(() => {
      clearTimeout(failSafeTimer);
      setIsLoading(false);
    });

    return () => clearTimeout(failSafeTimer);
  }, []);

  async function handleOpenReplay(matchOrWrapper, directReplay = null) {
    let match = matchOrWrapper;
    let replay = directReplay;

    if (matchOrWrapper?.matchInfo && matchOrWrapper?.replay) {
      match = matchOrWrapper.matchInfo;
      replay = matchOrWrapper.replay;
    }

    const isAuthoritativeBotMatch =
      ['BOT', 'LADDER'].includes(String(match?.mode || '').toUpperCase()) ||
      ['BOT', 'LADDER'].includes(String(match?.matchType || '').toUpperCase());

    if (!isAuthoritativeBotMatch && !replay && (match?.replayData || match?.replay)) {
      replay = match.replayData || match.replay;
    }

    const replayId = match?.replayId || match?.id;

    if (!isAuthoritativeBotMatch && !replay && replayId) {
      try {
        const raw = localStorage.getItem(`ottv2_replay_${replayId}`);
        if (raw) replay = JSON.parse(raw);
      } catch {}
    }

    // 1. Direct replay passed via arguments or match object in current session
    if (!isAuthoritativeBotMatch && replay && ((replay.turns && replay.turns.length > 0) || (replay.moves && replay.moves.length > 0) || (replay.movesHistory && replay.movesHistory.length > 0))) {
      setSelectedMatch(match);
      setReplayData(replay);
      return;
    }

    // 2. Backend API replay fetch (chuẩn hóa đồng bộ cho mọi người xem)
    const targetId =
      match?.matchPublicId ||
      match?.publicId ||
      match?.replayId ||
      (match?.id ? String(match.id) : null);

    if (targetId) {
      try {
        let replayMatch = match;
        if (isAuthoritativeBotMatch) {
          try {
            const matchResponse = await api(`/api/matches/${targetId}`);
            if (matchResponse?.ok) {
              const matchDetails = await matchResponse.json();
              if (matchDetails?.sideA && matchDetails?.sideB) {
                const ownedBotIds = new Set((bots || []).map((bot) => String(bot.id)));
                const viewerId = user?.id != null ? String(user.id) : null;
                const sideAOwnerId = matchDetails.sideA.ownerId != null ? String(matchDetails.sideA.ownerId) : null;
                const sideBOwnerId = matchDetails.sideB.ownerId != null ? String(matchDetails.sideB.ownerId) : null;
                const viewerSide = (viewerId && sideAOwnerId === viewerId) || ownedBotIds.has(String(matchDetails.sideA.botId))
                  ? 'A'
                  : ((viewerId && sideBOwnerId === viewerId) || ownedBotIds.has(String(matchDetails.sideB.botId)) ? 'B' : null);
                const sideAEloBefore = matchDetails.sideA.eloBefore ?? matchDetails.sideA.elo;
                const sideBEloBefore = matchDetails.sideB.eloBefore ?? matchDetails.sideB.elo;
                const sideAEloChange = Number.isFinite(sideAEloBefore) && Number.isFinite(matchDetails.sideA.eloAfter)
                  ? matchDetails.sideA.eloAfter - sideAEloBefore
                  : undefined;
                const sideBEloChange = Number.isFinite(sideBEloBefore) && Number.isFinite(matchDetails.sideB.eloAfter)
                  ? matchDetails.sideB.eloAfter - sideBEloBefore
                  : undefined;
                replayMatch = {
                  ...match,
                  matchPublicId: targetId,
                  sideA: {
                    ...matchDetails.sideA,
                    elo: sideAEloBefore,
                    eloChange: sideAEloChange,
                  },
                  sideB: {
                    ...matchDetails.sideB,
                    elo: sideBEloBefore,
                    eloChange: sideBEloChange,
                  },
                  userSide: viewerSide || match?.userSide,
                  viewerPerspective: viewerSide === 'B' ? 'RED' : (viewerSide === 'A' ? 'BLUE' : match?.viewerPerspective),
                };
              }
            }
          } catch (metadataError) {
            console.warn('Không tải được thông tin bot/ELO của replay:', metadataError);
          }
        }

        const response = await api(`/api/matches/${targetId}/replay`);
        if (response && response.ok) {
          const buffer = await response.arrayBuffer();
          if (!buffer || buffer.byteLength === 0) {
            throw new Error('Dữ liệu Replay từ máy chủ rỗng');
          }

          const uint8 = new Uint8Array(buffer);
          const hasGzipMagic = uint8.length >= 2 && uint8[0] === 0x1f && uint8[1] === 0x8b;
          const contentType = (response.headers?.get('content-type') || '').toLowerCase();
          const contentEncoding = (response.headers?.get('content-encoding') || '').toLowerCase();
          const isGzip = hasGzipMagic || contentType.includes('gzip') || contentEncoding.includes('gzip');

          let jsonText = '';
          if (isGzip && typeof DecompressionStream !== 'undefined') {
            try {
              const stream = new Blob([buffer]).stream().pipeThrough(new DecompressionStream('gzip'));
              jsonText = await new Response(stream).text();
            } catch {
              jsonText = new TextDecoder('utf-8').decode(buffer);
            }
          } else {
            jsonText = new TextDecoder('utf-8').decode(buffer);
          }

          if (!jsonText || !jsonText.trim()) {
            throw new Error('Dữ liệu Replay rỗng sau khi giải mã');
          }

          const parsed = JSON.parse(jsonText);
          if (
            isAuthoritativeBotMatch &&
            parsed?.matchId &&
            String(parsed.matchId) !== String(targetId) &&
            String(parsed.publicId || '') !== String(targetId)
          ) {
            throw new Error('Replay ID không trùng với trận bot được chọn');
          }
          if (
            parsed &&
            ((parsed.turns && parsed.turns.length > 0) ||
              (parsed.moves && parsed.moves.length > 0) ||
              (parsed.movesHistory && parsed.movesHistory.length > 0))
          ) {
            setSelectedMatch(replayMatch);
            setReplayData(parsed);
            return;
          }
        }
      } catch (err) {
        console.warn('Backend match replay not found or failed to decompress:', err);
      }
    }

    // 3. Tuyệt đối không fallback về simulateMatch demo hay mock data:
    // Đặt replayData = null để ReplayModal hiển thị thông báo "Dữ liệu Replay chưa sẵn sàng hoặc đã bị xóa"
    setSelectedMatch(match);
    setReplayData(null);
  }

  async function handleDownloadLog(side) {
    if (!selectedMatch) return;
    try {
      const response = await api(`/api/matches/${selectedMatch.id}/logs?side=${side}`);
      const bytes = await response.arrayBuffer();
      const plain = await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'))).text();
      const url = URL.createObjectURL(new Blob([plain], { type: 'text/plain' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `bot-${side}.log`;
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      setEloToast({ type: 'INFO', text: 'Chưa có file log chi tiết cho bên ' + side });
    }
  }

  async function handleUploadSubmission({ botId, newBotName, language, source }) {
    if (botId === '__new__' && bots.length >= 5) {
      setEloToast({
        type: 'LOSE',
        text: '⚠️ Bạn đã đạt giới hạn tối đa 5 Bot. Vui lòng xóa bớt các bot cũ hoặc chọn Cập nhật phiên bản trên bot hiện có!',
      });
      return;
    }
    let targetBotId = botId;
    let targetBotName = newBotName;
    if (botId === '__new__') {
      try {
        const res = await api('/api/bots', {
          method: 'POST',
          headers: await csrfHeaders(),
          body: JSON.stringify({ name: newBotName }),
        });
        const created = await res.json();
        targetBotId = created.id;
        targetBotName = created.name || newBotName;
        const botWithSource = {
          ...created,
          sourceCode: source,
          language: language || 'PYTHON',
        };
        setBots((prev) => {
          const next = [botWithSource, ...prev];
          if (user?.email) {
            localStorage.setItem(`ottv2_bots_${user.email}`, JSON.stringify(next));
          }
          return next;
        });
      } catch (err) {
        throw new Error(`Không thể tạo bot trên máy chủ: ${err.message || 'Vui lòng thử lại.'}`);
      }
    } else {
      const existing = bots.find((b) => String(b.id) === String(botId));
      if (existing) {
        targetBotName = existing.name || newBotName;
      }
    }

    saveBotSource(targetBotId, targetBotName, source, language);

    const response = await api(`/api/bots/${targetBotId}/submissions`, {
      method: 'POST',
      headers: await csrfHeaders(),
      body: JSON.stringify({ language, source }),
    });
    if (!response) throw new Error('Máy chủ không xác nhận yêu cầu biên dịch.');
    const submission = await response.json();
    refreshData();
    return { botId: targetBotId, submissionId: submission.id, submission };
  }

  const handlePvpMatchFinished = (matchResult) => {
    const { opponentType, opponentName, opponentElo, userWon, winner, winReason, replay, replayData, isRanked, matchId, roomCode, mySide, isHost } = matchResult;
    const finalReplay = replay || replayData;
    const finalMatchId = matchId || finalReplay?.matchId || (roomCode ? `pvp_${roomCode}` : `pvp_${Date.now()}`);
    const rawMode = matchResult.mode || (matchResult.isQuickMatch ? 'PVP_QUICK' : 'PVP');
    const isSparring = Boolean(
      opponentType === 'SPARRING' ||
      opponentType === 'SPARRING_MACHINE' ||
      opponentType === 'BOT' ||
      (opponentName && (
        opponentName.startsWith('Máy ') ||
        opponentName.startsWith('sys_machine_') ||
        opponentName.toLowerCase().includes('sparring') ||
        opponentName.includes('Tân Binh') ||
        opponentName.includes('Tập Sự') ||
        opponentName.includes('Huyền Thoại')
      ))
    );
    const isRankedMatch = !isSparring && (Boolean(isRanked) || rawMode === 'PVP_QUICK') && opponentType === 'HUMAN';
    const targetRating = opponentElo || 1200;

    // Lưu replay vào localStorage ngay lập tức
    if (finalReplay) {
      try {
        localStorage.setItem(`ottv2_replay_${finalMatchId}`, JSON.stringify(finalReplay));
        if (roomCode) {
          localStorage.setItem(`ottv2_replay_${roomCode}`, JSON.stringify(finalReplay));
        }
      } catch (e) {
        console.warn('Lỗi lưu replay vào localStorage:', e);
      }
    }

    // Xác định bên thắng cuộc (A, B, hoặc DRAW)
    let winnerSide = 'DRAW';
    if (winner === 'A' || winner === 'B') {
      winnerSide = winner;
    } else if (winner === 'USER') {
      winnerSide = mySide || (isHost ? 'A' : 'B');
    } else if (winner === 'OPPONENT') {
      winnerSide = (mySide === 'A' || isHost) ? 'B' : 'A';
    } else if (userWon) {
      winnerSide = mySide || (isHost ? 'A' : 'B');
    } else if (userWon === false && winner !== 'DRAW') {
      winnerSide = (mySide === 'A' || isHost) ? 'B' : 'A';
    }

    const isOnlineAuthoritativeMatch = !isSparring && (Boolean(roomCode) || Boolean(matchId));
    const serverIdentifier = roomCode || matchId;

    const recordPracticeMatch = () => {
      csrfHeaders().then((headers) => {
        api('/api/profile/matches', {
          method: 'POST',
          headers,
          body: JSON.stringify({
            mode: 'PRACTICE',
            opponent: opponentName || 'Đối thủ',
            opponentElo: targetRating,
            opponentType: isSparring ? 'SPARRING' : (opponentType || 'HUMAN'),
            userWon: Boolean(userWon),
            winner: userWon ? 'USER' : (winner === 'DRAW' ? 'DRAW' : 'OPPONENT'),
            reason: winReason || 'FINISHED',
            change: 0,
            newElo: pvpElo,
            isRanked: false,
            replayId: finalMatchId,
            replayData: finalReplay,
          }),
        })
          .then(async (res) => {
            if (res && res.ok) {
              refreshData();
            }
          })
          .catch((err) => {
            console.warn('Lỗi lưu kết quả trận đấu tập:', err);
          });
      });
    };

    if (isOnlineAuthoritativeMatch && serverIdentifier) {
      // 1. Server-authoritative PvP: Gửi kết quả lên /api/matches/{id}/finish
      csrfHeaders().then((headers) => {
        api(`/api/matches/${serverIdentifier}/finish`, {
          method: 'POST',
          headers,
          body: JSON.stringify({
            winnerSide,
            reason: winReason || 'FINISHED',
            replayData: finalReplay,
            userId: user?.id,
          }),
        })
          .then(async (res) => {
            if (res && res.ok) {
              const finishRes = await res.json();
              if (finishRes && finishRes.newElo !== undefined) {
                setPvpElo(finishRes.newElo);
              }
              if (finishRes && finishRes.isRanked) {
                const diff = finishRes.eloChange;
                const oldRating = finishRes.oldElo ?? (finishRes.newElo - diff);
                const diffStr = diff > 0 ? `+${diff}` : `${diff}`;
                const toastPayload = {
                  type: diff > 0 ? 'WIN' : (diff < 0 ? 'LOSE' : 'DRAW'),
                  text: diff > 0
                    ? `🎉 Chiến thắng! ${diffStr} ELO PvP (${oldRating} ➜ ${finishRes.newElo})`
                    : diff < 0
                    ? `💔 Thất bại! ${diffStr} ELO PvP (${oldRating} ➜ ${finishRes.newElo})`
                    : `🤝 Hòa trận! 0 ELO PvP (${oldRating} ➜ ${finishRes.newElo})`,
                  autoClose: 4000,
                  duration: 4000,
                };
                try {
                  sessionStorage.setItem('ottv2_pending_elo_toast', JSON.stringify(toastPayload));
                } catch {}
              }
              refreshData();
            } else {
              // Fallback nếu không tìm thấy trận trên server (phòng cục bộ)
              recordPracticeMatch();
            }
          })
          .catch((err) => {
            console.warn('Lỗi gọi /api/matches/finish, fallback practice:', err);
            recordPracticeMatch();
          });
      });
    } else {
      // 2. Trận đấu tập với Bot/Máy hoặc offline: Ghi nhận lịch sử cá nhân (không đổi ELO)
      recordPracticeMatch();
    }

    const mySideCode = mySide || (isHost ? 'A' : 'B');
    const myName = user?.displayName || user?.username || 'Bạn';
    const oppName = opponentName || 'Đối thủ';
    const defaultSideA = mySideCode === 'A' ? { botName: myName, userId: user?.id } : { botName: oppName };
    const defaultSideB = mySideCode === 'B' ? { botName: myName, userId: user?.id } : { botName: oppName };

    const sideA = finalReplay?.sideA || defaultSideA;
    const sideB = finalReplay?.sideB || defaultSideB;

    const matchRecord = {
      id: finalMatchId,
      replayId: finalMatchId,
      opponent: opponentName,
      opponentElo: targetRating,
      opponentType,
      userSide: mySideCode,
      side: mySideCode,
      sideA,
      sideB,
      status: 'FINISHED',
      winner: winnerSide,
      winnerSide,
      userWon,
      change: isRankedMatch ? (matchResult.change ?? matchResult.eloChange ?? 0) : 0,
      newElo: matchResult.newElo ?? pvpElo,
      reason: winReason,
      isRanked: isRankedMatch,
      resultVerified: true,
      mode: isSparring ? 'PRACTICE' : (isRankedMatch ? 'PVP' : 'CASUAL'),
      createdAt: new Date().toISOString(),
      playedAt: new Date().toISOString(),
      timestamp: new Date().toLocaleTimeString(),
      replay: finalReplay,
      replayData: finalReplay,
    };
    setMatches((prev) => {
      const next = [matchRecord, ...prev];
      const matchKey = getMatchCacheKey(user);
      if (matchKey) {
        localStorage.setItem(matchKey, JSON.stringify(next));
      }
      return next;
    });
  };

  const handleConfirmChallenge = (target, options = {}) => {
    if (options.matchType === 'PVP') {
      const tc = options.timeControl;
      const targetUser = target.username || target.studentCode || target.name;
      const isRealHuman = !target.isSparring && !target.isMachine;

      if (isRealHuman && targetUser) {
        // Gửi lời mời thách đấu tới Backend qua POST /api/challenge/send (bắn STOMP tới đối thủ)
        csrfHeaders()
          .then((headers) =>
            api('/api/challenge/send', {
              method: 'POST',
              headers,
              body: JSON.stringify({
                targetUsername: targetUser,
                timeControl: tc,
                fromUser: user?.displayName || user?.studentCode || 'Người chơi',
              }),
            })
          )
          .then(async (res) => {
            if (res && res.ok) {
              const data = await res.json();
              setRoomGameConfig({
                roomCode: data.roomCode || data.code,
                isHost: true,
                isQuickMatch: true,
                targetOpponent: target,
                initialTimeControl: tc,
                timeControl: tc,
                playerToken: data.playerToken,
              });
              return;
            }
            throw new Error('API challenge/send failed');
          })
          .catch((err) => {
            console.warn('Lỗi gọi /api/challenge/send, fallback createRoomApi:', err);
            createRoomApi(user?.displayName || user?.studentCode || 'Người chơi', tc)
              .then((data) => {
                setRoomGameConfig({
                  roomCode: data.code,
                  isHost: true,
                  isQuickMatch: true,
                  targetOpponent: target,
                  initialTimeControl: tc,
                  timeControl: tc,
                  playerToken: data.playerToken,
                });
              })
              .catch((err2) => {
                console.error('Lỗi tạo phòng PvP qua backend, fallback offline:', err2);
                const code = Math.floor(100000 + Math.random() * 900000).toString();
                setRoomGameConfig({
                  roomCode: code,
                  isHost: true,
                  isQuickMatch: true,
                  targetOpponent: target,
                  initialTimeControl: tc,
                  timeControl: tc,
                });
              });
          });
        return;
      }

      // Nếu đối đầu với Máy Luyện Tập (Sparring AI)
      createRoomApi(user?.displayName || user?.studentCode || 'Người chơi', tc)
        .then((data) => {
          setRoomGameConfig({
            roomCode: data.code,
            isHost: true,
            isQuickMatch: true,
            targetOpponent: target,
            initialTimeControl: tc,
            timeControl: tc,
            playerToken: data.playerToken,
          });
        })
        .catch((err) => {
          console.error('Lỗi tạo phòng PvP qua backend, fallback offline:', err);
          const code = Math.floor(100000 + Math.random() * 900000).toString();
          setRoomGameConfig({
            roomCode: code,
            isHost: true,
            isQuickMatch: true,
            targetOpponent: target,
            initialTimeControl: tc,
            timeControl: tc,
          });
        });
      return;
    }

    const selectedBot = options.bot;
    const userBotName = selectedBot?.name || bots[0]?.name || 'MySuperBot';
    const { replay, userWon, matchInfo } = simulateMatch(
      botElo,
      target,
      user?.displayName || user?.studentCode || 'Người chơi',
      userBotName
    );

    const isPractice = true;
    const historyItem = {
      id: Date.now(),
      opponent: target.name,
      opponentElo: target.elo,
      userWon,
      change: 0,
      newElo: botElo,
      timestamp: new Date().toLocaleTimeString(),
      mode: 'PRACTICE',
    };
    setEloHistory((prev) => [historyItem, ...prev.slice(0, 19)]);

    setEloToast({
      type: userWon ? 'WIN' : 'LOSE',
      text: userWon
        ? `🎉 Chiến thắng ${target.name}! (Trận luyện tập: Bot ELO giữ nguyên ${botElo})`
        : `💔 Thất bại trước ${target.name}! (Trận luyện tập: Bot ELO giữ nguyên ${botElo})`,
    });

    csrfHeaders().then((headers) => {
      api('/api/profile/matches', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          mode: 'PRACTICE',
          opponent: target.name,
          opponentElo: target.elo,
          opponentType: 'SPARRING',
          userWon,
          winner: userWon ? 'USER' : 'OPPONENT',
          reason: matchInfo.reason || 'FINISHED',
          change: 0,
          newElo: botElo,
          isRanked: false,
        }),
      }).then(async (res) => {
        if (res) {
          const profile = await res.json();
          if (profile.pvpElo !== undefined) setPvpElo(profile.pvpElo);
          if (profile.botElo !== undefined) setBotElo(profile.botElo);
          if (profile.eloHistory) setEloHistory(profile.eloHistory);
          const newStats = {
            matchesCount: profile.matchesCount ?? 0,
            wins: profile.wins ?? 0,
            losses: profile.losses ?? 0,
            winRate: profile.winRate ?? 0,
            pvpMatchesCount: profile.pvpMatchesCount ?? 0,
            pvpWins: profile.pvpWins ?? 0,
            pvpLosses: profile.pvpLosses ?? 0,
            pvpDistinctOpponents: profile.pvpDistinctOpponents ?? 0,
            botMatchesCount: profile.botMatchesCount ?? 0,
            botWins: profile.botWins ?? 0,
            botLosses: profile.botLosses ?? 0,
          };
          setServerStats(newStats);
          const targetEmail = user?.email;
          if (targetEmail) {
            localStorage.setItem(`ottv2_stats_${targetEmail}`, JSON.stringify(newStats));
          }
        }
      }).catch(() => {});
    });

    setEloToast({
      type: userWon ? 'WIN' : 'LOSE',
      text: userWon
        ? `🎉 Bot [${userBotName}] của bạn đã chiến thắng ${target.name}! (Đấu tập: Bot ELO giữ nguyên ${botElo})`
        : `💔 Bot [${userBotName}] của bạn đã thất bại trước ${target.name}. (Đấu tập: Bot ELO giữ nguyên ${botElo})`,
    });

    const botMatchId = replay?.matchId || matchInfo?.id || `bot_match_${Date.now()}`;
    if (replay) {
      try {
        localStorage.setItem(`ottv2_replay_${botMatchId}`, JSON.stringify(replay));
      } catch {}
    }

    setSelectedMatch(matchInfo);
    setReplayData(replay);

    const botMatchRecord = {
      ...matchInfo,
      id: botMatchId,
      replayId: botMatchId,
      opponent: target.name,
      opponentElo: target.elo,
      userWon,
      change: eloResult.change,
      newElo: eloResult.newElo,
      isRanked: true,
      mode: 'BOT',
      createdAt: new Date().toISOString(),
      playedAt: new Date().toISOString(),
      timestamp: new Date().toLocaleTimeString(),
      replay,
      replayData: replay,
    };
    setMatches((prev) => {
      const next = [botMatchRecord, ...prev];
      const matchKey = getMatchCacheKey(user);
      if (matchKey) {
        localStorage.setItem(matchKey, JSON.stringify(next));
      }
      return next;
    });
  };

  async function logout() {
    try {
      const csrfH = await csrfHeaders();
      await api('/api/auth/logout', {
        method: 'POST',
        headers: {
          ...DEFAULT_HEADERS,
          ...csrfH,
        },
      });
    } catch {}
    localStorage.removeItem('ottv2_last_active_user');
    localStorage.removeItem('ottv2_auth_token');
    localStorage.removeItem('ottv2_match_history');
    setUser(null);
    setMatches([]);
    setEloHistory([]);
    setSelectedMatch(null);
    navigate('/');
  }

  const handleRetryConnection = async () => {
    const isUp = await checkBackendOnline();
    if (isUp) {
      setIsBackendOffline(false);
      loadUser();
      setEloToast({ type: 'WIN', text: '✅ Đã kết nối lại thành công với Backend 8080!' });
    } else {
      setEloToast({ type: 'LOSE', text: '⚠️ Backend 8080 vẫn chưa phản hồi. Vui lòng kiểm tra terminal chạy backend.' });
    }
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-zinc-950 flex flex-col items-center justify-center p-4 select-none">
        <div className="flex flex-col items-center justify-center text-center space-y-4">
          <div className="relative flex items-center justify-center">
            <div
              className="w-12 h-12 rounded-full border-4 border-zinc-800 border-t-emerald-500 animate-spin"
              style={{ borderTopColor: '#10b981' }}
            ></div>
            <span className="absolute text-base">⚔️</span>
          </div>

          <div className="space-y-1">
            <h2 className="text-white text-base md:text-lg font-bold tracking-wide">
              Đang kết nối đến máy chủ...
            </h2>
            <p className="text-xs text-zinc-400 font-mono">
              OTTV2 · Battle Code Arena Platform
            </p>
          </div>

          <div className="pt-2">
            <button
              type="button"
              onClick={() => {
                const cached = localStorage.getItem('ottv2_last_active_user');
                if (cached) {
                  try {
                    restoreOfflineData(JSON.parse(cached));
                  } catch {
                    restoreOfflineData(DEFAULT_OFFLINE_USER);
                  }
                } else {
                  restoreOfflineData(DEFAULT_OFFLINE_USER);
                }
                setIsBackendOffline(true);
                setIsLoading(false);
              }}
              className="text-xs px-4 py-2 bg-zinc-900 hover:bg-zinc-800 text-zinc-300 hover:text-white rounded-xl border border-zinc-700 transition shadow-lg font-sans flex items-center gap-2"
            >
              <span>⚡</span>
              <span>Bỏ qua &amp; Chơi ngoại tuyến (Offline)</span>
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!user) {
    return (
      <>
        {eloToast && (
          <div
            className={`fixed top-6 right-6 z-50 p-4 rounded-2xl shadow-2xl border flex items-center gap-3 max-w-md animate-fadeIn ${
              eloToast.type === 'WIN'
                ? 'bg-emerald-950 border-emerald-500/80 text-emerald-100'
                : eloToast.type === 'LOSE'
                ? 'bg-rose-950 border-rose-500/80 text-rose-100'
                : 'bg-zinc-850 border-zinc-700 text-zinc-200'
            }`}
          >
            <span className="text-xl">{eloToast.type === 'WIN' ? '🏆' : eloToast.type === 'LOSE' ? '⚠️' : 'ℹ️'}</span>
            <p className="text-xs font-semibold flex-1 leading-relaxed">{eloToast.text}</p>
            <button
              type="button"
              onClick={() => setEloToast(null)}
              className="p-1 text-zinc-400 hover:text-white text-xs"
            >
              ✕
            </button>
          </div>
        )}
        {authMode === 'register' ? (
          <SignUp
            onShowLogin={() => setAuthMode('login')}
            onRegistered={(email) => {
              setAuthEmail(email);
              setAuthMessage('Tạo tài khoản thành công! Hãy đăng nhập.');
              setAuthMode('login');
            }}
          />
        ) : (
          <SignIn
            onSignedIn={loadUser}
            onEnterOffline={() => {
              restoreOfflineData(DEFAULT_OFFLINE_USER);
              setIsBackendOffline(true);
              setIsLoading(false);
            }}
            initialEmail={authEmail}
            successMessage={authMessage}
            isBackendOffline={isBackendOffline}
            onShowRegister={() => {
              setAuthMessage('');
              setAuthMode('register');
            }}
          />
        )}
      </>
    );
  }

  const outletContext = {
    user,
    userStats,
    pvpElo,
    botElo,
    bots,
    matches,
    handleOpenReplay,
    handleDeleteBot,
    handleCleanBots,
    handleToggleLadderActive,
    handleUpdateStudentCode,
    handleConfirmChallenge,
    setEloToast,
  };

  return (
    <>
      {incomingChallenge && (
        <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-[100] flex items-center justify-center p-4 animate-fadeIn">
          <div className="bg-zinc-850 border-2 border-amber-500/80 w-full max-w-md rounded-3xl p-6 shadow-2xl text-center space-y-4">
            <div className="w-16 h-16 rounded-2xl bg-amber-500/20 text-amber-400 border border-amber-500/40 flex items-center justify-center mx-auto text-3xl shadow-lg shadow-amber-950/40 animate-pulse">
              ⚔️
            </div>
            <div>
              <span className="text-[10px] font-mono font-black text-amber-400 uppercase tracking-widest px-2.5 py-0.5 rounded-full bg-amber-500/10 border border-amber-500/30">
                LỜI MỜI THÁCH ĐẤU PVP
              </span>
              <h3 className="text-lg font-black text-white mt-2">
                ⚔️ {incomingChallenge.fromUser || 'Một người chơi'} đang thách đấu bạn!
              </h3>
              <p className="text-xs text-zinc-300 mt-1">
                Thể thức:{' '}
                <strong className="text-amber-300 font-mono">
                  {incomingChallenge.timeControl?.name || incomingChallenge.timeControl?.label || 'Chớp 3+2'}
                </strong>
              </p>
            </div>

            <div className="flex items-center gap-3 pt-2">
              <button
                type="button"
                onClick={() => {
                  const invite = incomingChallenge;
                  setIncomingChallenge(null);
                  setRoomGameConfig({
                    roomCode: invite.roomId || invite.roomCode,
                    isHost: false,
                    isQuickMatch: true,
                    initialTimeControl: invite.timeControl,
                    timeControl: invite.timeControl,
                  });
                }}
                className="flex-1 py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-black text-sm rounded-xl shadow-lg shadow-emerald-950/40 flex items-center justify-center gap-2 transition-all transform hover:scale-[1.02]"
              >
                <span>🎮</span>
                <span>Vào Phòng</span>
              </button>

              <button
                type="button"
                onClick={() => setIncomingChallenge(null)}
                className="flex-1 py-3 bg-zinc-800 hover:bg-zinc-700 text-zinc-300 hover:text-white font-bold text-sm rounded-xl border border-zinc-700 transition-all"
              >
                <span>✕</span>
                <span>Từ Chối</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Màn hình Đối Mặt Toàn Màn Hình (VS Screen Face-off) */}
      {vsMatchInfo && (
        <VsScreenModal
          matchInfo={vsMatchInfo}
          user={user}
          myBots={bots}
          onComplete={handleVsComplete}
          onClose={() => setVsMatchInfo(null)}
        />
      )}

      <Routes>
        <Route
          element={
            <Layout
              user={user}
              userStats={userStats}
              pvpElo={pvpElo}
              botElo={botElo}
              bots={bots}
              isBackendOffline={isBackendOffline}
              onRetryConnection={handleRetryConnection}
              eloToast={eloToast}
              onCloseToast={() => setEloToast(null)}
              onLogout={logout}
              challengeTarget={challengeTarget}
              setChallengeTarget={setChallengeTarget}
              handleConfirmChallenge={handleConfirmChallenge}
              selectedMatch={selectedMatch}
              setSelectedMatch={setSelectedMatch}
              replayData={replayData}
              handleDownloadLog={handleDownloadLog}
              isLocalGameOpen={isLocalGameOpen}
              setIsLocalGameOpen={setIsLocalGameOpen}
              localTimeControl={localTimeControl}
              setLocalTimeControl={setLocalTimeControl}
              pvpGameConfig={pvpGameConfig}
              setPvpGameConfig={setPvpGameConfig}
              roomGameConfig={roomGameConfig}
              setRoomGameConfig={setRoomGameConfig}
              handleCloseRoomGame={handleCloseRoomGame}
              handleOpenReplay={handleOpenReplay}
              handlePvpMatchFinished={handlePvpMatchFinished}
              outletContext={outletContext}
            />
          }
        >
          <Route
            index
            element={
              <GameModes
                onNavigateToBattleCode={() => navigate('/battle-code')}
                onOpenReplay={handleOpenReplay}
                matches={matches}
                user={user}
                pvpElo={pvpElo}
                onToast={(toast) => setEloToast(toast)}
                onMatchFound={(data) => setVsMatchInfo(data)}
                onStartLocalGame={(tc) => {
                  setLocalTimeControl(tc || null);
                  setIsLocalGameOpen(true);
                }}
                onStartRoomGame={(config) => setRoomGameConfig(config)}
              />
            }
          />
          <Route
            path="battle-code"
            element={
              <BattleCode
                bots={bots}
                submissions={submissions}
                onUploadSubmission={handleUploadSubmission}
                onChallenge={(target) => setChallengeTarget(target)}
                user={user}
                botElo={botElo}
                onDeleteBot={handleDeleteBot}
                onCleanBots={handleCleanBots}
                onToggleLadderActive={handleToggleLadderActive}
              />
            }
          />
          <Route
            path="leaderboard"
            element={
              <Leaderboard
                onChallenge={(target) => setChallengeTarget(target)}
                user={user}
                pvpElo={pvpElo}
                botElo={botElo}
                myBots={bots}
                matches={matches}
                userStats={userStats}
                onStartRoomGame={(config) => setRoomGameConfig(config)}
                onToast={(toast) => setEloToast(toast)}
                onOpenReplay={handleOpenReplay}
                onMatchFound={(data) => setVsMatchInfo(data)}
              />
            }
          />
          <Route
            path="tournaments"
            element={
              <Tournaments
                userBots={bots}
                userElo={botElo}
                user={user}
                onOpenReplay={(match, replay) => {
                  handleOpenReplay(
                    {
                      ...match,
                      mode: 'TOURNAMENT',
                      matchType: 'TOURNAMENT',
                      isRanked: false,
                    },
                    replay
                  );
                }}
                onToast={(toast) => setEloToast(toast)}
              />
            }
          />
          <Route
            path="profile"
            element={
              <Profile
                user={user}
                userStats={userStats}
                pvpElo={pvpElo}
                botElo={botElo}
                myBots={bots}
                matches={matches}
                matchHistory={matches}
                onOpenReplay={(match, replay) => {
                  handleOpenReplay(match, replay);
                }}
                onDeleteBot={handleDeleteBot}
                onCleanBots={handleCleanBots}
                onToggleLadderActive={handleToggleLadderActive}
                onUpdateStudentCode={handleUpdateStudentCode}
                onToast={(toast) => setEloToast(toast)}
                onRefreshData={() => refreshData()}
                onResetTestData={async () => {
                  setMatches([]);
                  setEloHistory([]);
                  setPvpElo(1200);
                  setBotElo(1200);
                  setServerStats({
                    matchesCount: 0,
                    wins: 0,
                    losses: 0,
                    winRate: 0,
                    pvpMatchesCount: 0,
                    pvpWins: 0,
                    pvpLosses: 0,
                    pvpDistinctOpponents: 0,
                    botMatchesCount: 0,
                    botWins: 0,
                    botLosses: 0,
                    distinctOpponents: 0,
                    isProvisional: true,
                  });
                  await refreshData();
                }}
              />
            }
          />
        </Route>
        {/* Bắt tất cả các route không hợp lệ, redirect về trang chủ */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppContent />
    </BrowserRouter>
  );
}
