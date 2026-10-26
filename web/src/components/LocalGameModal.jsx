import React, { useState, useEffect, useRef } from 'react';
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
  playVictorySound,
  playDrawSound,
  isAudioEnabled,
  toggleAudio,
} from '../utils/soundEffects.js';

const TIME_CONTROLS = [
  { id: '1+1', label: 'Siêu Chớp 1+1', name: 'Siêu Chớp 1+1', initial: 60, increment: 1 },
  { id: '3+2', label: 'Chớp 3+2', name: 'Chớp 3+2', initial: 180, increment: 2 },
  { id: '5+3', label: 'Cờ Nhanh 5+3', name: 'Cờ Nhanh 5+3', initial: 300, increment: 3 },
];

export default function LocalGameModal({
  onClose,
  initialTimeControl = null,
  timeControl = null,
  opponentName = null,
  onMatchFinished = null,
  onOpenReplay = null,
}) {
  const chosenTc = timeControl || initialTimeControl;
  const [timeControlKey, setTimeControlKey] = useState(() => {
    if (chosenTc?.id === '5+3') return 'RAPID';
    if (chosenTc?.id === '1+1') return 'BULLET';
    return 'BLITZ';
  });

  const activeTc =
    TIME_CONTROLS.find((tc) => {
      if (chosenTc?.id === tc.id) return true;
      if (timeControlKey === 'RAPID' && tc.id === '5+3') return true;
      if (timeControlKey === 'BULLET' && tc.id === '1+1') return true;
      if (timeControlKey === 'BLITZ' && tc.id === '3+2') return true;
      return false;
    }) || TIME_CONTROLS[1];

  const initialSeconds = chosenTc?.initialSeconds ?? (chosenTc?.baseMs ? Math.round(chosenTc.baseMs / 1000) : (activeTc.initial || 180));
  const incrementSeconds = chosenTc?.incrementSeconds ?? (chosenTc?.incMs ? Math.round(chosenTc.incMs / 1000) : (activeTc.increment || 2));
  const [currentBaseMs, setCurrentBaseMs] = useState(initialSeconds * 1000);
  const [currentIncMs, setCurrentIncMs] = useState(incrementSeconds * 1000);
  const [audioEnabled, setAudioEnabled] = useState(() => isAudioEnabled());

  useEffect(() => {
    const handleToggle = (e) => setAudioEnabled(e.detail.enabled);
    window.addEventListener('ottv2_audio_toggle', handleToggle);
    return () => window.removeEventListener('ottv2_audio_toggle', handleToggle);
  }, []);

  const [pieces, setPieces] = useState(() =>
    INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 }))
  );
  const [currentTurn, setCurrentTurn] = useState('A'); // 'A' (Blue) starts first
  const [isFlipped, setIsFlipped] = useState(false);
  const [selectedPiece, setSelectedPiece] = useState(null);
  const [validMoves, setValidMoves] = useState([]);
  const [lastMove, setLastMove] = useState(null);
  const [moveHistory, setMoveHistory] = useState([]);

  // Fischer Chess Clock: timeLeft in milliseconds
  const [timeLeftA, setTimeLeftA] = useState(currentBaseMs);
  const [timeLeftB, setTimeLeftB] = useState(currentBaseMs);
  const [winner, setWinner] = useState(null); // 'A' | 'B' | 'DRAW' | null
  const [winReason, setWinReason] = useState(null);

  const recordedTurnsRef = useRef([]);
  const matchIdRef = useRef(`local-${Date.now()}`);
  const notifiedRef = useRef(false);

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
      matchIdRef.current = `local-${Date.now()}`;
    }
    const replayObj = {
      version: 1,
      matchId: matchIdRef.current,
      sideA: { botName: 'Người chơi 1 (Đội Xanh)' },
      sideB: { botName: opponentName || 'Người chơi 2 (Đội Đỏ)' },
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
    } catch {}

    return replayObj;
  };

  const handleFinishLocalGame = (finalWinner, finalReason) => {
    setWinner(finalWinner);
    setWinReason(finalReason);

    if (notifiedRef.current) return;
    notifiedRef.current = true;

    const replay = buildReplayData(finalWinner, finalReason);
    if (onMatchFinished) {
      onMatchFinished({
        matchId: replay.matchId,
        replayId: replay.matchId,
        opponentName: opponentName || 'Người chơi 2 (Cùng máy)',
        winner: finalWinner,
        winReason: finalReason,
        replay,
        replayData: replay,
      });
    }
  };

  // Countdown timer for active player (runs every 100ms)
  useEffect(() => {
    if (winner) return undefined;

    const timer = setInterval(() => {
      if (currentTurn === 'A') {
        setTimeLeftA((prev) => {
          if (prev <= 100) {
            handleFinishLocalGame('B', 'TIMEOUT');
            return 0;
          }
          return prev - 100;
        });
      } else {
        setTimeLeftB((prev) => {
          if (prev <= 100) {
            handleFinishLocalGame('A', 'TIMEOUT');
            return 0;
          }
          return prev - 100;
        });
      }
    }, 100);

    return () => clearInterval(timer);
  }, [currentTurn, winner]);

  // Handle piece selection
  const handleSelectPiece = (piece) => {
    if (winner) return;
    if (!piece) {
      setSelectedPiece(null);
      setValidMoves([]);
      return;
    }
    if (piece.team !== currentTurn) return;

    setSelectedPiece(piece);
    const moves = getValidMoves(piece, pieces);
    setValidMoves(moves);
  };

  // Handle making a move
  const handleMakeMove = (move) => {
    if (!selectedPiece || winner) return;

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

    const historyItem = {
      turnNumber: moveHistory.length + 1,
      team: currentTurn,
      pieceType: attackerType,
      from: posToLabel(fromPos[0], fromPos[1]),
      to: posToLabel(toPos[0], toPos[1]),
      capturedType,
    };

    recordTurn(currentTurn, selectedPiece.id, toPos, move.capturePieceId, fromPos, attackerType, capturedType);

    setPieces(nextPieces);
    setLastMove({ from: fromPos, to: toPos });
    setMoveHistory((prev) => [historyItem, ...prev]);
    setSelectedPiece(null);
    setValidMoves([]);

    // Check Win Condition
    const winResult = checkWinner(nextPieces);
    if (winResult.winner) {
      handleFinishLocalGame(winResult.winner, winResult.reason);
      if (winResult.winner === 'DRAW') {
        playDrawSound();
      } else {
        playVictorySound();
      }
    } else {
      // Add Fischer Increment and switch turn
      if (currentTurn === 'A') {
        setTimeLeftA((prev) => prev + currentIncMs);
        setCurrentTurn('B');
      } else {
        setTimeLeftB((prev) => prev + currentIncMs);
        setCurrentTurn('A');
      }
    }
  };

  // Reset to new match
  const handleRestart = () => {
    setPieces(INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 })));
    setCurrentTurn('A');
    setSelectedPiece(null);
    setValidMoves([]);
    setLastMove(null);
    setMoveHistory([]);
    setTimeLeftA(currentBaseMs);
    setTimeLeftB(currentBaseMs);
    setWinner(null);
    setWinReason(null);
    recordedTurnsRef.current = [];
    notifiedRef.current = false;
  };

  const handleChangeTimeControl = (key) => {
    setTimeControlKey(key);
    const tc = CHESS_TIME_CONTROLS[key];
    setCurrentBaseMs(tc.baseMs);
    setCurrentIncMs(tc.incMs);
    setTimeLeftA(tc.baseMs);
    setTimeLeftB(tc.baseMs);
    setPieces(INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 })));
    setCurrentTurn('A');
    setSelectedPiece(null);
    setValidMoves([]);
    setLastMove(null);
    setMoveHistory([]);
    setWinner(null);
    setWinReason(null);
  };

  const aliveTeamA = pieces.filter((p) => p.team === 'A' && p.alive).length;
  const aliveTeamB = pieces.filter((p) => p.team === 'B' && p.alive).length;

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-3 sm:p-6 animate-fadeIn">
      <div className="bg-zinc-850 border border-zinc-700 w-full max-w-5xl rounded-3xl p-5 sm:p-6 shadow-2xl flex flex-col max-h-[95vh] overflow-y-auto">
        {/* Top Header */}
        <div className="flex flex-wrap items-center justify-between border-b border-zinc-750 pb-4 mb-4 gap-3">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-2xl bg-blue-500/20 text-blue-400 border border-blue-500/40 text-xl flex items-center justify-center font-bold">
              👥
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="text-[10px] font-mono uppercase font-bold text-blue-400 bg-blue-500/10 px-2 py-0.5 rounded border border-blue-500/30">
                  FISCHER CHESS CLOCK
                </span>
                <span className="text-xs font-mono font-bold text-white bg-zinc-900 px-2 py-0.5 rounded border border-zinc-750">
                  {activeTc.name || activeTc.label}
                </span>
              </div>
              <h3 className="text-lg font-black text-white mt-0.5">
                {opponentName ? `Đấu PvP Trực Tiếp vs ${opponentName}` : 'Đấu 2 Người Trên Cùng Máy (Local)'}
              </h3>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {/* Time Control Quick Switcher */}
            <div className="hidden sm:flex bg-zinc-950 p-1 rounded-xl border border-zinc-800 gap-1">
              {Object.keys(CHESS_TIME_CONTROLS).map((k) => (
                <button
                  key={k}
                  type="button"
                  onClick={() => handleChangeTimeControl(k)}
                  className={`px-2 py-1 rounded-lg text-[11px] font-mono font-bold transition-all ${
                    timeControlKey === k
                      ? 'bg-blue-600 text-white shadow'
                      : 'bg-transparent text-zinc-400 hover:text-white'
                  }`}
                >
                  {CHESS_TIME_CONTROLS[k].label}
                </button>
              ))}
            </div>

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

            <button
              type="button"
              onClick={handleRestart}
              className="px-3 py-1.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-xs text-zinc-200 font-bold border border-zinc-700 transition-colors"
            >
              🔄 Ván Mới
            </button>
            <button
              type="button"
              onClick={onClose}
              className="p-1.5 rounded-xl text-zinc-400 hover:text-white hover:bg-zinc-800 text-sm ml-1"
            >
              ✕
            </button>
          </div>
        </div>

        {/* Main Area: Split into Board (Left) & Control/Info (Right) */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          {/* Left Column (Board): 7 cols */}
          <div className="board-container lg:col-span-7 flex flex-col items-center relative flex-shrink-0 w-full">
            {/* Clocks Bar: Team B (Red) on top, Team A (Blue) on bottom - Locked fixed height to prevent layout shift */}
            <div className="w-full max-w-[540px] h-12 min-h-[48px] max-h-[48px] mb-3 flex items-center justify-between px-4 rounded-2xl bg-zinc-900 border border-zinc-750 box-border overflow-hidden select-none flex-shrink-0">
              <div className="flex items-center gap-2.5 min-w-0 flex-1 mr-2 overflow-hidden">
                <span
                  className={`w-3.5 h-3.5 rounded-full flex-shrink-0 ${
                    currentTurn === 'A' ? 'bg-blue-500 animate-ping' : 'bg-rose-500 animate-ping'
                  }`}
                />
                <span className="text-xs font-bold text-zinc-300 truncate leading-none">
                  <span className="text-zinc-400">Lượt của: </span>
                  <strong className={currentTurn === 'A' ? 'text-blue-400 font-black' : 'text-rose-400 font-black'}>
                    {currentTurn === 'A' ? 'Đội Xanh (Phe A)' : 'Đội Đỏ (Phe B)'}
                  </strong>
                </span>
              </div>

              <div className="flex items-center gap-2 flex-shrink-0">
                <button
                  type="button"
                  onClick={() => setIsFlipped((prev) => !prev)}
                  className={`px-2.5 py-1 rounded-xl text-xs font-bold transition-all border flex items-center gap-1.5 flex-shrink-0 ${
                    isFlipped
                      ? 'bg-amber-500/20 text-amber-300 border-amber-500/40'
                      : 'bg-zinc-800 hover:bg-zinc-750 text-zinc-300 border-zinc-700'
                  }`}
                  title="Xoay bàn cờ 180 độ"
                >
                  <span>🔄</span>
                  <span className="font-mono text-[11px] whitespace-nowrap">{isFlipped ? 'Đỏ ở dưới' : 'Xanh ở dưới'}</span>
                </button>
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
                disabled={Boolean(winner)}
                lastMove={lastMove}
                playerSide="BOTH"
                isFlipped={isFlipped}
              />
            </div>
          </div>

          {/* Right Column: Clocks & Move History: 5 cols */}
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
                  <span className="text-blue-400">Đội Xanh (A)</span>
                  <span className="font-mono text-zinc-400 text-[11px]">{aliveTeamA}/9 quân</span>
                </div>
                <div
                  className={`text-2xl font-black font-mono mt-1 ${
                    timeLeftA <= 20000
                      ? 'text-rose-400 animate-pulse'
                      : currentTurn === 'A'
                      ? 'text-white'
                      : 'text-zinc-400'
                  }`}
                >
                  {formatChessClock(timeLeftA)}
                </div>
                <p className="text-[10px] text-zinc-500 mt-0.5">+{currentIncMs / 1000}s sau mỗi nước đi</p>
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
                  <span className="text-rose-400">Đội Đỏ (B)</span>
                  <span className="font-mono text-zinc-400 text-[11px]">{aliveTeamB}/9 quân</span>
                </div>
                <div
                  className={`text-2xl font-black font-mono mt-1 ${
                    timeLeftB <= 20000
                      ? 'text-rose-400 animate-pulse'
                      : currentTurn === 'B'
                      ? 'text-white'
                      : 'text-zinc-400'
                  }`}
                >
                  {formatChessClock(timeLeftB)}
                </div>
                <p className="text-[10px] text-zinc-500 mt-0.5">+{currentIncMs / 1000}s sau mỗi nước đi</p>
              </div>
            </div>

            {/* Quick Rules Hint */}
            <div className="bg-zinc-900/70 p-3 rounded-2xl border border-zinc-800 text-[11px] text-zinc-400 space-y-1">
              <p className="font-bold text-zinc-300 flex items-center gap-1.5">
                <span>💡</span> Luật Ăn Quân Oẳn Tù Tì:
              </p>
              <div className="flex justify-around text-xs pt-0.5 font-mono">
                <span className="text-emerald-400">👊 Búa ăn ✌️ Kéo</span>
                <span className="text-emerald-400">✌️ Kéo ăn ✋ Bao</span>
                <span className="text-emerald-400">✋ Bao ăn 👊 Búa</span>
              </div>
            </div>

            {/* Move History Log */}
            <div className="bg-zinc-900 rounded-2xl border border-zinc-750 p-4 flex flex-col flex-1 min-h-[240px] max-h-[300px]">
              <div className="flex items-center justify-between pb-2 border-b border-zinc-800 mb-2">
                <span className="text-xs font-bold text-zinc-300 flex items-center gap-1.5">
                  <span>📜</span> Lịch Sử Nước Đi ({moveHistory.length})
                </span>
                <span className="text-[10px] font-mono text-zinc-500">Mới nhất ở trên</span>
              </div>

              <div className="flex-1 overflow-y-auto space-y-1.5 pr-1 font-mono text-xs">
                {moveHistory.length === 0 ? (
                  <p className="text-center text-zinc-600 text-xs py-8">Chưa có nước đi nào. Nhấp vào quân cờ để bắt đầu!</p>
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

        {/* Win Banner Modal Overlay */}
        {winner && (
          <div className="fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-fadeIn">
            <div className="bg-zinc-850 border border-zinc-700 w-full max-w-md rounded-3xl p-6 text-center space-y-4 shadow-2xl">
              <div className="text-5xl">🏆</div>
              <h3 className="text-2xl font-black text-white">
                {winner === 'DRAW'
                  ? 'Trận Đấu Hòa!'
                  : winner === 'A'
                  ? 'Đội Xanh Chiến Thắng!'
                  : 'Đội Đỏ Chiến Thắng!'}
              </h3>
              <p className="text-xs text-zinc-300 font-mono bg-zinc-900 p-2.5 rounded-xl border border-zinc-800">
                {winReason === 'TIMEOUT'
                  ? `Đối thủ hết thời gian thi đấu (Timeout 00:00.0)!`
                  : winReason === 'TARGET_REACHED'
                  ? 'Đã thâm nhập thành công vào Ô Đích đối phương!'
                  : 'Đã tiêu diệt toàn bộ quân cờ của đối phương!'}
              </p>
              <div className="space-y-2 pt-2">
                {onOpenReplay && (
                  <button
                    type="button"
                    onClick={() => {
                      const rep = buildReplayData(winner, winReason);
                      onOpenReplay({
                        matchInfo: {
                          id: rep.matchId,
                          replayId: rep.matchId,
                          sideA: rep.sideA,
                          sideB: rep.sideB,
                          opponent: opponentName || 'Người chơi 2 (Cùng máy)',
                          opponentType: 'LOCAL',
                          winner: winner === 'A' ? 'USER' : winner === 'B' ? 'OPPONENT' : 'DRAW',
                          winReason,
                          replay: rep,
                          replayData: rep,
                          createdAt: new Date().toISOString(),
                        },
                        replay: rep,
                      });
                      onClose();
                    }}
                    className="w-full py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-bold text-xs shadow-md transition-all flex items-center justify-center gap-1.5"
                  >
                    <span>🎬</span>
                    <span>Xem Lại Replay Trận Đấu</span>
                  </button>
                )}
                <div className="flex gap-3">
                  <button
                    type="button"
                    onClick={handleRestart}
                    className="flex-1 py-3 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-sm shadow-lg transition-all"
                  >
                    Chơi Lại Ván Mới
                  </button>
                  <button
                    type="button"
                    onClick={onClose}
                    className="flex-1 py-3 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 font-bold text-sm border border-zinc-700 transition-colors"
                  >
                    Thoát Về Menu
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
