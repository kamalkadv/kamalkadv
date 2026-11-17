// Dashboard admin KioskOS. Tanpa framework; semua data dirender dengan textContent (anti-XSS).
"use strict";

const TOKEN_KEY = "kioskos.adminToken";
const $ = (id) => document.getElementById(id);

// ------------------------------------------------------------ utilitas
function getToken() {
  try { return sessionStorage.getItem(TOKEN_KEY) || ""; } catch (e) { return ""; }
}
function setToken(t) {
  try { t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY); } catch (e) { /* abaikan */ }
}

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: Object.assign({ "Authorization": "Bearer " + getToken() },
                           body ? { "Content-Type": "application/json" } : {}),
    body: body ? JSON.stringify(body) : undefined,
  });
  let data = null;
  try { data = await res.json(); } catch (e) { /* bukan JSON */ }
  if (res.status === 401) {
    showLogin("Sesi berakhir atau token salah.");
    throw new Error("unauthorized");
  }
  if (!res.ok) throw new Error((data && data.error) || ("HTTP " + res.status));
  return data;
}

function el(tag, attrs, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    e.append(c instanceof Node ? c : String(c));
  }
  return e;
}

// Beri tiap sel label kolomnya, agar di layar kecil baris bisa tampil sebagai kartu.
function labelCells(tbody) {
  const heads = [...tbody.closest("table").querySelectorAll("thead th")].map((th) => th.textContent);
  for (const row of tbody.rows) {
    [...row.cells].forEach((td, i) => {
      td.dataset.label = heads[i] || "";
      // Bungkus isi sel jadi satu elemen, agar di tampilan kartu (grid 2 kolom: label | isi)
      // isi yang terdiri dari beberapa baris tidak tumpah ke kolom label.
      const wrap = el("div", { class: "cell" });
      wrap.append(...td.childNodes);
      td.replaceChildren(wrap);
    });
  }
}

function relTime(ts) {
  if (!ts) return "belum pernah";
  const s = Math.max(0, Math.floor(Date.now() / 1000 - ts));
  if (s < 60) return "baru saja";
  if (s < 3600) return Math.floor(s / 60) + " menit lalu";
  if (s < 86400) return Math.floor(s / 3600) + " jam lalu";
  return Math.floor(s / 86400) + " hari lalu";
}
function fmtDate(ts) {
  return ts ? new Date(ts * 1000).toLocaleString("id-ID", { dateStyle: "medium", timeStyle: "short" }) : "-";
}
function fmtSize(n) {
  if (!n) return "-";
  const u = ["B", "KB", "MB", "GB"]; let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return n.toFixed(i ? 1 : 0) + " " + u[i];
}

let flashTimer;
function flash(msg, isError) {
  const f = $("flash");
  f.textContent = msg;
  f.className = "flash" + (isError ? " error" : "");
  f.hidden = false;
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => { f.hidden = true; }, 5000);
}

async function run(action, okMsg) {
  try {
    await action();
    if (okMsg) flash(okMsg);
  } catch (e) {
    if (e.message !== "unauthorized") flash("Gagal: " + e.message, true);
  }
}

function confirmDialog(title, text, okLabel) {
  return new Promise((resolve) => {
    const d = $("confirm-dialog");
    $("confirm-title").textContent = title;
    $("confirm-text").textContent = text;
    $("confirm-ok").textContent = okLabel || "Ya";
    d.returnValue = "";
    d.addEventListener("close", () => resolve(d.returnValue === "ok"), { once: true });
    d.showModal();
  });
}

// ------------------------------------------------------------ login
function showLogin(err) {
  setToken("");
  $("app").hidden = true;
  $("login").hidden = false;
  $("login-error").hidden = !err;
  $("login-error").textContent = err || "";
  $("token").value = "";
  $("token").focus();
}

async function start() {
  try {
    const me = await api("GET", "/admin/api/whoami");
    $("whoami").textContent = "Masuk sebagai " + me.admin;
    $("login").hidden = true;
    $("app").hidden = false;
    selectTab(location.hash.slice(1) || "devices");
  } catch (e) {
    if (e.message !== "unauthorized") showLogin("Tidak bisa menghubungi server: " + e.message);
  }
}

$("login-form").addEventListener("submit", (ev) => {
  ev.preventDefault();
  setToken($("token").value.trim());
  start();
});
$("logout").addEventListener("click", () => showLogin());

// ------------------------------------------------------------ tab
let refreshTimer;
function selectTab(name) {
  if (!["devices", "releases", "audit"].includes(name)) name = "devices";
  for (const b of document.querySelectorAll(".tabs button")) {
    b.setAttribute("aria-selected", String(b.dataset.tab === name));
  }
  for (const t of ["devices", "releases", "audit"]) $("tab-" + t).hidden = t !== name;
  history.replaceState(null, "", "#" + name);
  clearInterval(refreshTimer);
  const load = { devices: loadDevices, releases: loadReleases, audit: loadAudit }[name];
  load();
  refreshTimer = setInterval(load, 30000);
}
for (const b of document.querySelectorAll(".tabs button")) {
  b.addEventListener("click", () => selectTab(b.dataset.tab));
}

// ------------------------------------------------------------ perangkat
let devices = [];

function deviceStatus(d) {
  if (!d.enrolled) return "released";
  return d.locked ? "locked" : "active";
}
const STATUS_LABEL = { locked: "Terkunci", active: "Aktif", released: "Lepas" };

async function loadDevices() {
  await run(async () => {
    const data = await api("GET", "/admin/api/devices");
    devices = Object.entries(data).map(([serial, d]) => Object.assign({ serial }, d));
    devices.sort((a, b) => (b.updated_at || 0) - (a.updated_at || 0));
    renderDevices();
  });
}

function renderDevices() {
  const counts = { total: devices.length, locked: 0, active: 0, released: 0 };
  for (const d of devices) counts[deviceStatus(d)]++;
  const stats = $("stats");
  stats.replaceChildren(
    ...[["Total", counts.total], ["Terkunci", counts.locked], ["Aktif", counts.active], ["Lepas", counts.released]]
      .map(([l, n]) => el("div", { class: "stat" }, el("div", { class: "n" }, n), el("div", { class: "l" }, l))));

  const q = $("filter").value.trim().toLowerCase();
  const sf = $("status-filter").value;
  const rows = devices.filter((d) => {
    if (sf && deviceStatus(d) !== sf) return false;
    if (!q) return true;
    return [d.serial, d.imei, d.note, d.message].some((v) => (v || "").toLowerCase().includes(q));
  });

  $("devices-body").replaceChildren(...rows.map(deviceRow));
  labelCells($("devices-body"));
  $("devices-empty").hidden = rows.length > 0;
  $("devices-empty").textContent = devices.length ? "Tidak ada yang cocok dengan filter." : "Belum ada perangkat.";
}

function deviceRow(d) {
  const st = deviceStatus(d);
  const actions = el("div", { class: "actions" });
  if (st === "locked") {
    actions.append(el("button", { class: "small", onclick: () => unlock(d) }, "Buka kunci"));
  } else {
    actions.append(el("button", { class: "small danger", onclick: () => openLock(d) }, "Kunci"));
  }
  if (st !== "released") {
    actions.append(el("button", { class: "small", onclick: () => release(d) }, "Lepas"));
  } else {
    actions.append(el("button", { class: "small", onclick: () => reenroll(d) }, "Daftarkan lagi"));
  }
  return el("tr", {},
    el("td", {}, el("div", { class: "mono" }, d.serial), d.imei ? el("div", { class: "sub mono" }, "IMEI " + d.imei) : null),
    el("td", {}, el("span", { class: "badge " + st }, STATUS_LABEL[st])),
    el("td", {},
      d.locked && d.message ? el("div", {}, d.message) : null,
      d.locked && d.contact ? el("div", { class: "sub" }, "Kontak: " + d.contact) : null,
      d.note ? el("div", { class: "sub" }, "Catatan: " + d.note) : null,
      d.updated_by ? el("div", { class: "sub" }, "Diubah " + relTime(d.updated_at) + " oleh " + d.updated_by) : null),
    el("td", { title: fmtDate(d.last_seen) }, relTime(d.last_seen)),
    el("td", { class: "sub", title: d.fingerprint || "" }, d.build_utc ? fmtDate(d.build_utc) : "-"),
    el("td", {}, actions));
}

$("filter").addEventListener("input", renderDevices);
$("status-filter").addEventListener("change", renderDevices);

let lockTarget = null;
function openLock(d) {
  lockTarget = d;
  $("lock-serial").textContent = "Serial: " + d.serial;
  $("lock-message").value = d.message || "Cicilan Anda belum dibayar. Silakan hubungi kami untuk membuka kunci.";
  $("lock-contact").value = d.contact || "";
  $("lock-dialog").returnValue = "";
  $("lock-dialog").showModal();
}
$("lock-dialog").addEventListener("close", () => {
  if ($("lock-dialog").returnValue !== "ok" || !lockTarget) return;
  const d = lockTarget;
  run(async () => {
    await api("PUT", "/admin/api/devices/" + encodeURIComponent(d.serial), {
      locked: true, message: $("lock-message").value.trim(), contact: $("lock-contact").value.trim(),
    });
    await loadDevices();
  }, "Perangkat " + d.serial + " dikunci. Berlaku saat perangkat sinkron berikutnya (maks. ~15 menit).");
});

function unlock(d) {
  run(async () => {
    await api("PUT", "/admin/api/devices/" + encodeURIComponent(d.serial), { locked: false });
    await loadDevices();
  }, "Kunci " + d.serial + " dibuka.");
}

async function release(d) {
  const ok = await confirmDialog("Lepas perangkat?",
    "Perangkat " + d.serial + " akan dilepas: semua kunci dan pembatasan dicabut (mis. cicilan lunas).", "Lepas");
  if (!ok) return;
  run(async () => {
    await api("DELETE", "/admin/api/devices/" + encodeURIComponent(d.serial));
    await loadDevices();
  }, "Perangkat " + d.serial + " dilepas.");
}

function reenroll(d) {
  run(async () => {
    await api("PUT", "/admin/api/devices/" + encodeURIComponent(d.serial), { enrolled: true, locked: false });
    await loadDevices();
  }, "Perangkat " + d.serial + " didaftarkan lagi.");
}

$("add-device").addEventListener("click", () => {
  $("add-serial").value = "";
  $("add-note").value = "";
  $("add-dialog").returnValue = "";
  $("add-dialog").showModal();
});
$("add-dialog").addEventListener("close", () => {
  if ($("add-dialog").returnValue !== "ok") return;
  const serial = $("add-serial").value.trim();
  if (!serial) return;
  run(async () => {
    await api("PUT", "/admin/api/devices/" + encodeURIComponent(serial),
      { enrolled: true, locked: false, note: $("add-note").value.trim() });
    await loadDevices();
  }, "Perangkat " + serial + " didaftarkan.");
});

// ------------------------------------------------------------ rilis OTA
async function loadReleases() {
  await run(async () => {
    const data = await api("GET", "/admin/api/releases");
    const rels = Object.values(data).sort((a, b) => (b.build_utc || 0) - (a.build_utc || 0));
    $("releases-body").replaceChildren(...rels.map(releaseRow));
    labelCells($("releases-body"));
    $("releases-empty").hidden = rels.length > 0;
  });
}

function releaseRow(r) {
  const input = el("input", { type: "number", min: "0", max: "100", value: String(r.rollout ?? 100),
                              "aria-label": "Rollout persen untuk " + r.id });
  const save = el("button", { class: "small", onclick: () => run(async () => {
    await api("PATCH", "/admin/api/releases/" + encodeURIComponent(r.id), { rollout: Number(input.value) });
    await loadReleases();
  }, "Rollout " + r.version + " diubah ke " + input.value + "%.") }, "Simpan");
  const toggle = el("button", { class: "small" + (r.paused ? "" : " danger"), onclick: () => run(async () => {
    await api("PATCH", "/admin/api/releases/" + encodeURIComponent(r.id), { paused: !r.paused });
    await loadReleases();
  }, r.paused ? "Rilis " + r.version + " dilanjutkan." : "Rilis " + r.version + " dijeda.") },
    r.paused ? "Lanjutkan" : "Jeda");
  return el("tr", {},
    el("td", {}, el("strong", {}, r.version), el("div", { class: "sub mono" }, r.id)),
    el("td", {}, r.device, el("div", { class: "sub" }, r.channel || "stable")),
    el("td", {}, r.pre_fingerprint ? "Inkremental" : "Full"),
    el("td", {}, fmtDate(r.build_utc)),
    el("td", {}, fmtSize(r.file_size || r.size)),
    el("td", {}, el("div", { class: "rollout" }, input, el("span", {}, "%"), save)),
    el("td", {}, el("div", { class: "actions" },
      el("span", { class: "badge " + (r.paused ? "paused" : "active") }, r.paused ? "Dijeda" : "Aktif"), toggle)));
}

// ------------------------------------------------------------ audit
const ACTION_LABEL = {
  "device.update": "Ubah perangkat", "device.release": "Lepas perangkat",
  "release.create": "Rilis baru", "release.update": "Ubah rilis",
};
async function loadAudit() {
  await run(async () => {
    const data = await api("GET", "/admin/api/audit?limit=300");
    $("audit-body").replaceChildren(...data.entries.map((a) => el("tr", {},
      el("td", { class: "sub" }, fmtDate(a.ts)),
      el("td", {}, a.admin),
      el("td", {}, ACTION_LABEL[a.action] || a.action),
      el("td", { class: "mono" }, a.target),
      el("td", { class: "mono sub" }, Object.keys(a.data || {}).length ? JSON.stringify(a.data) : ""),
      el("td", { class: "sub mono" }, a.ip))));
    labelCells($("audit-body"));
    $("audit-empty").hidden = data.entries.length > 0;
  });
}

// ------------------------------------------------------------ mulai
if (getToken()) start(); else showLogin();
