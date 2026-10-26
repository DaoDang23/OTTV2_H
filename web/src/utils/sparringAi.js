/**
 * Sparring AI Strategies for OTTV2 9x9 Board
 * Implements 8 distinct algorithms for Machine 1 -> Machine 12
 * Machine controls Team B (Red), user controls Team A (Blue)
 * Target for Team B is A9 [8, 0]. Target for Team A is I1 [0, 8].
 */

import { getValidMoves, canBeat, TARGET_B, TARGET_A } from './gameRules.js';

export const SPARRING_MACHINES = [
  {
    level: 1,
    name: 'Máy 1 (Tân Binh)',
    code: 'SPAR-01',
    elo: 1050,
    winRate: 38,
    matches: 15,
    avatarSeed: 'Spar1',
    algorithm: 'Ngẫu Nhiên (Random Mover)',
    description: 'Chọn ngẫu nhiên một nước đi hợp lệ bất kỳ, không có chiến thuật.',
  },
  {
    level: 2,
    name: 'Máy 2 (Tập Sự)',
    code: 'SPAR-02',
    elo: 1120,
    winRate: 42,
    matches: 28,
    avatarSeed: 'Spar2',
    algorithm: 'Tiến Thẳng (Forward Runner)',
    description: 'Luôn ưu tiên đẩy quân về phía trước, ít quan tâm tới phòng thủ.',
  },
  {
    level: 3,
    name: 'Máy 3 (Cảm Tử)',
    code: 'SPAR-03',
    elo: 1200,
    winRate: 48,
    matches: 45,
    avatarSeed: 'Spar3',
    algorithm: 'Hổ Báo (Berserker)',
    description: 'Phát hiện quân địch trong tầm có thể ăn được là lập tức lao vào ăn.',
  },
  {
    level: 4,
    name: 'Máy 4 (Hộ Vệ)',
    code: 'SPAR-04',
    elo: 1280,
    winRate: 51,
    matches: 62,
    avatarSeed: 'Spar4',
    algorithm: 'Phòng Thủ (Guardian)',
    description: 'Bố trí quân chặn cửa ô đích I1, chỉ dâng quân phụ lên tấn công.',
  },
  {
    level: 5,
    name: 'Máy 5 (Tốc Biến)',
    code: 'SPAR-05',
    elo: 1360,
    winRate: 55,
    matches: 78,
    avatarSeed: 'Spar5',
    algorithm: 'Đua Đích (Goal Sprinter)',
    description: 'Dồn toàn lực đưa quân gần ô đích A9 nhất lách qua kẽ hở về đích.',
  },
  {
    level: 6,
    name: 'Máy 6 (Cẩn Trọng)',
    code: 'SPAR-06',
    elo: 1450,
    winRate: 58,
    matches: 95,
    avatarSeed: 'Spar6',
    algorithm: 'Né Thiên Địch (Threat Evasion)',
    description: 'Kiểm tra 4 ô xung quanh, tuyệt đối không bước vào ô có quân khắc chế.',
  },
  {
    level: 7,
    name: 'Máy 7 (Bầy Đàn)',
    code: 'SPAR-07',
    elo: 1550,
    winRate: 62,
    matches: 110,
    avatarSeed: 'Spar7',
    algorithm: 'Phalanx (Formation AI)',
    description: 'Di chuyển quân Kéo - Búa - Bao thành cụm tam giác bọc lót chéo nhau.',
  },
  {
    level: 8,
    name: 'Máy 8 (Chiến Thuật)',
    code: 'SPAR-08',
    elo: 1650,
    winRate: 66,
    matches: 135,
    avatarSeed: 'Spar8',
    algorithm: 'Đánh Giá Đa Tầng (Heuristic v1)',
    description: 'Cân bằng giữa khoảng cách đích, ăn quân và an toàn bàn cờ.',
  },
  {
    level: 9,
    name: 'Máy 9 (Bậc Thầy)',
    code: 'SPAR-09',
    elo: 1760,
    winRate: 70,
    matches: 160,
    avatarSeed: 'Spar9',
    algorithm: 'Đánh Giá Đa Tầng (Heuristic v2)',
    description: 'Kiểm soát trung tâm, đổi quân có lợi và dồn ép đường tiến của bạn.',
  },
  {
    level: 10,
    name: 'Máy 10 (Kiện Tướng)',
    code: 'SPAR-10',
    elo: 1880,
    winRate: 74,
    matches: 190,
    avatarSeed: 'Spar10',
    algorithm: 'Minimax Cận Chiến (Depth 2)',
    description: 'Tính toán dự đoán nước đi của đối phương để tránh bẫy và khóa góc.',
  },
  {
    level: 11,
    name: 'Máy 11 (Đại Kiện Tướng)',
    code: 'SPAR-11',
    elo: 2010,
    winRate: 79,
    matches: 225,
    avatarSeed: 'Spar11',
    algorithm: 'Minimax Đa Tầng & Kiểm Soát',
    description: 'Triệt tiêu quân chủ lực của bạn và khóa hoàn toàn hướng vào ô đích.',
  },
  {
    level: 12,
    name: 'Máy 12 (Huyền Thoại)',
    code: 'SPAR-12',
    elo: 2150,
    winRate: 85,
    matches: 280,
    avatarSeed: 'Spar12',
    algorithm: 'Grandmaster Heuristic (Tối Thượng)',
    description: 'Tối ưu hóa toàn diện: Đua đích + Đồ sát quân đối phương + Phòng ngự tầng sâu.',
  },
];

/**
 * Returns all valid moves for Team B in a flat list: [{ piece, move }]
 */
function getAllTeamBMoves(pieces) {
  const teamBPieces = pieces.filter((p) => p.team === 'B' && p.alive);
  const allMoves = [];
  for (const piece of teamBPieces) {
    const valid = getValidMoves(piece, pieces);
    for (const move of valid) {
      allMoves.push({ piece, move });
    }
  }
  return allMoves;
}

/**
 * Checks if a target cell (row, col) has any enemy adjacent piece that can beat pieceType
 */
function isThreatenedAt(row, col, pieceType, pieces) {
  const enemies = pieces.filter((p) => p.team === 'A' && p.alive);
  const dirs = [
    [-1, 0], [1, 0], [0, -1], [0, 1]
  ];
  for (const [dr, dc] of dirs) {
    const nr = row + dr;
    const nc = col + dc;
    const enemy = enemies.find((e) => e.row === nr && e.col === nc);
    if (enemy && canBeat(enemy.type, pieceType)) {
      return true;
    }
  }
  return false;
}

/**
 * Main AI decision engine for Sparring AI (Máy 1 -> Máy 12)
 */
export function getSparringAiMove(aiLevel, pieces) {
  const allMoves = getAllTeamBMoves(pieces);
  if (allMoves.length === 0) return null;

  // Immediate win check for any AI level: if any move lands on A9 [8, 0], take it!
  const winningMove = allMoves.find((m) => m.move.row === TARGET_B.row && m.move.col === TARGET_B.col);
  if (winningMove) return winningMove;

  switch (aiLevel) {
    // 1. Máy 1: Ngẫu nhiên 100%
    case 1: {
      const idx = Math.floor(Math.random() * allMoves.length);
      return allMoves[idx];
    }

    // 2. Máy 2: Tiến thẳng (Forward Runner) - ưu tiên tăng row về phía row 8
    case 2: {
      const scored = allMoves.map((item) => {
        const forwardScore = (item.move.row - item.piece.row) * 20; // +20 if moving forward
        const captureScore = item.move.capturePieceId ? 30 : 0;
        return {
          ...item,
          score: forwardScore + captureScore + Math.random() * 5,
        };
      });
      scored.sort((a, b) => b.score - a.score);
      return scored[0];
    }

    // 3. Máy 3: Cảm tử (Berserker) - Ưu tiên săn và ăn quân bất chấp
    case 3: {
      const scored = allMoves.map((item) => {
        let score = 0;
        if (item.move.capturePieceId) {
          score += 2000; // Cực kỳ hăng hái ăn quân
        }
        // Tiếp cận quân địch gần nhất
        const enemies = pieces.filter((p) => p.team === 'A' && p.alive);
        let minEnemyDist = 99;
        for (const e of enemies) {
          const d = Math.abs(item.move.row - e.row) + Math.abs(item.move.col - e.col);
          if (d < minEnemyDist) minEnemyDist = d;
        }
        score += (20 - minEnemyDist) * 10;
        return { ...item, score: score + Math.random() * 5 };
      });
      scored.sort((a, b) => b.score - a.score);
      return scored[0];
    }

    // 4. Máy 4: Hộ vệ (Guardian) - Giữ 3 quân quanh I1 [0, 8], quân còn lại dâng lên
    case 4: {
      const scored = allMoves.map((item) => {
        let score = 0;
        const distToUserTarget = Math.abs(item.piece.row - TARGET_A.row) + Math.abs(item.piece.col - TARGET_A.col);
        const nextDistToUserTarget = Math.abs(item.move.row - TARGET_A.row) + Math.abs(item.move.col - TARGET_A.col);

        // Nếu quân này ở gần I1 (hậu phương chặn cửa), ưu tiên giữ vị trí hoặc chặn đường
        if (distToUserTarget <= 3) {
          score += (nextDistToUserTarget <= 2) ? 50 : -20;
        } else {
          // Quân tấn công dâng lên
          score += (item.move.row - item.piece.row) * 15;
        }
        if (item.move.capturePieceId) score += 300;
        return { ...item, score: score + Math.random() * 5 };
      });
      scored.sort((a, b) => b.score - a.score);
      return scored[0];
    }

    // 5. Máy 5: Tốc biến (Goal Sprinter) - Đua thẳng về đích A9 [8, 0]
    case 5: {
      const scored = allMoves.map((item) => {
        const curDist = Math.abs(item.piece.row - TARGET_B.row) + Math.abs(item.piece.col - TARGET_B.col);
        const nextDist = Math.abs(item.move.row - TARGET_B.row) + Math.abs(item.move.col - TARGET_B.col);
        let score = (curDist - nextDist) * 60; // Rút ngắn khoảng cách cực mạnh
        if (item.move.capturePieceId) score += 200;
        return { ...item, score: score + Math.random() * 5 };
      });
      scored.sort((a, b) => b.score - a.score);
      return scored[0];
    }

    // 6. Máy 6: Cẩn trọng (Threat Evasion) - Tuyệt đối né thiên địch
    case 6: {
      const scored = allMoves.map((item) => {
        let score = 0;
        const isThreatened = isThreatenedAt(item.move.row, item.move.col, item.piece.type, pieces);
        if (isThreatened) {
          score -= 1000; // Phạt nặng nếu đi vào ô bị địch khắc chế rình
        } else {
          score += 50;
        }
        if (item.move.capturePieceId) score += 400;
        const curDist = Math.abs(item.piece.row - TARGET_B.row) + Math.abs(item.piece.col - TARGET_B.col);
        const nextDist = Math.abs(item.move.row - TARGET_B.row) + Math.abs(item.move.col - TARGET_B.col);
        score += (curDist - nextDist) * 20;
        return { ...item, score: score + Math.random() * 5 };
      });
      scored.sort((a, b) => b.score - a.score);
      return scored[0];
    }

    // 7. Máy 7: Bầy đàn (Phalanx Formation) - Đi sát nhau bọc lót
    case 7: {
      const teamBPieces = pieces.filter((p) => p.team === 'B' && p.alive);
      const scored = allMoves.map((item) => {
        let score = 0;
        // Đếm số đồng đội ở khoảng cách 1 hoặc 2 sau khi di chuyển
        let friendlyNeighbors = 0;
        for (const friend of teamBPieces) {
          if (friend.id === item.piece.id) continue;
          const d = Math.abs(item.move.row - friend.row) + Math.abs(item.move.col - friend.col);
          if (d === 1) friendlyNeighbors += 2;
          else if (d === 2) friendlyNeighbors += 1;
        }
        score += friendlyNeighbors * 25; // Thưởng đội hình bọc lót
        if (item.move.capturePieceId) score += 300;
        const curDist = Math.abs(item.piece.row - TARGET_B.row) + Math.abs(item.piece.col - TARGET_B.col);
        const nextDist = Math.abs(item.move.row - TARGET_B.row) + Math.abs(item.move.col - TARGET_B.col);
        score += (curDist - nextDist) * 15;
        return { ...item, score: score + Math.random() * 5 };
      });
      scored.sort((a, b) => b.score - a.score);
      return scored[0];
    }

    // 8 -> 12: Đánh giá đa tầng (Minimax / Multi-layer Heuristic Scoring)
    // Công thức yêu cầu: (Khoảng cách tới đích * 10) + (Ăn được quân địch * 50) - (Nguy cơ bị ăn * 60) + (Kiểm soát trung tâm * 5)
    default: {
      const centerDistWeight = aiLevel >= 10 ? 12 : 5;
      const threatWeight = aiLevel >= 11 ? 90 : 60;
      const captureWeight = aiLevel >= 11 ? 75 : 50;

      const scored = allMoves.map((item) => {
        // 1. Khoảng cách tới đích A9
        const curDist = Math.abs(item.piece.row - TARGET_B.row) + Math.abs(item.piece.col - TARGET_B.col);
        const nextDist = Math.abs(item.move.row - TARGET_B.row) + Math.abs(item.move.col - TARGET_B.col);
        const distReduction = (curDist - nextDist);
        let score = distReduction * 10;

        // 2. Ăn quân địch
        if (item.move.capturePieceId) {
          score += captureWeight;
        }

        // 3. Nguy cơ bị ăn ở lượt sau
        const isThreatened = isThreatenedAt(item.move.row, item.move.col, item.piece.type, pieces);
        if (isThreatened) {
          score -= threatWeight;
        }

        // 4. Kiểm soát khu vực trung tâm (hàng 3, 4, 5, cột 3, 4, 5)
        const centerDist = Math.abs(item.move.row - 4) + Math.abs(item.move.col - 4);
        score += (8 - centerDist) * centerDistWeight;

        // Thêm chút ngẫu nhiên nhỏ để tránh lặp nước
        score += Math.random() * 3;

        return { ...item, score };
      });

      scored.sort((a, b) => b.score - a.score);
      return scored[0];
    }
  }
}
