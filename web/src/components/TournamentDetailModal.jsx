import React, { useState, useEffect, useMemo } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client/dist/sockjs';
import { api, csrfHeaders, extractErrorMessage, buildWsUrl, createStompWebSocket } from '../utils/api.js';
import TournamentAdminModal from './TournamentAdminModal.jsx';
import BotDetailModal from './BotDetailModal.jsx';

/**
 * Tournament Detail Modal
 * Features:
 * - Tab 1: Overview (Rules, Fischer clock, status, format, description)
 * - Tab 2: Participants (Teams, bots, frozen versions, check-in status & action)
 * - Tab 3: Bracket / Standings & Match Schedule
 *   * Single Elimination: Visual horizontal tree bracket with SVG lines & Replay triggers
 *   * Round-Robin & Swiss: Tournament Leaderboard (Standings, Buchholz) + Round Pairings
 */
export default function TournamentDetailModal({
  tournament: initialTournament,
  user,
  userBots = [],
  onClose,
  onOpenReplay,
  onToast,
  onRegisterClick,
  onTournamentUpdated,
}) {
  const [tournament, setTournament] = useState(initialTournament);
  const [activeTab, setActiveTab] = useState('overview'); // 'overview' | 'teams' | 'schedule'
  const [teams, setTeams] = useState([]);
  const [bracketData, setBracketData] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [checkingInTeamId, setCheckingInTeamId] = useState(null);
  const [selectedRoundFilter, setSelectedRoundFilter] = useState('ALL');
  const [adminModalOpen, setAdminModalOpen] = useState(false);
  const [inspectedBotId, setInspectedBotId] = useState(null);
  const [startingMatchId, setStartingMatchId] = useState(null);
  const [previewMatch, setPreviewMatch] = useState(null);

  const tournamentId = tournament?.id;
  const canManageTournament = user?.role === 'ADMIN' || (tournament?.createdBy && user?.id === tournament.createdBy);

  const handleStartMatch = async (matchId) => {
    try {
      setStartingMatchId(matchId);
      const res = await api(`/api/admin/tournaments/${tournamentId}/matches/${matchId}/start`, {
        method: 'POST',
        headers: {
          ...(await csrfHeaders()),
          'Content-Type': 'application/json',
        },
      });
      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}));
        throw new Error(extractErrorMessage(errorData) || 'Không thể kích hoạt trận đấu');
      }
      onToast?.({ type: 'WIN', text: '▶️ Đã bắt đầu trận đấu thành công!' });
      setPreviewMatch(null);
      await loadData(true);
      onTournamentUpdated?.();
    } catch (err) {
      onToast?.({ type: 'LOSE', text: extractErrorMessage(err, 'Lỗi khi kích hoạt trận đấu') });
    } finally {
      setStartingMatchId(null);
    }
  };

  // Real-time WebSocket STOMP subscription for bracket & match progress updates
  useEffect(() => {
    if (!tournamentId) return undefined;

    const sockJsUrl = buildWsUrl('/api/ws');

    let isSubscribed = true;

    const client = new Client({
      webSocketFactory: () => createStompWebSocket('/api/ws'),
      connectHeaders: {
        'ngrok-skip-browser-warning': 'true',
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 5000,
      heartbeatOutgoing: 5000,
      onConnect: () => {
        if (!isSubscribed) return;
        client.subscribe(`/topic/tournaments/${tournamentId}`, () => {
          loadData(true);
          onTournamentUpdated?.();
        });
        client.subscribe(`/topic/tournament/${tournamentId}`, () => {
          loadData(true);
          onTournamentUpdated?.();
        });
      },
      debug: () => {},
    });

    try {
      client.activate();
    } catch (err) {
      console.warn('Could not activate STOMP client for tournament:', err);
    }

    return () => {
      isSubscribed = false;
      try {
        client.deactivate();
      } catch {}
    };
  }, [tournamentId]);

  const loadData = async (isBackground = false) => {
    if (!tournamentId) return;
    if (!isBackground) setIsLoading(true);
    else setIsRefreshing(true);

    try {
      // Parallel fetch for fresh tournament meta, teams, and bracket view
      const [tourneyRes, teamsRes, bracketRes] = await Promise.all([
        api(`/api/tournaments/${tournamentId}`).catch(() => null),
        api(`/api/tournaments/${tournamentId}/teams`).catch(() => null),
        api(`/api/tournaments/${tournamentId}/bracket`).catch(() => null),
      ]);

      if (tourneyRes && tourneyRes.ok) {
        const freshTourney = await tourneyRes.json();
        setTournament(freshTourney);
      }

      if (teamsRes && teamsRes.ok) {
        const teamsList = await teamsRes.json();
        setTeams(Array.isArray(teamsList) ? teamsList : []);
      }

      if (bracketRes && bracketRes.ok) {
        const bracket = await bracketRes.json();
        setBracketData(bracket);
      }
    } catch (err) {
      console.error('Lỗi khi tải chi tiết giải đấu:', err);
      onToast?.({ type: 'LOSE', text: 'Không thể tải toàn bộ dữ liệu giải đấu.' });
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  };

  useEffect(() => {
    loadData();
    // Default to schedule tab if tournament is already running or completed
    if (['RUNNING', 'COMPLETED', 'FINISHED'].includes(tournament?.status)) {
      setActiveTab('schedule');
    }
  }, [tournamentId]);

  // Check if current user is captain or member of any team in this tournament
  const myTeam = useMemo(() => {
    if (!user?.id || !teams?.length) return null;
    return teams.find(
      (t) =>
        t?.captainId === user.id ||
        t?.members?.some((m) => m?.userId === user.id)
    );
  }, [user?.id, teams]);

  // Handle Team Check-In
  const handleCheckIn = async (teamId) => {
    setCheckingInTeamId(teamId);
    try {
      const res = await api(`/api/tournaments/${tournamentId}/teams/${teamId}/check-in`, {
        method: 'POST',
        headers: await csrfHeaders(),
        body: JSON.stringify({}),
      });
      if (res && res.ok) {
        onToast?.({ type: 'WIN', text: '✅ Điểm danh (Check-In) thành công!' });
        loadData(true);
        onTournamentUpdated?.();
      } else {
        const err = await res?.json().catch(() => null);
        const msg = extractErrorMessage(err?.error || err?.message || 'Điểm danh không thành công. Vui lòng thử lại.');
        onToast?.({
          type: 'LOSE',
          text: msg,
        });
      }
    } catch (err) {
      onToast?.({ type: 'LOSE', text: extractErrorMessage(err, 'Lỗi khi điểm danh.') });
    } finally {
      setCheckingInTeamId(null);
    }
  };

  // Direct Open Registration for tournament creator / admin
  const handleDirectOpenRegistration = async () => {
    if (!tournamentId) {
      onToast?.({ type: 'LOSE', text: 'Không xác định được ID giải đấu.' });
      return;
    }
    try {
      const res = await api(`/api/admin/tournaments/${tournamentId}/open-registration`, {
        method: 'POST',
        headers: await csrfHeaders(),
      });
      if (res && res.ok) {
        onToast?.({ type: 'WIN', text: '📢 Đã mở đăng ký giải đấu thành công!' });
        loadData(true);
        onTournamentUpdated?.();
      } else {
        const err = await res?.json().catch(() => null);
        const msg = extractErrorMessage(err?.error || err?.message || 'Không thể mở đăng ký.');
        onToast?.({ type: 'LOSE', text: msg });
      }
    } catch (err) {
      onToast?.({ type: 'LOSE', text: extractErrorMessage(err, 'Lỗi khi mở đăng ký giải.') });
    }
  };

  // Format Helper
  const getFormatLabel = (fmt) => {
    switch (fmt) {
      case 'SINGLE_ELIMINATION':
        return { label: 'Loại Trực Tiếp (Single Elimination)', badge: 'bg-rose-500/20 text-rose-300 border-rose-500/40', icon: '⚔️' };
      case 'ROUND_ROBIN':
        return { label: 'Vòng Tròn Tính Điểm (Round-Robin)', badge: 'bg-amber-500/20 text-amber-300 border-amber-500/40', icon: '🔄' };
      case 'SWISS':
        return { label: 'Hệ Thụy Sĩ (Swiss System)', badge: 'bg-indigo-500/20 text-indigo-300 border-indigo-500/40', icon: '🇨🇭' };
      default:
        return { label: fmt || 'Tiêu chuẩn', badge: 'bg-zinc-800 text-zinc-300 border-zinc-700', icon: '🏆' };
    }
  };

  const getStatusBadge = (status) => {
    switch (status) {
      case 'REGISTRATION_OPEN':
        return (
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 shadow-sm shadow-emerald-950/30">
            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
            Đang Mở Đăng Ký
          </span>
        );
      case 'REGISTRATION_CLOSED':
        return (
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-zinc-800 text-zinc-300 border border-zinc-700">
            Đã Đóng Đăng Ký
          </span>
        );
      case 'CHECK_IN':
        return (
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-amber-500/20 text-amber-300 border border-amber-500/40 animate-pulse">
            ⏱️ Đang Điểm Danh (Check-in)
          </span>
        );
      case 'RUNNING':
        return (
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-indigo-500/20 text-indigo-300 border border-indigo-500/40 shadow-sm shadow-indigo-950/30">
            <span className="w-2 h-2 rounded-full bg-indigo-400 animate-pulse" />
            Đang Diễn Ra
          </span>
        );
      case 'COMPLETED':
      case 'FINISHED':
        return (
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-sky-500/20 text-sky-300 border border-sky-500/40">
            🏁 Đã Hoàn Thành
          </span>
        );
      case 'SUSPENDED':
        return (
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-red-500/20 text-red-300 border border-red-500/40">
            ⚠️ Tạm Dừng
          </span>
        );
      case 'CANCELLED':
        return (
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-zinc-800 text-zinc-500 border border-zinc-700 line-through">
            Đã Hủy
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-zinc-800 text-zinc-400 border border-zinc-700">
            {status || 'Bản Nháp'}
          </span>
        );
    }
  };

  const formatMeta = getFormatLabel(tournament?.format);
  const rules = tournament?.rulesConfig || {};
  const matchesList = bracketData?.matches || [];
  const standingsList = bracketData?.standings || [];

  // Group Single Elimination matches by roundNo
  const matchesByRound = useMemo(() => {
    if (!matchesList?.length) return {};
    const grouped = {};
    matchesList.forEach((m) => {
      const r = m?.roundNo || 1;
      if (!grouped[r]) grouped[r] = [];
      grouped[r].push(m);
    });
    // Sort matches within round by matchNo
    Object.keys(grouped).forEach((r) => {
      grouped[r].sort((a, b) => (a?.matchNo || 0) - (b?.matchNo || 0));
    });
    return grouped;
  }, [matchesList]);

  const totalRounds = useMemo(() => {
    const rounds = Object.keys(matchesByRound).map(Number);
    return rounds.length ? Math.max(...rounds) : 1;
  }, [matchesByRound]);

  const getRoundTitle = (roundNo, total) => {
    const remaining = total - roundNo;
    if (remaining === 0) return 'Chung Kết (Final)';
    if (remaining === 1) return 'Bán Kết (Semi-Finals)';
    if (remaining === 2) return 'Tứ Kết (Quarter-Finals)';
    if (remaining === 3) return 'Vòng 1/8 (Round of 16)';
    if (remaining === 4) return 'Vòng 1/16 (Round of 32)';
    return `Vòng ${roundNo}`;
  };

  // Unique rounds for Round-Robin / Swiss filter
  const roundNumbers = useMemo(() => {
    const rSet = new Set(matchesList.map((m) => m?.roundNo).filter(Boolean));
    return Array.from(rSet).sort((a, b) => a - b);
  }, [matchesList]);

  const filteredMatches = useMemo(() => {
    if (selectedRoundFilter === 'ALL') return matchesList;
    return matchesList.filter((m) => m?.roundNo === Number(selectedRoundFilter));
  }, [matchesList, selectedRoundFilter]);

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-2 sm:p-4 animate-fadeIn">
      <div className="bg-zinc-900 border border-zinc-750 w-full max-w-5xl h-[92vh] rounded-3xl shadow-2xl flex flex-col overflow-hidden text-zinc-100">
        {/* Modal Header */}
        <div className="p-4 sm:p-6 border-b border-zinc-800 bg-zinc-900/90 flex flex-wrap items-center justify-between gap-3 flex-shrink-0">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2 mb-1.5">
              <span className={`px-2.5 py-0.5 rounded-lg text-[11px] font-bold border ${formatMeta.badge} flex items-center gap-1`}>
                <span>{formatMeta.icon}</span>
                <span>{formatMeta.label}</span>
              </span>
              {getStatusBadge(tournament?.status)}
              {myTeam && (
                <span className="px-2.5 py-0.5 rounded-lg text-[11px] font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 flex items-center gap-1">
                  <span>🛡️</span>
                  <span>Đội của bạn: <strong>{myTeam.name}</strong></span>
                </span>
              )}
            </div>
            <h2 className="text-xl sm:text-2xl font-black text-white truncate flex items-center gap-2">
              <span>{tournament?.title || tournament?.name || 'Chi Tiết Giải Đấu'}</span>
            </h2>
          </div>

          <div className="flex items-center gap-2 flex-shrink-0">
            {tournament?.status === 'REGISTRATION_OPEN' && (
              myTeam ? (
                <div
                  className="px-3.5 py-2 rounded-xl bg-emerald-950/60 border border-emerald-500/50 text-emerald-300 font-bold text-xs flex items-center gap-1.5 shadow-sm"
                  title={`Bạn đã đăng ký với đội ${myTeam.name}`}
                >
                  <span>✓</span>
                  <span>Đã Đăng Ký ({myTeam.name})</span>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => {
                    onClose?.();
                    onRegisterClick?.(tournament);
                  }}
                  className="px-3.5 py-2 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-bold text-xs shadow-lg shadow-emerald-950/40 flex items-center gap-1.5 transition-all"
                  title="Đăng ký tham gia giải đấu"
                >
                  <span>📝</span>
                  <span>Đăng Ký Tham Gia</span>
                </button>
              )
            )}
            {canManageTournament && (
              <button
                type="button"
                onClick={() => setAdminModalOpen(true)}
                className="px-3 py-2 rounded-xl bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 font-bold text-xs border border-amber-500/40 transition-colors flex items-center gap-1.5 shadow-sm"
                title="Mở bảng điều khiển quản trị giải đấu"
              >
                <span>⚙️</span>
                <span className="hidden sm:inline">Quản Trị Giải</span>
              </button>
            )}
            <button
              type="button"
              onClick={() => loadData(true)}
              disabled={isRefreshing}
              className="p-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-300 transition-colors border border-zinc-700/60 disabled:opacity-50"
              title="Làm mới dữ liệu"
            >
              <span className={`inline-block ${isRefreshing ? 'animate-spin' : ''}`}>🔄</span>
            </button>
            <button
              type="button"
              onClick={onClose}
              className="p-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-300 transition-colors border border-zinc-700/60 text-lg font-bold leading-none"
              title="Đóng"
            >
              ✕
            </button>
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="px-4 sm:px-6 border-b border-zinc-800 bg-zinc-900/50 flex items-center gap-2 overflow-x-auto flex-shrink-0">
          <button
            type="button"
            onClick={() => setActiveTab('overview')}
            className={`py-3 px-4 font-bold text-xs sm:text-sm border-b-2 transition-all flex items-center gap-2 whitespace-nowrap ${
              activeTab === 'overview'
                ? 'border-emerald-500 text-emerald-400 bg-emerald-500/5'
                : 'border-transparent text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <span>📋</span>
            <span>Tổng Quan & Điều Lệ</span>
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('teams')}
            className={`py-3 px-4 font-bold text-xs sm:text-sm border-b-2 transition-all flex items-center gap-2 whitespace-nowrap ${
              activeTab === 'teams'
                ? 'border-emerald-500 text-emerald-400 bg-emerald-500/5'
                : 'border-transparent text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <span>👥</span>
            <span>Đội Tham Gia</span>
            <span className="ml-1 px-1.5 py-0.5 rounded-md text-[10px] bg-zinc-800 text-zinc-300 font-mono">
              {teams?.length || 0}/{tournament?.maxTeams || 16}
            </span>
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('schedule')}
            className={`py-3 px-4 font-bold text-xs sm:text-sm border-b-2 transition-all flex items-center gap-2 whitespace-nowrap ${
              activeTab === 'schedule'
                ? 'border-emerald-500 text-emerald-400 bg-emerald-500/5'
                : 'border-transparent text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <span>🏆</span>
            <span>
              {tournament?.format === 'SINGLE_ELIMINATION'
                ? 'Nhánh Đấu (Bracket)'
                : 'Bảng Điểm & Lịch Đấu'}
            </span>
            {matchesList?.length > 0 && (
              <span className="ml-1 px-1.5 py-0.5 rounded-md text-[10px] bg-emerald-950/80 text-emerald-300 border border-emerald-800/60 font-mono">
                {matchesList.filter((m) => m?.status === 'FINISHED').length}/{matchesList.length}
              </span>
            )}
          </button>
        </div>

        {/* Modal Body Content */}
        <div className="flex-1 overflow-y-auto p-4 sm:p-6 space-y-6">
          {isLoading ? (
            <div className="space-y-4 animate-pulse">
              <div className="h-28 bg-zinc-800/70 rounded-2xl" />
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div className="h-32 bg-zinc-800/70 rounded-2xl" />
                <div className="h-32 bg-zinc-800/70 rounded-2xl" />
                <div className="h-32 bg-zinc-800/70 rounded-2xl" />
              </div>
              <div className="h-60 bg-zinc-800/70 rounded-2xl" />
            </div>
          ) : (
            <>
              {/* TAB 1: OVERVIEW */}
              {activeTab === 'overview' && (
                <div className="space-y-6 max-w-4xl mx-auto">
                  {/* Banner Description Card */}
                  <div className="bg-gradient-to-r from-zinc-850 to-zinc-900 border border-zinc-750 p-5 rounded-2xl space-y-3">
                    <h3 className="text-base font-bold text-white flex items-center gap-2">
                      <span>📢</span>
                      <span>Giới Thiệu Giải Đấu</span>
                    </h3>
                    <p className="text-sm text-zinc-300 leading-relaxed whitespace-pre-line">
                      {tournament?.description ||
                        'Giải đấu đối kháng chiến thuật Oẳn Tù Tì Phalanx quy tụ các cỗ máy thuật toán tối tân nhất tranh tài.'}
                    </p>

                    {/* Quick Registration Status */}
                    {tournament?.status === 'REGISTRATION_OPEN' && (
                      <div className="pt-2 flex flex-wrap items-center justify-between gap-3 border-t border-zinc-800">
                        <div className="text-xs text-zinc-400">
                          {myTeam ? (
                            <span className="text-emerald-400 font-semibold">
                              ✓ Bạn đã đăng ký tham gia với đội <strong>{myTeam.name}</strong>.
                            </span>
                          ) : (
                            <span>Đăng ký đang mở! Số lượng có hạn ({teams.length}/{tournament?.maxTeams} đội).</span>
                          )}
                        </div>
                        {myTeam ? (
                          <div className="px-3.5 py-1.5 rounded-xl bg-emerald-950/60 border border-emerald-500/50 text-emerald-300 font-bold text-xs flex items-center gap-1.5 shadow-sm">
                            <span>✓</span>
                            <span>Đã Đăng Ký</span>
                          </div>
                        ) : (
                          <button
                            type="button"
                            onClick={() => {
                              onClose?.();
                              onRegisterClick?.(tournament);
                            }}
                            className="px-4 py-2 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-bold text-xs shadow-lg shadow-emerald-950/40 flex items-center gap-1.5"
                          >
                            <span>📝</span>
                            <span>Đăng Ký Tham Gia</span>
                          </button>
                        )}
                      </div>
                    )}

                    {/* Draft Banner for Organizer */}
                    {tournament?.status === 'DRAFT' && canManageTournament && (
                      <div className="pt-2 flex flex-wrap items-center justify-between gap-3 border-t border-zinc-800">
                        <div className="text-xs text-amber-300">
                          ⚠️ Giải đấu đang ở trạng thái <strong>Bản Nháp (DRAFT)</strong>. Hãy mở đăng ký để người chơi có thể nộp đơn tham gia.
                        </div>
                        <div className="flex items-center gap-2">
                          <button
                            type="button"
                            onClick={handleDirectOpenRegistration}
                            className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs shadow-lg shadow-emerald-950/40 flex items-center gap-1.5"
                          >
                            <span>📢</span>
                            <span>Mở Đăng Ký Ngay</span>
                          </button>
                          <button
                            type="button"
                            onClick={() => setAdminModalOpen(true)}
                            className="px-3.5 py-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-300 border border-zinc-700 font-bold text-xs flex items-center gap-1.5"
                          >
                            <span>⚙️</span>
                            <span>Quản Trị</span>
                          </button>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Key Tournament Parameters Grid */}
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                    {/* Clock Config */}
                    <div className="bg-zinc-850/80 border border-zinc-800 p-4 rounded-2xl space-y-1.5">
                      <p className="text-xs text-zinc-400 font-medium flex items-center gap-1.5">
                        <span>⏱️</span>
                        <span>Đồng Hồ Fischer</span>
                      </p>
                      <p className="text-lg font-black text-white font-mono">
                        {Math.floor((rules?.initialTimeSeconds || 180) / 60)}m {rules?.initialTimeSeconds % 60 || 0}s + {rules?.incrementSeconds || 2}s
                      </p>
                      <p className="text-[11px] text-zinc-500">
                        Cộng {rules?.incrementSeconds || 2} giây cho mỗi nước đi hợp lệ.
                      </p>
                    </div>

                    {/* Sandbox Limits */}
                    <div className="bg-zinc-850/80 border border-zinc-800 p-4 rounded-2xl space-y-1.5">
                      <p className="text-xs text-zinc-400 font-medium flex items-center gap-1.5">
                        <span>🛡️</span>
                        <span>Giới Hạn Sandbox</span>
                      </p>
                      <p className="text-lg font-black text-white font-mono">
                        {rules?.timeoutSeconds || 3}s / {rules?.memoryLimitMb || 256}MB
                      </p>
                      <p className="text-[11px] text-zinc-500">
                        Timeout mỗi nước đi & Giới hạn RAM tối đa cho Bot.
                      </p>
                    </div>

                    {/* Format Details */}
                    <div className="bg-zinc-850/80 border border-zinc-800 p-4 rounded-2xl space-y-1.5">
                      <p className="text-xs text-zinc-400 font-medium flex items-center gap-1.5">
                        <span>🎯</span>
                        <span>Quy Mô & Đội</span>
                      </p>
                      <p className="text-lg font-black text-white">
                        {tournament?.maxTeams || 16} Đội
                      </p>
                      <p className="text-[11px] text-zinc-500">
                        {tournament?.teamSize || 1} thành viên / {tournament?.teamSize || 1} Bot mỗi đội.
                      </p>
                    </div>

                    {/* Scoring system */}
                    <div className="bg-zinc-850/80 border border-zinc-800 p-4 rounded-2xl space-y-1.5">
                      <p className="text-xs text-zinc-400 font-medium flex items-center gap-1.5">
                        <span>🔢</span>
                        <span>Thang Điểm Trận</span>
                      </p>
                      <p className="text-lg font-black text-emerald-400 font-mono">
                        {rules?.pointsPerWin ?? 3} / {rules?.pointsPerDraw ?? 1} / {rules?.pointsPerLoss ?? 0}
                      </p>
                      <p className="text-[11px] text-zinc-500">
                        Thắng {rules?.pointsPerWin ?? 3} điểm · Hòa {rules?.pointsPerDraw ?? 1} · Thua {rules?.pointsPerLoss ?? 0} điểm.
                      </p>
                    </div>
                  </div>

                  {/* Detailed Rules breakdown */}
                  <div className="bg-zinc-850/60 border border-zinc-800 p-5 rounded-2xl space-y-3">
                    <h4 className="text-sm font-bold text-white flex items-center gap-2">
                      <span>⚖️</span>
                      <span>Thể Lệ Phân Xử & Tiêu Chí Phụ (Tie-Break)</span>
                    </h4>
                    <div className="text-xs text-zinc-400 space-y-2 leading-relaxed">
                      <p>
                        • <strong>Bản Đồ Thi Đấu:</strong> Chiến trường 9x9 với địa hình cân xứng tâm đối xứng. Đội A xuất phát tại A9 [8,0], Đội B xuất phát tại I1 [0,8].
                      </p>
                      <p>
                        • <strong>Điều Kiện Thắng:</strong> Đưa quân cờ bất kỳ chạm ô căn cứ đối phương hoặc tiêu diệt toàn bộ quân đối phương.
                      </p>
                      <p>
                        • <strong>Đóng Băng Phiên Bản (Bot Immutability):</strong> Toàn bộ mã nguồn Bot sẽ được chốt chặt phiên bản (`source_sha256`, `version_no`) tại thời điểm đăng ký/check-in. Mọi sửa đổi sau đó không ảnh hưởng giải đấu.
                      </p>
                      {tournament?.format === 'SWISS' && (
                        <p>
                          • <strong>Tiêu Chí Phụ Thụy Sĩ:</strong> Xếp hạng ưu tiên theo <strong>Điểm số</strong> &rarr; <strong>Hệ số Buchholz</strong> (tổng điểm của tất cả đối thủ đã chạm trán) &rarr; <strong>Hạt giống gốc</strong>.
                        </p>
                      )}
                      {tournament?.format === 'ROUND_ROBIN' && (
                        <p>
                          • <strong>Lịch Vòng Tròn (Berger):</strong> Mỗi đội gặp tất cả các đội còn lại đúng 1 lần duy nhất, xoay đổi phe Xanh/Đỏ công bằng.
                        </p>
                      )}
                    </div>
                  </div>
                </div>
              )}

              {/* TAB 2: TEAMS */}
              {activeTab === 'teams' && (
                <div className="space-y-4 max-w-4xl mx-auto">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <h3 className="text-base font-bold text-white flex items-center gap-2">
                        <span>👥</span>
                        <span>Danh Sách Đội Tham Gia ({teams.length}/{tournament?.maxTeams})</span>
                      </h3>
                      <p className="text-xs text-zinc-400">
                        Phiên bản bot được khóa vĩnh viễn đảm bảo tính công bằng và minh bạch.
                      </p>
                    </div>

                    {tournament?.status === 'REGISTRATION_OPEN' && (
                      myTeam ? (
                        <div className="px-3.5 py-1.5 rounded-xl bg-emerald-950/60 border border-emerald-500/50 text-emerald-300 font-bold text-xs flex items-center gap-1.5 shadow-sm">
                          <span>✓</span>
                          <span>Đã Đăng Ký ({myTeam.name})</span>
                        </div>
                      ) : (
                        <button
                          type="button"
                          onClick={() => {
                            onClose?.();
                            onRegisterClick?.(tournament);
                          }}
                          className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs shadow-lg shadow-emerald-950/40 flex items-center gap-1.5"
                        >
                          <span>➕</span>
                          <span>Đăng Ký Tham Gia</span>
                        </button>
                      )
                    )}
                  </div>

                  {teams?.length === 0 ? (
                    <div className="text-center py-16 bg-zinc-850/40 border border-zinc-800 rounded-2xl space-y-3">
                      <div className="w-14 h-14 rounded-2xl bg-zinc-800 text-zinc-500 text-2xl flex items-center justify-center mx-auto">
                        👥
                      </div>
                      <p className="text-sm font-bold text-zinc-300">Chưa có đội nào đăng ký</p>
                      <p className="text-xs text-zinc-500 max-w-sm mx-auto">
                        Hãy là đội tiên phong ghi danh vào giải đấu đối kháng đỉnh cao này!
                      </p>
                    </div>
                  ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                      {teams.map((t, idx) => {
                        const isMyTeam = t?.captainId === user?.id || t?.members?.some((m) => m?.userId === user?.id);
                        const canCheckIn =
                          tournament?.status === 'CHECK_IN' &&
                          isMyTeam &&
                          t?.status !== 'CHECKED_IN' &&
                          t?.status !== 'DISQUALIFIED';

                        return (
                          <div
                            key={t.id || idx}
                            className={`p-4 rounded-2xl border transition-all ${
                              isMyTeam
                                ? 'bg-emerald-950/20 border-emerald-500/40 shadow-md shadow-emerald-950/20'
                                : 'bg-zinc-850/70 border-zinc-800 hover:border-zinc-700'
                            }`}
                          >
                            <div className="flex items-start justify-between gap-2 mb-2">
                              <div className="min-w-0">
                                <div className="flex items-center gap-2">
                                  <span className="text-xs font-mono font-bold text-zinc-500">
                                    #{t.seed || idx + 1}
                                  </span>
                                  <h4 className="font-bold text-sm text-white truncate">{t.name}</h4>
                                  {isMyTeam && (
                                    <span className="text-[10px] bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 px-1.5 py-0.5 rounded font-black">
                                      ĐỘI BẠN
                                    </span>
                                  )}
                                </div>
                                <p className="text-xs text-zinc-400 truncate">
                                  Đội trưởng: <span className="text-zinc-200">{t.captainName || 'Sinh viên'}</span>
                                </p>
                              </div>

                              {/* Status Badge */}
                              <div>
                                {t.status === 'CHECKED_IN' ? (
                                  <span className="text-[10px] font-bold px-2 py-0.5 rounded-md bg-emerald-500/20 text-emerald-300 border border-emerald-500/40">
                                    ✓ Đã Điểm Danh
                                  </span>
                                ) : t.status === 'DISQUALIFIED' ? (
                                  <span className="text-[10px] font-bold px-2 py-0.5 rounded-md bg-red-500/20 text-red-300 border border-red-500/40">
                                    Bị Loại
                                  </span>
                                ) : t.status === 'ELIMINATED' ? (
                                  <span className="text-[10px] font-bold px-2 py-0.5 rounded-md bg-zinc-800 text-zinc-400 border border-zinc-700">
                                    Đã Dừng Bước
                                  </span>
                                ) : (
                                  <span className="text-[10px] font-bold px-2 py-0.5 rounded-md bg-zinc-800 text-zinc-300 border border-zinc-700">
                                    {t.status || 'Đã Đăng Ký'}
                                  </span>
                                )}
                              </div>
                            </div>

                            {/* Bot Details Snapshot */}
                            <div className="bg-zinc-900/80 rounded-xl p-2.5 border border-zinc-800/80 flex items-center justify-between text-xs">
                              <div>
                                {t.botId ? (
                                  <button
                                    type="button"
                                    onClick={() => setInspectedBotId(t.botId)}
                                    className="font-bold text-zinc-200 hover:text-emerald-400 flex items-center gap-1.5 transition-colors underline decoration-dotted underline-offset-2 text-left"
                                    title="Xem hồ sơ & thông kê chi tiết Bot"
                                  >
                                    <span>🤖</span>
                                    <span>{t.botName || 'Xem Bot'}</span>
                                  </button>
                                ) : (
                                  <p className="font-bold text-zinc-400 flex items-center gap-1.5">
                                    <span>🤖</span>
                                    <span>{t.botName || 'Chưa liên kết Bot'}</span>
                                  </p>
                                )}
                                <p className="text-[11px] text-zinc-500 font-mono">
                                  Phiên bản: <strong className="text-zinc-400">v{t.versionNo || 1}</strong> · SHA: <span className="text-emerald-400">{t.sourceSha256?.substring(0, 8) || 'N/A'}</span>
                                </p>
                              </div>

                              {/* Check-in button if applicable */}
                              {canCheckIn && (
                                <button
                                  type="button"
                                  onClick={() => handleCheckIn(t.id)}
                                  disabled={checkingInTeamId === t.id}
                                  className="px-3 py-1.5 rounded-lg bg-amber-500 hover:bg-amber-400 text-black font-bold text-xs shadow disabled:opacity-50 animate-pulse"
                                >
                                  {checkingInTeamId === t.id ? 'Đang gửi...' : 'Điểm Danh Ngay'}
                                </button>
                              )}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}

              {/* TAB 3: SCHEDULE / BRACKET / STANDINGS */}
              {activeTab === 'schedule' && (
                <div className="space-y-6">
                  {/* CASE A: SINGLE ELIMINATION BRACKET */}
                  {tournament?.format === 'SINGLE_ELIMINATION' && (
                    <div className="space-y-4">
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <div>
                          <h3 className="text-base font-bold text-white flex items-center gap-2">
                            <span>⚔️</span>
                            <span>Cây Phân Nhánh Loại Trực Tiếp (Bracket)</span>
                          </h3>
                          <p className="text-xs text-zinc-400">
                            Các cặp đấu được phân nhánh tự động. Nhấp vào trận đã hoàn thành để xem lại Replay chiến thuật.
                          </p>
                        </div>
                      </div>

                      {matchesList.length === 0 ? (
                        <div className="text-center py-16 bg-zinc-850/40 border border-zinc-800 rounded-2xl space-y-3">
                          <div className="w-14 h-14 rounded-2xl bg-zinc-800 text-zinc-500 text-2xl flex items-center justify-center mx-auto">
                            🏆
                          </div>
                          <p className="text-sm font-bold text-zinc-300">Nhánh đấu chưa được kích hoạt</p>
                          <p className="text-xs text-zinc-500 max-w-sm mx-auto">
                            Nhánh đấu sẽ tự động sinh và hiển thị khi ban tổ chức tiến hành bốc thăm hoặc khởi tranh giải.
                          </p>
                        </div>
                      ) : (
                        <div className="overflow-x-auto pb-6 pt-2">
                          <div className="flex items-stretch gap-8 min-w-max px-2">
                            {Array.from({ length: totalRounds }, (_, i) => i + 1).map((roundNo) => {
                              const roundMatches = matchesByRound[roundNo] || [];
                              const roundTitle = getRoundTitle(roundNo, totalRounds);

                              return (
                                <div key={roundNo} className="w-72 flex flex-col">
                                  {/* Round Header */}
                                  <div className="mb-4 text-center py-2 px-3 rounded-xl bg-zinc-800/80 border border-zinc-700/80 shadow-sm">
                                    <h4 className="text-xs font-black uppercase tracking-wider text-emerald-400">
                                      {roundTitle}
                                    </h4>
                                    <p className="text-[10px] text-zinc-400 font-mono">
                                      {roundMatches.length} Trận Đấu
                                    </p>
                                  </div>

                                  {/* Match List in this Round */}
                                  <div className="flex-1 flex flex-col justify-around gap-6">
                                    {roundMatches.map((m) => {
                                      const isFinished = m?.status === 'FINISHED' || m?.status === 'BYE';
                                      const isRunning = m?.status === 'RUNNING';
                                      const isBye = m?.status === 'BYE';
                                      const winnerTeamId = m?.winnerTeamId;

                                      return (
                                        <div
                                          key={m.id}
                                          onClick={() => {
                                            if (isFinished && m.matchId) {
                                              onOpenReplay?.({
                                                id: m.matchId,
                                                playerA: m.teamAName || 'Đội Xanh',
                                                playerB: m.teamBName || 'Đội Đỏ',
                                                winner: m.winnerTeamId === m.teamAId ? 'A' : 'B',
                                                mode: 'TOURNAMENT',
                                                matchType: 'TOURNAMENT',
                                                isRanked: false,
                                              });
                                            } else if (m.status === 'READY') {
                                              setPreviewMatch(m);
                                            }
                                          }}
                                          className={`relative p-3 rounded-2xl border transition-all ${
                                            isFinished && m.matchId
                                              ? 'cursor-pointer hover:border-emerald-500 hover:shadow-lg hover:shadow-emerald-950/30'
                                              : m.status === 'READY'
                                              ? 'cursor-pointer hover:border-cyan-500/80 hover:shadow-lg hover:shadow-cyan-950/30'
                                              : ''
                                          } ${
                                            isRunning
                                              ? 'bg-indigo-950/30 border-indigo-500/60 shadow-md shadow-indigo-950/40'
                                              : m.status === 'READY'
                                              ? 'bg-zinc-850/90 border-cyan-500/30'
                                              : 'bg-zinc-850/90 border-zinc-800'
                                          }`}
                                        >
                                          {/* Match Header Tag */}
                                          <div className="flex items-center justify-between text-[10px] text-zinc-500 pb-2 mb-2 border-b border-zinc-800">
                                            <span className="font-mono">Trận #{m.matchNo}</span>
                                            {isBye ? (
                                              <span className="text-amber-400 font-bold">BYE (Miễn Đấu)</span>
                                            ) : isRunning ? (
                                              <span className="text-indigo-400 font-bold flex items-center gap-1 animate-pulse">
                                                <span>⚔️ Đang đấu</span>
                                              </span>
                                            ) : isFinished ? (
                                              <span className="text-emerald-400 font-bold">Hoàn tất</span>
                                            ) : m.status === 'READY' ? (
                                              <span className="text-zinc-400 font-medium">Sẵn sàng</span>
                                            ) : (
                                              <span className="text-zinc-500">Chờ ghép</span>
                                            )}
                                          </div>

                                          {/* Team A */}
                                          <div
                                            className={`p-2 rounded-xl flex items-center justify-between transition-colors mb-1 ${
                                              winnerTeamId && winnerTeamId === m.teamAId
                                                ? 'bg-emerald-500/15 text-emerald-200 font-bold border border-emerald-500/30'
                                                : 'text-zinc-300'
                                            }`}
                                          >
                                            <div className="flex items-center gap-2 truncate">
                                              <span className="w-2 h-2 rounded-full bg-blue-500 flex-shrink-0" />
                                              <span className="text-xs truncate">
                                                {m.teamAName || (isBye && !m.teamAId ? 'BYE' : 'TBD')}
                                              </span>
                                              {winnerTeamId && winnerTeamId === m.teamAId && (
                                                <span className="text-xs">👑</span>
                                              )}
                                            </div>
                                            <span className="font-mono text-xs font-black ml-2">
                                              {isFinished && !isBye ? m.scoreA : '-'}
                                            </span>
                                          </div>

                                          {/* Team B */}
                                          <div
                                            className={`p-2 rounded-xl flex items-center justify-between transition-colors ${
                                              winnerTeamId && winnerTeamId === m.teamBId
                                                ? 'bg-emerald-500/15 text-emerald-200 font-bold border border-emerald-500/30'
                                                : 'text-zinc-300'
                                            }`}
                                          >
                                            <div className="flex items-center gap-2 truncate">
                                              <span className="w-2 h-2 rounded-full bg-red-500 flex-shrink-0" />
                                              <span className="text-xs truncate">
                                                {m.teamBName || (isBye && !m.teamBId ? 'BYE' : 'TBD')}
                                              </span>
                                              {winnerTeamId && winnerTeamId === m.teamBId && (
                                                <span className="text-xs">👑</span>
                                              )}
                                            </div>
                                            <span className="font-mono text-xs font-black ml-2">
                                              {isFinished && !isBye ? m.scoreB : '-'}
                                            </span>
                                          </div>

                                          {/* Replay action badge on hover */}
                                          {isFinished && m.matchId && (
                                            <div className="mt-2 pt-1 border-t border-zinc-800/60 flex items-center justify-center gap-1 text-[10px] text-emerald-400 font-semibold">
                                              <span>▶️ Xem Lại Trận</span>
                                            </div>
                                          )}

                                          {m.status === 'READY' && canManageTournament && (
                                            <button
                                              type="button"
                                              onClick={(e) => {
                                                e.stopPropagation();
                                                handleStartMatch(m.id);
                                              }}
                                              disabled={startingMatchId === m.id}
                                              className="mt-2 w-full py-1.5 px-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-[11px] flex items-center justify-center gap-1.5 shadow-md shadow-emerald-950/40 transition-all cursor-pointer"
                                            >
                                              {startingMatchId === m.id ? (
                                                <>
                                                  <span className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                                                  <span>Đang kích hoạt...</span>
                                                </>
                                              ) : (
                                                <>
                                                  <span>▶️ Bắt Đầu Trận</span>
                                                </>
                                              )}
                                            </button>
                                          )}

                                          {m.status === 'READY' && !canManageTournament && (
                                            <div className="mt-2 pt-1 border-t border-zinc-800/60 flex items-center justify-center gap-1 text-[10px] text-cyan-400 font-semibold">
                                              <span>👁️ Xem Đối Đầu (VS)</span>
                                            </div>
                                          )}
                                        </div>
                                      );
                                    })}
                                  </div>
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      )}
                    </div>
                  )}

                  {/* CASE B: ROUND-ROBIN & SWISS (STANDINGS + PAIRINGS) */}
                  {(tournament?.format === 'ROUND_ROBIN' || tournament?.format === 'SWISS') && (
                    <div className="space-y-6 max-w-4xl mx-auto">
                      {/* Standings Table Card */}
                      <div className="bg-zinc-850/80 border border-zinc-800 rounded-2xl p-5 space-y-4">
                        <div className="flex items-center justify-between">
                          <div>
                            <h3 className="text-base font-bold text-white flex items-center gap-2">
                              <span>📊</span>
                              <span>Bảng Xếp Hạng Giải Đấu (Leaderboard)</span>
                            </h3>
                            <p className="text-xs text-zinc-400">
                              {tournament?.format === 'SWISS'
                                ? 'Hệ số Buchholz tính dựa trên chất lượng các đối thủ đã gặp.'
                                : 'Xếp hạng dựa trên tổng điểm số vòng tròn.'}
                            </p>
                          </div>
                        </div>

                        {standingsList.length === 0 ? (
                          <div className="text-center py-8 text-zinc-500 text-xs">
                            Chưa có thống kê điểm số giải đấu.
                          </div>
                        ) : (
                          <div className="overflow-x-auto">
                            <table className="w-full text-left text-xs">
                              <thead>
                                <tr className="border-b border-zinc-800 text-zinc-400 font-bold">
                                  <th className="py-2.5 px-3">Hạng</th>
                                  <th className="py-2.5 px-3">Đội Thi Đấu</th>
                                  <th className="py-2.5 px-3 text-center">Trận</th>
                                  <th className="py-2.5 px-3 text-center">Thắng</th>
                                  <th className="py-2.5 px-3 text-center">Hòa</th>
                                  <th className="py-2.5 px-3 text-center">Thua</th>
                                  <th className="py-2.5 px-3 text-right">Điểm</th>
                                  {tournament?.format === 'SWISS' && (
                                    <th className="py-2.5 px-3 text-right text-indigo-400">Buchholz</th>
                                  )}
                                  <th className="py-2.5 px-3 text-center">Trạng Thái</th>
                                </tr>
                              </thead>
                              <tbody className="divide-y divide-zinc-800/60 font-mono">
                                {standingsList.map((row, idx) => {
                                  const isMyRow = myTeam?.id === row.teamId;
                                  const medal = idx === 0 ? '🥇' : idx === 1 ? '🥈' : idx === 2 ? '🥉' : null;

                                  return (
                                    <tr
                                      key={row.teamId || idx}
                                      className={`hover:bg-zinc-800/40 transition-colors ${
                                        isMyRow ? 'bg-emerald-950/20 font-bold text-emerald-300' : 'text-zinc-300'
                                      }`}
                                    >
                                      <td className="py-3 px-3 font-bold">
                                        {medal ? <span className="text-base mr-1">{medal}</span> : `#${row.rank || idx + 1}`}
                                      </td>
                                      <td className="py-3 px-3 font-sans font-bold text-white flex items-center gap-1.5">
                                        <span>{row.teamName}</span>
                                        {isMyRow && (
                                          <span className="text-[9px] bg-emerald-500/20 text-emerald-300 px-1 py-0.5 rounded font-black">
                                            BẠN
                                          </span>
                                        )}
                                      </td>
                                      <td className="py-3 px-3 text-center text-zinc-400">{row.played}</td>
                                      <td className="py-3 px-3 text-center text-emerald-400 font-bold">{row.wins}</td>
                                      <td className="py-3 px-3 text-center text-amber-400">{row.draws}</td>
                                      <td className="py-3 px-3 text-center text-red-400">{row.losses}</td>
                                      <td className="py-3 px-3 text-right font-black text-emerald-400 text-sm">
                                        {row.score?.toFixed(1) || '0.0'}
                                      </td>
                                      {tournament?.format === 'SWISS' && (
                                        <td className="py-3 px-3 text-right font-bold text-indigo-300">
                                          {row.buchholzScore?.toFixed(1) || '0.0'}
                                        </td>
                                      )}
                                      <td className="py-3 px-3 text-center font-sans">
                                        <span className="text-[10px] px-2 py-0.5 rounded bg-zinc-800 text-zinc-400">
                                          {row.status || 'Đang đấu'}
                                        </span>
                                      </td>
                                    </tr>
                                  );
                                })}
                              </tbody>
                            </table>
                          </div>
                        )}
                      </div>

                      {/* Matches / Pairings for Round-Robin or Swiss */}
                      <div className="bg-zinc-850/80 border border-zinc-800 rounded-2xl p-5 space-y-4">
                        <div className="flex flex-wrap items-center justify-between gap-3">
                          <div>
                            <h3 className="text-base font-bold text-white flex items-center gap-2">
                              <span>⚔️</span>
                              <span>Lịch Thi Đấu & Cặp Đấu</span>
                            </h3>
                            <p className="text-xs text-zinc-400">
                              Bấm vào vòng đấu để lọc các cặp trận tương ứng.
                            </p>
                          </div>

                          {/* Round selector */}
                          <div className="flex items-center gap-1.5 overflow-x-auto">
                            <button
                              type="button"
                              onClick={() => setSelectedRoundFilter('ALL')}
                              className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all ${
                                selectedRoundFilter === 'ALL'
                                  ? 'bg-emerald-600 text-white'
                                  : 'bg-zinc-800 text-zinc-400 hover:text-zinc-200'
                              }`}
                            >
                              Tất Cả
                            </button>
                            {roundNumbers.map((r) => (
                              <button
                                key={r}
                                type="button"
                                onClick={() => setSelectedRoundFilter(String(r))}
                                className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all ${
                                  selectedRoundFilter === String(r)
                                    ? 'bg-emerald-600 text-white'
                                    : 'bg-zinc-800 text-zinc-400 hover:text-zinc-200'
                                }`}
                              >
                                Vòng {r}
                              </button>
                            ))}
                          </div>
                        </div>

                        {filteredMatches.length === 0 ? (
                          <div className="text-center py-8 text-zinc-500 text-xs">
                            Chưa có trận đấu nào trong vòng này.
                          </div>
                        ) : (
                          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                            {filteredMatches.map((m) => {
                              const isFinished = m?.status === 'FINISHED' || m?.status === 'BYE';
                              const isRunning = m?.status === 'RUNNING';
                              const isReady = m?.status === 'READY';

                              return (
                                <div
                                  key={m.id}
                                  onClick={() => {
                                    if (isFinished && m.matchId) {
                                      onOpenReplay?.({
                                        id: m.matchId,
                                        playerA: m.teamAName || 'Đội Xanh',
                                        playerB: m.teamBName || 'Đội Đỏ',
                                        winner: m.winnerTeamId === m.teamAId ? 'A' : 'B',
                                        mode: 'TOURNAMENT',
                                        matchType: 'TOURNAMENT',
                                        isRanked: false,
                                      });
                                    } else if (isReady) {
                                      setPreviewMatch(m);
                                    }
                                  }}
                                  className={`p-3.5 rounded-2xl border transition-all ${
                                    (isFinished && m.matchId) || isReady
                                      ? 'cursor-pointer hover:border-emerald-500 hover:shadow'
                                      : ''
                                  } ${
                                    isRunning
                                      ? 'bg-indigo-950/30 border-indigo-500/60'
                                      : isReady
                                      ? 'bg-zinc-850 border-emerald-500/30'
                                      : 'bg-zinc-900 border-zinc-800/80'
                                  }`}
                                >
                                  <div className="flex items-center justify-between text-[11px] text-zinc-400 pb-2 mb-2 border-b border-zinc-800">
                                    <span className="font-mono">
                                      Vòng {m.roundNo} · Trận #{m.matchNo}
                                    </span>
                                    {isFinished ? (
                                      <span className="text-emerald-400 font-bold">✓ Hoàn thành</span>
                                    ) : isRunning ? (
                                      <span className="text-indigo-400 font-bold animate-pulse">⚔️ Đang đấu</span>
                                    ) : isReady ? (
                                      <span className="text-emerald-400 font-bold">⏱️ Sẵn sàng</span>
                                    ) : (
                                      <span className="text-zinc-500">{m.status || 'Sắp đấu'}</span>
                                    )}
                                  </div>

                                  <div className="space-y-1.5">
                                    {/* Team A */}
                                    <div
                                      className={`p-2 rounded-xl flex items-center justify-between ${
                                        m.winnerTeamId === m.teamAId
                                          ? 'bg-emerald-500/15 text-emerald-300 font-bold'
                                          : 'text-zinc-300'
                                      }`}
                                    >
                                      <div className="flex items-center gap-2 truncate">
                                        <span className="w-2 h-2 rounded-full bg-blue-500" />
                                        <span className="truncate">{m.teamAName || 'TBD'}</span>
                                        {m.winnerTeamId === m.teamAId && <span>👑</span>}
                                      </div>
                                      <span className="font-mono font-black">{isFinished ? m.scoreA : '-'}</span>
                                    </div>

                                    {/* Team B */}
                                    <div
                                      className={`p-2 rounded-xl flex items-center justify-between ${
                                        m.winnerTeamId === m.teamBId
                                          ? 'bg-emerald-500/15 text-emerald-300 font-bold'
                                          : 'text-zinc-300'
                                      }`}
                                    >
                                      <div className="flex items-center gap-2 truncate">
                                        <span className="w-2 h-2 rounded-full bg-red-500" />
                                        <span className="truncate">{m.teamBName || 'TBD'}</span>
                                        {m.winnerTeamId === m.teamBId && <span>👑</span>}
                                      </div>
                                      <span className="font-mono font-black">{isFinished ? m.scoreB : '-'}</span>
                                    </div>
                                  </div>

                                  {isFinished && m.matchId && (
                                    <div className="mt-2.5 pt-1.5 border-t border-zinc-800 flex items-center justify-center gap-1 text-[11px] text-emerald-400 font-semibold">
                                      <span>▶️ Xem Replay</span>
                                    </div>
                                  )}

                                  {isReady && canManageTournament && (
                                    <button
                                      type="button"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        handleStartMatch(m.id);
                                      }}
                                      disabled={startingMatchId === m.id}
                                      className="mt-2.5 w-full py-1.5 px-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-[11px] flex items-center justify-center gap-1.5 shadow-md shadow-emerald-950/40 transition-all cursor-pointer"
                                    >
                                      {startingMatchId === m.id ? (
                                        <>
                                          <span className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                                          <span>Đang kích hoạt...</span>
                                        </>
                                      ) : (
                                        <>
                                          <span>▶️ Bắt Đầu Trận</span>
                                        </>
                                      )}
                                    </button>
                                  )}

                                  {isReady && !canManageTournament && (
                                    <div className="mt-2.5 pt-1.5 border-t border-zinc-800 flex items-center justify-center gap-1 text-[11px] text-cyan-400 font-semibold">
                                      <span>👁️ Xem Đối Đầu (VS)</span>
                                    </div>
                                  )}
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {/* Modal Quản Trị Giải Đấu */}
      {adminModalOpen && (
        <TournamentAdminModal
          tournament={tournament}
          currentUser={user}
          teams={teams}
          matches={matchesList}
          onClose={() => setAdminModalOpen(false)}
          onDeleteSuccess={() => {
            setAdminModalOpen(false);
            onClose?.();
            onTournamentUpdated?.();
          }}
          onTournamentUpdated={() => {
            loadData(true);
            onTournamentUpdated?.();
          }}
          onRefresh={() => {
            loadData(true);
            onTournamentUpdated?.();
          }}
          onToast={onToast}
        />
      )}

      {/* Modal Chi Tiết Bot */}
      {inspectedBotId && (
        <BotDetailModal
          botId={inspectedBotId}
          onClose={() => setInspectedBotId(null)}
          onOpenReplay={onOpenReplay}
        />
      )}

      {/* Màn Hình Face-off / VS Screen Trước Khi Bắt Đầu Trận Đấu */}
      {previewMatch && (
        <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-4 animate-fadeIn">
          <div className="bg-gradient-to-b from-zinc-850 to-zinc-900 border border-zinc-700 w-full max-w-2xl rounded-3xl p-6 sm:p-8 space-y-6 shadow-2xl relative">
            {/* Close button */}
            <button
              type="button"
              onClick={() => setPreviewMatch(null)}
              className="absolute top-5 right-5 w-8 h-8 rounded-full bg-zinc-800 hover:bg-zinc-700 text-zinc-400 hover:text-white flex items-center justify-center text-sm font-bold transition-colors cursor-pointer"
            >
              ✕
            </button>

            {/* Header info */}
            <div className="text-center space-y-1">
              <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 text-xs font-mono font-bold uppercase tracking-wider">
                <span>⚔️ Đối Đầu Trực Tiếp (Face-Off)</span>
                <span>·</span>
                <span>Vòng {previewMatch.roundNo} · Trận #{previewMatch.matchNo}</span>
              </div>
              <h3 className="text-xl sm:text-2xl font-black text-white tracking-tight">
                {tournament?.title || 'Giải Đấu Oẳn Tù Tì Phalanx'}
              </h3>
              <p className="text-xs text-zinc-400">
                Thể thức:{' '}
                <span className="font-semibold text-zinc-200">
                  {tournament?.format === 'SINGLE_ELIMINATION'
                    ? 'Loại Trực Tiếp (Bracket)'
                    : tournament?.format === 'SWISS'
                    ? 'Hệ Thụy Sĩ (Swiss)'
                    : 'Vòng Tròn (Round Robin)'}
                </span>{' '}
                · Đồng hồ Fischer:{' '}
                <span className="font-mono text-emerald-400">
                  {Math.floor((rules?.initialTimeSeconds || 180) / 60)}m + {rules?.incrementSeconds || 2}s
                </span>
              </p>
            </div>

            {/* Face-off Arena (Team A vs Team B) */}
            <div className="grid grid-cols-1 md:grid-cols-11 gap-4 items-center py-2">
              {/* Team A (Blue) */}
              <div className="md:col-span-5 bg-gradient-to-br from-blue-950/40 to-zinc-900 border border-blue-500/40 rounded-2xl p-5 text-center space-y-3 relative overflow-hidden">
                <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-blue-600 to-cyan-400" />
                <div className="w-16 h-16 rounded-2xl bg-blue-600/20 border border-blue-500/40 text-3xl flex items-center justify-center mx-auto shadow-inner">
                  🔵
                </div>
                <div>
                  <span className="text-[10px] font-mono font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-blue-500/20 text-blue-300 border border-blue-500/30">
                    Đội Xanh (Phe A)
                  </span>
                  <h4 className="text-base sm:text-lg font-black text-white mt-1.5 truncate">
                    {previewMatch.teamAName || 'TBD'}
                  </h4>
                  {(() => {
                    const teamA = teams.find((t) => t.id === previewMatch.teamAId);
                    return (
                      <div className="mt-2 text-xs space-y-1">
                        <div className="font-semibold text-blue-300 flex items-center justify-center gap-1">
                          <span>🤖</span>
                          <span className="truncate">{teamA?.botName || 'Bot Thuật Toán'}</span>
                        </div>
                        <div className="text-[11px] text-zinc-400 font-mono">
                          v{teamA?.versionNo || 1} · SHA:{' '}
                          <span className="text-emerald-400 font-bold">
                            {teamA?.sourceSha256?.substring(0, 8) || 'N/A'}
                          </span>
                        </div>
                        <div className="text-[11px] text-zinc-500">
                          Đội trưởng: <strong className="text-zinc-300">{teamA?.captainName || 'Sinh viên'}</strong>
                        </div>
                      </div>
                    );
                  })()}
                </div>
              </div>

              {/* Center VS Emblem */}
              <div className="md:col-span-1 flex flex-col items-center justify-center">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-amber-500 to-red-600 text-white font-black text-lg flex items-center justify-center shadow-lg shadow-amber-500/20 animate-pulse border-2 border-amber-300">
                  VS
                </div>
              </div>

              {/* Team B (Red) */}
              <div className="md:col-span-5 bg-gradient-to-br from-rose-950/40 to-zinc-900 border border-rose-500/40 rounded-2xl p-5 text-center space-y-3 relative overflow-hidden">
                <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-red-600 to-rose-400" />
                <div className="w-16 h-16 rounded-2xl bg-rose-600/20 border border-rose-500/40 text-3xl flex items-center justify-center mx-auto shadow-inner">
                  🔴
                </div>
                <div>
                  <span className="text-[10px] font-mono font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-rose-500/20 text-rose-300 border border-rose-500/30">
                    Đội Đỏ (Phe B)
                  </span>
                  <h4 className="text-base sm:text-lg font-black text-white mt-1.5 truncate">
                    {previewMatch.teamBName || 'TBD'}
                  </h4>
                  {(() => {
                    const teamB = teams.find((t) => t.id === previewMatch.teamBId);
                    return (
                      <div className="mt-2 text-xs space-y-1">
                        <div className="font-semibold text-rose-300 flex items-center justify-center gap-1">
                          <span>🤖</span>
                          <span className="truncate">{teamB?.botName || 'Bot Thuật Toán'}</span>
                        </div>
                        <div className="text-[11px] text-zinc-400 font-mono">
                          v{teamB?.versionNo || 1} · SHA:{' '}
                          <span className="text-emerald-400 font-bold">
                            {teamB?.sourceSha256?.substring(0, 8) || 'N/A'}
                          </span>
                        </div>
                        <div className="text-[11px] text-zinc-500">
                          Đội trưởng: <strong className="text-zinc-300">{teamB?.captainName || 'Sinh viên'}</strong>
                        </div>
                      </div>
                    );
                  })()}
                </div>
              </div>
            </div>

            {/* Action / Status Footer */}
            <div className="pt-2">
              {canManageTournament ? (
                <div className="space-y-3">
                  <button
                    type="button"
                    onClick={() => handleStartMatch(previewMatch.id)}
                    disabled={startingMatchId === previewMatch.id}
                    className="w-full py-3.5 px-6 rounded-2xl bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-black text-sm flex items-center justify-center gap-2 shadow-xl shadow-emerald-950/50 transition-all cursor-pointer disabled:opacity-50"
                  >
                    {startingMatchId === previewMatch.id ? (
                      <>
                        <span className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                        <span>Đang Kích Hoạt Trận Đấu...</span>
                      </>
                    ) : (
                      <>
                        <span>▶️</span>
                        <span>BẮT ĐẦU TRẬN ĐẤU NGAY</span>
                      </>
                    )}
                  </button>
                  <p className="text-center text-[11px] text-zinc-400">
                    Trận đấu sẽ được chuyển sang RUNNING, nạp snapshot bot và gửi đến Worker thực thi.
                  </p>
                </div>
              ) : (
                <div className="bg-zinc-800/80 border border-zinc-700/80 rounded-2xl p-4 text-center space-y-1">
                  <p className="text-xs font-bold text-amber-300 flex items-center justify-center gap-2">
                    <span className="animate-spin">⏳</span>
                    <span>Đang Chờ Quản trị viên kích hoạt trận đấu...</span>
                  </p>
                  <p className="text-[11px] text-zinc-400">
                    Trận đấu đã sẵn sàng. Bạn có thể theo dõi trực tiếp hoặc xem lại Replay sau khi trận kết thúc.
                  </p>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

