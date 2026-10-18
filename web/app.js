"use strict";
/* AutoScribe interface. Talks to Python through window.pywebview.api; Python pushes events to AS.onEvent(). */

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const ICON = {
  check: '<svg viewBox="0 0 24 24"><path d="M5 12l5 5 9-10"/></svg>',
  up: '<svg viewBox="0 0 24 24"><path d="M6 15l6-6 6 6"/></svg>',
  down: '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>',
  chev: '<svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>',
  ok: '<svg viewBox="0 0 24 24"><path d="M5 12l5 5 9-10"/></svg>',
  x: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  rate: '<svg viewBox="0 0 24 24"><path d="M4 19h16"/><path d="M7 15l4-4 3 3 5-6"/></svg>',
  bolt: '<svg viewBox="0 0 24 24"><path d="M13 3L5 14h6l-1 7 8-11h-6z"/></svg>',
  clock: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>',
  info: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 11v5"/><path d="M12 8h.01"/></svg>',
  warn: '<svg viewBox="0 0 24 24"><path d="M12 4l9 16H3z"/><path d="M12 10v4"/><path d="M12 17h.01"/></svg>',
  plug: '<svg viewBox="0 0 24 24"><path d="M5 12h14"/><path d="M13 6l6 6-6 6"/></svg>',
  copy: '<svg viewBox="0 0 24 24"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/></svg>',
  table: '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 10h16M10 4v16"/></svg>',
};

// Strings the old Tk interface didn't have.
const EXTRA = {
  dist_title: { en: "Ping distribution", fa: "توزیع پینگ", ru: "Распределение пинга" },
  dist_sub: { en: "Connected routes by latency", fa: "مسیرهای متصل بر اساس تأخیر", ru: "Рабочие маршруты по задержке" },
  dist_empty: { en: "Appears once routes connect.", fa: "پس از اتصال مسیرها نمایش داده می‌شود.", ru: "Появится после первых подключений." },
  showing_first: { en: "Showing the first {n} rows. Search or filter to narrow down.", fa: "فقط {n} ردیف اول نمایش داده می‌شود. برای محدود کردن جستجو یا فیلتر کنید.", ru: "Показаны первые {n} строк. Используйте поиск или фильтр." },
  status_SUCCESS: { en: "Connected", fa: "متصل", ru: "Подключено" },
  status_FAILURE: { en: "Timed out", fa: "پایان مهلت", ru: "Тайм-аут" },
  status_INVALID_LOC: { en: "Unavailable", fa: "در دسترس نیست", ru: "Недоступно" },
  running: { en: "Running", fa: "در حال اجرا", ru: "Идёт тест" },
};

const AS = {
  api: null, demo: false,
  S: null,                 // strings
  lang: "en",
  settings: null, profiles: {}, currentProfile: "",
  cliPath: "", cliOk: false,
  protocols: {}, protoNames: {}, regions: {},
  locations: [], allKeys: new Set(), selected: new Set(),
  results: [], newIds: new Set(),
  busy: null,              // null | "job" | "connect" | "disconnect"
  stopping: false,
  run: null,               // {kind, scope, profile, startedTs, finishedTs, stopped, kept, i, total, executed, stepTs}
  phases: [], ping: null, connectedTo: null,
  view: "results", filter: "All", search: "", sort: { col: null, desc: false },
  selectedRow: null, openPorts: null, timingOpen: false,
  picker: null,
  logLines: [],
};

/* ------------------------------------------------------------------ i18n */
function t(key, params) {
  const e = (AS.S && AS.S[key]) || EXTRA[key];
  let text = e ? (e[AS.lang] || e.en) : key;
  if (params) text = text.replace(/\{(\w+)\}/g, (m, k) => (k in params ? params[k] : m));
  return text;
}
const num = (n) => (AS.lang === "ru" ? Number(n).toLocaleString("ru-RU") : Number(n).toLocaleString("en-US"));
const regionName = (r) => (AS.regions[r] && (AS.regions[r][AS.lang] || AS.regions[r].en)) || r;
function duration(secs) {
  secs = Math.max(0, Math.floor(secs));
  const h = Math.floor(secs / 3600), m = Math.floor((secs % 3600) / 60), s = secs % 60;
  if (h) return t("dur_h", { h, m: String(m).padStart(2, "0") });
  if (m) return t("dur_m", { m });
  return t("dur_s", { s });
}
function applyStaticText() {
  document.documentElement.lang = AS.lang;
  document.documentElement.dir = AS.lang === "fa" ? "rtl" : "ltr";
  document.querySelectorAll("[data-t]").forEach((el) => { el.textContent = t(el.dataset.t); });
  $("#tagline").textContent = t("tagline", { v: AS.version });
  $("#in-search").placeholder = t("search_results");
  $("#picker-search").placeholder = t("search_locations");
  $("#dist-title").textContent = t("dist_title");
  $("#dist-sub").textContent = t("dist_sub");
}

/* ------------------------------------------------------------------ helpers */
const locKey = (l) => `${l.country}|${l.city}|${l.location}`;
const locName = (city, node) => (node ? `${city} – ${node}` : city);
const statusLabel = (s) => t(`status_${s}`);
const statusClass = (s) => (s === "SUCCESS" ? "good" : s === "INVALID_LOC" ? "warn" : "bad");
const pingClass = (p) => (p == null ? "" : p < 50 ? "good" : p < 100 ? "warn" : "bad");
const rateClass = (r) => (r >= 0.75 ? "good" : r >= 0.45 ? "warn" : "bad");
const matchesRegion = (l, region) => region === "All" || (region === "USA" ? l.country.startsWith("US ") : l.continent === region);
function call(name, ...args) {
  if (!AS.api || !AS.api[name]) return Promise.resolve(null);
  return AS.api[name](...args);
}
let saveTimer = null;
function saveSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => call("save_state", gatherSettings(), AS.cliPath), 400);
}

/* ------------------------------------------------------------------ settings */
function sanitizeOrder(order) {
  const all = Object.keys(AS.protocols);
  const o = (order || []).filter((p) => all.includes(p));
  return o.concat(all.filter((p) => !o.includes(p)));
}
function normalizeSettings(s) {
  s = JSON.parse(JSON.stringify(s || {}));
  s.priority_order = sanitizeOrder(s.priority_order);
  s.protocols = s.protocols || {};
  for (const [proto, ports] of Object.entries(AS.protocols)) {
    const saved = s.protocols[proto] || {};
    const sp = saved.ports || {};
    s.protocols[proto] = { enabled: saved.enabled !== false, ports: Object.fromEntries(ports.map((p) => [String(p), sp[String(p)] !== false])) };
  }
  for (const [k, v] of Object.entries({ timeout: "25", settle: "2", ping_host: "1.1.1.1", ping_count: "5" })) if (s[k] == null) s[k] = v;
  for (const k of ["resume", "random_cities", "chaos_mode", "stop_on_success"]) s[k] = !!s[k];
  s.smart_scan = s.smart_scan !== false;
  return s;
}
function applySettings(s) {
  AS.settings = normalizeSettings(s);
  const keys = new Set((AS.settings.selected || []).filter((k) => AS.allKeys.has(k)));
  AS.selected = keys.size === AS.allKeys.size ? new Set() : keys;
  $("#in-host").value = AS.settings.ping_host;
  $("#in-pings").value = AS.settings.ping_count;
  $("#in-timeout").value = AS.settings.timeout;
  $("#in-settle").value = AS.settings.settle;
  renderSidebar();
}
function gatherSettings() {
  const s = AS.settings;
  s.ping_host = $("#in-host").value.trim();
  s.ping_count = $("#in-pings").value.trim();
  s.timeout = $("#in-timeout").value.trim();
  s.settle = $("#in-settle").value.trim();
  s.selected = [...AS.selected].sort();
  return JSON.parse(JSON.stringify(s));
}
function getPlan() {
  const plan = {};
  for (const proto of AS.settings.priority_order) {
    const p = AS.settings.protocols[proto];
    if (!p.enabled) continue;
    const ports = AS.protocols[proto].filter((port) => p.ports[String(port)]);
    if (ports.length) plan[proto] = ports;
  }
  return plan;
}
const scopeLocations = () => (AS.selected.size ? AS.locations.filter((l) => AS.selected.has(locKey(l))) : AS.locations);
const routeCount = () => Object.values(getPlan()).reduce((a, p) => a + p.length, 0);
const testCount = () => scopeLocations().length * routeCount();

function scopeSummary() {
  const locs = scopeLocations();
  const countries = [...new Set(locs.map((l) => l.country))];
  if (!AS.selected.size) {
    return [t("all_servers"), t("servers_in", { n: num(AS.locations.length), c: num(countries.length) })];
  }
  let title;
  if (countries.length === 1) {
    const all = AS.locations.filter((l) => l.country === countries[0]).length;
    title = locs.length === all ? countries[0] : locs.length === 1 ? locName(locs[0].city, locs[0].location) : countries[0];
  } else {
    title = countries.slice(0, 2).join(", ") + (countries.length > 2 ? t("more", { n: countries.length - 2 }) : "");
  }
  const sub = countries.length === 1 ? t("servers_in_one", { n: num(locs.length) }) : t("servers_in", { n: num(locs.length), c: num(countries.length) });
  return [title, sub];
}

/* ------------------------------------------------------------------ sidebar */
function renderSidebar() {
  renderProfiles();
  renderScope();
  renderProtocols();
  renderOptions();
  renderTiming();
  renderLang();
  renderCli();
}
function renderProfiles() {
  const sel = $("#sel-profile");
  const names = Object.keys(AS.profiles);
  sel.innerHTML = `<option value="">${esc(names.length ? t("choose_profile") : t("no_profiles"))}</option>` +
    names.map((n) => `<option value="${esc(n)}" ${n === AS.currentProfile ? "selected" : ""}>${esc(n)}</option>`).join("");
}
function renderScope() {
  const [title, sub] = scopeSummary();
  $("#scope-title").textContent = title;
  $("#scope-sub").textContent = sub;
  $("#scope-math").textContent = t("scope_math", { s: num(scopeLocations().length), r: num(routeCount()) });
  $("#scope-tests").textContent = t("tests_n", { n: num(testCount()) });
  $("#no-list-hint").classList.toggle("hidden", AS.locations.length > 0);
  renderHeader();
}
function portsSummary(proto) {
  const p = AS.settings.protocols[proto];
  const all = AS.protocols[proto];
  const on = all.filter((port) => p.ports[String(port)]);
  if (!on.length) return [t("ports_none"), true];
  if (on.length === all.length && all.length > 2) return [t("ports_all", { n: all.length }), false];
  if (on.length <= 2) return [on.join(", "), false];
  return [t("ports_some", { a: on.length, b: all.length }), false];
}
function renderProtocols() {
  const order = AS.settings.priority_order;
  $("#proto-list").innerHTML = order.map((proto, i) => {
    const p = AS.settings.protocols[proto];
    const [summary, none] = portsSummary(proto);
    const open = AS.openPorts === proto;
    const grid = open ? `<div class="ports-grid">
        ${AS.protocols[proto].map((port) => `<button class="port-chip ${p.ports[String(port)] ? "on" : ""}" data-act="port" data-proto="${proto}" data-port="${port}">${port}</button>`).join("")}
        <div class="ports-actions"><button class="btn small" data-act="ports-all" data-proto="${proto}">${esc(t("select_all"))}</button>
        <button class="btn small" data-act="ports-none" data-proto="${proto}">${esc(t("select_none"))}</button></div></div>` : "";
    return `<div class="proto ${p.enabled ? "on" : ""}">
      <div class="proto-head">
        <button class="check ${p.enabled ? "on" : ""}" data-act="proto" data-proto="${proto}">${ICON.check}</button>
        <span class="proto-name" data-act="proto" data-proto="${proto}">${esc(AS.protoNames[proto])}</span>
        <button class="ports-btn ${none ? "none" : ""} ${open ? "open" : ""}" data-act="ports" data-proto="${proto}" data-tip="${esc(t("ports_tip"))}">${esc(summary)}</button>
        <button class="mini" data-act="move" data-dir="-1" data-proto="${proto}" ${i === 0 ? "disabled" : ""}>${ICON.up}</button>
        <button class="mini" data-act="move" data-dir="1" data-proto="${proto}" ${i === order.length - 1 ? "disabled" : ""}>${ICON.down}</button>
      </div>${grid}</div>`;
  }).join("");
}
function onProtoClick(e) {
  const b = e.target.closest("[data-act]");
  if (!b || b.disabled) return;
  const proto = b.dataset.proto, s = AS.settings, p = s.protocols[proto];
  switch (b.dataset.act) {
    case "proto": p.enabled = !p.enabled; break;
    case "ports": AS.openPorts = AS.openPorts === proto ? null : proto; break;
    case "port": p.ports[b.dataset.port] = !p.ports[b.dataset.port]; break;
    case "ports-all": case "ports-none":
      for (const k of Object.keys(p.ports)) p.ports[k] = b.dataset.act === "ports-all"; break;
    case "move": {
      const o = s.priority_order, i = o.indexOf(proto), j = i + Number(b.dataset.dir);
      if (j >= 0 && j < o.length) [o[i], o[j]] = [o[j], o[i]];
      break;
    }
  }
  renderProtocols();
  renderScope();
  if (!AS.busy) AS.run = null, renderHero();
  saveSoon();
}
const OPTIONS = [
  ["smart_scan", "opt_smart", "opt_smart_tip", true],
  ["resume", "opt_resume", "opt_resume_tip"],
  ["random_cities", "opt_shuffle", "opt_shuffle_tip"],
  ["chaos_mode", "opt_chaos", "opt_chaos_tip"],
  ["stop_on_success", "opt_stop", "opt_stop_tip"],
];
function renderOptions() {
  $("#options").innerHTML = OPTIONS.map(([key, label, tip, featured]) =>
    `<div class="opt ${featured ? "featured" : ""}" data-opt="${key}" data-tip="${esc(t(tip))}">
      <span class="opt-label">${featured ? "⚡ " : ""}${esc(t(label))}</span><span class="switch ${AS.settings[key] ? "on" : ""}"></span></div>`).join("");
}
function renderTiming() {
  $("#timing").classList.toggle("hidden", !AS.timingOpen);
  $("#btn-timing").classList.toggle("open", AS.timingOpen);
  $("#timing-summary").textContent = t("timing_summary", {
    host: $("#in-host").value.trim() || "?", count: $("#in-pings").value.trim(), timeout: $("#in-timeout").value.trim() });
  $("#in-cli").value = AS.cliPath;
}
function renderLang() {
  const codes = Object.keys(AS.languages);
  $("#seg-lang").innerHTML = codes.map((c) => `<button class="${c === AS.lang ? "on" : ""}" data-lang="${c}">${esc(AS.languages[c])}</button>`).join("");
}
function renderCli() {
  const pill = $("#cli-pill");
  pill.className = "cli-pill " + (AS.cliOk ? "ok" : "bad");
  $("#cli-text").textContent = AS.cliOk ? t("cli_ready") : t("cli_missing");
}
async function checkCli() {
  AS.cliPath = $("#in-cli").value.trim();
  AS.cliOk = AS.demo ? true : !!(await call("check_cli", AS.cliPath));
  renderCli();
  saveSoon();
  return AS.cliOk;
}
function focusCli() {
  AS.timingOpen = true;
  setSidebar(true);
  renderTiming();
  $("#in-cli").focus();
  toast(t("set_cli_path"), "warn");
}

/* ------------------------------------------------------------------ header */
function renderHeader() {
  const run = AS.run;
  const [scope] = scopeSummary();
  let title = t("ready"), sub = t("ready_sub", { scope, n: num(testCount()) }), badge = null;
  if (run && run.kind === "job") {
    const time = (ts) => new Date(ts).toLocaleTimeString(AS.lang === "fa" ? "fa-IR" : AS.lang, { hour: "2-digit", minute: "2-digit" });
    const prof = run.profile && run.profile !== "Manual" ? t("with_profile", { p: run.profile }) : "";
    if (!run.finishedTs) {
      title = t("testing", { scope: run.scope });
      sub = t("started", { t: time(run.startedTs) }) + prof;
      badge = [t("n_of_tests", { a: num(Math.max(0, run.i - 1)), b: num(run.total || 0) }), "accent"];
    } else {
      title = run.kept ? t("found_route") : run.stopped ? t("run_stopped") : t("run_complete");
      sub = t("finished_sub", { scope: run.scope, t: time(run.finishedTs), d: duration((run.finishedTs - run.startedTs) / 1000) }) + prof;
      badge = run.kept ? [t("stopped_first"), "good"] : null;
    }
  }
  $("#title").textContent = title;
  $("#subtitle").textContent = sub;
  const b = $("#badge");
  b.classList.toggle("hidden", !badge);
  if (badge) { b.textContent = badge[0]; b.className = "badge " + badge[1]; }
  const n = testCount();
  $("#start-text").textContent = n ? t("start_n", { n: num(n) }).replace(/\s+/g, " ") : t("start");
  $("#btn-start").classList.toggle("hidden", !!AS.busy);
  $("#btn-stop").classList.toggle("hidden", !AS.busy);
  $("#btn-stop").disabled = AS.stopping || AS.busy === "disconnect";
  $("#stop-text").textContent = AS.busy === "disconnect" ? t("disconnecting") : AS.stopping ? t("stopping") : t("stop");
  $("#btn-disconnect").disabled = !!AS.busy;
  const chip = $("#conn-chip");
  chip.classList.toggle("hidden", !AS.connectedTo);
  $("#conn-text").textContent = AS.connectedTo ? t("connected_chip", { x: AS.connectedTo }) : "";
  document.title = run && run.kind === "job" && !run.finishedTs && AS.busy ? `AutoScribe · ${run.i}/${run.total}` : "AutoScribe";
}

/* ------------------------------------------------------------------ hero */
function bestRoutes() {
  return AS.results.filter((r) => r.status === "SUCCESS" && r.ping != null)
    .sort((a, b) => a.ping - b.ping || (a.loss || 0) - (b.loss || 0) || a.duration - b.duration);
}
function renderHero() {
  const best = bestRoutes()[0];
  const live = !!AS.busy && AS.busy !== "disconnect";
  $("#hero-live").classList.toggle("hidden", !live);
  $("#hero-best").classList.toggle("hidden", live || !best);
  $("#hero-ready").classList.toggle("hidden", live || !!best);
  if (!live && best) {
    $("#best-name").textContent = locName(best.city, best.node);
    $("#best-country").textContent = best.country;
    $("#best-route").textContent = `${best.proto}:${best.port}`;
    $("#best-ping").innerHTML = `<span class="ltr"><span class="c-${pingClass(best.ping)}">${Math.round(best.ping)}</span> <small class="muted">${esc(t("ms"))}</small></span>`;
    $("#best-loss").textContent = best.loss != null ? `${Math.round(best.loss)}%` : "–";
    $("#best-time").innerHTML = `<span class="ltr">${best.duration.toFixed(1)} <small class="muted">s</small></span>`;
    $("#btn-best-connect").disabled = !!AS.busy;
  } else if (!live) {
    const [title, sub] = scopeSummary();
    $("#ready-title").textContent = title;
    $("#ready-sub").textContent = sub + " · " + t("tests_queued", { n: num(testCount()) });
  }
}
function setTarget(captionKey, city, node, country, route, cls = "live") {
  const c = $("#live-caption");
  c.textContent = t(captionKey);
  c.className = "caption " + cls;
  $("#live-target").textContent = locName(city, node);
  $("#live-sub").textContent = country;
  const r = $("#live-route");
  r.textContent = route;
  r.classList.toggle("hidden", !route);
}
function resetLive() {
  AS.phases = [0, 1, 2, 3].map(() => ({ state: "idle", secs: null }));
  setPing(undefined);
  renderPhases();
}
function renderPhases() {
  const keys = ["phase_disconnect", "phase_connect", "phase_settle", "phase_ping"];
  $("#phases").innerHTML = keys.map((k, i) => {
    const p = AS.phases[i] || { state: "idle" };
    let right = "";
    if (p.state === "skip") right = t("skipped");
    else if (p.state === "fail") right = p.secs ? t("secs", { v: p.secs.toFixed(1) }) : t("failed_lc");
    else if (p.secs != null && p.state === "done") right = t("secs", { v: p.secs.toFixed(1) });
    else if (p.state === "active") right = "…";
    return `<div class="phase ${p.state}"><div class="bar"><i></i></div><div class="meta"><span>${esc(t(k))}</span><span class="mono">${esc(right)}</span></div></div>`;
  }).join("");
}
function setPing(result) {
  const fill = $("#gauge-fill"), v = $("#live-ping"), d = $("#live-ping-detail");
  if (result === undefined) {
    v.textContent = "–"; v.className = "mono gauge-value"; d.textContent = "";
    fill.style.strokeDashoffset = 314.16; fill.style.stroke = "var(--muted)";
    return;
  }
  if (!result) {
    v.textContent = "–"; v.className = "mono gauge-value c-bad"; d.textContent = t("no_reply");
    fill.style.strokeDashoffset = 0; fill.style.stroke = "var(--bad)";
    return;
  }
  const cls = pingClass(result.avg);
  v.textContent = Math.round(result.avg);
  v.className = "mono gauge-value c-" + cls;
  d.textContent = t("ping_detail", { a: Math.round(result.min), b: Math.round(result.max), c: Math.round(result.loss) });
  // Full ring = 0 ms, empty = 400+ ms: a fuller ring is a better route.
  const frac = Math.max(0.04, 1 - Math.min(result.avg, 400) / 400);
  fill.style.strokeDashoffset = 314.16 * (1 - frac);
  fill.style.stroke = `var(--${cls})`;
}
function setProgress(frac) { $("#progress-fill").style.width = `${Math.max(0, Math.min(1, frac)) * 100}%`; }

function tick() {
  const run = AS.run;
  if (AS.busy === "job" && run && !run.finishedTs) {
    const now = Date.now();
    const elapsed = t("elapsed", { t: duration((now - run.startedTs) / 1000) });
    let text = elapsed;
    if (run.stepTs) {
      if (run.executed > 1) {
        const avg = (run.stepTs - run.startedTs) / (run.executed - 1);
        const left = Math.max(avg * (run.total - run.i + 1) - (now - run.stepTs), avg * (run.total - run.i));
        text = t("time_left", { left: duration(left / 1000), elapsed });
      } else text = t("estimating", { elapsed });
    }
    $("#prog-right").textContent = text;
  }
}

/* ------------------------------------------------------------------ stats + insights */
function renderStats() {
  const total = AS.results.length;
  const ok = AS.results.filter((r) => r.status === "SUCCESS");
  const best = bestRoutes()[0];
  const avg = ok.length ? ok.reduce((a, r) => a + r.duration, 0) / ok.length : null;
  const tiles = [
    ["stat_connected", num(ok.length), "var(--good)", ICON.ok],
    ["stat_failed", num(total - ok.length), "var(--bad)", ICON.x],
    ["stat_rate", total ? `${Math.round((ok.length / total) * 100)}%` : "–", "var(--accent)", ICON.rate],
    ["stat_best", best ? `${Math.round(best.ping)} ${t("ms")}` : "–", best ? `var(--${pingClass(best.ping)})` : "var(--accent-2)", ICON.bolt],
    ["stat_avg", avg != null ? t("secs", { v: avg.toFixed(1) }) : "–", "#A78BFA", ICON.clock],
  ];
  $("#stats").innerHTML = tiles.map(([k, v, c, icon]) =>
    `<div class="card stat" style="--c:${c}"><div class="stat-icon">${icon}</div><div style="min-width:0"><div class="k">${esc(t(k))}</div><div class="v"><span class="ltr">${esc(v)}</span></div></div></div>`).join("");
}
function renderInsights() {
  const stats = {};
  for (const r of AS.results) {
    const s = (stats[r.proto] ||= [0, 0]);
    s[1]++;
    if (r.status === "SUCCESS") s[0]++;
  }
  const entries = Object.entries(stats).sort((a, b) => b[1][0] / b[1][1] - a[1][0] / a[1][1]);
  $("#works").innerHTML = entries.length ? entries.map(([proto, [ok, total]]) => {
    const rate = ok / total, cls = rateClass(rate);
    return `<div class="work"><div class="top"><span class="name">${esc(AS.protoNames[proto] || proto)}</span><span class="frac">${ok}/${total}</span>
      <span class="pct c-${cls}">${Math.round(rate * 100)}%</span></div><div class="bar"><i class="bg-${cls}" style="width:${Math.max(2, rate * 100)}%"></i></div></div>`;
  }).join("") : `<div class="works-empty">${esc(t("works_empty"))}</div>`;
  const weak = entries.filter(([, [ok, total]]) => total >= 4 && ok / total < 0.4).map(([p]) => AS.protoNames[p] || p);
  const tip = $("#works-tip");
  const showTip = weak.length && weak.length < entries.length && !AS.busy;
  tip.classList.toggle("hidden", !showTip);
  if (showTip) tip.textContent = t(weak.length === 1 ? "tip_one" : "tip_many", { names: weak.join(t("and")) });

  const pings = AS.results.filter((r) => r.status === "SUCCESS" && r.ping != null).map((r) => r.ping);
  const buckets = [[0, 50, "<50", "good"], [50, 100, "50–100", "warn"], [100, 200, "100–200", "bad"], [200, 400, "200–400", "bad"], [400, 1e9, "400+", "bad"]];
  const counts = buckets.map(([a, b]) => pings.filter((p) => p >= a && p < b).length);
  const max = Math.max(1, ...counts);
  $("#dist").innerHTML = pings.length ? buckets.map(([, , label, cls], i) =>
    `<div class="col"><b>${counts[i] || ""}</b><i class="bg-${cls}" style="height:${(counts[i] / max) * 62}px;opacity:${counts[i] ? 0.9 : 0.25}"></i><span>${label}</span></div>`).join("")
    : `<div class="works-empty">${esc(t("dist_empty"))}</div>`;
}

/* ------------------------------------------------------------------ tables */
const RESULT_COLS = [
  ["loc", "col_location", false, 25], ["country", "col_country", false, 14], ["route", "col_route", false, 15], ["status", "col_result", false, 15],
  ["time", "col_connect", true, 10], ["ping", "col_ping", true, 12], ["loss", "col_loss", true, 9]];
const BEST_COLS = [["loc", "col_location", false, 32], ["country", "col_country", false, 17], ["route", "col_route", false, 14],
  ["ping", "col_ping", true, 16], ["loss", "col_loss", true, 9], ["time", "col_connect", true, 12]];
const SORT_KEYS = {
  loc: (r) => locName(r.city, r.node).toLowerCase(), country: (r) => r.country.toLowerCase(), route: (r) => r.proto + String(r.port).padStart(6, "0"),
  status: (r) => r.status, time: (r) => (r.status === "SUCCESS" ? r.duration : Infinity),
  ping: (r) => (r.ping ?? Infinity), loss: (r) => (r.loss ?? Infinity),
};
const MAX_ROWS = 1500;

function renderTabs() {
  const counts = { results: AS.results.length, best: bestRoutes().length };
  const tabs = [["results", "tab_results"], ["best", "tab_best"], ["log", "tab_log"]];
  $("#tabs").innerHTML = tabs.map(([v, k]) => `<button class="${AS.view === v ? "on" : ""}" data-view="${v}">${esc(t(k))}${counts[v] ? `<span class="count">${num(counts[v])}</span>` : ""}</button>`).join("");
  $("#results-tools").classList.toggle("hidden", AS.view !== "results");
  $("#log-tools").classList.toggle("hidden", AS.view !== "log");
  $("#view-results").classList.toggle("hidden", AS.view !== "results");
  $("#view-best").classList.toggle("hidden", AS.view !== "best");
  $("#view-log").classList.toggle("hidden", AS.view !== "log");
}
function setView(v) { AS.view = v; renderTabs(); if (v === "log") scrollLog(); }

function head(cols, sortable) {
  return "<tr>" + cols.map(([key, label, n, w]) => {
    const arrow = sortable && AS.sort.col === key ? `<span class="arrow">${AS.sort.desc ? "↓" : "↑"}</span>` : "";
    return `<th class="${n ? "num" : ""}" style="width:${w}%" ${sortable ? `data-sort="${key}"` : ""}>${esc(t(label))}${arrow}</th>`;
  }).join("") + "</tr>";
}
function pingCell(r) {
  if (r.ping == null) return "–";
  const cls = pingClass(r.ping), w = Math.max(6, 100 - Math.min(r.ping, 400) / 4);
  return `<span class="ping-bar"><span class="ltr c-${cls}">${Math.round(r.ping)} ${esc(t("ms"))}</span><i><b class="bg-${cls}" style="width:${w}%"></b></i></span>`;
}
function renderResults() {
  const ok = AS.results.filter((r) => r.status === "SUCCESS").length;
  const counts = { All: AS.results.length, Connected: ok, Failed: AS.results.length - ok };
  $("#filters").innerHTML = Object.entries({ All: "filter_all", Connected: "filter_connected", Failed: "filter_failed" }).map(([name, k]) =>
    `<button class="pill ${AS.filter === name ? "on" : ""}" data-filter="${name}">${esc(t(k))}<span class="n">${num(counts[name])}</span></button>`).join("");

  const tokens = AS.search.toLowerCase().split(/\s+/).filter(Boolean);
  let rows = AS.results.filter((r) => {
    if (AS.filter === "Connected" && r.status !== "SUCCESS") return false;
    if (AS.filter === "Failed" && r.status === "SUCCESS") return false;
    if (tokens.length) {
      const hay = `${locName(r.city, r.node)} ${r.country} ${r.proto}:${r.port} ${statusLabel(r.status)}`.toLowerCase();
      if (!tokens.every((tok) => hay.includes(tok))) return false;
    }
    return true;
  });
  if (AS.sort.col) {
    const key = SORT_KEYS[AS.sort.col], dir = AS.sort.desc ? -1 : 1;
    rows.sort((a, b) => { const x = key(a), y = key(b); return (x < y ? -1 : x > y ? 1 : 0) * dir; });
  } else rows = rows.slice().reverse();

  const tbl = $("#tbl-results");
  tbl.tHead.innerHTML = head(RESULT_COLS, true);
  const shown = rows.slice(0, MAX_ROWS);
  tbl.tBodies[0].innerHTML = shown.map((r) => {
    const sc = statusClass(r.status);
    return `<tr data-id="${r.id}" class="${r.status !== "SUCCESS" ? "muted" : ""} ${AS.newIds.has(r.id) ? "new" : ""} ${AS.selectedRow === r.id ? "sel" : ""}">
      <td><span class="loc-cell"><span class="sdot ${sc}"></span><span>${esc(locName(r.city, r.node))}</span></span></td>
      <td>${esc(r.country)}</td><td class="route">${esc(r.proto)}:${r.port}</td>
      <td><span class="status ${sc}">${esc(statusLabel(r.status))}</span></td>
      <td class="num">${r.status === "SUCCESS" ? esc(t("secs", { v: r.duration.toFixed(1) })) : "–"}</td>
      <td class="num">${r.ping != null ? `<span class="ltr c-${pingClass(r.ping)}">${Math.round(r.ping)} ${esc(t("ms"))}</span>` : "–"}</td><td class="num">${r.loss != null ? Math.round(r.loss) + "%" : "–"}</td></tr>`;
  }).join("") + (rows.length > MAX_ROWS ? `<tr><td colspan="7" class="more-note">${esc(t("showing_first", { n: num(MAX_ROWS) }))}</td></tr>` : "");
  AS.newIds.clear();

  const empty = $("#empty-results");
  empty.classList.toggle("hidden", rows.length > 0);
  empty.innerHTML = `${ICON.table}<div>${esc(AS.results.length ? t("empty_filter") : t("empty_results"))}</div>`;
}
function renderBest() {
  const rows = bestRoutes().slice(0, 200);
  const tbl = $("#tbl-best");
  tbl.tHead.innerHTML = head(BEST_COLS, false);
  tbl.tBodies[0].innerHTML = rows.map((r, n) => `<tr data-id="${r.id}" class="${AS.selectedRow === r.id ? "sel" : ""}">
    <td><span class="loc-cell"><span class="rank">${n + 1}</span><span class="sdot ${pingClass(r.ping)}"></span><span>${esc(locName(r.city, r.node))}</span></span></td>
    <td>${esc(r.country)}</td><td class="route">${esc(r.proto)}:${r.port}</td><td class="num">${pingCell(r)}</td>
    <td class="num">${r.loss != null ? Math.round(r.loss) + "%" : "–"}</td><td class="num">${esc(t("secs", { v: r.duration.toFixed(1) }))}</td></tr>`).join("");
  const empty = $("#empty-best");
  empty.classList.toggle("hidden", rows.length > 0);
  empty.innerHTML = `${ICON.bolt}<div>${esc(t("empty_results"))}</div>`;
}
let renderQueued = false;
function renderData() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    renderTabs(); renderResults(); renderBest(); renderStats(); renderInsights();
    if (!AS.busy) renderHero();
    renderHeader();
  });
}
function addResult(row) {
  row.id = AS.results.length;
  AS.results.push(row);
  AS.newIds.add(row.id);
  renderData();
}
const rowById = (id) => AS.results[Number(id)];

/* ------------------------------------------------------------------ log */
function logMessage(msg, tag = "INFO") {
  const ts = new Date().toTimeString().slice(0, 8);
  const box = $("#view-log");
  const atBottom = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
  const line = document.createElement("div");
  line.innerHTML = `<span class="ts">${ts}</span><span class="${esc(tag)}">${esc(msg)}</span>`;
  box.appendChild(line);
  AS.logLines.push(`${ts}  ${msg}`);
  while (box.childElementCount > 4000) { box.firstChild.remove(); AS.logLines.shift(); }
  if (atBottom) scrollLog();
}
function scrollLog() { const b = $("#view-log"); b.scrollTop = b.scrollHeight; }

/* ------------------------------------------------------------------ jobs */
function readNumber(id, nameKey, min, max) {
  const el = $(id), v = Number(el.value.trim());
  if (!Number.isInteger(v) || v < min || v > max) {
    AS.timingOpen = true; setSidebar(true); renderTiming();
    el.classList.add("invalid"); el.focus();
    toast(t("must_be_number", { name: t(nameKey), a: min, b: max }), "error");
    return null;
  }
  el.classList.remove("invalid");
  return v;
}
async function startJob() {
  if (AS.busy) return;
  const plan = getPlan();
  if (!Object.keys(plan).length) return toast(t("need_protocol"), "warn");
  if (!(await checkCli())) return focusCli();
  const timeout = readNumber("#in-timeout", "name_timeout", 3, 300); if (timeout == null) return;
  const settle = readNumber("#in-settle", "name_settle", 0, 60); if (settle == null) return;
  const pings = readNumber("#in-pings", "name_pings", 1, 20); if (pings == null) return;
  const host = $("#in-host").value.trim();
  if (!host || /\s/.test(host)) { $("#in-host").classList.add("invalid"); return toast(t("bad_host"), "error"); }

  const resume = AS.settings.resume;
  const stored = AS.demo ? AS.results.length : await call("stored_count");
  if (!resume && stored && !(await confirmDialog(t("start_fresh"), t("start_fresh_q", { n: num(stored) }), t("start")))) return;

  const settings = gatherSettings();
  await call("save_state", settings, AS.cliPath);
  if (!resume) AS.results = [];
  const profile = AS.currentProfile || "Manual";
  const opts = { keys: [...AS.selected], resume, timeout, settle, ping_count: pings, ping_host: host,
    random_cities: settings.random_cities, chaos_mode: settings.chaos_mode, stop_on_success: settings.stop_on_success,
    smart_scan: settings.smart_scan, profile_name: profile };
  const res = AS.demo ? demoStart(plan, opts) : await call("start", plan, opts, AS.cliPath);
  if (!res || !res.ok) return toast(t(res && res.error === "cli_missing" ? "set_cli_path" : "wait_run"), "warn");

  AS.busy = "job"; AS.stopping = false; AS.connectedTo = null;
  AS.run = { kind: "job", scope: scopeSummary()[0], profile, startedTs: Date.now(), i: 0, total: testCount(), executed: 0, stepTs: null };
  resetLive();
  setTarget("cap_starting", scopeSummary()[0], "", "", "");
  $("#prog-left").textContent = t("preparing");
  $("#prog-right").textContent = "";
  setProgress(0);
  setView("results");
  renderData(); renderHero();
}
function stopJob() {
  if (AS.busy !== "job" && AS.busy !== "connect") return;
  AS.stopping = true;
  call("stop");
  if (AS.demo) demoStop();
  const c = $("#live-caption"); c.textContent = t("cap_stopping"); c.className = "caption stopping";
  renderHeader();
}
async function connectRow(r) {
  if (!r) return;
  if (AS.busy) return toast(t("wait_run"), "warn");
  if (!(await checkCli())) return focusCli();
  const timeout = Math.max(3, Math.min(300, parseInt($("#in-timeout").value, 10) || 25));
  const res = AS.demo ? { ok: true } : await call("connect", { city: r.city, node: r.node, country: r.country, proto: r.proto, port: r.port }, timeout, AS.cliPath);
  if (!res || !res.ok) return toast(t("wait_run"), "warn");
  AS.busy = "connect"; AS.stopping = false; AS.connectedTo = null;
  resetLive();
  setTarget("cap_connecting", r.city, r.node, r.country, `${r.proto}:${r.port}`);
  setProgress(0);
  $("#prog-left").textContent = t("manual_connection");
  $("#prog-right").textContent = "";
  renderHero(); renderHeader();
  if (AS.demo) setTimeout(() => AS.onEvent({ type: "manual_done", row: r, connected: true }), 1500);
}
async function disconnectVpn() {
  if (AS.busy) return;
  if (!(await checkCli())) return focusCli();
  const res = AS.demo ? { ok: true } : await call("disconnect", AS.cliPath);
  if (!res || !res.ok) return;
  AS.busy = "disconnect";
  renderHeader();
  if (AS.demo) setTimeout(() => AS.onEvent({ type: "disconnect_done", done: true }), 800);
}
async function exportCsv() {
  if (AS.demo) return toast(t("exported", { n: num(AS.results.length), f: "1_Manual.csv" }), "success");
  const res = await call("export_csv", AS.currentProfile || "Manual");
  if (!res) return;
  if (res.count === -1) toast(t("csv_failed", { e: res.path }), "error", { ms: 6000 });
  else toast(t("exported", { n: num(res.count), f: res.path }), "success", { action: () => call("open_reports"), actionText: t("open_folder"), ms: 7000 });
}

/* ------------------------------------------------------------------ events from Python */
AS.onEvent = function (ev) {
  switch (ev.type) {
    case "log": logMessage(ev.msg, ev.tag); break;
    case "phase": {
      const p = AS.phases[ev.idx];
      if (!p) break;
      if (ev.idx === 0 && ev.state === "active") AS.phases = [0, 1, 2, 3].map(() => ({ state: "idle", secs: null }));
      AS.phases[ev.idx] = { state: ev.state, secs: ev.secs };
      renderPhases();
      break;
    }
    case "begin": {
      const run = AS.run;
      if (!run) break;
      Object.assign(run, { i: ev.i, total: ev.total, executed: ev.executed, stepTs: Date.now() });
      AS.phases = [0, 1, 2, 3].map(() => ({ state: "idle", secs: null }));
      setPing(undefined);
      renderPhases();
      if (!AS.stopping) setTarget("cap_testing", ev.city, ev.node, ev.country, ev.route);
      else { $("#live-target").textContent = locName(ev.city, ev.node); $("#live-route").textContent = ev.route; }
      setProgress((ev.i - 1) / ev.total);
      $("#prog-left").textContent = t("test_progress", { i: num(ev.i), total: num(ev.total), pct: Math.floor(((ev.i - 1) / ev.total) * 100) });
      renderHeader();
      break;
    }
    case "ping": setPing(ev.result); break;
    case "result": addResult(ev.row); break;
    case "finished": {
      if (AS.run) Object.assign(AS.run, { finishedTs: Date.now(), stopped: ev.stopped, kept: ev.kept });
      AS.busy = null; AS.stopping = false;
      setProgress(1);
      if (ev.kept) {
        AS.connectedTo = ev.kept;
        logMessage("Job finished (connection kept).", "HEADER");
        toast(t("connected_to", { x: ev.kept }), "success");
      } else {
        logMessage("Job finished.", "HEADER");
        if (!ev.stopped && AS.results.length) toast(t("finished_toast", { ok: num(ev.ok), fail: num(ev.fail) }), "success", { action: () => setView("best"), actionText: t("tab_best"), ms: 7000 });
      }
      renderData(); renderHero();
      break;
    }
    case "manual_done": {
      AS.busy = null; AS.stopping = false;
      const r = ev.row, route = `${r.proto}:${r.port}`, where = locName(r.city, r.node);
      if (ev.connected) { AS.connectedTo = `${where} · ${route}`; toast(t("connected_via", { w: where, r: route }), "success"); }
      else toast(t("could_not_connect", { w: where, r: route }), "error");
      renderHero(); renderHeader();
      break;
    }
    case "disconnect_done":
      AS.busy = null;
      if (ev.done) AS.connectedTo = null;
      toast(ev.done ? t("disconnected") : t("could_not_disconnect"), ev.done ? "info" : "error", { ms: 2500 });
      renderHeader(); renderHero();
      break;
    case "locations_updated": locationsUpdated(ev); break;
  }
};

/* ------------------------------------------------------------------ locations */
function setLocations(list) {
  AS.locations = list;
  AS.allKeys = new Set(list.map(locKey));
}
function updateLocations() {
  const b = $("#btn-update-list");
  if (b.disabled) return;
  b.disabled = true;
  b.querySelector("span").textContent = t("updating");
  if (AS.demo) return setTimeout(() => locationsUpdated({ error: "demo mode" }), 900);
  call("update_locations");
}
function locationsUpdated(ev) {
  const b = $("#btn-update-list");
  b.disabled = false;
  b.querySelector("span").textContent = t("update_list");
  if (ev.error) { logMessage(`Server list update failed: ${ev.error}`, "FAILURE"); return toast(t("update_failed", { e: ev.error }), "error", { ms: 7000 }); }
  const old = AS.allKeys;
  setLocations(ev.locations);
  AS.updated = ev.updated;
  const added = [...AS.allKeys].filter((k) => !old.has(k)).length, removed = [...old].filter((k) => !AS.allKeys.has(k)).length;
  const hadSel = AS.selected.size > 0;
  AS.selected = new Set([...AS.selected].filter((k) => AS.allKeys.has(k)));
  const countries = new Set(AS.locations.map((l) => l.country)).size;
  let msg = t("list_updated", { n: num(AS.locations.length), c: num(countries) });
  const changes = [added && t("changes_new", { n: added }), removed && t("changes_removed", { n: removed })].filter(Boolean);
  msg += changes.length && old.size ? ` (${changes.join(AS.lang === "fa" ? "، " : ", ")}).` : ".";
  if (hadSel && !AS.selected.size) msg += t("selection_gone");
  logMessage(`Server list updated: ${AS.locations.length} servers, ${added} new, ${removed} removed.`, "SUCCESS");
  toast(msg, "success", { ms: 6000 });
  renderScope(); renderHero(); saveSoon();
  if (AS.picker) renderPicker();
}

function openPicker() {
  if (!AS.locations.length) return toast(t("no_list_toast"), "warn", { action: updateLocations, actionText: t("download"), ms: 7000 });
  AS.picker = { selected: new Set(AS.selected), region: "All", open: new Set() };
  $("#picker-search").value = "";
  $("#picker").classList.remove("hidden");
  renderPickerRegions();
  renderPicker();
  setTimeout(() => $("#picker-search").focus(), 50);
}
function closePicker(apply) {
  if (apply && AS.picker) {
    const keys = new Set([...AS.picker.selected].filter((k) => AS.allKeys.has(k)));
    AS.selected = keys.size === AS.allKeys.size ? new Set() : keys;
    renderScope(); if (!AS.busy) { AS.run = null; renderHero(); }
    saveSoon();
  }
  AS.picker = null;
  $("#picker").classList.add("hidden");
}
function renderPickerRegions() {
  const regions = ["All", ...[...new Set(AS.locations.map((l) => l.continent))].sort()];
  if (AS.locations.some((l) => l.country.startsWith("US "))) regions.push("USA");
  $("#picker-regions").innerHTML = regions.map((r) => `<button class="pill ${AS.picker.region === r ? "on" : ""}" data-region="${esc(r)}">${esc(regionName(r))}</button>`).join("");
}
function pickerMatches() {
  const tokens = $("#picker-search").value.toLowerCase().split(/\s+/).filter(Boolean);
  const byCountry = new Map();
  const sorted = AS.locations.slice().sort((a, b) => a.country.localeCompare(b.country) || a.city.localeCompare(b.city) || a.location.localeCompare(b.location));
  for (const l of sorted) {
    if (!matchesRegion(l, AS.picker.region)) continue;
    if (!byCountry.has(l.country)) byCountry.set(l.country, []);
    byCountry.get(l.country).push(l);
  }
  const out = [];
  for (const [country, locs] of byCountry) {
    let list = locs;
    if (tokens.length) {
      const hay = `${country} ${locs[0].continent} ${regionName(locs[0].continent)}`.toLowerCase();
      if (!tokens.every((tok) => hay.includes(tok))) list = locs.filter((l) => tokens.every((tok) => `${country} ${l.continent} ${l.city} ${l.location}`.toLowerCase().includes(tok)));
    }
    if (list.length) out.push([country, list]);
  }
  return [out, tokens.length > 0];
}
function renderPicker() {
  const P = AS.picker;
  const [matches, searching] = pickerMatches();
  P.shown = matches;
  const html = [];
  for (const [country, locs] of matches) {
    const keys = locs.map(locKey), n = keys.filter((k) => P.selected.has(k)).length;
    const state = n === 0 ? "" : n === keys.length ? "on" : "part";
    const open = searching || matches.length === 1 || P.open.has(country);
    html.push(`<div class="pk-row country" data-country="${esc(country)}">
      <span class="exp ${open ? "open" : ""}" data-expand="${esc(country)}">${ICON.chev}</span>
      <button class="check ${state}">${ICON.check}</button><span class="name">${esc(country)}</span>
      <span class="cnt">${n ? `${n}/${keys.length}` : keys.length}</span></div>`);
    if (open) for (const l of locs) {
      const k = locKey(l);
      html.push(`<div class="pk-row server" data-key="${esc(k)}"><button class="check ${P.selected.has(k) ? "on" : ""}">${ICON.check}</button><span class="name">${esc(locName(l.city, l.location))}</span></div>`);
    }
  }
  $("#picker-list").innerHTML = html.join("") || `<div class="pk-empty">${esc(t("no_match_locations"))}</div>`;
  const n = P.selected.size;
  $("#picker-count").textContent = n ? t("n_selected", { n: num(n), total: num(AS.locations.length) }) : t("nothing_selected", { total: num(AS.locations.length) });
}
function onPickerClick(e) {
  const P = AS.picker;
  const exp = e.target.closest("[data-expand]");
  if (exp) { const c = exp.dataset.expand; P.open.has(c) ? P.open.delete(c) : P.open.add(c); return renderPicker(); }
  const row = e.target.closest(".pk-row");
  if (!row) return;
  const keys = row.dataset.key ? [row.dataset.key] : P.shown.find(([c]) => c === row.dataset.country)[1].map(locKey);
  const all = keys.every((k) => P.selected.has(k));
  keys.forEach((k) => (all ? P.selected.delete(k) : P.selected.add(k)));
  renderPicker();
}

/* ------------------------------------------------------------------ profiles */
async function saveProfileFlow() {
  const name = await promptDialog(t("save_profile"), t("save_profile_hint"), t("profile_name"), AS.currentProfile);
  if (!name) return;
  AS.profiles = (await call("save_profile", name, gatherSettings())) || { ...AS.profiles, [name]: gatherSettings() };
  AS.currentProfile = name;
  renderProfiles();
  toast(t("profile_saved", { n: name }), "success");
}
function loadProfile(name) {
  if (!name || !AS.profiles[name]) return;
  AS.currentProfile = name;
  applySettings(AS.profiles[name]);
  AS.run = null; renderHero(); saveSoon();
  toast(t("profile_loaded", { n: name }), "info", { ms: 2200 });
}
async function deleteProfileFlow() {
  const name = $("#sel-profile").value;
  if (!name) return toast(t("choose_to_delete"), "warn");
  if (!(await confirmDialog(t("delete_profile"), t("delete_profile_q", { n: name }), t("delete_profile")))) return;
  AS.profiles = (await call("delete_profile", name)) || (delete AS.profiles[name], AS.profiles);
  if (AS.currentProfile === name) AS.currentProfile = "";
  renderProfiles();
  toast(t("profile_deleted", { n: name }), "info");
}

/* ------------------------------------------------------------------ UI bits */
function toast(msg, kind = "info", { action, actionText, ms = 4000 } = {}) {
  const el = document.createElement("div");
  el.className = `toast ${kind}`;
  const icon = { success: ICON.ok, error: ICON.x, warn: ICON.warn, info: ICON.info }[kind];
  el.innerHTML = `<span class="ti">${icon}</span><span class="msg">${esc(msg)}</span>${action ? `<button class="act">${esc(actionText)}</button>` : ""}`;
  const close = () => { el.classList.add("out"); setTimeout(() => el.remove(), 200); };
  if (action) el.querySelector(".act").onclick = () => { action(); close(); };
  $("#toasts").appendChild(el);
  while ($("#toasts").childElementCount > 4) $("#toasts").firstChild.remove();
  setTimeout(close, ms);
}
let dialogResolve = null;
function openDialog(title, text, okText, input) {
  $("#dialog-title").textContent = title;
  $("#dialog-text").textContent = text;
  $("#dialog-ok").textContent = okText;
  const inp = $("#dialog-input");
  inp.classList.toggle("hidden", input == null);
  if (input != null) { inp.placeholder = input.placeholder; inp.value = input.value || ""; }
  $("#dialog").classList.remove("hidden");
  setTimeout(() => (input != null ? inp.focus() : $("#dialog-ok").focus()), 30);
  return new Promise((res) => (dialogResolve = res));
}
function closeDialog(ok) {
  $("#dialog").classList.add("hidden");
  const inp = $("#dialog-input");
  const val = ok ? (inp.classList.contains("hidden") ? true : inp.value.trim()) : null;
  if (dialogResolve) dialogResolve(val);
  dialogResolve = null;
}
const confirmDialog = (title, text, okText) => openDialog(title, text, okText, null).then((v) => !!v);
const promptDialog = (title, text, placeholder, value) => openDialog(title, text, t("save"), { placeholder, value });

function showMenu(x, y, r) {
  const m = $("#row-menu");
  m.innerHTML = `<button data-m="connect" ${AS.busy ? "disabled" : ""}>${ICON.plug}${esc(t("menu_connect"))}</button><hr>
    <button data-m="route">${ICON.copy}${esc(t("menu_copy_route"))}</button><button data-m="row">${ICON.copy}${esc(t("menu_copy_row"))}</button>`;
  m.classList.remove("hidden");
  const w = m.offsetWidth, h = m.offsetHeight;
  m.style.left = Math.min(x, innerWidth - w - 8) + "px";
  m.style.top = Math.min(y, innerHeight - h - 8) + "px";
  m.onclick = (e) => {
    const b = e.target.closest("button");
    if (!b || b.disabled) return;
    m.classList.add("hidden");
    if (b.dataset.m === "connect") return connectRow(r);
    const text = b.dataset.m === "route" ? `${r.city}  ${r.proto}:${r.port}`
      : [locName(r.city, r.node), r.country, `${r.proto}:${r.port}`, statusLabel(r.status), r.status === "SUCCESS" ? r.duration.toFixed(1) : "–", r.ping != null ? Math.round(r.ping) : "–", r.loss != null ? Math.round(r.loss) + "%" : "–"].join("\t");
    copy(text, t("copied"));
  };
}
async function copy(text, msg) {
  let ok = false;
  try { await navigator.clipboard.writeText(text); ok = true; } catch (e) { ok = !!(await call("copy_text", text)); }
  if (ok) toast(msg, "success", { ms: 1800 });
}
function setSidebar(open) {
  $("#app").classList.toggle("collapsed", !open);
  $("#btn-expand").classList.toggle("hidden", open);
  call("save_state", null, null, null, open);
}

let tipTimer = null;
function setupTooltips() {
  const tipEl = $("#tooltip");
  document.addEventListener("mouseover", (e) => {
    const el = e.target.closest("[data-tip], [data-tip-key]");
    clearTimeout(tipTimer);
    tipEl.classList.add("hidden");
    if (!el) return;
    tipTimer = setTimeout(() => {
      tipEl.textContent = el.dataset.tip || t(el.dataset.tipKey);
      tipEl.classList.remove("hidden");
      const r = el.getBoundingClientRect(), w = tipEl.offsetWidth, h = tipEl.offsetHeight;
      let x = r.left + r.width / 2 - w / 2, y = r.bottom + 8;
      if (y + h > innerHeight - 8) y = r.top - h - 8;
      tipEl.style.left = Math.max(8, Math.min(x, innerWidth - w - 8)) + "px";
      tipEl.style.top = y + "px";
    }, 450);
  });
  document.addEventListener("mousedown", () => { clearTimeout(tipTimer); tipEl.classList.add("hidden"); });
}

/* ------------------------------------------------------------------ wiring */
function bind() {
  $("#btn-collapse").onclick = () => setSidebar(false);
  $("#btn-expand").onclick = () => setSidebar(true);
  $("#sel-profile").onchange = (e) => loadProfile(e.target.value);
  $("#btn-save-profile").onclick = saveProfileFlow;
  $("#btn-del-profile").onclick = deleteProfileFlow;
  $("#btn-update-list").onclick = updateLocations;
  $("#scope-card").onclick = openPicker;
  $("#proto-list").onclick = onProtoClick;
  $("#options").onclick = (e) => {
    const o = e.target.closest("[data-opt]");
    if (!o) return;
    AS.settings[o.dataset.opt] = !AS.settings[o.dataset.opt];
    renderOptions(); saveSoon();
  };
  $("#btn-timing").onclick = () => { AS.timingOpen = !AS.timingOpen; renderTiming(); };
  for (const id of ["#in-host", "#in-pings", "#in-timeout", "#in-settle"]) $(id).addEventListener("input", (e) => { e.target.classList.remove("invalid"); renderTiming(); saveSoon(); });
  $("#in-cli").addEventListener("change", checkCli);
  $("#btn-browse").onclick = async () => { const p = await call("browse_cli"); if (p) { $("#in-cli").value = p; checkCli(); } };
  $("#seg-lang").onclick = (e) => {
    const b = e.target.closest("[data-lang]");
    if (!b || b.dataset.lang === AS.lang) return;
    AS.lang = b.dataset.lang;
    call("save_state", null, null, AS.lang);
    applyStaticText(); renderSidebar(); renderData(); renderHero(); renderPhases();
  };
  $("#cli-pill").onclick = () => { AS.timingOpen = true; renderTiming(); $("#in-cli").focus(); };

  $("#btn-start").onclick = startJob;
  $("#btn-stop").onclick = stopJob;
  $("#btn-export").onclick = exportCsv;
  $("#btn-disconnect").onclick = disconnectVpn;
  $("#btn-best-connect").onclick = () => connectRow(bestRoutes()[0]);

  $("#tabs").onclick = (e) => { const b = e.target.closest("[data-view]"); if (b) setView(b.dataset.view); };
  $("#filters").onclick = (e) => { const b = e.target.closest("[data-filter]"); if (b) { AS.filter = b.dataset.filter; renderResults(); } };
  let searchTimer;
  $("#in-search").addEventListener("input", (e) => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { AS.search = e.target.value; renderResults(); }, 120); });
  $("#tbl-results").tHead.onclick = (e) => {
    const th = e.target.closest("[data-sort]");
    if (!th) return;
    const col = th.dataset.sort, s = AS.sort;
    if (s.col === col) { if (s.desc) { s.col = null; s.desc = false; } else s.desc = true; } else { s.col = col; s.desc = false; }
    renderResults();
  };
  for (const id of ["#tbl-results", "#tbl-best"]) {
    const body = $(id).tBodies[0];
    body.onclick = (e) => { const tr = e.target.closest("tr[data-id]"); if (!tr) return; AS.selectedRow = Number(tr.dataset.id); body.querySelectorAll("tr.sel").forEach((x) => x.classList.remove("sel")); tr.classList.add("sel"); };
    body.ondblclick = (e) => { const tr = e.target.closest("tr[data-id]"); if (tr) connectRow(rowById(tr.dataset.id)); };
    body.oncontextmenu = (e) => { const tr = e.target.closest("tr[data-id]"); if (!tr) return; e.preventDefault(); body.onclick(e); showMenu(e.clientX, e.clientY, rowById(tr.dataset.id)); };
  }
  $("#btn-log-clear").onclick = () => { $("#view-log").innerHTML = ""; AS.logLines = []; };
  $("#btn-log-copy").onclick = () => copy(AS.logLines.join("\n"), t("log_copied"));

  $("#picker-close").onclick = () => closePicker(false);
  $("#picker-done").onclick = () => closePicker(true);
  $("#picker-clear").onclick = () => { AS.picker.selected.clear(); renderPicker(); };
  $("#picker-shown").onclick = () => { for (const [, locs] of AS.picker.shown) locs.forEach((l) => AS.picker.selected.add(locKey(l))); renderPicker(); };
  $("#picker-list").onclick = onPickerClick;
  $("#picker-regions").onclick = (e) => { const b = e.target.closest("[data-region]"); if (b) { AS.picker.region = b.dataset.region; renderPickerRegions(); renderPicker(); } };
  let pickTimer;
  $("#picker-search").addEventListener("input", () => { clearTimeout(pickTimer); pickTimer = setTimeout(renderPicker, 100); });
  $("#picker").addEventListener("mousedown", (e) => { if (e.target.id === "picker") closePicker(false); });

  $("#dialog-ok").onclick = () => closeDialog(true);
  $("#dialog-cancel").onclick = () => closeDialog(false);
  $("#dialog-input").addEventListener("keydown", (e) => { if (e.key === "Enter") closeDialog(true); });

  document.addEventListener("mousedown", (e) => { if (!e.target.closest("#row-menu")) $("#row-menu").classList.add("hidden"); });
  document.addEventListener("keydown", (e) => {
    const k = e.key.toLowerCase();
    if (!$("#dialog").classList.contains("hidden")) { if (k === "escape") closeDialog(false); return; }
    if (AS.picker) { if (k === "escape") closePicker(false); if (k === "enter" && e.target.id === "picker-search") closePicker(true); return; }
    if ((e.ctrlKey && k === "enter") || k === "f5") { e.preventDefault(); startJob(); }
    else if (k === "escape") { $("#row-menu").classList.add("hidden"); stopJob(); }
    else if (e.ctrlKey && k === "e") { e.preventDefault(); exportCsv(); }
    else if (e.ctrlKey && k === "s") { e.preventDefault(); saveProfileFlow(); }
    else if (e.ctrlKey && k === "l") { e.preventDefault(); openPicker(); }
    else if (e.ctrlKey && k === "b") { e.preventDefault(); setSidebar($("#app").classList.contains("collapsed")); }
    else if (e.ctrlKey && k === "f") { e.preventDefault(); setView("results"); $("#in-search").focus(); }
  });
  document.addEventListener("contextmenu", (e) => { if (!e.target.closest("input")) e.preventDefault(); });
  setupTooltips();
  setInterval(tick, 1000);
}

/* ------------------------------------------------------------------ boot */
function boot(data) {
  AS.S = data.strings; AS.regions = data.regions; AS.languages = data.languages; AS.lang = data.language || "en";
  AS.version = data.version; AS.protocols = data.protocols; AS.protoNames = data.proto_names;
  AS.profiles = data.profiles || {}; AS.cliPath = data.cli_path; AS.cliOk = data.cli_ok; AS.updated = data.locations_updated;
  setLocations(data.locations || []);
  AS.results = (data.results || []).map((r, i) => ({ ...r, id: i }));
  applyStaticText();
  applySettings(data.settings);
  bind();
  if (data.sidebar_open === false) setSidebar(false);
  resetLive();
  renderData(); renderHero();
  logMessage(`AutoScribe ${data.version} ready${AS.demo ? " (demo mode – not connected to Python)" : ""}.`, "HEADER");
}

window.addEventListener("pywebviewready", async () => {
  if (AS.api) return;
  AS.api = window.pywebview.api;
  boot(await AS.api.init());
});
// Opened in a plain browser (no pywebview): run a self-contained demo so the interface can be previewed.
setTimeout(() => { if (!AS.api && !window.pywebview) { AS.demo = true; AS.api = {}; boot(demoData()); } }, 600);

/* ------------------------------------------------------------------ demo */
let demoTimer = null;
function demoData() {
  const cities = [["Canada East", "North America", "Montreal", "Expo 67"], ["Canada East", "North America", "Toronto", "The 6"], ["Canada West", "North America", "Vancouver", "Stanley"],
    ["Germany", "Europe", "Frankfurt", "Zeil"], ["Germany", "Europe", "Berlin", "Brandenburg"], ["Netherlands", "Europe", "Amsterdam", "Tulip"], ["United Kingdom", "Europe", "London", "Crown"],
    ["Turkey", "Europe", "Istanbul", "Bosporus"], ["UAE", "Asia", "Dubai", "Khalifa"], ["Japan", "Asia", "Tokyo", "Shibuya"], ["US East", "North America", "New York", "Empire"], ["France", "Europe", "Paris", "Seine"]];
  const locations = cities.map(([country, continent, city, location]) => ({ country, continent, city, location }));
  const protos = { wireguard: [443, 80, 53, 123, 1194, 65142], ikev2: [500], udp: [443, 80, 53, 123, 1194, 54783], tcp: [443, 587, 21, 22, 80, 123, 143, 3306, 8080, 54783, 1194], stealth: [443, 587, 21, 22, 80, 123, 143, 3306, 8080, 54783, 8443], wstunnel: [443] };
  const results = [];
  const rnd = (a, b) => a + Math.random() * (b - a);
  for (const l of locations) for (const [proto, port] of [["wstunnel", 443], ["stealth", 443], ["tcp", 443]]) {
    const ok = Math.random() < (proto === "tcp" ? 0.2 : 0.65);
    results.push({ city: l.city, node: l.location, country: l.country, proto, port, status: ok ? "SUCCESS" : "FAILURE", duration: ok ? rnd(5, 18) : 0, ping: ok ? rnd(35, 420) : null, loss: ok ? Math.round(rnd(0, 1.4)) * 20 : null });
  }
  return {
    version: "2.0.0", strings: window.DEMO_STRINGS || {}, regions: {}, languages: { en: "English", fa: "فارسی", ru: "Русский" }, language: "en",
    settings: { protocols: { wireguard: { enabled: false }, ikev2: { enabled: false }, udp: { enabled: false }, tcp: { enabled: true }, stealth: { enabled: true } } },
    profiles: { "Home Wi-Fi": {} }, cli_path: "C:\\Program Files\\Windscribe\\windscribe-cli.exe", cli_ok: true, sidebar_open: true,
    protocols: protos, proto_names: { wireguard: "WireGuard", ikev2: "IKEv2", udp: "UDP", tcp: "TCP", stealth: "Stealth", wstunnel: "WStunnel" },
    locations, locations_updated: "2026-10-18 10:00", results,
  };
}
function demoStart(plan, opts) {
  const locs = scopeLocations();
  const tasks = [];
  for (const l of locs) for (const [proto, ports] of Object.entries(plan)) for (const port of ports) tasks.push([l, proto, port]);
  let i = 0;
  const step = () => {
    if (i >= tasks.length || AS.stopping) return AS.onEvent({ type: "finished", kept: null, ok: 0, fail: 0, stopped: AS.stopping });
    const [l, proto, port] = tasks[i++];
    AS.onEvent({ type: "begin", i, total: tasks.length, executed: i, city: l.city, node: l.location, country: l.country, route: `${proto}:${port}` });
    AS.onEvent({ type: "phase", idx: 0, state: "active" });
    demoTimer = setTimeout(() => {
      AS.onEvent({ type: "phase", idx: 0, state: "done", secs: 0.4 });
      AS.onEvent({ type: "phase", idx: 1, state: "active" });
      demoTimer = setTimeout(() => {
        const ok = Math.random() < 0.5, ping = 30 + Math.random() * 300;
        AS.onEvent({ type: "phase", idx: 1, state: ok ? "done" : "fail", secs: 2 + Math.random() * 8 });
        if (ok) { AS.onEvent({ type: "phase", idx: 2, state: "skip" }); AS.onEvent({ type: "phase", idx: 3, state: "done", secs: 1 }); AS.onEvent({ type: "ping", result: { avg: ping, min: ping - 10, max: ping + 20, loss: 0 } }); }
        AS.onEvent({ type: "log", msg: `${l.city} via ${proto}:${port} → ${ok ? "connected" : "timed out"}`, tag: ok ? "SUCCESS" : "FAILURE" });
        AS.onEvent({ type: "result", row: { city: l.city, node: l.location, country: l.country, proto, port, status: ok ? "SUCCESS" : "FAILURE", duration: ok ? 6 : 0, ping: ok ? ping : null, loss: ok ? 0 : null } });
        demoTimer = setTimeout(step, 600);
      }, 1200);
    }, 500);
  };
  setTimeout(step, 300);
  return { ok: true };
}
function demoStop() {}
