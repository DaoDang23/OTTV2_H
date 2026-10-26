#!/usr/bin/env python3
"""Template: implement decide(state) and return {"pieceId": int, "to": [row, col]}."""
import json
import sys


def decide(state):
    # Minimal deterministic example: move the first piece one step toward target.
    targets = state.get("targets", [[4, 4]])
    target = targets[0]
    for piece in state.get("yourPieces", []):
        r, c = piece["r"], piece["c"]
        if r != target[0]:
            return {"pieceId": piece["id"], "to": [r + (1 if target[0] > r else -1), c]}
        if c != target[1]:
            return {"pieceId": piece["id"], "to": [r, c + (1 if target[1] > c else -1)]}
    return {"pieceId": state["yourPieces"][0]["id"], "to": [state["yourPieces"][0]["r"] - 1, state["yourPieces"][0]["c"]]}


for line in sys.stdin:
    try:
        state = json.loads(line)
        print(json.dumps(decide(state), separators=(",", ":")), flush=True)
    except Exception:
        # Keep stdout protocol-only. Diagnostics belong on stderr.
        print("invalid input", file=sys.stderr, flush=True)
        break
