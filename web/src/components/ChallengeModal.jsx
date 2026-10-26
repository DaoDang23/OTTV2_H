import React, { useState, useEffect } from 'react';
import { calculateEloChange, CHESS_TIME_CONTROLS } from '../utils/gameRules.js';
import { filterActiveBots } from '../utils/botStorage.js';

const DEFAULT_USER_BOTS = [
  { id: 'bot_smart', name: 'MasterRPS_v2', language: 'Python', version: 'v2', status: 'READY', style: 'Cân bằng & Săn ô đích' },
  { id: 'bot_alpha', name: 'AlphaScissors', language: 'Python', version: 'v3', status: 'READY', style: 'Chuyên Kéo hung hãn' },
  { id: 'bot_tank', name: 'IronFist_CPP', language: 'C++', version: 'v1', status: 'READY', style: 'Búa thép thủ chắc phản công' },
  { id: 'bot_paper', name: 'NeuralPaper', language: 'Java', version: 'v1', status: 'READY', style: 'Bao áp đảo bọc lót' },
];

export const TIME_CONTROL_CONFIGS = {
  blitz_3_2: {
    id: '3+2',
    label: 'Chớp 3+2',
    initialSeconds: 180,
    incrementSeconds: 2,
    baseMs: 180000,
    incMs: 2000,
    name: '⚡ Chớp 3+2',
    desc: '03:00 tổng thời gian · +2s sau mỗi nước đi',
  },
  rapid_5_3: {
    id: '5+3',
    label: 'Nhanh 5+3',
    initialSeconds: 300,
    incrementSeconds: 3,
    baseMs: 300000,
    incMs: 3000,
    name: '🔥 Nhanh 5+3',
    desc: '05:00 tổng thời gian · +3s sau mỗi nước đi',
  },
  bullet_1_1: {
    id: '1+1',
    label: 'Siêu Chớp 1+1',
    initialSeconds: 60,
    incrementSeconds: 1,
    baseMs: 60000,
    incMs: 1000,
    name: '🚀 Siêu Chớp 1+1',
    desc: '01:00 tổng thời gian · +1s sau mỗi nước đi',
  },
};

export default function ChallengeModal({
  target,
  pvpElo = 1200,
  botElo = 1200,
  userElo = 1200,
  userBots = [],
  matchType = 'BOT', // 'PVP' | 'BOT'
  onClose,
  onConfirm,
}) {
  if (!target) return null;

  const isPvP = matchType === 'PVP' || target.isPvP;
  const activeRating = isPvP ? (pvpElo || userElo) : (botElo || userElo);

  // Selected time control for PvP mode
  const [selectedTimeControlKey, setSelectedTimeControlKey] = useState('blitz_3_2');

  // Available active bots for Bot mode
  const activeUserBots = filterActiveBots(userBots);
  const availableBots =
    activeUserBots.length > 0
      ? activeUserBots.filter((b) => b.status === 'READY' || b.status === 'COMPILED' || !b.status)
      : DEFAULT_USER_BOTS;

  const [selectedBotId, setSelectedBotId] = useState(
    availableBots[0]?.id || DEFAULT_USER_BOTS[0].id
  );

  useEffect(() => {
    if (availableBots.length > 0 && !availableBots.some((b) => String(b.id) === String(selectedBotId))) {
      setSelectedBotId(availableBots[0].id);
    }
  }, [availableBots.length]);

  const selectedBot =
    availableBots.find((b) => String(b.id) === String(selectedBotId)) ||
    availableBots[0] ||
    DEFAULT_USER_BOTS[0];

  const getBotStyle = (bot) => {
    if (bot?.style) return bot.style;
    const name = bot?.name || '';
    if (name.includes('Sprinter') || name === 'VIP_ShadowSprinter') return '🎯 Tốc biến đua đích & Lách né khắc chế';
    if (name.includes('Predator') || name === 'VIP_ApexPredator') return '⚡ Săn lùng đồ sát & Diệt sạch quân địch';
    if (name.includes('Phalanx') || name === 'VIP_GrandmasterPhalanx') return '🛡️ Đội hình Phalanx tam giác bọc lót';
    return '⚔️ Tấn công cân bằng & Kiểm soát bàn cờ';
  };

  const winElo = calculateEloChange(activeRating, target.elo, true);
  const loseElo = calculateEloChange(activeRating, target.elo, false);

  const opponentStyle =
    target.algorithm ||
    target.description ||
    target.playstyle ||
    (target.elo > 1900
      ? '🎯 Săn Ô Đích Aggressive & Ép Góc'
      : target.elo > 1600
      ? '⚡ Tấn Công Tầm Gần & Đổi Quân Lợi Thế'
      : '🛡️ Phòng Thủ Phản Công & Giữ Vùng');

  const handleConfirmAction = () => {
    if (isPvP) {
      const selectedTimeControl =
        TIME_CONTROL_CONFIGS[selectedTimeControlKey] || TIME_CONTROL_CONFIGS.blitz_3_2;
      onConfirm?.(target, {
        matchType: 'PVP',
        timeControl: selectedTimeControl,
      });
    } else {
      onConfirm?.(target, {
        matchType: 'BOT',
        bot: selectedBot,
      });
    }
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-4 animate-fadeIn">
      <div className="bg-zinc-850 border border-zinc-700 w-full max-w-lg rounded-3xl p-6 shadow-2xl space-y-5 text-center">
        {/* Header Icon */}
        <div className={`w-14 h-14 rounded-2xl flex items-center justify-center mx-auto shadow-lg text-3xl ${
          isPvP
            ? 'bg-blue-500/20 text-blue-400 border border-blue-500/40 shadow-blue-950/40'
            : 'bg-rose-500/20 text-rose-400 border border-rose-500/40 shadow-rose-950/40'
        }`}>
          {isPvP ? '⚔️' : '🤖'}
        </div>

        <div>
          <span className={`text-[10px] font-mono uppercase font-black px-2.5 py-0.5 rounded-full border ${
            isPvP
              ? 'text-blue-400 bg-blue-500/10 border-blue-500/30'
              : 'text-emerald-400 bg-emerald-500/10 border-emerald-500/30'
          }`}>
            {isPvP ? 'ĐẤU TRƯỜNG PVP TRỰC TIẾP' : 'ĐẤU TẬP LUYỆN VỚI BOT (GIAO HỮU)'}
          </span>
          <h3 className="text-xl font-black text-white mt-1">
            {isPvP ? 'Vào Trận Đối Kháng Trực Tiếp' : 'Xác Nhận Đấu Tập Luyện Với Bot'}
          </h3>
          <p className="text-xs text-zinc-400 mt-1">
            {isPvP
              ? `Bạn sẽ tự cầm quân đối đầu với ${target.name} (${target.elo} ELO).`
              : `Bot của bạn sẽ tự động thi đấu tập luyện với ${target.name} (${target.elo} ELO). Trận đấu này là chế độ Giao Hữu (Practice), không làm thay đổi điểm ELO Ranked.`}
          </p>
        </div>

        <div className="space-y-3 text-left">
          {/* TRƯỜNG HỢP 1: Chế độ PvP -> Chọn Thể Thức Thời Gian Cờ Vua (Fischer Clock) */}
          {isPvP ? (
            <div className="bg-zinc-900/90 p-4 rounded-2xl border border-zinc-800 space-y-3">
              <label className="text-xs font-bold text-blue-400 flex items-center justify-between">
                <span>⏱️ Chọn Thể Thức Thời Gian (Fischer Chess Clock):</span>
                <span className="text-[10px] font-mono text-zinc-500">Chuẩn FIDE</span>
              </label>

              <div className="grid grid-cols-3 gap-2">
                {Object.entries(TIME_CONTROL_CONFIGS).map(([key, tc]) => {
                  const isSelected = selectedTimeControlKey === key;
                  return (
                    <button
                      key={key}
                      type="button"
                      onClick={() => setSelectedTimeControlKey(key)}
                      className={`p-2.5 rounded-xl border text-left transition-all ${
                        isSelected
                          ? 'bg-blue-950/60 border-blue-500 text-white shadow-md'
                          : 'bg-zinc-950 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                      }`}
                    >
                      <span className="text-xs font-bold block">{tc.label}</span>
                      <span className="text-[10px] font-mono text-zinc-400 block mt-0.5">{tc.desc}</span>
                    </button>
                  );
                })}
              </div>

              <div className="bg-zinc-950/80 p-2.5 rounded-xl border border-zinc-800/80 text-[11px] text-zinc-400 space-y-1">
                <p className="text-zinc-300 font-semibold">Quy tắc đồng hồ Fischer:</p>
                <p>• Đồng hồ của bên nào chạm mốc <strong className="text-rose-400">00:00.0</strong> trước sẽ bị xử thua do hết giờ (Timeout).</p>
                <p>• Dưới 20 giây sẽ hiển thị mili-giây dạng <span className="font-mono text-amber-400">00:14.7</span> giống Chess.com.</p>
              </div>
            </div>
          ) : (
            /* TRƯỜNG HỢP 2: Chế độ Bot -> Chọn Bot Của Bản Thân */
            <div className="bg-zinc-900/90 p-4 rounded-2xl border border-zinc-800 space-y-2.5">
              <label className="text-xs font-bold text-emerald-400 flex items-center justify-between">
                <span>🤖 Chọn Bot Thi Đấu Của Bạn:</span>
                <span className="text-[10px] font-mono text-zinc-500">Đã biên dịch</span>
              </label>

              <select
                value={selectedBotId}
                onChange={(e) => setSelectedBotId(e.target.value)}
                className="w-full bg-zinc-950 border border-zinc-700 rounded-xl px-3.5 py-2.5 text-xs text-white focus:outline-none focus:border-emerald-500 font-mono tracking-wide"
              >
                {availableBots.map((bot) => (
                  <option key={bot.id} value={bot.id}>
                    {bot.name} ({bot.language || 'Python'} - {bot.version || 'v1'})
                  </option>
                ))}
              </select>

              <div className="flex items-center justify-between text-[11px] text-zinc-400 bg-zinc-950/60 p-2 rounded-xl border border-zinc-800/80">
                <span>Chiến thuật:</span>
                <span className="text-zinc-200 font-medium">{getBotStyle(selectedBot)}</span>
              </div>
            </div>
          )}

          {/* Thông tin Đối Thủ & Kèo Elo */}
          <div className="bg-zinc-900/90 p-4 rounded-2xl border border-zinc-800 text-xs text-zinc-300 font-mono space-y-2">
            <div className="flex justify-between">
              <span className="text-zinc-500">Đối thủ:</span>
              <span className="font-bold text-white">{target.name}</span>
            </div>
            {!isPvP && (
              <div className="flex justify-between">
                <span className="text-zinc-500">Bot đối thủ:</span>
                <span className="text-amber-400 font-bold">{target.botName || target.bot || 'TargetBot'}</span>
              </div>
            )}
            <div className="flex justify-between">
              <span className="text-zinc-500">Tỉ lệ thắng đối thủ:</span>
              <span className="text-zinc-200">{target.winRate || 68}% ({target.matches || 85} trận)</span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">Phong cách đánh:</span>
              <span className="text-rose-400 font-medium">{opponentStyle}</span>
            </div>

            {isPvP ? (
              <>
                <div className="border-t border-zinc-800 pt-2 flex justify-between">
                  <span className="text-zinc-400">Nếu thắng:</span>
                  <span className="font-bold text-emerald-400">+{winElo.change} ELO ({winElo.newElo} ELO)</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-zinc-400">Nếu thua:</span>
                  <span className="font-bold text-rose-400">{loseElo.change} ELO ({loseElo.newElo} ELO)</span>
                </div>
              </>
            ) : (
              <div className="border-t border-zinc-800 pt-2 flex items-center justify-between text-amber-300">
                <span>Tính chất trận đấu:</span>
                <span className="font-bold flex items-center gap-1">
                  <span>⚔️</span>
                  <span>Tập Luyện Giao Hữu (±0 ELO Ranked)</span>
                </span>
              </div>
            )}
          </div>
        </div>

        {/* Buttons */}
        <div className="flex gap-3 pt-1">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 py-3 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 font-bold text-xs border border-zinc-700 transition-colors"
          >
            Hủy Bỏ
          </button>
          <button
            type="button"
            onClick={handleConfirmAction}
            className={`flex-1 py-3 rounded-xl text-white font-bold text-xs shadow-lg transition-all flex items-center justify-center gap-2 hover:scale-[1.02] ${
              isPvP
                ? 'bg-blue-600 hover:bg-blue-500 shadow-blue-950/60'
                : 'bg-emerald-600 hover:bg-emerald-500 shadow-emerald-950/60'
            }`}
          >
            <span>⚔️</span>
            <span>{isPvP ? 'Vào Bàn Đấu PvP' : 'Vào Đấu Tập Luyện'}</span>
          </button>
        </div>
      </div>
    </div>
  );
}
