/**
 * Lightweight Web Audio API Sound Effects Engine for OTTV2
 * Zero external audio files required, zero latency, highly responsive,
 * with volume throttling and full browser autoplay policy compliance.
 */

let audioCtx = null;
let lastSoundTime = 0;

function getAudioContext() {
  if (typeof window === 'undefined') return null;
  const AudioContextClass = window.AudioContext || window.webkitAudioContext;
  if (!AudioContextClass) return null;

  if (!audioCtx) {
    audioCtx = new AudioContextClass();
  }
  if (audioCtx.state === 'suspended') {
    audioCtx.resume().catch(() => {});
  }
  return audioCtx;
}

/**
 * Audio Mute State Management (persisted in localStorage)
 */
const STORAGE_KEY = 'ottv2_audio_enabled';

export function isAudioEnabled() {
  if (typeof window === 'undefined') return true;
  try {
    const val = localStorage.getItem(STORAGE_KEY);
    return val === null ? true : val === 'true';
  } catch {
    return true;
  }
}

export function setAudioEnabled(enabled) {
  if (typeof window === 'undefined') return;
  try {
    localStorage.setItem(STORAGE_KEY, enabled ? 'true' : 'false');
    window.dispatchEvent(new CustomEvent('ottv2_audio_toggle', { detail: { enabled } }));
  } catch {
    // ignore
  }
}

export function toggleAudio() {
  const current = isAudioEnabled();
  const next = !current;
  setAudioEnabled(next);
  if (next) {
    getAudioContext();
    playUiSound();
  }
  return next;
}

/**
 * 1. Valid Move Sound (Tiếng gõ quân cờ nhẹ, tinh tế kiểu Chess.com)
 * A soft wooden tap: decaying sine oscillator with a subtle transient bandpass click.
 */
export function playMoveSound() {
  if (!isAudioEnabled()) return;
  const ctx = getAudioContext();
  if (!ctx) return;

  const now = ctx.currentTime;
  // Throttle if fired within 35ms
  if (now - lastSoundTime < 0.035) return;
  lastSoundTime = now;

  try {
    // Osc 1: Subtle wooden pitch sweep (520Hz down to 220Hz)
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'sine';
    osc.frequency.setValueAtTime(520, now);
    osc.frequency.exponentialRampToValueAtTime(220, now + 0.04);

    gain.gain.setValueAtTime(0.22, now);
    gain.gain.exponentialRampToValueAtTime(0.001, now + 0.045);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start(now);
    osc.stop(now + 0.05);

    // Subtle click transient (white noise burst)
    const bufferSize = Math.floor(ctx.sampleRate * 0.008); // 8ms noise
    const noiseBuffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
    const output = noiseBuffer.getChannelData(0);
    for (let i = 0; i < bufferSize; i += 1) {
      output[i] = Math.random() * 2 - 1;
    }

    const whiteNoise = ctx.createBufferSource();
    whiteNoise.buffer = noiseBuffer;

    const filter = ctx.createBiquadFilter();
    filter.type = 'bandpass';
    filter.frequency.setValueAtTime(1400, now);
    filter.Q.setValueAtTime(3, now);

    const noiseGain = ctx.createGain();
    noiseGain.gain.setValueAtTime(0.08, now);
    noiseGain.gain.exponentialRampToValueAtTime(0.001, now + 0.01);

    whiteNoise.connect(filter);
    filter.connect(noiseGain);
    noiseGain.connect(ctx.destination);

    whiteNoise.start(now);
  } catch {
    // ignore
  }
}

/**
 * 2. Capture Sound (Tiếng ăn quân dứt khoát, trầm và uy lực)
 * Distinct impact tone with snappy attack.
 */
export function playCaptureSound() {
  if (!isAudioEnabled()) return;
  const ctx = getAudioContext();
  if (!ctx) return;

  const now = ctx.currentTime;
  if (now - lastSoundTime < 0.04) return;
  lastSoundTime = now;

  try {
    // Triangle wave sweep for punchy body (320Hz down to 100Hz)
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'triangle';
    osc.frequency.setValueAtTime(320, now);
    osc.frequency.exponentialRampToValueAtTime(100, now + 0.08);

    gain.gain.setValueAtTime(0.32, now);
    gain.gain.exponentialRampToValueAtTime(0.001, now + 0.09);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start(now);
    osc.stop(now + 0.1);

    // Secondary sub-thud (160Hz down to 70Hz)
    const subOsc = ctx.createOscillator();
    const subGain = ctx.createGain();

    subOsc.type = 'sine';
    subOsc.frequency.setValueAtTime(160, now);
    subOsc.frequency.exponentialRampToValueAtTime(70, now + 0.08);

    subGain.gain.setValueAtTime(0.24, now);
    subGain.gain.exponentialRampToValueAtTime(0.001, now + 0.085);

    subOsc.connect(subGain);
    subGain.connect(ctx.destination);

    subOsc.start(now);
    subOsc.stop(now + 0.09);
  } catch {
    // ignore
  }
}

/**
 * 3. Victory Sound (Chuông hợp âm chiến thắng nhẹ nhàng)
 * Gentle ascending arpeggio: C5 (523Hz) -> E5 (659Hz) -> G5 (784Hz)
 */
export function playVictorySound() {
  if (!isAudioEnabled()) return;
  const ctx = getAudioContext();
  if (!ctx) return;

  const now = ctx.currentTime;
  const notes = [
    { freq: 523.25, time: 0.0, dur: 0.2 },
    { freq: 659.25, time: 0.09, dur: 0.22 },
    { freq: 783.99, time: 0.18, dur: 0.38 },
  ];

  try {
    notes.forEach(({ freq, time, dur }) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now + time);

      gain.gain.setValueAtTime(0.001, now + time);
      gain.gain.linearRampToValueAtTime(0.18, now + time + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.001, now + time + dur);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now + time);
      osc.stop(now + time + dur + 0.02);
    });
  } catch {
    // ignore
  }
}

/**
 * 4. Defeat Sound (Âm trầm kết thúc nhẹ nhàng)
 * Gentle descending cadence: G4 (392Hz) -> E4 (330Hz) -> C4 (261Hz)
 */
export function playDefeatSound() {
  if (!isAudioEnabled()) return;
  const ctx = getAudioContext();
  if (!ctx) return;

  const now = ctx.currentTime;
  const notes = [
    { freq: 392.0, time: 0.0, dur: 0.18 },
    { freq: 329.63, time: 0.1, dur: 0.2 },
    { freq: 261.63, time: 0.2, dur: 0.35 },
  ];

  try {
    notes.forEach(({ freq, time, dur }) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now + time);

      gain.gain.setValueAtTime(0.001, now + time);
      gain.gain.linearRampToValueAtTime(0.16, now + time + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.001, now + time + dur);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now + time);
      osc.stop(now + time + dur + 0.02);
    });
  } catch {
    // ignore
  }
}

/**
 * 5. Draw Sound (Hợp âm kết thúc hòa)
 */
export function playDrawSound() {
  if (!isAudioEnabled()) return;
  const ctx = getAudioContext();
  if (!ctx) return;

  const now = ctx.currentTime;
  try {
    [440, 554.37].forEach((freq) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now);

      gain.gain.setValueAtTime(0.001, now);
      gain.gain.linearRampToValueAtTime(0.14, now + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.3);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now);
      osc.stop(now + 0.32);
    });
  } catch {
    // ignore
  }
}

/**
 * 6. UI Button Click
 */
export function playUiSound() {
  if (!isAudioEnabled()) return;
  const ctx = getAudioContext();
  if (!ctx) return;

  const now = ctx.currentTime;
  try {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'sine';
    osc.frequency.setValueAtTime(950, now);
    osc.frequency.exponentialRampToValueAtTime(500, now + 0.015);

    gain.gain.setValueAtTime(0.08, now);
    gain.gain.exponentialRampToValueAtTime(0.001, now + 0.018);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start(now);
    osc.stop(now + 0.02);
  } catch {
    // ignore
  }
}
