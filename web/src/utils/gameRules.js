/**
 * Game Rules, Formations, and Constants for OTTV2 9x9 Rock-Paper-Scissors Battle
 */

export const BOARD_SIZE = 9;

/**
 * Standard Turn Limits across game modes (authoritative limits)
 */
export const GAME_TURN_LIMITS = {
  QUICK_TEST: 100,
  DEFAULT: 200,
  RANKED: 200,
  TOURNAMENT: 300,
  SANDBOX: 500,
};

export const DEFAULT_MAX_TURNS = GAME_TURN_LIMITS.DEFAULT;

// Target zones:
// Team A (Blue - Phe dưới) marches to I1 (row 0, col 8)
export const TARGET_A = { row: 0, col: 8, label: 'I1' };
// Team B (Red - Phe trên) marches to A9 (row 8, col 0)
export const TARGET_B = { row: 8, col: 0, label: 'A9' };

export const PIECE_EMOJIS = {
  ROCK: '👊',
  PAPER: '✋',
  SCISSORS: '✌️',
};

/**
 * 9x9 Balanced Phalanx Formation (Đội hình 3 Cánh Đa Hệ Đối Xứng Tâm)
 */
export const INITIAL_PIECES = [
  // Đội Xanh (Phe A - Ở dưới, đánh lên ô đích I1)
  // Hàng 7 (Tiền đạo - row 6): C7 (Kéo), E7 (Búa), G7 (Bao)
  { id: 1, team: 'A', type: 'SCISSORS', row: 6, col: 2, alive: true, kills: 0 },
  { id: 2, team: 'A', type: 'ROCK', row: 6, col: 4, alive: true, kills: 0 },
  { id: 3, team: 'A', type: 'PAPER', row: 6, col: 6, alive: true, kills: 0 },
  // Hàng 8 (Trung tuyến - row 7): D8 (Bao), E8 (Kéo), F8 (Búa)
  { id: 4, team: 'A', type: 'PAPER', row: 7, col: 3, alive: true, kills: 0 },
  { id: 5, team: 'A', type: 'SCISSORS', row: 7, col: 4, alive: true, kills: 0 },
  { id: 6, team: 'A', type: 'ROCK', row: 7, col: 5, alive: true, kills: 0 },
  // Hàng 9 (Hậu vệ - row 8): C9 (Búa), E9 (Bao), G9 (Kéo)
  { id: 7, team: 'A', type: 'ROCK', row: 8, col: 2, alive: true, kills: 0 },
  { id: 8, team: 'A', type: 'PAPER', row: 8, col: 4, alive: true, kills: 0 },
  { id: 9, team: 'A', type: 'SCISSORS', row: 8, col: 6, alive: true, kills: 0 },

  // Đội Đỏ (Phe B - Ở trên, đánh xuống ô đích A9 - Đối xứng tâm [8-r, 8-c] với Đội Xanh)
  // Hàng 3 (Tiền đạo - row 2): G3 (Kéo), E3 (Búa), C3 (Bao)
  { id: 10, team: 'B', type: 'SCISSORS', row: 2, col: 6, alive: true, kills: 0 },
  { id: 11, team: 'B', type: 'ROCK', row: 2, col: 4, alive: true, kills: 0 },
  { id: 12, team: 'B', type: 'PAPER', row: 2, col: 2, alive: true, kills: 0 },
  // Hàng 2 (Trung tuyến - row 1): F2 (Bao), E2 (Kéo), D2 (Búa)
  { id: 13, team: 'B', type: 'PAPER', row: 1, col: 5, alive: true, kills: 0 },
  { id: 14, team: 'B', type: 'SCISSORS', row: 1, col: 4, alive: true, kills: 0 },
  { id: 15, team: 'B', type: 'ROCK', row: 1, col: 3, alive: true, kills: 0 },
  // Hàng 1 (Hậu vệ - row 0): G1 (Búa), E1 (Bao), C1 (Kéo)
  { id: 16, team: 'B', type: 'ROCK', row: 0, col: 6, alive: true, kills: 0 },
  { id: 17, team: 'B', type: 'PAPER', row: 0, col: 4, alive: true, kills: 0 },
  { id: 18, team: 'B', type: 'SCISSORS', row: 0, col: 2, alive: true, kills: 0 },
];

/**
 * Returns true if attacker beats defender in Rock-Paper-Scissors
 */
export function canBeat(attackerType, defenderType) {
  if (attackerType === 'ROCK' && defenderType === 'SCISSORS') return true;
  if (attackerType === 'PAPER' && defenderType === 'ROCK') return true;
  if (attackerType === 'SCISSORS' && defenderType === 'PAPER') return true;
  return false;
}

export function posToLabel(row, col) {
  const letters = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'];
  const c = Math.max(0, Math.min(8, col));
  const r = Math.max(0, Math.min(8, row));
  return `${letters[c]}${r + 1}`;
}

/**
 * Calculates legal moves for a given piece on the board.
 * 4 orthogonal directions: Up, Down, Left, Right (1 step).
 * Cannot enter cells with friendly pieces.
 * Can enter enemy cell ONLY IF canBeat(piece.type, enemy.type).
 */
export function getValidMoves(piece, allPieces) {
  if (!piece || !piece.alive) return [];
  const moves = [];
  const directions = [
    [-1, 0], // Up
    [1, 0],  // Down
    [0, -1], // Left
    [0, 1],  // Right
  ];

  const occupied = new Map();
  for (const p of allPieces) {
    if (p.alive) {
      occupied.set(`${p.row},${p.col}`, p);
    }
  }

  for (const [dr, dc] of directions) {
    const nr = piece.row + dr;
    const nc = piece.col + dc;

    if (nr < 0 || nr >= BOARD_SIZE || nc < 0 || nc >= BOARD_SIZE) continue;

    const key = `${nr},${nc}`;
    const occupant = occupied.get(key);

    if (!occupant) {
      moves.push({ row: nr, col: nc, capturePieceId: null });
    } else {
      if (occupant.team === piece.team) {
        continue;
      }
      if (canBeat(piece.type, occupant.type)) {
        moves.push({ row: nr, col: nc, capturePieceId: occupant.id });
      }
    }
  }

  return moves;
}

/**
 * Check win condition on current state.
 */
export function checkWinner(pieces) {
  const alivePieces = pieces.filter((p) => p.alive);
  const teamAPieces = alivePieces.filter((p) => p.team === 'A');
  const teamBPieces = alivePieces.filter((p) => p.team === 'B');

  // 1. Annihilation condition
  if (teamBPieces.length === 0 && teamAPieces.length > 0) {
    return { winner: 'A', reason: 'ANNIHILATION' };
  }
  if (teamAPieces.length === 0 && teamBPieces.length > 0) {
    return { winner: 'B', reason: 'ANNIHILATION' };
  }
  if (teamAPieces.length === 0 && teamBPieces.length === 0) {
    return { winner: 'DRAW', reason: 'ALL_CAPTURED' };
  }

  // 2. Target reached condition
  for (const p of teamAPieces) {
    if (p.row === TARGET_A.row && p.col === TARGET_A.col) {
      return { winner: 'A', reason: 'TARGET_REACHED' };
    }
  }
  for (const p of teamBPieces) {
    if (p.row === TARGET_B.row && p.col === TARGET_B.col) {
      return { winner: 'B', reason: 'TARGET_REACHED' };
    }
  }

  return { winner: null, reason: null };
}

/**
 * Standard Elo Rating Calculator (K-factor = 32)
 */
export function calculateEloChange(userElo, opponentElo, userWon) {
  const K = 32;
  const expectedUser = 1 / (1 + Math.pow(10, (opponentElo - userElo) / 400));
  const actualScore = userWon ? 1 : 0;
  const change = Math.round(K * (actualScore - expectedUser));

  let finalChange = change;
  if (userWon) {
    finalChange = Math.max(12, Math.min(32, change));
  } else {
    finalChange = Math.min(-10, Math.max(-28, change));
  }

  const newElo = Math.max(1000, userElo + finalChange);
  return { change: finalChange, newElo };
}

/**
 * Progressive Ladder Gate check:
 */
export function isOpponentUnlocked(opponentElo, userElo, opponentIndex = 0, userRankIndex = 0, maxDiff = 300) {
  if (Math.abs(opponentElo - userElo) <= maxDiff) return true;
  if (userRankIndex >= 0 && opponentIndex >= userRankIndex - 2) return true;
  return false;
}

/**
 * Chess Clock Time Controls (Fischer Clock)
 */
export const CHESS_TIME_CONTROLS = {
  BLITZ: {
    id: 'blitz',
    name: '⚡ Chớp (Blitz 3+2)',
    desc: '03:00 tổng thời gian · +2s sau mỗi nước đi',
    baseMs: 180000,
    incMs: 2000,
    label: '3+2',
  },
  RAPID: {
    id: 'rapid',
    name: '🔥 Nhanh (Rapid 5+3)',
    desc: '05:00 tổng thời gian · +3s sau mỗi nước đi',
    baseMs: 300000,
    incMs: 3000,
    label: '5+3',
  },
  BULLET: {
    id: 'bullet',
    name: '🚀 Siêu Chớp (Bullet 1+1)',
    desc: '01:00 tổng thời gian · +1s sau mỗi nước đi',
    baseMs: 60000,
    incMs: 1000,
    label: '1+1',
  },
};

/**
 * Formats milliseconds into Chess.com Fischer clock format:
 * - If > 20s: MM:SS (e.g. 02:45)
 * - If <= 20s: MM:SS.d with tenths of seconds (e.g. 00:14.3)
 */
export function formatChessClock(ms) {
  const safeMs = Math.max(0, ms);
  const totalSeconds = Math.floor(safeMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;

  const mm = String(minutes).padStart(2, '0');
  const ss = String(seconds).padStart(2, '0');

  if (safeMs <= 20000) {
    const tenths = Math.floor((safeMs % 1000) / 100);
    return `${mm}:${ss}.${tenths}`;
  }

  return `${mm}:${ss}`;
}
