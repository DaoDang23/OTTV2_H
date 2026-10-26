import React, { useEffect, useState, useMemo, useRef } from 'react';
import Board, { matchFrame } from './Board.jsx';
import { getValidMoves, posToLabel, PIECE_EMOJIS, TARGET_A, TARGET_B } from '../utils/gameRules.js';
import { api } from '../utils/api.js';
import {
  playMoveSound,
  playCaptureSound,
  playVictorySound,
  playDefeatSound,
  playDrawSound,
  isAudioEnabled,
  toggleAudio,
} from '../utils/soundEffects.js';

const hasReplayContent = (data) => {
  if (!data) return false;
  const turns = data.turns || data.moves || data.movesHistory || data.replayEvents || [];
  return turns.length > 0 || (Array.isArray(data.initialPieces) && data.initialPieces.length > 0 && Boolean(data.result));
};

export default function ReplayModal({ match, replay, onClose, onDownloadLog }) {
  // 1. Resolve replay in priority order:
  // Prop replay (in-memory) -> match.replayData -> async API fetch from Backend
  // Tuyệt đối không fallback về localStorage cache để tránh lệch pha giữa các trình duyệt
  const getInitialReplay = () => {
    if (hasReplayContent(replay)) return replay;
    const directFromMatch = match?.replayData || match?.replay;
    if (hasReplayContent(directFromMatch)) return directFromMatch;
    return null;
  };

  const [activeReplay, setActiveReplay] = useState(() => getInitialReplay());
  const [isLoadingReplay, setIsLoadingReplay] = useState(false);
  const [matchDetailsData, setMatchDetailsData] = useState(null);
  const [isLoadingDetails, setIsLoadingDetails] = useState(false);

  useEffect(() => {
    const immediate = getInitialReplay();
    if (immediate) {
      setActiveReplay(immediate);
      setIsLoadingReplay(false);
      if (!match?.isLiveSpectator) return;
    }

    const targetPublicId =
      match?.matchPublicId ||
      match?.publicId ||
      match?.replayId ||
      (match?.id ? String(match.id) : null);

    if (targetPublicId) {
      let cancelled = false;
      let retryTimer;
      setIsLoadingReplay(true);
      const loadReplay = async () => {
        try {
          const res = await api(`/api/matches/${targetPublicId}/replay`);
          if (res?.ok) {
            let parsed = null;
            const contentType = res.headers?.get('content-type') || '';
            if (contentType.includes('gzip')) {
              try {
                const plain = await new Response(res.clone().body.pipeThrough(new DecompressionStream('gzip'))).text();
                parsed = JSON.parse(plain);
              } catch {}
            }
            if (!parsed) {
              try {
                parsed = await res.json();
              } catch {}
            }
            if (hasReplayContent(parsed) && (!match?.matchPublicId || parsed.matchId === match.matchPublicId)) {
              if (!cancelled) {
                setActiveReplay(parsed);
                setIsLoadingReplay(false);
              }
              return;
            }
          }
        } catch (err) {
          if (!match?.isLiveSpectator) console.warn('[ReplayModal] Backend fetch replay failed:', err);
        }

        if (cancelled) return;
        if (match?.isLiveSpectator) {
          retryTimer = window.setTimeout(loadReplay, 1500);
        } else {
          setActiveReplay(null);
          setIsLoadingReplay(false);
        }
      };
      loadReplay();
      return () => {
        cancelled = true;
        window.clearTimeout(retryTimer);
      };
    } else {
      setActiveReplay(null);
      setIsLoadingReplay(false);
    }
  }, [match, replay]);

  useEffect(() => {
    if (hasReplayContent(replay)) {
      setActiveReplay(replay);
    }
  }, [replay]);

  // Nhận diện vai trò người xem (Đội A hay Đội B) để khởi tạo góc nhìn bàn cờ
  const initialFlipped = useMemo(() => {
    // 1. Chỉ định rõ ràng góc nhìn từ match perspective (nếu có)
    if (match?.viewerPerspective === 'RED' || match?.perspective === 'RED' || match?.perspective === 'B') {
      return true;
    }
    if (match?.viewerPerspective === 'BLUE' || match?.perspective === 'BLUE' || match?.perspective === 'A') {
      return false;
    }

    // 2. Ưu tiên kiểm tra activeReplay (Nguồn sự thật thực tế của trận đấu) với người dùng hiện tại
    try {
      const uStr = localStorage.getItem('ottv2_last_active_user');
      if (uStr) {
        const u = JSON.parse(uStr);
        const myIdentifiers = [
          u.username,
          u.studentCode,
          u.displayName,
          u.email ? u.email.split('@')[0] : null,
        ].filter(Boolean).map((s) => String(s).toLowerCase().trim());
        const myId = u.id ? String(u.id) : null;

        if (activeReplay) {
          // So khớp Side B (Đội Đỏ) trong Replay
          const repSideBName = (activeReplay.sideB?.botName || activeReplay.sideB?.name || activeReplay.sideB?.username || '')
            .toLowerCase()
            .trim();
          const repBUserId = activeReplay.sideB?.userId || activeReplay.sideB?.ownerId || activeReplay.sideB?.id;

          const isRepSideB =
            (myId && repBUserId && String(myId) === String(repBUserId)) ||
            myIdentifiers.some((ident) => ident && repSideBName && (repSideBName === ident || repSideBName.includes(ident)));

          if (isRepSideB) {
            return true;
          }

          // So khớp Side A (Đội Xanh) trong Replay
          const repSideAName = (activeReplay.sideA?.botName || activeReplay.sideA?.name || activeReplay.sideA?.username || '')
            .toLowerCase()
            .trim();
          const repAUserId = activeReplay.sideA?.userId || activeReplay.sideA?.ownerId || activeReplay.sideA?.id;

          const isRepSideA =
            (myId && repAUserId && String(myId) === String(repAUserId)) ||
            myIdentifiers.some((ident) => ident && repSideAName && (repSideAName === ident || repSideAName.includes(ident)));

          if (isRepSideA) {
            return false;
          }
        }

        // 3. Fallback: Nếu Replay chưa tải xong hoặc không ghi nhận đấu thủ, kiểm tra match object
        if (match?.userSide === 'B' || match?.side === 'B') {
          return true;
        }
        if (match?.userSide === 'A' || match?.side === 'A') {
          return false;
        }

        const sideBName = (match?.sideB?.botName || match?.sideB?.name || match?.opponent || '')
          .toLowerCase()
          .trim();
        const bUserId = match?.sideB?.userId || match?.sideB?.ownerId;

        const isSideB =
          (myId && bUserId && String(myId) === String(bUserId)) ||
          myIdentifiers.some((ident) => ident && sideBName && (sideBName === ident || sideBName.includes(ident)));

        if (isSideB) {
          return true;
        }

        const sideAName = (match?.sideA?.botName || match?.sideA?.name || '')
          .toLowerCase()
          .trim();
        const aUserId = match?.sideA?.userId || match?.sideA?.ownerId;

        const isSideA =
          (myId && aUserId && String(myId) === String(aUserId)) ||
          myIdentifiers.some((ident) => ident && sideAName && (sideAName === ident || sideAName.includes(ident)));

        if (isSideA) {
          return false;
        }
      }
    } catch {}

    if (match?.userSide === 'B' || match?.side === 'B') {
      return true;
    }
    if (match?.userSide === 'A' || match?.side === 'A') {
      return false;
    }

    return false;
  }, [match, activeReplay]);

  const [isFlipped, setIsFlipped] = useState(initialFlipped);

  useEffect(() => {
    setIsFlipped(initialFlipped);
  }, [initialFlipped]);

  const [step, setStep] = useState(0);
  const [playing, setPlaying] = useState(true); // Auto start playing match
  const [speed, setSpeed] = useState(1); // 0.5x, 1x, 2x, 4x, 8x
  const [inspectedPieceId, setInspectedPieceId] = useState(null);
  const [showCoordinates, setShowCoordinates] = useState(true);
  const [showSummaryModal, setShowSummaryModal] = useState(false);
  const [audioEnabled, setAudioEnabled] = useState(() => isAudioEnabled());

  const prevStepRef = useRef(0);
  const isScrubbingRef = useRef(false);
  const hasPlayedEndSoundRef = useRef(false);

  // Tự động nạp thông tin chính thức của trận đấu (MatchResponse chứa sideA/sideB với eloBefore, eloAfter, ownerId)
  useEffect(() => {
    const targetPublicId =
      match?.matchPublicId ||
      match?.publicId ||
      match?.replayId ||
      (match?.id ? String(match.id) : null);

    if (!targetPublicId) return undefined;

    let cancelled = false;
    let detailRetryTimer = null;

    const fetchDetails = async () => {
      try {
        setIsLoadingDetails(true);
        const res = await api(`/api/matches/${targetPublicId}`);
        if (res?.ok) {
          const data = await res.json();
          if (data && !cancelled) {
            setMatchDetailsData(data);
            if (data.sideA?.eloAfter != null || data.sideB?.eloAfter != null || data.status === 'FINISHED') {
              setIsLoadingDetails(false);
              return;
            }
          }
        }
      } catch (err) {
        // bỏ qua khi đang live
      } finally {
        if (!cancelled) setIsLoadingDetails(false);
      }
      if (!cancelled && (match?.isLiveSpectator || showSummaryModal)) {
        detailRetryTimer = window.setTimeout(fetchDetails, 2000);
      }
    };

    fetchDetails();

    return () => {
      cancelled = true;
      if (detailRetryTimer) window.clearTimeout(detailRetryTimer);
    };
  }, [match?.matchPublicId, match?.publicId, match?.id, match?.isLiveSpectator, showSummaryModal]);

  const turnsList =
    activeReplay?.turns ||
    activeReplay?.moves ||
    activeReplay?.movesHistory ||
    activeReplay?.replayEvents ||
    [];
  const totalTurns = turnsList.length;

  // 1. Phân định kết quả và lý do kết thúc (Khai báo trên đầu khối để tránh TDZ / ReferenceError)
  const winnerCode = useMemo(() => {
    if (activeReplay?.result?.winner) {
      const w = String(activeReplay.result.winner).toUpperCase();
      if (w === 'A' || w === 'B' || w === 'DRAW') return w;
      if (w === 'USER') return 'A';
      if (w === 'OPPONENT') return 'B';
    }
    if (matchDetailsData?.winnerSide) {
      const w = String(matchDetailsData.winnerSide).toUpperCase();
      if (w === 'A' || w === 'B' || w === 'DRAW') return w;
    }
    if (match?.winner) {
      const w = String(match.winner).toUpperCase();
      if (w === 'A' || w === 'B' || w === 'DRAW') return w;
      if (w === 'USER') return 'A';
      if (w === 'OPPONENT') return 'B';
    }
    if (match?.winnerSide) {
      const w = String(match.winnerSide).toUpperCase();
      if (w === 'A' || w === 'B' || w === 'DRAW') return w;
    }
    if (match?.userWon === true) return (match?.userSide === 'B' || match?.side === 'B') ? 'B' : 'A';
    if (match?.userWon === false) return (match?.userSide === 'B' || match?.side === 'B') ? 'A' : 'B';
    if (match?.winnerId) {
      if (match.sideA?.id && match.winnerId === match.sideA.id) return 'A';
      if (match.sideB?.id && match.winnerId === match.sideB.id) return 'B';
    }
    return 'DRAW';
  }, [activeReplay, match, matchDetailsData]);

  const reasonCode = useMemo(() => {
    return (
      activeReplay?.result?.reason ||
      matchDetailsData?.endReason ||
      match?.winReason ||
      match?.reason ||
      match?.endReason ||
      'FINISHED'
    );
  }, [activeReplay, match, matchDetailsData]);

  const sideAName =
    activeReplay?.sideA?.botName ||
    activeReplay?.sideA?.name ||
    activeReplay?.teams?.A ||
    matchDetailsData?.sideA?.botName ||
    (match?.sideA?.botName && match?.sideA?.botName !== 'Bạn' ? match.sideA.botName : null) ||
    match?.sideA?.name ||
    match?.sideA?.botName ||
    'Đội Xanh (A)';

  const sideBName =
    activeReplay?.sideB?.botName ||
    activeReplay?.sideB?.name ||
    activeReplay?.teams?.B ||
    matchDetailsData?.sideB?.botName ||
    (match?.sideB?.botName && match?.sideB?.botName !== 'Đối thủ' ? match.sideB.botName : null) ||
    match?.sideB?.name ||
    match?.sideB?.botName ||
    match?.opponent ||
    'Đội Đỏ (B)';

  const replayMode = String(match?.mode || match?.matchType || activeReplay?.mode || activeReplay?.matchType || '').toUpperCase();
  const isBotReplay = replayMode === 'BOT' || replayMode === 'LADDER';
  const sideAElo = match?.sideA?.eloBefore ?? match?.sideA?.elo ?? (isBotReplay ? null : activeReplay?.sideA?.eloBefore ?? activeReplay?.sideA?.elo ?? match?.userElo ?? 1200);
  const sideBElo = match?.sideB?.eloBefore ?? match?.sideB?.elo ?? (isBotReplay ? null : activeReplay?.sideB?.eloBefore ?? activeReplay?.sideB?.elo ?? match?.opponentElo ?? 1200);

  const isTournament =
    match?.matchType === 'TOURNAMENT' ||
    match?.mode === 'TOURNAMENT' ||
    activeReplay?.matchType === 'TOURNAMENT' ||
    activeReplay?.mode === 'TOURNAMENT';

  const isPvpMatch = !isBotReplay && (
    match?.mode === 'PVP' ||
    match?.mode === 'PVP_QUICK' ||
    match?.mode === 'PRACTICE' ||
    activeReplay?.mode === 'PVP' ||
    activeReplay?.mode === 'PVP_QUICK' ||
    match?.opponentType === 'HUMAN' ||
    Boolean(match?.opponent)
  );

  // Trận Giao Hữu (Casual / 0 ELO): khi isRanked === false, hoặc delta ELO === 0, hoặc mode là PRACTICE / CASUAL
  const isCasualMatch =
    !isTournament &&
    (match?.isRanked === false ||
      activeReplay?.isRanked === false ||
      match?.mode === 'PRACTICE' ||
      activeReplay?.mode === 'PRACTICE' ||
      (match?.change === 0 && match?.eloChange === 0));

  const isRankedPvP = isPvpMatch && !isCasualMatch && !isTournament;
  const isRankedBot = !isPvpMatch && !isTournament && (match?.isRanked !== false && activeReplay?.isRanked !== false);
  const isRankedMatch = isRankedPvP || isRankedBot;

  // Biến động ELO chuẩn cho Summary Modal
  const { deltaEloA, deltaEloB } = useMemo(() => {
    if (isCasualMatch || !isRankedMatch) {
      return { deltaEloA: 0, deltaEloB: 0 };
    }
    // Ranked Bot không suy ra điểm bằng K=32 ở phía giao diện. Chỉ dùng số đã lưu từ server.
    if (isRankedBot) {
      if (match?.sideA?.eloChange != null && match?.sideB?.eloChange != null) {
        return { deltaEloA: Number(match.sideA.eloChange), deltaEloB: Number(match.sideB.eloChange) };
      }
      return { deltaEloA: 0, deltaEloB: 0 };
    }
    if (match?.sideA?.eloChange !== undefined && match?.sideB?.eloChange !== undefined) {
      return { deltaEloA: match.sideA.eloChange, deltaEloB: match.sideB.eloChange };
    }
    if (match?.change !== undefined && match?.change !== 0) {
      const change = match.change;
      const isViewerB = match?.userSide === 'B';
      return isViewerB
        ? { deltaEloA: -change, deltaEloB: change }
        : { deltaEloA: change, deltaEloB: -change };
    }
    if (match?.eloChange !== undefined && match?.eloChange !== 0) {
      const change = match.eloChange;
      const isViewerB = match?.userSide === 'B';
      return isViewerB
        ? { deltaEloA: -change, deltaEloB: change }
        : { deltaEloA: change, deltaEloB: -change };
    }
    return { deltaEloA: 0, deltaEloB: 0 };
  }, [isCasualMatch, isRankedMatch, isRankedBot, match, sideAElo, sideBElo, winnerCode]);

  // Biến động ELO cá nhân hóa cho người xem hiện tại
  const viewerMatchData = useMemo(() => {
    if (!isRankedPvP) return null;

    let viewerSide = null;
    let myId = null;
    let myIdentifiers = [];
    try {
      const uStr = localStorage.getItem('ottv2_last_active_user');
      if (uStr) {
        const u = JSON.parse(uStr);
        myId = u.id ? String(u.id) : null;
        myIdentifiers = [
          u.username,
          u.studentCode,
          u.displayName,
          u.email ? u.email.split('@')[0] : null,
        ].filter(Boolean).map((s) => String(s).toLowerCase().trim());
      }
    } catch {}

    // 1. Kiểm tra trực tiếp trong activeReplay
    if (activeReplay) {
      const repSideBName = (activeReplay.sideB?.botName || activeReplay.sideB?.name || activeReplay.sideB?.username || '')
        .toLowerCase()
        .trim();
      const repBUserId = activeReplay.sideB?.userId || activeReplay.sideB?.ownerId || activeReplay.sideB?.id;
      const isRepSideB =
        (myId && repBUserId && String(myId) === String(repBUserId)) ||
        myIdentifiers.some((ident) => ident && repSideBName && (repSideBName === ident || repSideBName.includes(ident)));

      if (isRepSideB) {
        viewerSide = 'B';
      } else {
        const repSideAName = (activeReplay.sideA?.botName || activeReplay.sideA?.name || activeReplay.sideA?.username || '')
          .toLowerCase()
          .trim();
        const repAUserId = activeReplay.sideA?.userId || activeReplay.sideA?.ownerId || activeReplay.sideA?.id;
        const isRepSideA =
          (myId && repAUserId && String(myId) === String(repAUserId)) ||
          myIdentifiers.some((ident) => ident && repSideAName && (repSideAName === ident || repSideAName.includes(ident)));
        if (isRepSideA) {
          viewerSide = 'A';
        }
      }
    }

    // 2. Fallback nếu chưa xác định được từ activeReplay
    if (!viewerSide) {
      if (match?.userSide === 'B' || match?.side === 'B') {
        viewerSide = 'B';
      } else if (match?.userSide === 'A' || match?.side === 'A') {
        viewerSide = 'A';
      } else {
        const bName = (match?.sideB?.botName || match?.sideB?.name || match?.opponent || '').toLowerCase().trim();
        const bUserId = match?.sideB?.userId || match?.sideB?.ownerId;
        const isB = (myId && bUserId && String(myId) === String(bUserId)) ||
          myIdentifiers.some((ident) => ident && bName && (bName === ident || bName.includes(ident)));
        if (isB) {
          viewerSide = 'B';
        } else {
          const aName = (match?.sideA?.botName || match?.sideA?.name || '').toLowerCase().trim();
          const aUserId = match?.sideA?.userId || match?.sideA?.ownerId;
          const isA = (myId && aUserId && String(myId) === String(aUserId)) ||
            myIdentifiers.some((ident) => ident && aName && (aName === ident || aName.includes(ident)));
          if (isA) viewerSide = 'A';
        }
      }
    }

    if (!viewerSide) {
      return null;
    }

    const isSideA = viewerSide === 'A';
    const oldElo = isSideA ? sideAElo : sideBElo;
    const delta = isSideA ? deltaEloA : deltaEloB;
    const newElo = oldElo + delta;
    const opponentName = isSideA ? sideBName : sideAName;
    const isWin = winnerCode === viewerSide;
    const isDraw = winnerCode === 'DRAW';

    return {
      viewerSide,
      oldElo,
      delta,
      newElo,
      opponentName,
      isWin,
      isDraw,
    };
  }, [isRankedPvP, activeReplay, match, sideAElo, sideBElo, deltaEloA, deltaEloB, sideAName, sideBName, winnerCode]);

  const botViewerMatchData = useMemo(() => {
    if (!isRankedBot) return null;

    const mergedSideA = {
      ...(activeReplay?.sideA || {}),
      ...(match?.sideA || {}),
      ...(matchDetailsData?.sideA || {}),
    };
    const mergedSideB = {
      ...(activeReplay?.sideB || {}),
      ...(match?.sideB || {}),
      ...(matchDetailsData?.sideB || {}),
    };

    let viewerSide = match?.userSide || match?.side;
    if (viewerSide !== 'A' && viewerSide !== 'B') viewerSide = null;

    let currentUserId = null;
    let currentUserBots = [];
    try {
      const rawUser = localStorage.getItem('ottv2_last_active_user');
      if (rawUser) {
        const currentUser = JSON.parse(rawUser);
        if (currentUser?.id != null) currentUserId = String(currentUser.id);
        if (currentUser?.email) {
          const userBotsRaw = localStorage.getItem(`ottv2_bots_${currentUser.email}`);
          if (userBotsRaw) currentUserBots = JSON.parse(userBotsRaw);
        }
      }
      if (currentUserBots.length === 0) {
        const generalBotsRaw = localStorage.getItem('ottv2_user_bots');
        if (generalBotsRaw) currentUserBots = JSON.parse(generalBotsRaw);
      }
    } catch {}

    const ownedBotIds = new Set(currentUserBots.map((b) => String(b.id)));

    const participantMatchesViewer = (participant) => {
      if (!participant) return false;
      const ownerId = participant.ownerId ?? participant.userId;
      if (currentUserId != null && ownerId != null && String(ownerId) === currentUserId) {
        return true;
      }
      if (participant.botId != null && ownedBotIds.has(String(participant.botId))) {
        return true;
      }
      return false;
    };

    if (!viewerSide) {
      if (participantMatchesViewer(mergedSideA)) viewerSide = 'A';
      else if (participantMatchesViewer(mergedSideB)) viewerSide = 'B';
    }
    if (!viewerSide) return null;

    const ownSide = viewerSide === 'A' ? mergedSideA : mergedSideB;

    const ownHistoryChange = match?.change ?? match?.eloChange ?? ownSide?.eloChange;
    const ownHistoryAfter = match?.newElo ?? match?.userElo ?? ownSide?.eloAfter;
    const hasHistoryRating = ownHistoryChange != null && ownHistoryAfter != null &&
      Number.isFinite(Number(ownHistoryChange)) && Number.isFinite(Number(ownHistoryAfter));

    let oldElo = null;
    let newElo = null;
    let delta = null;
    if (hasHistoryRating) {
      delta = Number(ownHistoryChange);
      newElo = Number(ownHistoryAfter);
      oldElo = newElo - delta;
    } else {
      const before = ownSide?.eloBefore ?? ownSide?.elo;
      const after = ownSide?.eloAfter;
      if (before != null && after != null && Number.isFinite(Number(before)) && Number.isFinite(Number(after))) {
        oldElo = Number(before);
        newElo = Number(after);
        delta = newElo - oldElo;
      }
    }

    const isDraw = winnerCode === 'DRAW';
    return {
      viewerSide,
      botName: viewerSide === 'A' ? (mergedSideA?.botName || sideAName) : (mergedSideB?.botName || sideBName),
      opponentName: viewerSide === 'A' ? (mergedSideB?.botName || sideBName) : (mergedSideA?.botName || sideAName),
      oldElo,
      newElo,
      delta,
      hasRating: oldElo != null && newElo != null && delta != null,
      isWin: !isDraw && winnerCode === viewerSide,
      isDraw,
    };
  }, [isRankedBot, match, activeReplay, matchDetailsData, winnerCode, sideAName, sideBName]);

  const activeUserViewerSide = botViewerMatchData?.viewerSide || viewerMatchData?.viewerSide || match?.userSide || null;
  const isUserWin = Boolean(activeUserViewerSide && winnerCode === activeUserViewerSide);
  const isUserLoss = Boolean(activeUserViewerSide && winnerCode !== 'DRAW' && winnerCode !== activeUserViewerSide);
  const isMatchDraw = winnerCode === 'DRAW';

  const getReasonLabel = (reason) => {
    switch (reason) {
      case 'TARGET_REACHED':
        return 'Chạm Căn Cứ Đối Phương (A9/I1)';
      case 'ANNIHILATION':
        return 'Tiêu Diệt Toàn Bộ Quân Địch';
      case 'TIMEOUT':
        return 'Hết Thời Gian (Timeout)';
      case 'CHECKMATE':
        return 'Bắt Tướng / Chiếu Bí';
      case 'CRASH':
      case 'BOT_CRASH':
      case 'CODE_ERROR':
        return 'Lỗi Mã Nguồn / Crash';
      case 'ILLEGAL_MOVE':
        return 'Nước Đi Sai Luật';
      case 'NO_LEGAL_MOVES':
        return 'Hết Nước Đi Hợp Lệ';
      case 'DISQUALIFIED':
        return 'Bị Xử Thua (Truất Quyền)';
      case 'RESIGN':
      case 'SURRENDER':
      case 'OPPONENT_LEFT':
        return 'Đối Thủ Đầu Hàng / Bỏ Cuộc';
      case 'DRAW':
        return 'Hòa Trận';
      default:
        return reason || 'Hết Giờ / Ưu Thế Quân Số';
    }
  };

  // Sync with global audio events
  useEffect(() => {
    const handleToggle = (e) => {
      setAudioEnabled(e.detail.enabled);
    };
    window.addEventListener('ottv2_audio_toggle', handleToggle);
    return () => window.removeEventListener('ottv2_audio_toggle', handleToggle);
  }, []);

  // Audio triggering on step progression
  useEffect(() => {
    const prev = prevStepRef.current;
    prevStepRef.current = step;

    if (step < totalTurns) {
      hasPlayedEndSoundRef.current = false;
    }

    // Suppress sound while dragging the scrubber or if step didn't change
    if (isScrubbingRef.current || step === prev) {
      return;
    }

    // Forward single step: play move or capture sound according to speed rules
    if (step === prev + 1 && step <= totalTurns) {
      const turn = turnsList[step - 1];
      const capturedId =
        turn?.capturedPieceId ??
        turn?.capturedPiece?.id ??
        turn?.capturedPiece ??
        turn?.capturePieceId;
      const isCapture =
        turn?.events?.some((ev) => ev.type === 'CAPTURED' || ev.type === 'CAPTURE') ||
        Boolean(capturedId);

      // Rules:
      // 0.5x, 1x, 2x: play capture or move sound
      // 4x: play capture only (mute moves to avoid chaotic overlapping)
      // 8x: completely muted during fast auto-play
      if (speed <= 4 && isCapture) {
        playCaptureSound();
      } else if (speed <= 2 && !isCapture) {
        playMoveSound();
      }
    }

    // Play victory / defeat / draw sound once at the end of the match
    if (step >= totalTurns && totalTurns > 0 && !hasPlayedEndSoundRef.current) {
      hasPlayedEndSoundRef.current = true;
      if (activeUserViewerSide) {
        if (isUserWin) {
          playVictorySound();
        } else if (isMatchDraw) {
          playDrawSound();
        } else {
          playDefeatSound();
        }
      } else {
        if (winnerCode === 'DRAW') {
          playDrawSound();
        } else {
          playVictorySound();
        }
      }
    }
  }, [step, totalTurns, speed, activeReplay, match, winnerCode, activeUserViewerSide, isUserWin, isMatchDraw]);

  // Auto playback interval with dynamic speed
  useEffect(() => {
    if (!playing || totalTurns === 0) return undefined;
    const intervalMs = Math.max(75, Math.round(550 / speed));

    const timer = window.setInterval(() => {
      setStep((current) => {
        if (current >= totalTurns) {
          setPlaying(false);
          setShowSummaryModal(true);
          return totalTurns;
        }
        return current + 1;
      });
    }, intervalMs);

    return () => window.clearInterval(timer);
  }, [playing, speed, totalTurns]);

  // Frame calculation for current step
  const currentFrame = useMemo(() => {
    return matchFrame(activeReplay, step);
  }, [activeReplay, step]);

  const alivePieces = currentFrame.pieces;

  // Army Advantage calculations
  const teamAPieces = alivePieces.filter((p) => p.team === 'A');
  const teamBPieces = alivePieces.filter((p) => p.team === 'B');

  const countRockA = teamAPieces.filter((p) => p.type === 'ROCK').length;
  const countPaperA = teamAPieces.filter((p) => p.type === 'PAPER').length;
  const countScissorA = teamAPieces.filter((p) => p.type === 'SCISSORS').length;

  const countRockB = teamBPieces.filter((p) => p.type === 'ROCK').length;
  const countPaperB = teamBPieces.filter((p) => p.type === 'PAPER').length;
  const countScissorB = teamBPieces.filter((p) => p.type === 'SCISSORS').length;

  const totalAlive = teamAPieces.length + teamBPieces.length || 1;
  const percentA = Math.round((teamAPieces.length / totalAlive) * 100);
  const percentB = 100 - percentA;

  // Inspected Piece details
  const inspectedPiece = alivePieces.find((p) => p.id === inspectedPieceId);
  const inspectedValidMoves = useMemo(() => {
    if (!inspectedPiece) return [];
    return getValidMoves(inspectedPiece, alivePieces);
  }, [inspectedPiece, alivePieces]);

  // Capture turns for scrubber red dots
  const captureTurns = useMemo(() => {
    const list = [];
    const turns =
      activeReplay?.turns ||
      activeReplay?.moves ||
      activeReplay?.movesHistory ||
      activeReplay?.replayEvents ||
      [];
    turns.forEach((t, idx) => {
      const capturedId =
        t?.capturedPieceId ??
        t?.capturedPiece?.id ??
        t?.capturedPiece ??
        t?.capturePieceId;
      const isCapture =
        t?.events?.some((ev) => ev.type === 'CAPTURED' || ev.type === 'CAPTURE') ||
        Boolean(capturedId);
      if (isCapture) {
        list.push({
          step: idx + 1,
          round: t.t ?? t.turn ?? Math.ceil((idx + 1) / 2),
        });
      }
    });
    return list;
  }, [activeReplay]);

  const handleSkipToEnd = () => {
    isScrubbingRef.current = true;
    setPlaying(false);
    setStep(totalTurns);
    setShowSummaryModal(true);
    setTimeout(() => {
      isScrubbingRef.current = false;
      if (!hasPlayedEndSoundRef.current && totalTurns > 0) {
        hasPlayedEndSoundRef.current = true;
        if (activeUserViewerSide) {
          if (isUserWin) playVictorySound();
          else if (isMatchDraw) playDrawSound();
          else playDefeatSound();
        } else {
          if (winnerCode === 'DRAW') playDrawSound();
          else playVictorySound();
        }
      }
    }, 60);
  };

  const handleJumpToTurn = (t) => {
    isScrubbingRef.current = true;
    setPlaying(false);
    setStep(t);
    setTimeout(() => {
      isScrubbingRef.current = false;
    }, 60);
  };

  // Safe position label helper avoiding A1 fallback
  const getTurnDescription = (t, idx = 0) => {
    const captureEv = t.events?.find((ev) => ev.type === 'CAPTURED' || ev.type === 'CAPTURE');
    if (captureEv?.description) return captureEv.description;

    const moveEv = t.events?.find((ev) => ev.type === 'MOVE');
    if (moveEv?.description) return moveEv.description;

    const turnNum = t.t ?? t.turn ?? (idx + 1);
    const pieceId = t.move?.p ?? t.move?.pieceId ?? t.pieceId ?? '?';
    const fromCoord = t.move?.from || t.from;
    const toCoord = t.move?.to || t.to;

    let fromStr = '';
    if (fromCoord && Array.isArray(fromCoord)) {
      fromStr = posToLabel(fromCoord[0], fromCoord[1]);
    } else {
      // Find previous position of piece p
      const prevPieces = matchFrame(activeReplay, idx).pieces;
      const pc = prevPieces.find((p) => p.id === pieceId);
      if (pc) {
        fromStr = posToLabel(pc.row, pc.col);
      }
    }

    let toStr = '';
    if (toCoord && Array.isArray(toCoord)) {
      toStr = posToLabel(toCoord[0], toCoord[1]);
    }

    const teamLabel = t.team === 'A' ? 'Xanh' : 'Đỏ';
    const capturedId =
      t.capturedPieceId ??
      t.capturedPiece?.id ??
      t.capturedPiece ??
      t.capturePieceId;
    if (capturedId) {
      const victimIdStr = typeof capturedId === 'object' ? capturedId.id : capturedId;
      return `[${teamLabel}] Quân #${pieceId} (${fromStr || '?'} → ${toStr || '?'}) tiêu diệt quân #${victimIdStr}!`;
    }
    if (fromStr && toStr) {
      return `[${teamLabel}] Quân #${pieceId} (${fromStr} → ${toStr})`;
    }
    if (toStr) {
      return `[${teamLabel}] Quân #${pieceId} di chuyển tới ${toStr}`;
    }
    return `[${teamLabel}] Nước đi lượt #${turnNum}`;
  };

  if (!match) return null;

  if (isLoadingReplay) {
    return (
      <div className="fixed inset-0 bg-black/90 backdrop-blur-md z-50 flex items-center justify-center p-4 animate-fadeIn">
        <div className="bg-zinc-850 border border-zinc-700 w-full max-w-sm rounded-3xl p-6 text-center space-y-4 shadow-2xl">
          <div className="w-12 h-12 mx-auto border-4 border-emerald-500 border-t-transparent rounded-full animate-spin" />
          <h3 className="text-base font-bold text-white">
            {match?.isLiveSpectator ? 'Đang chờ trận đấu hoàn tất...' : 'Đang tải dữ liệu Replay...'}
          </h3>
          <p className="text-xs text-zinc-400">
            {match?.isLiveSpectator
              ? 'Replay sẽ tự phát ngay khi worker lưu xong diễn biến chính thức.'
              : 'Đang tải diễn biến trận đấu từ máy chủ.'}
          </p>
          <button
            type="button"
            onClick={onClose}
            className="w-full mt-2 py-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-xs font-bold"
          >
            Hủy
          </button>
        </div>
      </div>
    );
  }

  if (!hasReplayContent(activeReplay)) {
    return (
      <div className="fixed inset-0 bg-black/90 backdrop-blur-md z-50 flex items-center justify-center p-4 animate-fadeIn">
        <div className="bg-zinc-850 border border-zinc-700 w-full max-w-md rounded-3xl p-6 sm:p-8 text-center space-y-5 shadow-2xl">
          <div className="w-16 h-16 mx-auto rounded-2xl bg-amber-500/10 border border-amber-500/30 flex items-center justify-center text-3xl text-amber-400">
            ⚠️
          </div>
          <div>
            <h3 className="text-lg font-black text-white">Dữ liệu Replay chưa sẵn sàng hoặc đã bị xóa</h3>
            <p className="text-xs text-zinc-400 mt-2 leading-relaxed">
              Trận đấu này không có dữ liệu diễn biến (moves history) trên máy chủ hoặc file lưu trữ đã hết hạn.
            </p>
          </div>

          <div className="bg-zinc-900 p-3.5 rounded-2xl border border-zinc-800 text-xs font-mono text-zinc-300 space-y-2 text-left">
            <div className="flex justify-between items-center">
              <span className="text-zinc-500">Mã trận:</span>
              <span className="font-bold text-zinc-200">{match?.id || match?.replayId || 'N/A'}</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-zinc-500">Cặp đấu:</span>
              <span className="font-bold text-white">
                <span className="text-blue-400">{sideAName}</span>
                <span className="text-zinc-500 mx-1">vs</span>
                <span className="text-rose-400">{sideBName}</span>
              </span>
            </div>
            {winnerCode && (
              <div className="flex justify-between items-center">
                <span className="text-zinc-500">Kết quả:</span>
                <span className={`font-bold ${winnerCode === 'A' ? 'text-blue-400' : winnerCode === 'B' ? 'text-rose-400' : 'text-amber-400'}`}>
                  {winnerCode === 'A' ? `${sideAName} (A) Thắng` : winnerCode === 'B' ? `${sideBName} (B) Thắng` : 'Hòa'}
                </span>
              </div>
            )}
            {reasonCode && (
              <div className="flex justify-between items-center">
                <span className="text-zinc-500">Chi tiết:</span>
                <span className="text-zinc-300">{getReasonLabel(reasonCode)}</span>
              </div>
            )}
          </div>

          <button
            type="button"
            onClick={onClose}
            className="w-full py-3 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-white font-bold text-xs border border-zinc-700 transition-colors shadow-lg"
          >
            Đóng Trình Xem Replay
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 bg-black/90 backdrop-blur-md z-50 flex items-center justify-center p-2 sm:p-4 animate-fadeIn">
      <div className="relative bg-zinc-850 border border-zinc-700 w-full max-w-6xl rounded-3xl p-4 sm:p-6 shadow-2xl flex flex-col max-h-[96vh] overflow-y-auto space-y-4">
        {/* Top Header */}
        <div className="flex flex-wrap items-center justify-between border-b border-zinc-750 pb-3 pr-12 gap-3">
          <div className="flex flex-wrap items-center gap-2 sm:gap-3">
            {match?.isLiveSpectator ? (
              <span className="text-[10px] font-mono uppercase font-black text-rose-300 bg-rose-500/20 px-2.5 py-1 rounded-lg border border-rose-500/40 flex items-center gap-1.5 animate-pulse">
                <span className="w-2 h-2 rounded-full bg-rose-400" />
                🔴 TRỰC TIẾP KHÁN GIẢ (SPECTATOR)
              </span>
            ) : (
              <span className="text-[10px] font-mono uppercase font-black text-emerald-400 bg-emerald-500/10 px-2.5 py-1 rounded-lg border border-emerald-500/30">
                MIT BATTLECODE VIEWER
              </span>
            )}

            {/* Bot Names and ELO Badges */}
            <div className="flex items-center gap-2 sm:gap-2.5 flex-wrap">
              <div className="flex items-center gap-1.5 bg-blue-950/60 border border-blue-500/40 px-2.5 py-1 rounded-xl">
                <span className="w-2 h-2 rounded-full bg-blue-400" />
                <span className="text-xs sm:text-sm font-black text-blue-300">{sideAName}</span>
                <span className="text-[10px] font-mono font-bold text-blue-200 bg-blue-900/80 px-1.5 py-0.5 rounded-lg border border-blue-400/30">
                  ⚡ {sideAElo ?? '—'} ELO
                </span>
              </div>

              <span className="text-[11px] font-black font-mono text-zinc-400 bg-zinc-800 px-2 py-0.5 rounded-lg">
                VS
              </span>

              <div className="flex items-center gap-1.5 bg-rose-950/60 border border-rose-500/40 px-2.5 py-1 rounded-xl">
                <span className="w-2 h-2 rounded-full bg-rose-400" />
                <span className="text-xs sm:text-sm font-black text-rose-300">{sideBName}</span>
                <span className="text-[10px] font-mono font-bold text-rose-200 bg-rose-900/80 px-1.5 py-0.5 rounded-lg border border-rose-400/30">
                  ⚡ {sideBElo ?? '—'} ELO
                </span>
              </div>
            </div>

            {/* Winner Badge on Title Bar */}
            {winnerCode === 'A' && (
              <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-blue-500/15 border border-blue-500/40 text-blue-300 font-bold text-xs">
                <span>👑</span>
                <span>{sideAName} (A) Thắng</span>
                <span className="text-zinc-400 font-normal hidden md:inline">({getReasonLabel(reasonCode)})</span>
              </span>
            )}
            {winnerCode === 'B' && (
              <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-rose-500/15 border border-rose-500/40 text-rose-300 font-bold text-xs">
                <span>👑</span>
                <span>{sideBName} (B) Thắng</span>
                <span className="text-zinc-400 font-normal hidden md:inline">({getReasonLabel(reasonCode)})</span>
              </span>
            )}
            {winnerCode === 'DRAW' && (
              <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-amber-500/15 border border-amber-500/40 text-amber-300 font-bold text-xs">
                <span>🤝</span>
                <span>Hòa Trận</span>
                <span className="text-zinc-400 font-normal hidden md:inline">({getReasonLabel(reasonCode)})</span>
              </span>
            )}
          </div>

          <div className="flex items-center gap-2">
            {/* Flip Perspective Button */}
            <button
              type="button"
              onClick={() => setIsFlipped((prev) => !prev)}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-mono font-bold border transition-colors ${
                isFlipped
                  ? 'bg-rose-500/20 border-rose-500/40 text-rose-300 hover:bg-rose-500/30'
                  : 'bg-zinc-800 border-zinc-700 text-zinc-300 hover:bg-zinc-750'
              }`}
              title="Đảo góc nhìn bàn cờ giữa Đội Xanh (ở dưới) và Đội Đỏ (ở dưới)"
            >
              <span>🔄</span>
              <span>{isFlipped ? 'Đỏ ở dưới' : 'Xanh ở dưới'}</span>
            </button>

            {/* Toggles & Audio Control */}
            <div className="hidden sm:flex items-center bg-zinc-900 px-2.5 py-1.5 rounded-xl border border-zinc-750 gap-2 text-xs">
              <label className="flex items-center gap-1.5 cursor-pointer text-zinc-300">
                <input
                  type="checkbox"
                  checked={showCoordinates}
                  onChange={(e) => setShowCoordinates(e.target.checked)}
                  className="accent-emerald-500 rounded"
                />
                <span>Tọa độ</span>
              </label>
            </div>

            {/* Audio Mute / Unmute Button with LocalStorage persistence */}
            <button
              type="button"
              onClick={() => {
                const next = toggleAudio();
                setAudioEnabled(next);
              }}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-mono font-bold border transition-colors ${
                audioEnabled
                  ? 'bg-emerald-500/15 border-emerald-500/40 text-emerald-300 hover:bg-emerald-500/25'
                  : 'bg-zinc-800 border-zinc-700 text-zinc-400 hover:text-zinc-200'
              }`}
              title={audioEnabled ? 'Bấm để tắt âm thanh' : 'Bấm để bật âm thanh'}
            >
              <span>{audioEnabled ? '🔊' : '🔇'}</span>
              <span>{audioEnabled ? 'Âm thanh' : 'Đã tắt'}</span>
            </button>

            {onDownloadLog && (
              <>
                <button
                  type="button"
                  onClick={() => onDownloadLog('A')}
                  className="px-2.5 py-1.5 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-xs text-zinc-300 font-mono border border-zinc-700"
                >
                  Log A
                </button>
                <button
                  type="button"
                  onClick={() => onDownloadLog('B')}
                  className="px-2.5 py-1.5 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-xs text-zinc-300 font-mono border border-zinc-700"
                >
                  Log B
                </button>
              </>
            )}

          </div>
        </div>

        <button
          type="button"
          onClick={onClose}
          aria-label="Thoát replay"
          title="Thoát replay"
          className="absolute top-3 right-3 z-10 flex h-10 w-10 items-center justify-center rounded-xl border border-rose-400/60 bg-rose-500/25 text-2xl font-black leading-none text-rose-100 shadow-lg shadow-rose-950/40 transition hover:bg-rose-500/50 hover:text-white focus:outline-none focus:ring-2 focus:ring-rose-300"
        >
          ×
        </button>

        {totalTurns === 0 && (
          <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-center text-xs text-amber-200">
            Trận kết thúc trước nước đi hợp lệ đầu tiên. Bàn cờ bên dưới là đội hình ban đầu; kết quả và lý do kết thúc lấy từ máy chủ.
          </div>
        )}

        {/* 1. Army Advantage Bar (Thanh Tương Quan Lực Lượng) */}
        <div className="bg-zinc-900 p-3 rounded-2xl border border-zinc-750 space-y-2">
          <div className="flex items-center justify-between text-xs font-mono font-bold">
            {/* Team A stats */}
            <div className="flex items-center gap-2 text-blue-400">
              <span className="w-2.5 h-2.5 rounded-full bg-blue-500" />
              <span>Đội Xanh: {teamAPieces.length}/9 quân ({percentA}%)</span>
              <span className="text-zinc-400 font-normal hidden sm:inline">
                ({countRockA} 👊 · {countPaperA} ✋ · {countScissorA} ✌️)
              </span>
            </div>

            {/* Team B stats */}
            <div className="flex items-center gap-2 text-rose-400">
              <span className="text-zinc-400 font-normal hidden sm:inline">
                ({countRockB} 👊 · {countPaperB} ✋ · {countScissorB} ✌️)
              </span>
              <span>Đội Đỏ: {teamBPieces.length}/9 quân ({percentB}%)</span>
              <span className="w-2.5 h-2.5 rounded-full bg-rose-500" />
            </div>
          </div>

          {/* Visual Percentage Bar */}
          <div className="w-full h-2.5 bg-zinc-800 rounded-full overflow-hidden flex shadow-inner">
            <div
              className="h-full bg-gradient-to-r from-blue-600 to-blue-400 transition-all duration-300"
              style={{ width: `${percentA}%` }}
            />
            <div
              className="h-full bg-gradient-to-r from-rose-400 to-rose-600 transition-all duration-300"
              style={{ width: `${percentB}%` }}
            />
          </div>
        </div>

        {/* Main Center Area: Split-pane (Board 7 cols, Inspector + Combat Log 5 cols) */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
          {/* Left Column: Board & Playback Controls */}
          <div className="lg:col-span-7 flex flex-col space-y-3">
            {/* 9x9 Board */}
            <Board
              replay={activeReplay}
              step={step}
              inspectedPieceId={inspectedPieceId}
              onSelectPiece={(piece) => setInspectedPieceId(piece.id)}
              inspectedValidMoves={inspectedValidMoves}
              showCoordinates={showCoordinates}
              isFlipped={isFlipped}
            />

            {/* Playback Controls & Speed */}
            <div className="bg-zinc-900 p-3.5 rounded-2xl border border-zinc-750 space-y-3">
              {/* Button row */}
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => { setStep(0); setPlaying(false); }}
                    className="p-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-200 text-xs font-bold"
                    title="Về đầu"
                  >
                    ⏮
                  </button>
                  <button
                    type="button"
                    onClick={() => { setPlaying(false); setStep(Math.max(0, step - 1)); }}
                    className="p-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-200 text-xs font-bold"
                    title="Lùi 1 bước"
                  >
                    ◀
                  </button>
                  <button
                    type="button"
                    onClick={() => setPlaying(!playing)}
                    className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-black shadow"
                  >
                    {playing ? '⏸ Tạm Dừng' : '▶ Phát'}
                  </button>
                  <button
                    type="button"
                    onClick={() => { setPlaying(false); setStep(Math.min(totalTurns, step + 1)); }}
                    className="p-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-200 text-xs font-bold"
                    title="Tiến 1 bước"
                  >
                    ▶
                  </button>
                  <button
                    type="button"
                    onClick={handleSkipToEnd}
                    className="px-3 py-2 rounded-xl bg-amber-500/20 hover:bg-amber-500/30 text-amber-400 border border-amber-500/40 text-xs font-bold flex items-center gap-1"
                    title="Nhảy thẳng tới kết quả cuối cùng"
                  >
                    <span>⏭️</span>
                    <span>Skip Kết Quả</span>
                  </button>
                </div>

                {/* Speed Controls: 0.5x, 1x, 2x, 4x, 8x */}
                <div className="flex items-center bg-zinc-950 p-1 rounded-xl border border-zinc-800 gap-1">
                  {[0.5, 1, 2, 4, 8].map((s) => (
                    <button
                      key={s}
                      type="button"
                      onClick={() => setSpeed(s)}
                      className={`px-2 py-1 rounded-lg text-xs font-bold font-mono transition-all ${
                        speed === s
                          ? 'bg-emerald-600 text-white shadow'
                          : 'bg-transparent text-zinc-400 hover:text-white'
                      }`}
                    >
                      {s}x
                    </button>
                  ))}
                </div>
              </div>

              {/* Scrubber Timeline with Combat Event Markers */}
              <div className="space-y-1 relative pt-1">
                <input
                  type="range"
                  min="0"
                  max={totalTurns}
                  value={step}
                  onMouseDown={() => {
                    isScrubbingRef.current = true;
                  }}
                  onMouseUp={() => {
                    isScrubbingRef.current = false;
                  }}
                  onTouchStart={() => {
                    isScrubbingRef.current = true;
                  }}
                  onTouchEnd={() => {
                    isScrubbingRef.current = false;
                  }}
                  onChange={(e) => {
                    setPlaying(false);
                    setStep(Number(e.target.value));
                  }}
                  className="w-full accent-emerald-500 cursor-pointer"
                />

                {/* Combat Capture Markers */}
                <div className="relative w-full h-2 pointer-events-none">
                  {captureTurns.map((ct) => {
                    const stepNum = typeof ct === 'object' ? ct.step : ct;
                    const roundNum = typeof ct === 'object' ? ct.round : ct;
                    const posPct = totalTurns > 0 ? (stepNum / totalTurns) * 100 : 0;
                    return (
                      <span
                        key={`capture-step-${stepNum}`}
                        style={{ left: `${posPct}%` }}
                        className="absolute -top-1 w-2 h-2 rounded-full bg-rose-500 ring-1 ring-black shadow cursor-pointer pointer-events-auto"
                        title={`Nước #${stepNum} (Hiệp ${roundNum}): Giao tranh ăn quân`}
                        onClick={() => handleJumpToTurn(stepNum)}
                      />
                    );
                  })}
                </div>

                <div className="flex justify-between text-[11px] font-mono text-zinc-400">
                  <span>Lượt: {step} / {totalTurns}</span>
                  <span className="flex items-center gap-1">
                    <span className="w-2 h-2 rounded-full bg-rose-500 inline-block" />
                    <span>= Lượt có giao tranh</span>
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* Right Column: Piece Inspector & Combat Event Log */}
          <div className="lg:col-span-5 flex flex-col space-y-4">
            {/* 2. Piece Inspector Card */}
            <div className="bg-zinc-900 p-4 rounded-2xl border border-zinc-750 space-y-3">
              <div className="flex items-center justify-between border-b border-zinc-800 pb-2">
                <span className="text-xs font-black uppercase text-amber-400 flex items-center gap-1.5 font-mono">
                  <span>🔍</span> PIECE INSPECTOR (SOI QUÂN CỜ)
                </span>
                {inspectedPiece && (
                  <button
                    type="button"
                    onClick={() => setInspectedPieceId(null)}
                    className="text-[11px] text-zinc-400 hover:text-white font-mono"
                  >
                    Bỏ chọn ✕
                  </button>
                )}
              </div>

              {inspectedPiece ? (
                <div className="space-y-2 text-xs font-mono">
                  <div className="flex items-center gap-3 bg-zinc-950 p-2.5 rounded-xl border border-zinc-800">
                    <span className="text-2xl">{PIECE_EMOJIS[inspectedPiece.type]}</span>
                    <div>
                      <h5 className="font-bold text-white text-sm">
                        Quân #{inspectedPiece.id} · {inspectedPiece.type}
                      </h5>
                      <span
                        className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                          inspectedPiece.team === 'A'
                            ? 'bg-blue-500/20 text-blue-400 border border-blue-500/30'
                            : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                        }`}
                      >
                        Đội {inspectedPiece.team === 'A' ? 'Xanh (Phe A)' : 'Đỏ (Phe B)'}
                      </span>
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-2 text-zinc-300">
                    <div className="bg-zinc-950 p-2 rounded-xl border border-zinc-800">
                      <span className="text-zinc-500 block text-[10px]">VỊ TRÍ HIỆN TẠI</span>
                      <span className="font-bold text-white">{posToLabel(inspectedPiece.row, inspectedPiece.col)}</span>
                    </div>
                    <div className="bg-zinc-950 p-2 rounded-xl border border-zinc-800">
                      <span className="text-zinc-500 block text-[10px]">ĐÃ TIÊU DIỆT</span>
                      <span className="font-bold text-amber-400">{inspectedPiece.kills || 0} quân địch</span>
                    </div>
                  </div>

                  <div className="bg-zinc-950 p-2 rounded-xl border border-zinc-800">
                    <span className="text-zinc-500 block text-[10px] mb-1">CÁC Ô HỢP LỆ CÓ THỂ ĐI TIẾP</span>
                    {inspectedValidMoves.length > 0 ? (
                      <div className="flex flex-wrap gap-1">
                        {inspectedValidMoves.map((m, idx) => (
                          <span
                            key={idx}
                            className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${
                              m.capturePieceId
                                ? 'bg-rose-500/20 text-rose-300 border border-rose-500/40'
                                : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'
                            }`}
                          >
                            {posToLabel(m.row, m.col)} {m.capturePieceId ? '⚔️' : ''}
                          </span>
                        ))}
                      </div>
                    ) : (
                      <span className="text-zinc-600 italic text-[11px]">Bị chặn 4 hướng (Không có nước đi hợp lệ)</span>
                    )}
                  </div>
                </div>
              ) : (
                <div className="py-4 text-center text-zinc-500 text-xs">
                  <p>Nhấp vào bất kỳ quân cờ nào trên bàn cờ để soi thông số chi tiết & các ô nước đi hợp lệ.</p>
                </div>
              )}
            </div>

            {/* 3. Combat Event Timeline & Live Log */}
            <div className="bg-zinc-900 rounded-2xl border border-zinc-750 p-4 flex flex-col flex-1 min-h-[260px] max-h-[320px]">
              <div className="flex items-center justify-between pb-2 border-b border-zinc-800 mb-2">
                <span className="text-xs font-black uppercase text-white flex items-center gap-1.5 font-mono">
                  <span>📜</span> NHẬT KÝ CHIẾN ĐẤU ({totalTurns} LƯỢT)
                </span>
                <span className="text-[10px] font-mono text-zinc-500">Bấm để nhảy tới lượt</span>
              </div>

              <div className="flex-1 overflow-y-auto space-y-1.5 pr-1 font-mono text-xs">
                {turnsList.map((t, idx) => {
                  const stepIndex = idx + 1;
                  const roundNum = t.t ?? t.turn;
                  const isCurrent = stepIndex === step;
                  const isCapture =
                    t.events?.some((ev) => ev.type === 'CAPTURED' || ev.type === 'CAPTURE') ||
                    Boolean(t.capturedPieceId);

                  return (
                    <div
                      key={`turn-step-${stepIndex}`}
                      onClick={() => handleJumpToTurn(stepIndex)}
                      className={`p-2 rounded-xl border cursor-pointer transition-all ${
                        isCurrent
                          ? 'bg-emerald-950/40 border-emerald-500 text-white shadow'
                          : isCapture
                          ? 'bg-rose-950/20 hover:bg-rose-950/40 border-rose-900/50 text-rose-200'
                          : 'bg-zinc-950/50 hover:bg-zinc-950 border-zinc-800 text-zinc-400'
                      }`}
                    >
                      <div className="flex items-center justify-between">
                        <span className="text-[10px] font-bold text-zinc-500">
                          Nước #{stepIndex}{roundNum ? ` (Hiệp ${roundNum})` : ''}
                        </span>
                        <span
                          className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                            t.team === 'A' ? 'text-blue-400' : 'text-rose-400'
                          }`}
                        >
                          Đội {t.team === 'A' ? 'Xanh' : 'Đỏ'}
                        </span>
                      </div>

                      <p className="text-[11px] mt-0.5 leading-snug">
                        {getTurnDescription(t, idx)}
                      </p>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </div>

        {/* 4. Match End Summary Modal Overlay */}
        {showSummaryModal && (
          <div className="fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-fadeIn">
            <div className="bg-zinc-850 border border-zinc-700 w-full max-w-md rounded-3xl p-6 text-center space-y-4 shadow-2xl">
              <div className="text-5xl">
                {activeUserViewerSide
                  ? isUserWin
                    ? '🎉'
                    : isMatchDraw
                    ? '🤝'
                    : '💀'
                  : winnerCode === 'A'
                  ? '🏆'
                  : winnerCode === 'B'
                  ? '⚔️'
                  : '🤝'}
              </div>
              <h3 className={`text-2xl font-black ${
                activeUserViewerSide
                  ? isUserWin
                    ? 'text-emerald-400'
                    : isMatchDraw
                    ? 'text-amber-400'
                    : 'text-rose-400'
                  : 'text-white'
              }`}>
                {activeUserViewerSide
                  ? isUserWin
                    ? 'BẠN ĐÃ CHIẾN THẮNG!'
                    : isMatchDraw
                    ? 'TRẬN ĐẤU HÒA!'
                    : 'BẠN ĐÃ THẤT BẠI!'
                  : winnerCode === 'DRAW'
                  ? 'Trận Đấu Bất Phân Thắng Bại!'
                  : winnerCode === 'A'
                  ? `Đội Xanh (${sideAName}) Thắng Lợi!`
                  : `Đội Đỏ (${sideBName}) Thắng Lợi!`}
              </h3>

              <div className="bg-zinc-900 p-3.5 rounded-2xl border border-zinc-800 text-xs font-mono text-zinc-300 space-y-1.5 text-left">
                <div className="flex justify-between">
                  <span className="text-zinc-500">Lý do kết thúc:</span>
                  <span className="font-bold text-amber-400">
                    {getReasonLabel(reasonCode)}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-zinc-500">Tổng số lượt đấu:</span>
                  <span className="font-bold text-white">{totalTurns} lượt</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-zinc-500">Quân sống sót Xanh:</span>
                  <span className="font-bold text-blue-400">{teamAPieces.length}/9 quân</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-zinc-500">Quân sống sót Đỏ:</span>
                  <span className="font-bold text-rose-400">{teamBPieces.length}/9 quân</span>
                </div>

                {/* Khối Thống Kê Biến Động ELO: Phân tách rõ ràng PvP Giao Hữu, PvP Ranked, Ranked Bot & Giải Đấu */}
                {isCasualMatch ? (
                  <div className="pt-2 border-t border-zinc-800">
                    <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-center space-y-1">
                      <div className="flex items-center justify-center gap-1.5 text-xs font-bold text-emerald-400">
                        <span>🤝</span>
                        <span>Chế độ: PvP Giao Hữu (Không xếp hạng / 0 ELO)</span>
                      </div>
                      <p className="text-[10px] text-zinc-400">
                        Trận đấu giao lưu rèn luyện kỹ năng, không tính điểm ELO xếp hạng.
                      </p>
                    </div>
                  </div>
                ) : isRankedPvP ? (
                  <div className="pt-2 border-t border-zinc-800 space-y-2">
                    <div className="flex items-center justify-between text-[11px] font-bold">
                      <span className="text-zinc-400">⚡ Biến động ELO PvP Xếp Hạng:</span>
                      <span className="text-cyan-400 font-mono">Ranked PvP</span>
                    </div>

                    {viewerMatchData ? (
                      <div className={`p-3 rounded-2xl border text-left flex items-center justify-between ${
                        viewerMatchData.isWin
                          ? 'bg-emerald-950/40 border-emerald-500/50 text-emerald-100'
                          : viewerMatchData.isDraw
                          ? 'bg-amber-950/40 border-amber-500/50 text-amber-100'
                          : 'bg-rose-950/40 border-rose-500/50 text-rose-100'
                      }`}>
                        <div className="space-y-1">
                          <div className="flex items-center gap-2">
                            <span className={`px-2 py-0.5 rounded-full text-xs font-black font-mono tracking-wide ${
                              viewerMatchData.isWin
                                ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/40'
                                : viewerMatchData.isDraw
                                ? 'bg-amber-500/20 text-amber-400 border border-amber-500/40'
                                : 'bg-rose-500/20 text-rose-400 border border-rose-500/40'
                            }`}>
                              {viewerMatchData.delta >= 0 ? `+${viewerMatchData.delta}` : viewerMatchData.delta} ELO
                            </span>
                            <span className="font-mono text-xs font-bold text-white">
                              {viewerMatchData.oldElo} <span className="text-zinc-400">➜</span> {viewerMatchData.newElo}
                            </span>
                          </div>
                          <p className="text-[11px] text-zinc-400 font-medium">
                            Đối đầu với: <strong className="text-zinc-200">{viewerMatchData.opponentName}</strong>
                          </p>
                        </div>

                        <div className="text-3xl">
                          {viewerMatchData.isWin ? '📈' : viewerMatchData.isDraw ? '🤝' : '📉'}
                        </div>
                      </div>
                    ) : (
                      <div className="p-3 bg-zinc-900 border border-zinc-800 rounded-2xl text-center text-xs text-zinc-400">
                        👁️ Bạn đang xem trận đấu với tư cách Khán Giả
                      </div>
                    )}

                    <p className="text-[10px] text-zinc-500 italic text-center">
                      * Điểm số ELO PvP được ghi nhận chính thức vào hệ thống xếp hạng người chơi.
                    </p>
                  </div>
                ) : isRankedBot ? (
                  <div className="pt-2 border-t border-zinc-800 space-y-2">
                    <div className="flex items-center justify-between text-[11px] font-bold">
                      <span className="text-zinc-400">⚡ Biến động ELO Ranked Bot:</span>
                      <span className="text-amber-400 font-mono">
                        {activeUserViewerSide ? 'Bot của bạn' : 'Khán giả'}
                      </span>
                    </div>

                    {botViewerMatchData?.hasRating ? (
                      <div className={`p-3 rounded-2xl border text-left flex items-center justify-between ${
                        botViewerMatchData.isWin
                          ? 'bg-emerald-950/40 border-emerald-500/50 text-emerald-100'
                          : botViewerMatchData.isDraw
                          ? 'bg-amber-950/40 border-amber-500/50 text-amber-100'
                          : 'bg-rose-950/40 border-rose-500/50 text-rose-100'
                      }`}>
                        <div className="space-y-1">
                          <div className="flex items-center gap-2">
                            <span className={`px-2 py-0.5 rounded-full text-xs font-black font-mono tracking-wide ${
                              botViewerMatchData.delta > 0
                                ? 'bg-emerald-500/20 text-emerald-300'
                                : botViewerMatchData.delta < 0
                                ? 'bg-rose-500/20 text-rose-300'
                                : 'bg-zinc-700/60 text-zinc-300'
                            }`}>
                              {botViewerMatchData.delta > 0 ? '+' : ''}{botViewerMatchData.delta} ELO
                            </span>
                            <span className="text-white font-mono font-bold">
                              {botViewerMatchData.oldElo} <span className="text-zinc-400">➜</span> {botViewerMatchData.newElo}
                            </span>
                          </div>
                          <p className="text-[11px] text-zinc-400 font-medium">
                            Bot: <strong className="text-zinc-200">{botViewerMatchData.botName}</strong>
                            {' · '}Đối đầu với: <strong className="text-zinc-200">{botViewerMatchData.opponentName}</strong>
                          </p>
                        </div>

                        <div className="text-3xl">
                          {botViewerMatchData.isWin ? '📈' : botViewerMatchData.isDraw ? '🤝' : '📉'}
                        </div>
                      </div>
                    ) : activeUserViewerSide ? (
                      <div className="p-3 bg-zinc-900 border border-zinc-800 rounded-2xl text-center text-xs text-zinc-400 flex items-center justify-center gap-2">
                        <span className="inline-block w-3.5 h-3.5 border-2 border-zinc-500 border-t-amber-400 rounded-full animate-spin" />
                        <span>Đang đồng bộ điểm số ELO từ máy chủ...</span>
                      </div>
                    ) : (
                      <div className="p-3 bg-zinc-900 border border-zinc-800 rounded-2xl text-center text-xs text-zinc-400">
                        👁️ Bạn đang xem trận đấu với tư cách Khán Giả
                      </div>
                    )}

                    <p className="text-[10px] text-zinc-500 italic text-center">
                      * Chỉ hiển thị biến động ELO thực tế của bot thuộc tài khoản đang xem.
                    </p>
                  </div>
                ) : (
                  <div className="pt-2 border-t border-zinc-800">
                    <div className="p-3 rounded-xl bg-indigo-500/10 border border-indigo-500/20 text-center space-y-1">
                      <div className="flex items-center justify-center gap-1.5 text-xs font-bold text-indigo-400">
                        <span>🏆</span>
                        <span>Chế độ Giải Đấu - Không ảnh hưởng đến ELO Ranked</span>
                      </div>
                      <p className="text-[10px] text-zinc-400">
                        Kết quả trận đấu được tính vào bảng xếp hạng & tiến trình bảng đấu của giải.
                      </p>
                    </div>
                  </div>
                )}
              </div>

              <div className="flex gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => {
                    setShowSummaryModal(false);
                    setStep(0);
                    setPlaying(true);
                  }}
                  className="flex-1 py-3 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-200 font-bold text-xs border border-zinc-700 transition-colors"
                >
                  ⏮ Xem Lại Từ Đầu
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setShowSummaryModal(false);
                    onClose();
                  }}
                  className="flex-1 py-3 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs shadow-lg transition-all"
                >
                  Đóng Viewer
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
