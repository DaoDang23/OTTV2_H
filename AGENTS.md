# AGENTS.md

Instructions, conventions, and agent configuration for OTTV2 (Battle Code Arena).

## Tech Stack & Architecture

- **Backend**: Java 17+, Spring Boot (REST API, STOMP/WebSocket), Maven wrapper (`mvnw`).
  - `engine/`: Core 9x9 Rock-Paper-Scissors board rules, movement validation, symmetric map generator.
  - `api/`: Spring Boot REST controllers, authentication, match orchestrator, and WebSocket endpoints.
  - `worker/`: Bot match evaluation runner and sandbox execution.
- **Frontend**: React 18+, Vite, Tailwind CSS, Lucide React, HTML5 Canvas / PixiJS (`web/`).
  - `web/src/components/Board.jsx`: Visual 9x9 tactical board renderer.
  - `web/src/components/RoomGameModal.jsx`: Real-time PvP room match with Fischer Chess Clock, board flip (`isFlipped`), and Sparring AI backup.
  - `web/src/components/BattleCode.jsx`: Bot IDE and code submission for Battle Code matches.
  - `web/src/utils/sparringAi.js`: 12 Sparring AI machines (Máy 1-12) with 8 distinct algorithmic strategies.

## Build & Test Commands

- **Backend Tests**:
  ```powershell
  .\mvnw.cmd test
  ```
- **Frontend Dev Server**:
  ```powershell
  cd web; npm run dev
  ```
- **Frontend Production Build**:
  ```powershell
  cd web; npm run build
  ```

## Core Domain Rules & Conventions

1. **Board Coordinates**:
   - 9x9 grid, rows `0..8` (displayed as `1..9`), columns `0..8` (displayed as `A..I`).
   - Team A (Blue) base: `(8, 0)` / `A9`.
   - Team B (Red) base: `(0, 8)` / `I1`.
   - Moving into enemy base or eliminating all enemy pieces wins the match.
2. **Dual ELO Rating**:
   - `pvpElo`: Rating earned exclusively in human PvP matches (starts at 1200).
   - `botElo`: Rating earned exclusively in programmed Bot matches (starts at 1200).
3. **Fischer Chess Clock**:
   - Blitz: `03:00 + 2s/move`
   - Rapid: `05:00 + 3s/move`
   - Bullet: `01:00 + 1s/move`

---

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Standard 5 canonical roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout (`GLOSSARY.md` and `docs/adr/` at the repo root). See `docs/agents/domain.md`.
