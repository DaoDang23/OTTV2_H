import React, { useState } from 'react';
import { api, csrfHeaders } from '../utils/api.js';

/**
 * Tournament Admin Modal
 * Tác vụ quản trị giải đấu (dành cho Admin & Người tạo giải):
 * - Quản lý duyệt / loại đội
 * - Khóa đăng ký, Bắt đầu giải, Tạm dừng, Tiếp tục, Hủy giải
 * - Xử lý trận lỗi (Retry) và Can thiệp phân định thắng thua thủ công (Resolve Dispute)
 */
export default function TournamentAdminModal({
  tournament,
  teams = [],
  matches = [],
  user,
  currentUser,
  onClose,
  onRefresh,
  onTournamentUpdated,
  onDeleteSuccess,
  onToast,
}) {
  const [activeTab, setActiveTab] = useState('lifecycle'); // 'lifecycle' | 'teams' | 'disputes'
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Disqualify team state
  const [disqualifyTeamId, setDisqualifyTeamId] = useState(null);
  const [disqualifyReason, setDisqualifyReason] = useState('');

  // Suspend / Cancel reason state
  const [actionPrompt, setActionPrompt] = useState(null); // 'SUSPEND' | 'CANCEL'
  const [actionReason, setActionReason] = useState('');

  // Delete confirmation state
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  // Dispute resolution state
  const [disputeMatch, setDisputeMatch] = useState(null);
  const [disputeWinnerTeamId, setDisputeWinnerTeamId] = useState('');
  const [disputeScoreA, setDisputeScoreA] = useState(2);
  const [disputeScoreB, setDisputeScoreB] = useState(0);
  const [disputeReason, setDisputeReason] = useState('');

  const tournamentId = tournament?.id;

  // Refresh data helper for parent components
  const triggerRefresh = () => {
    onRefresh?.();
    onTournamentUpdated?.();
  };

  // Safe Error Parser avoiding raw JSON strings
  const parseErrorMessage = (err, fallback = 'Đã xảy ra lỗi, vui lòng thử lại!') => {
    if (!err) return fallback;
    const rawMsg = typeof err === 'string' ? err : err?.message || '';
    let errorMsg = fallback;
    try {
      const errData = JSON.parse(rawMsg);
      errorMsg = errData.message || errData.error || rawMsg;
    } catch {
      errorMsg = rawMsg || fallback;
    }

    if (typeof errorMsg === 'string') {
      if (errorMsg.includes('requires at least 2 checked-in teams')) {
        return 'Không thể bắt đầu giải: Cần tối thiểu 2 đội đã Điểm Danh (Check-in) để xếp lịch thi đấu!';
      }
      if (errorMsg.includes('Tournament can only be started from')) {
        return 'Giải đấu chỉ có thể bắt đầu từ giai đoạn Điểm Danh (CHECK_IN) hoặc Đã Khóa Đăng Ký!';
      }
      if (errorMsg.includes('Registration can only be opened')) {
        return 'Đăng ký chỉ có thể mở khi giải ở trạng thái Bản Nháp (DRAFT) hoặc Đã Khóa Đăng Ký!';
      }
      if (errorMsg.includes('Registration can only be locked')) {
        return 'Đăng ký chỉ có thể khóa khi giải đang ở trạng thái Mở Đăng Ký (REGISTRATION_OPEN)!';
      }
      if (errorMsg.includes('Only tournaments in RUNNING state can be suspended')) {
        return 'Chỉ giải đấu đang diễn ra (RUNNING) mới có thể tạm dừng!';
      }
      if (errorMsg.includes('Tournament cannot be cancelled')) {
        return 'Không thể hủy giải đấu (giải đã hoàn tất hoặc đã bị hủy)!';
      }
      if (errorMsg.includes('<html') || errorMsg.includes('org.springframework')) {
        return fallback;
      }
    }
    return errorMsg || fallback;
  };

  const showToastError = (err, fallback = 'Đã xảy ra lỗi, vui lòng thử lại!') => {
    const text = parseErrorMessage(err, fallback);
    onToast?.({ type: 'LOSE', text });
  };

  // 1. Approve Team
  const handleApproveTeam = async (teamId) => {
    setIsSubmitting(true);
    try {
      const res = await api(`/api/admin/tournaments/${tournamentId}/teams/${teamId}/approve`, {
        method: 'POST',
        headers: await csrfHeaders(),
      });
      if (res && res.ok) {
        onToast?.({ type: 'WIN', text: '✅ Đã duyệt đội tham gia thành công!' });
        triggerRefresh();
      } else {
        const err = await res?.json().catch(() => null);
        showToastError(err, 'Không thể duyệt đội.');
      }
    } catch (err) {
      showToastError(err, 'Lỗi khi duyệt đội.');
    } finally {
      setIsSubmitting(false);
    }
  };

  // 2. Disqualify Team
  const handleDisqualifyTeam = async () => {
    if (!disqualifyTeamId || !disqualifyReason.trim()) {
      showToastError('Vui lòng nhập lý do loại đội.');
      return;
    }
    setIsSubmitting(true);
    try {
      const res = await api(`/api/admin/tournaments/${tournamentId}/teams/${disqualifyTeamId}/disqualify`, {
        method: 'POST',
        headers: await csrfHeaders(),
        body: JSON.stringify({ reason: disqualifyReason.trim() }),
      });
      if (res && res.ok) {
        onToast?.({ type: 'WIN', text: '🚫 Đã loại đội khỏi giải đấu.' });
        setDisqualifyTeamId(null);
        setDisqualifyReason('');
        triggerRefresh();
      } else {
        const err = await res?.json().catch(() => null);
        showToastError(err, 'Không thể loại đội.');
      }
    } catch (err) {
      showToastError(err, 'Lỗi khi loại đội.');
    } finally {
      setIsSubmitting(false);
    }
  };

  // 3. Lifecycle Actions
  const handleLifecycleAction = async (actionPath, payload = null, shouldCloseModal = true) => {
    if (!tournamentId) {
      showToastError('Lỗi: Không tìm thấy ID giải đấu hợp lệ.');
      return;
    }
    setIsSubmitting(true);
    try {
      const options = {
        method: 'POST',
        headers: await csrfHeaders(),
      };
      if (payload) {
        options.body = JSON.stringify(payload);
      }
      const res = await api(`/api/admin/tournaments/${tournamentId}/${actionPath}`, options);
      if (res && res.ok) {
        let successMsg = '⚡ Thao tác điều hành giải đấu thành công!';
        if (actionPath === 'open-registration') {
          successMsg = '📢 Đã mở đăng ký giải đấu thành công! Người chơi hiện có thể đăng ký tham gia.';
        } else if (actionPath === 'lock-registration') {
          successMsg = '🔒 Đã khóa đăng ký và chuyển sang giai đoạn Điểm Danh!';
        } else if (actionPath === 'start') {
          successMsg = '⚔️ Bắt đầu giải đấu thành công! Các nhánh đấu và trận mở màn đã được tạo.';
        } else if (actionPath === 'cancel') {
          successMsg = '🛑 Đã hủy giải đấu thành công!';
        } else if (actionPath === 'suspend') {
          successMsg = '⏸️ Đã tạm dừng giải đấu!';
        } else if (actionPath === 'resume') {
          successMsg = '▶️ Đã tiếp tục giải đấu thành công!';
        }

        onToast?.({ type: 'WIN', text: successMsg });
        setActionPrompt(null);
        setActionReason('');

        // Refresh underlying view so status updates immediately without F5
        triggerRefresh();

        // Automatically close admin modal on lifecycle transitions
        if (shouldCloseModal) {
          onClose?.();
        }
      } else {
        const err = await res?.json().catch(() => null);
        showToastError(err, 'Thao tác không thành công.');
      }
    } catch (err) {
      showToastError(err, 'Lỗi điều hành giải.');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Dedicated Lifecycle Handlers as requested
  const handleOpenRegistration = () => handleLifecycleAction('open-registration', null, true);
  const handleLockRegistration = () => handleLifecycleAction('lock-registration', null, true);
  const handleStartTournament = () => handleLifecycleAction('start', null, true);
  const handleSuspendTournament = () => {
    if (!actionReason.trim()) {
      showToastError('Vui lòng nhập lý do tạm dừng giải đấu.');
      return;
    }
    handleLifecycleAction('suspend', { reason: actionReason.trim() }, true);
  };
  const handleResumeTournament = () => handleLifecycleAction('resume', null, true);
  const handleCancelTournament = () => {
    if (!actionReason.trim()) {
      showToastError('Vui lòng nhập lý do hủy giải đấu.');
      return;
    }
    handleLifecycleAction('cancel', { reason: actionReason.trim() }, true);
  };

  // Dedicated Delete Tournament Handler
  const handleDeleteTournament = async () => {
    if (!tournamentId) {
      showToastError('Lỗi: Không tìm thấy ID giải đấu hợp lệ.');
      return;
    }
    setIsSubmitting(true);
    try {
      await api(`/api/admin/tournaments/${tournamentId}`, {
        method: 'DELETE',
        headers: await csrfHeaders(),
      });
      onToast?.({ type: 'WIN', text: '🗑️ Đã xóa vĩnh viễn giải đấu thành công!' });
      setShowDeleteConfirm(false);
      if (typeof onDeleteSuccess === 'function') {
        onDeleteSuccess();
      } else {
        triggerRefresh();
        onClose?.();
      }
    } catch (err) {
      showToastError(err, 'Lỗi khi xóa giải đấu.');
    } finally {
      setIsSubmitting(false);
    }
  };

  // 4. Retry match
  const handleRetryMatch = async (matchId) => {
    setIsSubmitting(true);
    try {
      const res = await api(`/api/admin/tournaments/${tournamentId}/matches/${matchId}/retry`, {
        method: 'POST',
        headers: await csrfHeaders(),
      });
      if (res && res.ok) {
        onToast?.({ type: 'WIN', text: '🔄 Đã đưa trận đấu vào hàng đợi thực thi lại (Retry)!' });
        triggerRefresh();
      } else {
        const err = await res?.json().catch(() => null);
        showToastError(err, 'Không thể retry trận đấu.');
      }
    } catch (err) {
      showToastError(err, 'Lỗi retry trận đấu.');
    } finally {
      setIsSubmitting(false);
    }
  };

  // 5. Resolve match dispute
  const handleResolveDispute = async () => {
    if (!disputeMatch?.id || !disputeWinnerTeamId || !disputeReason.trim()) {
      showToastError('Vui lòng chọn đội thắng và nhập lý do bắt buộc.');
      return;
    }
    setIsSubmitting(true);
    try {
      const payload = {
        winnerTeamId: Number(disputeWinnerTeamId),
        scoreA: Number(disputeScoreA) || 0,
        scoreB: Number(disputeScoreB) || 0,
        reason: disputeReason.trim(),
      };

      const res = await api(`/api/admin/tournaments/${tournamentId}/matches/${disputeMatch.id}/resolve-dispute`, {
        method: 'POST',
        headers: await csrfHeaders(),
        body: JSON.stringify(payload),
      });

      if (res && res.ok) {
        onToast?.({ type: 'WIN', text: '⚖️ Đã phân định kết quả trận đấu thủ công!' });
        setDisputeMatch(null);
        setDisputeWinnerTeamId('');
        setDisputeReason('');
        triggerRefresh();
      } else {
        const err = await res?.json().catch(() => null);
        showToastError(err, 'Không thể giải quyết tranh chấp.');
      }
    } catch (err) {
      showToastError(err, 'Lỗi khi giải quyết tranh chấp.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const errorMatches = matches.filter((m) => m?.status === 'ERROR');

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-3 sm:p-4 animate-fadeIn">
      <div className="bg-zinc-900 border border-zinc-750 w-full max-w-3xl max-h-[90vh] rounded-3xl shadow-2xl flex flex-col overflow-hidden text-zinc-100">
        {/* Header */}
        <div className="p-5 border-b border-zinc-800 bg-zinc-900/95 flex items-center justify-between gap-3 flex-shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="w-10 h-10 rounded-2xl bg-amber-500/20 text-amber-400 border border-amber-500/40 flex items-center justify-center text-xl">
              ⚙️
            </div>
            <div>
              <h3 className="text-lg font-black text-white">Quản Trị Giải Đấu</h3>
              <p className="text-xs text-zinc-400">
                Giải: <strong className="text-white">{tournament?.title || tournament?.name}</strong> · Trạng thái: <span className="text-amber-400 font-mono font-bold">{tournament?.status}</span>
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={onClose}
            className="p-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-300 transition-colors border border-zinc-700 text-lg font-bold leading-none"
          >
            ✕
          </button>
        </div>

        {/* Tab switcher */}
        <div className="px-5 border-b border-zinc-800 bg-zinc-900/50 flex items-center gap-2 overflow-x-auto flex-shrink-0">
          {[
            { id: 'lifecycle', label: 'Vòng Đời Giải Đấu', icon: '⚡' },
            { id: 'teams', label: `Duyệt & Quản Lý Đội (${teams.length})`, icon: '👥' },
            { id: 'disputes', label: `Xử Lý Sự Cố (${errorMatches.length})`, icon: '⚖️' },
          ].map((tab) => (
            <button
              key={tab.id}
              type="button"
              onClick={() => setActiveTab(tab.id)}
              className={`py-3 px-4 font-bold text-xs sm:text-sm border-b-2 transition-all flex items-center gap-1.5 whitespace-nowrap ${
                activeTab === tab.id
                  ? 'border-amber-500 text-amber-400 bg-amber-500/5'
                  : 'border-transparent text-zinc-400 hover:text-zinc-200'
              }`}
            >
              <span>{tab.icon}</span>
              <span>{tab.label}</span>
            </button>
          ))}
        </div>

        {/* Tab Body */}
        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          {/* TAB 1: LIFECYCLE */}
          {activeTab === 'lifecycle' && (
            <div className="space-y-5 max-w-2xl mx-auto">
              <div className="bg-zinc-850/80 p-4 rounded-2xl border border-zinc-800 space-y-2">
                <h4 className="text-xs font-bold text-zinc-300">Quy Trình Vòng Đời Giải Đấu</h4>
                <p className="text-xs text-zinc-400 leading-relaxed">
                  Trạng thái hiện tại: <strong className="text-emerald-400">{tournament?.status}</strong>. Ban tổ chức thực hiện các bước theo thứ tự: Mở đăng ký &rarr; Khóa đăng ký &rarr; Khởi tranh giải.
                </p>
              </div>

              {/* Check-In Stage Guidelines & Alert for Admin */}
              {(tournament?.status === 'CHECK_IN' || tournament?.status === 'REGISTRATION_CLOSED') && (
                <div className="bg-amber-500/10 border border-amber-500/30 p-4 rounded-2xl space-y-2 animate-fadeIn">
                  <div className="flex items-center gap-2">
                    <span className="text-amber-400 text-sm">⏱️</span>
                    <h5 className="text-xs font-bold text-amber-300">
                      Giai đoạn Điểm Danh (Check-in) trước giờ thi đấu
                    </h5>
                  </div>
                  <p className="text-xs text-amber-200/90 leading-relaxed">
                    <em>Lưu ý: Chỉ những đội đã Điểm Danh (Checked-in) mới được đưa vào lịch thi đấu.</em>
                  </p>
                  <p className="text-[11px] text-zinc-400">
                    Số đội đã điểm danh: <strong className="text-emerald-400 font-mono">{teams.filter((t) => t.status === 'CHECKED_IN').length}</strong> / <span className="font-mono">{teams.length}</span> (Cần tối thiểu 2 đội checked-in để bắt đầu giải)
                  </p>
                </div>
              )}

              {/* Action Buttons Grid */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {/* Open Registration */}
                {tournament?.status === 'DRAFT' && (
                  <button
                    type="button"
                    disabled={isSubmitting}
                    onClick={handleOpenRegistration}
                    className="p-4 rounded-2xl bg-emerald-950/40 hover:bg-emerald-900/60 text-left border border-emerald-500/60 transition-all group shadow-lg shadow-emerald-950/30"
                  >
                    <p className="font-bold text-sm text-emerald-400 flex items-center gap-1.5">
                      <span>📢</span>
                      <span>Mở Đăng Ký (Open Registration)</span>
                    </p>
                    <p className="text-xs text-zinc-300 mt-1">
                      Mở cổng cho phép người chơi đăng ký đội và chốt phiên bản bot tham gia giải đấu.
                    </p>
                  </button>
                )}

                {/* Lock Registration */}
                {tournament?.status === 'REGISTRATION_OPEN' && (
                  <button
                    type="button"
                    disabled={isSubmitting}
                    onClick={handleLockRegistration}
                    className="p-4 rounded-2xl bg-zinc-800 hover:bg-zinc-750 text-left border border-zinc-750 transition-all hover:border-amber-500/60 group"
                  >
                    <p className="font-bold text-sm text-amber-400 flex items-center gap-1.5">
                      <span>🔒</span>
                      <span>Khóa Đăng Ký</span>
                    </p>
                    <p className="text-xs text-zinc-400 mt-1">
                      Đóng cổng đăng ký để chuyển sang giai đoạn Điểm Danh (Check-in) trước giờ đấu.
                    </p>
                  </button>
                )}

                {/* Start Tournament */}
                {(tournament?.status === 'REGISTRATION_CLOSED' || tournament?.status === 'CHECK_IN') && (
                  <button
                    type="button"
                    disabled={isSubmitting}
                    onClick={handleStartTournament}
                    className="p-4 rounded-2xl bg-emerald-950/40 hover:bg-emerald-950/60 text-left border border-emerald-500/60 transition-all group shadow-lg shadow-emerald-950/30"
                  >
                    <p className="font-bold text-sm text-emerald-400 flex items-center gap-1.5">
                      <span>⚔️</span>
                      <span>Bắt Đầu Giải Đấu</span>
                    </p>
                    <p className="text-xs text-zinc-300 mt-1">
                      Hệ thống tự động xếp hạt giống, sinh nhánh đấu/lịch đấu và kích hoạt trận đầu tiên.
                    </p>
                    <p className="text-[11px] text-amber-300/90 mt-1.5 italic">
                      Lưu ý: Chỉ những đội đã Điểm Danh (Checked-in) mới được đưa vào lịch thi đấu.
                    </p>
                  </button>
                )}

                {/* Suspend */}
                {tournament?.status === 'RUNNING' && (
                  <button
                    type="button"
                    disabled={isSubmitting}
                    onClick={() => setActionPrompt('SUSPEND')}
                    className="p-4 rounded-2xl bg-amber-950/30 hover:bg-amber-950/50 text-left border border-amber-500/40 transition-all"
                  >
                    <p className="font-bold text-sm text-amber-400 flex items-center gap-1.5">
                      <span>⏸️</span>
                      <span>Tạm Dừng Giải</span>
                    </p>
                    <p className="text-xs text-zinc-400 mt-1">
                      Tạm ngưng điều phối các trận đấu mới khi có sự cố kỹ thuật.
                    </p>
                  </button>
                )}

                {/* Resume */}
                {tournament?.status === 'SUSPENDED' && (
                  <button
                    type="button"
                    disabled={isSubmitting}
                    onClick={handleResumeTournament}
                    className="p-4 rounded-2xl bg-emerald-950/30 hover:bg-emerald-950/50 text-left border border-emerald-500/40 transition-all"
                  >
                    <p className="font-bold text-sm text-emerald-400 flex items-center gap-1.5">
                      <span>▶️</span>
                      <span>Tiếp Tục Giải</span>
                    </p>
                    <p className="text-xs text-zinc-400 mt-1">
                      Tiếp tục giải đấu sau khi đã xử lý xong sự cố.
                    </p>
                  </button>
                )}

                {/* Cancel */}
                {tournament?.status !== 'CANCELLED' && tournament?.status !== 'COMPLETED' && (
                  <button
                    type="button"
                    disabled={isSubmitting}
                    onClick={() => {
                      setShowDeleteConfirm(false);
                      setActionPrompt('CANCEL');
                    }}
                    className={`p-4 rounded-2xl bg-red-950/20 hover:bg-red-950/40 text-left border border-red-500/40 transition-all ${
                      tournament?.status === 'DRAFT' ? '' : 'sm:col-span-2'
                    }`}
                  >
                    <p className="font-bold text-sm text-red-400 flex items-center gap-1.5">
                      <span>🛑</span>
                      <span>Hủy Giải Đấu</span>
                    </p>
                    <p className="text-xs text-zinc-400 mt-1">
                      Hủy bỏ toàn bộ giải đấu và các trận đấu chưa đấu. Thao tác này không thể hoàn tác.
                    </p>
                  </button>
                )}

                {/* Delete Tournament (Only DRAFT or CANCELLED) */}
                {(tournament?.status === 'DRAFT' || tournament?.status === 'CANCELLED') && (
                  <button
                    type="button"
                    disabled={isSubmitting}
                    onClick={() => {
                      setActionPrompt(null);
                      setShowDeleteConfirm(true);
                    }}
                    className={`p-4 rounded-2xl bg-rose-950/30 hover:bg-rose-900/50 text-left border border-rose-500/60 transition-all group shadow-lg shadow-rose-950/30 ${
                      tournament?.status === 'CANCELLED' ? 'sm:col-span-2' : ''
                    }`}
                  >
                    <p className="font-bold text-sm text-rose-400 flex items-center gap-1.5">
                      <span>🗑️</span>
                      <span>Xóa Vĩnh Viễn</span>
                    </p>
                    <p className="text-xs text-zinc-300 mt-1">
                      Xóa hoàn toàn dữ liệu giải đấu khỏi hệ thống. Thao tác này không thể hoàn tác.
                    </p>
                  </button>
                )}
              </div>

              {/* Delete Confirmation Dialog */}
              {showDeleteConfirm && (
                <div className="bg-rose-950/40 p-5 rounded-2xl border border-rose-500/60 space-y-3 animate-fadeIn">
                  <div className="flex items-center gap-2 text-rose-400">
                    <span className="text-xl">⚠️</span>
                    <h5 className="font-bold text-sm text-white">Xác Nhận Xóa Vĩnh Viễn Giải Đấu</h5>
                  </div>
                  <p className="text-xs text-zinc-300 leading-relaxed">
                    Bạn có chắc chắn muốn xóa vĩnh viễn giải đấu này? Thao tác này không thể hoàn tác.
                  </p>
                  <div className="flex gap-2 justify-end pt-1">
                    <button
                      type="button"
                      disabled={isSubmitting}
                      onClick={() => setShowDeleteConfirm(false)}
                      className="px-4 py-2 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 text-xs font-bold transition-colors"
                    >
                      Hủy Bỏ
                    </button>
                    <button
                      type="button"
                      disabled={isSubmitting}
                      onClick={handleDeleteTournament}
                      className="px-4 py-2 rounded-xl bg-rose-600 hover:bg-rose-500 text-white text-xs font-bold transition-colors shadow-lg shadow-rose-950/40 flex items-center gap-1.5"
                    >
                      <span>🗑️</span>
                      <span>{isSubmitting ? 'Đang Xóa...' : 'Xác Nhận Xóa'}</span>
                    </button>
                  </div>
                </div>
              )}

              {/* Action Prompt Form (Suspend or Cancel) */}
              {actionPrompt && (
                <div className="bg-zinc-850 p-4 rounded-2xl border border-zinc-700 space-y-3 animate-fadeIn">
                  <h5 className="font-bold text-xs text-white">
                    {actionPrompt === 'SUSPEND' ? 'Lý Do Tạm Dừng Giải:' : 'Lý Do Hủy Giải Đấu:'}
                  </h5>
                  <input
                    type="text"
                    required
                    value={actionReason}
                    onChange={(e) => setActionReason(e.target.value)}
                    placeholder="Nhập lý do cụ thể (lưu vào nhật ký kiểm toán)..."
                    className="w-full bg-zinc-900 border border-zinc-750 rounded-xl px-3.5 py-2 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-amber-500"
                  />
                  <div className="flex gap-2 justify-end">
                    <button
                      type="button"
                      onClick={() => {
                        setActionPrompt(null);
                        setActionReason('');
                      }}
                      className="px-3 py-1.5 rounded-xl bg-zinc-800 text-zinc-300 text-xs font-bold"
                    >
                      Hủy
                    </button>
                    <button
                      type="button"
                      disabled={isSubmitting || !actionReason.trim()}
                      onClick={() => {
                        if (actionPrompt === 'SUSPEND') {
                          handleSuspendTournament();
                        } else {
                          handleCancelTournament();
                        }
                      }}
                      className={`px-4 py-1.5 rounded-xl text-white text-xs font-bold ${
                        actionPrompt === 'SUSPEND' ? 'bg-amber-600 hover:bg-amber-500' : 'bg-red-600 hover:bg-red-500'
                      }`}
                    >
                      {isSubmitting ? 'Đang thực hiện...' : 'Xác Nhận'}
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* TAB 2: TEAMS MANAGEMENT */}
          {activeTab === 'teams' && (
            <div className="space-y-4 max-w-2xl mx-auto">
              <div className="flex items-center justify-between text-xs text-zinc-400">
                <span>Duyệt hoặc loại các đội đăng ký tham gia giải.</span>
                <span className="font-mono">{teams.length} Đội</span>
              </div>

              {teams.length === 0 ? (
                <div className="text-center py-10 text-zinc-500 text-xs">
                  Chưa có đội nào đăng ký.
                </div>
              ) : (
                <div className="space-y-2">
                  {teams.map((t) => (
                    <div
                      key={t.id}
                      className="p-3.5 rounded-2xl bg-zinc-850/80 border border-zinc-800 flex items-center justify-between gap-3 text-xs"
                    >
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-zinc-500">#{t.seed || '-'}</span>
                          <span className="font-bold text-white truncate">{t.name}</span>
                          <span className="text-[10px] px-1.5 py-0.2 rounded bg-zinc-800 text-zinc-400">
                            {t.status}
                          </span>
                        </div>
                        <p className="text-[11px] text-zinc-400 mt-0.5 truncate">
                          Đội trưởng: {t.captainName} · Bot: <span className="text-emerald-400">{t.botName}</span> (v{t.versionNo})
                        </p>
                      </div>

                      <div className="flex items-center gap-2 flex-shrink-0">
                        {t.status !== 'VERIFIED' && t.status !== 'CHECKED_IN' && t.status !== 'DISQUALIFIED' && (
                          <button
                            type="button"
                            disabled={isSubmitting}
                            onClick={() => handleApproveTeam(t.id)}
                            className="px-2.5 py-1 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs"
                          >
                            Duyệt
                          </button>
                        )}
                        {t.status !== 'DISQUALIFIED' && (
                          <button
                            type="button"
                            disabled={isSubmitting}
                            onClick={() => {
                              setDisqualifyTeamId(t.id);
                              setDisqualifyReason('');
                            }}
                            className="px-2.5 py-1 rounded-xl bg-red-600/30 hover:bg-red-600 text-red-300 hover:text-white border border-red-500/40 font-bold text-xs"
                          >
                            Loại
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {/* Disqualify Prompt */}
              {disqualifyTeamId && (
                <div className="bg-zinc-850 p-4 rounded-2xl border border-red-500/40 space-y-3 animate-fadeIn">
                  <h5 className="font-bold text-xs text-red-300">
                    Lý Do Loại Đội (Disqualify):
                  </h5>
                  <input
                    type="text"
                    required
                    value={disqualifyReason}
                    onChange={(e) => setDisqualifyReason(e.target.value)}
                    placeholder="Ví dụ: Gian lận mã nguồn, vi phạm nội quy giải đấu..."
                    className="w-full bg-zinc-900 border border-zinc-750 rounded-xl px-3.5 py-2 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-red-500"
                  />
                  <div className="flex gap-2 justify-end">
                    <button
                      type="button"
                      onClick={() => setDisqualifyTeamId(null)}
                      className="px-3 py-1.5 rounded-xl bg-zinc-800 text-zinc-300 text-xs font-bold"
                    >
                      Hủy
                    </button>
                    <button
                      type="button"
                      disabled={isSubmitting || !disqualifyReason.trim()}
                      onClick={handleDisqualifyTeam}
                      className="px-4 py-1.5 rounded-xl bg-red-600 hover:bg-red-500 text-white text-xs font-bold"
                    >
                      {isSubmitting ? 'Đang gửi...' : 'Xác Nhận Loại Đội'}
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* TAB 3: DISPUTES & ERROR MATCHES */}
          {activeTab === 'disputes' && (
            <div className="space-y-4 max-w-2xl mx-auto">
              <div className="flex items-center justify-between text-xs text-zinc-400">
                <span>Quản lý các trận đấu lỗi kỹ thuật hoặc cần phân xử kết quả.</span>
                <span className="font-mono text-red-400">{errorMatches.length} Trận Lỗi</span>
              </div>

              {/* Error Matches Section */}
              {errorMatches.length > 0 && (
                <div className="space-y-2">
                  <h5 className="text-xs font-bold text-red-400 flex items-center gap-1.5">
                    <span>⚠️</span>
                    <span>Trận Đấu Bị Lỗi Kỹ Thuật (Isolate Sandbox Error):</span>
                  </h5>
                  {errorMatches.map((m) => (
                    <div
                      key={m.id}
                      className="p-3.5 rounded-2xl bg-red-950/20 border border-red-500/40 flex items-center justify-between gap-3 text-xs"
                    >
                      <div>
                        <p className="font-bold text-white">
                          Trận #{m.matchNo} (Vòng {m.roundNo}): {m.teamAName || 'TBD'} vs {m.teamBName || 'TBD'}
                        </p>
                        <p className="text-[11px] text-zinc-400">Trạng thái: ERROR</p>
                      </div>
                      <button
                        type="button"
                        disabled={isSubmitting}
                        onClick={() => handleRetryMatch(m.id)}
                        className="px-3 py-1.5 rounded-xl bg-amber-600 hover:bg-amber-500 text-white font-bold text-xs flex items-center gap-1"
                      >
                        <span>🔄</span>
                        <span>Retry Trận</span>
                      </button>
                    </div>
                  ))}
                </div>
              )}

              {/* Dispute Resolution Section */}
              <div className="space-y-3 pt-3 border-t border-zinc-800">
                <h5 className="text-xs font-bold text-zinc-200 flex items-center gap-1.5">
                  <span>⚖️</span>
                  <span>Can Thiệp Kết Quả Trận Thủ Công (Trọng Tài):</span>
                </h5>

                <div className="space-y-2">
                  {matches.slice(0, 10).map((m) => (
                    <div
                      key={m.id}
                      className="p-3 rounded-2xl bg-zinc-850/80 border border-zinc-800 flex items-center justify-between gap-3 text-xs"
                    >
                      <div className="min-w-0">
                        <p className="font-bold text-white truncate">
                          Trận #{m.matchNo} (Vòng {m.roundNo}): {m.teamAName || 'TBD'} vs {m.teamBName || 'TBD'}
                        </p>
                        <p className="text-[11px] text-zinc-400 font-mono">
                          Trạng thái: {m.status} {m.winnerTeamName ? `· Thắng: ${m.winnerTeamName}` : ''}
                        </p>
                      </div>

                      <button
                        type="button"
                        onClick={() => {
                          setDisputeMatch(m);
                          setDisputeWinnerTeamId(m.teamAId ? String(m.teamAId) : '');
                          setDisputeScoreA(2);
                          setDisputeScoreB(0);
                          setDisputeReason('');
                        }}
                        className="px-3 py-1.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-300 font-bold text-xs border border-zinc-700 flex-shrink-0"
                      >
                        Phân Xử
                      </button>
                    </div>
                  ))}
                </div>
              </div>

              {/* Dispute Resolution Modal/Form */}
              {disputeMatch && (
                <div className="bg-zinc-850 p-4 rounded-2xl border border-amber-500/40 space-y-3 animate-fadeIn">
                  <h5 className="font-bold text-xs text-amber-300">
                    Phân Xử Trận #{disputeMatch.matchNo}: {disputeMatch.teamAName} vs {disputeMatch.teamBName}
                  </h5>

                  <div className="grid grid-cols-2 gap-3">
                    <div className="space-y-1">
                      <label className="text-[11px] text-zinc-400 block">Đội Thắng Cuộc *</label>
                      <select
                        value={disputeWinnerTeamId}
                        onChange={(e) => setDisputeWinnerTeamId(e.target.value)}
                        className="w-full bg-zinc-900 border border-zinc-750 rounded-xl px-3 py-2 text-xs text-white"
                      >
                        {disputeMatch.teamAId && (
                          <option value={disputeMatch.teamAId}>Đội A: {disputeMatch.teamAName}</option>
                        )}
                        {disputeMatch.teamBId && (
                          <option value={disputeMatch.teamBId}>Đội B: {disputeMatch.teamBName}</option>
                        )}
                      </select>
                    </div>

                    <div className="grid grid-cols-2 gap-2">
                      <div className="space-y-1">
                        <label className="text-[11px] text-zinc-400 block">Tỷ số A</label>
                        <input
                          type="number"
                          value={disputeScoreA}
                          onChange={(e) => setDisputeScoreA(Number(e.target.value))}
                          className="w-full bg-zinc-900 border border-zinc-750 rounded-xl px-3 py-2 text-xs text-white font-mono"
                        />
                      </div>
                      <div className="space-y-1">
                        <label className="text-[11px] text-zinc-400 block">Tỷ số B</label>
                        <input
                          type="number"
                          value={disputeScoreB}
                          onChange={(e) => setDisputeScoreB(Number(e.target.value))}
                          className="w-full bg-zinc-900 border border-zinc-750 rounded-xl px-3 py-2 text-xs text-white font-mono"
                        />
                      </div>
                    </div>
                  </div>

                  <div className="space-y-1">
                    <label className="text-[11px] text-zinc-400 block">Lý Do Phân Định (Bắt buộc) *</label>
                    <input
                      type="text"
                      required
                      value={disputeReason}
                      onChange={(e) => setDisputeReason(e.target.value)}
                      placeholder="Ví dụ: Đối phương ngắt kết nối quá thời gian quy định..."
                      className="w-full bg-zinc-900 border border-zinc-750 rounded-xl px-3.5 py-2 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-amber-500"
                    />
                  </div>

                  <div className="flex gap-2 justify-end">
                    <button
                      type="button"
                      onClick={() => setDisputeMatch(null)}
                      className="px-3 py-1.5 rounded-xl bg-zinc-800 text-zinc-300 text-xs font-bold"
                    >
                      Hủy
                    </button>
                    <button
                      type="button"
                      disabled={isSubmitting || !disputeReason.trim() || !disputeWinnerTeamId}
                      onClick={handleResolveDispute}
                      className="px-4 py-1.5 rounded-xl bg-amber-600 hover:bg-amber-500 text-white text-xs font-bold"
                    >
                      {isSubmitting ? 'Đang lưu...' : 'Lưu Quyết Định'}
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
