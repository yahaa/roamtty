// Edit decision list for public/demo.mp4 (1080x2400, 30 fps screen recording of emulator-5554).
// All times are seconds in the source recording; raw/marks.txt + 2.55 s gives the video time of each scripted tap.

export type Segment = {
  name: string;
  step: number; // 0-based index into copy.steps
  start: number;
  end: number;
  rate: number;
};

export const SEGMENTS: Segment[] = [
  {name: 'Pick agent and host', step: 0, start: 1.6, end: 7.4, rate: 1},
  {name: 'Pick folder, Start', step: 0, start: 7.4, end: 12.6, rate: 1.5},
  {name: 'Connect and launch Claude Code', step: 1, start: 12.6, end: 22.4, rate: 3},
  {name: 'Type the request', step: 1, start: 22.4, end: 27.0, rate: 1},
  {name: 'Agent works', step: 1, start: 27.0, end: 31.0, rate: 2},
  {name: 'Read the answer', step: 1, start: 41.0, end: 44.7, rate: 1},
  {name: 'Home, open Settings', step: 2, start: 44.7, end: 49.2, rate: 1},
  {name: 'Notification shade', step: 2, start: 49.2, end: 53.45, rate: 1},
  {name: 'Back in the session', step: 3, start: 53.45, end: 57.0, rate: 1},
  {name: 'Type follow-up', step: 3, start: 57.0, end: 63.4, rate: 1.5},
  {name: 'Agent replies', step: 3, start: 63.4, end: 66.6, rate: 2},
  {name: 'Read the reply', step: 3, start: 66.6, end: 70.6, rate: 1},
  {name: 'Disconnect all', step: 4, start: 78.6, end: 86.45, rate: 1},
];

// Scripted taps on the 1080x2400 screen.
export const TAPS: {t: number; x: number; y: number}[] = [
  {t: 2.67, x: 1006, y: 346}, // New session
  {t: 4.84, x: 500, y: 1725}, // Claude
  {t: 7.09, x: 500, y: 2070}, // Play review demo host
  {t: 10.24, x: 500, y: 734}, // review-project folder
  {t: 12.4, x: 575, y: 2242}, // Start Claude
  {t: 53.59, x: 540, y: 880}, // notification body
  {t: 56.1, x: 540, y: 1500}, // focus terminal
  {t: 82.18, x: 293, y: 1096}, // Disconnect all
];

export const SWIPES: {t: number}[] = [{t: 49.0}, {t: 78.95}];

// Camera moves on the phone: scale around (ox, oy) in screen pixels, eased in/out over `ease` seconds.
export const ZOOMS: {t0: number; t1: number; scale: number; ox: number; oy: number}[] = [
  {t0: 41.0, t1: 44.7, scale: 1.45, ox: 540, oy: 760},
  {t0: 49.6, t1: 53.3, scale: 1.75, ox: 540, oy: 940},
  {t0: 66.8, t1: 70.6, scale: 1.45, ox: 540, oy: 760},
  {t0: 79.5, t1: 84.3, scale: 1.75, ox: 540, oy: 940},
];

// Peach outlines drawn on the phone screen (screen pixels).
export const HIGHLIGHTS: {t0: number; t1: number; x: number; y: number; w: number; h: number}[] = [
  {t0: 49.8, t1: 53.3, x: 42, y: 705, w: 996, h: 470}, // ongoing notification
  {t0: 79.7, t1: 82.25, x: 130, y: 1030, w: 330, h: 132}, // Disconnect all
];

// Blur the server address in the host picker.
export const MASKS: {t0: number; t1: number; x: number; y: number; w: number; h: number}[] = [
  {t0: 4.5, t1: 7.6, x: 190, y: 2088, w: 600, h: 60},
];

export const FREEZE_SECONDS = 1.6;
export const INTRO_SECONDS = 3.2;
export const OUTRO_SECONDS = 5.5;
