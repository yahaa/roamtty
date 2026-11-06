// Edit list for the website demo: real screen recordings of emulator-5554, one folder per language
// (public/site/<lang>/<clip>.mp4, see README). Times are seconds in that clip. The takes differ, so each language has its own list.
import type {Lang} from './copy';

export type SiteSegment = {
  clip: string;
  step: number; // 0-based index into SITE_COPY[lang].steps
  start: number;
  end: number;
  rate: number;
  landscape?: boolean;
  zoom?: number; // Portrait only: enlarges the whole phone around its centre, where the desktop picture sits.
};

// Each segment must end before its clip does; the render shows black otherwise.
export const SITE_SEGMENTS: Record<Lang, SiteSegment[]> = {
  en: [
    {clip: '01-list', step: 0, start: 0.5, end: 21, rate: 3},
    {clip: '02-agent', step: 1, start: 1.8, end: 14.2, rate: 1.5},
    {clip: '02-agent', step: 2, start: 14.2, end: 15.6, rate: 1},
    {clip: '02-agent', step: 2, start: 34, end: 50.8, rate: 1.4},
    {clip: '02-agent', step: 3, start: 50.8, end: 84.5, rate: 3.5},
    {clip: '03-desktop', step: 4, start: 1.5, end: 9.2, rate: 1},
    {clip: '03-desktop', step: 5, start: 9.2, end: 33, rate: 3, zoom: 1.6},
    {clip: '03-desktop', step: 6, start: 33, end: 55.5, rate: 1.8, zoom: 1.2},
    {clip: '04-landscape', step: 7, start: 0.5, end: 45.5, rate: 3.5, landscape: true},
    {clip: '05-terminal-land', step: 7, start: 1.2, end: 8.2, rate: 1, landscape: true},
    {clip: '06-windows', step: 8, start: 0.8, end: 31.8, rate: 2.6, landscape: true},
  ],
  zh: [
    {clip: '01-list', step: 0, start: 0.5, end: 21.2, rate: 3},
    {clip: '02-agent', step: 1, start: 1.8, end: 12.3, rate: 1.5},
    {clip: '02-agent', step: 2, start: 12.3, end: 13.7, rate: 1},
    {clip: '02-agent', step: 2, start: 28, end: 44.2, rate: 1.4},
    {clip: '02-agent', step: 3, start: 44.2, end: 92.8, rate: 5},
    {clip: '03-desktop', step: 4, start: 1.5, end: 9.2, rate: 1},
    {clip: '03-desktop', step: 5, start: 9.2, end: 33, rate: 3, zoom: 1.6},
    {clip: '03-desktop', step: 6, start: 33, end: 55.2, rate: 1.8, zoom: 1.2},
    {clip: '04-landscape', step: 7, start: 0.5, end: 45.7, rate: 3.5, landscape: true},
    {clip: '05-terminal-land', step: 7, start: 1.2, end: 8.2, rate: 1, landscape: true},
    {clip: '06-windows', step: 8, start: 0.8, end: 33.4, rate: 2.6, landscape: true},
  ],
};

export const SITE_INTRO_SECONDS = 3;
export const SITE_OUTRO_SECONDS = 5;
export const SITE_TURN_FRAMES = 20;
