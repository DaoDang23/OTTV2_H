# 1. Record Architecture Decisions

Date: 2026-10-15

## Status

Accepted

## Context

We need a structured way to document architectural and design decisions for OTTV2 so developers and AI agents can understand the rationale behind system designs and prevent contradictory implementations.

## Decision

We adopt the Architecture Decision Record (ADR) standard under `docs/adr/NNNN-<slug>.md`.

Key architectural principles already established:
1. **Separation of ELO Systems**: `pvpElo` (real-time manual play) and `botElo` (programmed bot competitions) operate completely independently.
2. **Fischer Chess Clocks**: Room and PvP matches enforce Blitz/Rapid/Bullet Fischer time increments.
3. **Board Perspective Decoupling**: Visual flipping (`isFlipped`) is strictly a presentation-layer transformation; game state coordinates remain strictly canonical `(r, c)`.
4. **Sparring AI Fallback**: Matchmaking smoothly transitions to an offline heuristic Sparring AI if human opponents are unavailable.

## Consequences

- All future major design changes should record an ADR in `docs/adr/`.
- AI agents running `/domain-modeling`, `/grill-with-docs`, or `/implement` must check `docs/adr/` before proposing structural alterations.
