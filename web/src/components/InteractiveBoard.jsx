import React from 'react';
import { TARGET_A, TARGET_B, PIECE_EMOJIS, posToLabel } from '../utils/gameRules.js';
import { playMoveSound, playCaptureSound } from '../utils/soundEffects.js';

export default function InteractiveBoard({
  pieces = [],
  currentTurn = 'A',
  selectedPiece = null,
  validMoves = [],
  onSelectPiece,
  onMakeMove,
  disabled = false,
  lastMove = null,
  playerSide = 'BOTH', // 'A' | 'B' | 'BOTH'
  isFlipped = false,
}) {
  const colLetters = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'];

  // View Layer projection: displayRows and displayCols
  const displayRows = isFlipped ? [8, 7, 6, 5, 4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7, 8];
  const displayCols = isFlipped ? [8, 7, 6, 5, 4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7, 8];

  // Map position to alive piece: key = `${row},${col}`
  const pieceMap = new Map();
  for (const piece of pieces) {
    if (piece.alive) {
      pieceMap.set(`${piece.row},${piece.col}`, piece);
    }
  }

  // Map position to valid move: key = `${row},${col}`
  const validMoveMap = new Map();
  for (const move of validMoves) {
    validMoveMap.set(`${move.row},${move.col}`, move);
  }

  const handleCellClick = (r, c) => {
    if (disabled) return;

    const move = validMoveMap.get(`${r},${c}`);
    if (move) {
      if (move.capturePieceId) {
        playCaptureSound();
      } else {
        playMoveSound();
      }
      onMakeMove?.(move);
      return;
    }

    const clickedPiece = pieceMap.get(`${r},${c}`);
    if (clickedPiece) {
      // Check if player has right to select this piece
      const canControl =
        playerSide === 'BOTH'
          ? clickedPiece.team === currentTurn
          : playerSide === clickedPiece.team && currentTurn === clickedPiece.team;

      if (canControl) {
        onSelectPiece?.(clickedPiece);
        return;
      }
    }

    // Clicked outside valid moves and not a selectable friendly piece -> Deselect
    if (selectedPiece) {
      onSelectPiece?.(null);
    }
  };

  return (
    <div className="w-[min(100%,540px)] mx-auto aspect-square select-none rounded-2xl overflow-hidden border border-zinc-700 shadow-2xl bg-zinc-950 flex flex-col p-2 sm:p-3 relative flex-shrink-0">
      {/* Top Column Labels: dynamic based on displayCols */}
      <div className="grid grid-cols-[28px_repeat(9,1fr)] sm:grid-cols-[34px_repeat(9,1fr)] text-center text-xs font-bold text-zinc-400 pb-1">
        <div></div>
        {displayCols.map((c) => (
          <div key={c} className="flex items-center justify-center font-mono">
            {colLetters[c]}
          </div>
        ))}
      </div>

      {/* Board Body: Row Labels (Left) + 9x9 Board with Floating Pieces Layer */}
      <div className="flex-1 flex flex-row border border-zinc-800 rounded-xl overflow-hidden shadow-inner">
        {/* Left Row Label Column */}
        <div className="w-[28px] sm:w-[34px] grid grid-rows-9 bg-zinc-900 border-r border-zinc-800">
          {displayRows.map((r) => (
            <div key={r} className="flex items-center justify-center text-xs font-mono font-bold text-zinc-400">
              {r + 1}
            </div>
          ))}
        </div>

        {/* 9x9 Board Area: Grid Layer + Floating Pieces Layer */}
        <div className="flex-1 relative aspect-square">
          {/* 1. Static 9x9 Grid Layer */}
          <div className="w-full h-full grid grid-rows-9 grid-cols-9">
            {displayRows.map((r) =>
              displayCols.map((c) => {
                const isEven = (r + c) % 2 === 0;
                const isTargetA = r === TARGET_A.row && c === TARGET_A.col; // I1 (0, 8)
                const isTargetB = r === TARGET_B.row && c === TARGET_B.col; // A9 (8, 0)
                const piece = pieceMap.get(`${r},${c}`);
                const isValidMove = validMoveMap.has(`${r},${c}`);
                const moveInfo = validMoveMap.get(`${r},${c}`);

                const isLastMoveFrom = lastMove?.from && lastMove.from[0] === r && lastMove.from[1] === c;
                const isLastMoveTo = lastMove?.to && lastMove.to[0] === r && lastMove.to[1] === c;

                // Chess.com style warm yellow highlights - absolutely NO black / dark overlay!
                let highlightStyle = {};
                if (isLastMoveTo) {
                  highlightStyle = {
                    backgroundColor: 'rgba(250, 204, 21, 0.55)',
                    border: '2px solid rgba(234, 179, 8, 0.8)',
                  };
                } else if (isLastMoveFrom) {
                  highlightStyle = {
                    backgroundColor: 'rgba(250, 204, 21, 0.35)',
                  };
                }

                const canSelect =
                  !disabled &&
                  piece &&
                  (playerSide === 'BOTH'
                    ? piece.team === currentTurn
                    : playerSide === piece.team && currentTurn === piece.team);

                return (
                  <div
                    key={`${r}-${c}`}
                    onClick={() => handleCellClick(r, c)}
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
                      isTargetA && !isLastMoveTo ? 'border-2 border-dashed border-blue-600' : ''
                    } ${isTargetB && !isLastMoveTo ? 'border-2 border-dashed border-pink-600' : ''} ${
                      isValidMove || canSelect ? 'cursor-pointer hover:brightness-95' : ''
                    }`}
                    title={`${posToLabel(r, c)}${isTargetA ? ' (Đích Xanh I1)' : ''}${
                      isTargetB ? ' (Đích Đỏ A9)' : ''
                    }`}
                  >
                    {/* Target badges */}
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

                    {/* Valid Move Indicator */}
                    {isValidMove && (
                      <div className="absolute inset-0 z-20 flex items-center justify-center pointer-events-none">
                        {moveInfo?.capturePieceId ? (
                          <div className="w-full h-full border-4 border-rose-500/80 rounded-sm bg-rose-500/25 animate-pulse flex items-center justify-center">
                            <span className="text-[10px] font-black text-rose-800 bg-white/90 px-1 rounded shadow">
                              ⚔️
                            </span>
                          </div>
                        ) : (
                          <div className="w-3.5 h-3.5 sm:w-4 sm:h-4 rounded-full bg-emerald-500/90 shadow-md ring-2 ring-emerald-300 animate-bounce" />
                        )}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>

          {/* 2. Floating Pieces Layer with 16-Step Smooth Sliding Animation */}
          <div className="absolute inset-0 pointer-events-none">
            {pieces.filter((p) => p.alive).map((piece) => {
              const displayRowIndex = displayRows.indexOf(piece.row);
              const displayColIndex = displayCols.indexOf(piece.col);
              if (displayRowIndex === -1 || displayColIndex === -1) return null;

              const isSelected = selectedPiece && selectedPiece.id === piece.id;
              const canSelect =
                !disabled &&
                (playerSide === 'BOTH'
                  ? piece.team === currentTurn
                  : playerSide === piece.team && currentTurn === piece.team);

              return (
                <div
                  key={piece.id}
                  style={{
                    position: 'absolute',
                    left: `${(displayColIndex / 9) * 100}%`,
                    top: `${(displayRowIndex / 9) * 100}%`,
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
                      isSelected
                        ? 'ring-4 ring-amber-400 scale-110 shadow-xl z-20 animate-pulse'
                        : ''
                    } ${canSelect ? 'hover:scale-105 active:scale-95' : ''}`}
                  >
                    <span className="text-sm sm:text-xl filter drop-shadow select-none pointer-events-none">
                      {PIECE_EMOJIS[piece.type] ?? '❓'}
                    </span>
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
