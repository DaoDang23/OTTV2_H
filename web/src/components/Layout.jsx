import React from 'react';
import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar.jsx';
import ChallengeModal from './ChallengeModal.jsx';
import ReplayModal from './ReplayModal.jsx';
import LocalGameModal from './LocalGameModal.jsx';
import RoomGameModal from './RoomGameModal.jsx';
import ErrorBoundary from './ErrorBoundary.jsx';

export default function Layout({
  user,
  userStats,
  pvpElo,
  botElo,
  bots,
  isBackendOffline,
  onRetryConnection,
  eloToast,
  onCloseToast,
  onLogout,
  // Modals state & handlers
  challengeTarget,
  setChallengeTarget,
  handleConfirmChallenge,
  selectedMatch,
  setSelectedMatch,
  replayData,
  handleDownloadLog,
  isLocalGameOpen,
  setIsLocalGameOpen,
  localTimeControl,
  setLocalTimeControl,
  pvpGameConfig,
  setPvpGameConfig,
  roomGameConfig,
  setRoomGameConfig,
  handleCloseRoomGame,
  handleOpenReplay,
  handlePvpMatchFinished,
  outletContext,
}) {
  return (
    <div className={`min-h-screen bg-zinc-900 text-zinc-200 font-sans flex antialiased ${isBackendOffline ? 'pt-10' : ''}`}>
      {/* 0. Banner Thông Báo Chế Độ Offline khi Backend 8080 đang tắt */}
      {isBackendOffline && (
        <div className="fixed top-0 left-0 right-0 z-50 bg-amber-950/95 border-b border-amber-500/40 text-amber-200 px-4 py-2 text-xs flex items-center justify-between shadow-2xl backdrop-blur">
          <div className="flex items-center gap-2">
            <span className="text-base">⚠️</span>
            <span>
              <strong>Chế độ Offline Local (Backend 8080 đang tắt)</strong> - Chạy{' '}
              <code className="bg-zinc-900 border border-amber-500/30 px-1.5 py-0.5 rounded text-amber-300 font-mono font-bold">
                .\mvnw.cmd spring-boot:run -pl api
              </code>{' '}
              để bật lại. Đang dùng dữ liệu đã lưu từ lần đăng nhập gần nhất.
            </span>
          </div>
          {onRetryConnection && (
            <button
              type="button"
              onClick={onRetryConnection}
              className="px-3 py-1 bg-amber-600/30 hover:bg-amber-600/50 border border-amber-500/50 rounded-lg text-amber-100 font-bold text-xs flex items-center gap-1 transition-all"
            >
              <span>🔄</span>
              <span>Thử lại</span>
            </button>
          )}
        </div>
      )}

      {/* Toast Notification Banner */}
      {eloToast && (
        <div
          className={`fixed top-6 right-6 z-50 p-4 rounded-2xl shadow-2xl border flex items-center gap-3 max-w-md animate-fadeIn ${
            eloToast.type === 'WIN'
              ? 'bg-emerald-950 border-emerald-500/80 text-emerald-100'
              : eloToast.type === 'LOSE'
              ? 'bg-rose-950 border-rose-500/80 text-rose-100'
              : 'bg-zinc-850 border-zinc-700 text-zinc-200'
          }`}
        >
          {!(eloToast.text && (eloToast.text.startsWith('🎉') || eloToast.text.startsWith('💔') || eloToast.text.startsWith('🤝') || eloToast.text.startsWith('⚠️') || eloToast.text.startsWith('🏆') || eloToast.text.startsWith('ℹ️'))) && (
            <span className="text-xl">{eloToast.type === 'WIN' ? '🎉' : eloToast.type === 'LOSE' ? '💔' : 'ℹ️'}</span>
          )}
          <p className="text-xs font-semibold flex-1 leading-relaxed">{eloToast.text}</p>
          <button
            type="button"
            onClick={onCloseToast}
            className="p-1 text-zinc-400 hover:text-white text-xs"
          >
            ✕
          </button>
        </div>
      )}

      {/* 1. Sidebar Cố Định (Fixed Left) */}
      <Sidebar
        user={user}
        userStats={userStats}
        onLogout={onLogout}
      />

      {/* 2. Main Content Động theo Route */}
      <main className="ml-64 flex-1 p-8 lg:p-10 min-h-screen overflow-y-auto">
        <Outlet context={outletContext} />
      </main>

      {/* 3. Modal Thách Đấu "Gạ Kèo" */}
      <ChallengeModal
        target={challengeTarget}
        pvpElo={pvpElo}
        botElo={botElo}
        userBots={bots}
        matchType={challengeTarget?.matchType || (challengeTarget?.isPvP ? 'PVP' : 'BOT')}
        onClose={() => setChallengeTarget(null)}
        onConfirm={handleConfirmChallenge}
      />

      {/* 4. Modal Replay Bàn Cờ 9x9 (Bọc ErrorBoundary chống crash) */}
      <ErrorBoundary onReset={() => setSelectedMatch(null)}>
        {selectedMatch && (
          <ReplayModal
            match={selectedMatch}
            replay={replayData}
            onClose={() => setSelectedMatch(null)}
            onDownloadLog={handleDownloadLog}
          />
        )}
      </ErrorBoundary>

      {/* 5. Modal 2 Người Cùng Máy (Local) hoặc Đấu PvP Fischer Clock */}
      <ErrorBoundary onReset={() => { setIsLocalGameOpen(false); setLocalTimeControl(null); setPvpGameConfig(null); }}>
        {isLocalGameOpen && (
          <LocalGameModal
            onClose={() => {
              setIsLocalGameOpen(false);
              setLocalTimeControl(null);
              setPvpGameConfig(null);
            }}
            initialTimeControl={localTimeControl || pvpGameConfig?.timeControl}
            timeControl={localTimeControl || pvpGameConfig?.timeControl}
            opponentName={pvpGameConfig?.target?.name}
            onOpenReplay={({ matchInfo, replay }) => {
              handleOpenReplay(matchInfo, replay);
            }}
            onMatchFinished={handlePvpMatchFinished}
          />
        )}
      </ErrorBoundary>

      {/* 6. Modal Chơi Qua Mã Phòng & Ghép Trận PvP */}
      <ErrorBoundary onReset={() => (handleCloseRoomGame ? handleCloseRoomGame() : setRoomGameConfig(null))}>
        {roomGameConfig && (
          <RoomGameModal
            roomCode={roomGameConfig.roomCode}
            matchId={roomGameConfig.matchId || roomGameConfig.matchPublicId}
            isHost={roomGameConfig.isHost}
            isQuickMatch={roomGameConfig.isQuickMatch}
            initialTimeControl={roomGameConfig.initialTimeControl}
            timeControl={roomGameConfig.timeControl || roomGameConfig.initialTimeControl}
            userName={user?.displayName || user?.studentCode || 'Người chơi'}
            pvpElo={pvpElo}
            targetOpponent={roomGameConfig.targetOpponent}
            playerToken={roomGameConfig.playerToken}
            onClose={handleCloseRoomGame || (() => setRoomGameConfig(null))}
            onMatchFinished={handlePvpMatchFinished}
            onOpenReplay={({ matchInfo, replay }) => {
              handleOpenReplay(matchInfo, replay);
            }}
          />
        )}
      </ErrorBoundary>
    </div>
  );
}
