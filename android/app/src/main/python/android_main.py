"""Starts the Hebrew Maps server inside the Android app (called from MainActivity through Chaquopy)."""
import asyncio
import os
import threading


def start(data_dir, web_dir, port):
    os.environ["HMAPS_DATA"] = data_dir
    os.environ["HMAPS_WEB"] = web_dir
    os.environ["HMAPS_ROUTER"] = "local"
    os.environ["PYTHONUTF8"] = "1"
    os.makedirs(data_dir, exist_ok=True)

    import uvicorn

    config = uvicorn.Config("hmaps.api.app:app", host="127.0.0.1", port=int(port), log_level="warning",
                            use_colors=False, loop="asyncio", http="h11", ws="none")
    server = uvicorn.Server(config)

    def run():
        asyncio.set_event_loop(asyncio.new_event_loop())
        # no signal handlers outside the main thread
        server.install_signal_handlers = lambda: None
        server.run()

    threading.Thread(target=run, name="hmaps-server", daemon=True).start()
