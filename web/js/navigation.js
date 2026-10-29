// Turn-by-turn navigation: follows the live position, speaks the Hebrew instructions
// (speechSynthesis) and re-routes when the user leaves the route.
import { announce, esc, fmtDistance } from './util.js';

const spoken = (m) => (m >= 1000 ? `${(m / 1000).toFixed(1).replace('.0', '')} קילומטר` : `${m} מטר`);

const R = 6371000;
const rad = (d) => (d * Math.PI) / 180;

function dist(a, b) {
  const dLat = rad(b.lat - a.lat);
  const dLon = rad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/** Distance in metres from p to the segment a-b (local flat approximation, fine for < 1 km). */
function segDist(p, a, b) {
  const k = Math.cos(rad(p.lat));
  const px = p.lon * k, py = p.lat, ax = a.lon * k, ay = a.lat, bx = b.lon * k, by = b.lat;
  const dx = bx - ax, dy = by - ay;
  const t = dx || dy ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy))) : 0;
  return dist(p, { lon: (ax + t * dx) / k, lat: ay + t * dy });
}

const round10 = (m) => (m >= 100 ? Math.round(m / 50) * 50 : Math.round(m / 10) * 10);

export class Navigator {
  constructor(app, directions) {
    this.app = app;
    this.dir = directions;
    this.active = false;
    this.muted = false;
    this._bar = null;
  }

  start(route) {
    if (!('geolocation' in navigator)) { announce('הדפדפן אינו תומך בזיהוי מיקום', true); return; }
    this.stop(true);
    this.route = route;
    this.line = route.geometry.coordinates.map(([lon, lat]) => ({ lon, lat }));
    this.idx = 0;
    this.said = { far: -1, near: -1 };
    this.off = 0;
    this.rerouting = false;
    this.active = true;
    this._buildBar();
    this._speakNext(true);
    this._watch = navigator.geolocation.watchPosition((pos) => this._onFix(pos), () => {
      announce('אות המיקום אבד', true);
    }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 });
    navigator.wakeLock?.request('screen').then((l) => { this._lock = l; }).catch(() => {});
  }

  stop(silent = false) {
    if (this._watch != null) navigator.geolocation.clearWatch(this._watch);
    this._watch = null;
    this._lock?.release?.().catch?.(() => {});
    this._lock = null;
    this._bar?.remove();
    this._bar = null;
    window.speechSynthesis?.cancel();
    if (this.active && !silent) announce('הניווט הופסק');
    this.active = false;
    this.app.map.map.easeTo({ pitch: 0, bearing: 0, duration: 600 });
  }

  _speak(text) {
    this._setText(text);
    if (this.muted || !window.speechSynthesis) return;
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'he-IL';
    const v = speechSynthesis.getVoices().find((x) => x.lang?.toLowerCase().startsWith('he'));
    if (v) u.voice = v;
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
  }

  _speakNext(first) {
    const s = this.route.steps[this.idx];
    if (s) this._speak(first ? `מתחילים בניווט. ${s.instruction}` : s.instruction);
  }

  _onFix(pos) {
    if (!this.active || this.rerouting) return;
    const p = { lat: pos.coords.latitude, lon: pos.coords.longitude, accuracy: pos.coords.accuracy };
    this.app.myLocation = p;
    this.app.map.setLocation(p);
    const cam = { center: [p.lon, p.lat], zoom: 17, pitch: 50, duration: 900, essential: true };
    if (pos.coords.heading != null && !Number.isNaN(pos.coords.heading) && (pos.coords.speed ?? 0) > 1) cam.bearing = pos.coords.heading;
    this.app.map.map.easeTo(cam);

    // off route? (3 fixes in a row more than 80 m from the line)
    let min = Infinity;
    for (let i = 1; i < this.line.length; i++) min = Math.min(min, segDist(p, this.line[i - 1], this.line[i]));
    this.off = min > Math.max(80, p.accuracy * 1.5) ? this.off + 1 : 0;
    if (this.off >= 3) return this._reroute(p);

    const steps = this.route.steps;
    const last = steps.length - 1;
    const step = steps[this.idx];
    const loc = step.maneuver?.location;
    if (!loc) return;
    const d = dist(p, { lon: loc[0], lat: loc[1] });
    this._setBar(step, d);
    if (this.idx >= last && d < 30) {
      this._speak('הגעתם ליעד');
      return this.stop(true);
    }
    if (d < 25 && this.idx < last) {
      this.idx += 1;
      this.said = { far: -1, near: -1 };
      return;
    }
    if (d < 45 && this.said.near !== this.idx) {
      this.said.near = this.idx;
      this._speak(step.instruction);
    } else if (d < 300 && d >= 60 && this.said.far !== this.idx && this.idx > 0) {
      this.said.far = this.idx;
      this._speak(`בעוד ${spoken(round10(d))}, ${step.instruction}`);
    }
  }

  _reroute(p) {
    this.rerouting = true;
    this._speak('סטיתם מהמסלול, מחשב מסלול מחדש');
    this.dir.setPoint('from', { lat: p.lat, lon: p.lon, name: 'המיקום שלי', mine: true });
    const t = setInterval(() => {
      if (this.dir.loading) return;
      clearInterval(t);
      if (!this.active) return;
      const r = this.dir.routes?.[this.dir.selected];
      if (r) this.start(r); else { this.rerouting = false; this.off = 0; }
    }, 500);
  }

  // ---- banner -------------------------------------------------------------
  _buildBar() {
    const bar = document.createElement('div');
    bar.className = 'nav-bar';
    bar.setAttribute('role', 'status');
    bar.innerHTML = `<div class="nav-text" aria-live="polite"></div>
      <button type="button" class="btn btn-secondary btn-sm" data-nav="mute" aria-pressed="false">השתקה</button>
      <button type="button" class="btn btn-primary btn-sm" data-nav="stop">סיום</button>`;
    bar.querySelector('[data-nav="stop"]').addEventListener('click', () => this.stop());
    const mute = bar.querySelector('[data-nav="mute"]');
    mute.addEventListener('click', () => {
      this.muted = !this.muted;
      mute.setAttribute('aria-pressed', String(this.muted));
      mute.textContent = this.muted ? 'הפעלת קול' : 'השתקה';
      if (this.muted) window.speechSynthesis?.cancel();
    });
    document.body.append(bar);
    this._bar = bar;
  }

  _setText(t) {
    const el = this._bar?.querySelector('.nav-text');
    if (el) el.textContent = t;
  }

  _setBar(step, d) {
    const el = this._bar?.querySelector('.nav-text');
    if (el) el.innerHTML = `<strong>${esc(fmtDistance(round10(d)))}</strong> ${esc(step.instruction)}`;
  }
}
