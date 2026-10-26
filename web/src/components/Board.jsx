import React from 'react';
import { TARGET_A, TARGET_B, PIECE_EMOJIS, posToLabel, INITIAL_PIECES } from '../utils/gameRules.js';

/**
 * Computes piece states, kills, and last move up to `step`
 */
export function matchFrame(replay, step) {
  if (!replay) {
    return { board: [], pieces: [], lastMove: null, lastTurn: null };
  }

  const initialPieces =
    replay.initialPieces && replay.initialPieces.length > 0
      ? replay.initialPieces
      : INITIAL_PIECES;
  const piecesMap = new Map();

  for (const p of initialPieces) {
    piecesMap.set(p.id, {
      id: p.id,
      team: p.team,
      type: p.type,
      row: p.row !== undefined ? p.row : p.r,
      col: p.col !== undefined ? p.col : p.c,
      alive: true,
      kills: p.kills || 0,
    });
  }

  const getPiece = (id) =>
    piecesMap.get(id) ?? piecesMap.get(Number(id)) ?? piecesMap.get(String(id));

  const turns =
    replay.turns ||
    replay.moves ||
    replay.movesHistory ||
    replay.replayEvents ||
    replay.data?.turns ||
    replay.data?.moves ||
    [];
  const currentStep = Math.max(0, Math.min(step, turns.length));
  let lastMove = null;
  let lastTurn = null;

  for (let i = 0; i < currentStep; i += 1) {
    const turn = turns[i];
    if (!turn) continue;

    lastTurn = turn;
    const pieceId = turn.move?.p || turn.move?.pieceId || turn.pieceId;
    const rawTo = turn.move?.to || turn.to;
    const rawFrom = turn.move?.from || turn.from;

    const to = Array.isArray(rawTo)
      ? rawTo
      : (rawTo && typeof rawTo === 'object' && rawTo.row !== undefined
        ? [rawTo.row, rawTo.col]
        : (rawTo && rawTo.r !== undefined ? [rawTo.r, rawTo.c] : null));

    let from = Array.isArray(rawFrom)
      ? rawFrom
      : (rawFrom && typeof rawFrom === 'object' && rawFrom.row !== undefined
        ? [rawFrom.row, rawFrom.col]
        : (rawFrom && rawFrom.r !== undefined ? [rawFrom.r, rawFrom.c] : null));

    const piece = getPiece(pieceId);
    if (piece && Array.isArray(to)) {
      // Prior position before moving
      const priorFrom = [piece.row, piece.col];
      if (!from || (from[0] === 0 && from[1] === 0 && (piece.row !== 0 || piece.col !== 0))) {
        from = priorFrom;
        if (turn.move) turn.move.from = priorFrom;
        if (turn.from) turn.from = priorFrom;
      }

      lastMove = { from, to, pieceId: piece.id };
      piece.row = to[0];
      piece.col = to[1];
    }

    const events = turn.events || [];
    let capturedHappened = false;
    for (const ev of events) {
      if (ev.type === 'CAPTURED' || ev.type === 'CAPTURE') {
        const victimId = ev.victimId ?? ev.target ?? turn.capturedPieceId;
        const victim = getPiece(victimId);
        if (victim) {
          victim.alive = false;
          capturedHappened = true;
        }
        if (piece) {
          piece.kills = (piece.kills || 0) + 1;
        }
      }
    }

    // Direct capturedPieceId if events did not trigger capture
    const directCapturedId =
      turn.capturedPieceId ??
      turn.capturedPiece?.id ??
      turn.capturedPiece ??
      turn.capturePieceId;
    if (!capturedHappened && directCapturedId !== undefined && directCapturedId !== null) {
      const victim = getPiece(typeof directCapturedId === 'object' ? directCapturedId.id : directCapturedId);
      if (victim) {
        victim.alive = false;
      }
      if (piece) {
        piece.kills = (piece.kills || 0) + 1;
      }
    }
  }

  const alivePieces = [...piecesMap.values()].filter((p) => p.alive);
  return {
    board: replay.initialBoard || Array.from({ length: 9 }, () => Array(9).fill('EMPTY')),
    pieces: alivePieces,
    allPiecesMap: piecesMap,
    lastMove,
    lastTurn,
  };
}

export default function Board({
  replay,
  step = 0,
  inspectedPieceId = null,
  onSelectPiece,
  inspectedValidMoves = [],
  showCoordinates = true,
  showMoveArrow = true,
  isFlipped = false,
}) {
  const { pieces, lastMove } = matchFrame(replay, step);

  const colLabels = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'];
  const rowLabels = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];

  const displayCols = isFlipped ? [8, 7, 6, 5, 4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7, 8];
  const displayRows = isFlipped ? [8, 7, 6, 5, 4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7, 8];

  // Map of alive pieces: key = `${row},${col}`
  const piecePosMap = new Map();
  for (const piece of pieces) {
    piecePosMap.set(`${piece.row},${piece.col}`, piece);
  }

  // Set of valid move coordinates for inspected piece: `${r},${c}`
  const validMoveSet = new Set(
    inspectedValidMoves.map((m) => `${m.row},${m.col}`)
  );

  return (
    <div
      className="w-[min(100%,520px)] mx-auto aspect-square select-none rounded-2xl overflow-hidden border border-zinc-700/80 shadow-2xl bg-zinc-950 flex flex-col p-2 sm:p-3 relative flex-shrink-0"
      aria-label="Bàn đấu 9x9"
    >
      {/* Top Column Labels (A -> I or I -> A when flipped) */}
      {showCoordinates && (
        <div className="grid grid-cols-[26px_repeat(9,1fr)] sm:grid-cols-[32px_repeat(9,1fr)] text-center text-xs font-bold text-zinc-400 pb-1">
          <div></div>
          {displayCols.map((c) => (
            <div key={c} className="flex items-center justify-center font-mono text-[11px] sm:text-xs">
              {colLabels[c]}
            </div>
          ))}
        </div>
      )}

      {/* Grid Container */}
      <div className="flex-1 flex flex-row border border-zinc-800 rounded-xl overflow-hidden relative shadow-inner">
        {/* Left Row Label (1 -> 9 or 9 -> 1 when flipped) */}
        {showCoordinates && (
          <div className="w-[26px] sm:w-[32px] grid grid-rows-9 bg-zinc-900 border-r border-zinc-800">
            {displayRows.map((r) => (
              <div
                key={r}
                className="flex items-center justify-center text-[11px] sm:text-xs font-mono font-bold text-zinc-400"
              >
                {rowLabels[r]}
              </div>
            ))}
          </div>
        )}

        {/* 9x9 Board Area: Grid Layer + Floating Pieces Layer */}
        <div className="flex-1 relative aspect-square">
          {/* 1. Static 9x9 Cells */}
          <div className="w-full h-full grid grid-rows-9 grid-cols-9">
            {displayRows.map((r) =>
              displayCols.map((c) => {
                const isEven = (r + c) % 2 === 0;
                const isTargetA = r === TARGET_A.row && c === TARGET_A.col; // I1 (0, 8)
                const isTargetB = r === TARGET_B.row && c === TARGET_B.col; // A9 (8, 0)
                const piece = piecePosMap.get(`${r},${c}`);
                const isValidInspectMove = validMoveSet.has(`${r},${c}`);

                const isFrom = lastMove?.from && lastMove.from[0] === r && lastMove.from[1] === c;
                const isTo = lastMove?.to && lastMove.to[0] === r && lastMove.to[1] === c;

                // Chess.com style warm yellow highlights - absolutely NO black / dark overlay!
                let highlightStyle = {};
                if (isTo) {
                  highlightStyle = {
                    backgroundColor: 'rgba(250, 204, 21, 0.55)',
                    border: '2px solid rgba(234, 179, 8, 0.8)',
                  };
                } else if (isFrom) {
                  highlightStyle = {
                    backgroundColor: 'rgba(250, 204, 21, 0.35)',
                  };
                }

                return (
                  <div
                    key={`${r}-${c}`}
                    onClick={() => {
                      if (piece) {
                        onSelectPiece?.(piece);
                      }
                    }}
                    style={highlightStyle}
                    className={`relative flex items-center justify-center ${
                      isTargetA
                        ? 'bg-blue-100/80'
                        : isTargetB
                        ? 'bg-pink-100/80'
                        : isEven
                        ? 'bg-[#f8fafc]'
                        : 'bg-[#e2e8f0]'
                    } ${
                      isTargetA && !isTo ? 'border-2 border-dashed border-blue-600' : ''
                    } ${isTargetB && !isTo ? 'border-2 border-dashed border-pink-600' : ''} ${
                      piece ? 'cursor-pointer' : ''
                    }`}
                    title={`${posToLabel(r, c)}${isTargetA ? ' (Đích Xanh I1)' : ''}${
                      isTargetB ? ' (Đích Đỏ A9)' : ''
                    }`}
                  >
                    {/* Subtle target badges */}
                    {isTargetA && !piece && (
                      <span className="absolute text-[8px] font-black text-blue-700 tracking-tighter opacity-80 uppercase font-mono pointer-events-none">
                        I1
                      </span>
                    )}
                    {isTargetB && !piece && (
                      <span className="absolute text-[8px] font-black text-pink-700 tracking-tighter opacity-80 uppercase font-mono pointer-events-none">
                        A9
                      </span>
                    )}

                    {/* Reachable cell highlight for inspected piece */}
                    {isValidInspectMove && (
                      <div className="absolute inset-0 z-20 flex items-center justify-center pointer-events-none">
                        <div className="w-3.5 h-3.5 sm:w-4 sm:h-4 rounded-full bg-emerald-500/80 shadow-md ring-2 ring-emerald-300 animate-pulse" />
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>

          {/* 2. Floating Pieces Layer with Smooth Sliding Animation */}
          <div className="absolute inset-0 pointer-events-none">
            {pieces.map((piece) => {
              const isInspected = piece && piece.id === inspectedPieceId;
              const displayRow = isFlipped ? 8 - piece.row : piece.row;
              const displayCol = isFlipped ? 8 - piece.col : piece.col;

              return (
                <div
                  key={piece.id}
                  style={{
                    position: 'absolute',
                    left: `${(displayCol / 9) * 100}%`,
                    top: `${(displayRow / 9) * 100}%`,
                    width: '11.111%',
                    height: '11.111%',
                    transition:
                      'left 240ms cubic-bezier(0.22, 1, 0.36, 1), top 240ms cubic-bezier(0.22, 1, 0.36, 1)',
                    pointerEvents: 'none',
                  }}
                  className="flex items-center justify-center z-10"
                >
                  <div
                    className={`relative w-4/5 h-4/5 rounded-full flex items-center justify-center shadow-md transition-all duration-150 ${
                      piece.team === 'A'
                        ? 'bg-gradient-to-tr from-blue-700 to-blue-500 shadow-blue-900/60'
                        : 'bg-gradient-to-tr from-rose-700 to-rose-500 shadow-rose-900/60'
                    } ${
                      isInspected
                        ? 'ring-4 ring-amber-400 scale-110 shadow-xl z-30 animate-pulse'
                        : ''
                    }`}
                  >
                    <span className="text-sm sm:text-xl filter drop-shadow select-none pointer-events-none">
                      {PIECE_EMOJIS[piece.type] ?? '❓'}
                    </span>

                    {/* Small badge if piece has kills */}
                    {piece.kills > 0 && (
                      <span className="absolute -top-1 -right-1 bg-amber-400 text-zinc-950 font-black text-[9px] w-4 h-4 rounded-full flex items-center justify-center shadow-sm">
                        {piece.kills}
                      </span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
