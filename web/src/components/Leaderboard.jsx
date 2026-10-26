import React, { useState, useEffect } from 'react';
import { isOpponentUnlocked } from '../utils/gameRules.js';
import { buildApiUrl } from '../utils/api.js';
import BotDetailModal from './BotDetailModal.jsx';
import MatchmakingModal from './MatchmakingModal.jsx';

export default function Leaderboard({
  onChallenge,
  user,
  pvpElo = 1200,
  botElo = 1200,
  myBots = [],
  matches = [],
  userStats = null,
  onStartRoomGame = null,
  onToast = null,
  onOpenReplay = null,
  onMatchFound = null,
}) {
  const [category, setCategory] = useState('PLAYER'); // 'PLAYER' (PvP) | 'BOT' (Battle Code)
  const [searchTerm, setSearchTerm] = useState('');
  const [dbUsers, setDbUsers] = useState([]);
  const [dbLadderBots, setDbLadderBots] = useState([]);
  const [isLoadingUsers, setIsLoadingUsers] = useState(true);
  const [isMatching, setIsMatching] = useState(false);
  const [selectedBotForDetail, setSelectedBotForDetail] = useState(null);
  const [isMatchmakingModalOpen, setIsMatchmakingModalOpen] = useState(false);

  const handleRandomMatch = async () => {
    setIsMatching(true);
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

      const matchMode = category === 'BOT' ? 'BOT' : 'PVP';
      const activeLadderBot = myBots.find((b) => b.ladderEnabled) || null;

      if (matchMode === 'BOT' && !activeLadderBot) {
        if (onToast) {
          onToast({
            type: 'LOSE',
            text: '⚠️ Chưa có bot nào bật “Đang Đấu Ranked”. Hãy bật chế độ này trong Bot Của Tôi trước.'
          });
        }
        setIsMatching(false);
        return;
      }

      const res = await fetch(buildApiUrl('/api/matchmaking/join'), {
        method: 'POST',
        credentials: 'include',
        headers,
        body: JSON.stringify({
          mode: matchMode,
          botId: matchMode === 'BOT' ? (activeLadderBot?.id || null) : null,
          pvpElo: activeElo,
          userId: currentUser?.id,
          username: currentUser?.studentCode || currentUser?.username || currentUser?.email,
          studentCode: currentUser?.studentCode,
          email: currentUser?.email,
          displayName: currentUser?.displayName,
        }),
      });

      if (res.ok) {
        const data = await res.json();
        if (data && data.status === 'MATCHED') {
          if (matchMode === 'PVP' && data.roomCode && onStartRoomGame) {
            onStartRoomGame({
              roomCode: data.roomCode,
              isHost: true,
              isQuickMatch: true,
              targetOpponent: data.opponent,
              initialTimeControl: data.timeControl,
              timeControl: data.timeControl,
              playerToken: data.playerToken,
            });
            return;
          }
          if (onToast) {
            onToast({
              type: 'WIN',
              text: `🎉 Ghép trận thành công! ${matchMode === 'BOT' ? `Bot "${activeLadderBot?.name}"` : 'Người chơi'} chuẩn bị vào trận!`
            });
          }
          return;
        } else if (data && data.status === 'QUEUED') {
          if (onToast) {
            onToast({
              type: 'INFO',
              text: `⏳ ${data.message || 'Đã vào hàng đợi ghép trận. Hệ thống đang tìm đối thủ phù hợp!'}`
            });
          }
          return;
        }
      }

      // Not ok or error
      const err = await res.json().catch(() => null);
      const msg = err?.message || 'Hiện không có đối thủ nào phù hợp!';
      if (onToast) {
        onToast({ type: 'LOSE', text: `⚠️ ${msg}` });
      }
    } catch (err) {
      console.error('Lỗi khi ghép trận:', err);
      if (onToast) {
        onToast({ type: 'LOSE', text: '⚠️ Lỗi kết nối khi ghép trận!' });
      }
    } finally {
      setIsMatching(false);
    }
  };

  // 1. Fetch toàn bộ người chơi và bot đã xếp hạng từ Database (Durable Persistence)
  useEffect(() => {
    let isMounted = true;

    async function loadData() {
      setIsLoadingUsers(true);
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 4000);

      try {
        const res = await fetch(buildApiUrl('/api/users'), { 
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            'ngrok-skip-browser-warning': 'true',
          },
          signal: controller.signal,
        });
        if (res.ok) {
          const contentType = res.headers?.get('content-type') || '';
          if (contentType.includes('application/json')) {
            const data = await res.json();
            if (isMounted && Array.isArray(data)) {
              setDbUsers(data);
              try {
                localStorage.setItem('ottv2_leaderboard_users_cache', JSON.stringify(data));
              } catch {}
            }
          }
        }
      } catch (err) {
        console.warn('Backend /api/users not reachable:', err);
      }

      // Lấy danh sách bot thật từ PostgreSQL/Ladder Service
      try {
        const botRes = await fetch(buildApiUrl('/api/ladder/rankings'), {
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            'ngrok-skip-browser-warning': 'true',
          },
          signal: controller.signal,
        });
        if (botRes.ok) {
          const botData = await botRes.json();
          if (isMounted && Array.isArray(botData)) {
            setDbLadderBots(botData);
            try {
              localStorage.setItem('ottv2_leaderboard_bots_cache', JSON.stringify(botData));
            } catch {}
          }
        }
      } catch (err) {
        console.warn('Backend /api/ladder/rankings not reachable:', err);
      } finally {
        clearTimeout(timeoutId);
        if (isMounted) {
          try {
            const cachedUsers = localStorage.getItem('ottv2_leaderboard_users_cache');
            if (cachedUsers) {
              setDbUsers((prev) => (prev && prev.length > 0 ? prev : JSON.parse(cachedUsers)));
            }
            const cachedBots = localStorage.getItem('ottv2_leaderboard_bots_cache');
            if (cachedBots) {
              setDbLadderBots((prev) => (prev && prev.length > 0 ? prev : JSON.parse(cachedBots)));
            }
          } catch {}
          setIsLoadingUsers(false);
        }
      }
    }

    loadData();
    return () => {
      isMounted = false;
    };
  }, []);

  // 2. Định danh an toàn cho người dùng hiện tại
  const currentUser = user ? {
    ...user,
    username: user.username || user.studentCode || (user.email ? user.email.split('@')[0] : '') || user.displayName || '',
  } : null;

  const activeElo = category === 'PLAYER' ? pvpElo : botElo;

  // Thống kê thực tế của người dùng hiện tại (TUYỆT ĐỐI CHỈ TÍNH CÁC TRẬN RANKED)
  const isPvpCategory = category === 'PLAYER' || category === 'PVP';
  const rankedMatchesList = (matches || []).filter((m) => {
    const mode = String(m.mode || m.matchType || '').toUpperCase();
    const isRanked = m.isRanked === true || m.matchType === 'RANKED' || (mode === 'PVP' && m.isRanked !== false);
    return isRanked && (isPvpCategory ? mode === 'PVP' : mode === 'BOT' || mode === 'LADDER');
  });
  const realMatches = userStats
    ? (isPvpCategory ? (userStats.pvpMatchesCount ?? userStats.matchesCount) : (userStats.botMatchesCount ?? userStats.matchesCount))
    : rankedMatchesList.length;
  const realWins = userStats
    ? (isPvpCategory ? (userStats.pvpWins ?? userStats.wins) : (userStats.botWins ?? userStats.wins))
    : rankedMatchesList.filter((m) => m.winner === 'USER' || m.winner === 'A' || m.userWon === true).length;
  const realLosses = userStats
    ? (isPvpCategory ? (userStats.pvpLosses ?? userStats.losses) : (userStats.botLosses ?? userStats.losses))
    : rankedMatchesList.filter((m) => (m.winner !== 'USER' && m.winner !== 'A' && m.winner !== 'DRAW') || m.userWon === false).length;
  const realDraws = userStats
    ? Math.max(0, realMatches - realWins - realLosses)
    : rankedMatchesList.filter((m) => m.winner === 'DRAW').length;
  // The profile-level winRate combines PvP and bot games. Recompute it from
  // the selected ladder's counts so bot results cannot leak into PvP stats.
  const realWinRate = realMatches > 0 ? Math.round((realWins / realMatches) * 100) : 0;
  const realDistinctOpponents = userStats
    ? (isPvpCategory
      ? (userStats.pvpDistinctOpponents ?? userStats.distinctOpponents ?? 0)
      : (userStats.distinctOpponents || 0))
    : new Set(rankedMatchesList.map((m) => m.opponent).filter(Boolean)).size;
  const realIsProvisional = userStats
    ? (userStats.provisional !== undefined ? userStats.provisional : (userStats.isProvisional !== undefined ? userStats.isProvisional : (realMatches < 10 && realDistinctOpponents < 5)))
    : (realMatches < 10 && realDistinctOpponents < 5);

  // Helper tính trạng thái Online/Offline dựa vào timestamp truy cập cuối cùng từ DB
  const checkIsOnline = (u) => {
    if (!u || !u.lastActiveAt) return false;
    try {
      const lastActiveMs = new Date(u.lastActiveAt).getTime();
      const diffMinutes = (Date.now() - lastActiveMs) / (1000 * 60);
      return diffMinutes <= 15;
    } catch {
      return false;
    }
  };

  // 3. Chuẩn hóa danh sách người chơi thật từ Database (isMachine: false)
  const allRealUsersFromDB = dbUsers.map((u) => {
    const isThisMe = Boolean(
      currentUser && (
        (u.id && currentUser.id && String(u.id) === String(currentUser.id)) ||
        (u.username && currentUser.username && u.username === currentUser.username) ||
        (u.email && currentUser.email && u.email === currentUser.email) ||
        (u.studentCode && currentUser.studentCode && u.studentCode === currentUser.studentCode)
      )
    );

    const userPvpElo = isThisMe ? (pvpElo || u.pvpElo || 1200) : (u.pvpElo || 1200);
    const userBotElo = isThisMe ? (botElo || u.botElo || 1200) : (u.botElo || 1200);
    const userMatches = isThisMe ? Math.max(u.matchesCount || 0, realMatches) : (u.matchesCount || 0);
    const userWins = isThisMe ? Math.max(u.wins || 0, realWins) : (u.wins || 0);
    const userLosses = isThisMe ? Math.max(u.losses || 0, realLosses) : (u.losses || 0);
    const userDraws = isThisMe ? Math.max(u.draws || 0, realDraws) : (u.draws || 0);
    const userWinRate = userMatches > 0 ? Math.round((userWins / userMatches) * 100) : (u.winRate || 0);
    const userBotsCount = isThisMe ? (myBots.length || u.botsCount || 0) : (u.botsCount || 0);
    const userDistinctOpponents = isThisMe ? Math.max(u.distinctOpponents || 0, realDistinctOpponents) : (u.distinctOpponents || 0);
    const isProvisional = (userMatches < 10) && (userDistinctOpponents < 5);
    const isOnline = isThisMe ? true : checkIsOnline(u);

    return {
      id: u.id,
      userId: u.id,
      username: u.username || u.studentCode || (u.email ? u.email.split('@')[0] : `user_${u.id}`),
      displayName: u.displayName || u.studentCode || u.email || 'Người chơi',
      name: u.displayName || u.studentCode || u.email || 'Người chơi',
      code: u.studentCode || u.email,
      email: u.email,
      studentCode: u.studentCode,
      isMachine: false, // TUYỆT ĐỐI KHÔNG PHẢI LÀ MÁY
      pvpElo: userPvpElo,
      botElo: userBotElo,
      elo: category === 'PLAYER' ? userPvpElo : userBotElo,
      winRate: userWinRate,
      matchesCount: userMatches,
      matches: userMatches,
      wins: userWins,
      losses: userLosses,
      draws: userDraws,
      distinctOpponents: userDistinctOpponents,
      provisional: isProvisional,
      botsCount: userBotsCount,
      avatarSeed: u.displayName || u.studentCode || u.email || `Player_${u.id}`,
      isOnline,
      lastActiveAt: u.lastActiveAt,
      type: 'PLAYER',
    };
  });

  // Đảm bảo người dùng hiện tại luôn có trong danh sách dù DB trống hoặc offline
  const hasCurrentUserInList = allRealUsersFromDB.some((item) =>
    currentUser && !item.isMachine && (
      (item.id && currentUser.id && String(item.id) === String(currentUser.id)) ||
      (item.username && currentUser.username && item.username === currentUser.username) ||
      (item.email && currentUser.email && item.email === currentUser.email)
    )
  );

  if (!hasCurrentUserInList && currentUser && (currentUser.email || currentUser.studentCode || currentUser.displayName)) {
    allRealUsersFromDB.push({
      id: currentUser.id || 'me',
      userId: currentUser.id,
      username: currentUser.username || 'me',
      displayName: currentUser.displayName || currentUser.studentCode || currentUser.email || 'Người chơi',
      name: currentUser.displayName || currentUser.studentCode || currentUser.email || 'Người chơi',
      code: currentUser.studentCode || currentUser.email || 'Local Player',
      email: currentUser.email,
      studentCode: currentUser.studentCode,
      isMachine: false,
      pvpElo: pvpElo || 1200,
      botElo: botElo || 1200,
      elo: category === 'PLAYER' ? (pvpElo || 1200) : (botElo || 1200),
      winRate: realWinRate,
      matchesCount: realMatches,
      matches: realMatches,
      wins: realWins,
      losses: realLosses,
      draws: realDraws,
      distinctOpponents: realDistinctOpponents,
      provisional: realIsProvisional,
      botsCount: myBots.length || 0,
      avatarSeed: currentUser.displayName || currentUser.studentCode || currentUser.email || 'You',
      isOnline: true,
      lastActiveAt: new Date().toISOString(),
      type: 'PLAYER',
    });
  }

  // Deduplicate người dùng thật để tránh trùng lặp key khi DB hoặc cache trả về bản ghi trùng
  const seenUserKeys = new Set();
  const dedupedRealUsers = [];
  for (const u of allRealUsersFromDB) {
    const k = u.id != null ? `id_${u.id}` : (u.username ? `u_${u.username}` : (u.email ? `email_${u.email}` : null));
    if (k && seenUserKeys.has(k)) {
      continue;
    }
    if (k) seenUserKeys.add(k);
    dedupedRealUsers.push(u);
  }

  // 4. Danh sách BXH Bot từ Database (Per-Bot Rating)
  const mappedLadderBots = dbLadderBots.map((b) => {
    const isMine = Boolean(currentUser && (
      (b.ownerId && currentUser.id && String(b.ownerId) === String(currentUser.id)) ||
      (b.ownerUsername && currentUser.username && b.ownerUsername === currentUser.username)
    ));
    return {
      id: `ladder_bot_${b.botId}`,
      botId: b.botId,
      level: 1,
      username: b.ownerUsername || `bot_${b.botId}`,
      displayName: b.botName,
      botName: b.botName,
      name: b.botName,
      ownerUsername: b.ownerUsername,
      ownerDisplayName: b.ownerDisplayName || b.ownerUsername || `User #${b.ownerId}`,
      ownerId: b.ownerId,
      language: b.language || 'PYTHON',
      code: `BOT-${b.botId}`,
      isMachine: true,
      isBotEntity: true,
      pvpElo: b.botElo ?? b.elo ?? 1200,
      botElo: b.botElo ?? b.elo ?? 1200,
      elo: b.botElo ?? b.elo ?? 1200,
      description: `Chủ sở hữu: ${b.ownerDisplayName || b.ownerUsername || 'User #' + b.ownerId}`,
      winRate: b.winRate !== undefined ? b.winRate : (b.games > 0 ? Math.round(((b.wins || 0) / b.games) * 100) : 0),
      matchesCount: b.matchesCount ?? b.games ?? 0,
      matches: b.matchesCount ?? b.games ?? 0,
      wins: b.wins || 0,
      losses: b.losses || 0,
      draws: b.draws || 0,
      distinctOpponents: b.distinctOpponents || 0,
      isProvisional: b.isProvisional !== undefined ? b.isProvisional : (b.provisional !== undefined ? b.provisional : ((b.games || 0) < 10 && (b.distinctOpponents || 0) < 5)),
      provisional: b.isProvisional !== undefined ? b.isProvisional : (b.provisional !== undefined ? b.provisional : ((b.games || 0) < 10 && (b.distinctOpponents || 0) < 5)),
      botsCount: 0,
      avatarSeed: b.botName || `Bot_${b.botId}`,
      isOnline: true,
      type: 'BOT',
      isSparring: false,
      isMyBot: isMine,
    };
  });

  // 5. Phân tách dữ liệu theo Tab:
  // - Tab PvP Rating: CHỈ lấy danh sách người chơi thật từ Database.
  // - Tab Bot Rating: Hoàn toàn xếp hạng theo thực thể Bot độc lập.
  //   Quy tắc:
  //   1) Bot/Người chơi đạt chuẩn (>= 10 trận Ranked VÀ >= 5 đối thủ riêng biệt): Cấp số thứ hạng chính thức (#1..#30)
  //   2) Bot chưa đủ điều kiện: Rank = null (hiển thị '-'), đẩy xuống cuối danh sách.
  //   3) Bộ lọc cá nhân: Chỉ hiển thị các bot chưa xếp hạng thuộc quyền sở hữu của người dùng hiện tại (isMyBot === true).
  let officialList = [];
  let personalUnrankedList = [];

  if (category === 'PLAYER' || category === 'PVP') {
    // Tab PvP: Xếp hạng người chơi thật
    const allPlayers = dedupedRealUsers.map((u) => {
      const isProv = u.provisional !== undefined
        ? u.provisional
        : ((u.matchesCount || 0) < 10 && (u.distinctOpponents || 0) < 5);
      return {
        ...u,
        elo: u.pvpElo ?? u.elo ?? 1200,
        pvpElo: u.pvpElo ?? u.elo ?? 1200,
        type: 'PLAYER',
        isProvisional: isProv,
        provisional: isProv,
      };
    });

    const officialPlayers = allPlayers
      .filter((p) => !p.isProvisional)
      .sort((a, b) => (b.pvpElo ?? 1200) - (a.pvpElo ?? 1200));

    // Giới hạn Top 30 cho bảng xếp hạng chính thức
    officialList = officialPlayers.slice(0, 30);
    officialList.forEach((item, index) => {
      item.rank = index + 1;
    });

    // Unranked: Chỉ lấy người dùng hiện tại nếu chưa đủ điều kiện xếp hạng
    const unrankedPlayers = allPlayers.filter((p) => p.isProvisional);
    personalUnrankedList = unrankedPlayers.filter((p) => {
      if (!currentUser) return false;
      return (
        (p.id && currentUser.id && String(p.id) === String(currentUser.id)) ||
        (p.username && currentUser.username && p.username === currentUser.username) ||
        (p.email && currentUser.email && p.email === currentUser.email)
      );
    });
    personalUnrankedList.forEach((item) => {
      item.rank = null; // Chưa xếp hạng: '-'
    });
  } else {
    // Tab Bot: Xếp hạng các thực thể Bot độc lập theo botElo
    const knownLadderBotIds = new Set(mappedLadderBots.map((b) => String(b.botId)));
    const myExtraBots = (myBots || []).filter((b) => !knownLadderBotIds.has(String(b.id))).map((b) => ({
      id: `ladder_bot_${b.id}`,
      botId: b.id,
      level: 1,
      username: currentUser?.username || 'user',
      displayName: b.name,
      botName: b.name,
      name: b.name,
      ownerUsername: currentUser?.username || 'Bạn',
      ownerDisplayName: currentUser?.displayName || currentUser?.username || 'Bạn',
      ownerId: currentUser?.id,
      language: b.language || 'PYTHON',
      code: `BOT-${b.id}`,
      isMachine: true,
      isBotEntity: true,
      pvpElo: b.elo ?? 1200,
      botElo: b.elo ?? 1200,
      elo: b.elo ?? 1200,
      description: `Chủ sở hữu: ${currentUser?.displayName || currentUser?.username || 'Bạn'}`,
      winRate: b.winRate ?? 0,
      matchesCount: b.games ?? 0,
      matches: b.games ?? 0,
      wins: b.wins || 0,
      losses: b.losses || 0,
      draws: b.draws || 0,
      distinctOpponents: 0,
      isProvisional: true,
      provisional: true,
      botsCount: 0,
      avatarSeed: b.name,
      isOnline: true,
      type: 'BOT',
      isSparring: false,
      isMyBot: true,
    }));

    const allCandidateBots = [
      ...mappedLadderBots,
      ...myExtraBots,
    ];

    // Lọc bot chính thức: đã hoàn tất >= 10 trận Ranked VÀ gặp >= 5 đối thủ
    const officialBots = allCandidateBots
      .filter((b) => !b.isProvisional && !b.provisional)
      .sort((a, b) => (b.botElo ?? 1200) - (a.botElo ?? 1200));

    // Bảng xếp hạng chính thức chỉ lấy Top 30 bot có ELO cao nhất
    officialList = officialBots.slice(0, 30);
    officialList.forEach((item, index) => {
      item.rank = index + 1;
    });

    // Unranked: Chỉ hiển thị những bot chưa xếp hạng thuộc quyền sở hữu của người dùng hiện tại (isMyBot === true)
    // Tuyệt đối không hiển thị tràn lan các bot chưa xếp hạng của những người chơi khác.
    const unrankedBots = allCandidateBots.filter((b) => b.isProvisional || b.provisional);
    personalUnrankedList = unrankedBots
      .filter((b) => Boolean(
        b.isMyBot ||
        (currentUser && (
          (b.ownerId && currentUser.id && String(b.ownerId) === String(currentUser.id)) ||
          (b.ownerUsername && currentUser.username && b.ownerUsername === currentUser.username)
        ))
      ))
      .sort((a, b) => (b.botElo ?? 1200) - (a.botElo ?? 1200));

    personalUnrankedList.forEach((item) => {
      item.rank = null; // Hiển thị '-' (Chưa Xếp Hạng)
    });
  }

  const combinedLeaderboard = [...officialList, ...personalUnrankedList];
  const activeList = combinedLeaderboard;

  // Single Source of Truth: Tìm thông tin của chính người dùng hiện tại trong activeList
  const myRankInfo = activeList.find((item) => {
    if (!currentUser) return false;
    if (category === 'BOT') {
      return item.isMyBot ||
        (item.ownerId && currentUser.id && String(item.ownerId) === String(currentUser.id)) ||
        (item.ownerUsername && currentUser.username && item.ownerUsername === currentUser.username);
    }
    return !item.isMachine && (
      (item.id && currentUser.id && String(item.id) === String(currentUser.id)) ||
      (item.username && currentUser.username && item.username === currentUser.username) ||
      (item.email && currentUser.email && item.email === currentUser.email) ||
      (item.studentCode && currentUser.studentCode && item.studentCode === currentUser.studentCode)
    );
  });

  // Tìm thứ hạng của người dùng hiện tại (hoặc bot cao nhất của người dùng)
  const userRankIndex = activeList.findIndex((item) => {
    if (!currentUser) return false;
    if (category === 'BOT') {
      return item.isMyBot ||
             (item.ownerId && currentUser.id && String(item.ownerId) === String(currentUser.id)) ||
             (item.ownerUsername && currentUser.username && item.ownerUsername === currentUser.username);
    }
    return !item.isMachine && (
      (item.id && currentUser.id && String(item.id) === String(currentUser.id)) ||
      (item.username && currentUser.username && item.username === currentUser.username) ||
      (item.email && currentUser.email && item.email === currentUser.email) ||
      (item.studentCode && currentUser.studentCode && item.studentCode === currentUser.studentCode)
    );
  });

  // Render chung huy hiệu Xếp Hạng Đồng Nhất (Single Source of Truth)
  const renderProvisionalBadge = (item) => {
    const isProv = item?.isProvisional !== undefined
      ? item.isProvisional
      : (item?.provisional !== undefined
          ? item.provisional
          : (((item?.matchesCount ?? item?.matches ?? 0) < 10) && ((item?.distinctOpponents ?? 0) < 5)));

    if (isProv) {
      return (
        <span className="text-[9px] font-bold px-2 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/40 inline-flex items-center gap-1 flex-shrink-0">
          <span>⚠️</span>
          <span>Tạm thời</span>
        </span>
      );
    }
    return (
      <span className="text-[9px] font-bold px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 inline-flex items-center gap-1 flex-shrink-0">
        <span>✓</span>
        <span>Chính thức</span>
      </span>
    );
  };

  const filteredList = activeList.filter((item) => {
    const q = searchTerm.toLowerCase();
    const displayName = (item.displayName || item.name || item.botName || '').toLowerCase();
    const username = (item.username || '').toLowerCase();
    const ownerName = (item.ownerDisplayName || item.ownerUsername || '').toLowerCase();
    const desc = (item.description || '').toLowerCase();
    return displayName.includes(q) || username.includes(q) || ownerName.includes(q) || desc.includes(q);
  });

  return (
    <div className="max-w-6xl mx-auto space-y-8 animate-fadeIn">
      {/* Header and Actions */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h2 className="text-3xl font-black text-white tracking-tight flex items-center gap-3">
            <span>🏆</span> Bảng Xếp Hạng Leo Tháp
          </h2>
          <p className="text-sm text-zinc-400 mt-1">
            Tách biệt hoàn toàn giữa Đấu Trực Tiếp (PvP) và Đấu Bot Tự Động (Battle Code 24/7)
          </p>
        </div>

        {/* Nút Lớn Nổi Bật Tìm Trận Xếp Hạng (Ranked) */}
        <button
          type="button"
          onClick={() => setIsMatchmakingModalOpen(true)}
          className="px-6 py-3.5 bg-gradient-to-r from-emerald-500 via-teal-500 to-blue-600 hover:from-emerald-400 hover:via-teal-400 hover:to-blue-500 text-white font-black text-sm rounded-2xl shadow-xl shadow-emerald-950/50 flex items-center justify-center gap-2.5 transition-all transform hover:scale-[1.03] active:scale-[0.98] border border-emerald-400/40 cursor-pointer flex-shrink-0"
        >
          <span className="text-xl animate-pulse">🔍</span>
          <span>Tìm Trận Xếp Hạng (Ranked)</span>
          <span className="text-[10px] bg-black/40 px-2 py-0.5 rounded-full border border-white/20 font-mono tracking-wider">
            ±300 ELO
          </span>
        </button>
      </div>

      {/* Search and Category Filters */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <input
          type="text"
          placeholder={category === 'PLAYER' ? 'Tìm người chơi...' : 'Tìm bot đối thủ / máy luyện tập...'}
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          className="bg-zinc-850 border border-zinc-700/80 rounded-xl px-3.5 py-2 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500 w-full sm:w-72"
        />

        <div className="flex bg-zinc-950 p-1 rounded-2xl border border-zinc-800 self-start sm:self-auto">
          <button
            type="button"
            onClick={() => setCategory('PLAYER')}
            className={`px-4 py-2 rounded-xl text-xs font-bold transition-all flex items-center gap-1.5 ${
              category === 'PLAYER'
                ? 'bg-blue-600 text-white shadow-lg shadow-blue-950/40'
                : 'bg-transparent text-zinc-400 hover:text-white'
            }`}
          >
            <span>🎮</span>
            <span>BXH Đấu Trực Tiếp (PvP)</span>
          </button>
          <button
            type="button"
            onClick={() => setCategory('BOT')}
            className={`px-4 py-2 rounded-xl text-xs font-bold transition-all flex items-center gap-1.5 ${
              category === 'BOT'
                ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-950/40'
                : 'bg-transparent text-zinc-400 hover:text-white'
            }`}
          >
            <span>🤖</span>
            <span>BXH Đấu Bot (24/7)</span>
          </button>
        </div>
      </div>

      {/* PvP Transparency Notice Banner */}
      {category === 'PLAYER' && (
        <div className="bg-blue-950/40 border border-blue-500/40 p-4 rounded-3xl flex items-center gap-3 text-xs text-blue-200 shadow-md">
          <span className="text-xl flex-shrink-0">ℹ️</span>
          <p className="leading-relaxed">
            <strong className="text-white">Minh bạch cơ chế Tính Điểm ELO:</strong> Chế độ Đấu Hạng (Ranked) được hệ thống ghép cặp tự động. Các trận thách đấu chỉ định đích danh từ Bảng Xếp Hạng là Đấu Giao Hữu (Friendly - Không thay đổi ELO).
          </p>
        </div>
      )}

      {/* Progressive Ladder Status Banner */}
      <div className="bg-gradient-to-r from-zinc-850 via-zinc-900 to-zinc-850 border border-zinc-750 p-4 rounded-3xl flex flex-wrap items-center justify-between gap-4 shadow-xl">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-2xl bg-amber-500/10 border border-amber-500/30 flex items-center justify-center text-2xl">
            ⚡
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-mono font-bold text-amber-400 uppercase tracking-wider block">
                TIẾN TRÌNH LEO THÁP HIỆN TẠI
              </span>
              {renderProvisionalBadge(myRankInfo || { matchesCount: realMatches, distinctOpponents: realDistinctOpponents })}
            </div>
            <p className="text-white font-extrabold text-sm mt-0.5">
              Điểm {category === 'PLAYER' ? 'PvP ELO' : 'Bot ELO'} Của Bạn:{' '}
              <span className="text-amber-400 font-mono text-base">{(myRankInfo?.elo ?? activeElo) || 1200} ELO</span> · {myRankInfo?.rank ? `Hạng #${myRankInfo.rank}` : 'Chưa Xếp Hạng (-)'}
            </p>
            <p className="text-[11px] text-zinc-400 font-mono mt-0.5">
              Đã đấu: <strong className="text-white">{myRankInfo?.matchesCount ?? myRankInfo?.matches ?? realMatches}</strong> trận Ranked · Gặp <strong className="text-white">{myRankInfo?.distinctOpponents ?? realDistinctOpponents}</strong> đối thủ riêng biệt
            </p>
          </div>
        </div>

        <div className="flex items-center gap-4 text-xs font-mono">
          <div className="bg-zinc-950 px-3.5 py-2 rounded-xl border border-zinc-800">
            <span className="text-zinc-500 block text-[10px]">NGƯỠNG MỞ KHÓA TỐI ĐA (±300)</span>
            <span className="text-emerald-400 font-bold">{((myRankInfo?.elo ?? activeElo) || 1200) + 300} ELO</span>
          </div>
          <div className="bg-zinc-950 px-3.5 py-2 rounded-xl border border-zinc-800">
            <span className="text-zinc-500 block text-[10px]">CHẾ ĐỘ ĐANG CHỌN</span>
            <span className={`font-bold ${category === 'PLAYER' ? 'text-blue-400' : 'text-emerald-400'}`}>
              {category === 'PLAYER' ? `🎮 PvP (${dedupedRealUsers.length} Người chơi)` : '🤖 Đấu Bot Tự Động (24/7)'}
            </span>
          </div>
        </div>
      </div>

      {/* Top 3 Podium */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 pt-2">
        {/* Hạng 2 - Bạc */}
        <div className="order-2 md:order-1 bg-zinc-850/90 border border-slate-400/40 rounded-3xl p-6 text-center space-y-3 relative shadow-xl hover:-translate-y-1 transition-transform">
          <span className="absolute -top-3.5 left-1/2 -translate-x-1/2 px-3 py-1 bg-slate-300 text-zinc-950 font-black text-[11px] rounded-full uppercase shadow">
            🥈 Hạng 2 · Bạc
          </span>
          <img
            src={`https://api.dicebear.com/7.x/bottts/svg?seed=${activeList[1]?.avatarSeed || activeList[1]?.botName || '2'}`}
            alt="Avatar"
            className="w-16 h-16 mx-auto rounded-2xl bg-zinc-800 p-1.5 border border-slate-400"
          />
          <div>
            <h4
              onClick={() => {
                if (category === 'BOT' && activeList[1]) {
                  setSelectedBotForDetail(activeList[1]);
                }
              }}
              className={`font-extrabold text-white text-base truncate px-2 flex items-center justify-center gap-1.5 ${
                category === 'BOT' ? 'cursor-pointer hover:text-emerald-400 hover:underline' : ''
              }`}
              title={category === 'BOT' ? 'Xem chi tiết Bot' : ''}
            >
              <span className="truncate">{activeList[1]?.botName || activeList[1]?.displayName || activeList[1]?.username || '---'}</span>
              {category === 'BOT' && activeList[1]?.language && (
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-zinc-700/80 text-zinc-300 border border-zinc-600 font-semibold uppercase flex-shrink-0">
                  {activeList[1].language}
                </span>
              )}
            </h4>
            <p className="text-xs text-zinc-400 font-mono truncate px-2 mt-0.5">
              {category === 'BOT'
                ? (activeList[1]?.ownerDisplayName ? `Chủ sở hữu: ${activeList[1].ownerDisplayName}` : (activeList[1]?.description || 'Hệ thống'))
                : (activeList[1]?.isMachine
                    ? activeList[1]?.description
                    : `${activeList[1]?.botsCount || 0} bot sở hữu`)}
            </p>
          </div>
          <div className="text-2xl font-black font-mono text-slate-300 tabular-nums">
            {(category === 'PLAYER' ? activeList[1]?.pvpElo : activeList[1]?.botElo) ?? activeList[1]?.elo ?? 1200} ELO
          </div>
          <p className="text-xs text-zinc-400 tabular-nums">
            {activeList[1]?.winRate ?? 0}% Thắng · {activeList[1]?.matchesCount ?? activeList[1]?.matches ?? 0} Trận Ranked
          </p>
          <div className="flex items-center justify-center gap-1.5 mt-1">
            <span className="text-[10px] text-zinc-400 font-mono">
              👥 {activeList[1]?.distinctOpponents ?? (activeList[1]?.isSparring ? 10 : 0)} đối thủ
            </span>
            {renderProvisionalBadge(activeList[1])}
          </div>
        </div>

        {/* Hạng 1 - Vàng Hoàng Gia */}
        <div className="order-1 md:order-2 bg-gradient-to-b from-amber-500/10 to-zinc-850 border-2 border-amber-400 rounded-3xl p-6 text-center space-y-3 relative shadow-2xl shadow-amber-950/20 md:-translate-y-4 hover:-translate-y-5 transition-transform">
          <span className="absolute -top-3.5 left-1/2 -translate-x-1/2 px-3.5 py-1 bg-amber-400 text-zinc-950 font-black text-[11px] rounded-full uppercase shadow">
            👑 Quán Quân · Vàng
          </span>
          <img
            src={`https://api.dicebear.com/7.x/bottts/svg?seed=${activeList[0]?.avatarSeed || activeList[0]?.botName || '1'}`}
            alt="Avatar"
            className="w-20 h-20 mx-auto rounded-2xl bg-zinc-800 p-1.5 border-2 border-amber-400"
          />
          <div>
            <h4
              onClick={() => {
                if (category === 'BOT' && activeList[0]) {
                  setSelectedBotForDetail(activeList[0]);
                }
              }}
              className={`font-black text-white text-lg truncate px-2 flex items-center justify-center gap-1.5 ${
                category === 'BOT' ? 'cursor-pointer hover:text-emerald-400 hover:underline' : ''
              }`}
              title={category === 'BOT' ? 'Xem chi tiết Bot' : ''}
            >
              <span className="truncate">{activeList[0]?.botName || activeList[0]?.displayName || activeList[0]?.username || '---'}</span>
              {category === 'BOT' && activeList[0]?.language && (
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-zinc-700/80 text-zinc-300 border border-zinc-600 font-semibold uppercase flex-shrink-0">
                  {activeList[0].language}
                </span>
              )}
            </h4>
            <p className="text-xs text-amber-400 font-mono truncate px-2 mt-0.5">
              {category === 'BOT'
                ? (activeList[0]?.ownerDisplayName ? `Chủ sở hữu: ${activeList[0].ownerDisplayName}` : (activeList[0]?.description || 'Hệ thống'))
                : (activeList[0]?.isMachine
                    ? activeList[0]?.description
                    : `${activeList[0]?.botsCount || 0} bot sở hữu`)}
            </p>
          </div>
          <div className="text-3xl font-black font-mono text-amber-400 tabular-nums">
            {(category === 'PLAYER' ? activeList[0]?.pvpElo : activeList[0]?.botElo) ?? activeList[0]?.elo ?? 1200} ELO
          </div>
          <p className="text-xs text-zinc-400 tabular-nums">
            {activeList[0]?.winRate ?? 0}% Thắng · {activeList[0]?.matchesCount ?? activeList[0]?.matches ?? 0} Trận Ranked
          </p>
          <div className="flex items-center justify-center gap-1.5 mt-1">
            <span className="text-[10px] text-zinc-400 font-mono">
              👥 {activeList[0]?.distinctOpponents ?? (activeList[0]?.isSparring ? 10 : 0)} đối thủ
            </span>
            {renderProvisionalBadge(activeList[0])}
          </div>
        </div>

        {/* Hạng 3 - Đồng */}
        <div className="order-3 bg-zinc-850/90 border border-amber-700/50 rounded-3xl p-6 text-center space-y-3 relative shadow-xl hover:-translate-y-1 transition-transform">
          <span className="absolute -top-3.5 left-1/2 -translate-x-1/2 px-3 py-1 bg-amber-700 text-white font-black text-[11px] rounded-full uppercase shadow">
            🥉 Hạng 3 · Đồng
          </span>
          <img
            src={`https://api.dicebear.com/7.x/bottts/svg?seed=${activeList[2]?.avatarSeed || activeList[2]?.botName || '3'}`}
            alt="Avatar"
            className="w-16 h-16 mx-auto rounded-2xl bg-zinc-800 p-1.5 border border-amber-700"
          />
          <div>
            <h4
              onClick={() => {
                if (category === 'BOT' && activeList[2]) {
                  setSelectedBotForDetail(activeList[2]);
                }
              }}
              className={`font-extrabold text-white text-base truncate px-2 flex items-center justify-center gap-1.5 ${
                category === 'BOT' ? 'cursor-pointer hover:text-emerald-400 hover:underline' : ''
              }`}
              title={category === 'BOT' ? 'Xem chi tiết Bot' : ''}
            >
              <span className="truncate">{activeList[2]?.botName || activeList[2]?.displayName || activeList[2]?.username || '---'}</span>
              {category === 'BOT' && activeList[2]?.language && (
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-zinc-700/80 text-zinc-300 border border-zinc-600 font-semibold uppercase flex-shrink-0">
                  {activeList[2].language}
                </span>
              )}
            </h4>
            <p className="text-xs text-zinc-400 font-mono truncate px-2 mt-0.5">
              {category === 'BOT'
                ? (activeList[2]?.ownerDisplayName ? `Chủ sở hữu: ${activeList[2].ownerDisplayName}` : (activeList[2]?.description || 'Hệ thống'))
                : (activeList[2]?.isMachine
                    ? activeList[2]?.description
                    : `${activeList[2]?.botsCount || 0} bot sở hữu`)}
            </p>
          </div>
          <div className="text-2xl font-black font-mono text-amber-600 tabular-nums">
            {(category === 'PLAYER' ? activeList[2]?.pvpElo : activeList[2]?.botElo) ?? activeList[2]?.elo ?? 1200} ELO
          </div>
          <p className="text-xs text-zinc-400 tabular-nums">
            {activeList[2]?.winRate ?? 0}% Thắng · {activeList[2]?.matchesCount ?? activeList[2]?.matches ?? 0} Trận Ranked
          </p>
          <div className="flex items-center justify-center gap-1.5 mt-1">
            <span className="text-[10px] text-zinc-400 font-mono">
              👥 {activeList[2]?.distinctOpponents ?? (activeList[2]?.isSparring ? 10 : 0)} đối thủ
            </span>
            {renderProvisionalBadge(activeList[2])}
          </div>
        </div>
      </div>

      {/* Danh sách BXH theo CSS Grid cố định cột */}
      <div className="bg-zinc-850 border border-zinc-750 rounded-3xl overflow-hidden shadow-2xl">
        <div className="max-h-[620px] overflow-y-auto custom-scrollbar">
          {/* Table Header Bar */}
          {category === 'BOT' ? (
            <div className="sticky top-0 z-10 grid grid-cols-[56px_1fr_160px_110px_100px_115px_155px] items-center gap-3 px-5 py-3.5 bg-zinc-900/95 backdrop-blur-md border-b border-zinc-750 text-xs font-mono text-zinc-400 font-bold uppercase tracking-wider shadow-sm">
              <span className="text-center">#</span>
              <span>Tên Bot · Ngôn Ngữ · Chủ Sở Hữu (Top 30)</span>
              <span className="text-right">Thành Tích Ranked</span>
              <span className="text-right">Đối Thủ</span>
              <span className="text-right">Điểm ELO</span>
              <span className="text-center">Trạng Thái</span>
              <span className="text-right pr-2">Hành Động</span>
            </div>
          ) : (
            <div className="sticky top-0 z-10 grid grid-cols-[56px_1fr_170px_100px_170px] items-center gap-4 px-5 py-3.5 bg-zinc-900/95 backdrop-blur-md border-b border-zinc-750 text-xs font-mono text-zinc-400 font-bold uppercase tracking-wider shadow-sm">
              <span className="text-center">#</span>
              <span>Người Chơi (Top 30)</span>
              <span className="text-right">Thành Tích Ranked</span>
              <span className="text-right">Điểm ELO</span>
              <span className="text-right pr-2">Hành Động</span>
            </div>
          )}

          {/* Rows */}
          <div className="divide-y divide-zinc-800">
            {filteredList.map((item, idx) => {
              const isMyBot = category === 'BOT' && Boolean(
                item.isMyBot ||
                (currentUser && (
                  (item.ownerId && currentUser.id && String(item.ownerId) === String(currentUser.id)) ||
                  (item.ownerUsername && currentUser.username && item.ownerUsername === currentUser.username)
                ))
              );

              // Điều kiện isMe an toàn: Đối với PLAYER là user chính mình; đối với BOT là bot do chính mình sở hữu
              const isMe = category === 'PLAYER'
                ? Boolean(
                    currentUser &&
                    !item.isMachine &&
                    (
                      (item.id && currentUser.id && String(item.id) === String(currentUser.id)) ||
                      (item.username && currentUser.username && item.username === currentUser.username) ||
                      (item.email && currentUser.email && item.email === currentUser.email) ||
                      (item.studentCode && currentUser.studentCode && item.studentCode === currentUser.studentCode)
                    )
                  )
                : isMyBot;

              const isOnline = item.isMachine ? true : 
                (isMe ? true : (item.lastActiveAt ? (Date.now() - new Date(item.lastActiveAt).getTime() < 15 * 60 * 1000) : Boolean(item.isOnline)));

              const rowKey = category === 'BOT'
                ? (item.botId ? `bot-${item.botId}` : item.id || `bot-row-${idx}`)
                : (item.isMachine
                    ? (item.id || item.username || `machine-${idx}`)
                    : `user-${item.id || item.username || item.studentCode || idx}`);

              // ELO Gate Rule Check: Chỉ khóa nút nếu chênh lệch ELO giữa currentUser và opponent lớn hơn 300
              const itemElo = category === 'PLAYER' ? (item.pvpElo ?? item.elo ?? 1200) : (item.botElo ?? item.elo ?? 1200);
              const isUnlocked = category === 'PLAYER'
                ? Math.abs(itemElo - activeElo) <= 300
                : (item.rank == null ? true : isOpponentUnlocked(itemElo, activeElo, idx, userRankIndex, 300));

              // Dấu phân cách cho phần bot đang phân hạng (Unranked)
              const isFirstUnrankedBot = category === 'BOT' && item.rank == null && (idx === 0 || filteredList[idx - 1]?.rank != null);
              const isFirstUnrankedPlayer = category === 'PLAYER' && item.rank == null && (idx === 0 || filteredList[idx - 1]?.rank != null);

              // Giao diện Hàng cho Tab Đấu Bot (Per-Bot Rating Layout 7 cột)
              if (category === 'BOT') {
                return (
                  <React.Fragment key={rowKey}>
                    {isFirstUnrankedBot && (
                      <div className="px-5 py-3 bg-gradient-to-r from-amber-950/40 via-zinc-900 to-amber-950/40 border-y border-amber-500/30 text-xs text-amber-300 font-mono font-bold flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <span className="text-base">⏳</span>
                          <span>BOT CỦA BẠN ĐANG PHÂN HẠNG (Chưa hoàn tất 10 trận Ranked hoặc 5 đối thủ riêng biệt)</span>
                        </div>
                        <span className="text-[10px] text-zinc-400 font-normal bg-zinc-900/80 px-2 py-0.5 rounded border border-zinc-700">Chỉ hiển thị cho bạn</span>
                      </div>
                    )}
                    <div
                      className={`grid grid-cols-[56px_1fr_160px_110px_100px_115px_155px] items-center gap-3 px-5 py-3.5 transition-all duration-200 group relative ${
                        isMe ? 'bg-emerald-950/30 hover:bg-emerald-950/40 border-l-4 border-l-emerald-500' : 'hover:bg-zinc-800/70'
                      }`}
                    >
                      {/* Cột 1: Thứ Hạng (#) */}
                      <div className="text-center font-mono">
                        {item.rank != null ? (
                          <span
                            className={`text-sm ${
                              item.rank === 1
                                ? 'text-amber-400 font-black'
                                : item.rank === 2
                                ? 'text-slate-300 font-black'
                                : item.rank === 3
                                ? 'text-amber-600 font-black'
                                : 'text-zinc-500 font-bold'
                            }`}
                          >
                            #{item.rank}
                          </span>
                        ) : (
                          <span
                            className="text-xs font-bold text-amber-400/90 px-1.5 py-0.5 rounded bg-amber-500/10 border border-amber-500/30"
                            title="Chưa Xếp Hạng: Cần tối thiểu 10 trận Ranked hoặc 5 đối thủ riêng biệt để cấp thứ hạng chính thức"
                          >
                            -
                          </span>
                        )}
                      </div>

                      {/* Cột 2: Avatar + Tên Bot + Ngôn ngữ + Chủ sở hữu */}
                      <div className="flex items-center gap-3 min-w-0">
                        <img
                          src={`https://api.dicebear.com/7.x/bottts/svg?seed=${item.avatarSeed || item.id || item.botName}`}
                          alt="avatar"
                          className="w-10 h-10 rounded-xl bg-zinc-900 border border-zinc-750 p-1 flex-shrink-0"
                        />
                        <div className="min-w-0 flex-1">
                          <p className="text-sm font-bold text-white flex items-center gap-2 truncate">
                            <span
                              onClick={() => setSelectedBotForDetail(item)}
                              className="cursor-pointer hover:text-emerald-400 hover:underline truncate"
                              title="Bấm để xem chi tiết Bot"
                            >
                              {item.botName || item.displayName || item.name}
                            </span>
                            {item.language && (
                              <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-300 border border-zinc-700 font-semibold uppercase flex-shrink-0">
                                {item.language}
                              </span>
                            )}
                            {isMe && (
                              <span className="text-[10px] bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 px-1.5 py-0.5 rounded font-extrabold uppercase flex-shrink-0">
                                [CỦA BẠN]
                              </span>
                            )}
                          </p>
                          <div className="text-xs text-zinc-400 truncate mt-0.5 font-mono">
                            {item.ownerDisplayName ? `Chủ sở hữu: ${item.ownerDisplayName}` : (item.description || 'Hệ thống')}
                          </div>
                        </div>
                      </div>

                      {/* Cột 3: Thành Tích Ranked */}
                      <div className="text-right tabular-nums">
                        <p className="text-xs font-semibold text-zinc-300">
                          <span className="text-emerald-400">{item.wins ?? 0}T</span> -{' '}
                          <span className="text-zinc-400">{item.draws ?? 0}H</span> -{' '}
                          <span className="text-rose-400">{item.losses ?? 0}B</span>{' '}
                          <span className="text-zinc-500 font-mono text-[11px]">({item.winRate ?? 0}%)</span>
                        </p>
                        <p className="text-[11px] text-zinc-400 font-mono mt-0.5">
                          {item.matchesCount ?? item.matches ?? 0} trận Ranked
                        </p>
                      </div>

                      {/* Cột 4: Số đối thủ riêng biệt */}
                      <div className="text-right tabular-nums">
                        <span className="text-xs font-mono font-bold text-zinc-300">
                          {item.distinctOpponents ?? (item.isSparring ? 10 : 0)} đối thủ
                        </span>
                        <p className="text-[10px] text-zinc-500 font-mono">riêng biệt</p>
                      </div>

                      {/* Cột 5: Điểm ELO */}
                      <div className="text-right tabular-nums">
                        <span className="text-base font-black font-mono text-amber-400">{itemElo}</span>
                        <p className="text-[10px] text-zinc-500 font-semibold uppercase">ELO</p>
                      </div>

                      {/* Cột 6: Trạng thái (Tạm thời vs Chính thức) */}
                      <div className="flex justify-center">
                        {renderProvisionalBadge(item)}
                      </div>

                      {/* Cột 7: Nút Hành động */}
                      <div className="flex items-center justify-end">
                        {isMe ? (
                          <div className="w-[145px] text-center text-xs text-emerald-400 font-mono font-bold opacity-80 py-1.5 rounded-xl bg-emerald-500/10 border border-emerald-500/30">
                            * Bot của bạn
                          </div>
                        ) : !isUnlocked ? (
                          <div className="relative group/lock w-[145px]">
                            <button
                              type="button"
                              disabled
                              className="w-full py-1.5 rounded-xl bg-zinc-900 border border-zinc-750 text-zinc-500 font-semibold text-xs flex items-center justify-center gap-1.5 cursor-not-allowed opacity-80"
                            >
                              <span>🔒</span>
                              <span>Khóa (±300 ELO)</span>
                            </button>
                            <div className="absolute right-0 bottom-full mb-2 hidden group-hover/lock:block z-30 w-56 p-2.5 rounded-xl bg-zinc-900 border border-zinc-700 text-[11px] text-zinc-300 font-medium shadow-2xl">
                              Chênh lệch ELO vượt quá 300 điểm ({Math.abs(itemElo - activeElo)} ELO)!
                            </div>
                          </div>
                        ) : (
                          <div className="relative group/practice">
                            <button
                              type="button"
                              onClick={() => onChallenge({
                                ...item,
                                matchType: 'BOT',
                                isPvP: false,
                                isPractice: true,
                                name: item.displayName || item.name,
                                elo: itemElo,
                              })}
                              className="w-[145px] py-1.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs flex items-center justify-center gap-1.5 shadow-md shadow-emerald-950/40 transition-all hover:scale-[1.02]"
                            >
                              <span>⚔️</span>
                              <span>Đấu Giao Hữu</span>
                            </button>
                            <div className="absolute right-0 bottom-full mb-1.5 hidden group-hover/practice:block z-30 w-52 p-2 rounded-xl bg-zinc-900 border border-zinc-700 text-[11px] text-zinc-300 font-medium shadow-2xl text-center">
                              🎮 Chế độ Tập Luyện / Giao Hữu (Không tính biến động ELO Ranked)
                            </div>
                          </div>
                        )}
                      </div>
                    </div>
                  </React.Fragment>
                );
              }

              // Giao diện Hàng cho Tab Đấu Trực Tiếp (PvP Layout 5 cột)
              return (
                <React.Fragment key={rowKey}>
                  {isFirstUnrankedPlayer && (
                    <div className="px-5 py-3 bg-gradient-to-r from-blue-950/40 via-zinc-900 to-blue-950/40 border-y border-blue-500/30 text-xs text-blue-300 font-mono font-bold flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <span className="text-base">⏳</span>
                        <span>HỒ SƠ CỦA BẠN ĐANG PHÂN HẠNG (Cần tối thiểu 10 trận Ranked hoặc 5 đối thủ để vào BXH chính thức)</span>
                      </div>
                      <span className="text-[10px] text-zinc-400 font-normal bg-zinc-900/80 px-2 py-0.5 rounded border border-zinc-700">Chỉ hiển thị cho bạn</span>
                    </div>
                  )}
                  <div
                    className={`grid grid-cols-[56px_1fr_170px_100px_170px] items-center gap-4 px-5 py-3.5 transition-all duration-200 group relative ${
                      isMe ? 'bg-emerald-950/30 hover:bg-emerald-950/40 border-l-4 border-l-emerald-500' : 'hover:bg-zinc-800/70'
                    }`}
                  >
                    {/* Cột 1: Thứ Hạng (56px) */}
                    <div className="text-center font-mono">
                      {item.rank != null ? (
                        <span
                          className={`text-sm ${
                            item.rank === 1
                              ? 'text-amber-400 font-black'
                              : item.rank === 2
                              ? 'text-slate-300 font-black'
                              : item.rank === 3
                              ? 'text-amber-600 font-black'
                              : 'text-zinc-500 font-bold'
                          }`}
                        >
                          #{item.rank}
                        </span>
                      ) : (
                        <span
                          className="text-xs font-bold text-amber-400/90 px-1.5 py-0.5 rounded bg-amber-500/10 border border-amber-500/30"
                          title="Chưa Xếp Hạng: Cần tối thiểu 10 trận Ranked hoặc 5 đối thủ riêng biệt để cấp thứ hạng chính thức"
                        >
                          -
                        </span>
                      )}
                    </div>

                    {/* Cột 2: Avatar + Tên hiển thị + Mô tả phụ (1fr - min-w-0) */}
                    <div className="flex items-center gap-3 min-w-0">
                      <img
                        src={`https://api.dicebear.com/7.x/bottts/svg?seed=${item.avatarSeed || item.id}`}
                        alt="avatar"
                        className="w-10 h-10 rounded-xl bg-zinc-900 border border-zinc-750 p-1 flex-shrink-0"
                      />
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-bold text-white flex items-center gap-2 truncate">
                          <span className="truncate">
                            {item.displayName || item.username}
                          </span>
                          {isMe && (
                            <span className="text-[10px] bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 px-1.5 py-0.5 rounded font-extrabold uppercase flex-shrink-0">
                              [BẠN]
                            </span>
                          )}
                          {renderProvisionalBadge(item)}
                        </p>

                        <div className="text-xs text-zinc-400 truncate mt-0.5">
                          <div className="flex items-center gap-2 font-mono truncate">
                            <span className="text-zinc-400 truncate">
                              {item.botsCount || 0} bot sở hữu
                            </span>
                            <span className="text-zinc-600">·</span>
                            {isOnline ? (
                              <span className="text-emerald-400 font-semibold flex items-center gap-1 flex-shrink-0">
                                <span>🟢</span>
                                <span>Đang Online</span>
                              </span>
                            ) : (
                              <span className="text-zinc-500 flex items-center gap-1 flex-shrink-0">
                                <span>⚫</span>
                                <span>Offline</span>
                              </span>
                            )}
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* Cột 3: Tỉ lệ thắng & Số trận Ranked & Đối thủ (170px - text-right tabular-nums) */}
                    <div className="text-right tabular-nums">
                      <p className="text-xs font-semibold text-zinc-300">
                        <span className="text-emerald-400">{item.wins ?? 0}T</span> -{' '}
                        <span className="text-zinc-400">{item.draws ?? 0}H</span> -{' '}
                        <span className="text-rose-400">{item.losses ?? 0}B</span>{' '}
                        <span className="text-zinc-500 font-mono text-[11px]">({item.winRate ?? 0}%)</span>
                      </p>
                      <div className="flex items-center justify-end gap-1.5 mt-0.5">
                        <p className="text-[11px] text-zinc-400 font-mono">
                          {item.matchesCount ?? item.matches ?? 0} trận Ranked
                        </p>
                        <span className="text-zinc-600">·</span>
                        <p className="text-[11px] text-zinc-400 font-mono" title="Số đối thủ riêng biệt">
                          {item.distinctOpponents ?? 0} đối thủ
                        </p>
                      </div>
                    </div>

                    {/* Cột 4: Điểm ELO (100px - text-right tabular-nums) */}
                    <div className="text-right tabular-nums">
                      <span className="text-base font-black font-mono text-amber-400">{itemElo}</span>
                      <p className="text-[10px] text-zinc-500 font-semibold uppercase">ELO</p>
                    </div>

                    {/* Cột 5: Khu vực nút bấm (170px - flex justify-end) */}
                    <div className="flex items-center justify-end">
                      {isMe ? (
                        <div className="w-[155px] text-center text-xs text-emerald-400 font-mono font-bold opacity-80 py-1.5 rounded-xl bg-emerald-500/10 border border-emerald-500/30">
                          * Vị trí của bạn
                        </div>
                      ) : !isUnlocked ? (
                        <div className="relative group/lock w-[155px]">
                          <button
                            type="button"
                            disabled
                            className="w-full py-1.5 rounded-xl bg-zinc-900 border border-zinc-750 text-zinc-500 font-semibold text-xs flex items-center justify-center gap-1.5 cursor-not-allowed opacity-80"
                          >
                            <span>🔒</span>
                            <span>Khóa (±300 ELO)</span>
                          </button>
                          <div className="absolute right-0 bottom-full mb-2 hidden group-hover/lock:block z-30 w-56 p-2.5 rounded-xl bg-zinc-900 border border-zinc-700 text-[11px] text-zinc-300 font-medium shadow-2xl">
                            Chênh lệch ELO vượt quá 300 điểm ({Math.abs(itemElo - activeElo)} ELO)!
                          </div>
                        </div>
                      ) : (
                        <button
                          type="button"
                          onClick={() => onChallenge({
                            ...item,
                            matchType: 'PVP',
                            isPvP: true,
                            aiLevel: item.level || (item.isSparring ? 1 : undefined),
                            isSparring: Boolean(item.isSparring),
                            isRealUser: !item.isMachine,
                            name: item.displayName || item.name,
                            elo: itemElo,
                          })}
                          className="w-[155px] py-1.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-bold text-xs flex items-center justify-center gap-1.5 shadow-md shadow-blue-950/40 transition-all hover:scale-[1.02]"
                        >
                          <span>⚔️</span>
                          <span>Đấu Giao Hữu</span>
                        </button>
                      )}
                    </div>
                  </div>
                </React.Fragment>
              );
            })}
          </div>
        </div>
      </div>

      {/* Bot Detail Modal */}
      {selectedBotForDetail && (
        <BotDetailModal
          botId={selectedBotForDetail.botId || selectedBotForDetail.id}
          botData={selectedBotForDetail}
          currentUser={currentUser}
          onClose={() => setSelectedBotForDetail(null)}
          onOpenReplay={onOpenReplay || ((match) => onChallenge && onChallenge(match))}
        />
      )}

      {/* Modal Matchmaking: Chọn chế độ PvP / Bot & Chọn Bot tham gia */}
      <MatchmakingModal
        isOpen={isMatchmakingModalOpen}
        onClose={() => setIsMatchmakingModalOpen(false)}
        user={currentUser}
        pvpElo={pvpElo}
        myBots={myBots}
        onToast={onToast}
        onMatchFound={(data) => {
          setIsMatchmakingModalOpen(false);
          if (onMatchFound) {
            onMatchFound(data);
          }
        }}
      />
    </div>
  );
}
