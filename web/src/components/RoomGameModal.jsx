import React, { useState, useEffect, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client/dist/sockjs';
import { buildApiUrl, buildWsUrl, createStompWebSocket } from '../utils/api.js';
import { joinRoomApi, getRoomApi } from '../utils/roomApi.js';
import InteractiveBoard from './InteractiveBoard.jsx';
import {
  INITIAL_PIECES,
  getValidMoves,
  checkWinner,
  posToLabel,
  PIECE_EMOJIS,
  CHESS_TIME_CONTROLS,
  formatChessClock,
} from '../utils/gameRules.js';
import {
  playMoveSound,
  playCaptureSound,
  playVictorySound,
  playDefeatSound,
  playDrawSound,
  isAudioEnabled,
  toggleAudio,
} from '../utils/soundEffects.js';
import { getSparringAiMove } from '../utils/sparringAi.js';

// Tactical AI evaluator for generic fallback Bot (playing Team B, advancing towards target A9 at [8, 0])
function getBestBotMove(currentPieces) {
  const teamBPieces = currentPieces.filter((p) => p.team === 'B' && p.alive);
  const candidates = [];

  for (const piece of teamBPieces) {
    const validMoves = getValidMoves(piece, currentPieces);
    for (const move of validMoves) {
      let score = 0;
      // 1. Move into target zone A9 (row 8, col 0) -> Immediate win!
      if (move.row === 8 && move.col === 0) {
        score += 10000;
      }
      // 2. Capture opponent piece
      if (move.capturePieceId) {
        score += 600;
      }
      // 3. Move closer to target A9 [8, 0]
      const curDist = Math.abs(piece.row - 8) + Math.abs(piece.col - 0);
      const nextDist = Math.abs(move.row - 8) + Math.abs(move.col - 0);
      score += (curDist - nextDist) * 35;

      // 4. Slight random factor for natural gameplay variability
      score += Math.random() * 8;

      candidates.push({ piece, move, score });
    }
  }

  if (candidates.length === 0) return null;
  candidates.sort((a, b) => b.score - a.score);
  return candidates[0];
}

export default function RoomGameModal({
  roomCode,
  playerToken = null,
  matchId = null,
  isHost = false,
  onClose,
  initialTimeControl = null,
  timeControl = null,
  userName = 'Người chơi',
  pvpElo = 1200,
  userElo = 1200,
  targetOpponent = null,
  onMatchFinished = null,
  onOpenReplay = null,
  isQuickMatch = false,
  isSpectator = false,
  spectatorInfo = null,
}) {
  const currentPvpElo = pvpElo || userElo;
  const isSparring = Boolean(targetOpponent?.isSparring || targetOpponent?.aiLevel || targetOpponent?.level);
  const sparringLevel = targetOpponent?.aiLevel || targetOpponent?.level || 1;
  const isPreMatched = Boolean(isQuickMatch || targetOpponent);

  const [roomState, setRoomState] = useState(isSparring || isSpectator || isPreMatched ? 'PLAYING' : 'WAITING');
  const [isSearching, setIsSearching] = useState(!isSparring && !isSpectator && !isPreMatched);
  const [opponentType, setOpponentType] = useState(isSparring ? 'BOT' : 'HUMAN');
  const mySide = isSpectator ? 'SPECTATOR' : isHost ? 'A' : 'B'; // Host is Blue (A), Guest is Red (B)
  const [isFlipped, setIsFlipped] = useState(!isHost && !isSparring && !isSpectator);
  const [audioEnabled, setAudioEnabled] = useState(() => isAudioEnabled());
  const [waitingSeconds, setWaitingSeconds] = useState(0);

  useEffect(() => {
    const handleToggle = (e) => setAudioEnabled(e.detail.enabled);
    window.addEventListener('ottv2_audio_toggle', handleToggle);
    return () => window.removeEventListener('ottv2_audio_toggle', handleToggle);
  }, []);

  // Waiting counter when in WAITING state or searching
  useEffect(() => {
    if (roomState !== 'WAITING' && !isSearching) return undefined;
    setWaitingSeconds(0);
    const timer = setInterval(() => {
      setWaitingSeconds((prev) => prev + 1);
    }, 1000);
    return () => clearInterval(timer);
  }, [roomState, isSearching]);

  const selectedTc = timeControl || initialTimeControl;
  const [gameState, setGameState] = useState(() => ({
    status: isSparring || isPreMatched ? 'PLAYING' : 'WAITING',
    timeControl: selectedTc || { initialSeconds: 180, incrementSeconds: 2, startTime: Date.now() },
    startTime: selectedTc?.startTime || Date.now(),
  }));

  // Safe startTime accessor with optional chaining and fallback
  const startTime =
    gameState?.timeControl?.startTime ||
    gameState?.startTime ||
    selectedTc?.startTime ||
    Date.now();

  const initialSeconds =
    gameState?.timeControl?.initialSeconds ??
    selectedTc?.initialSeconds ??
    (selectedTc?.baseMs ? Math.round(selectedTc.baseMs / 1000) : 180);
  const incrementSeconds =
    gameState?.timeControl?.incrementSeconds ??
    selectedTc?.incrementSeconds ??
    (selectedTc?.incMs ? Math.round(selectedTc.incMs / 1000) : 2);
  const baseMs = initialSeconds * 1000;
  const incMs = incrementSeconds * 1000;
  const tcLabel =
    selectedTc?.label ||
    selectedTc?.name ||
    gameState?.timeControl?.label ||
    `${Math.floor(initialSeconds / 60)}+${incrementSeconds}`;

  const [pieces, setPieces] = useState(() =>
    INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 }))
  );

  // Render Guard: Only open board when not searching and valid game data is available
  const hasValidGameData = Boolean(
    Array.isArray(pieces) && pieces.length > 0 && (selectedTc || gameState?.timeControl)
  );
  const [currentTurn, setCurrentTurn] = useState('A');
  const [selectedPiece, setSelectedPiece] = useState(null);
  const [validMoves, setValidMoves] = useState([]);
  const [lastMove, setLastMove] = useState(null);
  const [moveHistory, setMoveHistory] = useState([]);
  const [timeLeftA, setTimeLeftA] = useState(baseMs);
  const [timeLeftB, setTimeLeftB] = useState(baseMs);
  const [winner, setWinner] = useState(null);
  const [winReason, setWinReason] = useState(null);
  const [showWinModal, setShowWinModal] = useState(false);
  const [serverEloResult, setServerEloResult] = useState(null);
  const winnerRef = useRef(winner);
  winnerRef.current = winner;
  const [copied, setCopied] = useState(false);

  const stompClientRef = useRef(null);
  const [wsConnected, setWsConnected] = useState(false);
  const [opponentReady, setOpponentReady] = useState(Boolean(isSparring || isSpectator || isPreMatched));
  const [myReady, setMyReady] = useState(true);
  const recordedTurnsRef = useRef([]);
  const matchIdRef = useRef(matchId || `pvp-${roomCode || 'match'}-${Date.now()}`);
  const notifiedRef = useRef(false);

  useEffect(() => {
    if (matchId) {
      matchIdRef.current = matchId;
    }
  }, [matchId]);

  const [remoteOpponentName, setRemoteOpponentName] = useState(
    targetOpponent?.displayName || targetOpponent?.name || targetOpponent?.username || ''
  );
  const [remoteOpponentElo, setRemoteOpponentElo] = useState(
    targetOpponent?.elo || targetOpponent?.pvpElo || 1200
  );

  useEffect(() => {
    if (targetOpponent) {
      if (targetOpponent.displayName || targetOpponent.name || targetOpponent.username) {
        setRemoteOpponentName(targetOpponent.displayName || targetOpponent.name || targetOpponent.username);
      }
      if (targetOpponent.elo || targetOpponent.pvpElo) {
        setRemoteOpponentElo(targetOpponent.elo || targetOpponent.pvpElo);
      }
    }
  }, [targetOpponent]);

  // Mutable refs to keep STOMP WebSocket connection isolated from component re-renders
  const playerTokenRef = useRef(playerToken);
  useEffect(() => {
    if (playerToken) {
      playerTokenRef.current = playerToken;
    }
  }, [playerToken]);

  // If entering room without playerToken, fetch or join room to retrieve playerToken
  useEffect(() => {
    if (!playerTokenRef.current && !isSparring && !isSpectator && roomCode) {
      if (isHost) {
        getRoomApi(roomCode, { username: userName })
          .then((res) => {
            if (res?.playerToken) {
              playerTokenRef.current = res.playerToken;
            }
          })
          .catch((err) => {
            console.warn('[RoomGameModal] Host token sync failed:', err);
          });
      } else {
        joinRoomApi(roomCode, userName)
          .then((res) => {
            if (res?.playerToken) {
              playerTokenRef.current = res.playerToken;
            }
          })
          .catch((err) => {
            console.warn('[RoomGameModal] Auto-join room token fetch failed:', err);
          });
      }
    }
  }, [roomCode, isHost, isSparring, isSpectator, userName]);

  const isHostRef = useRef(isHost);
  isHostRef.current = isHost;

  const mySideRef = useRef(mySide);
  mySideRef.current = mySide;

  const userNameRef = useRef(userName);
  userNameRef.current = userName;

  const opponentTypeRef = useRef(opponentType);
  opponentTypeRef.current = opponentType;

  const selectedTcRef = useRef(selectedTc);
  selectedTcRef.current = selectedTc;

  const startTimeRef = useRef(startTime);
  startTimeRef.current = startTime;

  const baseMsRef = useRef(baseMs);
  baseMsRef.current = baseMs;

  const recordTurn = (
    team,
    pieceId,
    toPos,
    capturePieceId = null,
    fromPos = null,
    attackerType = null,
    capturedType = null
  ) => {
    const t = recordedTurnsRef.current.length + 1;
    const fromLabel = fromPos ? posToLabel(fromPos[0], fromPos[1]) : '?';
    const toLabel = toPos ? posToLabel(toPos[0], toPos[1]) : '?';
    const teamLabel = team === 'A' ? 'Xanh' : 'Đỏ';

    const events = [];
    if (capturePieceId) {
      events.push({
        type: 'CAPTURED',
        victimId: capturePieceId,
        target: capturePieceId,
        victimType: capturedType,
        victimTeam: team === 'A' ? 'B' : 'A',
        attackerId: pieceId,
        attackerType: attackerType,
        attackerTeam: team,
        description: `[${teamLabel}] Quân #${pieceId} (${fromLabel} → ${toLabel}) tiêu diệt quân #${capturePieceId}!`,
      });
    } else {
      events.push({
        type: 'MOVE',
        description: `[${teamLabel}] Quân #${pieceId} (${fromLabel} → ${toLabel})`,
      });
    }

    recordedTurnsRef.current.push({
      t,
      turn: t,
      team,
      pieceId,
      pieceType: attackerType,
      from: fromPos,
      to: toPos,
      capturedPieceId: capturePieceId || null,
      capturedPieceType: capturedType || null,
      move: {
        p: pieceId,
        pieceId,
        from: fromPos,
        to: toPos,
      },
      events,
    });
  };

  const buildReplayData = (finalWinner, finalReason) => {
    if (!matchIdRef.current) {
      matchIdRef.current = matchId || `pvp-${roomCode || 'room'}-${Date.now()}`;
    }
    const sideAName = isHost
      ? userName
      : (opponentType === 'BOT' ? userName : (remoteOpponentName || targetOpponent?.name || 'Chủ Phòng (Đội Xanh)'));
    const sideBName = isHost
      ? (isSparring ? (targetOpponent?.name || 'Máy Tập Luyện') : opponentType === 'BOT' ? 'Bot Dự Phòng' : (remoteOpponentName || targetOpponent?.name || 'Khách (Đội Đỏ)'))
      : userName;

    const replayObj = {
      version: 1,
      matchId: matchIdRef.current,
      sideA: { botName: sideAName },
      sideB: { botName: sideBName },
      initialBoard: Array.from({ length: 9 }, () => Array(9).fill('EMPTY')),
      initialPieces: INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 })),
      turns: [...recordedTurnsRef.current],
      moves: [...recordedTurnsRef.current],
      movesHistory: [...recordedTurnsRef.current],
      replayEvents: [...recordedTurnsRef.current],
      result: { winner: finalWinner, reason: finalReason },
    };

    try {
      localStorage.setItem(`ottv2_replay_${replayObj.matchId}`, JSON.stringify(replayObj));
      if (roomCode) {
        localStorage.setItem(`ottv2_replay_${roomCode}`, JSON.stringify(replayObj));
      }
    } catch (e) {
      console.warn('Lỗi lưu replay vào localStorage:', e);
    }

    return replayObj;
  };

  const handleFinishGame = (finalWinner, finalReason) => {
    winnerRef.current = finalWinner;
    setWinner(finalWinner);
    setWinReason(finalReason);
    setTimeout(() => {
      setShowWinModal(true);
    }, 450);

    if (notifiedRef.current) return;
    notifiedRef.current = true;

    const replay = buildReplayData(finalWinner, finalReason);
    const userWon = opponentType === 'BOT' ? finalWinner === 'A' : finalWinner === mySide;

    const resolvedOpponentName = isSparring
      ? (targetOpponent?.name || 'Máy Tập Luyện')
      : opponentType === 'BOT'
      ? 'Bot Dự Phòng'
      : (remoteOpponentName || targetOpponent?.name || (isHost ? 'Khách (Đội Đỏ)' : 'Chủ Phòng (Đội Xanh)'));

    const resolvedOpponentElo = isSparring
      ? (targetOpponent?.elo || 1050)
      : (remoteOpponentElo || targetOpponent?.elo || 1200);

    const isRankedMatch = !isSparring && (opponentType === 'HUMAN' || Boolean(isQuickMatch));

    if (onMatchFinished) {
      onMatchFinished({
        roomCode,
        matchId: matchId || matchIdRef.current || replay.matchId,
        replayId: matchId || matchIdRef.current || replay.matchId,
        opponentType: isSparring ? 'SPARRING' : opponentType,
        opponentName: resolvedOpponentName,
        opponentElo: resolvedOpponentElo,
        userWon,
        winner: finalWinner,
        winReason: finalReason,
        replay,
        replayData: replay,
        isRanked: isRankedMatch,
        mode: isQuickMatch ? 'PVP_QUICK' : 'PVP',
        mySide,
        isHost,
      });
    }
  };

  // Setup STOMP WebSocket via SockJS for Real-time Internet PvP
  useEffect(() => {
    if (isSparring) return undefined;

    const sockJsUrl = buildWsUrl('/api/ws');

    let isSubscribed = true;

    const client = new Client({
      webSocketFactory: () => createStompWebSocket('/api/ws'),
      connectHeaders: {
        'ngrok-skip-browser-warning': 'true',
      },
      reconnectDelay: 4000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      debug: (str) => {
        if (str && !str.includes('PING') && !str.includes('PONG')) {
          console.log('[STOMP Debug]', str);
        }
      },
      onConnect: (frame) => {
        // Requirement 3: BẮT BUỘC gọi ngay dòng đầu tiên trong callback onConnect
        console.log('[STOMP] CONNECTED successfully, headers:', frame?.headers);
        setWsConnected(true);
        if (!isSubscribed) return;

        // Đăng ký theo dõi kênh: stompClient.subscribe('/topic/room/' + roomCode, (message) => { ... })
        console.log(`[STOMP] Subscribing to /topic/room/${roomCode}`);
        client.subscribe(`/topic/room/${roomCode}`, (message) => {
          if (!message?.body) return;
          try {
            const data = JSON.parse(message.body);
            if (!data || String(data.roomCode) !== String(roomCode)) return;
            if (data.sender && data.sender === mySideRef.current) return; // Bỏ qua tin nhắn do chính mình gửi

            // Đồng bộ hoá luồng mở bàn cờ khi nhận được tín hiệu bắt đầu trận
            if (
              data.type === 'MATCH_START' ||
              data.type === 'ROOM_START' ||
              data.status === 'PLAYING' ||
              data.gameState
            ) {
              if (opponentTypeRef.current !== 'BOT') {
                if (data.gameState) {
                  setGameState(data.gameState);
                } else if (data.timeControl) {
                  setGameState((prev) => ({
                    ...prev,
                    status: 'PLAYING',
                    timeControl: data.timeControl,
                    startTime: data.timeControl?.startTime || data.startTime || Date.now(),
                  }));
                }
                setOpponentReady(true);
                setOpponentType('HUMAN');
                setRoomState('PLAYING');
                setIsSearching(false);
              }
            }

            switch (data.type) {
              case 'PLAYER_JOINED':
                if (opponentTypeRef.current === 'BOT') return;
                setOpponentReady(true);
                setOpponentType('HUMAN');
                setRoomState('PLAYING');
                setIsSearching(false);
                if (data.senderName || data.username || data.displayName) {
                  setRemoteOpponentName(data.senderName || data.displayName || data.username);
                }
                if (data.elo || data.pvpElo) {
                  setRemoteOpponentElo(data.elo || data.pvpElo);
                }
                if (isHostRef.current) {
                  // Host phát tin xác nhận bắt đầu trận đấu
                  try {
                    client.publish({
                      destination: `/app/room/${roomCode}/action`,
                      headers: { 'content-type': 'application/json' },
                      body: JSON.stringify({
                        type: 'ROOM_START',
                        roomCode: String(roomCode),
                        sender: mySideRef.current || 'A',
                        senderName: userNameRef.current || 'Chủ Phòng',
                        playerToken: playerTokenRef.current,
                        timeControl: selectedTcRef.current || null,
                        ready: true,
                        startTime: startTimeRef.current || Date.now(),
                      }),
                    });
                    console.log('[STOMP SEND] /app/room/' + roomCode + '/action (ROOM_START)');
                  } catch (pubErr) {
                    console.error('Lỗi gửi ROOM_START:', pubErr);
                  }
                }
                break;

              case 'ROOM_START':
              case 'MATCH_START':
                if (opponentTypeRef.current !== 'BOT') {
                  setOpponentType('HUMAN');
                  setRoomState('PLAYING');
                  setIsSearching(false);
                  setOpponentReady(true);
                  if (data.senderName || data.username || data.displayName) {
                    setRemoteOpponentName(data.senderName || data.displayName || data.username);
                  }
                  if (data.elo || data.pvpElo) {
                    setRemoteOpponentElo(data.elo || data.pvpElo);
                  }
                  if (data.timeControl) {
                    setGameState((prev) => ({
                      ...prev,
                      status: 'PLAYING',
                      timeControl: data.timeControl,
                      startTime: data.timeControl?.startTime || data.startTime || Date.now(),
                    }));
                  }
                }
                break;

              case 'READY_CHANGE':
                setOpponentReady(Boolean(data.ready));
                break;

              case 'MAKE_MOVE':
                if (opponentTypeRef.current === 'BOT' && !isSpectator) return;
                if (Array.isArray(data.nextPieces)) setPieces(data.nextPieces);
                if (data.nextTurn) setCurrentTurn(data.nextTurn);
                if (data.from && data.to) setLastMove({ from: data.from, to: data.to });
                if (data.capturePieceId) {
                  playCaptureSound();
                } else {
                  playMoveSound();
                }
                if (data.historyItem) {
                  setMoveHistory((prev) => [data.historyItem, ...prev]);
                }
                recordTurn(
                  data.historyItem?.team || (mySideRef.current === 'A' ? 'B' : 'A'),
                  data.pieceId,
                  data.to,
                  data.capturePieceId,
                  data.from,
                  data.historyItem?.pieceType,
                  data.historyItem?.capturedType
                );
                if (typeof data.timeLeftA === 'number') setTimeLeftA(data.timeLeftA);
                if (typeof data.timeLeftB === 'number') setTimeLeftB(data.timeLeftB);
                setSelectedPiece(null);
                setValidMoves([]);

                // Kiểm tra điều kiện thắng cờ từ payload hoặc tính toán trực tiếp từ bàn cờ mới
                const winCheck = Array.isArray(data.nextPieces)
                  ? checkWinner(data.nextPieces)
                  : { winner: null, reason: null };
                const finalWinner = data.winner || winCheck.winner;
                const finalReason = data.winReason || winCheck.reason;

                if (finalWinner) {
                  winnerRef.current = finalWinner;
                  handleFinishGame(finalWinner, finalReason);
                  if (finalWinner === mySideRef.current) {
                    playVictorySound();
                  } else if (finalWinner === 'DRAW') {
                    playDrawSound();
                  } else {
                    playDefeatSound();
                  }
                }
                break;

              case 'MATCH_FINISHED':
                const winningSide = data.winnerSide || data.winner;
                const endReason = data.endReason || data.winReason || 'TARGET_REACHED';

                // Cập nhật nước đi cuối cùng nếu server hoặc đối thủ gửi kèm
                if (data.finalMove) {
                  setLastMove(data.finalMove);
                } else if (data.lastMove) {
                  setLastMove(data.lastMove);
                } else if (data.from && data.to) {
                  setLastMove({ from: data.from, to: data.to });
                }
                if (Array.isArray(data.nextPieces)) {
                  setPieces(data.nextPieces);
                }

                if (data.sideA && data.sideB) {
                  const isMeSideA = (mySideRef.current === 'A');
                  const mySideData = isMeSideA ? data.sideA : data.sideB;
                  const isWin = winningSide === mySideRef.current;
                  const isDraw = winningSide === 'DRAW';
                  setServerEloResult({
                    isRanked: data.isRanked !== false,
                    isWin,
                    isDraw,
                    oldElo: mySideData.eloBefore,
                    newElo: mySideData.eloAfter,
                    delta: mySideData.eloChange,
                    opponentName: resolvedOpponentName,
                  });
                }

                if (!winnerRef.current) {
                  winnerRef.current = winningSide;
                  setWinner(winningSide);
                  setWinReason(endReason);
                  if (endReason === 'OPPONENT_LEFT' || endReason === 'RESIGN' || endReason === 'FORFEIT') {
                    setRoomState('OPPONENT_LEFT');
                  }
                  if (winningSide === mySideRef.current) {
                    playVictorySound();
                  } else if (winningSide === 'DRAW') {
                    playDrawSound();
                  } else {
                    playDefeatSound();
                  }
                  handleFinishGame(winningSide, endReason);
                }
                break;

              case 'CLOCK_SYNC':
                if (typeof data.timeLeftA === 'number') setTimeLeftA(data.timeLeftA);
                if (typeof data.timeLeftB === 'number') setTimeLeftB(data.timeLeftB);
                if (data.currentTurn) setCurrentTurn(data.currentTurn);
                break;

              case 'RESTART_GAME':
                if (opponentTypeRef.current === 'BOT') return;
                setPieces(INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 })));
                setCurrentTurn('A');
                setSelectedPiece(null);
                setValidMoves([]);
                setLastMove(null);
                setMoveHistory([]);
                setTimeLeftA(baseMsRef.current);
                setTimeLeftB(baseMsRef.current);
                winnerRef.current = null;
                setWinner(null);
                setWinReason(null);
                setShowWinModal(false);
                recordedTurnsRef.current = [];
                notifiedRef.current = false;
                break;

              case 'PLAYER_LEFT':
                if (opponentTypeRef.current === 'HUMAN') {
                  // Nếu ván cờ đã kết thúc (đã có kết quả thắng/thua), việc đối thủ rời phòng sau đó là bình thường
                  // TUYỆT ĐỐI KHÔNG ghi đè kết quả thành OPPONENT_LEFT!
                  if (winnerRef.current || notifiedRef.current) {
                    console.log('[STOMP] Đối thủ đã rời phòng sau khi trận đấu đã kết thúc.');
                    return;
                  }
                  setRoomState('OPPONENT_LEFT');
                  const remainingSide = mySideRef.current || 'A';
                  winnerRef.current = remainingSide;
                  setWinner(remainingSide);
                  setWinReason('OPPONENT_LEFT');
                  handleFinishGame(remainingSide, 'OPPONENT_LEFT');
                  playVictorySound();
                }
                break;

              default:
                break;
            }
          } catch (err) {
            console.error('Lỗi phân tích WebSocket message:', err);
          }
        });

        // Gửi thông báo kết nối/sẵn sàng lên topic phòng với payload đầy đủ, không undefined
        try {
          if (!isSpectator) {
            if (!isHostRef.current) {
              client.publish({
                destination: `/app/room/${roomCode}/action`,
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({
                  type: 'PLAYER_JOINED',
                  roomCode: String(roomCode),
                  sender: mySideRef.current || 'B',
                  senderName: userNameRef.current || 'Khách',
                  playerToken: playerTokenRef.current,
                  ready: true,
                  timeControl: selectedTcRef.current || null,
                  startTime: startTimeRef.current || Date.now(),
                }),
              });
              console.log('[STOMP SEND] /app/room/' + roomCode + '/action (PLAYER_JOINED)');
            } else {
              client.publish({
                destination: `/app/room/${roomCode}/action`,
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({
                  type: 'READY_CHANGE',
                  roomCode: String(roomCode),
                  sender: mySideRef.current || 'A',
                  senderName: userNameRef.current || 'Chủ Phòng',
                  playerToken: playerTokenRef.current,
                  ready: true,
                  timeControl: selectedTcRef.current || null,
                  startTime: startTimeRef.current || Date.now(),
                }),
              });
              console.log('[STOMP SEND] /app/room/' + roomCode + '/action (READY_CHANGE)');
            }
          }
        } catch (pubErr) {
          console.error('[STOMP Error] Lỗi gửi action khởi tạo phòng:', pubErr);
        }
      },
      onWebSocketError: (error) => {
        console.error('[STOMP WS Transport Error]', error?.message || error?.type || error);
      },
      onStompError: (frame) => {
        console.error('[STOMP Protocol Error]', frame?.headers?.['message'] || frame?.headers, frame?.body);
      },
      onWebSocketClose: (event) => {
        console.warn(`[STOMP WS Close] code=${event?.code}, reason=${event?.reason}, wasClean=${event?.wasClean}`);
        setWsConnected(false);
      },
    });

    client.activate();
    stompClientRef.current = client;

    return () => {
      isSubscribed = false;
      if (client.connected) {
        try {
          // Chỉ gửi PLAYER_LEFT nếu ván cờ đang diễn ra và chưa có kết quả thắng/thua
          if (!winnerRef.current) {
            client.publish({
              destination: `/app/room/${roomCode}/action`,
              headers: { 'content-type': 'application/json' },
              body: JSON.stringify({
                type: 'PLAYER_LEFT',
                roomCode: String(roomCode),
                sender: mySideRef.current || 'A',
                playerToken: playerTokenRef.current,
              }),
            });
          }
        } catch {}
      }
      client.deactivate();
      stompClientRef.current = null;
      setWsConnected(false);
    };
  }, [roomCode, isSparring]);

  // Bắt sự kiện thoát tab/reload trang giữa trận để giải phóng và thông báo cho đối thủ ngay tức thì
  useEffect(() => {
    if (isSparring || isSpectator) return undefined;
    const handleBeforeUnload = () => {
      if (roomState === 'PLAYING' && !winnerRef.current && opponentTypeRef.current === 'HUMAN') {
        try {
          if (stompClientRef.current?.connected) {
            stompClientRef.current.publish({
              destination: `/app/room/${roomCode}/action`,
              headers: { 'content-type': 'application/json' },
              body: JSON.stringify({
                type: 'PLAYER_LEFT',
                roomCode: String(roomCode),
                sender: mySideRef.current || 'A',
                playerToken: playerTokenRef.current,
                reason: 'OPPONENT_LEFT',
              }),
            });
          }
          if (navigator.sendBeacon) {
            const payload = JSON.stringify({ sender: mySideRef.current || 'A', roomCode });
            navigator.sendBeacon(buildApiUrl(`/api/rooms/${roomCode}/leave`), new Blob([payload], { type: 'application/json' }));
          }
        } catch (_) {}
      }
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    window.addEventListener('pagehide', handleBeforeUnload);
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload);
      window.removeEventListener('pagehide', handleBeforeUnload);
    };
  }, [roomCode, roomState, isSparring, isSpectator]);

  // Autonomous move loop for Sparring AI (Máy 1..12) or generic Fallback Bot
  useEffect(() => {
    if (opponentType !== 'BOT' || currentTurn !== 'B' || winner || roomState !== 'PLAYING') {
      return;
    }

    // Delay 400ms for Sparring AI as requested in Requirement 3
    const delayMs = isSparring ? 400 : 600;

    const botTimer = setTimeout(() => {
      const best = isSparring
        ? getSparringAiMove(sparringLevel, pieces)
        : getBestBotMove(pieces);

      if (!best) {
        handleFinishGame('A', 'ALL_CAPTURED');
        playVictorySound();
        return;
      }

      const { piece, move } = best;
      const fromPos = [piece.row, piece.col];
      const toPos = [move.row, move.col];
      let capturedType = null;

      const nextPieces = pieces.map((p) => {
        if (p.id === piece.id) {
          return { ...p, row: move.row, col: move.col };
        }
        if (p.id === move.capturePieceId) {
          capturedType = p.type;
          return { ...p, alive: false };
        }
        return p;
      });

      const historyItem = {
        turnNumber: moveHistory.length + 1,
        team: 'B',
        pieceType: piece.type,
        from: posToLabel(fromPos[0], fromPos[1]),
        to: posToLabel(toPos[0], toPos[1]),
        capturedType,
      };

      recordTurn('B', piece.id, toPos, move.capturePieceId, fromPos, piece.type, capturedType);

      if (move.capturePieceId) {
        playCaptureSound();
      } else {
        playMoveSound();
      }

      setPieces(nextPieces);
      setLastMove({ from: fromPos, to: toPos });
      setMoveHistory((prev) => [historyItem, ...prev]);
      setTimeLeftB((prev) => prev + incMs);

      const winResult = checkWinner(nextPieces);
      if (winResult.winner) {
        handleFinishGame(winResult.winner, winResult.reason);
        if (winResult.winner === 'A') {
          playVictorySound();
        } else if (winResult.winner === 'DRAW') {
          playDrawSound();
        } else {
          playDefeatSound();
        }
      } else {
        setCurrentTurn('A');
      }
    }, delayMs);

    return () => clearTimeout(botTimer);
  }, [currentTurn, opponentType, winner, roomState, pieces, isSparring, sparringLevel]);

  // Fischer Chess Clock Timer: Runs every 100ms for active player / bot
  useEffect(() => {
    if (roomState !== 'PLAYING' || winner) return undefined;

    const timer = setInterval(() => {
      if (currentTurn === 'A') {
        setTimeLeftA((prev) => {
          if (prev <= 100) {
            if (!isSpectator) {
              handleFinishGame('B', 'TIMEOUT');
              playDefeatSound();
            }
            return 0;
          }
          return prev - 100;
        });
      } else {
        setTimeLeftB((prev) => {
          if (prev <= 100) {
            if (!isSpectator) {
              handleFinishGame('A', 'TIMEOUT');
              playVictorySound();
            }
            return 0;
          }
          return prev - 100;
        });
      }
    }, 100);

    return () => clearInterval(timer);
  }, [currentTurn, roomState, winner, isSpectator]);

  // Handler to start match immediately with fallback Bot
  const handleStartWithBot = () => {
    setOpponentType('BOT');
    setRoomState('PLAYING');
    setIsSearching(false);
    setIsFlipped(false);
    recordedTurnsRef.current = [];
    notifiedRef.current = false;
  };

  // Handle piece selection (User only controls their team, disabled for spectator)
  const handleSelectPiece = (piece) => {
    if (isSpectator || winner || roomState !== 'PLAYING') return;
    if (opponentType === 'HUMAN' && !isSparring && !wsConnected) return;
    if (!piece) {
      setSelectedPiece(null);
      setValidMoves([]);
      return;
    }
    const allowedSide = opponentType === 'BOT' ? 'A' : mySide;
    if (piece.team !== allowedSide || currentTurn !== allowedSide) return;

    setSelectedPiece(piece);
    const moves = getValidMoves(piece, pieces);
    setValidMoves(moves);
  };

  // Handle making a move (disabled for spectator)
  const handleMakeMove = (move) => {
    if (isSpectator) return;
    if (opponentType === 'HUMAN' && !isSparring && !wsConnected) return;
    const allowedSide = opponentType === 'BOT' ? 'A' : mySide;
    if (!selectedPiece || winner || currentTurn !== allowedSide) return;

    const fromPos = [selectedPiece.row, selectedPiece.col];
    const toPos = [move.row, move.col];
    const attackerType = selectedPiece.type;

    let capturedType = null;
    const nextPieces = pieces.map((p) => {
      if (p.id === selectedPiece.id) {
        return { ...p, row: move.row, col: move.col };
      }
      if (p.id === move.capturePieceId) {
        capturedType = p.type;
        return { ...p, alive: false };
      }
      return p;
    });

    const nextTurn = currentTurn === 'A' ? 'B' : 'A';
    const historyItem = {
      turnNumber: moveHistory.length + 1,
      team: currentTurn,
      pieceType: attackerType,
      from: posToLabel(fromPos[0], fromPos[1]),
      to: posToLabel(toPos[0], toPos[1]),
      capturedType,
    };

    recordTurn(currentTurn, selectedPiece.id, toPos, move.capturePieceId, fromPos, attackerType, capturedType);

    if (move.capturePieceId) {
      playCaptureSound();
    } else {
      playMoveSound();
    }

    const winResult = checkWinner(nextPieces);

    setPieces(nextPieces);
    setLastMove({ from: fromPos, to: toPos });
    setMoveHistory((prev) => [historyItem, ...prev]);
    setSelectedPiece(null);
    setValidMoves([]);

    let nextTimeA = timeLeftA;
    let nextTimeB = timeLeftB;
    if (currentTurn === 'A') {
      nextTimeA += incMs;
      setTimeLeftA(nextTimeA);
    } else {
      nextTimeB += incMs;
      setTimeLeftB(nextTimeB);
    }

    // 1. Luôn publish nước đi (kèm trạng thái kết thúc trận nếu có) qua STOMP WebSocket trước tiên
    // để máy đối thủ nhận được ngay lập tức và render mượt mà nước cờ cuối cùng
    if (opponentType === 'HUMAN' && !isSparring && stompClientRef.current?.connected) {
      try {
        stompClientRef.current.publish({
          destination: `/app/room/${roomCode}/move`,
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({
            type: 'MAKE_MOVE',
            roomCode,
            sender: mySide,
            playerToken: playerTokenRef.current,
            from: fromPos,
            to: toPos,
            pieceId: selectedPiece.id,
            capturePieceId: move.capturePieceId,
            nextPieces,
            nextTurn,
            historyItem,
            timeLeftA: nextTimeA,
            timeLeftB: nextTimeB,
            winner: winResult.winner,
            winReason: winResult.reason,
          }),
        });
        console.log('[STOMP SEND] /app/room/' + roomCode + '/move');
      } catch (sendErr) {
        console.error('[STOMP Error] Lỗi gửi MAKE_MOVE:', sendErr);
      }
    }

    // 2. Cập nhật kết thúc trận cờ trên máy người chơi
    if (winResult.winner) {
      winnerRef.current = winResult.winner;
      handleFinishGame(winResult.winner, winResult.reason);
      if (winResult.winner === allowedSide) {
        playVictorySound();
      } else if (winResult.winner === 'DRAW') {
        playDrawSound();
      } else {
        playDefeatSound();
      }
    } else {
      setCurrentTurn(nextTurn);
    }
  };

  const handleRestart = () => {
    setPieces(INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 })));
    setCurrentTurn('A');
    setSelectedPiece(null);
    setValidMoves([]);
    setLastMove(null);
    setMoveHistory([]);
    setTimeLeftA(baseMs);
    setTimeLeftB(baseMs);
    winnerRef.current = null;
    setWinner(null);
    setWinReason(null);
    setShowWinModal(false);
    recordedTurnsRef.current = [];
    notifiedRef.current = false;

    if (opponentType === 'HUMAN' && !isSparring && stompClientRef.current?.connected) {
      try {
        stompClientRef.current.publish({
          destination: `/app/room/${roomCode}/action`,
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({
            type: 'RESTART_GAME',
            roomCode,
            sender: mySide,
            playerToken: playerTokenRef.current,
          }),
        });
        console.log('[STOMP SEND] /app/room/' + roomCode + '/action (RESTART_GAME)');
      } catch (restartErr) {
        console.error('[STOMP Error] Lỗi gửi RESTART_GAME:', restartErr);
      }
    }
  };

  // Periodic Fischer Clock sync over WebSocket every 5s for the active player
  useEffect(() => {
    if (roomState !== 'PLAYING' || winner || isSparring || opponentType !== 'HUMAN') return undefined;
    if (currentTurn !== mySide) return undefined;

    const syncTimer = setInterval(() => {
      if (stompClientRef.current?.connected) {
        try {
          stompClientRef.current.publish({
            destination: `/app/room/${roomCode}/action`,
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({
              type: 'CLOCK_SYNC',
              roomCode,
              sender: mySide,
              playerToken: playerTokenRef.current,
              timeLeftA,
              timeLeftB,
              currentTurn,
            }),
          });
        } catch (syncErr) {
          console.error('[STOMP Error] Lỗi gửi CLOCK_SYNC:', syncErr);
        }
      }
    }, 5000);

    return () => clearInterval(syncTimer);
  }, [roomState, winner, isSparring, opponentType, currentTurn, mySide, timeLeftA, timeLeftB, roomCode]);

  const handleSearchNewMatch = () => {
    setPieces(INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 })));
    setCurrentTurn('A');
    setSelectedPiece(null);
    setValidMoves([]);
    setLastMove(null);
    setMoveHistory([]);
    setTimeLeftA(baseMs);
    setTimeLeftB(baseMs);
    winnerRef.current = null;
    setWinner(null);
    setWinReason(null);
    setShowWinModal(false);
    recordedTurnsRef.current = [];
    notifiedRef.current = false;
    setOpponentType('HUMAN');
    setRoomState('WAITING');
    setIsSearching(true);
    setWaitingSeconds(0);
  };

  const handleCopyCode = () => {
    navigator.clipboard?.writeText(roomCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleWatchReplay = () => {
    const replay = buildReplayData(winner, winReason);
    const opponentName = isSparring
      ? targetOpponent.name
      : opponentType === 'BOT'
      ? 'Bot Dự Phòng'
      : isHost
      ? 'Khách (Đội Đỏ)'
      : 'Chủ Phòng (Đội Xanh)';

    const matchInfo = {
      id: replay.matchId,
      replayId: replay.matchId,
      sideA: replay.sideA,
      sideB: replay.sideB,
      opponent: opponentName,
      opponentType: isSparring ? 'SPARRING' : opponentType,
      winner: winner === 'A' ? 'USER' : winner === 'B' ? 'OPPONENT' : 'DRAW',
      winReason,
      replay,
      replayData: replay,
      createdAt: new Date().toISOString(),
    };
    onOpenReplay?.({ matchInfo, replay });
    onClose();
  };

  const handleRequestClose = () => {
    if (roomState === 'PLAYING' && !winnerRef.current && opponentType === 'HUMAN' && !isSpectator) {
      const confirmed = window.confirm(
        '⚠️ Ván đấu đang diễn ra!\n\nNếu bạn thoát ngay bây giờ, bạn sẽ bị tính là ĐẦU HÀNG (Thua trận).\nNếu đối thủ đang dừng suy nghĩ, hãy đợi đồng hồ đối thủ hết giờ để tự động thắng cuộc.\n\nBạn có chắc chắn muốn thoát phòng không?'
      );
      if (!confirmed) return;
      const opponentSide = mySide === 'A' ? 'B' : 'A';
      if (stompClientRef.current?.connected) {
        try {
          stompClientRef.current.publish({
            destination: `/app/room/${roomCode}/action`,
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({
              type: 'PLAYER_LEFT',
              roomCode: String(roomCode),
              sender: mySideRef.current || mySide || 'A',
              playerToken: playerTokenRef.current,
              reason: 'RESIGN',
            }),
          });
        } catch (e) {
          console.warn('Lỗi gửi STOMP action PLAYER_LEFT:', e);
        }
      }
      handleFinishGame(opponentSide, 'RESIGN');
    }
    onClose();
  };

  const aliveTeamA = pieces.filter((p) => p.team === 'A' && p.alive).length;
  const aliveTeamB = pieces.filter((p) => p.team === 'B' && p.alive).length;
  const effectiveSide = isSpectator ? 'SPECTATOR' : opponentType === 'BOT' ? 'A' : mySide;
  const isMyTurn = !isSpectator && currentTurn === effectiveSide;

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-3 sm:p-6 animate-fadeIn">
      <div className="bg-zinc-850 border border-zinc-700 w-full max-w-5xl rounded-3xl p-5 sm:p-6 shadow-2xl flex flex-col max-h-[95vh] overflow-y-auto">
        {/* Top Header */}
        <div className="flex items-center justify-between border-b border-zinc-750 pb-4 mb-4">
          <div className="flex items-center gap-3">
            <div className={`w-10 h-10 rounded-2xl flex items-center justify-center font-bold text-xl border ${
              isSpectator
                ? 'bg-purple-500/20 text-purple-400 border-purple-500/40'
                : isSparring
                ? 'bg-blue-500/20 text-blue-400 border-blue-500/40'
                : opponentType === 'BOT'
                ? 'bg-amber-500/20 text-amber-400 border-amber-500/40'
                : 'bg-indigo-500/20 text-indigo-400 border-indigo-500/40'
            }`}>
              {isSpectator ? '👁️' : isSparring ? '⚔️' : opponentType === 'BOT' ? '🤖' : '🔑'}
            </div>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-[10px] font-mono uppercase font-bold px-2 py-0.5 rounded border ${
                  isSpectator
                    ? 'text-purple-400 bg-purple-500/10 border-purple-500/30'
                    : 'text-blue-400 bg-blue-500/10 border-blue-500/30'
                }`}>
                  {isSpectator
                    ? 'TRỰC TIẾP KHÁN GIẢ · ĐẤU BOT'
                    : isSparring
                    ? `ĐẤU TẬP PVP · CẤP ${sparringLevel}`
                    : opponentType === 'BOT'
                    ? 'ĐẤU VỚI BOT DỰ PHÒNG'
                    : 'PHÒNG ĐẤU PVP REAL-TIME'}
                </span>
                <span className="text-xs font-mono font-bold text-white bg-zinc-900 px-2 py-0.5 rounded border border-zinc-750">
                  Mã: {roomCode}
                </span>
                {!isSparring && (
                  <span
                    className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded-full border flex items-center gap-1 ${
                      wsConnected
                        ? 'bg-emerald-500/15 text-emerald-300 border-emerald-500/40'
                        : 'bg-amber-500/15 text-amber-300 border-amber-500/40 animate-pulse'
                    }`}
                  >
                    <span className={`w-1.5 h-1.5 rounded-full ${wsConnected ? 'bg-emerald-400' : 'bg-amber-400'}`} />
                    <span>{wsConnected ? 'WebSocket Online' : 'Đang kết nối WebSocket...'}</span>
                  </span>
                )}
                {!isSparring && opponentType === 'HUMAN' && (
                  <span
                    className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded-full border ${
                      opponentReady
                        ? 'bg-blue-500/15 text-blue-300 border-blue-500/40'
                        : 'bg-zinc-800 text-zinc-400 border-zinc-700'
                    }`}
                  >
                    {opponentReady ? '👤 Đối thủ đã sẵn sàng ✓' : '👤 Chờ đối thủ sẵn sàng...'}
                  </span>
                )}
              </div>
              <h3 className="text-lg font-black text-white mt-0.5">
                {isSparring
                  ? `Bạn (Đội Xanh) VS ${targetOpponent.name} (Đội Đỏ)`
                  : opponentType === 'BOT'
                  ? 'Bạn (Đội Xanh) VS Bot [AI] (Đội Đỏ)'
                  : isHost
                  ? 'Bạn là Chủ Phòng · Đội Xanh (Phe A)'
                  : 'Bạn là Khách · Đội Đỏ (Phe B)'}
              </h3>
            </div>
          </div>

          <div className="flex items-center gap-2">
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

            {roomState === 'PLAYING' && (
              <button
                type="button"
                onClick={handleRestart}
                className="px-3 py-1.5 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-xs text-zinc-200 font-bold border border-zinc-700 transition-colors"
              >
                🔄 Đấu Lại
              </button>
            )}
            <button
              type="button"
              onClick={handleRequestClose}
              className="p-1.5 rounded-xl text-zinc-400 hover:text-white hover:bg-zinc-800 text-sm ml-1"
              title="Đóng / Thoát phòng"
            >
              ✕
            </button>
          </div>
        </div>

        {/* State 1: WAITING / SEARCHING for opponent or incomplete game data (Render Guard) */}
        {!isPreMatched && (isSearching || roomState === 'WAITING' || !hasValidGameData) ? (
          <div className="py-10 text-center space-y-6 max-w-lg mx-auto">
            <div className="w-20 h-20 rounded-3xl bg-indigo-500/20 text-indigo-400 border border-indigo-500/40 text-4xl flex items-center justify-center mx-auto animate-pulse">
              ⌛
            </div>
            <div>
              <h4 className="text-2xl font-black text-white">
                {isHost ? 'Đang Tìm Kiếm Người Chơi Trực Tuyến...' : 'Đang Kết Nối Vào Phòng Đấu...'}
              </h4>
              <p className="text-xs text-zinc-400 mt-2 leading-relaxed">
                {isHost
                  ? 'Đang ưu tiên tìm đối thủ thật ghép trận PvP. Bạn cũng có thể mở thêm 1 tab trình duyệt để thử nghiệm hai chiều qua mã phòng.'
                  : 'Đang kết nối WebSocket và đồng bộ hóa bàn cờ với chủ phòng...'}
              </p>
              <div className="flex items-center justify-center gap-3 mt-4 flex-wrap">
                <div
                  className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-mono font-bold border ${
                    wsConnected
                      ? 'bg-emerald-500/15 text-emerald-300 border-emerald-500/40'
                      : 'bg-amber-500/15 text-amber-300 border-amber-500/40 animate-pulse'
                  }`}
                >
                  <span className={`w-2 h-2 rounded-full ${wsConnected ? 'bg-emerald-400' : 'bg-amber-400'}`} />
                  <span>{wsConnected ? 'WebSocket: Đã kết nối' : 'WebSocket: Đang kết nối...'}</span>
                </div>
                <div className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-zinc-900 border border-zinc-800 font-mono text-xs text-zinc-300">
                  <span className="w-2 h-2 rounded-full bg-indigo-400 animate-ping" />
                  <span>Thời gian chờ: <strong className="text-indigo-400 font-black">{waitingSeconds}s</strong></span>
                </div>
              </div>
            </div>

            {/* Quick Bot Fallback Button */}
            <div className="bg-gradient-to-r from-amber-950/40 via-zinc-900 to-amber-950/40 border-2 border-amber-500/40 rounded-3xl p-5 text-left space-y-3 shadow-xl">
              <div className="flex items-center gap-3">
                <span className="text-2xl">🤖</span>
                <div>
                  <h5 className="text-sm font-black text-amber-300">Không muốn chờ đợi đối thủ?</h5>
                  <p className="text-[11px] text-zinc-400 leading-relaxed mt-0.5">
                    Hệ thống cung cấp sẵn <strong>Bot Đối Thủ Dự Phòng</strong> để bạn có thể vào trận ngay lập tức khi đang chạy local hoặc chưa có người trực tuyến.
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={handleStartWithBot}
                className="w-full py-3 rounded-2xl bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-400 hover:to-amber-500 text-zinc-950 font-black text-xs shadow-lg shadow-amber-950/50 transition-all flex items-center justify-center gap-2"
              >
                <span>⚡ Vào Trận Ngay Với Bot Dự Phòng (Không Cần Chờ)</span>
              </button>
            </div>

            {/* Auto Suggestion after 8s */}
            {waitingSeconds >= 8 && (
              <div className="p-3.5 bg-amber-500/10 border border-amber-500/30 rounded-2xl text-amber-300 text-xs flex items-center justify-between gap-3 animate-fadeIn text-left">
                <div>
                  <div className="font-bold flex items-center gap-1.5">
                    <span>💡</span> Gợi ý ghép trận
                  </div>
                  <div className="text-[11px] text-zinc-400 mt-0.5">
                    Chưa tìm thấy người chơi trực tuyến sau {waitingSeconds}s. Bạn có thể bắt đầu với Bot để không gián đoạn trải nghiệm.
                  </div>
                </div>
                <button
                  type="button"
                  onClick={handleStartWithBot}
                  className="px-3.5 py-2 rounded-xl bg-amber-500 hover:bg-amber-400 text-zinc-950 font-black text-xs whitespace-nowrap transition-colors"
                >
                  Đấu Ngay
                </button>
              </div>
            )}

            {/* Room Code Card for Cross-tab test */}
            <div className="bg-zinc-900 p-5 rounded-3xl border border-zinc-800 shadow-inner space-y-3">
              <span className="text-xs text-zinc-500 uppercase tracking-widest block font-bold">HOẶC GỬI MÃ PHÒNG CHO BẠN BÈ</span>
              <span className="text-4xl font-mono font-black text-indigo-400 tracking-widest block">{roomCode}</span>
              <button
                type="button"
                onClick={handleCopyCode}
                className="px-4 py-2 rounded-xl bg-zinc-800 hover:bg-zinc-750 border border-zinc-700 text-white font-bold text-xs shadow-md transition-all flex items-center gap-2 mx-auto"
              >
                <span>{copied ? '✓ Đã Sao Chép Mã!' : '📋 Sao Chép Mã Phòng'}</span>
              </button>
            </div>
          </div>
        ) : roomState === 'OPPONENT_LEFT' ? (
          <div className="py-16 text-center space-y-4 max-w-md mx-auto">
            <div className="text-5xl">{winner ? '🏆' : '🚪'}</div>
            <h4 className="text-xl font-black text-white">
              {winner ? 'Đối Thủ Đã Rời Trận Đấu!' : 'Đối Thủ Đã Rời Phòng'}
            </h4>
            <p className="text-xs text-zinc-300">
              {winner
                ? 'Đối thủ đã thoát phòng hoặc đóng tab giữa trận. Bạn giành Chiến Thắng do đối thủ bỏ cuộc!'
                : 'Trận đấu đã kết thúc do đối thủ thoát hoặc đóng tab.'}
            </p>
            <button
              type="button"
              onClick={onClose}
              className={`px-6 py-2.5 rounded-xl font-bold text-xs shadow-lg transition-all ${
                winner
                  ? 'bg-emerald-600 hover:bg-emerald-500 text-white'
                  : 'bg-zinc-800 hover:bg-zinc-750 text-white'
              }`}
            >
              {winner ? 'Nhận Chiến Thắng & Quay Về Sảnh' : 'Quay Về Sảnh'}
            </button>
          </div>
        ) : (
          /* State 2: PLAYING (Active 9x9 board) */
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
            {/* Left Column (Board): 7 cols */}
            <div className="board-container lg:col-span-7 flex flex-col items-center relative flex-shrink-0 w-full">
              {/* Turn & Role Banner with Flip View Button - Locked fixed height to prevent layout shift */}
              <div className="w-full max-w-[540px] h-12 min-h-[48px] max-h-[48px] mb-3 flex items-center justify-between px-4 rounded-2xl bg-zinc-900 border border-zinc-750 box-border overflow-hidden select-none flex-shrink-0">
                <div className="flex items-center gap-2.5 min-w-0 flex-1 mr-2 overflow-hidden">
                  <span
                    className={`w-3.5 h-3.5 rounded-full flex-shrink-0 ${
                      isSpectator
                        ? 'bg-rose-500 animate-pulse'
                        : isMyTurn
                        ? 'bg-emerald-500 animate-ping'
                        : 'bg-zinc-600'
                    }`}
                  />
                  <div
                    className="text-xs min-w-0 flex items-center gap-1.5 leading-none whitespace-nowrap overflow-hidden"
                    title={
                      isSpectator
                        ? `Trực tiếp khán giả: Đang theo dõi lượt đi của ${currentTurn === 'A' ? 'Đội Xanh' : 'Đội Đỏ'}`
                        : isMyTurn
                        ? 'Trạng thái: 👉 Đến Lượt Bạn Đi!'
                        : isSparring
                        ? `Trạng thái: 🤖 ${targetOpponent.name} đang suy nghĩ...`
                        : opponentType === 'BOT'
                        ? 'Trạng thái: 🤖 Bot đang tính toán nước đi...'
                        : 'Trạng thái: ⏳ Đang chờ đối thủ...'
                    }
                  >
                    <span className="text-zinc-400 flex-shrink-0 font-medium">Trạng thái:</span>
                    <span className="truncate font-semibold inline-block min-w-0">
                      {isSpectator ? (
                        <span className="font-bold text-rose-300">
                          🔴 Trực Tiếp Khán Giả · Lượt {currentTurn === 'A' ? 'Đội Xanh' : 'Đội Đỏ'} đi
                        </span>
                      ) : isMyTurn ? (
                        <span className="font-black text-emerald-400">👉 Đến Lượt Bạn Đi!</span>
                      ) : (
                        <span className="font-bold text-zinc-300">
                          {isSparring
                            ? `🤖 ${targetOpponent.name} đang suy nghĩ...`
                            : opponentType === 'BOT'
                            ? '🤖 Bot đang tính toán nước đi...'
                            : '⏳ Đang chờ đối thủ...'}
                        </span>
                      )}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2 flex-shrink-0">
                  {/* Nút Đổi Góc Nhìn 180 độ */}
                  <button
                    type="button"
                    onClick={() => setIsFlipped((prev) => !prev)}
                    className={`px-2.5 py-1 rounded-xl text-xs font-bold transition-all border flex items-center gap-1.5 flex-shrink-0 ${
                      isFlipped
                        ? 'bg-amber-500/20 text-amber-300 border-amber-500/40 shadow-sm'
                        : 'bg-zinc-800 hover:bg-zinc-750 text-zinc-300 border-zinc-700'
                    }`}
                    title="Xoay bàn cờ 180 độ theo góc nhìn người chơi"
                  >
                    <span>🔄</span>
                    <span className="font-mono text-[11px] whitespace-nowrap">
                      {isFlipped ? 'Đỏ ở dưới' : 'Xanh ở dưới'}
                    </span>
                  </button>

                  {/* Active Player Fischer Clock */}
                  <div
                    className={`font-mono text-xs font-black px-2.5 py-1 rounded-xl flex items-center gap-1.5 flex-shrink-0 tabular-nums ${
                      (currentTurn === 'A' ? timeLeftA : timeLeftB) <= 20000
                        ? 'bg-rose-500/20 text-rose-400 border border-rose-500/40 animate-pulse'
                        : 'bg-zinc-800 text-amber-400 border border-zinc-700'
                    }`}
                  >
                    <span>⏱</span>
                    <span>{formatChessClock(currentTurn === 'A' ? timeLeftA : timeLeftB)}</span>
                  </div>
                </div>
              </div>

              {/* 9x9 Interactive Board */}
              <div className="w-full max-w-[540px] relative flex flex-col items-center flex-shrink-0">
                <InteractiveBoard
                  pieces={pieces}
                  currentTurn={currentTurn}
                  selectedPiece={selectedPiece}
                  validMoves={validMoves}
                  onSelectPiece={handleSelectPiece}
                  onMakeMove={handleMakeMove}
                  disabled={Boolean(winner) || !isMyTurn}
                  lastMove={lastMove}
                  playerSide={effectiveSide}
                  isFlipped={isFlipped}
                />
              </div>
            </div>

            {/* Right Column: Game State & History: 5 cols */}
            <div className="lg:col-span-5 flex flex-col gap-4">
              {/* Dual Fischer Clocks Display (Chess.com style) */}
              <div className="grid grid-cols-2 gap-3">
                {/* Blue Team Clock */}
                <div
                  className={`p-3.5 rounded-2xl border transition-all ${
                    currentTurn === 'A'
                      ? 'bg-blue-950/50 border-blue-500 shadow-lg ring-1 ring-blue-500/50'
                      : 'bg-zinc-900/80 border-zinc-800'
                  }`}
                >
                  <div className="flex items-center justify-between text-xs font-bold">
                    <span className="text-blue-400">Đội Xanh (A) {!isSpectator && effectiveSide === 'A' ? '· Bạn' : ''}</span>
                    <span className="font-mono text-zinc-400 text-[11px]">{aliveTeamA}/9 quân</span>
                  </div>
                  <div
                    className={`text-2xl font-black font-mono mt-1 tabular-nums ${
                      timeLeftA <= 20000
                        ? 'text-rose-400 animate-pulse'
                        : currentTurn === 'A'
                        ? 'text-white'
                        : 'text-zinc-400'
                    }`}
                  >
                    {formatChessClock(timeLeftA)}
                  </div>
                  <p className="text-[10px] text-zinc-500 mt-0.5">+{incrementSeconds}s sau mỗi nước đi</p>
                </div>

                {/* Red Team Clock */}
                <div
                  className={`p-3.5 rounded-2xl border transition-all ${
                    currentTurn === 'B'
                      ? 'bg-rose-950/50 border-rose-500 shadow-lg ring-1 ring-rose-500/50'
                      : 'bg-zinc-900/80 border-zinc-800'
                  }`}
                >
                  <div className="flex items-center justify-between text-xs font-bold">
                    <span className="text-rose-400 flex items-center gap-1 truncate">
                      Đội Đỏ (B) {isSparring ? `· ${targetOpponent.name}` : opponentType === 'BOT' ? '· Bot [AI]' : (!isSpectator && effectiveSide === 'B') ? '· Bạn' : ''}
                    </span>
                    <span className="font-mono text-zinc-400 text-[11px]">{aliveTeamB}/9 quân</span>
                  </div>
                  <div
                    className={`text-2xl font-black font-mono mt-1 tabular-nums ${
                      timeLeftB <= 20000
                        ? 'text-rose-400 animate-pulse'
                        : currentTurn === 'B'
                        ? 'text-white'
                        : 'text-zinc-400'
                    }`}
                  >
                    {formatChessClock(timeLeftB)}
                  </div>
                  <p className="text-[10px] text-zinc-500 mt-0.5">+{incrementSeconds}s sau mỗi nước đi</p>
                </div>
              </div>

              {/* My Role Indicator */}
              {isSpectator ? (
                <div className="p-4 rounded-2xl border bg-purple-950/40 border-purple-500/50 text-purple-200">
                  <div className="flex items-center justify-between text-xs font-bold">
                    <span className="flex items-center gap-1.5">
                      <span className="w-2 h-2 rounded-full bg-rose-500 animate-pulse" />
                      <span>🔴 Chế Độ Khán Giả (Spectator)</span>
                    </span>
                    <span className="font-mono text-zinc-300">
                      {aliveTeamA} vs {aliveTeamB} quân
                    </span>
                  </div>
                  <p className="text-[11px] text-zinc-300 mt-1.5 leading-relaxed">
                    Bạn đang theo dõi trực tiếp trận đấu. Bàn cờ và đồng hồ Fischer đếm ngược liên tục thời gian thực theo lượt của từng bot.
                  </p>
                </div>
              ) : (
                <div
                  className={`p-4 rounded-2xl border ${
                    effectiveSide === 'A'
                      ? 'bg-blue-950/40 border-blue-500/50 text-blue-200'
                      : 'bg-rose-950/40 border-rose-500/50 text-rose-200'
                  }`}
                >
                  <div className="flex items-center justify-between text-xs font-bold">
                    <span>Phe Của Bạn: {effectiveSide === 'A' ? 'Đội Xanh (Phe A)' : 'Đội Đỏ (Phe B)'}</span>
                    <span className="font-mono">
                      {effectiveSide === 'A' ? `${aliveTeamA}/9 quân` : `${aliveTeamB}/9 quân`}
                    </span>
                  </div>
                  {effectiveSide === 'A' ? (
                    <p className="text-[11px] text-zinc-300 mt-1.5 leading-relaxed">
                      Mục tiêu: Đưa quân tới ô đích{' '}
                      <strong className="text-white font-mono bg-blue-900/40 px-1 py-0.5 rounded border border-blue-500/30">
                        {isFlipped ? 'I1 (Góc dưới bên trái)' : 'I1 (Góc trên bên phải)'}
                      </strong>{' '}
                      hoặc ăn sạch quân đối phương.
                    </p>
                  ) : (
                    <p className="text-[11px] text-zinc-300 mt-1.5 leading-relaxed">
                      Mục tiêu: Đưa quân tới ô đích{' '}
                      <strong className="text-white font-mono bg-rose-900/40 px-1 py-0.5 rounded border border-rose-500/30">
                        {isFlipped ? 'A9 (Góc trên bên phải)' : 'A9 (Góc dưới bên trái)'}
                      </strong>{' '}
                      hoặc ăn sạch quân đối phương.
                    </p>
                  )}
                </div>
              )}

              {/* Opponent Info with Clear AI Badge & Notice */}
              <div className="bg-zinc-900/80 p-3.5 rounded-2xl border border-zinc-800 text-xs flex items-center justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-zinc-400">Đối Thủ:</span>
                    {isSparring ? (
                      <div className="flex items-center gap-1.5">
                        <span className="font-bold text-blue-300">{targetOpponent.name}</span>
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-blue-500/20 border border-blue-500/40 text-blue-300 font-mono font-bold">
                          [SPARRING #{sparringLevel}]
                        </span>
                      </div>
                    ) : opponentType === 'BOT' ? (
                      <div className="flex items-center gap-1.5">
                        <span className="font-bold text-amber-300">Bot</span>
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/20 border border-amber-500/40 text-amber-300 font-mono font-bold">
                          [BOT] AI DỰ PHÒNG
                        </span>
                      </div>
                    ) : (
                      <span className="font-bold text-zinc-200">
                        {mySide === 'A' ? 'Đội Đỏ (Phe B - Khách)' : 'Đội Xanh (Phe A - Chủ Phòng)'}
                      </span>
                    )}
                  </div>
                  {isSparring && (
                    <p className="text-[10px] text-zinc-400 mt-1 leading-tight font-mono">
                      ⚙️ {targetOpponent.algorithm || targetOpponent.description}
                    </p>
                  )}
                  {!isSparring && opponentType === 'BOT' && (
                    <p className="text-[10px] text-zinc-500 mt-1 leading-tight">
                      Đối thủ AI — được sử dụng khi chưa tìm thấy người chơi trực tuyến
                    </p>
                  )}
                </div>
                <span className="font-mono font-bold text-zinc-300">
                  {effectiveSide === 'A' ? `${aliveTeamB}/9 quân` : `${aliveTeamA}/9 quân`}
                </span>
              </div>

              {/* Move History */}
              <div className="bg-zinc-900 rounded-2xl border border-zinc-750 p-4 flex flex-col flex-1 min-h-[220px] max-h-[300px]">
                <div className="flex items-center justify-between pb-2 border-b border-zinc-800 mb-2">
                  <span className="text-xs font-bold text-zinc-300 flex items-center gap-1.5">
                    <span>📜</span> Lịch Sử Trận Đấu ({moveHistory.length})
                  </span>
                  <span className="text-[10px] font-mono text-zinc-500">
                    {isSparring ? `PvP vs ${targetOpponent.name}` : opponentType === 'BOT' ? 'Đấu với Bot' : 'Realtime sync'}
                  </span>
                </div>

                <div className="flex-1 overflow-y-auto space-y-1.5 pr-1 font-mono text-xs">
                  {moveHistory.length === 0 ? (
                    <p className="text-center text-zinc-600 text-xs py-8">
                      {isMyTurn ? 'Đến lượt bạn, nhấp vào quân cờ để đi!' : 'Đang đợi đối thủ đi nước đầu tiên...'}
                    </p>
                  ) : (
                    moveHistory.map((item) => (
                      <div
                        key={item.turnNumber}
                        className="flex items-center justify-between p-2 rounded-xl bg-zinc-950/60 border border-zinc-800/80"
                      >
                        <div className="flex items-center gap-2">
                          <span className="text-zinc-500 text-[10px]">#{item.turnNumber}</span>
                          <span className={item.team === 'A' ? 'text-blue-400 font-bold' : 'text-rose-400 font-bold'}>
                            {item.team === 'A' ? 'Xanh' : 'Đỏ'}
                          </span>
                          <span>{PIECE_EMOJIS[item.pieceType]}</span>
                          <span className="text-zinc-300">
                            {item.from} → {item.to}
                          </span>
                        </div>

                        {item.capturedType && (
                          <span className="text-[10px] bg-rose-500/20 text-rose-400 border border-rose-500/30 px-1.5 py-0.5 rounded font-bold">
                            Ăn {PIECE_EMOJIS[item.capturedType]}
                          </span>
                        )}
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Win Banner Modal Overlay */}
        {winner && showWinModal && (
          <div className="fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-fadeIn">
            <div className="bg-zinc-850 border border-zinc-700 w-full max-w-md rounded-3xl p-6 text-center space-y-4 shadow-2xl">
              <div className="text-5xl">{isSpectator ? '🏆' : winner === effectiveSide ? '🎉' : '💔'}</div>
              <h3 className="text-2xl font-black text-white">
                {winner === 'DRAW'
                  ? 'Trận Đấu Hòa!'
                  : isSpectator
                  ? winner === 'A'
                    ? 'ĐỘI XANH CHIẾN THẮNG!'
                    : 'ĐỘI ĐỎ CHIẾN THẮNG!'
                  : winner === effectiveSide
                  ? 'BẠN ĐÃ CHIẾN THẮNG!'
                  : 'BẠN ĐÃ THẤT BẠI!'}
              </h3>
              <p className="text-xs text-zinc-300 font-mono bg-zinc-900 p-2.5 rounded-xl border border-zinc-800">
                {winReason === 'TIMEOUT'
                  ? (isSpectator
                      ? `Hết thời gian thi đấu (Timeout 00:00.0) cho ${winner === 'A' ? 'Đội Đỏ' : 'Đội Xanh'}!`
                      : 'Đối thủ hết thời gian thi đấu (Timeout 00:00.0)!')
                  : winReason === 'TARGET_REACHED'
                  ? 'Đã thâm nhập thành công vào Ô Đích đối phương!'
                  : winReason === 'RESIGN' || winReason === 'FORFEIT'
                  ? 'Đối thủ đã xin đầu hàng / hủy trận!'
                  : winReason === 'OPPONENT_LEFT'
                  ? 'Đối thủ đã rời khỏi phòng đấu / hủy trận!'
                  : 'Đã tiêu diệt toàn bộ quân cờ của đối phương!'}
              </p>

              {/* Opponent & ELO Notice */}
              {isSparring ? (
                <div className="p-3 bg-blue-500/10 border border-blue-500/30 rounded-2xl text-xs space-y-1 text-left">
                  <div className="flex items-center gap-2 font-bold text-blue-400">
                    <span className="px-1.5 py-0.5 rounded bg-blue-500/20 border border-blue-500/40 text-[10px] font-mono">
                      RANKED PVP
                    </span>
                    <span>Đấu Tập Với {targetOpponent.name} ({targetOpponent.elo} ELO)</span>
                  </div>
                  <p className="text-zinc-300 text-[11px] leading-relaxed">
                    Trận đấu PvP Xếp Hạng. Điểm xếp hạng <strong>PvP ELO</strong> của bạn đã được cập nhật dựa trên kết quả trận đấu này.
                  </p>
                </div>
              ) : opponentType === 'BOT' ? (
                <div className="p-3 bg-amber-500/10 border border-amber-500/30 rounded-2xl text-xs space-y-1 text-left">
                  <div className="flex items-center gap-2 font-bold text-amber-400">
                    <span className="px-1.5 py-0.5 rounded bg-amber-500/20 border border-amber-500/40 text-[10px] font-mono">
                      [BOT] AI DỰ PHÒNG
                    </span>
                    <span>Trận Đấu Tập Luyện</span>
                  </div>
                  <p className="text-zinc-400 text-[11px] leading-relaxed">
                    Trận đấu với <strong>Bot</strong> (Đối thủ AI dự phòng khi chưa tìm thấy người chơi trực tuyến). 
                    Điểm xếp hạng <strong>Player ELO không thay đổi</strong> (giữ nguyên).
                  </p>
                </div>
              ) : (
                serverEloResult && serverEloResult.isRanked ? (
                  <div className={`p-3 rounded-2xl border text-left flex items-center justify-between ${
                    serverEloResult.isWin
                      ? 'bg-emerald-950/40 border-emerald-500/50 text-emerald-100'
                      : serverEloResult.isDraw
                      ? 'bg-amber-950/40 border-amber-500/50 text-amber-100'
                      : 'bg-rose-950/40 border-rose-500/50 text-rose-100'
                  }`}>
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className={`px-2 py-0.5 rounded-full text-xs font-black font-mono tracking-wide ${
                          serverEloResult.isWin
                            ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/40'
                            : serverEloResult.isDraw
                            ? 'bg-amber-500/20 text-amber-400 border border-amber-500/40'
                            : 'bg-rose-500/20 text-rose-400 border border-rose-500/40'
                        }`}>
                          {serverEloResult.delta >= 0 ? `+${serverEloResult.delta}` : serverEloResult.delta} ELO
                        </span>
                        <span className="font-mono text-xs font-bold text-white">
                          {serverEloResult.oldElo} <span className="text-zinc-400">➜</span> {serverEloResult.newElo}
                        </span>
                      </div>
                      <p className="text-[11px] text-zinc-400 font-medium">
                        Đối đầu với: <strong className="text-zinc-200">{serverEloResult.opponentName}</strong>
                      </p>
                    </div>

                    <div className="text-3xl">
                      {serverEloResult.isWin ? '📈' : serverEloResult.isDraw ? '🤝' : '📉'}
                    </div>
                  </div>
                ) : (
                  <div className="p-3 bg-indigo-950/40 border border-indigo-500/30 rounded-2xl text-xs text-left">
                    <div className="flex items-center gap-2 font-bold text-indigo-400">
                      <span className="px-1.5 py-0.5 rounded bg-indigo-500/20 border border-indigo-500/30 text-[10px] font-mono">
                        RANKED PVP
                      </span>
                      <span>Trận Đấu Xếp Hạng Người Chơi</span>
                    </div>
                    <p className="text-zinc-300 text-[11px] mt-1">
                      Điểm PvP ELO đã được cập nhật sau trận đấu này.
                    </p>
                  </div>
                )
              )}

              {/* Action Buttons */}
              <div className="space-y-2 pt-2">
                <button
                  type="button"
                  onClick={handleWatchReplay}
                  className="w-full py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-bold text-xs shadow-md transition-all flex items-center justify-center gap-1.5"
                >
                  <span>🎬</span>
                  <span>Xem Lại Replay Trận Đấu</span>
                </button>

                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={handleRestart}
                    className="flex-1 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs shadow-md transition-all"
                  >
                    Chơi Lại Ván Mới
                  </button>
                  <button
                    type="button"
                    onClick={onClose}
                    className="flex-1 py-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 font-bold text-xs border border-zinc-700 transition-colors"
                  >
                    Thoát Về Sảnh
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
