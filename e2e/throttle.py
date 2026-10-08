#!/usr/bin/env python3
"""A TCP relay that limits the bandwidth from the server to the client, for the real-client scenarios H and I (a book
arriving over a slow link instead of the instant loopback).

    throttle.py --listen PORT --target HOST:PORT --kbit RATE

Every connection to 127.0.0.1:PORT is relayed to the target. The client-to-server direction is not limited. The
server-to-client direction is paced to RATE kilobit/s: the relay reads from the server only as fast as it may pass the
data on and keeps nothing buffered ahead, so the server's socket fills up and it sees a slow client, as it would on a
real link. Each connection prints "throttle: <bytes> bytes to the client in <seconds> s" when it ends.

A Minecraft server disconnects a client that does not answer a keep-alive within 15 seconds
(ServerCommonPacketListenerImpl), and the keep-alive queues behind the recipe book. The rate must therefore deliver the
whole book in well under 15 s: the compressed 9.2 MB test book is about 6.9 MB, so use at least 8000 kbit/s.
"""
import argparse
import asyncio
import signal
import sys
import time

CHUNK = 16384


async def pipe(reader, writer):
    try:
        while data := await reader.read(CHUNK):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


async def paced_pipe(reader, writer, bytes_per_second, stats):
    """Passes data on, then sleeps for as long as sending it would take at the limited rate."""
    ready = time.monotonic()
    try:
        while data := await reader.read(CHUNK):
            ready = max(ready, time.monotonic()) + len(data) / bytes_per_second
            writer.write(data)
            await writer.drain()
            stats["bytes"] += len(data)
            delay = ready - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


async def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--listen", type=int, required=True)
    parser.add_argument("--target", required=True, help="HOST:PORT of the server")
    parser.add_argument("--kbit", type=float, required=True, help="server-to-client rate in kilobit/s")
    args = parser.parse_args()
    host, port = args.target.rsplit(":", 1)
    bytes_per_second = args.kbit * 1000 / 8

    async def handle(client_reader, client_writer):
        try:
            server_reader, server_writer = await asyncio.open_connection(host, int(port))
        except OSError as e:
            print(f"throttle: cannot reach {args.target}: {e}", flush=True)
            client_writer.close()
            return
        started = time.monotonic()
        stats = {"bytes": 0}
        up = asyncio.create_task(pipe(client_reader, server_writer))
        down = asyncio.create_task(paced_pipe(server_reader, client_writer, bytes_per_second, stats))
        await asyncio.wait([up, down], return_when=asyncio.FIRST_COMPLETED)
        for task in (up, down):
            task.cancel()
        print(f"throttle: {stats['bytes']} bytes to the client in {time.monotonic() - started:.1f} s", flush=True)

    server = await asyncio.start_server(handle, "127.0.0.1", args.listen)
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    print(f"throttle: 127.0.0.1:{args.listen} -> {args.target}, {args.kbit:g} kbit/s to the client", flush=True)
    async with server:
        await stop.wait()


if __name__ == "__main__":
    asyncio.run(main())
    sys.exit(0)
