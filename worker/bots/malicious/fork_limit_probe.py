#!/usr/bin/env python3
"""Bounded probe: try to hold more children than the Isolate PID limit allows."""
import os
import sys
import time

children = []
for _ in range(64):
    try:
        child = os.fork()
    except OSError as error:
        print(f"fork stopped after {len(children)} children: {error}", file=sys.stderr, flush=True)
        break
    if child == 0:
        time.sleep(1)
        os._exit(0)
    children.append(child)

for child in children:
    os.waitpid(child, 0)
