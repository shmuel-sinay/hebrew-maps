"""Starts the Hebrew Maps server inside the Android app (called from ServerService through Chaquopy)."""
import asyncio
import os
import threading
import traceback

_error = ""


def last_error():
    return _error


def start(data_dir, web_dir, port):
    global _error
    os.environ["HMAPS_DATA"] = data_dir
    os.environ["HMAPS_WEB"] = web_dir
    os.environ["HMAPS_ROUTER"] = "local"
    os.environ["PYTHONUTF8"] = "1"
    os.makedirs(data_dir, exist_ok=True)

    import uvicorn
    import hmaps.api.app  # noqa: F401  - import here so a failure is reported to the screen

    config = uvicorn.Config("hmaps.api.app:app", host="127.0.0.1", port=int(port), log_level="warning",
                            use_colors=False, loop="asyncio", http="h11", ws="none")
    server = uvicorn.Server(config)

    def run():
        global _error
        try:
            asyncio.set_event_loop(asyncio.new_event_loop())
            server.run()
        except BaseException:  # noqa: BLE001
            _error = traceback.format_exc()

    threading.Thread(target=run, name="hmaps-server", daemon=True).start()
