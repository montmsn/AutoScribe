"""AutoScribe engine: config, server list, ping, results database and the Windscribe test worker.
No UI code lives here; the interface talks to it through an object with a post(fn, *args) method."""
import os
import sys

# PyInstaller --noconsole stream redirect fix
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w")

import csv
import datetime
import json
import platform
import random
import re
import shutil
import sqlite3
import subprocess
import threading
import time
import urllib.request

APP_NAME = "AutoScribe"
APP_VERSION = "2.1.0"

IS_WINDOWS = platform.system() == "Windows"

PROTO_NAMES = {"wireguard": "WireGuard", "ikev2": "IKEv2", "udp": "UDP", "tcp": "TCP",
               "stealth": "Stealth", "wstunnel": "WStunnel"}

DEFAULT_PROTOCOLS = {
    "wireguard": [443, 80, 53, 123, 1194, 65142],
    "ikev2": [500],
    "udp": [443, 80, 53, 123, 1194, 54783],
    "tcp": [443, 587, 21, 22, 80, 123, 143, 3306, 8080, 54783, 1194],
    "stealth": [443, 587, 21, 22, 80, 123, 143, 3306, 8080, 54783, 8443],
    "wstunnel": [443],
}

if getattr(sys, "frozen", False):
    APP_PATH = os.path.dirname(sys.executable)
else:
    APP_PATH = os.path.dirname(os.path.abspath(__file__))

DB_FILE = os.path.join(APP_PATH, "windscribe_results.db")
CONFIG_FILE = os.path.join(APP_PATH, "config.json")
LOCATIONS_FILE = os.path.join(APP_PATH, "locations.json")
REPORTS_DIR = os.path.join(APP_PATH, "reports")

DEFAULT_CLI = r"C:\Program Files\Windscribe\windscribe-cli.exe" if IS_WINDOWS else "windscribe-cli"
# The public server list that windscribe.com/vpn-servers loads; same format as locations.json.
LOCATIONS_URL = "https://api.windscribe.com/WebsiteStatus"


def hidden_process_kwargs():
    """Keeps console windows from flashing up on Windows."""
    if IS_WINDOWS:
        return {"creationflags": subprocess.CREATE_NO_WINDOW}
    return {}


def resolve_cli(path):
    """Returns a usable path to windscribe-cli, or None."""
    path = (path or "").strip().strip('"')
    if not path:
        return None
    if os.path.isfile(path):
        return path
    return shutil.which(path)


def open_path(path):
    try:
        if IS_WINDOWS:
            os.startfile(path)
        elif platform.system() == "Darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path])
    except Exception as e:
        print(f"Open Error: {e}")


def loc_key(loc):
    return f"{loc['country']}|{loc['city']}|{loc['location']}"


def loc_name(city, node):
    return f"{city} – {node}" if node else city


def matches_region(loc, region):
    if region == "All":
        return True
    if region == "USA":
        return loc["country"].startswith("US ")
    return loc["continent"] == region


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
class ConfigManager:
    @staticmethod
    def default_settings():
        settings = {
            "timeout": "25",
            "settle": "2",
            "ping_host": "1.1.1.1",
            "ping_count": "5",
            "resume": False,
            "selected": [],
            "random_cities": False,
            "chaos_mode": False,
            "stop_on_success": False,
            "smart_scan": True,
            "priority_order": list(DEFAULT_PROTOCOLS.keys()),
            "protocols": {},
        }
        for proto, ports in DEFAULT_PROTOCOLS.items():
            settings["protocols"][proto] = {"enabled": True, "ports": {str(p): True for p in ports}}
        return settings

    @staticmethod
    def load_config():
        default_data = {"saved_profiles": {}, "last_settings": ConfigManager.default_settings(),
                        "cli_path": DEFAULT_CLI, "window": "", "sidebar_open": True, "language": ""}
        if not os.path.exists(CONFIG_FILE):
            return default_data
        try:
            with open(CONFIG_FILE, "r") as f:
                loaded = json.load(f)
            for key, val in default_data.items():
                loaded.setdefault(key, val)
            for key, val in default_data["last_settings"].items():
                loaded["last_settings"].setdefault(key, val)
            return loaded
        except Exception as e:
            print(f"Config Load Error: {e}")
            return default_data

    @staticmethod
    def save_config(data):
        try:
            with open(CONFIG_FILE, "w") as f:
                json.dump(data, f, indent=4)
        except Exception as e:
            print(f"Config Save Error: {e}")


# ---------------------------------------------------------------------------
# Locations (Windscribe's locations.json)
# ---------------------------------------------------------------------------
class LocationManager:
    @staticmethod
    def load_locations():
        """Returns a flat list of dicts: city, location, country, continent."""
        locations = []
        if not os.path.exists(LOCATIONS_FILE):
            return locations
        try:
            with open(LOCATIONS_FILE, "r", encoding="utf-8") as f:
                raw = json.load(f)

            for _, entry in raw.get("data", {}).get("locations", {}).items():
                country = (entry.get("name") or "Unknown").strip()
                continent = (entry.get("continent") or "Unknown").strip()

                for pop in entry.get("pops", []):
                    if pop.get("status", 1) != 1:
                        continue  # skip offline/disabled nodes
                    pop_name = (pop.get("name") or "").strip()
                    if not pop_name:
                        continue
                    if " - " in pop_name:
                        city, location = pop_name.split(" - ", 1)
                    else:
                        city, location = pop_name, ""

                    locations.append({
                        "city": city.strip(),
                        "location": location.strip(),
                        "country": country,
                        "continent": continent,
                    })
        except Exception as e:
            print(f"Locations Load Error: {e}")
        return locations

    @staticmethod
    def download():
        """Fetches the current server list from windscribe.com and replaces locations.json.
        Raises on any network or format problem, leaving the old file untouched."""
        req = urllib.request.Request(LOCATIONS_URL, headers={
            "User-Agent": f"{APP_NAME}/{APP_VERSION}", "Accept": "application/json",
            # The same placeholder token windscribe.com's own server list page sends.
            "Authorization": "Bearer 1234"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read()
        data = json.loads(raw.decode("utf-8"))
        locations = data.get("data", {}).get("locations")
        if not isinstance(locations, dict) or not locations:
            raise ValueError("the response did not contain a server list")
        if not any(isinstance(v, dict) and v.get("pops") for v in locations.values()):
            raise ValueError("the server list has no servers in it")
        tmp = LOCATIONS_FILE + ".tmp"
        with open(tmp, "wb") as f:
            f.write(raw)
        os.replace(tmp, LOCATIONS_FILE)

    @staticmethod
    def last_updated():
        try:
            return datetime.datetime.fromtimestamp(os.path.getmtime(LOCATIONS_FILE))
        except OSError:
            return None


# ---------------------------------------------------------------------------
# Ping - uses the system `ping`, nothing else to install
# ---------------------------------------------------------------------------
class PingRunner:
    TIME_RE = re.compile(r"[=<]\s*(\d+(?:[.,]\d+)?)\s*ms", re.IGNORECASE)

    @staticmethod
    def parse(output):
        """Extracts per-reply latencies. Only lines with a TTL are replies, which also
        keeps the 'Minimum/Maximum/Average' summary line on Windows out of the numbers."""
        times = []
        for line in output.splitlines():
            if "ttl" not in line.lower():
                continue
            m = PingRunner.TIME_RE.search(line)
            if m:
                times.append(float(m.group(1).replace(",", ".")))
        return times

    INTERVAL = 0.2  # seconds between echo requests (system ping on Windows is fixed at 1s)

    def __init__(self):
        self._icmp = self._load_icmp() if IS_WINDOWS else None

    @staticmethod
    def _load_icmp():
        """Windows' IcmpSendEcho needs no admin rights and lets us ping much faster than ping.exe."""
        try:
            import ctypes
            from ctypes import wintypes
            iphlpapi = ctypes.WinDLL("iphlpapi.dll")
            iphlpapi.IcmpCreateFile.restype = wintypes.HANDLE
            iphlpapi.IcmpCloseHandle.argtypes = [wintypes.HANDLE]
            iphlpapi.IcmpSendEcho.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.c_void_p, wintypes.WORD,
                                              ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD]
            iphlpapi.IcmpSendEcho.restype = wintypes.DWORD
            return iphlpapi
        except Exception:
            return None

    def _run_icmp(self, host, count, reply_timeout_ms, stop_event=None):
        import ctypes
        import socket
        try:
            addr = socket.gethostbyname(host)
        except OSError as e:
            return None, f"cannot resolve {host}: {e}"
        ip = int.from_bytes(socket.inet_aton(addr), "little")
        handle = self._icmp.IcmpCreateFile()
        if not handle or handle == ctypes.c_void_p(-1).value:
            return None, "IcmpCreateFile failed"
        payload = b"AutoScribe" * 3
        reply = ctypes.create_string_buffer(64 + len(payload) + 8)
        times = []
        try:
            for i in range(count):
                if stop_event is not None and stop_event.is_set():
                    break
                t0 = time.perf_counter()
                n = self._icmp.IcmpSendEcho(handle, ip, payload, len(payload), None, reply, len(reply),
                                            reply_timeout_ms)
                if n:
                    # ICMP_ECHO_REPLY: Address(4) Status(4) RoundTripTime(4)
                    status = int.from_bytes(reply.raw[4:8], "little")
                    rtt = int.from_bytes(reply.raw[8:12], "little")
                    if status == 0:
                        times.append(float(rtt))
                if i < count - 1:
                    time.sleep(max(0.0, self.INTERVAL - (time.perf_counter() - t0)))
        finally:
            self._icmp.IcmpCloseHandle(handle)
        return times, None

    def run(self, host, count=5, reply_timeout_ms=2000, stop_event=None):
        """Returns ({"avg", "min", "max", "loss"}, None) or (None, error)."""
        if self._icmp is not None:
            times, err = self._run_icmp(host, count, reply_timeout_ms, stop_event)
            if err is None:
                if not times:
                    return None, "no replies"
                return {"avg": sum(times) / len(times), "min": min(times), "max": max(times),
                        "loss": (count - len(times)) / count * 100}, None
        if IS_WINDOWS:
            cmd = ["ping", "-n", str(count), "-w", str(reply_timeout_ms), host]
        else:
            cmd = ["ping", "-c", str(count), "-i", str(self.INTERVAL), "-W", str(max(1, reply_timeout_ms // 1000)),
                   host]
        try:
            result = subprocess.run(
                cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=count * (reply_timeout_ms / 1000 + 1) + 5, **hidden_process_kwargs())
        except FileNotFoundError:
            return None, "ping command not found"
        except subprocess.TimeoutExpired:
            return None, "ping timed out"
        except Exception as e:
            return None, str(e)

        times = self.parse(result.stdout)
        if not times:
            return None, "no replies"
        return {
            "avg": sum(times) / len(times),
            "min": min(times),
            "max": max(times),
            "loss": (count - len(times)) / count * 100,
        }, None


# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------
class DatabaseManager:
    EXPORT_COLUMNS = ["city", "location", "country", "protocol", "port", "status",
                      "duration", "ping", "packet_loss", "profile_name", "timestamp"]

    def __init__(self, app=None):
        self.app = app
        self.lock = threading.RLock()
        self.conn = sqlite3.connect(DB_FILE, check_same_thread=False)
        self.cursor = self.conn.cursor()
        try:
            self.cursor.execute("PRAGMA journal_mode=WAL")
            self.cursor.execute("PRAGMA synchronous=NORMAL")
        except sqlite3.DatabaseError:
            pass
        self.create_table()

    def create_table(self):
        with self.lock:
            self.cursor.execute('''
                CREATE TABLE IF NOT EXISTS tests (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    location TEXT,
                    city TEXT,
                    country TEXT,
                    protocol TEXT,
                    port INTEGER,
                    status TEXT,
                    duration REAL,
                    ping REAL,
                    packet_loss REAL,
                    profile_name TEXT,
                    timestamp TEXT,
                    UNIQUE(location, city, country, protocol, port)
                )
            ''')
            self.conn.commit()
            # Older databases from the speedtest version: add the new column if missing.
            cols = [r[1] for r in self.cursor.execute("PRAGMA table_info(tests)").fetchall()]
            if "packet_loss" not in cols:
                self.cursor.execute("ALTER TABLE tests ADD COLUMN packet_loss REAL")
                self.conn.commit()

    def record_result(self, location, city, country, proto, port, status, duration, ping, loss, profile_name):
        timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with self.lock:
            try:
                self.cursor.execute('''
                    INSERT OR REPLACE INTO tests
                    (location, city, country, protocol, port, status, duration, ping, packet_loss, profile_name, timestamp)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ''', (location, city, country, proto, port, status, round(duration, 2), ping, loss,
                      profile_name, timestamp))
                self.conn.commit()
                return True
            except Exception as e:
                if self.app:
                    self.app.post(self.app.log_message, f"DB ERROR: {e}", "FAILURE")
                return False

    def get_row_count(self):
        with self.lock:
            try:
                self.cursor.execute("SELECT COUNT(*) FROM tests")
                return self.cursor.fetchone()[0]
            except Exception:
                return 0

    def is_tested(self, location, city, country, proto, port):
        with self.lock:
            self.cursor.execute(
                "SELECT 1 FROM tests WHERE location=? AND city=? AND country=? AND protocol=? AND port=?",
                (location, city, country, proto, port))
            return self.cursor.fetchone() is not None

    def tested_status(self):
        """{(location, city, country, protocol, port): status}, for resuming without a query per test."""
        with self.lock:
            self.cursor.execute("SELECT location, city, country, protocol, port, status FROM tests")
            return {tuple(r[:5]): r[5] for r in self.cursor.fetchall()}

    def fetch_results(self):
        with self.lock:
            self.cursor.execute(
                "SELECT city, location, country, protocol, port, status, duration, ping, packet_loss "
                "FROM tests ORDER BY id")
            return self.cursor.fetchall()

    def export_to_csv(self, current_profile_name="Manual"):
        try:
            os.makedirs(REPORTS_DIR, exist_ok=True)
        except OSError as e:
            return -1, f"Failed to create directory: {e}"

        max_id = 0
        for f in os.listdir(REPORTS_DIR):
            if f.endswith(".csv"):
                head = f.split("_")[0]
                if head.isdigit():
                    max_id = max(max_id, int(head))

        ts = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        safe_profile = "".join(c for c in current_profile_name if c.isalnum() or c in (" ", "-", "_")).strip()
        safe_profile = safe_profile.replace(" ", "_") or "Manual"
        csv_file = os.path.join(REPORTS_DIR, f"{max_id + 1}_{safe_profile}_{ts}.csv")

        with self.lock:
            self.cursor.execute(f"SELECT {', '.join(self.EXPORT_COLUMNS)} FROM tests ORDER BY id")
            rows = self.cursor.fetchall()

        try:
            with open(csv_file, "w", newline="", encoding="utf-8") as f:
                writer = csv.writer(f)
                writer.writerow(self.EXPORT_COLUMNS)
                writer.writerows(rows)
            return len(rows), csv_file
        except PermissionError:
            return -1, "File Open Error - Close the file if open."

    def clear_db(self):
        with self.lock:
            self.cursor.execute("DELETE FROM tests")
            self.conn.commit()


# ---------------------------------------------------------------------------
# Worker
# ---------------------------------------------------------------------------
class WindscribeWorker:
    # Steps of one test, as shown in the UI.
    DISCONNECT, CONNECT, SETTLE, PING = range(4)

    def __init__(self, app_instance, cli_cmd=DEFAULT_CLI):
        self.app = app_instance
        self.db = DatabaseManager(app=app_instance)
        self.pinger = PingRunner()
        self.stop_event = threading.Event()
        self.running = False
        self.cli_cmd = cli_cmd

    # -- helpers (all UI access goes through app.post, never touches widgets directly) --
    def log(self, msg, tag="INFO"):
        self.app.post(self.app.log_message, msg, tag)

    def phase(self, idx, state, secs=None):
        self.app.post(self.app.set_phase, idx, state, secs)

    def run_cli(self, args, timeout=30):
        cmd = [self.cli_cmd] + args
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                                    timeout=timeout, **hidden_process_kwargs())
            return result.returncode, result.stdout
        except subprocess.TimeoutExpired:
            return -1, ""
        except Exception as e:
            self.log(f"CLI Error: {e}", "FAILURE")
            return -1, ""

    @staticmethod
    def is_connected_output(out):
        up = out.upper()
        return "CONNECTED" in up and "DISCONNECTED" not in up and "CONNECTING" not in up

    POLL = 0.4  # seconds between status checks

    @staticmethod
    def is_disconnected_output(out):
        return "DISCONNECTED" in out.upper()

    @staticmethod
    def is_invalid_location_output(out):
        low = out.lower()
        return "location does not exist" in low or "is disabled" in low

    def ensure_disconnected(self):
        """Polls quickly instead of sleeping a fixed time; only falls back to 'firewall off' if needed."""
        _, out = self.run_cli(["status"])
        if self.is_disconnected_output(out):
            return True
        for attempt in range(3):
            if attempt:
                self.run_cli(["firewall", "off"])
            self.run_cli(["disconnect"])
            deadline = time.time() + 6
            while time.time() < deadline:
                _, out = self.run_cli(["status"])
                if self.is_disconnected_output(out):
                    return True
                if self.stop_event.wait(self.POLL):
                    return False
        return False

    def wait_for_connection(self, start_time, timeout, expect_city=None):
        while time.time() - start_time < timeout:
            _, out = self.run_cli(["status"])
            if self.is_connected_output(out) and (not expect_city or expect_city.lower() in out.lower()):
                return True, time.time() - start_time
            if self.is_invalid_location_output(out):
                return None, 0.0
            if self.stop_event.wait(self.POLL):
                return False, 0.0
        return False, 0.0

    def connect(self, city, route, timeout, expect_city=None):
        """Returns (True|False|None, seconds); None means the location is unavailable.
        The CLI usually blocks until it is connected, so its own output often settles it without polling."""
        start_ts = time.time()
        _, out = self.run_cli(["connect", "-n", city, route], timeout=timeout + 15)
        if self.is_invalid_location_output(out):
            return None, 0.0
        if self.is_connected_output(out):
            # Confirm once: the CLI output can report success a moment before the tunnel is up.
            _, status = self.run_cli(["status"])
            if self.is_connected_output(status) and (not expect_city or expect_city.lower() in status.lower()):
                return True, time.time() - start_ts
        return self.wait_for_connection(start_ts, timeout, expect_city)

    # -- job control --
    def start(self, work_plan, opts):
        self.stop_event.clear()
        self.running = True
        threading.Thread(target=self._run, args=(work_plan, opts), daemon=True).start()

    def stop(self):
        self.stop_event.set()
        self.log("Stopping after the current step...", "WARN")

    def connect_to(self, result, timeout):
        """Connects to a single route picked from the results."""
        self.stop_event.clear()
        self.running = True
        threading.Thread(target=self._connect_one, args=(result, timeout), daemon=True).start()

    def disconnect(self):
        self.stop_event.clear()
        self.running = True
        threading.Thread(target=self._disconnect, daemon=True).start()

    def _connect_one(self, r, timeout):
        connected = False
        route = f"{r['proto']}:{r['port']}"
        try:
            self.log(f"Connecting to {r['city']} via {route}", "HEADER")
            self.phase(self.DISCONNECT, "active")
            t0 = time.time()
            if not self.ensure_disconnected():
                self.phase(self.DISCONNECT, "fail")
                self.log("Could not disconnect the current session first.", "FAILURE")
                return
            self.phase(self.DISCONNECT, "done", time.time() - t0)
            self.phase(self.CONNECT, "active")
            connected, duration = self.connect(r["city"], route, timeout)
            connected = bool(connected)
            if connected:
                self.phase(self.CONNECT, "done", duration)
                self.log(f"Connected to {r['city']} via {route} in {duration:.1f}s", "SUCCESS")
            else:
                self.phase(self.CONNECT, "fail")
                self.log(f"Could not connect to {r['city']} via {route}", "FAILURE")
        except Exception as e:
            self.log(f"Critical Error: {e}", "FAILURE")
        finally:
            self.running = False
            self.app.post(self.app.manual_connect_finished, r, connected)

    def _disconnect(self):
        done = False
        try:
            done = self.ensure_disconnected()
            self.log("Disconnected." if done else "Could not disconnect.", "INFO" if done else "FAILURE")
        finally:
            self.running = False
            self.app.post(self.app.disconnect_finished, done)

    def _build_tasks(self, work_plan, opts):
        keys = opts["keys"]
        locations = [l for l in LocationManager.load_locations() if not keys or loc_key(l) in keys]
        if opts["random_cities"]:
            random.shuffle(locations)
        tasks = [(loc, proto, port) for loc in locations for proto, ports in work_plan.items() for port in ports]
        if opts["chaos_mode"]:
            random.shuffle(tasks)
        return locations, tasks

    PROBE_SERVERS = 3   # smart scan: servers a route is tried on before it counts as blocked on this network
    PRUNE_AFTER = 3     # a route that failed on this many servers and never worked is dropped for the run
    MIN_TIMEOUT = 8.0   # the learned timeout never goes below this

    def _effective_timeout(self, opts):
        """About 3x the median successful connect time, once there are a few to learn from."""
        d = sorted(self._durations)
        if len(d) < 3:
            return opts["timeout"]
        return min(opts["timeout"], max(self.MIN_TIMEOUT, d[len(d) // 2] * 3))

    @staticmethod
    def _pick_probes(locations, n):
        """One server per country first, so a single blocked country can't hide a working route."""
        out, countries = [], set()
        for l in locations:
            if l["country"] not in countries:
                countries.add(l["country"])
                out.append(l)
        out += [l for l in locations if l not in out]
        return out[:n]

    def _record(self, loc, proto, port, status, duration, ping, loss, opts):
        city, node, country = loc["city"], loc["location"], loc["country"]
        self.db.record_result(node, city, country, proto, port, status, duration, ping, loss, opts["profile_name"])
        self.app.post(self.app.add_result_row, city, node, country, proto, port, status, duration, ping, loss)
        self._tested[(node, city, country, proto, port)] = status  # stage 2 must not repeat a stage 1 test
        if status != "INVALID_LOC":
            self._route_stats.setdefault((proto, port), [0, 0])[0 if status == "SUCCESS" else 1] += 1

    def _test(self, loc, proto, port, opts):
        """Runs one test. Returns SUCCESS / FAILURE / INVALID_LOC / SKIP, or STOP / KEPT to end the run."""
        city, node, country = loc["city"], loc["location"], loc["country"]
        route = f"{proto}:{port}"
        key = (node, city, country, proto, port)
        if self.stop_event.is_set():
            return "STOP"
        if key in self._tested:
            return self._tested[key]
        stats = self._route_stats.get((proto, port), [0, 0])
        if self._can_prune and stats[0] == 0 and stats[1] >= self.PRUNE_AFTER:
            if (proto, port) not in self._pruned:
                self._pruned.add((proto, port))
                self.log(f"{route} failed on {stats[1]} servers and never worked; skipping it from now on.", "WARN")
            return "SKIP"

        self._i += 1
        if (city, country) in self._invalid:
            self._fail += 1
            self._record(loc, proto, port, "INVALID_LOC", 0, None, None, opts)
            return "INVALID_LOC"

        self._executed += 1
        total = max(self._total, self._i)
        self.app.post(self.app.begin_test, self._i, total, self._executed, city, node, country, route)
        self.log(f"[{self._i}/{total}] {city} ({country}) via {route}", "HEADER")

        direct = self._switch_ok and self._last_connected
        if direct:
            self.phase(self.DISCONNECT, "skip")
        else:
            self.phase(self.DISCONNECT, "active")
            t0 = time.time()
            if not self.ensure_disconnected():
                self.phase(self.DISCONNECT, "fail")
                self.log("Could not disconnect, skipping this test.", "FAILURE")
                return "STOP" if self.stop_event.is_set() else "SKIP"
            self.phase(self.DISCONNECT, "done", time.time() - t0)
        self._last_connected = False
        if self.stop_event.is_set():
            return "STOP"

        timeout = self._effective_timeout(opts)
        self.phase(self.CONNECT, "active")
        connected, duration = self.connect(city, route, timeout, expect_city=city if direct else None)

        if connected is None:
            self._invalid.add((city, country))
            self._fail += 1
            self.phase(self.CONNECT, "fail")
            self.log(f"{city} is unavailable, skipping.", "FAILURE")
            self._record(loc, proto, port, "INVALID_LOC", 0, None, None, opts)
            return "INVALID_LOC"

        # User pressed Stop while we were still connecting: undo and leave, don't record a bogus failure.
        if self.stop_event.is_set() and not connected:
            self.run_cli(["disconnect"])
            self.log("Stopped while connecting; this test was not recorded.", "WARN")
            return "STOP"

        ping_avg = loss = None
        if connected:
            self._ok += 1
            self._durations.append(duration)
            self.phase(self.CONNECT, "done", duration)
            self.log(f"Connected in {duration:.2f}s", "SUCCESS")
            if self._switch_ok is None:
                # Switching servers without disconnecting is only safe if we can tell which server we're on.
                _, status = self.run_cli(["status"])
                self._switch_ok = city.lower() in status.lower()
                self.log("Fast switching between servers enabled." if self._switch_ok
                         else "CLI status does not name the server; disconnecting between tests.", "INFO")

            if opts["settle"] > 0:
                self.phase(self.SETTLE, "active")
                self.stop_event.wait(opts["settle"])
                self.phase(self.SETTLE, "done", opts["settle"])
            else:
                self.phase(self.SETTLE, "skip")

            self.phase(self.PING, "active")
            t0 = time.time()
            result, err = self.pinger.run(opts["ping_host"], opts["ping_count"], stop_event=self.stop_event)
            self.phase(self.PING, "done" if result else "fail", time.time() - t0)
            self.app.post(self.app.set_ping, result)
            if result:
                ping_avg, loss = result["avg"], result["loss"]
                self.log(f"Ping avg {ping_avg:.0f} ms (min {result['min']:.0f} / max {result['max']:.0f}), "
                         f"loss {loss:.0f}%", "INFO")
            else:
                self.log(f"Ping failed: {err}", "WARN")
        else:
            self._fail += 1
            self.phase(self.CONNECT, "fail", timeout)
            self.log(f"Timed out on {route} after {timeout:.0f}s", "FAILURE")

        status_str = "SUCCESS" if connected else "FAILURE"
        self._record(loc, proto, port, status_str, duration, ping_avg, loss, opts)

        if connected and opts["stop_on_success"]:
            self._kept = f"{loc_name(city, node)} · {route}"
            self.log(f"Stop on success: staying connected to {self._kept}.", "SUCCESS")
            return "KEPT"

        if connected and self._switch_ok:
            self._last_connected = True  # the next test switches straight over
        else:
            # No fixed pause: the next test's ensure_disconnected() polls until the tunnel is down.
            self.run_cli(["disconnect"])
        return status_str

    def _run(self, work_plan, opts):
        self._kept = None
        self._ok = self._fail = self._executed = self._i = self._total = 0
        self._durations, self._invalid, self._pruned, self._route_stats = [], set(), set(), {}
        self._switch_ok, self._last_connected = None, False
        self._can_prune = False
        try:
            locations, tasks = self._build_tasks(work_plan, opts)
            if not locations:
                self.log(f"No locations match the selection (or locations.json is missing at {LOCATIONS_FILE})",
                         "FAILURE")
                return

            self._tested = self.db.tested_status() if opts["resume"] else {}
            for (_, _, _, proto, port), status in self._tested.items():
                if status != "INVALID_LOC":
                    self._route_stats.setdefault((proto, port), [0, 0])[0 if status == "SUCCESS" else 1] += 1

            routes = [(proto, port) for proto, ports in work_plan.items() for port in ports]
            # With a single route there is nothing to prune it in favour of; dropping it would end the run.
            self._can_prune = bool(opts.get("smart_scan")) and len(routes) > 1
            smart = (opts.get("smart_scan") and not opts["chaos_mode"]
                     and len(locations) > self.PROBE_SERVERS and len(routes) > 1)
            if not smart:
                self._total = len(tasks)
                self.log(f"Queued {len(locations)} locations, {self._total} tests", "HEADER")
                for loc, proto, port in tasks:
                    if self._test(loc, proto, port, opts) in ("STOP", "KEPT"):
                        return
                return

            # Stage 1: networks block protocols/ports, not single servers, so find working routes on a few servers.
            probes = self._pick_probes(locations, self.PROBE_SERVERS)
            self._total = len(routes) * len(probes)
            self.log(f"Smart scan stage 1: trying {len(routes)} routes on up to {len(probes)} servers each",
                     "HEADER")
            working = []
            for proto, port in routes:
                for n, loc in enumerate(probes):
                    res = self._test(loc, proto, port, opts)
                    if res in ("STOP", "KEPT"):
                        return
                    if res == "SUCCESS":
                        working.append((proto, port))
                        self._total -= len(probes) - n - 1
                        break

            if not working:
                self.log("Smart scan: no route worked on this network.", "FAILURE")
                return
            # Stage 2: every selected server, but only with the routes that work here.
            names = ", ".join(f"{p}:{port}" for p, port in working)
            self._total = self._i + len(working) * (len(locations) - 1)
            self.log(f"Smart scan stage 2: testing {len(locations)} servers with {len(working)} working routes "
                     f"({names})", "HEADER")
            for loc in locations:
                for proto, port in working:
                    if self._test(loc, proto, port, opts) in ("STOP", "KEPT"):
                        return
        except Exception as e:
            self.log(f"Critical Error: {e}", "FAILURE")
        finally:
            if self._kept is None and self._last_connected:
                self.run_cli(["disconnect"])
            self.running = False
            self.app.post(self.app.job_finished, self._kept, self._ok, self._fail)
