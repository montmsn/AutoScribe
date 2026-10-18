"""AutoScribe desktop app: a pywebview window running the HTML interface in web/, driving engine.py."""
import json
import os
import sys
import threading

import webview

import i18n
from engine import (APP_NAME, APP_PATH, APP_VERSION, DEFAULT_CLI, DEFAULT_PROTOCOLS, IS_WINDOWS, LOCATIONS_FILE,
                    PROTO_NAMES, REPORTS_DIR, ConfigManager, LocationManager, WindscribeWorker, open_path,
                    resolve_cli)

if getattr(sys, "frozen", False):
    WEB_DIR = os.path.join(getattr(sys, "_MEIPASS", APP_PATH), "web")
else:
    WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")


class Bridge:
    """What the worker sees as 'the app': post(fn, *args) runs fn, and fn forwards the event to the page."""

    def __init__(self):
        self.window = None
        self._lock = threading.Lock()

    def emit(self, kind, **data):
        if self.window is None:
            return
        payload = json.dumps({"type": kind, **data}, default=str)
        with self._lock:
            try:
                self.window.evaluate_js(f"window.AS && AS.onEvent({payload})")
            except Exception as e:
                print(f"emit error: {e}")

    def post(self, fn, *args, **kwargs):
        fn(*args, **kwargs)

    # -- worker callbacks --
    def log_message(self, msg, tag="INFO"):
        self.emit("log", msg=msg, tag=tag)

    def set_phase(self, idx, state, secs=None):
        self.emit("phase", idx=idx, state=state, secs=secs)

    def begin_test(self, i, total, executed, city, node, country, route):
        self.emit("begin", i=i, total=total, executed=executed, city=city, node=node or "", country=country,
                  route=route)

    def add_result_row(self, city, node, country, proto, port, status, duration, ping, loss):
        self.emit("result", row={"city": city, "node": node or "", "country": country, "proto": proto,
                                 "port": port, "status": status, "duration": duration or 0, "ping": ping,
                                 "loss": loss})

    def set_ping(self, result):
        self.emit("ping", result=result)

    def job_finished(self, kept_connection, ok, fail):
        self.emit("finished", kept=kept_connection, ok=ok, fail=fail, stopped=api._worker.stop_event.is_set())

    def manual_connect_finished(self, r, connected):
        self.emit("manual_done", row=r, connected=connected)

    def disconnect_finished(self, done):
        self.emit("disconnect_done", done=done)

    def locations_updated(self, error):
        self.emit("locations_updated", error=error)


class Api:
    """Methods the page calls as window.pywebview.api.<name>(...)."""

    def __init__(self, bridge):
        self._bridge = bridge
        self._config = ConfigManager.load_config()
        if not self._config.get("language"):
            self._config["language"] = i18n.detect_language()
        self._worker = WindscribeWorker(bridge, resolve_cli(self._config.get("cli_path")) or DEFAULT_CLI)
        self._updating = False

    # pywebview exposes public attributes too; keep the worker out of the JS API.
    def __dir__(self):
        return [n for n in super().__dir__() if not n.startswith("_") and n != "worker"]

    def init(self):
        updated = LocationManager.last_updated()
        return {
            "version": APP_VERSION, "app": APP_NAME, "is_windows": IS_WINDOWS,
            "strings": i18n.STRINGS, "regions": i18n.REGIONS, "languages": i18n.LANGUAGES,
            "language": self._config["language"],
            "settings": self._config["last_settings"], "profiles": self._config.get("saved_profiles", {}),
            "cli_path": self._config.get("cli_path") or DEFAULT_CLI,
            "cli_ok": bool(resolve_cli(self._config.get("cli_path"))),
            "sidebar_open": self._config.get("sidebar_open", True),
            "protocols": DEFAULT_PROTOCOLS, "proto_names": PROTO_NAMES,
            "locations": LocationManager.load_locations(),
            "locations_updated": updated.strftime("%Y-%m-%d %H:%M") if updated else None,
            "results": self.results(),
            "running": self._worker.running,
        }

    def results(self):
        cols = ("city", "node", "country", "proto", "port", "status", "duration", "ping", "loss")
        return [dict(zip(cols, (c, n or "", co, p, po, s, d or 0, pi, l)))
                for c, n, co, p, po, s, d, pi, l in self._worker.db.fetch_results()]

    def save_state(self, settings=None, cli_path=None, language=None, sidebar_open=None):
        if settings is not None:
            self._config["last_settings"] = settings
        if cli_path is not None:
            self._config["cli_path"] = cli_path
        if language is not None:
            self._config["language"] = language
        if sidebar_open is not None:
            self._config["sidebar_open"] = bool(sidebar_open)
        ConfigManager.save_config(self._config)
        return True

    def save_profile(self, name, settings):
        self._config.setdefault("saved_profiles", {})[name] = settings
        ConfigManager.save_config(self._config)
        return self._config["saved_profiles"]

    def delete_profile(self, name):
        self._config.get("saved_profiles", {}).pop(name, None)
        ConfigManager.save_config(self._config)
        return self._config["saved_profiles"]

    def check_cli(self, path):
        resolved = resolve_cli(path)
        if resolved and not self._worker.running:
            self._worker.cli_cmd = resolved
        return bool(resolved)

    def browse_cli(self):
        types = ("windscribe-cli (*.exe)", "All files (*.*)") if IS_WINDOWS else ("All files (*.*)",)
        picked = self._bridge.window.create_file_dialog(webview.OPEN_DIALOG, file_types=types)
        return picked[0] if picked else None

    def stored_count(self):
        return self._worker.db.get_row_count()

    def start(self, plan, opts, cli_path):
        if self._worker.running:
            return {"ok": False, "error": "wait_run"}
        cli = resolve_cli(cli_path)
        if not cli:
            return {"ok": False, "error": "cli_missing"}
        plan = {proto: [int(p) for p in ports] for proto, ports in plan.items() if ports}
        opts["keys"] = set(opts.get("keys") or [])
        if not opts.get("resume"):
            self._worker.db.clear_db()
        self._worker.cli_cmd = cli
        self._worker.start(plan, opts)
        return {"ok": True}

    def stop(self):
        if self._worker.running:
            self._worker.stop()
        return True

    def connect(self, row, timeout, cli_path):
        if self._worker.running:
            return {"ok": False, "error": "wait_run"}
        cli = resolve_cli(cli_path)
        if not cli:
            return {"ok": False, "error": "cli_missing"}
        self._worker.cli_cmd = cli
        self._worker.connect_to(row, int(timeout))
        return {"ok": True}

    def disconnect(self, cli_path):
        if self._worker.running:
            return {"ok": False, "error": "wait_run"}
        cli = resolve_cli(cli_path)
        if not cli:
            return {"ok": False, "error": "cli_missing"}
        self._worker.cli_cmd = cli
        self._worker.disconnect()
        return {"ok": True}

    def export_csv(self, profile_name):
        count, path = self._worker.db.export_to_csv(profile_name or "Manual")
        return {"count": count, "path": path if count == -1 else os.path.basename(path)}

    def open_reports(self):
        os.makedirs(REPORTS_DIR, exist_ok=True)
        open_path(REPORTS_DIR)

    def update_locations(self):
        if self._updating:
            return False
        self._updating = True

        def work():
            try:
                LocationManager.download()
                error = None
            except Exception as e:
                error = str(e) or e.__class__.__name__
            self._updating = False
            payload = {"error": error}
            if not error:
                updated = LocationManager.last_updated()
                payload.update(locations=LocationManager.load_locations(),
                               updated=updated.strftime("%Y-%m-%d %H:%M") if updated else None)
            self._bridge.emit("locations_updated", **payload)

        threading.Thread(target=work, daemon=True).start()
        return True

    def copy_text(self, text):
        """Clipboard through Windows' own tools; the page's navigator.clipboard isn't always allowed."""
        try:
            import subprocess
            if IS_WINDOWS:
                subprocess.run(["clip"], input=text.encode("utf-16-le"), check=True,
                               creationflags=subprocess.CREATE_NO_WINDOW)
                return True
        except Exception:
            pass
        return False


def on_closing():
    if api._worker.running:
        lang = api._config.get("language", "en")
        i18n.set_language(lang)
        if not bridge.window.create_confirmation_dialog(i18n.t("quit"), i18n.t("quit_q")):
            return False
        api._worker.stop_event.set()
    ConfigManager.save_config(api._config)
    return True


bridge = Bridge()
api = Api(bridge)


def main():
    if not os.path.exists(LOCATIONS_FILE):
        print(f"locations.json missing at {LOCATIONS_FILE}")
    try:
        screen = webview.screens[0]
        sw, sh = screen.width, screen.height
    except Exception:
        sw, sh = 1600, 900
    width, height = min(1440, sw - 40), min(900, sh - 70)
    window = webview.create_window(APP_NAME, os.path.join(WEB_DIR, "index.html"), js_api=api,
                                   width=width, height=height, x=(sw - width) // 2, y=max(0, (sh - 40 - height) // 2),
                                   min_size=(1000, 620), background_color="#070A14")
    bridge.window = window
    window.events.closing += on_closing
    webview.start(debug="--debug" in sys.argv)


if __name__ == "__main__":
    main()
