import {
  INITIAL_PIECES,
  TARGET_A,
  TARGET_B,
  DEFAULT_MAX_TURNS,
  getValidMoves,
  checkWinner,
  posToLabel,
  PIECE_EMOJIS,
} from './gameRules.js';

/**
 * Calculates Manhattan distance between two positions
 */
function manhattanDist(r1, c1, r2, c2) {
  return Math.abs(r1 - r2) + Math.abs(c1 - c2);
}

/**
 * Simulates a full 9x9 match between User Bot (Team A) and Opponent Bot (Team B)
 * Returns { replay, userWon, matchInfo }
 */
export function simulateMatch(
  userElo,
  opponent,
  userName = 'Bạn',
  userBotName = 'MasterRPS_v2',
  maxTurns = DEFAULT_MAX_TURNS,
  seed = null
) {
  // Deterministic PRNG if seed is provided (guarantees zero desync between clients)
  let prng = Math.random;
  if (seed !== null && seed !== undefined) {
    let s = Math.abs(typeof seed === 'number' ? seed : String(seed).split('').reduce((acc, c) => (acc * 31 + c.charCodeAt(0)) | 0, 0)) || 123456789;
    prng = function () {
      s = (s * 1664525 + 1013904223) >>> 0;
      return s / 4294967296;
    };
  }

  const pieces = INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 }));
  const turns = [];
  let currentTeam = 'A';
  let winner = null;
  let winReason = null;

  for (let t = 1; t <= maxTurns; t += 1) {
    const aliveTeamPieces = pieces.filter((p) => p.team === currentTeam && p.alive);
    if (aliveTeamPieces.length === 0) {
      winner = currentTeam === 'A' ? 'B' : 'A';
      winReason = 'ANNIHILATION';
      break;
    }

    // Collect all legal moves for current team
    const allCandidateMoves = [];
    for (const piece of aliveTeamPieces) {
      const valid = getValidMoves(piece, pieces);
      for (const m of valid) {
        allCandidateMoves.push({
          piece,
          targetRow: m.row,
          targetCol: m.col,
          capturePieceId: m.capturePieceId,
        });
      }
    }

    if (allCandidateMoves.length === 0) {
      // Stalemate / no legal moves -> opponent team wins
      winner = currentTeam === 'A' ? 'B' : 'A';
      winReason = 'NO_LEGAL_MOVES';
      break;
    }

    // Target for current team
    const target = currentTeam === 'A' ? TARGET_A : TARGET_B;
    const teamElo = currentTeam === 'A' ? userElo : (opponent?.elo || 1200);

    // Score candidate moves with tactical AI
    for (const cand of allCandidateMoves) {
      let score = 0;

      // 1. If reaching target -> Immediate win move!
      if (cand.targetRow === target.row && cand.targetCol === target.col) {
        score += 3000;
      }

      // 2. If capturing opponent piece -> High priority
      if (cand.capturePieceId) {
        score += 350;
      }

      // 3. Distance reduction to target zone
      const oldDist = manhattanDist(cand.piece.row, cand.piece.col, target.row, target.col);
      const newDist = manhattanDist(cand.targetRow, cand.targetCol, target.row, target.col);
      score += (oldDist - newDist) * 35;

      // 4. Add small random variance depending on Elo
      const noiseMagnitude = Math.max(5, (2500 - teamElo) / 35);
      score += (prng() - 0.5) * noiseMagnitude;

      cand.score = score;
    }

    // Sort moves descending by score
    allCandidateMoves.sort((a, b) => b.score - a.score);

    // High Elo strictly picks optimal moves; lower Elo picks from top 3-4
    const pickPoolSize = teamElo > 1800 ? 1 : teamElo > 1400 ? 2 : 4;
    const selectedMove =
      allCandidateMoves[Math.floor(prng() * Math.min(pickPoolSize, allCandidateMoves.length))];

    const movingPiece = pieces.find((p) => p.id === selectedMove.piece.id);
    const fromPos = [movingPiece.row, movingPiece.col];
    const toPos = [selectedMove.targetRow, selectedMove.targetCol];

    // Execute move
    movingPiece.row = toPos[0];
    movingPiece.col = toPos[1];

    const events = [];
    if (selectedMove.capturePieceId) {
      const victim = pieces.find((p) => p.id === selectedMove.capturePieceId);
      if (victim) {
        victim.alive = false;
        movingPiece.kills = (movingPiece.kills || 0) + 1;
        events.push({
          type: 'CAPTURED',
          victimId: victim.id,
          victimType: victim.type,
          victimTeam: victim.team,
          attackerId: movingPiece.id,
          attackerType: movingPiece.type,
          attackerTeam: movingPiece.team,
          description: `[${currentTeam === 'A' ? 'Xanh' : 'Đỏ'}] ${PIECE_EMOJIS[movingPiece.type]} #${movingPiece.id} (${posToLabel(fromPos[0], fromPos[1])} → ${posToLabel(toPos[0], toPos[1])}) tiêu diệt [${victim.team === 'A' ? 'Xanh' : 'Đỏ'}] ${PIECE_EMOJIS[victim.type]} #${victim.id}!`,
        });
      }
    } else {
      events.push({
        type: 'MOVE',
        description: `[${currentTeam === 'A' ? 'Xanh' : 'Đỏ'}] ${PIECE_EMOJIS[movingPiece.type]} #${movingPiece.id} di chuyển ${posToLabel(fromPos[0], fromPos[1])} → ${posToLabel(toPos[0], toPos[1])}`,
      });
    }

    const turnItem = {
      t,
      turn: t,
      team: currentTeam,
      pieceId: movingPiece.id,
      pieceType: movingPiece.type,
      from: fromPos,
      to: toPos,
      capturedPieceId: selectedMove.capturePieceId || null,
      capturedPieceType: selectedMove.capturePieceId
        ? pieces.find((p) => p.id === selectedMove.capturePieceId)?.type || null
        : null,
      move: {
        p: movingPiece.id,
        pieceId: movingPiece.id,
        from: fromPos,
        to: toPos,
      },
      events,
    };
    turns.push(turnItem);

    // Check win condition
    const winCheck = checkWinner(pieces);
    if (winCheck.winner) {
      winner = winCheck.winner;
      winReason = winCheck.reason;
      break;
    }

    currentTeam = currentTeam === 'A' ? 'B' : 'A';
  }

  if (!winner) {
    const teamAPieces = pieces.filter((p) => p.team === 'A' && p.alive);
    const teamBPieces = pieces.filter((p) => p.team === 'B' && p.alive);

    // 1st criterion: Alive pieces count
    if (teamAPieces.length > teamBPieces.length) {
      winner = 'A';
      winReason = 'PIECE_ADVANTAGE';
    } else if (teamBPieces.length > teamAPieces.length) {
      winner = 'B';
      winReason = 'PIECE_ADVANTAGE';
    } else {
      // 2nd criterion: Closest piece Chebyshev distance to respective target zone
      let minA = Infinity;
      let minB = Infinity;
      for (const p of teamAPieces) {
        const distA = Math.max(Math.abs(p.row - TARGET_A.row), Math.abs(p.col - TARGET_A.col));
        if (distA < minA) minA = distA;
      }
      for (const p of teamBPieces) {
        const distB = Math.max(Math.abs(p.row - TARGET_B.row), Math.abs(p.col - TARGET_B.col));
        if (distB < minB) minB = distB;
      }

      if (minA < minB) {
        winner = 'A';
        winReason = 'TARGET_PROXIMITY_ADVANTAGE';
      } else if (minB < minA) {
        winner = 'B';
        winReason = 'TARGET_PROXIMITY_ADVANTAGE';
      } else {
        winner = 'DRAW';
        winReason = 'TURN_LIMIT_EXCEEDED';
      }
    }
  }

  const matchId = Date.now();
  const replay = {
    version: 1,
    matchId,
    sideA: { botName: `${userName} - ${userBotName} (${userElo} ELO)` },
    sideB: { botName: `${opponent?.botName || opponent?.name || 'Bot B'} (${opponent?.elo || 1200} ELO)` },
    initialBoard: Array.from({ length: 9 }, () => Array(9).fill('EMPTY')),
    initialPieces: INITIAL_PIECES.map((p) => ({ ...p, alive: true, kills: 0 })),
    turns,
    moves: turns,
    result: { winner, reason: winReason },
  };

  const matchInfo = {
    id: matchId,
    sideA: { botName: `${userName} - ${userBotName} (${userElo} ELO)` },
    sideB: { botName: `${opponent?.botName || opponent?.name || 'Bot B'} (${opponent?.elo || 1200} ELO)` },
    status: 'FINISHED',
    winner,
    reason: winReason,
  };

  return {
    replay,
    userWon: winner === 'A',
    matchInfo,
  };
}
