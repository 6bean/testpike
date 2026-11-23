"""WebSocket fixture station for the api-test ws branch (port 8898).

Why a separate process: serve_fixtures.py is a plain http.server — it can never
upgrade a WebSocket handshake, and bolting asyncio onto it would risk the whole
F-8/B11 static-fixture line for zero benefit. This station is the minimal
deterministic ws target the executor tests dial for real (red line 1: the ws
consumption branch must face a live socket, not a mock).

Endpoints (path-selected inside one handler):
 /echo — sends "welcome" on connect, then replies "echo:<text>" per message
 /order — streams alpha/beta/gamma in a fixed order, then closes
 /freq — one "tick" every 200ms for ~2s (frequency assertions measure it)
 /closed — accepts the handshake and closes at once (reachable, zero messages)
 /reject — refuses the upgrade with HTTP 404 (handshake-refusal attribution)

Run: python3 ws_station.py [port] (default 8898)
"""
from __future__ import annotations

import asyncio
import sys

from websockets.asyncio.server import serve

DEFAULT_PORT = 8898


async def _endpoint(ws, path: str):
    if path == "/order":
        for word in ("alpha", "beta", "gamma"):
            await ws.send(word)
        return
    if path == "/freq":
        deadline = asyncio.get_running_loop().time() + 2.0
        while asyncio.get_running_loop().time() < deadline:
            await ws.send("tick")
            await asyncio.sleep(0.2)
        return
    if path == "/closed":
        return
    # /echo (and any unknown path): greeting + echo loop until the client leaves
    await ws.send("welcome")
    async for text in ws:
        await ws.send(f"echo:{text}")


async def _process_request(connection, request):
    if request.path == "/reject":
        return connection.respond(404, "fixture refuses this upgrade\n")
    return None


async def main(port: int):
    async with serve(_endpoint_wrapper, "127.0.0.1", port,
                     process_request=_process_request):
        await asyncio.Future()  # run forever until the process is terminated


async def _endpoint_wrapper(ws):
    path = (ws.request.path if ws.request else "") or "/"
    await _endpoint(ws, path.split("?")[0])


if __name__ == "__main__":
    p = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    try:
        asyncio.run(main(p))
    except KeyboardInterrupt:
        pass
