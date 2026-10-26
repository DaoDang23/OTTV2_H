import React from 'react';
import { NavLink } from 'react-router-dom';

export default function Sidebar({ user, userStats, onLogout }) {
  const menuItems = [
    { to: '/', label: 'Chơi Game', icon: '🎮', end: true },
    { to: '/battle-code', label: 'Battle Code', icon: '💻', badge: 'Hot' },
    { to: '/leaderboard', label: 'Bảng Xếp Hạng', icon: '🏆' },
    { to: '/tournaments', label: 'Giải Đấu', icon: '⚔️' },
    { to: '/profile', label: 'Hồ Sơ', icon: '👤' },
  ];

  return (
    <aside className="fixed left-0 top-0 bottom-0 w-64 bg-zinc-950 border-r border-zinc-800 flex flex-col justify-between p-4 z-40 select-none">
      <div>
        {/* Brand Logo phong cách Chess.com */}
        <div className="flex items-center gap-3 px-3 py-3 mb-6">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-emerald-600 to-teal-500 flex items-center justify-center text-xl shadow-lg shadow-emerald-950/60 font-bold">
            ⚔️
          </div>
          <div>
            <h1 className="font-black text-lg text-white tracking-wider flex items-center gap-1.5 leading-none">
              OTTV2 <span className="text-[10px] px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-400 font-bold border border-emerald-500/30">BATTLE</span>
            </h1>
            <p className="text-[10px] font-semibold text-zinc-500 uppercase tracking-widest mt-1">Bot Arena Platform</p>
          </div>
        </div>

        {/* Danh sách Menu */}
        <nav className="space-y-1.5">
          {menuItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `w-full flex items-center justify-between px-3.5 py-3 rounded-xl font-semibold text-sm transition-all duration-200 group ${
                  isActive
                    ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-950/40 font-bold'
                    : 'bg-transparent text-zinc-400 hover:text-zinc-100 hover:bg-zinc-900'
                }`
              }
            >
              {({ isActive }) => (
                <>
                  <div className="flex items-center gap-3">
                    <span className="text-lg transition-transform duration-200 group-hover:scale-110">{item.icon}</span>
                    <span>{item.label}</span>
                  </div>
                  {item.badge && (
                    <span
                      className={`text-[10px] font-black uppercase px-2 py-0.5 rounded-full ${
                        isActive ? 'bg-white/20 text-white' : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                      }`}
                    >
                      {item.badge}
                    </span>
                  )}
                </>
              )}
            </NavLink>
          ))}
        </nav>
      </div>

      {/* Thông tin User tóm tắt & Đăng xuất */}
      <div className="p-3 bg-zinc-900/90 border border-zinc-800/80 rounded-2xl flex items-center justify-between gap-2">
        <div className="flex items-center gap-3 min-w-0">
          <div className="relative flex-shrink-0">
            <img
              src={`https://api.dicebear.com/7.x/bottts/svg?seed=${user?.displayName || 'Student'}`}
              alt="User avatar"
              className="w-10 h-10 rounded-xl bg-zinc-800 p-1 border border-zinc-700"
            />
            <span className="absolute -bottom-0.5 -right-0.5 w-3 h-3 bg-emerald-500 rounded-full border-2 border-zinc-950"></span>
          </div>
          <div className="min-w-0">
            <p className="text-xs font-bold text-white truncate">{user?.displayName || 'Người Chơi'}</p>
            <div className="flex flex-col gap-0.5 mt-0.5">
              <span className="text-[10px] font-mono font-bold text-blue-400 flex items-center gap-1">
                <span>🎮 PvP:</span> <strong className="tabular-nums">{userStats?.pvpElo ?? 1200} ELO</strong>
              </span>
              <span className="text-[10px] font-mono font-bold text-emerald-400 flex items-center gap-1">
                <span>🤖 Bot:</span> <strong className="tabular-nums">{userStats?.botElo ?? 1200} ELO</strong>
              </span>
            </div>
          </div>
        </div>

        {onLogout && (
          <button
            onClick={onLogout}
            title="Đăng xuất"
            className="p-2 rounded-xl bg-transparent text-zinc-400 hover:text-rose-400 hover:bg-zinc-800 transition-colors text-xs"
          >
            🚪
          </button>
        )}
      </div>
    </aside>
  );
}
