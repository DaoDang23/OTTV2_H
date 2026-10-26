// Helper quản lý danh sách ID các bot đã xóa trong localStorage với key 'ottv2_deleted_bot_ids'
// và bản đồ mã nguồn bot 'ottv2_bot_sources_map' theo bot.id và bot.name

export const DELETED_BOTS_KEY = 'ottv2_deleted_bot_ids';
export const BOT_SOURCES_MAP_KEY = 'ottv2_bot_sources_map';

export const VIP_BOT_CODES = {
  VIP_ShadowSprinter: `import json
import sys

# VIP_ShadowSprinter: Thuật toán Tốc Biến & Đua Đích
# Chiến thuật: Luôn tính khoảng cách Manhattan ngắn nhất tới ô đích (I1 [0, 8] hoặc A9 [8, 0]),
# đồng thời rà soát 4 hướng để lách qua kẽ hở và né tránh quân khắc chế.

def manhattan(r1, c1, r2, c2):
    return abs(r1 - r2) + abs(c1 - c2)

def can_beat(t1, t2):
    rules = {"ROCK": "SCISSORS", "SCISSORS": "PAPER", "PAPER": "ROCK"}
    return rules.get(t1) == t2

def decide(state):
    your_pieces = [p for p in state.get("yourPieces", []) if p.get("alive", True)]
    enemy_pieces = [p for p in state.get("enemyPieces", []) if p.get("alive", True)]
    targets = state.get("targets", [[0, 8]])
    target = targets[0] if targets else [0, 8]
    
    if not your_pieces:
        return None
        
    best_move = None
    best_score = -999999
    
    for piece in your_pieces:
        pid = piece["id"]
        pr, pc, ptype = piece["r"], piece["c"], piece.get("type", "ROCK")
        
        for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            nr, nc = pr + dr, pc + dc
            if not (0 <= nr < 9 and 0 <= nc < 9):
                continue
                
            # Kiểm tra va chạm với quân mình
            if any(other["r"] == nr and other["c"] == nc for other in your_pieces if other["id"] != pid):
                continue

            if any((cell.get("r") == nr and cell.get("c") == nc and cell.get("type") in ("OBSTACLE", "TRAP"))
                   for cell in state.get("visibleCells", state.get("cells", []))):
                continue
            if any(isinstance(ob, list) and len(ob) == 2 and ob[0] == nr and ob[1] == nc
                   for ob in state.get("obstacles", [])):
                continue

            # Kiểm tra vật cản
            if any((cell.get("r") == nr and cell.get("c") == nc and cell.get("type") in ("OBSTACLE", "TRAP"))
                   for cell in state.get("visibleCells", state.get("cells", []))):
                continue
            if any(isinstance(ob, list) and len(ob) == 2 and ob[0] == nr and ob[1] == nc
                   for ob in state.get("obstacles", [])):
                continue
                
            # 1. Chạm ô đích -> Thắng ngay
            if nr == target[0] and nc == target[1]:
                return {"pieceId": pid, "to": [nr, nc]}
                
            score = 0
            # 2. Rút ngắn khoảng cách Manhattan tới ô đích
            dist_change = manhattan(pr, pc, target[0], target[1]) - manhattan(nr, nc, target[0], target[1])
            score += dist_change * 85
            
            # 3. Né quân địch khắc chế ở ô đích đến và 4 ô kề cạnh
            target_enemy = next((ep for ep in enemy_pieces if ep["r"] == nr and ep["c"] == nc), None)
            if target_enemy:
                if can_beat(ptype, target_enemy.get("type")):
                    score += 450
                else:
                    continue  # Tránh tự sát
                    
            for ep in enemy_pieces:
                if manhattan(nr, nc, ep["r"], ep["c"]) == 1 and can_beat(ep.get("type"), ptype):
                    score -= 350
                    
            if score > best_score:
                best_score = score
                best_move = {"pieceId": pid, "to": [nr, nc]}
                
    return best_move

for line in sys.stdin:
    try:
        if not line.strip(): continue
        print(json.dumps(decide(json.loads(line))), flush=True)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr, flush=True)
        break`,

  VIP_ApexPredator: `import json
import sys

# VIP_ApexPredator: Thuật toán Săn Mồi & Đồ Sát Quân Địch
# Chiến thuật: Tận dụng ưu thế Kéo - Búa - Bao (canBeat) để truy quét, bắt sống
# và tiêu diệt sạch quân chủ lực của đối thủ trên toàn bàn cờ 9x9.

def can_beat(t1, t2):
    rules = {"ROCK": "SCISSORS", "SCISSORS": "PAPER", "PAPER": "ROCK"}
    return rules.get(t1) == t2

def manhattan(r1, c1, r2, c2):
    return abs(r1 - r2) + abs(c1 - c2)

def decide(state):
    your_pieces = [p for p in state.get("yourPieces", []) if p.get("alive", True)]
    enemy_pieces = [p for p in state.get("enemyPieces", []) if p.get("alive", True)]
    targets = state.get("targets", [[0, 8]])
    target = targets[0] if targets else [0, 8]
    
    if not your_pieces:
        return None
        
    best_move = None
    best_score = -999999
    
    for piece in your_pieces:
        pid = piece["id"]
        pr, pc, ptype = piece["r"], piece["c"], piece.get("type", "ROCK")
        
        for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            nr, nc = pr + dr, pc + dc
            if not (0 <= nr < 9 and 0 <= nc < 9):
                continue
                
            if any(other["r"] == nr and other["c"] == nc for other in your_pieces if other["id"] != pid):
                continue

            if any((cell.get("r") == nr and cell.get("c") == nc and cell.get("type") in ("OBSTACLE", "TRAP"))
                   for cell in state.get("visibleCells", state.get("cells", []))):
                continue
            if any(isinstance(ob, list) and len(ob) == 2 and ob[0] == nr and ob[1] == nc
                   for ob in state.get("obstacles", [])):
                continue
                
            if nr == target[0] and nc == target[1]:
                return {"pieceId": pid, "to": [nr, nc]}
                
            score = 0
            target_enemy = next((ep for ep in enemy_pieces if ep["r"] == nr and ep["c"] == nc), None)
            if target_enemy:
                if can_beat(ptype, target_enemy.get("type")):
                    score += 1600  # Ưu tiên cực cao săn mồi ăn quân
                else:
                    continue  # Không đâm đầu vào ô bị khắc chế
                    
            # Thu hẹp khoảng cách tới con mồi gần nhất mà mình ăn được
            prey = [ep for ep in enemy_pieces if can_beat(ptype, ep.get("type"))]
            if prey:
                min_prey_dist = min(manhattan(nr, nc, ep["r"], ep["c"]) for ep in prey)
                score += (18 - min_prey_dist) * 40
                
            # Tránh xa kẻ thù khắc chế mình
            for ep in enemy_pieces:
                if can_beat(ep.get("type"), ptype) and manhattan(nr, nc, ep["r"], ep["c"]) == 1:
                    score -= 700
                    
            # Rút ngắn khoảng cách đích phụ
            score += (manhattan(pr, pc, target[0], target[1]) - manhattan(nr, nc, target[0], target[1])) * 15
            
            if score > best_score:
                best_score = score
                best_move = {"pieceId": pid, "to": [nr, nc]}
                
    return best_move

for line in sys.stdin:
    try:
        if not line.strip(): continue
        print(json.dumps(decide(json.loads(line))), flush=True)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr, flush=True)
        break`,

  VIP_GrandmasterPhalanx: `import json
import sys

# VIP_GrandmasterPhalanx: Thuật toán Đội Hình Phalanx Đa Tầng
# Chiến thuật: Giữ vững liên kết tam giác Kéo - Búa - Bao bọc lót chéo nhau,
# kiểm soát trung tâm bàn cờ và đồng loạt tiến quân siết chặt vòng vây.

def can_beat(t1, t2):
    rules = {"ROCK": "SCISSORS", "SCISSORS": "PAPER", "PAPER": "ROCK"}
    return rules.get(t1) == t2

def manhattan(r1, c1, r2, c2):
    return abs(r1 - r2) + abs(c1 - c2)

def decide(state):
    your_pieces = [p for p in state.get("yourPieces", []) if p.get("alive", True)]
    enemy_pieces = [p for p in state.get("enemyPieces", []) if p.get("alive", True)]
    targets = state.get("targets", [[0, 8]])
    target = targets[0] if targets else [0, 8]
    
    if not your_pieces:
        return None
        
    best_move = None
    best_score = -999999
    
    for piece in your_pieces:
        pid = piece["id"]
        pr, pc, ptype = piece["r"], piece["c"], piece.get("type", "ROCK")
        
        for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            nr, nc = pr + dr, pc + dc
            if not (0 <= nr < 9 and 0 <= nc < 9):
                continue
                
            if any(other["r"] == nr and other["c"] == nc for other in your_pieces if other["id"] != pid):
                continue

            if any((cell.get("r") == nr and cell.get("c") == nc and cell.get("type") in ("OBSTACLE", "TRAP"))
                   for cell in state.get("visibleCells", state.get("cells", []))):
                continue
            if any(isinstance(ob, list) and len(ob) == 2 and ob[0] == nr and ob[1] == nc
                   for ob in state.get("obstacles", [])):
                continue
                
            if nr == target[0] and nc == target[1]:
                return {"pieceId": pid, "to": [nr, nc]}
                
            score = 0
            target_enemy = next((ep for ep in enemy_pieces if ep["r"] == nr and ep["c"] == nc), None)
            if target_enemy:
                if can_beat(ptype, target_enemy.get("type")):
                    score += 950
                else:
                    continue
                    
            # Điểm bọc lót Phalanx: Thưởng điểm khi đứng cạnh đồng đội khác loại
            for ally in [p for p in your_pieces if p["id"] != pid]:
                d = manhattan(nr, nc, ally["r"], ally["c"])
                if d == 1:
                    score += 140 if ally.get("type") != ptype else 40
                elif d == 2:
                    score += 50
                    
            # Kiểm soát trung tâm bàn cờ (hàng 4, cột 4)
            center_dist = abs(nr - 4) + abs(nc - 4)
            score += (8 - center_dist) * 25
            
            # Tiến quân về đích
            score += (manhattan(pr, pc, target[0], target[1]) - manhattan(nr, nc, target[0], target[1])) * 30
            
            # Né đe dọa
            for ep in enemy_pieces:
                if manhattan(nr, nc, ep["r"], ep["c"]) == 1 and can_beat(ep.get("type"), ptype):
                    score -= 450
                    
            if score > best_score:
                best_score = score
                best_move = {"pieceId": pid, "to": [nr, nc]}
                
    return best_move

for line in sys.stdin:
    try:
        if not line.strip(): continue
        print(json.dumps(decide(json.loads(line))), flush=True)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr, flush=True)
        break`,
};

export function getDeletedBotIds() {
  try {
    const raw = localStorage.getItem(DELETED_BOTS_KEY);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw);
    return new Set(Array.isArray(parsed) ? parsed.map(String) : []);
  } catch {
    return new Set();
  }
}

export function addDeletedBotId(botId) {
  try {
    const s = getDeletedBotIds();
    s.add(String(botId));
    localStorage.setItem(DELETED_BOTS_KEY, JSON.stringify(Array.from(s)));
  } catch {}
}

export function addDeletedBotIds(botIds) {
  try {
    const s = getDeletedBotIds();
    botIds.forEach((id) => s.add(String(id)));
    localStorage.setItem(DELETED_BOTS_KEY, JSON.stringify(Array.from(s)));
  } catch {}
}

export function filterActiveBots(botList) {
  if (!Array.isArray(botList)) return [];
  const deletedIds = getDeletedBotIds();
  return botList.filter((b) => b && !deletedIds.has(String(b.id)));
}

// Map quản lý sourceCode của từng con bot trong localStorage với key 'ottv2_bot_sources_map'
export function getBotSourcesMap() {
  try {
    const raw = localStorage.getItem(BOT_SOURCES_MAP_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

export function saveBotSource(botId, botName, code, language = 'PYTHON') {
  try {
    const map = getBotSourcesMap();
    const entry = {
      code,
      language: language || 'PYTHON',
      botName: botName || '',
      updatedAt: new Date().toISOString(),
    };
    if (botId !== undefined && botId !== null) {
      map[String(botId)] = entry;
    }
    if (botName) {
      map[String(botName)] = entry;
    }
    localStorage.setItem(BOT_SOURCES_MAP_KEY, JSON.stringify(map));
    return entry;
  } catch (err) {
    console.warn('Failed to save bot source to localStorage:', err);
  }
}

export function getBotSource(botId, botName) {
  const map = getBotSourcesMap();
  let found = null;
  if (botId !== undefined && botId !== null && map[String(botId)]) {
    found = map[String(botId)];
  } else if (botName && map[String(botName)]) {
    found = map[String(botName)];
  }

  if (found) {
    if (typeof found === 'string') {
      return {
        code: found,
        language: found.includes('public class') ? 'JAVA' : 'PYTHON',
      };
    }
    return found;
  }

  // Nếu chưa có trong map, kiểm tra các bot VIP hoặc khởi tạo mã nguồn đặc thù
  let defaultCode = null;
  if (botName && VIP_BOT_CODES[botName]) {
    defaultCode = VIP_BOT_CODES[botName];
  } else if (botName?.includes('Sprinter')) {
    defaultCode = VIP_BOT_CODES.VIP_ShadowSprinter;
  } else if (botName?.includes('Predator')) {
    defaultCode = VIP_BOT_CODES.VIP_ApexPredator;
  } else if (botName?.includes('Phalanx')) {
    defaultCode = VIP_BOT_CODES.VIP_GrandmasterPhalanx;
  } else {
    defaultCode = `# Mã nguồn chiến thuật cho bot [${botName || 'CustomBot'}]
import json
import sys

def decide(state):
    your_pieces = [p for p in state.get("yourPieces", []) if p.get("alive", True)]
    targets = state.get("targets", [[0, 8]])
    target = targets[0] if targets else [0, 8]
    if not your_pieces: return None

    blocked = {(p["r"], p["c"]) for p in your_pieces}
    for cell in state.get("visibleCells", state.get("cells", [])):
        if cell.get("type") in ("OBSTACLE", "TRAP"):
            blocked.add((cell.get("r"), cell.get("c")))
    for ob in state.get("obstacles", []):
        if isinstance(ob, list) and len(ob) == 2:
            blocked.add((ob[0], ob[1]))

    for p in your_pieces:
        r, c = p["r"], p["c"]
        moves = []
        if r != target[0]: moves.append((r + (1 if target[0] > r else -1), c))
        if c != target[1]: moves.append((r, c + (1 if target[1] > c else -1)))
        for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]: moves.append((r + dr, c + dc))
        for nr, nc in moves:
            if 0 <= nr < 9 and 0 <= nc < 9 and (nr, nc) not in blocked:
                return {"pieceId": p["id"], "to": [nr, nc]}
    return None

for line in sys.stdin:
    try:
        if not line.strip(): continue
        print(json.dumps(decide(json.loads(line))), flush=True)
    except: break`;
  }

  const newEntry = {
    code: defaultCode,
    language: 'PYTHON',
    botName: botName || '',
    updatedAt: new Date().toISOString(),
  };

  // Tự động đồng bộ và lưu vào ottv2_bot_sources_map để các lần sau tải đúng
  try {
    if (botId !== undefined && botId !== null) map[String(botId)] = newEntry;
    if (botName) map[String(botName)] = newEntry;
    localStorage.setItem(BOT_SOURCES_MAP_KEY, JSON.stringify(map));
  } catch {}

  return newEntry;
}
