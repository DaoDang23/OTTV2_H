import React, { useState, useEffect } from 'react';
import { filterActiveBots, getBotSource, saveBotSource } from '../utils/botStorage.js';
import { api, buildApiUrl } from '../utils/api.js';

const STARTER_CODE = {
  PYTHON: `import json\nimport sys\n\ndef decide(state):\n    # Return {"pieceId": int, "to": [row, col]} for a legal move.\n    pieces = state.get("yourPieces", [])\n    if not pieces:\n        return None\n    piece = pieces[0]\n    r, c = piece["r"], piece["c"]\n    # Replace this example with your strategy and legal-move checks.\n    target = state.get("targets", [[0, 8]])[0]\n    nr = r + (1 if target[0] > r else -1 if target[0] < r else 0)\n    nc = c + (1 if target[1] > c else -1 if target[1] < c else 0)\n    return {"pieceId": piece["id"], "to": [nr, nc]}\n\nfor line in sys.stdin:\n    try:\n        print(json.dumps(decide(json.loads(line))), flush=True)\n    except Exception as error:\n        print(str(error), file=sys.stderr, flush=True)\n        print("null", flush=True)`,
  CPP: `#include <iostream>\n#include <string>\n#include <nlohmann/json.hpp>\nusing json = nlohmann::json;\n\nint main() {\n    std::string line;\n    while (std::getline(std::cin, line)) {\n        try {\n            const json state = json::parse(line);\n            const auto pieces = state.value("yourPieces", json::array());\n            if (pieces.empty()) { std::cout << "null\\n" << std::flush; continue; }\n            const auto piece = pieces[0];\n            const int r = piece.at("r").get<int>();\n            const int c = piece.at("c").get<int>();\n            const auto targets = state.value("targets", json::array({json::array({0, 8})}));\n            const int tr = targets[0][0].get<int>(), tc = targets[0][1].get<int>();\n            const int nr = r + (tr > r ? 1 : tr < r ? -1 : 0);\n            const int nc = c + (tc > c ? 1 : tc < c ? -1 : 0);\n            json move = {{"pieceId", piece.at("id")}, {"to", {nr, nc}}};\n            std::cout << move.dump() << '\\n' << std::flush;\n        } catch (const std::exception& e) {\n            std::cerr << e.what() << std::endl;\n            std::cout << "null\\n" << std::flush;\n        }\n    }\n    return 0;\n}`,
  JAVA: `import com.fasterxml.jackson.databind.JsonNode;\nimport com.fasterxml.jackson.databind.ObjectMapper;\nimport com.fasterxml.jackson.databind.node.ObjectNode;\nimport java.io.BufferedReader;\nimport java.io.InputStreamReader;\n\npublic final class Bot {\n    private static final ObjectMapper JSON = new ObjectMapper();\n    private static JsonNode decide(JsonNode state) {\n        JsonNode pieces = state.path("yourPieces");\n        if (!pieces.isArray() || pieces.isEmpty()) return JSON.getNodeFactory().nullNode();\n        JsonNode piece = pieces.get(0);\n        int r = piece.path("r").asInt(), c = piece.path("c").asInt();\n        JsonNode target = state.path("targets").path(0);\n        int tr = target.path(0).asInt(0), tc = target.path(1).asInt(8);\n        ObjectNode move = JSON.createObjectNode();\n        move.put("pieceId", piece.path("id").asInt());\n        move.putArray("to").add(r + Integer.compare(tr, r)).add(c + Integer.compare(tc, c));\n        return move;\n    }\n    public static void main(String[] args) throws Exception {\n        try (var in = new BufferedReader(new InputStreamReader(System.in))) {\n            String line;\n            while ((line = in.readLine()) != null) {\n                try { System.out.println(JSON.writeValueAsString(decide(JSON.readTree(line)))); }\n                catch (Exception e) { System.err.println(e.getMessage()); System.out.println("null"); }\n                System.out.flush();\n            }\n        }\n    }\n}`,
};

function syntaxWarnings(language, source) {
  const text = source || '';
  if (language === 'CPP') {
    if (/\bdef\s+|\bimport\s+sys\b|\bprint\s*\(/.test(text)) return ['Bạn đang chọn C++ nhưng mã nguồn có dấu hiệu là Python!'];
    const warnings = [];
    if (!/#\s*include\b/.test(text)) warnings.push('C++ cần khai báo thư viện bằng #include.');
    if (!/\bint\s+main\s*\(/.test(text)) warnings.push('C++ cần có hàm int main().');
    return warnings;
  }
  if (language === 'JAVA') {
    if (/\bdef\s+|\bimport\s+sys\b|\bprint\s*\(|#\s*include\b|\busing\s+namespace\b/.test(text)) return ['Bạn đang chọn Java nhưng mã nguồn có dấu hiệu là Python hoặc C++!'];
    const warnings = [];
    if (!/\bclass\s+\w+/.test(text)) warnings.push('Java cần khai báo class.');
    if (!/public\s+static\s+void\s+main\s*\(/.test(text)) warnings.push('Java cần có public static void main(String[] args).');
    if (!/\bclass\s+Bot\b/.test(text)) warnings.push('Tên class chính cần là Bot để khớp với file Bot.java.');
    return warnings;
  }
  const warnings = [];
  if (!/\bdef\s+decide\s*\(/.test(text)) warnings.push('Python cần định nghĩa def decide(state).');
  if (!/\bsys\.stdin\b/.test(text)) warnings.push('Python cần đọc các dòng JSON từ sys.stdin.');
  if (/\bint\s+main\s*\(|#\s*include\b/.test(text)) warnings.push('Bạn đang chọn Python nhưng mã nguồn có dấu hiệu là C++!');
  if (/public\s+static\s+void\s+main|\bclass\s+Bot\b/.test(text)) warnings.push('Bạn đang chọn Python nhưng mã nguồn có dấu hiệu là Java!');
  return warnings;
}

const MOCK_CODE_TEMPLATES = {
  PYTHON: `import json
import sys

def decide(state):
    # Lấy tọa độ đích của phe mình (I1 [0, 8] cho Đội Xanh, A9 [8, 0] cho Đội Đỏ)
    targets = state.get("targets", [[0, 8]])
    target = targets[0] if targets else [0, 8]
    your_pieces = state.get("yourPieces", [])
    
    if not your_pieces:
        return None
        
    # Tập hợp các ô bị chặn (quân mình + chướng ngại vật)
    blocked = {(p["r"], p["c"]) for p in your_pieces}
    for cell in state.get("visibleCells", state.get("cells", [])):
        if cell.get("type") in ("OBSTACLE", "TRAP"):
            blocked.add((cell.get("r"), cell.get("c")))
    for ob in state.get("obstacles", []):
        if isinstance(ob, list) and len(ob) == 2:
            blocked.add((ob[0], ob[1]))

    # Duyệt các quân để tìm nước đi hợp lệ tiến về ô đích
    for p in your_pieces:
        r, c = p["r"], p["c"]
        candidates = []
        if r != target[0]:
            candidates.append((r + (1 if target[0] > r else -1), c))
        if c != target[1]:
            candidates.append((r, c + (1 if target[1] > c else -1)))
        for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            candidates.append((r + dr, c + dc))

        for nr, nc in candidates:
            if 0 <= nr < 9 and 0 <= nc < 9 and (nr, nc) not in blocked:
                return {"pieceId": p["id"], "to": [nr, nc]}

    return None

for line in sys.stdin:
    try:
        state = json.loads(line)
        print(json.dumps(decide(state)), flush=True)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr, flush=True)
        break`,
  JAVA: `import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class Bot {
    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode decide(JsonNode state) {
        JsonNode piece = state.path("yourPieces").path(0);
        if (piece.isMissingNode()) return null;
        
        int r = piece.path("r").asInt();
        int c = piece.path("c").asInt();
        
        ObjectNode move = JSON.createObjectNode();
        move.put("pieceId", piece.path("id").asInt());
        move.putArray("to").add(r > 0 ? r - 1 : r + 1).add(c);
        return move;
    }

    public static void main(String[] args) throws Exception {
        try (var in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(JSON.writeValueAsString(decide(JSON.readTree(line))));
                System.out.flush();
            }
        }
    }
}`,
  CPP: `#include <iostream>
#include <string>
#include <nlohmann/json.hpp>
using json = nlohmann::json;

int main() {
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        try {
            json state = json::parse(line);
            // Ví dụ: Đọc thời gian còn lại
            // int timeLeftMs = state.value("timeLeftMs", 180000);
            
            auto myPieces = state["yourPieces"];
            if (myPieces.empty()) {
                std::cout << "{\"pieceId\": -1, \"to\": [0,0]}\n";
            } else {
                int pId = myPieces[0]["id"];
                int r = myPieces[0]["r"];
                int c = myPieces[0]["c"];
                std::cout << "{\"pieceId\": " << pId << ", \"to\": [" << r << "," << c+1 << "]}\n";
            }
        } catch (...) {
            std::cout << "{\"pieceId\": -1, \"to\": [0,0]}\n";
        }
        std::cout << std::flush;
    }
    return 0;
}`
};

export default function BattleCode({
  bots = [],
  submissions = [],
  onUploadSubmission,
  onChallenge,
  user,
  botElo = 1200,
  userElo = 1200,
  onDeleteBot,
  onCleanBots,
  onToggleLadderActive,
}) {
  const activeBots = filterActiveBots(bots);
  const MAX_BOTS = 5;
  const isLimitReached = activeBots.length >= MAX_BOTS;
  const selectedRankedBot = activeBots.find((b) => b.ladderEnabled) || null;
  const [language, setLanguage] = useState('PYTHON');
  const [code, setCode] = useState(MOCK_CODE_TEMPLATES.PYTHON);
  const [botName, setBotName] = useState('');
  const [selectedBotId, setSelectedBotId] = useState(() => (activeBots.length >= 5 && activeBots[0]?.id ? activeBots[0].id : '__new__'));
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');
  const [compileLog, setCompileLog] = useState('');
  const [showCompileSuccess, setShowCompileSuccess] = useState(false);
  const [showCompileError, setShowCompileError] = useState(false);
  const [showIoGuide, setShowIoGuide] = useState(false);
  const [rightTab, setRightTab] = useState('LEADERBOARD'); // 'LEADERBOARD' | 'MY_BOTS'
  const [ladderRankings, setLadderRankings] = useState([]);

  useEffect(() => {
    let isMounted = true;
    const loadRankings = async () => {
      try {
        const response = await fetch(buildApiUrl('/api/ladder/rankings?limit=500'), {
          credentials: 'include',
          cache: 'no-store',
          headers: { 'Content-Type': 'application/json', 'ngrok-skip-browser-warning': 'true' },
        });
        if (!response.ok) return;
        const rows = await response.json();
        if (isMounted && Array.isArray(rows)) setLadderRankings(rows);
      } catch {
        // The panel can still show the selected bot as unranked if the ladder API is unavailable.
      }
    };
    loadRankings();
    return () => { isMounted = false; };
  }, [selectedRankedBot?.id]);

  const handleSelectBot = (botId) => {
    setSelectedBotId(botId);
    if (botId === '__new__') {
      setBotName('');
      setCode(MOCK_CODE_TEMPLATES[language] || MOCK_CODE_TEMPLATES.PYTHON);
      setStatusMessage('Đã chọn tạo Bot mới. Hãy nhập tên Bot và soạn thảo chiến thuật!');
      return;
    }
    const targetBot = activeBots.find((b) => String(b.id) === String(botId));
    if (targetBot) {
      setBotName(targetBot.name);
      const sourceEntry = getBotSource(targetBot.id, targetBot.name);
      setCode(sourceEntry.code);
      if (sourceEntry.language) {
        setLanguage(sourceEntry.language);
      }
      setStatusMessage(`Đã nạp mã nguồn của bot [${targetBot.name}] (${sourceEntry.code.length} bytes).`);
    }
  };

  // Tự động tải mã nguồn của bot khi selectedBotId thay đổi hoặc khi danh sách bot nạp xong
  useEffect(() => {
    if (selectedBotId !== '__new__') {
      const targetBot = activeBots.find((b) => String(b.id) === String(selectedBotId));
      if (targetBot) {
        setBotName(targetBot.name);
        const sourceEntry = getBotSource(targetBot.id, targetBot.name);
        setCode(sourceEntry.code);
        if (sourceEntry.language) {
          setLanguage(sourceEntry.language);
        }
      }
    } else if (activeBots.length >= 5 && activeBots[0]?.id) {
      handleSelectBot(activeBots[0].id);
    }
  }, [selectedBotId, activeBots.length]);

  const officialRankings = ladderRankings
    .filter((row) => !(row.isProvisional ?? row.provisional ?? true))
    .sort((a, b) => (b.botElo ?? b.elo ?? 1200) - (a.botElo ?? a.elo ?? 1200))
    .map((row, index) => {
      const isMine = (selectedRankedBot && String(row.botId) === String(selectedRankedBot.id))
        || activeBots.some((b) => String(b.id) === String(row.botId))
        || (user?.id && row.ownerId === user.id);
      return {
        rank: index + 1,
        botId: row.botId,
        ownerId: row.ownerId,
        ownerName: row.ownerDisplayName || row.ownerUsername || `Chủ bot ${row.ownerId}`,
        name: row.ownerDisplayName || row.ownerUsername || `Chủ bot ${row.ownerId}`,
        botName: row.botName || `Bot #${row.botId}`,
        bot: row.botName || `Bot #${row.botId}`,
        language: row.language,
        elo: row.botElo ?? row.elo ?? 1200,
        avatarSeed: row.botName || `Bot_${row.botId}`,
        isMine,
      };
    });
  const selectedRankedEntry = selectedRankedBot
    ? ladderRankings.find((row) => String(row.botId) === String(selectedRankedBot.id))
    : null;
  const selectedOfficialRank = selectedRankedEntry && !(selectedRankedEntry.isProvisional ?? selectedRankedEntry.provisional ?? true)
    ? officialRankings.find((row) => String(row.botId) === String(selectedRankedBot.id))
    : null;
  const adjacentOfficialBot = selectedOfficialRank
    ? officialRankings.find((row) => row.rank === selectedOfficialRank.rank - 1)
    : officialRankings[officialRankings.length - 1];
  const ownOfficialRank = selectedOfficialRank?.rank ?? null;
  const miniRankings = [
    ...officialRankings.slice(0, 5),
    ...(adjacentOfficialBot && !officialRankings.slice(0, 5).some((row) => String(row.botId) === String(adjacentOfficialBot.botId))
      ? [adjacentOfficialBot]
      : []),
  ].map((row) => ({ ...row, key: `ranked-${row.botId}` }));
  const ownRankedBot = selectedRankedBot;
  const isOwnBotInMiniList = ownRankedBot && miniRankings.some(
    (row) => String(row.botId) === String(ownRankedBot.id) || row.isMine
  );

  const handleLanguageChange = (lang) => {
    setLanguage(lang);
    if (selectedBotId === '__new__') {
      setCode(MOCK_CODE_TEMPLATES[lang] || '');
    }
  };

  const handleFileUpload = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      setCode(event.target?.result || '');
    };
    reader.readAsText(file);
  };

  const handleSubmit = async () => {
    if (selectedBotId === '__new__' && isLimitReached) {
      setStatusMessage('⚠️ Bạn đã đạt giới hạn tối đa 5 Bot. Vui lòng xóa bớt các bot cũ hoặc chọn Cập nhật phiên bản trên bot hiện có!');
      return;
    }
    if (selectedBotId === '__new__') {
      const trimmed = botName.trim();
      if (!trimmed || trimmed.length < 3 || trimmed.length > 20) {
        setStatusMessage('⚠️ Tên bot phải từ 3 đến 20 ký tự.');
        return;
      }
      if (!/^[a-zA-Z0-9_-]+$/.test(trimmed)) {
        setStatusMessage('⚠️ Tên bot chỉ chứa chữ cái, số, dấu gạch dưới (_) hoặc gạch ngang (-).');
        return;
      }
    }
    setIsSubmitting(true);
    setStatusMessage('');
    try {
      const targetName = selectedBotId === '__new__'
        ? botName
        : (activeBots.find((b) => String(b.id) === String(selectedBotId))?.name || botName);

      // Lưu mã nguồn vào localStorage key 'ottv2_bot_sources_map'
      saveBotSource(selectedBotId, targetName, code, language);

      if (onUploadSubmission) {
        const submitted = await onUploadSubmission({
          botId: selectedBotId,
          newBotName: botName,
          language,
          source: code,
        });
        if (!submitted?.botId || !submitted?.submissionId) {
          throw new Error('Máy chủ chưa trả về mã phiên biên dịch để theo dõi.');
        }
        setStatusMessage(`Đã nộp mã nguồn [${targetName}]. Đang chờ kết quả biên dịch...`);
        let finalSubmission = submitted.submission;
        for (let attempt = 0; attempt < 10; attempt += 1) {
          if (attempt > 0) await new Promise((resolve) => setTimeout(resolve, 1500));
          const response = await api(`/api/bots/${submitted.botId}/submissions/${submitted.submissionId}`, {
            cache: 'no-store',
            headers: { 'Cache-Control': 'no-cache' },
          });
          if (!response) throw new Error('Không đọc được trạng thái biên dịch từ máy chủ.');
          finalSubmission = await response.json();
          if (finalSubmission.status === 'COMPILED') {
            setStatusMessage('✅ Bot đã được biên dịch thành công và sẵn sàng thi đấu!');
            setShowCompileSuccess(true);
            setTimeout(() => setShowCompileSuccess(false), 5000);
            return;
          }
          if (finalSubmission.status === 'COMPILE_ERROR') {
            setCompileLog(finalSubmission.compileLog || 'Compiler trả về lỗi nhưng không có nội dung log.');
            setShowCompileError(true);
            setStatusMessage('❌ Biên dịch thất bại. Xem log chi tiết.');
            return;
          }
        }
        setStatusMessage(`Máy chủ vẫn đang biên dịch phiên #${submitted.submissionId}. Hãy thử tải lại danh sách phiên bản sau ít phút.`);
      } else {
        setTimeout(() => {
          setIsSubmitting(false);
          setStatusMessage(`Biên dịch thành công (Mock)! Bot [${targetName}] đã sẵn sàng thi đấu.`);
        }, 1200);
      }
    } catch (err) {
      setStatusMessage('Lỗi: ' + (err.message || 'Không thể nộp bot'));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 min-h-[calc(100vh-6rem)] animate-fadeIn">
      {/* CỘT TRÁI (60% ~ 7 cols): Trình Soạn Thảo & Nộp Mã Nguồn */}
      <div className="lg:col-span-7 flex flex-col bg-zinc-850 border border-zinc-750 rounded-3xl overflow-hidden shadow-2xl">
        {/* Editor Toolbar Header */}
        <div className="flex flex-wrap items-center justify-between px-6 py-4 bg-zinc-900 border-b border-zinc-750 gap-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-lg">
              💻
            </div>
            <div>
              <h3 className="font-bold text-white text-sm">Bot Code Editor & Upload</h3>
              <p className="text-[11px] text-zinc-400">Khung thời gian: 3 Phút Tổng + 1s/turn</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <div className="flex bg-zinc-950 p-1 rounded-xl border border-zinc-800">
              {['PYTHON', 'JAVA', 'CPP'].map((lang) => (
                <button
                  key={lang}
                  type="button"
                  onClick={() => handleLanguageChange(lang)}
                  className={`px-3 py-1 rounded-lg text-xs font-bold transition-all ${
                    language === lang
                      ? 'bg-emerald-600 text-white shadow'
                      : 'bg-transparent text-zinc-400 hover:text-white'
                  }`}
                >
                  {lang === 'CPP' ? 'C++' : lang}
                </button>
              ))}
            </div>

            <label className="cursor-pointer px-3 py-1.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-xs font-semibold text-zinc-200 border border-zinc-700 transition-colors">
              📁 Tải File Lên
              <input
                type="file"
                className="hidden"
                accept={language === 'PYTHON' ? '.py' : language === 'CPP' ? '.cpp,.cc,.cxx,.h,.hpp' : '.java'}
                onChange={handleFileUpload}
              />
            </label>
          </div>
        </div>

        {/* Bộ đếm & Cảnh báo giới hạn MAX_BOTS = 5 */}
        <div className="px-6 py-2.5 bg-zinc-900/90 border-b border-zinc-800 flex flex-wrap items-center justify-between gap-3 text-xs">
          <div className="flex items-center gap-2">
            <span className="text-zinc-400 font-semibold">Số lượng Bot sở hữu:</span>
            <span className={`font-mono font-bold px-2.5 py-0.5 rounded-full text-xs ${
              isLimitReached
                ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                : 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
            }`}>
              {activeBots.length} / {MAX_BOTS} Bot
            </span>
          </div>

          <div className="flex items-center gap-2">
            {activeBots.length > MAX_BOTS && onCleanBots && (
              <button
                type="button"
                onClick={onCleanBots}
                className="px-2.5 py-1 rounded-lg bg-amber-500/20 hover:bg-amber-500/30 text-amber-200 border border-amber-500/40 font-semibold text-[11px] transition-all flex items-center gap-1"
              >
                <span>🧹</span> Giữ lại 5 Bot mới nhất & Xóa các Bot thừa
              </button>
            )}
            <button
              type="button"
              onClick={() => setRightTab('MY_BOTS')}
              className="px-2.5 py-1 rounded-lg bg-zinc-800 hover:bg-zinc-750 text-zinc-300 border border-zinc-700 font-semibold text-[11px] transition-all"
            >
              Quản lý Bot ({activeBots.length})
            </button>
          </div>
        </div>

        {isLimitReached && (
          <div className="px-6 py-2.5 bg-amber-950/40 border-b border-amber-500/30 text-amber-200 text-xs flex items-center gap-2">
            <span className="text-sm">⚠️</span>
            <span>
              <strong>Bạn đã đạt giới hạn tối đa 5 Bot.</strong> Vui lòng xóa bớt các bot cũ hoặc chọn Cập nhật phiên bản trên bot hiện có!
            </span>
          </div>
        )}

        {/* Cấu hình Bot (Tên bot & Lựa chọn) */}
        <div className="px-6 py-3 bg-zinc-900/60 border-b border-zinc-800/80 flex flex-wrap items-center gap-4 text-xs">
          <div className="flex items-center gap-2">
            <span className="text-zinc-400 font-semibold">Chọn Bot:</span>
            <select
              value={selectedBotId}
              onChange={(e) => handleSelectBot(e.target.value)}
              className="bg-zinc-950 border border-zinc-700 rounded-lg px-2.5 py-1 text-white focus:outline-none focus:border-emerald-500 font-medium"
            >
              <option value="__new__" disabled={isLimitReached}>
                {isLimitReached ? '+ Tạo Bot Mới (Đã đạt giới hạn 5/5)' : '+ Tạo Bot Mới'}
              </option>
              {activeBots.map((b) => (
                <option key={b.id} value={b.id}>{b.name}</option>
              ))}
            </select>
          </div>

          {selectedBotId === '__new__' && !isLimitReached && (
            <div className="flex items-center gap-2 flex-1 min-w-[240px]">
              <span className="text-zinc-400 font-semibold flex-shrink-0">Tên Bot:</span>
              <div className="relative flex-1 flex items-center">
                <input
                  type="text"
                  maxLength={20}
                  placeholder="Nhập tên bot (3-20 ký tự)"
                  value={botName}
                  onChange={(e) => {
                    const val = e.target.value;
                    if (val === '' || /^[a-zA-Z0-9_-]+$/.test(val)) {
                      setBotName(val);
                    }
                  }}
                  className={`w-full bg-zinc-950 border rounded-lg pl-2.5 pr-14 py-1 text-white focus:outline-none text-xs transition-colors ${
                    botName && botName.length < 3
                      ? 'border-amber-500/80 focus:border-amber-400'
                      : 'border-zinc-700 focus:border-emerald-500'
                  }`}
                />
                <span className={`absolute right-2 font-mono text-[10px] pointer-events-none ${
                  botName.length >= 20 ? 'text-amber-400 font-bold' : 'text-zinc-500'
                }`}>
                  ({botName.length}/20)
                </span>
              </div>
            </div>
          )}

          {selectedBotId !== '__new__' && (
            <div className="flex items-center gap-2 text-zinc-400 font-mono text-[11px]">
              <span>Đang chỉnh sửa:</span>
              <span className="text-emerald-400 font-bold font-sans">🤖 {botName}</span>
              <span className="text-zinc-500">(ID: {selectedBotId})</span>
            </div>
          )}
        </div>

        {/* Code Editor Body */}
        <div className="flex-1 relative bg-zinc-950 p-4 font-mono text-xs overflow-auto leading-relaxed min-h-[360px]">
          <textarea
            value={code}
            onChange={(e) => setCode(e.target.value)}
            spellCheck="false"
            className="w-full h-full min-h-[340px] bg-transparent text-emerald-300 resize-none outline-none font-mono text-xs selection:bg-emerald-800/50"
          />
        </div>

        {/* Status notice */}
        {statusMessage && (
          <div className="px-6 py-2 bg-zinc-900 border-t border-zinc-800 text-xs text-emerald-400 font-medium">
            {statusMessage}
          </div>
        )}

        {/* Action Footer Bar */}
        <div className="px-6 py-4 bg-zinc-900 border-t border-zinc-750 flex items-center justify-between">
          <span className="text-xs text-zinc-400 font-mono">
            {language === 'PYTHON' ? 'bot.py' : language === 'CPP' ? 'bot.cpp' : 'Bot.java'} · {code.length} bytes
          </span>

          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => {
                const warnings = syntaxWarnings(language, code);
                setStatusMessage(warnings.length
                  ? `⚠️ Kiểm tra nhanh phát hiện:\n• ${warnings.join('\n• ')}\nĐây chỉ là tiền kiểm tra; bạn vẫn có thể nộp để xem kết quả compiler.`
                  : '✓ Kiểm tra nhanh đạt: tìm thấy cấu trúc I/O và điểm vào cơ bản. Compiler vẫn là bước xác nhận cú pháp cuối cùng.');
              }}
              className="px-4 py-2 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 font-semibold text-xs border border-zinc-700 transition-colors"
            >
              Kiểm Tra Cú Pháp
            </button>
            <button
              type="button"
              onClick={() => setShowIoGuide(true)}
              className="px-4 py-2 rounded-xl bg-sky-600/20 hover:bg-sky-600/30 text-sky-200 font-semibold text-xs border border-sky-500/40 transition-colors"
            >
              📖 Hướng Dẫn Viết Bot
            </button>
            <button
              type="button"
              disabled={isSubmitting || (selectedBotId === '__new__' && (!botName || isLimitReached))}
              onClick={handleSubmit}
              className="px-6 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-bold text-xs tracking-wide transition-all shadow-md shadow-emerald-950/40 flex items-center gap-2"
            >
              {isSubmitting ? (
                <>
                  <span className="inline-block w-3 h-3 border-2 border-white/20 border-t-white rounded-full animate-spin"></span>
                  <span>Đang nộp...</span>
                </>
              ) : (
                <>
                  <span>🚀</span>
                  <span>Biên Dịch & Nộp Bot</span>
                </>
              )}
            </button>
          </div>
        </div>
      </div>

      {/* CỘT PHẢI (40% ~ 5 cols): Mini Leaderboard / Quản Lý Bot */}
      <div className="lg:col-span-5 flex flex-col bg-zinc-850 border border-zinc-750 rounded-3xl p-6 shadow-2xl">
        <div className="flex items-center justify-between pb-4 border-b border-zinc-800 gap-2">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setRightTab('MY_BOTS')}
              className={`px-3 py-1.5 rounded-xl font-bold text-xs transition-all flex items-center gap-1.5 ${
                rightTab === 'MY_BOTS'
                  ? 'bg-emerald-600 text-white shadow'
                  : 'text-zinc-400 hover:text-white bg-zinc-900 border border-zinc-800'
              }`}
            >
              <span>🤖</span> Bot Của Tôi ({activeBots.length}/{MAX_BOTS})
            </button>
            <button
              type="button"
              onClick={() => setRightTab('LEADERBOARD')}
              className={`px-3 py-1.5 rounded-xl font-bold text-xs transition-all flex items-center gap-1.5 ${
                rightTab === 'LEADERBOARD'
                  ? 'bg-emerald-600 text-white shadow'
                  : 'text-zinc-400 hover:text-white bg-zinc-900 border border-zinc-800'
              }`}
            >
              <span>🔥</span> Mini Rank
            </button>
          </div>
          <span className="text-xs font-mono font-bold px-2 py-0.5 rounded bg-zinc-900 border border-zinc-750 text-emerald-400">
            {rightTab === 'LEADERBOARD' ? 'Ladder' : `${activeBots.length} Bot`}
          </span>
        </div>

        {rightTab === 'MY_BOTS' ? (
          <div className="flex-1 flex flex-col pt-3 overflow-hidden">
            <div className="flex items-center justify-between mb-3 text-xs">
              <span className="text-zinc-400 font-semibold">Danh sách Bot sở hữu:</span>
              <span className="text-emerald-400 font-mono font-bold">{activeBots.length} / {MAX_BOTS}</span>
            </div>

            {activeBots.length > MAX_BOTS && onCleanBots && (
              <button
                type="button"
                onClick={onCleanBots}
                className="w-full mb-3 py-2 px-3 rounded-xl bg-amber-500/20 hover:bg-amber-500/30 text-amber-200 border border-amber-500/40 font-bold text-xs transition-all flex items-center justify-center gap-2"
              >
                <span>🧹</span> Giữ lại 5 Bot mới nhất & Xóa các Bot thừa
              </button>
            )}

            {activeBots.length === 0 ? (
              <div className="flex-1 flex items-center justify-center text-center p-6 text-zinc-500 text-xs">
                Bạn chưa có bot nào. Hãy đặt tên bot, viết code và bấm "Biên Dịch & Nộp Bot" để tạo bot mới!
              </div>
            ) : (
              <div className="flex-1 overflow-auto space-y-2.5 pr-1">
                {activeBots.map((b, idx) => (
                  <div
                    key={b.id}
                    className={`p-3 rounded-2xl border flex items-center justify-between gap-3 transition-all min-h-[68px] ${
                      selectedBotId == b.id
                        ? 'bg-emerald-950/40 border-emerald-500/50 shadow-[0_0_15px_rgba(16,185,129,0.12)]'
                        : 'bg-zinc-900/60 hover:bg-zinc-850/80 border-zinc-800 hover:border-zinc-750'
                    }`}
                  >
                    {/* Cột Trái: Thông tin Bot */}
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      {/* Dòng trên: #STT + Icon Bot + Tên Bot */}
                      <div className="flex items-center gap-1.5 min-w-0">
                        <span className="w-5 text-center font-bold text-xs text-zinc-500 flex-shrink-0">#{idx + 1}</span>
                        <span className="text-sm flex-shrink-0">🤖</span>
                        <span className="font-semibold text-sm text-white truncate max-w-[120px] sm:max-w-[150px]" title={b.name}>
                          {b.name}
                        </span>
                      </div>

                      {/* Dòng dưới: ELO Badge + Tag Ngôn ngữ + ID */}
                      <div className="flex items-center flex-wrap gap-1.5 mt-1 text-[11px] pl-6">
                        <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-amber-500/15 text-amber-300 border border-amber-500/30 font-mono font-bold text-[10px]">
                          <span>🏆</span>
                          <span>{b.elo ?? 1200} ELO</span>
                        </span>
                        <span className="inline-flex items-center px-1.5 py-0.5 rounded-md bg-zinc-800 text-zinc-300 border border-zinc-700 font-mono text-[10px] uppercase">
                          {b.language || 'PYTHON'}
                        </span>
                        <span className="text-zinc-500 font-mono text-[10px]">
                          ID: {b.id}
                        </span>
                      </div>
                    </div>

                    {/* Cột Phải: Cụm Nút bấm */}
                    <div className="flex items-center gap-2 flex-shrink-0">
                      <button
                        type="button"
                        onClick={() => onToggleLadderActive && onToggleLadderActive(b.id, !b.ladderEnabled)}
                        className={`min-w-[110px] h-7 px-2 py-1 rounded-lg text-xs font-bold border transition-colors flex items-center justify-center gap-1.5 flex-shrink-0 ${
                          b.ladderEnabled
                            ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40 hover:bg-emerald-500/30'
                            : 'bg-zinc-800 text-zinc-400 border-zinc-700 hover:text-white hover:bg-zinc-750'
                        }`}
                        title={b.ladderEnabled ? 'Bấm để tắt đấu Ranked' : 'Bấm để chọn bot này đi đấu Ranked'}
                      >
                        <span className={b.ladderEnabled ? 'text-emerald-400 font-black' : 'text-zinc-500'}>
                          {b.ladderEnabled ? '●' : '○'}
                        </span>
                        <span>{b.ladderEnabled ? 'Đang Đấu' : 'Vào Đấu'}</span>
                      </button>

                      <button
                        type="button"
                        onClick={() => handleSelectBot(b.id)}
                        className="h-7 px-2.5 rounded-lg bg-zinc-800 hover:bg-zinc-750 text-zinc-300 hover:text-white text-xs font-semibold border border-zinc-700 transition-colors flex items-center justify-center gap-1 flex-shrink-0"
                        title="Cập nhật phiên bản code cho bot này"
                      >
                        <span>✏️</span>
                        <span>Chọn</span>
                      </button>

                      {onDeleteBot && (
                        <button
                          type="button"
                          onClick={() => {
                            if (window.confirm(`Bạn có chắc muốn xóa bot "${b.name}"?`)) {
                              onDeleteBot(b.id);
                            }
                          }}
                          className="h-7 px-2.5 rounded-lg bg-rose-950/60 hover:bg-rose-900/80 text-rose-300 border border-rose-800/60 text-xs font-bold transition-colors flex items-center justify-center gap-1 flex-shrink-0"
                          title="Xóa bot"
                        >
                          <span>🗑️</span>
                          <span>Xóa</span>
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className="flex-1 flex flex-col pt-3 overflow-hidden">
            {/* Danh sách đối thủ Mini */}
            <div className="flex-1 overflow-auto space-y-2 pr-1">
              {miniRankings.map((target) => {
                const selectedElo = selectedRankedEntry?.botElo ?? selectedRankedEntry?.elo ?? ownRankedBot?.elo ?? botElo ?? userElo ?? 1200;
                const isUnlocked = target.elo <= selectedElo + 150;
                const isMine = target.isMine || (ownRankedBot && String(target.botId) === String(ownRankedBot.id));

                return (
                  <div
                    key={target.key}
                    className={`p-2.5 rounded-2xl border flex items-center justify-between transition-all group ${
                      isMine
                        ? 'bg-emerald-950/40 border-emerald-500/60 shadow-[0_0_15px_rgba(16,185,129,0.15)]'
                        : 'bg-zinc-900/60 hover:bg-zinc-800/80 border-zinc-800 hover:border-zinc-750'
                    }`}
                  >
                    <div className="flex items-center gap-2.5 min-w-0">
                      <span className={`w-5 text-center font-bold text-xs ${isMine ? 'text-emerald-400 font-black' : 'text-zinc-400'}`}>
                        #{target.rank}
                      </span>
                      <img
                        src={`https://api.dicebear.com/7.x/bottts/svg?seed=${target.avatarSeed || target.botName || target.botId || target.bot}`}
                        alt="bot avatar"
                        className={`w-9 h-9 rounded-xl bg-zinc-900 border p-0.5 flex-shrink-0 ${
                          isMine ? 'border-emerald-500/60 ring-1 ring-emerald-500/30' : 'border-zinc-750'
                        }`}
                      />
                      <div className="min-w-0 flex-1">
                        <p className="text-xs font-bold text-white flex items-center gap-1.5 truncate">
                          <span className="truncate">{target.botName || target.bot}</span>
                          {target.language && (
                            <span className="text-[9px] font-mono px-1.5 py-0.2 rounded bg-zinc-800 text-zinc-300 border border-zinc-700 font-semibold uppercase flex-shrink-0">
                              {target.language}
                            </span>
                          )}
                          {isMine && (
                            <span className="text-[9px] bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 px-1.5 py-0.2 rounded font-extrabold uppercase flex-shrink-0">
                              [BẠN]
                            </span>
                          )}
                        </p>
                        <p className="text-[10px] text-zinc-400 font-mono truncate">
                          Chủ sở hữu: <span className={isMine ? 'text-emerald-300 font-semibold' : 'text-zinc-300'}>{target.ownerName || target.name}</span>
                        </p>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 flex-shrink-0 ml-2">
                      <span className="w-10 text-right font-mono text-xs font-bold text-amber-400 tabular-nums">{target.elo}</span>
                      {isMine ? (
                        <span
                          className="w-7 h-7 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 flex items-center justify-center text-xs flex-shrink-0"
                          title="Bot của bạn"
                        >
                          ⭐
                        </span>
                      ) : isUnlocked ? (
                        <button
                          type="button"
                          onClick={() => onChallenge({ ...target, name: target.botName || target.bot, matchType: 'BOT', isPvP: false, isPractice: true })}
                          className="w-7 h-7 rounded-lg bg-zinc-800 hover:bg-emerald-600 text-zinc-400 hover:text-white transition-all text-xs flex items-center justify-center flex-shrink-0"
                          title="Đấu tập luyện với bot ngay (Chế độ Giao Hữu)"
                        >
                          ⚔️
                        </button>
                      ) : (
                        <button
                          type="button"
                          disabled
                          className="w-7 h-7 rounded-lg bg-zinc-900 text-zinc-600 cursor-not-allowed text-xs flex items-center justify-center flex-shrink-0"
                          title={`Cần tối thiểu ${target.elo - 150} ELO để mở khóa`}
                        >
                          🔒
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}

              {/* Chỉ hiển thị dòng chân trang khi bot của mình chưa có mặt trong danh sách (Unranked hoặc ngoài top) */}
              {ownRankedBot && !isOwnBotInMiniList && (
                <div className="p-2.5 rounded-2xl border flex items-center justify-between bg-emerald-950/40 border-emerald-500/50 shadow-[0_0_15px_rgba(16,185,129,0.12)]">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <span className="w-5 text-center font-bold text-xs text-emerald-400">
                      {ownOfficialRank ? `#${ownOfficialRank}` : '—'}
                    </span>
                    <img
                      src={`https://api.dicebear.com/7.x/bottts/svg?seed=${ownRankedBot.name || ownRankedBot.id}`}
                      alt="own bot avatar"
                      className="w-9 h-9 rounded-xl bg-zinc-900 border border-emerald-500/50 ring-1 ring-emerald-500/30 p-0.5 flex-shrink-0"
                    />
                    <div className="min-w-0 flex-1">
                      <p className="text-xs font-bold text-white flex items-center gap-1.5 truncate">
                        <span className="truncate">{ownRankedBot.name}</span>
                        {ownRankedBot.language && (
                          <span className="text-[9px] font-mono px-1.5 py-0.2 rounded bg-zinc-800 text-zinc-300 border border-zinc-700 font-semibold uppercase flex-shrink-0">
                            {ownRankedBot.language}
                          </span>
                        )}
                        <span className="text-[9px] bg-emerald-500 text-zinc-950 px-1.5 py-0.2 rounded font-black uppercase flex-shrink-0">
                          BẠN
                        </span>
                        {!ownOfficialRank && (
                          <span className="text-[9px] bg-amber-500/20 text-amber-300 border border-amber-500/40 px-1.5 py-0.2 rounded font-semibold uppercase flex-shrink-0">
                            UNRANK
                          </span>
                        )}
                      </p>
                      <p className="text-[10px] text-zinc-400 font-mono truncate">
                        Chủ sở hữu: <span className="text-emerald-300 font-semibold">{user?.displayName || user?.studentCode || 'Bạn'}</span>
                        {ownOfficialRank ? ` · Hạng #${ownOfficialRank}` : ' · Chưa đủ 10 trận hoặc 5 đối thủ'}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 flex-shrink-0 ml-2">
                    <span className="w-10 text-right font-mono text-xs font-bold text-amber-400 tabular-nums">
                      {selectedRankedEntry?.botElo ?? selectedRankedEntry?.elo ?? ownRankedBot.elo ?? 1200}
                    </span>
                    <span
                      className="w-7 h-7 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 flex items-center justify-center text-xs flex-shrink-0"
                      title="Bot của bạn"
                    >
                      ⭐
                    </span>
                  </div>
                </div>
              )}
              {!ownRankedBot && miniRankings.length === 0 && (
                <div className="flex-1 flex items-center justify-center text-center p-6 text-zinc-500 text-xs">
                  Chưa có bot xếp hạng chính thức. Bật “Vào Đấu Ranked” cho một bot để xem vị trí của bot tại đây.
                </div>
              )}
            </div>
          </div>
        )}
      </div>
      {showCompileError && (
        <div className="fixed inset-0 z-[120] bg-black/75 backdrop-blur-sm flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby="compile-error-title">
          <div className="w-full max-w-3xl max-h-[85vh] overflow-hidden rounded-2xl border border-red-500/50 bg-zinc-950 shadow-2xl flex flex-col">
            <div className="px-6 py-4 border-b border-red-500/30 flex items-center justify-between">
              <div><h2 id="compile-error-title" className="text-lg font-black text-red-300">Biên Dịch Thất Bại (Compilation Error)</h2><p className="text-xs text-zinc-400 mt-1">Log đầy đủ compiler để bạn xác định file, dòng và nguyên nhân.</p></div>
              <button type="button" onClick={() => setShowCompileError(false)} className="rounded-lg px-3 py-2 bg-zinc-800 text-zinc-200 hover:bg-zinc-700" aria-label="Đóng">✕</button>
            </div>
            <pre className="m-4 p-4 max-h-[60vh] overflow-auto rounded-xl border border-zinc-800 bg-black text-red-200 text-xs leading-relaxed font-mono whitespace-pre-wrap break-words">{compileLog}</pre>
            <div className="px-6 pb-5 flex justify-end"><button type="button" onClick={() => setShowCompileError(false)} className="px-5 py-2 rounded-lg bg-red-600 hover:bg-red-500 text-white font-bold text-sm">Đóng</button></div>
          </div>
        </div>
      )}
      {showCompileSuccess && (
        <div className="fixed bottom-6 right-6 z-[100] max-w-md rounded-xl border border-emerald-400/50 bg-emerald-950 px-5 py-4 text-sm font-bold text-emerald-200 shadow-2xl" role="status">
          ✅ Bot đã được biên dịch thành công và sẵn sàng thi đấu!
        </div>
      )}
      {showIoGuide && (
        <div className="fixed inset-0 z-[110] bg-black/75 backdrop-blur-sm flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby="io-guide-title">
          <div className="w-full max-w-3xl max-h-[88vh] overflow-hidden rounded-2xl border border-sky-500/40 bg-zinc-950 shadow-2xl flex flex-col">
            <div className="px-6 py-4 border-b border-zinc-800 flex items-center justify-between gap-4">
              <div><h2 id="io-guide-title" className="text-lg font-black text-white">📖 Hướng Dẫn & Quy Chuẩn Bot (I/O)</h2><p className="text-xs text-zinc-400 mt-1">Giao thức JSON Lines dùng khi chạy trận.</p></div>
              <button type="button" onClick={() => setShowIoGuide(false)} className="rounded-lg px-3 py-2 bg-zinc-800 text-zinc-200 hover:bg-zinc-700" aria-label="Đóng">✕</button>
            </div>
            <div className="overflow-y-auto p-6 space-y-5 text-sm text-zinc-300">
              <section><h3 className="font-bold text-sky-300 mb-1">1. Cơ chế giao tiếp</h3><p>Bot chạy liên tục trong suốt trận. Mỗi lượt server gửi một dòng JSON vào STDIN; bot phải đọc dòng đó và in đúng một dòng JSON nước đi ra STDOUT rồi flush. Gửi thông tin gỡ lỗi ra STDERR, tuyệt đối không in chữ thường vào STDOUT.</p></section>
              <section><h3 className="font-bold text-sky-300 mb-2">2. Input mẫu (các trường đúng theo runner)</h3><pre className="rounded-xl bg-black border border-zinc-800 p-4 overflow-auto text-xs text-emerald-200">{`{
  "turn": 1,
  "you": "A",
  "timeLeftMs": 180000,
  "yourPieces": [{"id": 1, "type": "ROCK", "r": 7, "c": 3}],
  "visibleEnemies": [],
  "visibleCells": [{"r": 5, "c": 3, "type": "EMPTY"}],
  "targets": [[0, 8]]
}`}</pre><p className="text-xs text-zinc-500 mt-2">Các alias <code>enemyPieces</code>, <code>opponentPieces</code>, <code>enemies</code>, <code>cells</code> cũng được runner hỗ trợ. Tọa độ hàng/cột chạy từ 0 đến 8.</p></section>
              <section><h3 className="font-bold text-sky-300 mb-2">3. Output bắt buộc</h3><pre className="rounded-xl bg-black border border-zinc-800 p-4 text-xs text-emerald-200">{`{"pieceId": 1, "to": [6, 3]}`}</pre><p className="text-xs text-zinc-500 mt-2"><code>pieceId</code> phải là ID quân của bạn; <code>to</code> có dạng <code>[row, column]</code>. Mỗi input cần đúng một output line hợp lệ.</p></section>
              <section className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-4"><h3 className="font-bold text-amber-200 mb-1">Lưu ý theo ngôn ngữ</h3><p className="text-xs">Python: <code>def decide(state)</code> và vòng lặp <code>sys.stdin</code>. C++: <code>int main()</code>, header JSON <code>nlohmann/json.hpp</code>. Java: file <code>Bot.java</code> phải có class <code>Bot</code> và <code>public static void main(String[] args)</code>; thư viện Jackson được cung cấp.</p></section>
            </div>
            <div className="px-6 py-4 border-t border-zinc-800 flex flex-wrap justify-end gap-3">
              <button type="button" onClick={() => { setCode(STARTER_CODE[language]); setShowIoGuide(false); setStatusMessage(`Đã nạp code khung ${language}. Hãy bổ sung kiểm tra nước đi hợp lệ trước khi thi đấu.`); }} className="px-5 py-2.5 rounded-xl bg-sky-600 hover:bg-sky-500 text-white font-bold text-sm">Nạp Code Mẫu ({language === 'CPP' ? 'C++' : language})</button>
              <button type="button" onClick={() => setShowIoGuide(false)} className="px-5 py-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-200 font-bold text-sm">Đóng</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
