import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { api, csrfHeaders, extractErrorMessage } from '../utils/api.js';
import TournamentDetailModal from './TournamentDetailModal.jsx';

/**
 * Trang Giải Đấu (Tournament Arena) - OTTV2
 * Xóa bỏ toàn bộ mock data, kết nối trực tiếp với API Backend:
 * - GET /api/tournaments
 * - POST /api/tournaments/{id}/teams
 * - POST /api/tournaments (dành cho Admin)
 */
export default function Tournaments({
  userBots = [],
  userElo = 1200,
  user = null,
  onOpenReplay = null,
  onToast = null,
}) {
  const [tournaments, setTournaments] = useState([]);
  const [myParticipations, setMyParticipations] = useState([]);
  const [myTournamentIds, setMyTournamentIds] = useState(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [statusFilter, setStatusFilter] = useState('ALL'); // 'ALL' | 'REGISTRATION_OPEN' | 'RUNNING' | 'COMPLETED'
  const [selectedTournament, setSelectedTournament] = useState(null);

  // Registration Modal State
  const [registerModalOpen, setRegisterModalOpen] = useState(false);
  const [targetTournament, setTargetTournament] = useState(null);
  const [teamName, setTeamName] = useState('');
  const [teamCode, setTeamCode] = useState('');
  const [selectedBotId, setSelectedBotId] = useState(null);
  const [botSubmissions, setBotSubmissions] = useState([]);
  const [isLoadingSubmissions, setIsLoadingSubmissions] = useState(false);
  const [isRegistering, setIsRegistering] = useState(false);
  const [registrationError, setRegistrationError] = useState(null);

  // Admin Create Tournament Modal State
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newSlug, setNewSlug] = useState('');
  const [newFormat, setNewFormat] = useState('SINGLE_ELIMINATION');
  const [newMaxTeams, setNewMaxTeams] = useState(16);
  const [newDescription, setNewDescription] = useState('');
  const [isCreating, setIsCreating] = useState(false);

  // Fetch Tournaments list from API
  const fetchTournaments = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const promises = [api('/api/tournaments')];
      if (user?.id) {
        promises.push(api('/api/tournaments/my').catch(() => null));
      }
      const [res, myRes] = await Promise.all(promises);
      if (res && res.ok) {
        const data = await res.json();
        setTournaments(Array.isArray(data) ? data : []);
      } else {
        setError('Không thể tải danh sách giải đấu từ máy chủ.');
      }
      if (myRes && myRes.ok) {
        const myData = await myRes.json();
        if (Array.isArray(myData)) {
          setMyParticipations(myData);
          setMyTournamentIds(new Set(myData.map((p) => p.tournamentId)));
        }
      }
    } catch (err) {
      console.error('Lỗi khi tải danh sách giải đấu:', err);
      setError(extractErrorMessage(err, 'Lỗi kết nối máy chủ khi tải giải đấu.'));
    } finally {
      setIsLoading(false);
    }
  }, [user?.id]);

  useEffect(() => {
    fetchTournaments();
  }, [fetchTournaments]);

  // Load submissions when a bot is selected in registration modal
  useEffect(() => {
    if (!selectedBotId) {
      setBotSubmissions([]);
      return;
    }
    let isCurrent = true;
    setIsLoadingSubmissions(true);
    api(`/api/bots/${selectedBotId}/submissions`)
      .then(async (res) => {
        if (res && res.ok && isCurrent) {
          const subs = await res.json();
          setBotSubmissions(Array.isArray(subs) ? subs : []);
        }
      })
      .catch((err) => {
        console.warn('Không thể tải bản nộp của bot:', err);
        if (isCurrent) setBotSubmissions([]);
      })
      .finally(() => {
        if (isCurrent) setIsLoadingSubmissions(false);
      });

    return () => {
      isCurrent = false;
    };
  }, [selectedBotId]);

  // Latest compiled submission for selected bot
  const latestCompiledSub = useMemo(() => {
    if (!botSubmissions?.length) return null;
    const compiled = botSubmissions.filter(
      (s) => s?.status === 'COMPILED' || s?.status === 'ACTIVE'
    );
    if (!compiled.length) return null;
    return compiled.sort((a, b) => (b?.versionNo || 0) - (a?.versionNo || 0))[0];
  }, [botSubmissions]);

  // Handle open registration modal
  const handleOpenRegister = (tourney) => {
    if (!tourney) return;
    const isAlreadyReg = Boolean(
      myTournamentIds.has(tourney.id) ||
      myParticipations.some((p) => p.tournamentId === tourney.id) ||
      tourney.teams?.some(
        (t) => t.captainId === user?.id || t.members?.some((m) => m.userId === user?.id)
      )
    );
    if (isAlreadyReg) {
      onToast?.({ type: 'WIN', text: 'Bạn đã đăng ký tham gia giải đấu này rồi!' });
      return;
    }
    setTargetTournament(tourney);
    const defaultBot = userBots?.[0] || null;
    setSelectedBotId(defaultBot?.id || null);
    const defaultName = defaultBot ? `Đội ${defaultBot.name}` : `Đội của ${user?.displayName || 'Tôi'}`;
    setTeamName(defaultName);
    setTeamCode('');
    setRegistrationError(null);
    setRegisterModalOpen(true);
  };

  // Handle submit registration
  const handleConfirmRegister = async (e) => {
    e?.preventDefault();
    if (!targetTournament?.id) return;
    if (!teamName?.trim()) {
      setRegistrationError('Vui lòng nhập tên đội thi đấu.');
      return;
    }
    if (!selectedBotId) {
      setRegistrationError('Vui lòng chọn một Bot tham gia.');
      return;
    }
    if (!latestCompiledSub) {
      setRegistrationError('Bot này chưa có bản nộp nào được biên dịch thành công.');
      return;
    }

    setIsRegistering(true);
    setRegistrationError(null);

    try {
      const payload = {
        name: teamName.trim(),
        teamCode: teamCode?.trim() || null,
        botId: selectedBotId,
        submissionId: latestCompiledSub.id,
        memberUserIds: [],
      };

      const headers = await csrfHeaders();
      const res = await api(`/api/tournaments/${targetTournament.id}/teams`, {
        method: 'POST',
        headers: {
          ...headers,
          'ngrok-skip-browser-warning': 'true',
        },
        body: JSON.stringify(payload),
      });

      if (res && res.ok) {
        onToast?.({
          type: 'WIN',
          text: `🎉 Đăng ký thành công đội [${teamName.trim()}] vào giải [${targetTournament.title || targetTournament.name}]!`,
        });
        setRegisterModalOpen(false);
        fetchTournaments();
      } else {
        const errData = await res?.json().catch(() => null);
        const rawMsg = errData?.error || errData?.message || 'Đăng ký thất bại. Vui lòng kiểm tra lại điều kiện giải.';
        const errMsg = extractErrorMessage(rawMsg, 'Đăng ký thất bại. Vui lòng kiểm tra lại điều kiện giải.');
        setRegistrationError(errMsg);
        onToast?.({ type: 'LOSE', text: errMsg });
      }
    } catch (err) {
      const errMsg = extractErrorMessage(err, 'Không thể đăng ký giải đấu vào lúc này. Vui lòng kiểm tra lại quyền hạn hoặc đăng nhập lại.');
      setRegistrationError(errMsg);
      onToast?.({ type: 'LOSE', text: errMsg });
    } finally {
      setIsRegistering(false);
    }
  };

  // Handle Admin Create Tournament
  const handleCreateTournament = async (e) => {
    e?.preventDefault();
    if (!newTitle.trim()) {
      onToast?.({ type: 'LOSE', text: 'Vui lòng nhập tên giải đấu.' });
      return;
    }

    setIsCreating(true);
    try {
      const slugVal =
        newSlug.trim() ||
        newTitle
          .toLowerCase()
          .replace(/[^a-z0-9]+/g, '-')
          .replace(/^-|-$/g, '') + `-${Date.now().toString(36)}`;

      const payload = {
        title: newTitle.trim(),
        slug: slugVal,
        description: newDescription.trim() || 'Giải đấu đối kháng chiến thuật Bot OTTV2 9x9.',
        format: newFormat,
        maxTeams: Number(newMaxTeams) || 16,
        teamSize: 1,
        rulesConfig: {
          initialTimeSeconds: 180,
          incrementSeconds: 2,
          timeoutSeconds: 3,
          memoryLimitMb: 256,
          tieBreakRules: 'BUCHHOLZ,DIRECT_ENCOUNTER',
          pointsPerWin: 3,
          pointsPerDraw: 1,
          pointsPerLoss: 0,
          swissRounds: newFormat === 'SWISS' ? 3 : null,
        },
      };

      const res = await api('/api/tournaments', {
        method: 'POST',
        headers: await csrfHeaders(),
        body: JSON.stringify(payload),
      });

      if (res && res.ok) {
        onToast?.({ type: 'WIN', text: '🏆 Tạo giải đấu mới thành công!' });
        setCreateModalOpen(false);
        setNewTitle('');
        setNewSlug('');
        setNewDescription('');
        fetchTournaments();
      } else {
        const err = await res?.json().catch(() => null);
        onToast?.({ type: 'LOSE', text: extractErrorMessage(err, 'Không thể tạo giải đấu.') });
      }
    } catch (err) {
      onToast?.({ type: 'LOSE', text: extractErrorMessage(err, 'Lỗi kết nối khi tạo giải.') });
    } finally {
      setIsCreating(false);
    }
  };

  // Filtered tournaments
  const filteredTournaments = useMemo(() => {
    if (!tournaments?.length) return [];
    if (statusFilter === 'ALL') return tournaments;
    if (statusFilter === 'REGISTRATION_OPEN') {
      return tournaments.filter((t) => t?.status === 'REGISTRATION_OPEN' || t?.status === 'CHECK_IN');
    }
    if (statusFilter === 'RUNNING') {
      return tournaments.filter((t) => t?.status === 'RUNNING');
    }
    if (statusFilter === 'COMPLETED') {
      return tournaments.filter((t) => t?.status === 'COMPLETED' || t?.status === 'FINISHED');
    }
    return tournaments;
  }, [tournaments, statusFilter]);

  // Statistics
  const stats = useMemo(() => {
    const total = tournaments?.length || 0;
    const open = tournaments?.filter((t) => t?.status === 'REGISTRATION_OPEN' || t?.status === 'CHECK_IN').length || 0;
    const running = tournaments?.filter((t) => t?.status === 'RUNNING').length || 0;
    const completed = tournaments?.filter((t) => t?.status === 'COMPLETED' || t?.status === 'FINISHED').length || 0;
    return { total, open, running, completed };
  }, [tournaments]);

  const getFormatBadge = (fmt) => {
    switch (fmt) {
      case 'SINGLE_ELIMINATION':
        return { label: 'Loại Trực Tiếp', color: 'bg-rose-500/20 text-rose-300 border-rose-500/40', icon: '⚔️' };
      case 'ROUND_ROBIN':
        return { label: 'Vòng Tròn', color: 'bg-amber-500/20 text-amber-300 border-amber-500/40', icon: '🔄' };
      case 'SWISS':
        return { label: 'Hệ Thụy Sĩ', color: 'bg-indigo-500/20 text-indigo-300 border-indigo-500/40', icon: '🇨🇭' };
      default:
        return { label: fmt || 'Tiêu chuẩn', color: 'bg-zinc-800 text-zinc-300 border-zinc-700', icon: '🏆' };
    }
  };

  const getStatusBadge = (status) => {
    switch (status) {
      case 'REGISTRATION_OPEN':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping" />
            Mở Đăng Ký
          </span>
        );
      case 'CHECK_IN':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-amber-500/20 text-amber-300 border border-amber-500/40 animate-pulse">
            ⏱️ Điểm Danh
          </span>
        );
      case 'REGISTRATION_CLOSED':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-zinc-800 text-zinc-300 border border-zinc-700">
            Đóng Đăng Ký
          </span>
        );
      case 'RUNNING':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-indigo-500/20 text-indigo-300 border border-indigo-500/40">
            <span className="w-1.5 h-1.5 rounded-full bg-indigo-400 animate-pulse" />
            Đang Diễn Ra
          </span>
        );
      case 'COMPLETED':
      case 'FINISHED':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-sky-500/20 text-sky-300 border border-sky-500/40">
            🏁 Hoàn Thành
          </span>
        );
      case 'SUSPENDED':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-red-500/20 text-red-300 border border-red-500/40">
            ⚠️ Tạm Dừng
          </span>
        );
      case 'CANCELLED':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-zinc-800 text-zinc-500 border border-zinc-700 line-through">
            Đã Hủy
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-zinc-800 text-zinc-400 border border-zinc-700">
            {status || 'Bản Nháp'}
          </span>
        );
    }
  };

  const isAdmin = user?.role === 'ADMIN';

  return (
    <div className="space-y-6 max-w-7xl mx-auto p-4 sm:p-6 text-zinc-100">
      {/* Hero Header Banner */}
      <div className="relative overflow-hidden rounded-3xl bg-gradient-to-r from-zinc-900 via-zinc-850 to-zinc-900 border border-zinc-750 p-6 sm:p-8 shadow-2xl">
        <div className="absolute top-0 right-0 -mr-16 -mt-16 w-64 h-64 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute bottom-0 left-1/3 -mb-20 w-80 h-80 bg-indigo-500/10 rounded-full blur-3xl pointer-events-none" />

        <div className="relative z-10 flex flex-col md:flex-row items-start md:items-center justify-between gap-6">
          <div className="space-y-2 max-w-2xl">
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              <span>🏆</span>
              <span>Đấu Trường Giải Đấu OTTV2</span>
            </div>
            <h1 className="text-2xl sm:text-3xl font-black text-white tracking-tight">
              Giải Đấu Thuật Toán & Tranh Cúp Vô Địch
            </h1>
            <p className="text-sm text-zinc-400 leading-relaxed">
              Tranh tài trong các thể thức Loại Trực Tiếp (Single Elimination), Vòng Tròn (Round-Robin) và Hệ Thụy Sĩ (Swiss). Tự động phân nhánh, khóa phiên bản bot bất biến và cập nhật tỷ số trực tiếp.
            </p>
          </div>

          {/* Quick Action / Stats */}
          <div className="flex flex-wrap items-center gap-3">
            {isAdmin && (
              <button
                type="button"
                onClick={() => setCreateModalOpen(true)}
                className="px-4 py-2.5 rounded-2xl bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-bold text-xs sm:text-sm shadow-lg shadow-emerald-950/40 flex items-center gap-2 transition-all"
              >
                <span>➕</span>
                <span>Tạo Giải Đấu</span>
              </button>
            )}
            <button
              type="button"
              onClick={fetchTournaments}
              disabled={isLoading}
              className="p-2.5 rounded-2xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 border border-zinc-700/80 transition-colors disabled:opacity-50"
              title="Làm mới danh sách"
            >
              <span className={`inline-block ${isLoading ? 'animate-spin' : ''}`}>🔄</span>
            </button>
          </div>
        </div>

        {/* Quick Counters Strip */}
        <div className="mt-6 pt-6 border-t border-zinc-800/80 grid grid-cols-2 sm:grid-cols-4 gap-4">
          <div className="bg-zinc-800/40 rounded-2xl p-3 border border-zinc-750/50">
            <p className="text-xs text-zinc-400">Tổng Số Giải</p>
            <p className="text-xl font-black text-white font-mono">{stats.total}</p>
          </div>
          <div className="bg-zinc-800/40 rounded-2xl p-3 border border-zinc-750/50">
            <p className="text-xs text-emerald-400">Đang Mở Đăng Ký</p>
            <p className="text-xl font-black text-emerald-300 font-mono">{stats.open}</p>
          </div>
          <div className="bg-zinc-800/40 rounded-2xl p-3 border border-zinc-750/50">
            <p className="text-xs text-indigo-400">Đang Diễn Ra</p>
            <p className="text-xl font-black text-indigo-300 font-mono">{stats.running}</p>
          </div>
          <div className="bg-zinc-800/40 rounded-2xl p-3 border border-zinc-750/50">
            <p className="text-xs text-sky-400">Đã Hoàn Thành</p>
            <p className="text-xl font-black text-sky-300 font-mono">{stats.completed}</p>
          </div>
        </div>
      </div>

      {/* Filter Tabs */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-1.5 p-1 bg-zinc-900 border border-zinc-800 rounded-2xl overflow-x-auto">
          {[
            { id: 'ALL', label: 'Tất Cả', icon: '🌐', count: stats.total },
            { id: 'REGISTRATION_OPEN', label: 'Đang Mở Đăng Ký', icon: '📝', count: stats.open },
            { id: 'RUNNING', label: 'Đang Diễn Ra', icon: '⚔️', count: stats.running },
            { id: 'COMPLETED', label: 'Đã Kết Thúc', icon: '🏁', count: stats.completed },
          ].map((tab) => (
            <button
              key={tab.id}
              type="button"
              onClick={() => setStatusFilter(tab.id)}
              className={`py-2 px-3.5 rounded-xl font-bold text-xs transition-all flex items-center gap-1.5 whitespace-nowrap ${
                statusFilter === tab.id
                  ? 'bg-zinc-800 text-white shadow'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              <span>{tab.icon}</span>
              <span>{tab.label}</span>
              <span className="px-1.5 py-0.2 rounded-md bg-zinc-750 text-[10px] text-zinc-300 font-mono">
                {tab.count}
              </span>
            </button>
          ))}
        </div>
      </div>

      {/* Error state */}
      {error && (
        <div className="bg-red-500/10 border border-red-500/30 p-4 rounded-2xl flex items-center justify-between text-xs text-red-300">
          <div className="flex items-center gap-2">
            <span>⚠️</span>
            <span>{error}</span>
          </div>
          <button
            type="button"
            onClick={fetchTournaments}
            className="px-3 py-1 rounded-xl bg-red-500/20 hover:bg-red-500/30 text-red-200 font-bold"
          >
            Thử Lại
          </button>
        </div>
      )}

      {/* Tournament Cards Grid */}
      {isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {Array.from({ length: 3 }).map((_, i) => (
            <div
              key={i}
              className="bg-zinc-900 border border-zinc-800 rounded-3xl p-6 space-y-4 animate-pulse"
            >
              <div className="h-4 bg-zinc-800 rounded-md w-1/3" />
              <div className="h-6 bg-zinc-800 rounded-md w-3/4" />
              <div className="h-12 bg-zinc-800 rounded-md w-full" />
              <div className="h-2 bg-zinc-800 rounded-full w-full" />
              <div className="h-10 bg-zinc-800 rounded-2xl w-full" />
            </div>
          ))}
        </div>
      ) : filteredTournaments.length === 0 ? (
        /* Empty State */
        <div className="text-center py-20 bg-zinc-900/60 border border-zinc-800 rounded-3xl space-y-4">
          <div className="w-16 h-16 rounded-2xl bg-zinc-850 text-zinc-500 text-3xl flex items-center justify-center mx-auto border border-zinc-800">
            🏆
          </div>
          <div className="space-y-1">
            <h3 className="text-lg font-bold text-white">Chưa Có Giải Đấu Nào</h3>
            <p className="text-xs text-zinc-400 max-w-sm mx-auto">
              {statusFilter === 'ALL'
                ? 'Hiện tại hệ thống chưa khởi tạo giải đấu nào. Ban tổ chức sẽ sớm công bố lịch thi đấu.'
                : 'Không có giải đấu nào phù hợp với bộ lọc đã chọn.'}
            </p>
          </div>
          {isAdmin && (
            <button
              type="button"
              onClick={() => setCreateModalOpen(true)}
              className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs shadow-md"
            >
              Tạo Giải Đấu Đầu Tiên
            </button>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {filteredTournaments.map((tourney) => {
            const fmt = getFormatBadge(tourney?.format);
            const isRegOpen = tourney?.status === 'REGISTRATION_OPEN';
            const isRunning = tourney?.status === 'RUNNING';
            const teamCount = tourney?.teamCount || 0;
            const maxTeams = tourney?.maxTeams || 16;
            const progress = Math.min(100, Math.round((teamCount / maxTeams) * 100));
            const isFull = teamCount >= maxTeams;

            const myParticipation = myParticipations.find((p) => p.tournamentId === tourney.id);
            const isRegistered = Boolean(
              myParticipation ||
              myTournamentIds.has(tourney.id) ||
              tourney.teams?.some(
                (t) => t.captainId === user?.id || t.members?.some((m) => m.userId === user?.id)
              )
            );

            return (
              <div
                key={tourney.id}
                className="bg-zinc-900 hover:bg-zinc-850/90 border border-zinc-800 hover:border-zinc-700/80 rounded-3xl p-5 sm:p-6 shadow-xl transition-all flex flex-col justify-between space-y-5 group"
              >
                {/* Card Top: Badges & Title */}
                <div className="space-y-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className={`px-2.5 py-0.5 rounded-lg text-[11px] font-bold border ${fmt.color} flex items-center gap-1`}>
                      <span>{fmt.icon}</span>
                      <span>{fmt.label}</span>
                    </span>
                    <div className="flex items-center gap-1.5">
                      {isRegistered && (
                        <span className="px-2 py-0.5 rounded-lg text-[11px] font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 flex items-center gap-1 shadow-sm">
                          <span>✓</span>
                          <span>Đã Đăng Ký</span>
                        </span>
                      )}
                      {getStatusBadge(tourney?.status)}
                    </div>
                  </div>

                  <div>
                    <h3 className="text-lg font-black text-white group-hover:text-emerald-400 transition-colors line-clamp-1">
                      {tourney?.title || tourney?.name || 'Giải Đấu Không Tên'}
                    </h3>
                    <p className="text-xs text-zinc-400 line-clamp-2 mt-1 leading-relaxed">
                      {tourney?.description || 'Giải đấu đối kháng chiến thuật Bot OTTV2.'}
                    </p>
                  </div>
                </div>

                {/* Card Middle: Capacity Progress & Clock config */}
                <div className="space-y-3 pt-2 border-t border-zinc-800/80">
                  {/* Progress bar */}
                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-zinc-400 font-medium flex items-center gap-1">
                        <span>👥 Đội tham gia:</span>
                      </span>
                      <span className="font-mono font-bold text-white">
                        {teamCount} / {maxTeams}
                        {isFull && <span className="ml-1 text-amber-400 font-bold">(Đầy)</span>}
                      </span>
                    </div>
                    <div className="w-full h-2 rounded-full bg-zinc-800 overflow-hidden">
                      <div
                        className={`h-full rounded-full transition-all duration-500 ${
                          isFull
                            ? 'bg-amber-500'
                            : progress > 70
                            ? 'bg-emerald-500'
                            : 'bg-emerald-600'
                        }`}
                        style={{ width: `${progress}%` }}
                      />
                    </div>
                  </div>

                  {/* Rules Pill */}
                  <div className="bg-zinc-850/90 rounded-2xl p-2.5 border border-zinc-800 flex items-center justify-between text-[11px] text-zinc-400 font-mono">
                    <span className="flex items-center gap-1">
                      <span>⏱️</span>
                      <span>
                        {Math.floor((tourney?.rulesConfig?.initialTimeSeconds || 180) / 60)}m +{' '}
                        {tourney?.rulesConfig?.incrementSeconds || 2}s
                      </span>
                    </span>
                    <span>{tourney?.teamSize || 1} Bot/Đội</span>
                  </div>
                </div>

                {/* Card Bottom: Actions */}
                <div className="pt-2 flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setSelectedTournament(tourney)}
                    className="flex-1 py-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-200 font-bold text-xs border border-zinc-700/60 transition-colors flex items-center justify-center gap-1.5"
                  >
                    <span>🔍</span>
                    <span>Xem Chi Tiết</span>
                  </button>

                  {isRegOpen && (
                    isRegistered ? (
                      <div
                        className="flex-1 py-2.5 rounded-xl font-bold text-xs bg-emerald-950/60 border border-emerald-500/50 text-emerald-300 flex items-center justify-center gap-1.5 shadow-sm"
                        title={myParticipation?.teamName ? `Đã đăng ký với đội: ${myParticipation.teamName}` : 'Bạn đã đăng ký tham gia giải này'}
                      >
                        <span>✓</span>
                        <span className="truncate">Đã Đăng Ký{myParticipation?.teamName ? ` (${myParticipation.teamName})` : ''}</span>
                      </div>
                    ) : (
                      <button
                        type="button"
                        disabled={isFull}
                        onClick={() => !isFull && handleOpenRegister(tourney)}
                        className={`flex-1 py-2.5 rounded-xl font-bold text-xs transition-colors flex items-center justify-center gap-1.5 ${
                          isFull
                            ? 'bg-zinc-800 text-zinc-500 border border-zinc-700 cursor-not-allowed'
                            : 'bg-emerald-600 hover:bg-emerald-500 text-white shadow-md shadow-emerald-950/40'
                        }`}
                      >
                        <span>📝</span>
                        <span>{isFull ? 'Đã Đầy Đội' : 'Đăng Ký Tham Gia'}</span>
                      </button>
                    )
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* REGISTRATION MODAL */}
      {registerModalOpen && targetTournament && (
        <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-4 animate-fadeIn">
          <div className="bg-zinc-900 border border-zinc-750 w-full max-w-lg rounded-3xl p-6 shadow-2xl space-y-5 text-zinc-100">
            <div className="text-center space-y-1">
              <div className="w-12 h-12 rounded-2xl bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 text-2xl flex items-center justify-center mx-auto">
                📝
              </div>
              <h3 className="text-xl font-black text-white">Đăng Ký Đội Vào Giải Đấu</h3>
              <p className="text-xs text-zinc-400 truncate">
                Giải: <strong className="text-emerald-400">{targetTournament?.title || targetTournament?.name}</strong>
              </p>
            </div>

            {registrationError && (
              <div className="p-3 rounded-2xl bg-red-500/10 border border-red-500/30 text-xs text-red-300 flex items-start gap-2">
                <span>⚠️</span>
                <span>{registrationError}</span>
              </div>
            )}

            <form onSubmit={handleConfirmRegister} className="space-y-4">
              {/* Team Name Input */}
              <div className="space-y-1.5">
                <label className="text-xs font-bold text-zinc-300 block">Tên Đội Thi Đấu *</label>
                <input
                  type="text"
                  required
                  maxLength={100}
                  value={teamName}
                  onChange={(e) => setTeamName(e.target.value)}
                  placeholder="Ví dụ: Đội Phượng Hoàng Lửa"
                  className="w-full bg-zinc-800 border border-zinc-700 rounded-xl px-3.5 py-2.5 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500"
                />
              </div>

              {/* Team Code Input (Optional) */}
              <div className="space-y-1.5">
                <label className="text-xs font-bold text-zinc-300 block">Mã Đội (Viết tắt, 2-10 ký tự)</label>
                <input
                  type="text"
                  maxLength={10}
                  value={teamCode}
                  onChange={(e) => setTeamCode(e.target.value.toUpperCase())}
                  placeholder="Ví dụ: PHL"
                  className="w-full bg-zinc-800 border border-zinc-700 rounded-xl px-3.5 py-2.5 text-xs text-white placeholder-zinc-500 font-mono uppercase focus:outline-none focus:border-emerald-500"
                />
              </div>

              {/* Bot Selector */}
              <div className="space-y-2">
                <label className="text-xs font-bold text-zinc-300 block">Chọn Bot Đại Diện *</label>
                {userBots.length === 0 ? (
                  <div className="p-4 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-300 space-y-2">
                    <p>⚠️ Bạn chưa tạo hoặc tải lên Bot nào trên hệ thống.</p>
                    <p className="text-[11px] text-zinc-400">
                      Vui lòng vào mục <strong>Đấu Trường Code (Battle Code)</strong> để tạo bot và biên dịch mã nguồn trước khi đăng ký giải đấu.
                    </p>
                  </div>
                ) : (
                  <div className="max-h-48 overflow-y-auto space-y-2 pr-1">
                    {userBots.map((b) => {
                      const isSelected = selectedBotId === b.id;
                      return (
                        <div
                          key={b.id}
                          onClick={() => setSelectedBotId(b.id)}
                          className={`p-3 rounded-2xl border cursor-pointer flex items-center justify-between transition-all ${
                            isSelected
                              ? 'bg-emerald-950/40 border-emerald-500 text-white shadow'
                              : 'bg-zinc-850 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                          }`}
                        >
                          <div className="min-w-0">
                            <p className="font-bold text-sm text-white truncate">{b.name}</p>
                            <p className="text-[11px] text-zinc-400 font-mono">
                              {b.language || 'Python'} · ELO: {b.elo || userElo}
                            </p>
                          </div>
                          <span className="text-base text-emerald-400 font-bold ml-2">
                            {isSelected ? '✓' : '○'}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* Submission Status Indicator */}
              {selectedBotId && (
                <div className="bg-zinc-850 rounded-2xl p-3 border border-zinc-800 text-xs space-y-1">
                  <p className="font-bold text-zinc-300 flex items-center gap-1.5">
                    <span>📦</span>
                    <span>Bản nộp đóng băng (Bot Freeze):</span>
                  </p>
                  {isLoadingSubmissions ? (
                    <p className="text-zinc-500 animate-pulse">Đang kiểm tra bản nộp...</p>
                  ) : latestCompiledSub ? (
                    <div className="text-[11px] text-zinc-400 font-mono space-y-0.5">
                      <p className="text-emerald-400 font-bold">
                        ✓ Phiên bản v{latestCompiledSub.versionNo} ({latestCompiledSub.language})
                      </p>
                      <p>
                        SHA-256: <span className="text-zinc-300">{latestCompiledSub.sourceSha256?.substring(0, 12)}...</span>
                      </p>
                      <p className="text-[10px] text-zinc-500">
                        Phiên bản này sẽ được chụp lại (snapshot) bất biến cho toàn bộ các trận đấu.
                      </p>
                    </div>
                  ) : (
                    <p className="text-amber-400 text-[11px]">
                      ⚠️ Bot này chưa có bản nộp nào ở trạng thái COMPILED. Vui lòng biên dịch bot trước.
                    </p>
                  )}
                </div>
              )}

              {/* Buttons */}
              <div className="flex gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setRegisterModalOpen(false)}
                  className="flex-1 py-3 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 font-bold text-xs"
                >
                  Hủy Bỏ
                </button>
                <button
                  type="submit"
                  disabled={isRegistering || !latestCompiledSub || userBots.length === 0}
                  className="flex-1 py-3 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs shadow-lg shadow-emerald-950/40 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-1.5"
                >
                  {isRegistering ? (
                    <>
                      <span className="inline-block animate-spin">🔄</span>
                      <span>Đang Gửi...</span>
                    </>
                  ) : (
                    <span>Xác Nhận Đăng Ký</span>
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ADMIN CREATE TOURNAMENT MODAL */}
      {createModalOpen && (
        <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-4 animate-fadeIn">
          <div className="bg-zinc-900 border border-zinc-750 w-full max-w-lg rounded-3xl p-6 shadow-2xl space-y-5 text-zinc-100">
            <div className="text-center space-y-1">
              <div className="w-12 h-12 rounded-2xl bg-indigo-500/20 text-indigo-400 border border-indigo-500/40 text-2xl flex items-center justify-center mx-auto">
                🏆
              </div>
              <h3 className="text-xl font-black text-white">Khởi Tạo Giải Đấu Mới</h3>
              <p className="text-xs text-zinc-400">
                Dành cho Quản Trị Viên (Admin) và Ban Tổ Chức giải đấu.
              </p>
            </div>

            <form onSubmit={handleCreateTournament} className="space-y-4">
              <div className="space-y-1.5">
                <label className="text-xs font-bold text-zinc-300 block">Tên Giải Đấu *</label>
                <input
                  type="text"
                  required
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="Ví dụ: OTTV2 Spring Championship 2026"
                  className="w-full bg-zinc-800 border border-zinc-700 rounded-xl px-3.5 py-2.5 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-zinc-300 block">Thể Thức *</label>
                  <select
                    value={newFormat}
                    onChange={(e) => setNewFormat(e.target.value)}
                    className="w-full bg-zinc-800 border border-zinc-700 rounded-xl px-3.5 py-2.5 text-xs text-white focus:outline-none focus:border-indigo-500"
                  >
                    <option value="SINGLE_ELIMINATION">Loại Trực Tiếp (Knockout)</option>
                    <option value="ROUND_ROBIN">Vòng Tròn (Round-Robin)</option>
                    <option value="SWISS">Hệ Thụy Sĩ (Swiss)</option>
                  </select>
                </div>

                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-zinc-300 block">Số Đội Tối Đa *</label>
                  <select
                    value={newMaxTeams}
                    onChange={(e) => setNewMaxTeams(Number(e.target.value))}
                    className="w-full bg-zinc-800 border border-zinc-700 rounded-xl px-3.5 py-2.5 text-xs text-white focus:outline-none focus:border-indigo-500 font-mono"
                  >
                    <option value={4}>4 Đội</option>
                    <option value={8}>8 Đội</option>
                    <option value={16}>16 Đội</option>
                    <option value={32}>32 Đội</option>
                  </select>
                </div>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-bold text-zinc-300 block">Mô Tả & Thể Lệ</label>
                <textarea
                  rows={3}
                  value={newDescription}
                  onChange={(e) => setNewDescription(e.target.value)}
                  placeholder="Mô tả tóm tắt giải đấu, đối tượng tham gia và giải thưởng..."
                  className="w-full bg-zinc-800 border border-zinc-700 rounded-xl px-3.5 py-2.5 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-indigo-500 resize-none"
                />
              </div>

              <div className="flex gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setCreateModalOpen(false)}
                  className="flex-1 py-3 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 font-bold text-xs"
                >
                  Hủy Bỏ
                </button>
                <button
                  type="submit"
                  disabled={isCreating}
                  className="flex-1 py-3 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-bold text-xs shadow-lg shadow-indigo-950/40 disabled:opacity-50 flex items-center justify-center gap-1.5"
                >
                  {isCreating ? 'Đang Tạo...' : 'Tạo Giải Đấu'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* TOURNAMENT DETAIL MODAL */}
      {selectedTournament && (
        <TournamentDetailModal
          tournament={selectedTournament}
          user={user}
          userBots={userBots}
          onClose={() => setSelectedTournament(null)}
          onOpenReplay={onOpenReplay}
          onToast={onToast}
          onRegisterClick={(tourney) => handleOpenRegister(tourney)}
          onTournamentUpdated={fetchTournaments}
        />
      )}
    </div>
  );
}
