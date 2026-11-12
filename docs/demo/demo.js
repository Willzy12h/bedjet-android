/* Nocturne interactive demo.
 * An HTML imitation of the Android app's main screens, driven by a simulated BedJet and a demo
 * clock. It is not the app: nothing here talks to Bluetooth, and screens can lag behind the app. */
(() => {
'use strict';

/* ---------------- Constants ---------------- */
const DAY = 1440;
const CLOCK_MIN = 18 * 60, CLOCK_MAX = 36 * 60;          // Monday 18:00 to Tuesday 12:00
const DAYS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const TODAY = new Date(2026, 10, 9);                         // Monday 9 November 2026

const MODES = {
  turbo: { label: 'Turbo', short: 'Turbo', min: 43, max: 43 },
  heat: { label: 'Heat', short: 'Heat', min: 19, max: 40 },
  ext: { label: 'Long heat', short: 'Long', min: 19, max: 33.5 },
  cool: { label: 'Cool', short: 'Cool', min: 19, max: 31 },
  dry: { label: 'Dry', short: 'Dry', min: 19, max: 31 },
  wait: { label: 'Pause', short: 'Pause' },
  off: { label: 'Off', short: 'Off' },
};
const HEAT_BOUNDS = [30, 34, 38];

const PALETTES = {
  midnight: { name: 'Midnight Ink', ground: '#0B0D18', glow: '#1A1F3A', accent: '#B48CFF', sheet: '#171A2C', menu: '#232740', onPrimary: '#12152A' },
  forest: { name: 'Forest', ground: '#08120E', glow: '#183428', accent: '#7FDCA6', sheet: '#0F1F18', menu: '#1B3027', onPrimary: '#08120E' },
  graphite: { name: 'Graphite', ground: '#000000', glow: '#18191D', accent: '#DCDFE6', sheet: '#111214', menu: '#1F2024', onPrimary: '#000000' },
  plum: { name: 'Plum', ground: '#120A18', glow: '#33204A', accent: '#D6A6FF', sheet: '#1C1324', menu: '#2B1E38', onPrimary: '#120A18' },
  rust: { name: 'Autumn Rust', ground: '#160D09', glow: '#4A2614', accent: '#FFB35C', sheet: '#21140E', menu: '#301F16', onPrimary: '#160D09' },
  storm: { name: 'Winter Storm', ground: '#0A1016', glow: '#1E3346', accent: '#8FD3FF', sheet: '#121B24', menu: '#1D2A37', onPrimary: '#0A1016' },
};
const DARK_INK = { ink: '#F1F3F9', ink2: '#BCC2D4', ink3: '#858CA2', glass: 'rgba(255,255,255,.075)', glass2: 'rgba(255,255,255,.12)', hair: 'rgba(255,255,255,.07)', cool: '#4FB3FF' };
const OVERCAST = { ground: '#CED2D8', glow: '#E2E5E9', accent: '#1F7CC2', primary: '#2F93DC', onPrimary: '#FFFFFF', sheet: '#E6E9ED', menu: '#F2F3F5',
  ink: '#14171D', ink2: '#3B4250', ink3: '#646C7B', glass: 'rgba(255,255,255,.55)', glass2: 'rgba(0,0,0,.08)', hair: 'rgba(0,0,0,.08)', cool: '#2B8EE0' };

const WAKE_BUTTONS = {
  snoozeWarm: 'Snooze warm 10m', snoozeCool: 'Snooze cool 10m', warm30: 'Warm 30m', cool30: 'Cool 30m', off: 'Off',
};

const TRY = [
  ['start', 'Start a night from Tonight'],
  ['timelapse', 'Press ▶ on Demo time and watch the night run to your alarm'],
  ['checkin', 'Answer the morning check-in'],
  ['editstep', 'Open a profile and change a step'],
  ['remote', 'Change the mode on Remote'],
  ['sleep', 'Open a night on the Sleep page'],
  ['palette', 'Pick another palette in Settings → Appearance'],
  ['widget', 'Use a widget on the home screen'],
];

/* ---------------- Helpers ---------------- */
const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const clone = (o) => JSON.parse(JSON.stringify(o));
const sum = (a, f) => a.reduce((t, x) => t + f(x), 0);
const pad = (n) => String(n).padStart(2, '0');
const tod = (m) => ((Math.round(m) % DAY) + DAY) % DAY;
const hhmm = (m) => { const t = tod(m); return `${pad(Math.floor(t / 60))}:${pad(t % 60)}`; };
const nightAbs = (t) => (t < 12 * 60 ? t + DAY : t);
const round5 = (m) => Math.round(m / 5) * 5;
const ceil5 = (m) => Math.ceil(m / 5) * 5;
function dur(m, long = false) {
  m = Math.round(m);
  const h = Math.floor(m / 60), r = m % 60;
  if (h === 0) return `${r} min`;
  if (r === 0) return `${h} h`;
  return long ? `${h} h ${r} min` : `${h} h ${pad(r)}`;
}
function temp(c, settings = S.settings) {
  if (c == null) return '';
  if (settings.fahrenheit) return `${Math.round(c * 9 / 5 + 32)}°F`;
  return `${Number.isInteger(c) ? c : c.toFixed(1)}°C`;
}
function modeColor(mode, t) {
  if (mode === 'turbo') return 'var(--heat4)';
  if (mode === 'heat' || mode === 'ext') return `var(--heat${1 + HEAT_BOUNDS.filter((b) => b <= t).length})`;
  if (mode === 'cool') return 'var(--cool)';
  if (mode === 'dry') return 'var(--dry)';
  return 'var(--off)';
}
function stepName(s) {
  if (s.mode === 'wait' || s.mode === 'off') return MODES[s.mode].label;
  return `${MODES[s.mode].label} ${temp(s.temp)}`;
}
function rng(seed) {
  return () => { seed |= 0; seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
function dateLabel(offsetDays) {
  const d = new Date(TODAY); d.setDate(d.getDate() + offsetDays);
  return `${DAY_NAMES[(d.getDay() + 6) % 7]} ${d.getDate()} ${MONTHS[d.getMonth()]}`;
}
const storage = {
  get(k) { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* storage blocked */ } },
};

/* ---------------- Profiles and plans ---------------- */
const step = (mode, t, fan, d) => ({ mode, temp: t, fan, dur: d });
const PRESETS = {
  summer: { name: 'Summer Cool', season: 'summer', bed: 22 * 60 + 30, wake: 7 * 60, wakeWithAlarm: true,
    start: [step('cool', 22, 70, 30)], middle: { mode: 'cool', temp: 22, tempTo: 22, fanFrom: 50, fanTo: 25, steps: 3 },
    wakeUp: [step('heat', 30, 30, 20)], after: [step('cool', 22, 40, 20)] },
  winter: { name: 'Winter Warm', season: 'winter', bed: 22 * 60 + 30, wake: 7 * 60, wakeWithAlarm: true,
    start: [step('turbo', 43, 100, 10), step('heat', 35, 50, 30)], middle: { mode: 'ext', temp: 29, tempTo: 27, fanFrom: 30, fanTo: 20, steps: 3 },
    wakeUp: [step('heat', 34, 40, 30)], after: [step('heat', 32, 30, 30)] },
  spring: { name: 'Spring/Autumn', season: 'spring', bed: 22 * 60 + 30, wake: 7 * 60, wakeWithAlarm: true,
    start: [step('heat', 32, 40, 20)], middle: { mode: 'cool', temp: 22, tempTo: 22, fanFrom: 35, fanTo: 20, steps: 3 },
    wakeUp: [step('heat', 32, 35, 25)], after: [] },
  hot: { name: 'Hot Sleeper', season: 'summer', bed: 23 * 60, wake: 7 * 60, wakeWithAlarm: true,
    start: [step('cool', 22, 100, 45)], middle: { mode: 'cool', temp: 22, tempTo: 22, fanFrom: 50, fanTo: 45, steps: 2 },
    wakeUp: [step('cool', 22, 30, 20)], after: [step('cool', 22, 40, 30)] },
  warmcool: { name: 'Warm Start, Cool Night', season: 'any', bed: 22 * 60 + 30, wake: 6 * 60 + 45, wakeWithAlarm: true,
    start: [step('turbo', 43, 100, 10), step('heat', 34, 40, 20)], middle: { mode: 'cool', temp: 22, tempTo: 22, fanFrom: 40, fanTo: 20, steps: 3 },
    wakeUp: [step('heat', 33, 35, 30)], after: [] },
};
function makeProfile(key, id, extra = {}) {
  return Object.assign({ id, fav: false, days: [1, 1, 1, 1, 1, 1, 1], limits: { fanMin: 5, fanMax: 100, tMin: 19, tMax: 43 }, repeatTurbo: false },
    clone(PRESETS[key]), extra);
}

function compile(p, bed, wake, extras = []) {
  const segs = [];
  let t = bed;
  p.start.forEach((s, i) => { segs.push({ ...s, a: t, b: t + s.dur, phase: 'start', i }); t += s.dur; });
  const W = sum(p.wakeUp, (s) => s.dur);
  const ws = Math.max(t, wake - W);
  const m = p.middle, n = Math.max(1, m.steps), len = ws - t;
  if (len > 0) {
    for (let k = 0; k < n; k++) {
      const f = n === 1 ? 0 : k / (n - 1);
      segs.push({ mode: m.mode, temp: Math.round((m.temp + (m.tempTo - m.temp) * f) * 2) / 2,
        fan: Math.round((m.fanFrom + (m.fanTo - m.fanFrom) * f) / 5) * 5,
        a: t + Math.round(len * k / n), b: t + Math.round(len * (k + 1) / n), phase: 'middle', i: k });
    }
  }
  t = ws;
  p.wakeUp.forEach((s, i) => { segs.push({ ...s, a: t, b: t + s.dur, phase: 'wakeUp', i }); t += s.dur; });
  p.after.forEach((s, i) => { segs.push({ ...s, a: t, b: t + s.dur, phase: 'after', i }); t += s.dur; });
  extras.forEach((s) => { segs.push({ ...s, a: t, b: t + s.dur, phase: 'extra', i: 0 }); t += s.dur; });
  return segs;
}
const planEnd = (segs) => (segs.length ? segs[segs.length - 1].b : 0);
const wakeAt = (segs) => { const w = segs.filter((s) => s.phase === 'wakeUp'); return w.length ? w[w.length - 1].b : null; };

/* ---------------- Sleep history ---------------- */
function makeStages(r, bed, wake) {
  const out = []; let t = Math.min(wake, bed + 8 + Math.floor(r() * 18)); let wakes = 0;
  if (t > bed) out.push({ s: 'awake', a: bed, b: t });
  let cycle = 0;
  while (t < wake - 20) {
    const deep = cycle < 2 ? 25 + r() * 25 : 5 + r() * 12;
    const rem = 10 + cycle * 6 + r() * 10;
    const light = 35 + r() * 20;
    for (const [s, d] of [['light', light * .5], ['deep', deep], ['light', light * .5], ['rem', rem]]) {
      const b = Math.min(wake - 5, t + d); if (b > t) out.push({ s, a: t, b }); t = b;
    }
    if (r() < .35 && t < wake - 40) { const b = t + 4 + r() * 8; out.push({ s: 'awake', a: t, b }); t = b; wakes++; }
    cycle++;
  }
  if (wake > t) out.push({ s: 'awake', a: t, b: wake });
  const asleep = sum(out.filter((x) => x.s !== 'awake'), (x) => x.b - x.a);
  return { stages: out, asleep, wakes };
}
function makeHistory() {
  const r = rng(20261109), nights = [];
  const feels = [null, 'right', 'right', 'right', 'hot', 'cold'];
  const prof = makeProfile('summer', 'x');
  for (let k = 1; k <= 91; k++) {
    const bed = 22 * 60 + 25 + Math.floor(r() * 70), wake = DAY + 6 * 60 + 40 + Math.floor(r() * 40);
    const st = makeStages(r, bed, wake);
    nights.push({ id: `n${k}`, date: dateLabel(-k), bed, wake, ...st, feel: feels[Math.floor(r() * feels.length)],
      profile: 'Summer Cool', segs: compile(prof, bed, wake - 20 + 20, []) });
  }
  nights[0].wakes = 1;
  return nights;
}

/* ---------------- State ---------------- */
let S;
function freshState() {
  const profiles = [makeProfile('summer', 'p1', { fav: true }), makeProfile('winter', 'p2'), makeProfile('warmcool', 'p3', { days: [1, 1, 1, 1, 1, 0, 0] })];
  return {
    clock: 22 * 60 + 10, playing: false, speed: 5, lastTick: 0, dragging: false,
    air: 22.5, room: 19.5,
    bedjet: { mode: 'off', temp: 22, fan: 50, endsAt: null, then: null },
    remote: { mode: 'cool', temp: 22, fan: 50, runFor: 60 },
    link: 'ok', outOfRange: false,
    profiles, drafts: {}, tonightProfile: 'p1', tonightWake: null,
    night: null, nights: makeHistory(),
    presets: [
      { id: 'turbo', mode: 'turbo', temp: 43, fan: 100, dur: 10 },
      { id: 'heat', mode: 'heat', temp: 32, fan: 50, dur: 30 },
      { id: 'cool', mode: 'cool', temp: 22, fan: 50, dur: 720 },
      { id: 'dry', mode: 'dry', temp: 28, fan: 50, dur: 30 },
      { id: 'boost' }, { id: 'off' },
    ],
    editPresets: false,
    boost: { heatMin: 5, temp: 32 },
    setup: { bt: true, notif: true, alarms: false, alarmapp: false, battery: false },
    settings: {
      lights: true, beeps: false, slot: 3, theme: 'dark', palette: 'midnight', fahrenheit: false,
      alarmApps: ['samsung', 'google'], alarmFrom: 'samsung', alarm: null, reminder: false,
      autoStart: { on: false, mode: 'fixed', at: 22 * 60 + 30, before: 8 * 60 + 30, charging: true, firedFor: null },
      wakeButtons: ['snoozeWarm', 'warm30', 'off'], quietStatus: false,
      autoBackup: true, hcRead: true, hcWrite: false,
    },
    backups: [{ when: 'Sun 8 Nov, 03:00', kind: 'Automatic' }, { when: 'Sat 7 Nov, 03:00', kind: 'Automatic' }],
    sleepRange: 7, sleepSel: 0,
    notif: null, toast: null, home: false,
  };
}

/* ---------------- Navigation ----------------
 * A route is { tab, paths: { tab: [page, ...] }, overlay }. Every navigation pushes a browser
 * history entry, so the phone's back arrow and the browser's back/forward both work. */
const NAV = { list: [], i: -1, push: true };
let R;
const ROOT = () => ({ tab: 'tonight', paths: { tonight: [{ p: 'home' }], remote: [{ p: 'home' }], profiles: [{ p: 'home' }], settings: [{ p: 'home' }] }, overlay: null, home: false });
const curPage = () => { const st = R.paths[R.tab]; return st[st.length - 1]; };
function hashOf(r) {
  if (r.home) return '#home';
  const page = r.paths[r.tab][r.paths[r.tab].length - 1];
  return `#${r.tab}${page.p !== 'home' ? '/' + page.p : ''}${r.overlay ? '/' + r.overlay.k : ''}`;
}
function commit(next, anim, replace = false) {
  const prevKey = overlayKey();
  R = next;
  if (replace) NAV.list[NAV.i] = clone(R);
  else { NAV.list = NAV.list.slice(0, NAV.i + 1); NAV.list.push(clone(R)); NAV.i++; }
  if (NAV.push) {
    try { (replace ? history.replaceState : history.pushState).call(history, { demo: NAV.i }, '', hashOf(R)); } catch { NAV.push = false; }
  }
  render(anim, prevKey !== overlayKey());
}
function nav(fn, anim = 'fwd', replace = false) { const n = clone(R); fn(n); commit(n, anim, replace); }
function push(page) { nav((n) => { n.overlay = null; n.paths[n.tab].push(page); }, 'fwd'); }
function open(overlay) { nav((n) => { n.overlay = overlay; }, null); }
function swapOverlay(overlay) { nav((n) => { n.overlay = overlay; }, null, true); }
function back() {
  if (NAV.i <= 0) {
    // Nothing to go back to in history: close what is open or pop the page directly.
    if (R.overlay) return nav((n) => { n.overlay = null; }, null, true);
    if (R.paths[R.tab].length > 1) return nav((n) => { n.paths[n.tab].pop(); }, 'back', true);
    return;
  }
  if (NAV.push) history.back(); else goIndex(NAV.i - 1);
}
function goIndex(i) {
  const target = NAV.list[i];
  if (!target) return;
  const dir = i < NAV.i ? 'back' : 'fwd';
  const prevKey = overlayKey();
  const prevPage = JSON.stringify([R.tab, curPage(), R.home]);
  NAV.i = i; R = clone(target);
  const pg = curPage();
  if (pg && pg.p === 'editor' && !S.drafts[pg.id]) {
    const saved = S.profiles.find((p) => p.id === pg.id);
    if (saved) S.drafts[pg.id] = { p: clone(saved), dirty: false };
  }
  const samePage = prevPage === JSON.stringify([R.tab, curPage(), R.home]);
  render(samePage ? null : dir, prevKey !== overlayKey());
}
window.addEventListener('popstate', (e) => {
  const i = e.state && typeof e.state.demo === 'number' ? e.state.demo : 0;
  if (!NAV.list[i]) return;
  goIndex(i);
});
const overlayKey = () => (R && R.overlay ? JSON.stringify(R.overlay) : '');

/* ---------------- Toasts, notifications, checklist ---------------- */
let toastTimer, notifTimer;
function toast(msg) {
  S.toast = msg; renderToast();
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { S.toast = null; renderToast(); }, 2600);
}
function notify(n, ms = 9000) {
  S.notif = n; renderNotif();
  clearTimeout(notifTimer); notifTimer = setTimeout(() => { S.notif = null; renderNotif(); }, ms);
}
let tried = new Set(storage.get('nocturne-demo-tried') || []);
function tick(k) {
  if (tried.has(k)) return;
  tried.add(k); storage.set('nocturne-demo-tried', [...tried]); renderTry();
}

/* ---------------- BedJet simulation ---------------- */
let sendEpoch = 0; // Reset bumps this so a send still in flight is dropped.
function send(label, apply, quiet = false) {
  if (S.outOfRange) { toast("Can't reach the BedJet. Nothing was sent."); return false; }
  S.link = 'sending'; render();
  const epoch = sendEpoch;
  setTimeout(() => { if (epoch !== sendEpoch) return; apply(); S.link = 'ok'; render(); if (!quiet) toast(label); }, 420);
  return true;
}
function runPreset(pr) {
  if (pr.id === 'off') return send('BedJet off', () => { S.bedjet = { ...S.bedjet, mode: 'off', endsAt: null, then: null, queue: null }; stopNight(); });
  if (pr.id === 'boost') {
    return send(`Boost: Turbo, then Heat for ${S.boost.heatMin} min`, () => {
      stopNight();
      S.bedjet = { mode: 'turbo', temp: 43, fan: 100, endsAt: S.clock + 10, then: S.boost.heatMin ? { mode: 'heat', temp: S.boost.temp, fan: 50, dur: S.boost.heatMin } : null };
    });
  }
  return send(`${stepName(pr)} for ${dur(pr.dur)}`, () => {
    stopNight();
    S.bedjet = { mode: pr.mode, temp: pr.temp, fan: pr.fan, endsAt: S.clock + pr.dur, then: null };
  });
}
function segAt(segs, t) { return segs.find((s) => t >= s.a && t < s.b) || null; }
function nightSegs() {
  const n = S.night; if (!n) return [];
  let segs = compile(n.profile, n.bed, n.wake, n.extras);
  const z = n.snooze;
  if (z) {
    // A snooze runs its own step over whatever the plan had for those minutes.
    segs = segs.flatMap((s) => {
      if (s.b <= z.a || s.a >= z.b) return [s];
      const out = [];
      if (s.a < z.a) out.push({ ...s, b: z.a });
      if (s.b > z.b) out.push({ ...s, a: z.b });
      return out;
    });
    segs.push({ ...z, phase: 'snooze', i: 0 });
    segs.sort((x, y) => x.a - y.a);
  }
  return segs;
}

function simulateMinute() {
  const bj = S.bedjet;
  if (S.night) {
    const segs = nightSegs();
    const s = segAt(segs, S.clock);
    if (s) Object.assign(bj, { mode: s.mode === 'wait' ? 'off' : s.mode, temp: s.temp ?? bj.temp, fan: s.fan ?? bj.fan, endsAt: s.b, then: null });
    const ws = segs.find((x) => x.phase === 'wakeUp');
    if (ws && !S.night.notified && S.clock >= ws.a) { S.night.notified = true; wakeNotification(); }
    if (S.clock >= planEnd(segs)) finishNight();
  } else if (bj.endsAt != null && S.clock >= bj.endsAt) {
    if (bj.queue && bj.queue.length) { const [nx, ...more] = bj.queue; S.bedjet = { ...nx, then: null, queue: more }; }
    else if (bj.then) { const t = bj.then; S.bedjet = { mode: t.mode, temp: t.temp, fan: t.fan, endsAt: S.clock + t.dur, then: null }; }
    else S.bedjet = { ...bj, mode: 'off', endsAt: null, queue: null };
  }
  // Air from the BedJet drifts toward what it is doing.
  const m = S.bedjet.mode;
  const target = m === 'off' ? S.room + 1.5 : m === 'turbo' ? 41 : m === 'cool' ? Math.max(S.room + .5, S.bedjet.temp) : m === 'dry' ? S.room + 3 : S.bedjet.temp;
  S.air += (target - S.air) * .18;
  // Auto-start and bedtime reminder.
  const a = S.settings.autoStart;
  if (a.on && !S.night && a.firedFor !== 'mon') {
    const startAt = a.mode === 'fixed' ? nightAbs(a.at) : (wakeFor(tonightProfile()).at - a.before);
    if (S.clock >= startAt && S.clock < startAt + 30) { a.firedFor = 'mon'; startNight(true); }
  }
  if (S.settings.reminder && !S.night && !S.reminded) {
    const bed = nightAbs(tonightProfile().bed);
    if (S.clock >= bed - 30 && S.clock < bed) {
      S.reminded = true;
      notify({ title: `Bedtime at ${hhmm(bed)}`, text: `${tonightProfile().name} is ready for tonight.`, acts: [{ l: 'Start night now', a: 'startNight' }] });
    }
  }
}
function wakeNotification() {
  const segs = nightSegs(); const w = segs.filter((s) => s.phase === 'wakeUp');
  if (!w.length) return;
  notify({
    title: `Waking you at ${hhmm(w[w.length - 1].b)}`,
    text: `${stepName(w[0])} · fan ${w[0].fan}% until your alarm`,
    acts: S.settings.wakeButtons.map((k) => ({ l: WAKE_BUTTONS[k], a: 'wakeAct', v: k })),
  }, 12000);
}

/* ---------------- Night lifecycle ---------------- */
const ALARM_APPS = {
  samsung: { name: 'Samsung Clock', clock: true, icon: '#1E6FD9' },
  google: { name: 'Google Clock', clock: true, icon: '#34A853' },
  whitenoise: { name: 'White Noise', clock: false, icon: '#7C5CD6', hint: 'Has set an alarm on this phone' },
};
const alarmCounts = () => S.settings.alarm != null && S.settings.alarmApps.includes(S.settings.alarmFrom);
const tonightProfile = () => S.profiles.find((p) => p.id === S.tonightProfile) || S.profiles[0];
function wakeFor(p) {
  if (S.tonightWake != null) return { at: nightAbs(S.tonightWake), src: 'Tonight only' };
  if (p.wakeWithAlarm && alarmCounts())
    return { at: nightAbs(S.settings.alarm), src: `${ALARM_APPS[S.settings.alarmFrom].name} alarm` };
  return { at: nightAbs(p.wake), src: S.night ? "Profile's wake time" : 'Profile fallback' };
}
function startNight(auto = false) {
  const p = tonightProfile();
  const go = () => {
    const w = wakeFor(p);
    S.night = { profile: clone(p), bed: Math.round(S.clock), wake: w.at, src: w.src === 'Profile fallback' ? "Profile's wake time" : w.src, extras: [], notified: false, startAir: S.air };
    simulateMinute();
    tick('start');
  };
  if (S.outOfRange) { toast("Can't reach the BedJet. Nothing was sent."); return; }
  if (auto) { go(); toast('Auto-start: tonight is on the BedJet'); return; }
  send(auto ? 'Auto-start: tonight is on the BedJet' : `Tonight is on the BedJet in slot ${S.settings.slot}`, go);
  if (!auto) setTimeout(() => { if (S.night && !S.playing) toast('Press ▶ on Demo time to run the night'); }, 3100);
}
function rewriteNight(msg) {
  if (!S.night) return;
  const w = wakeFor(S.night.profile);
  send(msg, () => { if (!S.night) return; S.night.wake = w.at; S.night.src = w.src; simulateMinute(); });
}
function stopNight() { if (S.night) { S.night = null; } }
function finishNight(early = false) {
  const n = S.night; if (!n) return;
  const segs = nightSegs();
  const wake = Math.max(n.bed, early ? S.clock : (wakeAt(segs) ?? n.wake));
  const r = rng(Math.round(n.bed * 7 + wake));
  const st = makeStages(r, n.bed, wake);
  S.nights.unshift({ id: `n${Date.now()}`, date: dateLabel(0), bed: n.bed, wake, ...st, feel: null, profile: n.profile.name, segs, fresh: true });
  S.sleepSel = 0;
  S.night = null;
  S.bedjet = { ...S.bedjet, mode: 'off', endsAt: null, then: null, queue: null };
  S.playing = false;
  S.notif = null; renderNotif();
  tick('timelapse');
  if (R.home) nav((x) => { x.home = false; }, 'fade');
  nav((x) => { x.tab = 'tonight'; x.overlay = { k: 'checkin' }; }, null);
}

/* ---------------- Rendering: shared bits ---------------- */
function planBar(segs, opts = {}) {
  if (!segs.length) return '';
  const a0 = segs[0].a, total = planEnd(segs) - a0;
  const parts = segs.map((s) => `<div class="seg-b" title="${esc(stepName(s))} ${hhmm(s.a)}–${hhmm(s.b)}" style="flex:${s.b - s.a} 1 0;background:${modeColor(s.mode, s.temp)}"></div>`).join('');
  const now = opts.now != null && opts.now >= a0 && opts.now <= a0 + total
    ? `<div class="now" style="left:calc(${((opts.now - a0) / total) * 100}% - 1.5px)" aria-label="Now"></div>` : '';
  let labels = '';
  if (opts.labels) {
    const pct = (t) => clamp(((t - a0) / total) * 100, 0, 100);
    const merged = [];
    opts.labels.forEach(([t, k, v]) => {
      const prev = merged[merged.length - 1];
      if (prev && pct(t) - pct(prev[0]) < 22 && merged.length > 1) { prev[0] = t; prev[1] = `${prev[1]} · ${k}`; prev[2] = `${prev[2]} · ${v}`; }
      else if (prev && pct(t) - pct(prev[0]) < 22) merged.push([t, k, v]);
      else merged.push([t, k, v]);
    });
    labels = `<div class="plan-labels">${merged.map(([t, k, v], i) => {
      const p = pct(t), al = i === 0 ? 'left:0' : p > 75 ? `right:${100 - p}%;text-align:right` : `left:${p}%;transform:translateX(-50%);text-align:center`;
      return `<span style="${al}"><b>${esc(k)}</b>${esc(v)}</span>`; }).join('')}</div>`;
  }
  return `<div class="planbar${opts.thin ? ' thin' : ''}" role="img" aria-label="Night plan">${parts}${now}</div>${labels}`;
}
function legend(segs) {
  const seen = new Map();
  segs.forEach((s) => { const k = stepName(s); if (!seen.has(k)) seen.set(k, modeColor(s.mode, s.temp)); });
  return `<div class="legend">${[...seen].slice(0, 6).map(([k, c]) => `<span><i style="background:${c}"></i>${esc(k)}</span>`).join('')}</div>`;
}
const ICON = {
  moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/>',
  remote: '<path d="M5 4v16M12 4v16M19 4v16"/><circle cx="5" cy="9" r="2.2"/><circle cx="12" cy="15" r="2.2"/><circle cx="19" cy="7" r="2.2"/>',
  list: '<rect x="4" y="4" width="16" height="6" rx="2"/><rect x="4" y="14" width="16" height="6" rx="2"/>',
  gear: '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
  bt: '<path d="M7 7l10 10-5 4V3l5 4L7 17"/>',
  bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
  palette: '<circle cx="12" cy="12" r="9"/><circle cx="8" cy="10" r="1.3"/><circle cx="12" cy="7.5" r="1.3"/><circle cx="16" cy="10" r="1.3"/>',
  shield: '<path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"/>',
  box: '<path d="M4 7l8-4 8 4v10l-8 4-8-4z"/><path d="M4 7l8 4 8-4M12 11v10"/>',
  sleep: '<path d="M4 18h16M4 18V8M20 18v-5a3 3 0 0 0-3-3h-7v8"/><circle cx="7" cy="12" r="1.6"/>',
  heart: '<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.5"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
};
const svg = (k) => `<svg viewBox="0 0 24 24" aria-hidden="true">${ICON[k]}</svg>`;
const sw = (on, a, v = '', label = '') => `<button class="switch${on ? ' on' : ''}" role="switch" aria-checked="${on}" aria-label="${esc(label)}" data-a="${a}" data-v="${esc(v)}"></button>`;
function slider(key, label, val, min, max, stepv, fmt, opts = {}) {
  const pct = ((val - min) / (max - min)) * 100;
  return `<div class="slider-wrap" data-fmt="${fmt}">
    <div class="slider-head"><span class="small muted">${esc(label)}</span><span class="v">${FMT[fmt](val)}</span></div>
    <input class="slider" type="range" aria-label="${esc(label)}" data-in="${key}" min="${min}" max="${max}" step="${stepv}" value="${val}" style="--pct:${pct}%;${opts.fill ? `--fill:${opts.fill}` : ''}" ${opts.disabled ? 'disabled' : ''}>
    ${opts.ends ? `<div class="slider-ends"><span>${opts.ends[0]}</span><span>${opts.ends[1]}</span></div>` : ''}
  </div>`;
}
const FMT = {
  temp: (v) => temp(Number(v)), fan: (v) => `${v}%`, min: (v) => (Number(v) === 0 ? 'None' : dur(Number(v))), time: (v) => hhmm(Number(v)), plain: (v) => String(v),
};
const topbar = (backLabel, right = '') => `<div class="topbar"><button class="back" data-a="back">‹ ${esc(backLabel)}</button>${right}</div>`;

/* ---------------- Pages ---------------- */
const PAGES = {};

PAGES.tonight_home = () => (S.night ? nightView() : tonightIdle());

function tonightIdle() {
  const p = tonightProfile();
  const w = wakeFor(p);
  const bed = Math.max(nightAbs(p.bed), ceil5(S.clock));
  const segs = compile(p, bed, w.at);
  const bj = S.bedjet;
  const state = S.link === 'sending' ? 'Sending to the BedJet…' : bj.mode === 'off' ? 'Idle' : `${stepName(bj)} · fan ${bj.fan}%${bj.endsAt ? ` · until ${hhmm(bj.endsAt)}` : ''}`;
  const alarmLine = !S.settings.alarmApps.length
    ? `Alarm app not chosen — profile wake ${hhmm(p.wake)}`
    : S.settings.alarm == null ? `No alarm set — profile wake ${hhmm(p.wake)}`
    : !alarmCounts() ? `Alarm ${hhmm(S.settings.alarm)} ignored: from ${ALARM_APPS[S.settings.alarmFrom].name}, not one of your alarm apps — choose in Settings`
    : `Next alarm ${hhmm(S.settings.alarm)} · ${ALARM_APPS[S.settings.alarmFrom].name}`;
  const setupLeft = Object.entries(S.setup).filter(([, v]) => !v);
  const last = S.nights[0];
  const week = S.nights.slice(0, 7);
  return `
  <div class="hero">
    <div class="kicker">Air from the BedJet</div>
    <div class="row between"><div class="big-temp">${temp(Math.round(S.air * 2) / 2)}</div>
      <span class="state"><span class="dot${bj.mode !== 'off' ? ' live' : ''}"></span>${esc(state)}</span></div>
  </div>
  <div class="card" style="margin-top:14px">
    <div class="row between"><span class="kicker">Tonight · ${esc(p.name)}</span><button class="btn ghost" data-a="openOverlay" data-v="pickProfile">Change</button></div>
    <div style="margin-top:8px">${planBar(segs, { labels: [[segs.length ? segs[0].a : bed, 'Bed', hhmm(bed)], (() => { const mm = segs.filter((s) => s.phase === 'middle'); return mm.length ? [(mm[0].a + mm[mm.length - 1].b) / 2, stepName(mm[0]), 'through the night'] : [segs.length ? segs[0].a : bed, '', '']; })(), [wakeAt(segs) ?? w.at, 'Wake', hhmm(w.at)]] })}</div>
    <div class="small faint" style="margin-top:2px">${esc(w.src)} · ${esc(alarmLine)}</div>
    <button class="btn primary block" style="margin-top:14px" data-a="startNight">Start night now</button>
    <button class="btn tonal block" style="margin-top:8px" data-a="openOverlay" data-v="wake">${S.tonightWake != null ? `Tonight-only wake ${hhmm(S.tonightWake)} · change` : 'Set tonight-only wake'}</button>
  </div>
  <div class="h-sec">Quick presets <button data-a="toggleEditPresets">${S.editPresets ? 'Done' : 'Edit'}</button></div>
  ${S.editPresets ? '<p class="small faint" style="margin:-4px 2px 8px">Tap a preset to change it.</p>' : ''}
  <div class="tiles">${S.presets.map(presetTile).join('')}</div>
  ${setupLeft.length ? `<div class="h-sec">Finish setting up <span class="faint" style="letter-spacing:0;text-transform:none">${5 - setupLeft.length} of 5 done</span></div>
  <div class="card tight">${setupLeft.map(([k]) => setupRow(k)).join('')}</div>` : ''}
  <div class="h-sec">Last night</div>
  <button class="card" style="display:block;width:100%" data-a="openSleep" data-v="0">
    <div class="row between"><span class="kicker">${esc(last.date)}</span>${feelPill(last.feel)}</div>
    <div style="font-weight:700;font-size:18px;margin:6px 0 8px">${dur(last.asleep, true)} asleep · woke ${last.wakes} time${last.wakes === 1 ? '' : 's'}</div>
    ${stageBar(last)}
    <div class="row between small" style="margin-top:10px"><span class="faint">This week average ${dur(sum(week, (n) => n.asleep) / week.length, true)}</span><span class="link">Sleep ›</span></div>
  </button>`;
}
function presetTile(pr) {
  const sub = { turbo: `${dur(pr.dur)}`, boost: `Turbo + Heat ${S.boost.heatMin}m`, off: 'Stop the BedJet' }[pr.id] ?? `${temp(pr.temp)} · ${dur(pr.dur)}`;
  const name = { boost: 'Boost', off: 'Off' }[pr.id] ?? MODES[pr.mode].label;
  const c = pr.id === 'boost' ? 'var(--heat3)' : pr.id === 'off' ? 'var(--off)' : modeColor(pr.mode, pr.temp);
  const on = !S.night && ((pr.id === 'off' && S.bedjet.mode === 'off') || (pr.mode && S.bedjet.mode === pr.mode && S.bedjet.temp === pr.temp));
  return `<button class="tile${on ? ' on' : ''}" style="--c:${c}" data-a="preset" data-v="${pr.id}">
    ${S.editPresets ? '<span class="badge pill">Edit</span>' : ''}<div class="t">${name}</div><div class="s">${esc(sub)}</div></button>`;
}
function setupRow(k) {
  const t = { bt: ['Bluetooth', 'Find and talk to your BedJet'], notif: ['Notifications', 'Night status and wake-up buttons'], alarms: ['Alarms and reminders', 'Start and change the night on time'],
    alarmapp: ['Your alarm app', 'Samsung Clock or Google Clock'], battery: ['Battery use', 'Set to Unrestricted so overnight changes arrive'] }[k];
  return `<button class="setup-item" data-a="setup" data-v="${k}"><span class="n">!</span><span class="grow"><b>${t[0]}</b><br><span class="small faint">${t[1]}</span></span><span class="link small">Allow</span></button>`;
}
function feelPill(f) {
  if (!f) return '';
  return { right: '<span class="pill good">Just right</span>', hot: '<span class="pill bad">Too hot</span>', cold: '<span class="pill" style="color:var(--cool2)">Too cold</span>' }[f];
}
function stageBar(n) {
  const total = Math.max(1, n.wake - n.bed);
  return `<div class="stagebar" role="img" aria-label="Sleep stages">${n.stages.map((s) => `<i class="stage-${s.s}" style="flex:${(s.b - s.a) / total} 1 0"></i>`).join('')}</div>`;
}

function nightView() {
  const n = S.night, segs = nightSegs();
  if (!segs.length) return `<div class="night-hero"><span class="state small muted">Night running · ${esc(n.profile.name)}</span><div class="step">Nothing planned</div><div class="muted">This profile has no steps, so the BedJet stays off.</div></div>
  <div class="btn-row"><button class="btn danger" data-a="openOverlay" data-v="endNight">End night</button></div>`;
  const cur = segAt(segs, S.clock) || segs[0];
  const idx = segs.indexOf(cur);
  const next = segs[idx + 1];
  const wake = wakeAt(segs) ?? n.wake, end = planEnd(segs);
  const leftToWake = wake - S.clock;
  const phaseName = { start: 'Start', middle: 'Through the night', wakeUp: 'Wake-up', after: 'After waking', extra: 'Extra time', snooze: 'Snooze' }[cur.phase];
  return `
  <div class="night-hero">
    <span class="state small muted" style="display:inline-flex;gap:8px;align-items:center"><span class="dot live"></span>Night running · ${esc(n.profile.name)}</span>
    <div class="step">${esc(stepName(cur))}</div>
    <div class="muted">Fan ${cur.fan}% · step ${idx + 1} of ${segs.length} · ${phaseName}</div>
  </div>
  <div class="card" style="margin-top:14px">
    ${planBar(segs, { now: S.clock, labels: [[segs[0].a, 'Bed', hhmm(n.bed)], [wake, 'Wake', hhmm(wake)], [end, 'Off', hhmm(end)]] })}
    <div class="small muted" style="margin-top:4px">${next ? `Next: ${esc(stepName(next))} at ${hhmm(next.a)}` : `BedJet turns off at ${hhmm(end)}`}</div>
    ${legend(segs)}
  </div>
  <div class="ring-row">
    <div class="stat"><div class="k">Air</div><div class="v">${temp(Math.round(S.air * 2) / 2)}</div></div>
    <div class="stat"><div class="k">Wake</div><div class="v">${hhmm(wake)}</div></div>
    <div class="stat"><div class="k">${leftToWake > 0 ? 'Until wake' : 'Woke'}</div><div class="v">${leftToWake > 0 ? dur(leftToWake) : hhmm(wake)}</div></div>
  </div>
  <div class="small faint" style="margin:2px 2px 0">${esc(n.src)}${n.extras.length ? ` · plus ${dur(sum(n.extras, (e) => e.dur))} extra` : ''}</div>
  <div class="btn-row"><button class="btn tonal" data-a="openOverlay" data-v="wake">Change wake</button><button class="btn danger" data-a="openOverlay" data-v="endNight">End night</button></div>
  <button class="btn ghost block" style="margin-top:6px" data-a="menu" data-v="nightMore">More</button>
  <div class="card tight" style="margin-top:12px">
    <div class="kicker">Tonight on the BedJet</div>
    ${segs.map((s, i) => `<div class="row small" style="padding:7px 0;border-bottom:1px solid var(--hair);${i === idx ? 'font-weight:700' : 'color:var(--ink2)'}">
      <i style="width:10px;height:10px;border-radius:3px;background:${modeColor(s.mode, s.temp)}"></i>
      <span style="width:92px">${hhmm(s.a)}–${hhmm(s.b)}</span><span class="grow">${esc(stepName(s))}${s.fan != null && s.mode !== 'wait' ? ` · fan ${s.fan}%` : ''}</span>${i === idx ? '<span class="pill accent">Now</span>' : ''}</div>`).join('')}
    <p class="tiny faint" style="margin:10px 0 0">The BedJet runs this on its own from programme slot ${S.settings.slot}. Your phone can sleep.</p>
  </div>`;
}

PAGES.sleep = () => {
  const rng7 = S.sleepRange;
  const list = S.nights.slice(0, rng7 === 90 ? 91 : rng7);
  let bars;
  if (rng7 === 90) {
    bars = []; for (let w = 0; w < 13; w++) { const wk = S.nights.slice(w * 7, w * 7 + 7); bars.push({ v: sum(wk, (n) => n.asleep) / wk.length, d: `${w === 0 ? 'Now' : `-${w}w`}`, i: w * 7 }); }
  } else bars = list.map((n, i) => ({ v: n.asleep, d: rng7 === 7 ? n.date.slice(0, 2) : (i % 5 === 0 ? n.date.split(' ')[1] : ''), i }));
  bars.reverse();
  const avg = sum(list, (n) => n.asleep) / list.length;
  const sel = S.nights[S.sleepSel] || S.nights[0];
  const h = (v) => clamp((v - 240) / (560 - 240), .06, 1) * 100;
  return `${topbar(R.tab === 'settings' ? 'Settings' : 'Tonight')}
  <h1 class="h-page">Sleep</h1>
  <div class="seg" role="tablist">${[[7, '7 days'], [30, '30 days'], [90, '3 months']].map(([v, l]) => `<button class="${rng7 === v ? 'on' : ''}" data-a="sleepRange" data-v="${v}">${l}</button>`).join('')}</div>
  <div class="card" style="margin-top:12px">
    <div class="kicker">Average asleep</div><div style="font:700 28px Bricolage,Figtree;margin-top:4px">${dur(avg, true)}</div>
    <div class="bars" style="gap:${rng7 === 30 ? 2 : 6}px">${bars.map((b) => `<button class="${S.sleepSel === b.i || (rng7 === 90 && S.sleepSel >= b.i && S.sleepSel < b.i + 7) ? 'on' : ''}" data-a="sleepSel" data-v="${b.i}" aria-label="${dur(b.v, true)}"><div class="b" style="height:${h(b.v)}%"></div><span class="d">${b.d}</span></button>`).join('')}</div>
  </div>
  ${sleepDay(sel)}
  <div class="h-sec">Nights</div>
  <div class="list">${list.slice(0, 14).map((n, i) => `<button class="listrow" data-a="sleepSel" data-v="${i}">
    <span class="grow"><span class="t">${esc(n.date)}</span><br><span class="s">${dur(n.asleep, true)} asleep · ${hhmm(n.bed)} → ${hhmm(n.wake)}</span></span>${feelPill(n.feel) || (n.wakes > 2 ? '<span class="pill warn">1 issue</span>' : '')}</button>`).join('')}</div>`;
};
function sleepDay(n) {
  const total = Math.max(1, n.wake - n.bed);
  const lane = (k) => n.stages.filter((s) => s.s === k).map((s) => `<i class="stage-${k}" style="left:${((s.a - n.bed) / total) * 100}%;width:${((s.b - s.a) / total) * 100}%"></i>`).join('');
  const bj = n.segs.filter((s) => s.a < n.wake).map((s) => `<i style="left:${clamp((s.a - n.bed) / total, 0, 1) * 100}%;width:${clamp((Math.min(s.b, n.wake) - Math.max(s.a, n.bed)) / total, 0, 1) * 100}%;background:${modeColor(s.mode, s.temp)}"></i>`).join('');
  const st = (k) => sum(n.stages.filter((s) => s.s === k), (s) => s.b - s.a);
  const week = S.nights.slice(1, 8); const avg = sum(week, (x) => x.asleep) / week.length;
  const diff = Math.round(n.asleep - avg);
  return `<div class="card">
    <div class="row between"><div><div class="kicker">${esc(n.date)}${n.fresh ? ' · just now' : ''}</div><div style="font-weight:700;font-size:18px;margin-top:4px">${dur(n.asleep, true)} asleep</div></div>${feelPill(n.feel)}</div>
    <div class="small faint" style="margin:2px 0 12px">${hhmm(n.bed)} → ${hhmm(n.wake)} · ${esc(n.profile)}</div>
    ${S.settings.hcRead ? `<div class="lanes">
      <span>Awake</span><div class="lane">${lane('awake')}</div>
      <span>REM</span><div class="lane">${lane('rem')}</div>
      <span>Light</span><div class="lane">${lane('light')}</div>
      <span>Deep</span><div class="lane">${lane('deep')}</div>
      <span>BedJet</span><div class="lane">${bj}</div>
    </div>
    <div class="slider-ends" style="margin:6px 0 0 60px"><span>${hhmm(n.bed)}</span><span>${hhmm(n.bed + total / 2)}</span><span>${hhmm(n.wake)}</span></div>`
    : `<div class="lanes"><span>BedJet</span><div class="lane">${bj}</div></div><p class="small faint">Turn on Health Connect in Settings to see sleep stages next to the BedJet.</p>`}
    <div class="figs" style="margin-top:12px">
      <div class="stat"><div class="k">In bed</div><div class="v">${dur(total)}</div></div>
      <div class="stat"><div class="k">Woke</div><div class="v">${n.wakes} time${n.wakes === 1 ? '' : 's'}</div></div>
      ${S.settings.hcRead ? `<div class="stat"><div class="k">Deep</div><div class="v">${dur(st('deep'))}</div></div><div class="stat"><div class="k">REM</div><div class="v">${dur(st('rem'))}</div></div>` : ''}
    </div>
    <p class="small muted" style="margin:10px 0 0">${diff >= 0 ? `${dur(diff)} more` : `${dur(-diff)} less`} than your 7-night average of ${dur(avg, true)}.</p>
  </div>`;
}

PAGES.remote_home = () => {
  const bj = S.bedjet, rm = S.remote, on = bj.mode !== 'off';
  const md = MODES[rm.mode];
  const line = S.link === 'sending' ? 'Sending…' : on
    ? `Target ${temp(bj.temp)} · fan ${bj.fan}%${bj.endsAt ? ` · ${dur(Math.max(0, bj.endsAt - S.clock))} left, until ${hhmm(bj.endsAt)}` : ''}` : 'The BedJet is off.';
  return `<h1 class="h-page">Remote</h1>
  <div class="card">
    <div class="row between"><span class="kicker">BedJet now</span><span class="pill${on ? ' accent' : ''}">${MODES[bj.mode].label}</span></div>
    <div class="row" style="gap:24px;margin-top:8px">
      <div><div class="small faint">Air</div><div style="font:700 30px Bricolage,Figtree">${temp(Math.round(S.air * 2) / 2)}</div></div>
      <div><div class="small faint">Room</div><div style="font:700 30px Bricolage,Figtree">${temp(S.room)}</div></div>
    </div>
    <div class="small muted" style="margin-top:6px">${esc(line)}</div>
    ${S.night ? '<p class="tiny faint" style="margin:8px 0 0">A night is running. Changing the BedJet here stops the night plan.</p>' : ''}
  </div>
  <div class="h-sec">Mode</div>
  <div class="modes">${['turbo', 'heat', 'ext', 'cool', 'dry', 'off'].map((m) => {
    const c = m === 'off' ? 'var(--off)' : modeColor(m, m === 'heat' ? 34 : m === 'ext' ? 30 : 22);
    const sub = m === 'off' ? 'Stop' : m === 'turbo' ? '43°C · 10 min' : `max ${temp(MODES[m].max)}`;
    return `<button class="mode${bj.mode === m && (m !== 'off' || !on) ? ' on' : ''}" style="--c:${c}" data-a="remoteMode" data-v="${m}"><div class="t">${MODES[m].label}</div><div class="s">${sub}</div></button>`; }).join('')}</div>
  <div class="card" style="margin-top:12px">
    ${slider('remoteTemp', 'Temperature', rm.mode === 'turbo' ? 43 : clamp(rm.temp, md.min || 19, md.max || 31), md.min && md.min !== 43 ? md.min : 19, md.max && md.max !== 43 ? md.max : 31, .5, 'temp', { disabled: rm.mode === 'turbo', ends: [temp(md.min && md.min !== 43 ? md.min : 19), temp(md.max && md.max !== 43 ? md.max : 31)], fill: modeColor(rm.mode, rm.temp) })}
    <div class="divider"></div>
    ${slider('remoteFan', 'Fan', rm.fan, 5, 100, 5, 'fan', { ends: ['5%', '100%'] })}
    <p class="tiny faint" style="margin:4px 0 0">The lowest running fan speed is 5%.</p>
  </div>
  <div class="h-sec">Run for</div>
  <div class="chips">${[[15, '15 min'], [30, '30 min'], [60, '1 h'], [120, '2 h'], [240, '4 h'], [480, '8 h'], [720, 'Max (12 h)']].map(([v, l]) => `<button class="chip${rm.runFor === v ? ' on' : ''}" data-a="runFor" data-v="${v}" ${rm.mode === 'turbo' && v > 10 ? 'disabled style="opacity:.4"' : ''}>${l}</button>`).join('')}
    <button class="chip${![15, 30, 60, 120, 240, 480, 720].includes(rm.runFor) ? ' on' : ''}" data-a="openOverlay" data-v="customRun" ${rm.mode === 'turbo' ? 'disabled style="opacity:.4"' : ''}>${![15, 30, 60, 120, 240, 480, 720].includes(rm.runFor) ? dur(rm.runFor) : 'Custom…'}</button></div>
  <button class="tile big" style="--c:var(--heat3);width:100%;margin-top:16px" data-a="preset" data-v="boost"><div class="t">Boost</div><div class="s">Turbo for 10 min, then Heat for ${S.boost.heatMin} min</div></button>`;
};

PAGES.profiles_home = () => `
  <div class="row between" style="margin-top:8px"><h1 class="h-page" style="margin:0">Profiles</h1>
    <div class="row" style="gap:6px"><button class="btn tonal" style="min-height:38px;padding:0 14px" data-a="importProfile">Import</button><button class="btn primary" style="min-height:38px;padding:0 14px" data-a="openOverlay" data-v="newProfile">+ New</button></div></div>
  <p class="small faint" style="margin:8px 2px 14px">Tap a profile to edit it. ★ marks the one used each night.</p>
  ${S.profiles.map((p, i) => {
    const d = S.drafts[p.id];
    const segs = compile(p, nightAbs(p.bed), nightAbs(p.wake));
    const tonight = S.tonightProfile === p.id;
    return `<div class="card profile-card">
      <div class="row">
        <button class="star${p.fav ? ' on' : ''}" aria-label="${p.fav ? 'Default profile' : 'Make default'}" data-a="fav" data-v="${p.id}">${p.fav ? '★' : '☆'}</button>
        <button class="grow" style="text-align:left" data-a="editProfile" data-v="${p.id}"><span class="name">${esc(p.name)}</span>${d && d.dirty ? ' <span class="pill warn">Unsaved</span>' : ''}</button>
        ${tonight ? '<span class="pill accent">Tonight</span>' : ''}
        <button class="more" aria-label="More for ${esc(p.name)}" data-a="menu" data-v="profile:${p.id}:${i}">⋯</button>
      </div>
      <button style="display:block;width:100%;margin-top:10px" data-a="editProfile" data-v="${p.id}">
        ${planBar(segs, { thin: true })}
        <div class="row between small" style="margin-top:8px"><span class="muted">${hhmm(p.bed)} → ${hhmm(p.wake)}${p.wakeWithAlarm ? ' · or your alarm' : ''}</span>${tonight ? `<span class="faint">${segs.length} steps</span>` : ''}</div>
        <div class="days">${DAYS.map((x, k) => `<span class="${p.days[k] ? 'on' : ''}">${x}</span>`).join('')}</div>
      </button>
      ${tonight ? '' : `<button class="chip" style="position:absolute;right:14px;bottom:44px;min-height:30px;font-size:13px" data-a="useTonight" data-v="${p.id}">Use tonight</button>`}
    </div>`; }).join('')}`;

PAGES.editor = (pg) => {
  const d = S.drafts[pg.id]; if (!d) return '<p>Profile not found.</p>';
  const p = d.p;
  const segs = compile(p, nightAbs(p.bed), nightAbs(p.wake));
  const mid = segs.filter((s) => s.phase === 'middle');
  const midLen = mid.length ? mid[mid.length - 1].b - mid[0].a : 0;
  const wakeLen = sum(p.wakeUp, (s) => s.dur), afterLen = sum(p.after, (s) => s.dur), startLen = sum(p.start, (s) => s.dur);
  const stepRow = (s, phase, i) => {
    const seg = segs.find((x) => x.phase === phase && x.i === i);
    return `<button class="steprow" style="--c:${modeColor(s.mode, s.temp)}" data-a="openStep" data-v="${phase}:${i}">
      <span class="grow"><span class="t">${esc(stepName(s))}</span><br><span class="s">${s.mode === 'wait' ? '' : `Fan ${s.fan}% · `}${dur(s.dur)}${seg ? ` · ${hhmm(seg.a)}–${hhmm(seg.b)}` : ''}</span></span><span class="faint">›</span></button>`;
  };
  const phase = (key, title, len, steps) => `<div class="phase-head"><span class="kicker">${title} <b>${len ? dur(len).toUpperCase() : ''}</b></span></div>
    ${steps.map((s, i) => stepRow(s, key, i)).join('')}<button class="add-step" data-a="openOverlay" data-v="addStep:${key}">+ Add a step</button>`;
  const m = p.middle;
  return `${topbar('Profiles', `<button class="btn primary" style="min-height:38px" data-a="saveProfile">Save</button>`)}
  <h1 class="h-page" style="margin-top:2px">${esc(p.name || 'New profile')}</h1>
  <div class="card">${planBar(segs, { labels: [[segs.length ? segs[0].a : nightAbs(p.bed), 'Bed', hhmm(p.bed)], [wakeAt(segs) ?? nightAbs(p.wake), 'Wake', hhmm(p.wake)], [planEnd(segs), 'Off', hhmm(planEnd(segs))]] })}${legend(segs)}</div>
  <div class="h-sec">Timing</div>
  <div class="card">
    <div class="row between"><span>Usual bedtime</span><div class="row" style="gap:6px"><button class="chip" aria-label="Earlier bedtime" data-a="pTime" data-v="bed:-15">−</button><b style="width:52px;text-align:center">${hhmm(p.bed)}</b><button class="chip" aria-label="Later bedtime" data-a="pTime" data-v="bed:15">+</button></div></div>
    <div class="divider"></div>
    <div class="row between"><span class="grow">Wake with my alarm<br><span class="small faint">Uses the next alarm in your clock app</span></span>${sw(p.wakeWithAlarm, 'pToggle', 'wakeWithAlarm', 'Wake with my alarm')}</div>
    <div class="divider"></div>
    <div class="row between"><span>${p.wakeWithAlarm ? 'Wake time with no alarm' : 'Wake time'}</span><div class="row" style="gap:6px"><button class="chip" aria-label="Earlier wake" data-a="pTime" data-v="wake:-15">−</button><b style="width:52px;text-align:center">${hhmm(p.wake)}</b><button class="chip" aria-label="Later wake" data-a="pTime" data-v="wake:15">+</button></div></div>
    <div class="divider"></div>
    <div class="small muted" style="margin-bottom:8px">Nights it's used</div>
    <div class="days edit">${DAYS.map((x, k) => `<span role="button" tabindex="0" class="${p.days[k] ? 'on' : ''}" data-a="pDay" data-v="${k}">${x}</span>`).join('')}</div>
  </div>
  ${phase('start', 'Start', startLen, p.start)}
  <div class="phase-head"><span class="kicker">Through the night <b>${midLen ? dur(midLen).toUpperCase() : ''}</b></span></div>
  <button class="steprow" style="--c:${modeColor(m.mode, m.temp)}" data-a="openOverlay" data-v="middle">
    <span class="grow"><span class="t">${MODES[m.mode].label} ${temp(m.temp)}${m.tempTo !== m.temp ? ` → ${temp(m.tempTo)}` : ''}</span><br><span class="s">Fan ${m.fanFrom}% → ${m.fanTo}% · ${m.steps} step${m.steps === 1 ? '' : 's'} · stretches to fit</span></span><span class="faint">›</span></button>
  ${phase('wakeUp', 'Wake up', wakeLen, p.wakeUp)}
  ${phase('after', 'After waking', afterLen, p.after)}
  <div class="h-sec">Name</div>
  <input class="field" data-in="pName" value="${esc(p.name)}" aria-label="Profile name" maxlength="40">
  <div class="chips" style="margin-top:10px">${[['any', 'Any time'], ['summer', 'Summer'], ['winter', 'Winter'], ['spring', 'Spring/autumn']].map(([k, l]) => `<button class="chip${p.season === k ? ' on' : ''}" data-a="pSeason" data-v="${k}">${l}</button>`).join('')}</div>
  <div class="h-sec">Limits</div>
  <div class="card">
    <p class="small faint" style="margin:0 0 8px">The whole night stays inside these.</p>
    ${slider('limFanMax', 'Highest fan', p.limits.fanMax, 20, 100, 5, 'fan')}
    ${slider('limTMax', 'Highest temperature', p.limits.tMax, 25, 43, .5, 'temp')}
    <button class="btn ghost" style="margin-top:4px" data-a="resetLimits">Reset limits</button>
  </div>
  <div class="h-sec">Turbo</div>
  <div class="card row between"><span class="grow">Repeat Turbo past 10 minutes<br><span class="small faint">Turbo runs 10 minutes at a time on the BedJet</span></span>${sw(p.repeatTurbo, 'pToggle', 'repeatTurbo', 'Repeat Turbo')}</div>
  ${d.isNew ? '' : '<button class="btn danger block" style="margin-top:6px" data-a="deleteProfileAsk">Delete profile</button>'}`;
};

PAGES.settings_home = () => {
  const allowed = ['bt', 'notif', 'alarms', 'battery'].filter((k) => S.setup[k]).length;
  const rows = [
    ['device', 'bt', 'BedJet 3', `${S.outOfRange ? 'Out of range' : 'Demo BedJet'} · slot ${S.settings.slot}`],
    ['nightwake', 'clock', 'Night and wake-up', S.settings.autoStart.on ? 'Auto-start on' : 'Alarm, reminders, auto-start'],
    ['appearance', 'palette', 'Appearance', `${{ dark: 'Dark', light: 'Light', system: 'System' }[S.settings.theme]} · ${PALETTES[S.settings.palette].name}`],
    ['permissions', 'shield', 'Permissions', `${allowed} of 4 allowed`],
    ['backups', 'box', 'Profiles and backups', `${S.backups.length} snapshots`],
    ['notifications', 'bell', 'Notifications', `${S.settings.wakeButtons.length} wake-up buttons`],
    ['sleep', 'sleep', 'Sleep', `${S.nights.length} nights recorded`],
    ['health', 'heart', 'Health Connect', S.settings.hcRead || S.settings.hcWrite ? 'On' : 'Off'],
    ['about', 'info', 'Help and about', 'Version 1.1.0-beta.3'],
  ];
  return `<h1 class="h-page">Settings</h1><div class="list">${rows.map(([k, ic, t, s]) => `<button class="listrow" data-a="settingsPage" data-v="${k}"><span class="ic">${svg(ic)}</span><span class="grow"><span class="t">${t}</span><br><span class="s">${esc(s)}</span></span><span class="chev">›</span></button>`).join('')}</div>`;
};
const setRow = (t, sub, right) => `<div class="listrow" style="cursor:default"><span class="grow"><span class="t">${t}</span>${sub ? `<br><span class="s">${sub}</span>` : ''}</span>${right}</div>`;

PAGES.device = () => `${topbar('Settings')}<h1 class="h-page">BedJet 3</h1>
  <div class="card"><div class="row"><span class="dot${S.outOfRange ? '' : ' live'}"></span><b class="grow">${S.outOfRange ? 'Out of range' : 'Demo BedJet · connected'}</b></div>
  <p class="small faint" style="margin:8px 0 0">Commands connect first and only count as done when the BedJet reports back.</p></div>
  <div class="list">
    ${setRow('Lights', 'The BedJet’s display and buttons', sw(S.settings.lights, 'setBool', 'lights', 'Lights'))}
    ${setRow('Beeps', 'Sounds when settings change', sw(S.settings.beeps, 'setBool', 'beeps', 'Beeps'))}
    ${setRow('Pretend it is out of range', 'Demo only: see what happens when the BedJet can’t be reached', sw(S.outOfRange, 'toggleRange', '', 'Out of range'))}
  </div>
  <div class="h-sec">Programme slot</div>
  <div class="card"><p class="small faint" style="margin:0 0 10px">Tonight's plan is written to one BedJet programme slot. What was in it is saved the first time.</p>
  <div class="seg">${[1, 2, 3].map((k) => `<button class="${S.settings.slot === k ? 'on' : ''}" data-a="slot" data-v="${k}">Slot ${k}</button>`).join('')}</div>
  <button class="btn ghost" style="margin-top:8px" data-a="toastOnly" data-v="Slot ${S.settings.slot} put back to what it held before Nocturne">Put back the original slot ${S.settings.slot}</button></div>`;

const appRow = (k) => {
  const a = ALARM_APPS[k], on = S.settings.alarmApps.includes(k);
  return `<button class="listrow" role="checkbox" aria-checked="${on}" data-a="toggleAlarmApp" data-v="${k}" style="text-align:left">
    <span class="appicon" style="background:${a.icon}" aria-hidden="true">${a.name[0]}</span>
    <span class="grow"><span class="t">${a.name}</span>${a.hint ? `<br><span class="s">${a.hint}</span>` : ''}</span>
    <span class="tickbox${on ? ' on' : ''}" aria-hidden="true"></span></button>`;
};
PAGES.nightwake = () => {
  const st = S.settings, a = st.autoStart;
  return `${topbar('Settings')}<h1 class="h-page">Night and wake-up</h1>
  <div class="h-sec">Alarm apps</div>
  <p class="small faint" style="margin:0 4px 8px">Tick the apps whose alarms set your wake-up. Alarms from apps you haven’t ticked, such as calendars and reminders, are never used.</p>
  <div class="kicker" style="margin:0 4px 6px">Clock apps</div>
  <div class="list">${Object.entries(ALARM_APPS).filter(([, a]) => a.clock).map(([k]) => appRow(k)).join('')}</div>
  <div class="kicker" style="margin:12px 4px 6px">Other apps that set alarms</div>
  <div class="list">${Object.entries(ALARM_APPS).filter(([, a]) => !a.clock).map(([k]) => appRow(k)).join('')}</div>
  <div class="card" style="margin-top:12px"><p class="small faint" style="margin:0 0 8px">Demo alarm set by:</p>
  <div class="seg">${Object.entries(ALARM_APPS).map(([k, a]) => `<button class="${st.alarmFrom === k ? 'on' : ''}" data-a="alarmFrom" data-v="${k}">${a.name}</button>`).join('')}</div>
  <p class="small faint" style="margin:10px 0 8px">Demo alarm time (moving it during a night rewrites the plan):</p>
  <div class="chips">${[[null, 'No alarm'], [6 * 60 + 30, '06:30'], [6 * 60 + 45, '06:45'], [7 * 60 + 15, '07:15']].map(([v, l]) => `<button class="chip${st.alarm === v ? ' on' : ''}" data-a="setAlarm" data-v="${v == null ? '' : v}">${l}</button>`).join('')}</div></div>
  <div class="list">
    ${setRow('Bedtime reminder', '30 minutes before your profile’s bedtime', sw(st.reminder, 'setBool', 'reminder', 'Bedtime reminder'))}
  </div>
  <div class="h-sec">Auto-start</div>
  <div class="list">
    ${setRow('Start the night by itself', a.on ? (a.mode === 'fixed' ? `At ${hhmm(a.at)}` : `${dur(a.before)} before your alarm`) : 'Off', sw(a.on, 'autoOn', '', 'Auto-start'))}
    ${a.on ? `<div class="listrow" style="display:block"><div class="seg">${[['fixed', 'At a fixed time'], ['before', 'Before my alarm']].map(([k, l]) => `<button class="${a.mode === k ? 'on' : ''}" data-a="autoMode" data-v="${k}">${l}</button>`).join('')}</div>
      <div class="row between" style="margin-top:10px"><span class="small muted">${a.mode === 'fixed' ? 'Time' : 'How long before'}</span><div class="row" style="gap:6px"><button class="chip" data-a="autoTime" data-v="-15" aria-label="Earlier">−</button><b style="min-width:64px;text-align:center">${a.mode === 'fixed' ? hhmm(a.at) : dur(a.before)}</b><button class="chip" data-a="autoTime" data-v="15" aria-label="Later">+</button></div></div></div>
    ${setRow('Only while charging', '', sw(a.charging, 'autoCharging', '', 'Only while charging'))}` : ''}
  </div>`;
};

PAGES.appearance = () => {
  const st = S.settings;
  return `${topbar('Settings')}<h1 class="h-page">Appearance</h1>
  <div class="card"><div class="kicker" style="margin-bottom:8px">Theme</div><div class="seg">${[['system', 'System'], ['dark', 'Dark'], ['light', 'Light']].map(([k, l]) => `<button class="${st.theme === k ? 'on' : ''}" data-a="theme" data-v="${k}">${l}</button>`).join('')}</div>
  <p class="tiny faint" style="margin:8px 0 0">In the demo, System follows the demo clock: dark from 19:00 to 07:00.</p></div>
  <div class="h-sec">Night palette</div>
  ${Object.entries(PALETTES).map(([k, p]) => `<button class="choice${st.palette === k ? ' on' : ''}" data-a="palette" data-v="${k}">
    <span class="sw" style="background:linear-gradient(${p.glow},${p.ground})"></span><span class="sw" style="background:${p.accent};width:8px"></span><b class="grow">${p.name}</b>${st.palette === k ? '<span class="pill accent">In use</span>' : ''}</button>`).join('')}
  <div class="h-sec">Day palette</div>
  <div class="choice on"><span class="sw" style="background:linear-gradient(#E2E5E9,#CED2D8)"></span><span class="sw" style="background:#1F7CC2;width:8px"></span><b class="grow">Overcast</b></div>
  <div class="list" style="margin-top:12px">${setRow('Show °F', 'Temperatures in Fahrenheit', sw(st.fahrenheit, 'setBool', 'fahrenheit', 'Show Fahrenheit'))}</div>`;
};

PAGES.permissions = () => `${topbar('Settings')}<h1 class="h-page">Permissions</h1>
  <div class="list">${[['bt', 'Nearby devices', 'Find and talk to your BedJet'], ['notif', 'Notifications', 'Night status and wake-up buttons'], ['alarms', 'Alarms and reminders', 'Start and change the night on time'], ['battery', 'Unrestricted battery', 'Lets overnight alarm changes reach the BedJet']]
    .map(([k, t, s]) => setRow(t, s, S.setup[k] ? '<span class="pill good">Allowed</span>' : `<button class="btn tonal" style="min-height:36px" data-a="setup" data-v="${k}">Allow</button>`)).join('')}</div>
  <p class="small faint" style="margin:4px 2px">There is no internet permission. Everything stays on the phone.</p>`;

PAGES.backups = () => `${topbar('Settings')}<h1 class="h-page">Profiles and backups</h1>
  <div class="list">${setRow('Automatic backups', 'A snapshot each night at 03:00', sw(S.settings.autoBackup, 'setBool', 'autoBackup', 'Automatic backups'))}</div>
  <div class="btn-row"><button class="btn primary" data-a="backupNow">Back up now</button><button class="btn tonal" data-a="toastOnly" data-v="On a phone this saves a file where you choose">Export…</button></div>
  <button class="btn ghost block" style="margin-top:6px" data-a="toastOnly" data-v="On a phone this opens a backup file you choose">Import a backup…</button>
  <div class="h-sec">Snapshots</div>
  <div class="list">${S.backups.map((b) => setRow(esc(b.when), `${b.kind} · ${S.profiles.length} profiles`, '<button class="btn ghost" data-a="toastOnly" data-v="Restored (demo)">Restore</button>')).join('')}</div>`;

PAGES.notifications = () => `${topbar('Settings')}<h1 class="h-page">Notifications</h1>
  <div class="card"><div class="kicker">Wake-up buttons</div><p class="small faint" style="margin:6px 0 10px">Choose up to three. They appear on the wake-up notification.</p>
  <div class="chips">${Object.entries(WAKE_BUTTONS).map(([k, l]) => `<button class="chip${S.settings.wakeButtons.includes(k) ? ' on' : ''}" data-a="wakeBtn" data-v="${k}">${S.settings.wakeButtons.includes(k) ? '✓ ' : ''}${l}</button>`).join('')}</div>
  <button class="btn tonal block" style="margin-top:12px" data-a="previewWake">Preview the wake-up notification</button></div>
  <div class="list">${setRow('Quiet night status', 'Keep the night notification silent and minimised', sw(S.settings.quietStatus, 'setBool', 'quietStatus', 'Quiet night status'))}</div>`;

PAGES.health = () => `${topbar('Settings')}<h1 class="h-page">Health Connect</h1>
  <p class="small faint" style="margin:0 2px 12px">Optional, and off by default in the app. Turned on here so the demo Sleep page has stages to show.</p>
  <div class="list">
    ${setRow('Show sleep stages', 'Read sleep from Health Connect for the Sleep page', sw(S.settings.hcRead, 'setBool', 'hcRead', 'Show sleep stages'))}
    ${setRow('Record BedJet nights', 'Write completed programmes to Health Connect', sw(S.settings.hcWrite, 'setBool', 'hcWrite', 'Record nights'))}
  </div>`;

PAGES.about = () => `${topbar('Settings')}<h1 class="h-page">Help and about</h1>
  <div class="card"><div style="font:700 22px Bricolage,Figtree">Nocturne</div><div class="small muted">Version 1.1.0-beta.3</div>
  <p class="small faint">A free, open-source app for the BedJet 3. Not affiliated with or endorsed by BedJet LLC.</p>
  <p class="small" style="color:var(--warn);margin-bottom:0">You're using the HTML demo. It imitates the app's main screens with a pretend BedJet.</p></div>
  <div class="list">
    <a class="listrow" style="color:inherit;text-decoration:none" href="https://github.com/Willzy12h/bedjet-android/releases/latest" target="_blank" rel="noopener"><span class="grow"><span class="t">Check for a newer build</span><br><span class="s">Opens the latest release in your browser to compare with 1.1.0-beta.3 and download the APK</span></span><span class="chev">↗</span></a>
    <a class="listrow" style="color:inherit;text-decoration:none" href="https://github.com/Willzy12h/bedjet-android/blob/main/PRIVACY.md" target="_blank" rel="noopener"><span class="grow"><span class="t">Privacy</span><br><span class="s">No servers, accounts or analytics</span></span><span class="chev">↗</span></a>
    <a class="listrow" style="color:inherit;text-decoration:none" href="https://github.com/Willzy12h/bedjet-android#readme" target="_blank" rel="noopener"><span class="grow"><span class="t">Project page</span><br><span class="s">Source, licence and credits</span></span><span class="chev">↗</span></a>
  </div>`;

/* ---------------- Overlays ---------------- */
const OVERLAYS = {};
const sheet = (body) => `<div class="sheet" role="dialog" aria-modal="true"><div class="grab"></div>${body}</div>`;
const dialog = (body) => `<div class="dialog" role="alertdialog" aria-modal="true">${body}</div>`;

OVERLAYS.pickProfile = () => sheet(`<h2>Tonight's profile</h2><p class="small faint" style="margin:0 0 12px">Only changes tonight. ★ in Profiles sets the usual one.</p>
  ${S.profiles.map((p) => `<button class="choice${S.tonightProfile === p.id ? ' on' : ''}" data-a="pickProfile" data-v="${p.id}"><span class="grow"><b>${esc(p.name)}</b><br><span class="small faint">${hhmm(p.bed)} → ${hhmm(p.wake)}</span></span>${p.fav ? '★' : ''}</button>`).join('')}`);

OVERLAYS.wake = () => {
  const p = S.night ? S.night.profile : tonightProfile();
  const cur = S.tonightWake ?? tod(wakeFor(p).at);
  return sheet(`<h2>${S.night ? 'Change wake' : 'Tonight-only wake'}</h2><p class="small faint" style="margin:0 0 14px">Only for tonight. ${S.night ? 'The plan on the BedJet is rewritten.' : 'Your profile and alarm stay as they are.'}</p>
  <div class="stepper"><button data-a="wakeAdj" data-v="-5" aria-label="5 minutes earlier">−</button><div class="v">${hhmm(cur)}</div><button data-a="wakeAdj" data-v="5" aria-label="5 minutes later">+</button></div>
  <div class="chips" style="margin:14px 0">${[6 * 60, 6 * 60 + 30, 7 * 60, 7 * 60 + 30, 8 * 60].map((v) => `<button class="chip${cur === v ? ' on' : ''}" data-a="wakeSet" data-v="${v}">${hhmm(v)}</button>`).join('')}</div>
  <div class="btn-row"><button class="btn tonal" data-a="wakeClear">${S.tonightWake != null ? 'Use alarm or profile' : 'Cancel'}</button><button class="btn primary" data-a="wakeDone">Done</button></div>`);
};

OVERLAYS.endNight = () => dialog(`<h2>End the night now?</h2><p class="muted" style="margin:0">The BedJet turns off and tonight is saved to Sleep.</p>
  <div class="actions"><button class="btn ghost" data-a="back">Keep going</button><button class="btn ghost" style="color:var(--bad)" data-a="endNight">End night</button></div>`);

OVERLAYS.checkin = () => dialog(`<h2>How did the night feel?</h2><p class="muted" style="margin:0 0 6px">${esc(S.nights[0].profile)} · ${dur(S.nights[0].asleep, true)} asleep</p>
  <div class="stack" style="margin-top:12px">
    <button class="btn tonal block" data-a="feel" data-v="hot">Too hot</button>
    <button class="btn primary block" data-a="feel" data-v="right">Just right</button>
    <button class="btn tonal block" data-a="feel" data-v="cold">Too cold</button>
  </div><div class="actions"><button class="btn ghost" data-a="back">Cancel</button></div>`);

OVERLAYS.suggest = (o) => {
  const p = S.profiles.find((x) => x.id === o.pid) || tonightProfile();
  const m = p.middle;
  const dt = o.feel === 'hot' ? -1 : 1;
  const heaty = m.mode === 'heat' || m.mode === 'ext';
  const what = heaty ? `${MODES[m.mode].label} ${temp(m.temp)} → ${temp(m.temp + dt)}`
    : o.feel === 'hot' ? `Fan ${m.fanFrom}% → ${Math.min(100, m.fanFrom + 10)}% at the start of the middle` : `${MODES[m.mode].label} ${temp(m.temp)} → ${temp(Math.min(31, m.temp + 1))}`;
  return dialog(`<h2>Change Middle for future nights?</h2><p class="muted" style="margin:0">You said last night felt ${o.feel === 'hot' ? 'too hot' : 'too cold'}. Suggested change to ${esc(p.name)}:</p>
  <div class="card tight" style="margin:12px 0 0;font-weight:700">${esc(what)}</div>
  <div class="stack" style="margin-top:12px"><button class="btn primary block" data-a="applySuggest" data-v="${o.feel}">Apply to profile</button><button class="btn ghost block" data-a="back">Keep profile as it is</button></div>`);
};

OVERLAYS.boost = () => dialog(`<h2>Edit Boost</h2><p class="muted" style="margin:0 0 10px">Turbo heat for 10 minutes, then Heat for <b class="boost-v">${S.boost.heatMin} minutes</b>.</p>
  ${slider('boostMin', 'Heat afterwards', S.boost.heatMin, 0, 20, 1, 'min', { ends: ['None', '20 min'] })}
  <div class="actions"><button class="btn ghost" data-a="back">Cancel</button><button class="btn ghost" data-a="saveBoost">Save</button></div>`);

OVERLAYS.preset = (o) => {
  const pr = S.presets.find((x) => x.id === o.id);
  const md = MODES[pr.mode];
  return sheet(`<h2>${md.label} preset</h2><p class="small faint" style="margin:0 0 12px">Shown on Tonight, the widget and shortcuts.</p>
  ${pr.mode === 'turbo' ? '' : `<div class="kicker">Temperature</div><div class="stepper" style="margin:8px 0 14px"><button data-a="presetTemp" data-v="-0.5" aria-label="Cooler">−</button><div class="v">${temp(pr.temp)}</div><button data-a="presetTemp" data-v="0.5" aria-label="Warmer">+</button></div>`}
  <div class="kicker">How long</div><div class="chips" style="margin-top:8px">${(pr.mode === 'turbo' ? [5, 10] : [15, 30, 60, 120, 240, 720]).map((v) => `<button class="chip${pr.dur === v ? ' on' : ''}" data-a="presetDur" data-v="${v}">${dur(v)}</button>`).join('')}</div>
  <button class="btn primary block" style="margin-top:16px" data-a="back">Done</button>`);
};

OVERLAYS.customRun = () => sheet(`<h2>Run for</h2><div class="stepper" style="margin:14px 0"><button data-a="customRun" data-v="-15" aria-label="Shorter">−</button><div class="v">${dur(S.remote.runFor)}</div><button data-a="customRun" data-v="15" aria-label="Longer">+</button></div>
  <button class="btn primary block" data-a="customRunDone">Done</button>`);

OVERLAYS.newProfile = () => sheet(`<h2>New profile</h2><p class="small faint" style="margin:0 0 12px">Start from a seasonal preset and make it yours.</p>
  ${Object.entries(PRESETS).map(([k, p]) => { const segs = compile(makeProfile(k, 'x'), nightAbs(p.bed), nightAbs(p.wake));
    return `<button class="choice" style="display:block;text-align:left" data-a="newProfile" data-v="${k}"><b>${esc(p.name)}</b><div style="margin-top:8px">${planBar(segs, { thin: true })}</div></button>`; }).join('')}`);

OVERLAYS.deleteProfile = () => dialog(`<h2>Delete this profile?</h2><p class="muted" style="margin:0">${esc(S.drafts[curPage().id]?.p.name || '')} is removed. A backup snapshot keeps a copy.</p>
  <div class="actions"><button class="btn ghost" data-a="back">Cancel</button><button class="btn ghost" style="color:var(--bad)" data-a="deleteProfile">Delete</button></div>`);
OVERLAYS.deleteProfileById = (o) => dialog(`<h2>Delete this profile?</h2><p class="muted" style="margin:0">${esc(S.profiles.find((p) => p.id === o.id)?.name || '')} is removed. A backup snapshot keeps a copy.</p>
  <div class="actions"><button class="btn ghost" data-a="back">Cancel</button><button class="btn ghost" style="color:var(--bad)" data-a="deleteProfileById" data-v="${o.id}">Delete</button></div>`);

OVERLAYS.discard = () => dialog(`<h2>Discard changes?</h2><p class="muted" style="margin:0">Your changes to this profile haven't been saved.</p>
  <div class="actions"><button class="btn ghost" data-a="back">Keep editing</button><button class="btn ghost" style="color:var(--bad)" data-a="discard">Discard</button><button class="btn ghost" data-a="saveProfile">Save</button></div>`);

OVERLAYS.addStep = (o) => {
  const choices = [['turbo', 43, 100, 10, 'Turbo 43°C · 10 min'], ['heat', 32, 40, 30, 'Heat 32°C · 30 min'], ['ext', 30, 30, 60, 'Long heat 30°C · any length'], ['cool', 22, 40, 30, 'Cool 22°C · 30 min'], ['dry', 28, 40, 30, 'Dry 28°C · 30 min'], ['wait', null, 0, 30, 'Pause 30 min']];
  return sheet(`<h2>Add a step</h2>
  <div class="seg" style="margin:10px 0 14px">${[['start', 'Start'], ['wakeUp', 'Wake up'], ['after', 'After waking']].map(([k, l]) => `<button class="${o.phase === k ? 'on' : ''}" data-a="addPhase" data-v="${k}">${l}</button>`).join('')}</div>
  ${choices.map(([m, t, f, d, l], i) => `<button class="choice${o.pick === i ? ' on' : ''}" data-a="addPick" data-v="${i}"><span class="sw" style="background:${modeColor(m, t)};width:6px;height:28px"></span><b class="grow">${l}</b></button>`).join('')}
  <button class="btn primary block" style="margin-top:8px" data-a="addStep" ${o.pick == null ? 'disabled style="opacity:.5;margin-top:8px"' : ''}>Next: set it up</button>`);
};

OVERLAYS.step = (o) => {
  const d = S.drafts[curPage().id]; if (!d) return '';
  const s = d.p[o.phase][o.i]; if (!s) return '';
  const md = MODES[s.mode];
  const segs = compile(d.p, nightAbs(d.p.bed), nightAbs(d.p.wake));
  const seg = segs.find((x) => x.phase === o.phase && x.i === o.i);
  const title = { start: 'Start step', wakeUp: 'Wake-up step', after: 'After waking step' }[o.phase];
  const maxDur = s.mode === 'turbo' && !d.p.repeatTurbo ? 10 : 240;
  return sheet(`<h2>${title}</h2>
  <div class="seg" style="margin:12px 0 16px">${['turbo', 'heat', 'ext', 'cool', 'dry', 'wait'].map((m) => `<button class="${s.mode === m ? 'on' : ''}" data-a="stepMode" data-v="${m}">${MODES[m].short}</button>`).join('')}</div>
  ${s.mode === 'wait' ? '<p class="muted">The BedJet rests for this long.</p>' : `
  <div class="kicker">Temperature</div>
  <div class="stepper" style="margin:8px 0 14px"><button data-a="stepTemp" data-v="-0.5" aria-label="Cooler" ${s.mode === 'turbo' ? 'disabled style="opacity:.35"' : ''}>−</button><div class="v" style="color:${modeColor(s.mode, s.temp)}">${temp(s.temp)}</div><button data-a="stepTemp" data-v="0.5" aria-label="Warmer" ${s.mode === 'turbo' ? 'disabled style="opacity:.35"' : ''}>+</button></div>
  ${s.mode === 'turbo' ? '' : `<p class="tiny faint" style="margin:-8px 0 12px;text-align:center">${md.label}: ${temp(md.min)} to ${temp(Math.min(md.max, d.p.limits.tMax))}</p>`}
  ${slider('stepFan', 'Fan', s.fan, Math.max(5, d.p.limits.fanMin), d.p.limits.fanMax, 5, 'fan')}`}
  <div class="kicker" style="margin-top:12px">How long</div>
  <div class="stepper" style="margin:8px 0 10px"><button data-a="stepDur" data-v="-5" aria-label="Shorter">−</button><div class="v">${dur(s.dur)}</div><button data-a="stepDur" data-v="5" aria-label="Longer">+</button></div>
  <div class="chips">${[30, 45, 60, 120].filter((v) => v <= maxDur).map((v) => `<button class="chip${s.dur === v ? ' on' : ''}" data-a="stepDurSet" data-v="${v}">${dur(v)}</button>`).join('')}</div>
  ${seg ? `<p class="small muted" style="margin:14px 0 0">Runs ${hhmm(seg.a)} – ${hhmm(seg.b)}. The night adjusts to fit.</p>` : ''}
  <div class="btn-row"><button class="btn danger" data-a="deleteStep">Delete step</button><button class="btn primary" data-a="back">Done</button></div>`);
};

OVERLAYS.middle = () => {
  const d = S.drafts[curPage().id]; if (!d) return '';
  const m = d.p.middle, md = MODES[m.mode];
  return sheet(`<h2>Through the night</h2><p class="small faint" style="margin:0 0 12px">Stretches or shrinks to fill the time between Start and Wake up.</p>
  <div class="seg" style="margin-bottom:14px">${['heat', 'ext', 'cool', 'dry'].map((k) => `<button class="${m.mode === k ? 'on' : ''}" data-a="midMode" data-v="${k}">${MODES[k].short}</button>`).join('')}</div>
  ${slider('midTemp', 'Temperature at the start', m.temp, md.min, Math.min(md.max, d.p.limits.tMax), .5, 'temp', { fill: modeColor(m.mode, m.temp) })}
  ${slider('midTempTo', 'Temperature by the end', m.tempTo, md.min, Math.min(md.max, d.p.limits.tMax), .5, 'temp', { fill: modeColor(m.mode, m.tempTo) })}
  ${slider('midFanFrom', 'Fan at the start', m.fanFrom, 5, d.p.limits.fanMax, 5, 'fan')}
  ${slider('midFanTo', 'Fan by the end', m.fanTo, 5, d.p.limits.fanMax, 5, 'fan')}
  <div class="row between" style="margin-top:12px"><span>Change in steps</span><div class="row" style="gap:6px"><button class="chip" data-a="midSteps" data-v="-1" aria-label="Fewer steps">−</button><b style="width:24px;text-align:center">${m.steps}</b><button class="chip" data-a="midSteps" data-v="1" aria-label="More steps">+</button></div></div>
  <button class="btn primary block" style="margin-top:16px" data-a="back">Done</button>`);
};

OVERLAYS.menu = (o) => {
  let items = [];
  if (o.id === 'nightMore') items = [['Skip to the next step', 'skipStep'], ['Stop tracking (BedJet keeps running)', 'stopTracking']];
  else if (o.id.startsWith('profile:')) {
    const [, pid, idx] = o.id.split(':'); const i = Number(idx);
    items = [['Edit', 'editProfile', pid], ...(i > 0 ? [['Move up', 'moveProfile', `${pid}:-1`]] : []), ...(i < S.profiles.length - 1 ? [['Move down', 'moveProfile', `${pid}:1`]] : []),
      ['Duplicate', 'dupProfile', pid], ['Keep on BedJet slot…', 'toastOnly', 'Kept on BedJet slot 2 (demo)'], ['Delete', 'askDeleteById', pid, 'bad']];
  }
  const style = o.y > 520 ? `bottom:${o.sh - o.y + 4}px` : `top:${o.y + o.h + 4}px`;
  return `<div class="menu" role="menu" style="right:${Math.max(10, o.sw - o.x - o.w)}px;${style}">${items.map(([l, a, v, c]) => `<button role="menuitem" class="${c || ''}" data-a="menuPick" data-v="${a}|${v ?? ''}">${esc(l)}</button>`).join('')}</div>`;
};

/* ---------------- Home screen ---------------- */
function homeScreen() {
  const bj = S.bedjet, on = bj.mode !== 'off';
  const state = S.night ? `Night · ${stepName(segAt(nightSegs(), S.clock) || nightSegs()[0])}` : on ? `${stepName(bj)} · fan ${bj.fan}%` : 'Idle';
  const tiles = [['turbo', 'Turbo', '10 min', 'var(--heat4)'], ['heat', 'Heat', temp(32), 'var(--heat2)'], ['cool', 'Cool', temp(22), 'var(--cool)'], ['dry', 'Dry', temp(28), 'var(--dry)'], ['boost', 'Boost', 'Turbo + Heat', 'var(--heat3)'], ['start', 'Start', 'tonight', 'var(--accent)']];
  const d = new Date(TODAY); if (S.clock >= DAY) d.setDate(d.getDate() + 1);
  return `<div class="home">
    <div class="statusbar" style="color:#fff"><span>${hhmm(S.clock)}</span><span class="small">Pretend home screen</span></div>
    <div class="wall">
      <div class="clock">${hhmm(S.clock)}</div><div class="date">${S.clock >= DAY ? 'Tuesday' : 'Monday'} ${d.getDate()} ${MONTHS[d.getMonth()]}</div>
      <div class="widget${S.outOfRange ? ' grey' : ''}">
        <div class="row between"><b>Nocturne</b><span class="small" style="color:var(--ink2)">${S.outOfRange ? 'Out of range · tap to reconnect' : esc(state)}</span></div>
        <div class="row" style="gap:16px;margin-top:6px"><span style="font:700 28px Bricolage,Figtree">${temp(Math.round(S.air * 2) / 2)}</span><span class="small" style="color:var(--ink3)">Air from the BedJet</span></div>
        <div class="wtiles">${tiles.map(([k, t, s, c]) => `<button class="wtile" style="--c:${c}" data-a="widget" data-v="${k}"><div class="t">${t}</div><div class="s">${esc(s)}</div></button>`).join('')}</div>
        <button class="wtile" style="--c:var(--off);width:100%;margin-top:8px;min-height:44px" data-a="widget" data-v="off"><div class="t">Off</div></button>
      </div>
      <div class="row" style="gap:14px;align-items:flex-start">
        <button class="widget${S.outOfRange ? ' grey' : ''}" style="width:96px;height:96px;padding:10px;text-align:left;--c:var(--heat3)" data-a="widget" data-v="boost"><div style="font-weight:700">Boost</div><div class="small" style="color:var(--ink3)">1 × 1</div></button>
        <div class="grow small" style="opacity:.8;padding-top:6px">Widgets go grey when the BedJet is out of range. A tap reconnects first, then runs what you tapped.<br><br><button class="chip" style="color:#fff;border-color:rgba(255,255,255,.3)" data-a="toggleRange">${S.outOfRange ? 'Bring the BedJet back' : 'Pretend it is out of range'}</button></div>
      </div>
      <div class="apps">
        <button data-a="openApp" aria-label="Open Nocturne"><span class="icon nocturne"><svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/></svg></span>Nocturne</button>
        <button data-a="toastOnly" data-v="Just part of the pretend home screen"><span class="icon">⏰</span>Clock</button>
        <button data-a="toastOnly" data-v="Just part of the pretend home screen"><span class="icon">⚙️</span>Settings</button>
        <button data-a="toastOnly" data-v="Just part of the pretend home screen"><span class="icon">❤️</span>Health</button>
      </div>
    </div></div>`;
}

/* ---------------- Render ---------------- */
let els = {};
function renderShell() {
  els.screen.innerHTML = `
  <div class="app">
    <div class="statusbar"><span class="sb-time">${hhmm(S.clock)}</span><span class="icons"><svg viewBox="0 0 24 24"><path d="M7 7l10 10-5 4V3l5 4L7 17" fill="none" stroke="currentColor" stroke-width="2"/></svg><svg viewBox="0 0 24 24"><rect x="3" y="7" width="16" height="10" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><rect x="5" y="9" width="10" height="6" rx="1"/><rect x="20" y="10" width="2" height="4"/></svg></span></div>
    <div class="viewport"><div class="page" tabindex="-1"></div></div>
    <div class="democlock" aria-label="Demo time">
      <button class="play" data-a="play" aria-label="Play"></button>
      <span><span class="lbl">Demo time</span><br><span class="time"></span></span>
      <input class="slider" type="range" data-in="clock" min="${CLOCK_MIN}" max="${CLOCK_MAX}" step="1" aria-label="Demo time">
      <button class="speed" data-a="speed" aria-label="Speed"></button>
    </div>
    <nav class="tabbar">${[['tonight', 'Tonight', 'moon'], ['remote', 'Remote', 'remote'], ['profiles', 'Profiles', 'list'], ['settings', 'Settings', 'gear']].map(([k, l, ic]) => `<button data-a="tab" data-v="${k}" data-tab="${k}"><span class="pip">${svg(ic)}</span>${l}</button>`).join('')}</nav>
  </div>
  <div class="home-layer"></div>
  <div class="overlay-layer"></div>
  <div class="notif-layer"></div>
  <div class="toast-layer"></div>`;
  ['page', 'viewport', 'democlock', 'tabbar', 'app'].forEach((k) => { els[k] = $(`.${k}`, els.screen); });
  els.sbTime = $('.sb-time', els.screen); els.home = $('.home-layer', els.screen); els.overlay = $('.overlay-layer', els.screen);
  els.notif = $('.notif-layer', els.screen); els.toast = $('.toast-layer', els.screen);
}
let lastPageKey = '';
function isTyping() { const a = document.activeElement; return a && a.matches && a.matches('input:not([type=range])') && els.screen.contains(a); }
function render(anim = null, overlayChanged = false) {
  applyTheme();
  // Page
  const pg = curPage();
  const key = JSON.stringify([R.home, R.tab, R.paths[R.tab]]);
  const fn = PAGES[`${R.tab}_${pg.p}`] || PAGES[pg.p];
  const pageChanged = key !== lastPageKey;
  if (!S.dragging && (pageChanged || !isTyping())) {
    const top = pageChanged ? 0 : els.page.scrollTop;
    els.page.innerHTML = fn ? fn(pg) : '';
    els.page.scrollTop = top;
    if (pageChanged && anim) { els.page.classList.remove('enter-fwd', 'enter-back', 'enter-fade'); void els.page.offsetWidth; els.page.classList.add(`enter-${anim}`); }
    if (pageChanged) lastPageKey = key;
  }
  // Tabs
  els.tabbar.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.tab === R.tab));
  // Home screen
  els.home.innerHTML = R.home ? homeScreen() : '';
  // Overlay
  if (!S.dragging || overlayChanged) {
    const o = R.overlay;
    if (!o) els.overlay.innerHTML = '';
    else if (!(isTyping() && els.overlay.contains(document.activeElement))) {
      const html = OVERLAYS[o.k] ? OVERLAYS[o.k](o) : '';
      const sc = $('.sheet', els.overlay)?.scrollTop || 0;
      els.overlay.innerHTML = `<div class="scrim${overlayChanged ? ' anim' : ''}" data-a="scrim"></div>${html}`;
      if (overlayChanged) els.overlay.querySelectorAll('.sheet,.dialog,.menu').forEach((x) => x.classList.add('anim'));
      else { const sh = $('.sheet', els.overlay); if (sh) sh.scrollTop = sc; }
      if (overlayChanged) setTimeout(() => { const f = els.overlay.querySelector('.menu button, .dialog button, .sheet button'); if (f && !matchMedia('(hover: none)').matches) f.focus({ preventScroll: true }); }, 30);
    }
  }
  renderClock();
}
function renderClock() {
  els.sbTime.textContent = hhmm(S.clock);
  const dc = els.democlock;
  $('.play', dc).innerHTML = S.playing ? '<svg viewBox="0 0 24 24"><rect x="5" y="4" width="5" height="16" rx="1"/><rect x="14" y="4" width="5" height="16" rx="1"/></svg>' : '<svg viewBox="0 0 24 24"><path d="M6 4l14 8-14 8z"/></svg>';
  $('.play', dc).setAttribute('aria-label', S.playing ? 'Pause demo time' : 'Play demo time');
  $('.time', dc).textContent = `${S.clock >= DAY ? 'Tue' : 'Mon'} ${hhmm(S.clock)}`;
  $('.speed', dc).textContent = `${S.speed} min/s`;
  const r = $('input', dc);
  if (!S.dragging || document.activeElement !== r) {
    r.value = S.clock; r.style.setProperty('--pct', `${((S.clock - CLOCK_MIN) / (CLOCK_MAX - CLOCK_MIN)) * 100}%`);
  }
  // Mark the night on the time bar.
  if (S.night && nightSegs().length) {
    const segs = nightSegs(); const a = ((segs[0].a - CLOCK_MIN) / (CLOCK_MAX - CLOCK_MIN)) * 100, b = ((planEnd(segs) - CLOCK_MIN) / (CLOCK_MAX - CLOCK_MIN)) * 100;
    r.style.setProperty('--fill', 'var(--accent)');
    r.title = `Night ${hhmm(segs[0].a)}–${hhmm(planEnd(segs))}`;
    r.style.background = `linear-gradient(to right, transparent ${a}%, color-mix(in srgb, var(--accent) 22%, transparent) ${a}%, color-mix(in srgb, var(--accent) 22%, transparent) ${b}%, transparent ${b}%)`;
    r.style.borderRadius = '8px';
  } else { r.style.background = ''; r.title = ''; }
}
function renderToast() { els.toast.innerHTML = S.toast ? `<div class="toast" role="status">${esc(S.toast)}</div>` : ''; }
function renderNotif() {
  const n = S.notif;
  els.notif.innerHTML = n ? `<div class="notif" role="status"><div class="app-name"><i></i>Nocturne · now<button style="margin-left:auto;font-size:16px;padding:0 4px" data-a="dismissNotif" aria-label="Dismiss">×</button></div>
    <div class="title">${esc(n.title)}</div><div class="small" style="color:var(--ink2)">${esc(n.text)}</div>
    ${n.acts && n.acts.length ? `<div class="acts">${n.acts.map((x) => `<button data-a="${x.a}" data-v="${esc(x.v || '')}">${esc(x.l)}</button>`).join('')}</div>` : ''}</div>` : '';
}
function applyTheme() {
  const st = S.settings;
  const dark = st.theme === 'dark' || (st.theme === 'system' && (tod(S.clock) >= 19 * 60 || tod(S.clock) < 7 * 60));
  const pal = dark ? { ...DARK_INK, ...PALETTES[st.palette] } : OVERCAST;
  const key = JSON.stringify(pal);
  if (applyTheme.key === key) return; applyTheme.key = key;
  const v = { ground: pal.ground, glow: pal.glow, accent: pal.accent, sheet: pal.sheet, menu: pal.menu, 'on-primary': pal.onPrimary, ink: pal.ink, ink2: pal.ink2, ink3: pal.ink3,
    glass: pal.glass, glass2: pal.glass2, hair: pal.hair, cool: pal.cool, primary: pal.primary || pal.ink };
  Object.entries(v).forEach(([k, x]) => els.screen.style.setProperty(`--${k}`, x));
  els.screen.style.colorScheme = dark ? 'dark' : 'light';
}
function renderTry() {
  const ul = $('#try'); if (!ul) return;
  ul.innerHTML = TRY.map(([k, l]) => `<li class="${tried.has(k) ? 'done' : ''}"><span class="box">${tried.has(k) ? '✓' : ''}</span><span>${esc(l)}</span></li>`).join('');
  const c = $('#try-count'); if (c) c.textContent = `${[...tried].filter((k) => TRY.some(([x]) => x === k)).length} of ${TRY.length}`;
}

/* ---------------- Demo clock ---------------- */
let raf = 0;
function loop(ts) {
  if (!S.playing) { raf = 0; return; }
  const dt = S.lastTick ? Math.min(.25, (ts - S.lastTick) / 1000) : 0; S.lastTick = ts;
  advanceTo(S.clock + dt * S.speed);
  raf = requestAnimationFrame(loop);
}
let lastRenderMin = -1;
function advanceTo(t) {
  t = clamp(t, CLOCK_MIN, CLOCK_MAX);
  const from = Math.floor(S.clock);
  S.clock = t;
  for (let m = from + 1; m <= Math.floor(t); m++) { const keep = S.clock; S.clock = m; simulateMinute(); S.clock = keep; }
  if (t >= CLOCK_MAX) { S.playing = false; toast('Demo time ends at 12:00. Reset the demo to start again.'); }
  if (Math.floor(S.clock) !== lastRenderMin) { lastRenderMin = Math.floor(S.clock); render(); } else renderClock();
}
function setPlaying(on) {
  S.playing = on; S.lastTick = 0;
  if (on && !raf) raf = requestAnimationFrame(loop);
  renderClock();
}

/* ---------------- Actions ---------------- */
const draft = () => S.drafts[curPage().id];
const touch = () => { const d = draft(); if (d) d.dirty = true; };
const A = {
  back: () => {
    const pg = curPage();
    if (!R.overlay && pg.p === 'editor' && draft()?.dirty) return open({ k: 'discard' });
    back();
  },
  scrim: () => back(),
  tab: (v) => {
    if (R.home) return nav((n) => { n.home = false; n.tab = v; }, 'fade');
    if (R.tab === v) { if (R.paths[v].length > 1) nav((n) => { n.paths[v] = [n.paths[v][0]]; n.overlay = null; }, 'back'); else els.page.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    nav((n) => { n.tab = v; n.overlay = null; }, 'fade');
  },
  play: () => { setPlaying(!S.playing); if (S.playing && S.night) tick('timelapse-started'); },
  speed: () => { S.speed = { 1: 5, 5: 15, 15: 1 }[S.speed]; renderClock(); },
  openOverlay: (v) => {
    const [k, arg] = v.split(':');
    if (k === 'addStep') return open({ k, phase: arg, pick: null });
    open({ k });
  },
  startNight: () => { S.notif = null; renderNotif(); if (R.home) nav((n) => { n.home = false; n.tab = 'tonight'; }, 'fade'); startNight(); },
  toggleEditPresets: () => { S.editPresets = !S.editPresets; render(); },
  preset: (v) => {
    const pr = S.presets.find((x) => x.id === v);
    if (S.editPresets) { if (v === 'boost') return open({ k: 'boost' }); if (v === 'off') return toast('Off has nothing to change'); return open({ k: 'preset', id: v }); }
    if (v === 'boost' && R.tab === 'remote') return runPreset(pr);
    runPreset(pr);
  },
  presetTemp: (v) => { const pr = S.presets.find((x) => x.id === R.overlay.id); const md = MODES[pr.mode]; pr.temp = clamp(pr.temp + Number(v), md.min, md.max); render(); },
  presetDur: (v) => { S.presets.find((x) => x.id === R.overlay.id).dur = Number(v); render(); },
  saveBoost: () => { toast(`Boost: Turbo, then Heat for ${S.boost.heatMin} min`); back(); },
  setup: (k) => {
    S.setup[k] = true;
    if (k === 'alarmapp') S.settings.alarmApps = ['samsung', 'google'];
    toast(k === 'alarmapp' ? 'Using your clock apps for your alarm' : 'On a phone this opens Android settings. Marked as allowed.');
    render();
  },
  openSleep: (v) => { S.sleepSel = Number(v); tick('sleep'); push({ p: 'sleep' }); },
  sleepRange: (v) => { S.sleepRange = Number(v); render(); },
  sleepSel: (v) => { S.sleepSel = Number(v); tick('sleep'); render(); const c = els.page.querySelectorAll('.card')[1]; if (c) c.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); },
  pickProfile: (v) => { S.tonightProfile = v; back(); toast(`Tonight: ${tonightProfile().name}`); },
  wakeAdj: (v) => { const p = S.night ? S.night.profile : tonightProfile(); S.tonightWake = tod((S.tonightWake ?? tod(wakeFor(p).at)) + Number(v)); render(); },
  wakeSet: (v) => { S.tonightWake = Number(v); render(); },
  wakeClear: () => { S.tonightWake = null; if (S.night) rewriteNight('Back to your alarm or profile. Plan on the BedJet rewritten.'); back(); },
  wakeDone: () => { back(); if (S.night) rewriteNight(`Wake ${hhmm(wakeFor(S.night.profile).at)}. Plan on the BedJet rewritten.`); else if (S.tonightWake != null) toast(`Tonight only: wake at ${hhmm(S.tonightWake)}`); },
  endNight: () => { back(); const epoch = sendEpoch; setTimeout(() => { if (epoch === sendEpoch) send('Night ended. BedJet off.', () => finishNight(true), true); }, 60); },
  feel: (v) => {
    S.nights[0].feel = v;
    if (v === 'right') { back(); toast('Morning check-in saved.'); tick('checkin'); return; }
    tick('checkin');
    const pid = S.profiles.find((p) => p.name === S.nights[0].profile)?.id || S.tonightProfile;
    swapOverlay({ k: 'suggest', feel: v, pid });
  },
  applySuggest: (v) => {
    const p = S.profiles.find((x) => x.id === R.overlay.pid) || tonightProfile();
    const m = p.middle, heaty = m.mode === 'heat' || m.mode === 'ext', dt = v === 'hot' ? -1 : 1;
    if (heaty) { m.temp += dt; m.tempTo += dt; } else if (v === 'hot') m.fanFrom = Math.min(100, m.fanFrom + 10); else { m.temp = Math.min(31, m.temp + 1); m.tempTo = Math.min(31, m.tempTo + 1); }
    back(); toast(`${p.name} updated for future nights. Morning check-in saved.`);
  },
  dismissNotif: () => { S.notif = null; renderNotif(); },
  wakeAct: (k) => {
    S.notif = null; renderNotif();
    if (!S.night) return toast('Preview only — start a night to use these.');
    const n = S.night;
    if (k === 'off') return send('Night ended. BedJet off.', () => finishNight(true), true);
    const add = { snoozeWarm: step('heat', 32, 30, 10), snoozeCool: step('cool', 22, 30, 10), warm30: step('heat', 32, 30, 30), cool30: step('cool', 22, 40, 30) }[k];
    if (k.startsWith('snooze')) { send(`Snoozed: ${stepName(add)} until ${hhmm(Math.round(S.clock) + 10)}`, () => { const a = Math.round(S.clock); n.snooze = { ...add, a, b: a + 10 }; n.wake = Math.max(n.wake, a) + 10; simulateMinute(); }); }
    else { send(`${WAKE_BUTTONS[k]} added after the plan`, () => { n.extras.push(add); simulateMinute(); }); }
  },
  menu: (v, el) => {
    const sr = els.screen.getBoundingClientRect(), r = el.getBoundingClientRect(), sc = sr.width / els.screen.offsetWidth;
    open({ k: 'menu', id: v, x: (r.left - sr.left) / sc, y: (r.top - sr.top) / sc, w: r.width / sc, h: r.height / sc, sw: els.screen.offsetWidth, sh: els.screen.offsetHeight });
  },
  menuPick: (v) => {
    const [a, arg] = v.split('|');
    if (a === 'editProfile') { nav((n) => { n.overlay = null; }, null, true); return A.editProfile(arg); }
    if (a === 'askDeleteById') return swapOverlay({ k: 'deleteProfileById', id: arg });
    back();
    setTimeout(() => A[a](arg), 30);
  },
  skipStep: () => {
    const segs = nightSegs(); const cur = segAt(segs, S.clock); if (!cur) return;
    const i = segs.indexOf(cur); const nx = segs[i + 1]; if (!nx) return toast('This is the last step');
    advanceTo(nx.a + .01); toast(`Skipped to ${stepName(nx)}`);
  },
  stopTracking: () => {
    const rest = nightSegs().filter((x) => x.b > S.clock);
    stopNight();
    if (rest.length) {
      const run = (x) => ({ mode: x.mode === 'wait' ? 'off' : x.mode, temp: x.temp ?? S.bedjet.temp, fan: x.fan ?? S.bedjet.fan, endsAt: x.b });
      S.bedjet = { ...S.bedjet, ...run(rest[0]), then: null, queue: rest.slice(1).map(run) };
    }
    render(); toast('Stopped tracking. The BedJet keeps running what it has.'); },
  remoteMode: (m) => {
    const rm = S.remote; rm.mode = m === 'off' ? rm.mode : m;
    if (m === 'off') return send('BedJet off', () => { S.bedjet = { ...S.bedjet, mode: 'off', endsAt: null, then: null, queue: null }; stopNight(); });
    const md = MODES[m]; rm.temp = m === 'turbo' ? 43 : clamp(rm.temp, md.min, md.max);
    const d = m === 'turbo' ? 10 : rm.runFor;
    tick('remote');
    send(`${stepName({ mode: m, temp: rm.temp })} · fan ${rm.fan}% for ${dur(d)}`, () => { if (S.night) { stopNight(); } S.bedjet = { mode: m, temp: rm.temp, fan: rm.fan, endsAt: S.clock + d, then: null }; });
  },
  runFor: (v) => {
    S.remote.runFor = Number(v);
    // Turbo always stops after 10 minutes, whatever run time is chosen.
    const d = S.bedjet.mode === 'turbo' ? Math.min(10, Number(v)) : Number(v);
    if (S.bedjet.mode !== 'off' && !S.night) send(`Runs until ${hhmm(S.clock + d)}`, () => { S.bedjet.endsAt = S.clock + d; }); else render();
  },
  customRun: (v) => { S.remote.runFor = clamp(S.remote.runFor + Number(v), 15, 720); render(); },
  customRunDone: () => { back(); A.runFor(S.remote.runFor); },
  importProfile: () => toast('On a phone this opens a profile file you choose'),
  fav: (v) => { S.profiles.forEach((p) => { p.fav = p.id === v; }); S.tonightProfile = v; render(); toast(`${S.profiles.find((p) => p.id === v).name} is used each night`); },
  useTonight: (v) => { S.tonightProfile = v; render(); toast(`Tonight: ${tonightProfile().name}`); },
  editProfile: (v) => {
    if (!S.drafts[v] || !S.drafts[v].dirty) S.drafts[v] = { p: clone(S.profiles.find((p) => p.id === v)), dirty: false };
    if (R.tab !== 'profiles') nav((n) => { n.tab = 'profiles'; n.paths.profiles = [{ p: 'home' }, { p: 'editor', id: v }]; n.overlay = null; }, 'fwd');
    else push({ p: 'editor', id: v });
  },
  newProfile: (k) => {
    const id = `p${Date.now()}`;
    S.drafts[id] = { p: makeProfile(k, id, { name: `My ${PRESETS[k].name}` }), dirty: true, isNew: true };
    nav((n) => { n.overlay = null; n.paths.profiles.push({ p: 'editor', id }); }, 'fwd', true);
  },
  saveProfile: () => {
    const pg = curPage().p === 'editor' ? curPage() : null; if (!pg) return;
    const d = S.drafts[pg.id]; if (!d.p.name.trim()) return toast('Give the profile a name');
    const i = S.profiles.findIndex((p) => p.id === pg.id);
    if (i >= 0) S.profiles[i] = clone(d.p); else S.profiles.push(clone(d.p));
    delete S.drafts[pg.id];
    toast(`Saved ${d.p.name}${S.night && S.night.profile.id === pg.id ? '. Tonight keeps the plan it started with.' : ''}`);
    if (R.overlay) nav((n) => { n.overlay = null; n.paths.profiles.pop(); }, 'back', true); else back();
  },
  discard: () => { delete S.drafts[curPage().id]; nav((n) => { n.overlay = null; n.paths.profiles.pop(); }, 'back', true); },
  deleteProfileAsk: () => open({ k: 'deleteProfile' }),
  deleteProfile: () => { const id = curPage().id; A._remove(id); nav((n) => { n.overlay = null; n.paths.profiles.pop(); }, 'back', true); },
  deleteProfileById: (id) => { A._remove(id); back(); },
  _remove: (id) => {
    if (S.profiles.length === 1) return toast('Keep at least one profile');
    const name = S.profiles.find((p) => p.id === id)?.name;
    S.profiles = S.profiles.filter((p) => p.id !== id); delete S.drafts[id];
    if (S.tonightProfile === id) S.tonightProfile = S.profiles[0].id;
    if (!S.profiles.some((p) => p.fav)) S.profiles[0].fav = true;
    toast(`Deleted ${name}`);
  },
  moveProfile: (v) => { const [id, d] = v.split(':'); const i = S.profiles.findIndex((p) => p.id === id); const j = i + Number(d); [S.profiles[i], S.profiles[j]] = [S.profiles[j], S.profiles[i]]; render(); },
  dupProfile: (id) => { const p = S.profiles.find((x) => x.id === id); const c = { ...clone(p), id: `p${Date.now()}`, name: `${p.name} copy`, fav: false }; S.profiles.splice(S.profiles.indexOf(p) + 1, 0, c); render(); toast(`Duplicated ${p.name}`); },
  pTime: (v) => { const [k, d] = v.split(':'); draft().p[k] = tod(draft().p[k] + Number(d)); touch(); render(); },
  pToggle: (k) => { draft().p[k] = !draft().p[k]; touch(); render(); },
  pDay: (k) => { const d = draft().p.days; d[k] = d[k] ? 0 : 1; touch(); render(); },
  pSeason: (k) => { draft().p.season = k; touch(); render(); },
  resetLimits: () => { draft().p.limits = { fanMin: 5, fanMax: 100, tMin: 19, tMax: 43 }; touch(); render(); },
  openStep: (v) => { const [phase, i] = v.split(':'); open({ k: 'step', phase, i: Number(i) }); },
  stepMode: (m) => {
    const o = R.overlay, s = draft().p[o.phase][o.i], md = MODES[m];
    s.mode = m;
    if (m === 'turbo') { s.temp = 43; s.fan = 100; if (!draft().p.repeatTurbo) s.dur = Math.min(s.dur, 10); }
    else if (m !== 'wait') s.temp = clamp(s.temp ?? 30, md.min, Math.min(md.max, draft().p.limits.tMax));
    touch(); tick('editstep'); render();
  },
  stepTemp: (v) => { const o = R.overlay, s = draft().p[o.phase][o.i], md = MODES[s.mode]; s.temp = clamp(s.temp + Number(v), md.min, Math.min(md.max, draft().p.limits.tMax)); touch(); tick('editstep'); render(); },
  stepDur: (v) => { const o = R.overlay, s = draft().p[o.phase][o.i]; s.dur = clamp(s.dur + Number(v), 5, s.mode === 'turbo' && !draft().p.repeatTurbo ? 10 : 240); touch(); tick('editstep'); render(); },
  stepDurSet: (v) => { const o = R.overlay; draft().p[o.phase][o.i].dur = Number(v); touch(); tick('editstep'); render(); },
  deleteStep: () => { const o = R.overlay; draft().p[o.phase].splice(o.i, 1); touch(); back(); },
  midMode: (m) => { const mid = draft().p.middle, md = MODES[m]; mid.mode = m; mid.temp = clamp(mid.temp, md.min, md.max); mid.tempTo = clamp(mid.tempTo, md.min, md.max); touch(); tick('editstep'); render(); },
  midSteps: (v) => { const mid = draft().p.middle; mid.steps = clamp(mid.steps + Number(v), 1, 6); touch(); render(); },
  addPhase: (k) => { nav((n) => { n.overlay.phase = k; }, null, true); },
  addPick: (i) => { nav((n) => { n.overlay.pick = Number(i); }, null, true); },
  addStep: () => {
    const o = R.overlay; if (o.pick == null) return;
    const c = [['turbo', 43, 100, 10], ['heat', 32, 40, 30], ['ext', 30, 30, 60], ['cool', 22, 40, 30], ['dry', 28, 40, 30], ['wait', null, 0, 30]][o.pick];
    const arr = draft().p[o.phase]; arr.push(step(...c)); clampToLimits(draft().p); touch(); tick('editstep');
    swapOverlay({ k: 'step', phase: o.phase, i: arr.length - 1 });
  },
  settingsPage: (k) => { if (k === 'sleep') tick('sleep'); push({ p: k }); },
  setBool: (k) => { S.settings[k] = !S.settings[k]; applyTheme.key = null; render(); },
  toggleRange: () => { S.outOfRange = !S.outOfRange; render(); toast(S.outOfRange ? 'The BedJet is out of range' : 'The BedJet is back in range'); },
  slot: (k) => { S.settings.slot = Number(k); render(); toast(`Tonight's plan goes into slot ${k}. Slot ${k}'s old programme was saved.`); },
  toggleAlarmApp: (k) => {
    const st = S.settings, was = alarmCounts();
    st.alarmApps = st.alarmApps.includes(k) ? st.alarmApps.filter((x) => x !== k) : [...st.alarmApps, k];
    S.setup.alarmapp = st.alarmApps.length > 0; render();
    if (S.night && was !== alarmCounts()) rewriteNight('Alarm apps changed. Plan on the BedJet rewritten.');
  },
  alarmFrom: (k) => {
    const was = alarmCounts(); S.settings.alarmFrom = k; render();
    if (S.night && (was || alarmCounts())) rewriteNight(alarmCounts() ? `${ALARM_APPS[k].name} alarm used. Plan on the BedJet rewritten.` : `${ALARM_APPS[k].name} isn’t ticked, so its alarm is ignored.`);
  },
  setAlarm: (v) => {
    S.settings.alarm = v === '' ? null : Number(v); render();
    if (S.night && S.tonightWake == null && S.night.profile.wakeWithAlarm) rewriteNight(v === '' ? 'Alarm removed. Plan on the BedJet uses the profile wake.' : `Alarm moved to ${hhmm(Number(v))}. Plan on the BedJet rewritten.`);
    else toast(v === '' ? 'No alarm set' : `Alarm set for ${hhmm(Number(v))}`);
  },
  autoOn: () => { const a = S.settings.autoStart; a.on = !a.on; render(); if (a.on) toast(`Auto-start ${a.mode === 'fixed' ? `at ${hhmm(a.at)}` : `${dur(a.before)} before your alarm`}. Press ▶ to see it fire.`); },
  autoMode: (k) => { S.settings.autoStart.mode = k; render(); },
  autoTime: (v) => { const a = S.settings.autoStart; if (a.mode === 'fixed') a.at = tod(a.at + Number(v)); else a.before = clamp(a.before + Number(v), 60, 12 * 60); render(); },
  autoCharging: () => { S.settings.autoStart.charging = !S.settings.autoStart.charging; render(); },
  theme: (k) => { S.settings.theme = k; render(); tick('palette'); },
  palette: (k) => { S.settings.palette = k; if (S.settings.theme === 'light') S.settings.theme = 'dark'; render(); tick('palette'); },
  wakeBtn: (k) => {
    const b = S.settings.wakeButtons;
    if (b.includes(k)) S.settings.wakeButtons = b.filter((x) => x !== k);
    else if (b.length >= 3) return toast('Up to three buttons. Remove one first.');
    else b.push(k);
    render();
  },
  previewWake: () => notify({ title: 'Waking you at 07:00', text: 'Heat 30°C · fan 30% until your alarm', acts: S.settings.wakeButtons.map((k) => ({ l: WAKE_BUTTONS[k], a: 'wakeAct', v: k })) }),
  backupNow: () => { S.backups.unshift({ when: `${S.clock >= DAY ? 'Tue 10' : 'Mon 9'} Nov, ${hhmm(S.clock)}`, kind: 'Manual' }); render(); toast('Snapshot saved'); },
  toastOnly: (v) => { if (R.overlay && R.overlay.k === 'menu') back(); toast(v); },
  widget: (k) => {
    tick('widget');
    const run = () => {
      if (k === 'start') { nav((n) => { n.home = false; n.tab = 'tonight'; }, 'fade'); return startNight(); }
      if (k === 'boost') return runPreset({ id: 'boost' });
      if (k === 'off') return runPreset({ id: 'off' });
      return runPreset(S.presets.find((x) => x.id === k));
    };
    if (S.outOfRange) { S.outOfRange = false; toast('Reconnecting…'); render(); setTimeout(run, 900); return; }
    run();
  },
  openApp: () => nav((n) => { n.home = false; }, 'fade'),
};

/* ---------------- Inputs (sliders and text) ---------------- */
const IN = {
  clock: (v, done) => {
    let t = Number(v);
    if (S.night) t = Math.max(t, S.night.bed);
    if (t >= S.clock) advanceTo(t);
    else { S.clock = t; if (S.night) simulateMinute(); render(); }
    if (S.night && done) tick('timelapse-started');
  },
  remoteTemp: (v, done) => { S.remote.temp = Number(v); if (done && S.bedjet.mode === S.remote.mode && S.bedjet.mode !== 'off' && !S.night) send(`Target ${temp(S.remote.temp)}`, () => { S.bedjet.temp = S.remote.temp; }); else if (done) render(); },
  remoteFan: (v, done) => { S.remote.fan = Number(v); if (done && S.bedjet.mode !== 'off' && !S.night) send(`Fan ${S.remote.fan}%`, () => { S.bedjet.fan = S.remote.fan; }); else if (done) render(); },
  boostMin: (v) => { S.boost.heatMin = Number(v); const b = $('.boost-v', els.overlay); if (b) b.textContent = `${v} minute${v === '1' ? '' : 's'}`; },
  stepFan: (v, done) => { const o = R.overlay; draft().p[o.phase][o.i].fan = Number(v); touch(); if (done) { tick('editstep'); render(); } },
  midTemp: (v, done) => { draft().p.middle.temp = Number(v); touch(); if (done) render(); },
  midTempTo: (v, done) => { draft().p.middle.tempTo = Number(v); touch(); if (done) render(); },
  midFanFrom: (v, done) => { draft().p.middle.fanFrom = Number(v); touch(); if (done) render(); },
  midFanTo: (v, done) => { draft().p.middle.fanTo = Number(v); touch(); if (done) render(); },
  limFanMax: (v, done) => { const p = draft().p; p.limits.fanMax = Number(v); touch(); if (done) { clampToLimits(p); render(); } },
  limTMax: (v, done) => { const p = draft().p; p.limits.tMax = Number(v); touch(); if (done) { clampToLimits(p); render(); } },
  pName: (v) => { draft().p.name = v; touch(); const h = $('.h-page', els.page); if (h) h.textContent = v || 'New profile'; },
};
function clampToLimits(p) {
  const L = p.limits;
  [...p.start, ...p.wakeUp, ...p.after].forEach((s) => { if (s.mode !== 'wait') s.fan = Math.min(s.fan, L.fanMax); if (s.mode !== 'turbo' && s.temp != null) s.temp = Math.min(s.temp, L.tMax); });
  p.middle.fanFrom = Math.min(p.middle.fanFrom, L.fanMax); p.middle.fanTo = Math.min(p.middle.fanTo, L.fanMax);
  p.middle.temp = Math.min(p.middle.temp, L.tMax); p.middle.tempTo = Math.min(p.middle.tempTo, L.tMax);
}

/* ---------------- Wiring ---------------- */
function wire() {
  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-a]');
    if (!el || el.disabled) return;
    const fn = A[el.dataset.a] || PANEL[el.dataset.a];
    if (!fn) return;
    e.preventDefault();
    fn(el.dataset.v ?? '', el);
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && (R.overlay || S.notif)) { e.preventDefault(); if (S.notif) A.dismissNotif(); else back(); }
    if ((e.key === 'Enter' || e.key === ' ') && e.target.matches('[role=button][data-a]')) { e.preventDefault(); e.target.click(); }
  });
  const onInput = (e, done) => {
    const el = e.target; const k = el.dataset && el.dataset.in; if (!k || !IN[k]) return;
    if (el.type === 'range') {
      const w = el.closest('.slider-wrap');
      el.style.setProperty('--pct', `${((el.value - el.min) / (el.max - el.min)) * 100}%`);
      if (w) { const v = $('.v', w); if (v) v.textContent = FMT[w.dataset.fmt](el.value); }
    }
    IN[k](el.value, done);
  };
  document.addEventListener('input', (e) => onInput(e, false));
  document.addEventListener('change', (e) => { if (e.target.type === 'range') { S.dragging = false; onInput(e, true); } });
  document.addEventListener('pointerdown', (e) => { if (e.target.matches && e.target.matches('input[type=range]')) S.dragging = true; });
  const up = () => { if (S.dragging) { S.dragging = false; setTimeout(() => render(), 0); } };
  window.addEventListener('pointerup', up); window.addEventListener('pointercancel', up);
  document.addEventListener('focusout', (e) => { if (e.target.matches && e.target.matches('.field')) setTimeout(() => render(), 0); });
  // Fit the phone to the window on desktop.
  const fit = () => {
    const dev = $('.device');
    if (innerWidth <= 760) { dev.style.transform = ''; dev.style.margin = ''; return; }
    const s = Math.min(1, (innerHeight - 40) / 864);
    dev.style.transform = `scale(${s})`;
    dev.style.margin = `${(-864 * (1 - s)) / 2}px ${(-410 * (1 - s)) / 2}px`;
  };
  addEventListener('resize', fit); fit();
}
const PANEL = {
  showHome: () => { if (!R.home) nav((n) => { n.home = true; n.overlay = null; }, 'fade'); closePanel(); },
  reset: () => {
    setPlaying(false); sendEpoch++; S = freshState(); tried = new Set(); storage.set('nocturne-demo-tried', []);
    applyTheme.key = null; lastPageKey = ''; S.toast = null; S.notif = null; renderNotif(); renderTry();
    nav((n) => Object.assign(n, ROOT()), 'fade'); toast('Demo reset'); closePanel();
  },
  openPanel: () => { $('.panel').classList.add('as-sheet'); $('.panel-scrim').classList.add('show'); },
  closePanel: () => closePanel(),
};
function closePanel() { $('.panel').classList.remove('as-sheet'); $('.panel-scrim').classList.remove('show'); }

function boot() {
  S = freshState();
  els.screen = $('.screen');
  renderShell();
  R = ROOT();
  const h = (location.hash || '').slice(1).split('/');
  if (['tonight', 'remote', 'profiles', 'settings'].includes(h[0])) R.tab = h[0];
  if (h[0] === 'home') R.home = true;
  if (h[0] === 'settings' && h[1] && PAGES[h[1]]) R.paths.settings.push({ p: h[1] });
  if (h[0] === 'tonight' && h[1] === 'sleep') R.paths.tonight.push({ p: 'sleep' });
  NAV.list = [clone(R)]; NAV.i = 0;
  try { history.replaceState({ demo: 0 }, '', hashOf(R)); } catch { NAV.push = false; }
  wire();
  renderTry();
  render('fade');
  // Stamp
  const st = document.documentElement.dataset;
  const stamp = $('#stamp');
  if (stamp) stamp.textContent = `Based on Nocturne ${st.build} (commit ${st.commit}). Generated ${st.generated}.`;
}
document.addEventListener('DOMContentLoaded', boot);
})();
