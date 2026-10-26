import React, { useState, useEffect } from 'react';
import { api } from '../utils/api.js';

/**
 * Modal Chi Tiết Bot (BotDetailModal)
 * Hiển thị thông tin minh bạch, lịch sử thi đấu và độ tin cậy ELO của Bot.
 * Tuyệt đối bảo mật: Không rò rỉ mã nguồn hay private token.
 */
export default function BotDetailModal({
  botId,
  botData: initialBotData = null,
  currentUser = null,
  onClose,
  onOpenReplay = null,
}) {
  const [bot, setBot] = useState(initialBotData);
  const [isLoading, setIsLoading] = useState(!initialBotData?.recentMatches);
  const [error, setError] = useState(null);

  useEffect(() => {
    const id = botId || initialBotData?.id || initialBotData?.botId;
    if (!id) return;

    setIsLoading(true);
    setError(null);
    api(`/api/bots/${id}`)
      .then(async (res) => {
        if (res && res.ok) {
          const data = await res.json();
          setBot(data);
        } else {
          // Fallback if detail endpoint returns error or bot deleted
          if (!initialBotData) {
            setError('Không tìm thấy thông tin Bot này.');
          }
        }
      })
      .catch((err) => {
        console.warn('Lỗi khi tải chi tiết Bot:', err);
        if (!initialBotData) {
          setError(err?.message || 'Không thể kết nối máy chủ để lấy thông tin Bot.');
        }
      })
      .finally(() => {
        setIsLoading(false);
      });
  }, [botId, initialBotData?.id, initialBotData?.botId]);

  const getLangBadge = (lang) => {
    const upper = (lang || 'PYTHON').toUpperCase();
    if (upper === 'JAVA') {
      return { icon: '☕', label: 'Java', color: 'bg-orange-500/20 text-orange-300 border-orange-500/40' };
    }
    if (upper === 'CPP' || upper === 'C++') {
      return { icon: '⚡', label: 'C++', color: 'bg-blue-500/20 text-blue-300 border-blue-500/40' };
    }
    return { icon: '🐍', label: 'Python', color: 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40' };
  };

  const langInfo = getLangBadge(bot?.language);
  const isOwner = currentUser?.id && bot?.ownerId && String(currentUser.id) === String(bot.ownerId);

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-3 sm:p-4 animate-fadeIn">
      <div className="bg-zinc-900 border border-zinc-750 w-full max-w-xl max-h-[90vh] rounded-3xl shadow-2xl flex flex-col overflow-hidden text-zinc-100">
        {/* Header */}
        <div className="p-5 border-b border-zinc-800 bg-zinc-900/90 flex items-center justify-between gap-3 flex-shrink-0">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-12 h-12 rounded-2xl bg-zinc-800 border border-zinc-700 flex items-center justify-center text-2xl flex-shrink-0">
              🤖
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h3 className="text-lg font-black text-white truncate">
                  {bot?.name || bot?.botName || 'Chi Tiết Bot'}
                </h3>
                {isOwner && (
                  <span className="text-[10px] bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 px-1.5 py-0.5 rounded font-black">
                    BẠN SỞ HỮU
                  </span>
                )}
              </div>
              <p className="text-xs text-zinc-400 truncate">
                Chủ sở hữu: <span className="text-zinc-200 font-semibold">{bot?.ownerName || 'Hệ thống'}</span>
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={onClose}
            className="p-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-300 transition-colors border border-zinc-700 text-lg font-bold leading-none"
            title="Đóng"
          >
            ✕
          </button>
        </div>

        {/* Content Body */}
        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          {isLoading ? (
            <div className="space-y-4 animate-pulse">
              <div className="h-20 bg-zinc-800/70 rounded-2xl" />
              <div className="h-32 bg-zinc-800/70 rounded-2xl" />
              <div className="h-40 bg-zinc-800/70 rounded-2xl" />
            </div>
          ) : error ? (
            <div className="text-center py-12 text-zinc-400 space-y-2">
              <p className="text-2xl">⚠️</p>
              <p className="text-sm font-bold text-red-400">{error}</p>
            </div>
          ) : (
            <>
              {/* Badges strip */}
              <div className="flex flex-wrap items-center gap-2">
                <span className={`px-2.5 py-1 rounded-xl text-xs font-bold border ${langInfo.color} flex items-center gap-1.5`}>
                  <span>{langInfo.icon}</span>
                  <span>{langInfo.label}</span>
                </span>

                <div className="px-3 py-1 rounded-xl text-xs font-bold bg-zinc-800 border border-zinc-700 flex items-center gap-1.5">
                  <span className="text-zinc-400">ELO:</span>
                  <span className="text-emerald-400 font-mono text-sm font-black">{bot?.elo || 1200}</span>
                  {bot?.provisional && (
                    <span
                      className="px-1.5 py-0.2 rounded text-[10px] bg-amber-500/20 text-amber-300 border border-amber-500/30"
                      title="Chưa đủ 10 trận hoặc 5 đối thủ khác nhau"
                    >
                      (Tạm thời)
                    </span>
                  )}
                </div>

              </div>

              {/* Statistics Grid */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div className="bg-zinc-850/80 rounded-2xl p-3 border border-zinc-800 text-center">
                  <p className="text-[11px] text-zinc-400">Trận Ranked</p>
                  <p className="text-lg font-black text-white font-mono mt-0.5">{bot?.games || 0}</p>
                </div>
                <div className="bg-zinc-850/80 rounded-2xl p-3 border border-zinc-800 text-center">
                  <p className="text-[11px] text-zinc-400">Thắng / Hòa / Thua</p>
                  <p className="text-sm font-black text-zinc-200 font-mono mt-1">
                    <span className="text-emerald-400">{bot?.wins || 0}</span> /{' '}
                    <span className="text-amber-400">{bot?.draws || 0}</span> /{' '}
                    <span className="text-red-400">{bot?.losses || 0}</span>
                  </p>
                </div>
                <div className="bg-zinc-850/80 rounded-2xl p-3 border border-zinc-800 text-center">
                  <p className="text-[11px] text-zinc-400">Tỉ Lệ Thắng</p>
                  <p className="text-lg font-black text-emerald-400 font-mono mt-0.5">
                    {bot?.winRate !== undefined ? `${bot.winRate}%` : `${bot?.games ? Math.round(((bot?.wins || 0) / bot.games) * 100) : 0}%`}
                  </p>
                </div>
                <div className="bg-zinc-850/80 rounded-2xl p-3 border border-zinc-800 text-center">
                  <p className="text-[11px] text-zinc-400">Đối Thủ Khác Nhau</p>
                  <p className="text-lg font-black text-indigo-400 font-mono mt-0.5">
                    {bot?.distinctOpponents || 0}
                  </p>
                </div>
              </div>

              {/* Recent Matches */}
              <div className="space-y-3">
                <div className="flex items-center justify-between gap-2">
                  <h4 className="text-xs font-bold text-zinc-300 flex items-center gap-1.5">
                    <span>📜</span>
                    <span>Lịch Sử Đấu Bot (10 trận gần đây)</span>
                  </h4>
                  <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded-full border bg-emerald-500/10 text-emerald-400 border-emerald-500/30">
                    🤖 Ranked Bot
                  </span>
                </div>

                {!bot?.recentMatches || bot.recentMatches.length === 0 ? (
                  <div className="text-center py-8 text-zinc-500 text-xs bg-zinc-850/40 rounded-2xl border border-zinc-800">
                    Chưa có lịch sử trận đấu nào được ghi nhận cho Bot này.
                  </div>
                ) : (
                  <div className="max-h-72 overflow-y-auto space-y-2 pr-1">
                    {bot.recentMatches.map((m, idx) => {
                      const isWin = m.result === 'WIN';
                      const isDraw = m.result === 'DRAW';
                      const isLoss = m.result === 'LOSS';
                      const hasElo = m.eloBefore != null && m.eloAfter != null;
                      const eloChange = hasElo ? m.eloAfter - m.eloBefore : null;
                      const playedAt = m.playedAt ? new Date(m.playedAt) : null;

                      return (
                        <div
                          key={m.matchPublicId || idx}
                          className="p-3 rounded-2xl bg-zinc-850/70 border border-zinc-800 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs"
                        >
                          <div className="flex items-center gap-2.5 min-w-0">
                            {/* Result pill */}
                            <span
                              className={`w-7 h-7 rounded-xl flex items-center justify-center font-black text-[11px] flex-shrink-0 ${
                                isWin
                                  ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                                  : isDraw
                                  ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30'
                                  : isLoss
                                  ? 'bg-red-500/20 text-red-400 border border-red-500/30'
                                  : 'bg-zinc-800 text-zinc-400'
                              }`}
                            >
                              {isWin ? 'W' : isDraw ? 'D' : isLoss ? 'L' : '?'}
                            </span>

                            <div className="min-w-0">
                              <p className="font-bold text-white truncate flex items-center gap-1.5">
                                <span>vs {m.opponentBotName || 'Đối thủ ẩn danh'}</span>
                                {m.opponentElo != null && (
                                  <span className="text-[10px] text-zinc-400 font-mono">({m.opponentElo} ELO)</span>
                                )}
                                {m.opponentOwnerName && (
                                  <span className="text-[10px] text-zinc-400 font-normal">
                                    ({m.opponentOwnerName})
                                  </span>
                                )}
                              </p>
                              <p className="text-[10px] text-zinc-500 font-mono">
                                {m.matchType === 'LADDER' ? 'Ranked Bot' : (m.matchType || 'MATCH')} ·{' '}
                                {playedAt ? playedAt.toLocaleString() : 'Vừa xong'}
                              </p>
                            </div>
                          </div>

                          <div className="flex items-center justify-between sm:justify-end gap-3 pl-9 sm:pl-0 flex-shrink-0">
                            <div className="flex items-center gap-3 font-mono">
                              <span className={`font-bold tabular-nums ${eloChange == null ? 'text-zinc-500' : eloChange > 0 ? 'text-emerald-400' : eloChange < 0 ? 'text-rose-400' : 'text-zinc-400'}`}>
                                {eloChange == null ? 'ELO —' : `${eloChange > 0 ? '+' : ''}${eloChange} ELO`}
                              </span>
                              <span className="text-zinc-400 tabular-nums">{m.eloAfter != null ? `${m.eloAfter} ELO` : '— ELO'}</span>
                            </div>
                            {(m.matchPublicId || m.publicId || m.id) && onOpenReplay && (
                              <button
                                type="button"
                                onClick={() => {
                                  onClose?.();
                                  const targetMatchId = m.matchPublicId || m.publicId || m.id;
                                  onOpenReplay?.({
                                    id: targetMatchId,
                                    matchPublicId: targetMatchId,
                                    replayId: targetMatchId,
                                    mode: 'BOT',
                                    matchType: m.matchType,
                                    playerA: bot?.name || 'Bot A',
                                    playerB: m.opponentBotName || 'Đối Thủ',
                                  });
                                }}
                                className="px-2.5 py-1 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-[11px] font-semibold text-zinc-200 transition-colors flex items-center gap-1"
                              >
                                <span>▶️</span>
                                <span>Xem Replay</span>
                              </button>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
