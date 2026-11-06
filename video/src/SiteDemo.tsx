import React from 'react';
import {Video} from '@remotion/media';
import {loadFont as loadInter} from '@remotion/google-fonts/Inter';
import {loadFont as loadNotoSC} from '@remotion/google-fonts/NotoSansSC';
import {AbsoluteFill, Easing, Img, Sequence, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import type {Lang} from './copy';
import {SITE_COPY} from './siteCopy';
import {SITE_INTRO_SECONDS, SITE_OUTRO_SECONDS, SITE_SEGMENTS, SITE_TURN_FRAMES, type SiteSegment} from './siteTimeline';

const {fontFamily: inter} = loadInter('normal', {weights: ['400', '500', '600', '700', '800'], subsets: ['latin']});
const {fontFamily: notoSC} = loadNotoSC('normal', {weights: ['400', '500', '700']});

const C = {bg: '#17181a', fg: '#f3f1f7', muted: '#a29eab', dim: '#6f6b77', accent: '#f0a27e'};
const BEZEL = 12;
const PORTRAIT = {w: 432, h: 960, cx: 1360, cy: 540};
const LANDSCAPE = {w: 1440, h: 648, cx: 960, cy: 680};

const ease = Easing.bezier(0.16, 1, 0.3, 1);
const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

type Planned = SiteSegment & {from: number; len: number};

export const sitePlan = (lang: Lang, fps: number) => {
  const intro = Math.round(SITE_INTRO_SECONDS * fps);
  let at = intro;
  const segs: Planned[] = SITE_SEGMENTS[lang].map((s) => {
    const len = Math.round(((s.end - s.start) / s.rate) * fps);
    const planned = {...s, from: at, len};
    at += len;
    return planned;
  });
  return {intro, segs, outroFrom: at, total: at + Math.round(SITE_OUTRO_SECONDS * fps)};
};

export const siteDuration = (lang: Lang, fps: number) => sitePlan(lang, fps).total;

type Plan = ReturnType<typeof sitePlan>;

const current = (plan: Plan, frame: number) =>
  plan.segs.find((s) => frame >= s.from && frame < s.from + s.len) ?? (frame < plan.intro ? plan.segs[0] : plan.segs[plan.segs.length - 1]);

// Frame the phone last turned at, so the turn plays once per change of orientation.
const turnedAt = (plan: Plan, seg: Planned) => {
  const i = plan.segs.indexOf(seg);
  let j = i;
  while (j > 0 && !!plan.segs[j - 1].landscape === !!seg.landscape) j--;
  return j === 0 ? null : plan.segs[j].from;
};

const Phone: React.FC<{plan: Plan; lang: Lang}> = ({plan, lang}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const seg = current(plan, frame);
  const box = seg.landscape ? LANDSCAPE : PORTRAIT;
  const enter = interpolate(frame, [plan.intro, plan.intro + 16], [0, 1], {...clamp, easing: ease});
  const exit = interpolate(frame, [plan.outroFrom - 4, plan.outroFrom + 14], [1, 0], {...clamp, easing: ease});
  const turned = turnedAt(plan, seg);
  const turn = turned === null ? 1 : interpolate(frame, [turned, turned + SITE_TURN_FRAMES], [0, 1], {...clamp, easing: ease});
  const rotate = (1 - turn) * (seg.landscape ? 90 : -90);
  // Eases in and out over each zoomed segment; neighbouring zoomed segments hold the zoom between them.
  const i = plan.segs.indexOf(seg);
  const prev = plan.segs[i - 1]?.zoom ?? 1;
  const next = plan.segs[i + 1]?.zoom ?? 1;
  const own = seg.zoom ?? 1;
  const zoom = interpolate(frame, [seg.from, seg.from + 14, seg.from + seg.len - 14, seg.from + seg.len], [prev === own ? own : 1, own, own, next === own ? own : 1], {...clamp, easing: Easing.inOut(Easing.cubic)});
  return (
    <div
      style={{
        position: 'absolute',
        left: box.cx - box.w / 2 - BEZEL,
        top: box.cy - box.h / 2 - BEZEL,
        width: box.w + BEZEL * 2,
        height: box.h + BEZEL * 2,
        opacity: enter * exit,
        rotate: `${rotate}deg`,
        scale: String(zoom),
        translate: `0px ${(1 - enter) * 60 + (1 - exit) * -30}px`,
      }}
    >
      <div style={{position: 'absolute', inset: 0, borderRadius: 54, background: '#0b0b0c', boxShadow: '0 40px 90px rgba(0,0,0,0.55), 0 0 0 2px #3a3b40, inset 0 0 0 2px #1d1e21'}} />
      <div style={{position: 'absolute', left: BEZEL, top: BEZEL, width: box.w, height: box.h, borderRadius: 42, overflow: 'hidden', background: '#000'}}>
        {plan.segs.map((s) => (
          <Sequence key={`${s.clip}-${s.start}`} from={s.from} durationInFrames={s.len} layout="none">
            <Video
              src={staticFile(`site/${lang}/${s.clip}.mp4`)}
              trimBefore={Math.round(s.start * fps)}
              playbackRate={s.rate}
              muted
              style={{position: 'absolute', width: box.w, height: box.h}}
            />
          </Sequence>
        ))}
      </div>
    </div>
  );
};

const Caption: React.FC<{plan: Plan; lang: Lang}> = ({plan, lang}) => {
  const frame = useCurrentFrame();
  const copy = SITE_COPY[lang];
  const seg = current(plan, frame);
  const firstOfStep = plan.segs.find((s) => s.step === seg.step)!;
  const inK = interpolate(frame - firstOfStep.from, [0, 16], [0, 1], {...clamp, easing: ease});
  const show = interpolate(frame, [plan.intro, plan.intro + 14, plan.outroFrom - 4, plan.outroFrom + 10], [0, 1, 1, 0], clamp);
  const step = copy.steps[seg.step];
  const zh = lang === 'zh';
  const speed = seg.rate > 1 ? copy.speed(seg.rate) : null;
  if (seg.landscape) {
    return (
      <div style={{position: 'absolute', left: 240, top: 66, width: 1440, opacity: show * inK, translate: `0px ${(1 - inK) * 16}px`}}>
        <div style={{display: 'flex', alignItems: 'baseline', gap: 28}}>
          <div style={{fontSize: 54, fontWeight: 800, color: C.fg, letterSpacing: zh ? 0 : -1, whiteSpace: 'nowrap'}}>{step.title}</div>
          {speed ? <div style={{fontSize: 24, fontWeight: 600, color: C.accent, whiteSpace: 'nowrap'}}>▶▶ {speed}</div> : null}
        </div>
        <div style={{fontSize: 30, color: C.muted, marginTop: 12}}>{step.body}</div>
      </div>
    );
  }
  return (
    <div style={{position: 'absolute', left: 130, top: 0, width: 800, height: 1080, opacity: show}}>
      <div style={{position: 'absolute', top: 92, display: 'flex', alignItems: 'center', gap: 16}}>
        <Img src={staticFile('icon.png')} style={{width: 48, height: 48, borderRadius: 12}} />
        <span style={{fontSize: 30, fontWeight: 700, color: C.fg}}>Roamtty</span>
      </div>
      <div style={{position: 'absolute', top: 300, width: 780, opacity: inK, translate: `0px ${(1 - inK) * 24}px`}}>
        <div style={{fontSize: 24, fontWeight: 700, letterSpacing: 2, color: C.accent}}>
          {seg.step + 1} / {copy.steps.length}
        </div>
        <div style={{fontSize: zh ? 66 : 62, fontWeight: 800, lineHeight: 1.12, color: C.fg, marginTop: 18, letterSpacing: zh ? 0 : -1}}>{step.title}</div>
        <div style={{fontSize: 32, lineHeight: 1.45, color: C.muted, marginTop: 26}}>{step.body}</div>
        {speed ? <div style={{fontSize: 24, fontWeight: 600, color: C.accent, marginTop: 28}}>▶▶ {speed}</div> : null}
      </div>
      <div style={{position: 'absolute', top: 990, fontSize: 19, color: C.dim}}>{copy.footnote}</div>
    </div>
  );
};

const Intro: React.FC<{lang: Lang}> = ({lang}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const copy = SITE_COPY[lang];
  const a = (d: number) => interpolate(frame, [d, d + 18], [0, 1], {...clamp, easing: ease});
  const out = interpolate(frame, [SITE_INTRO_SECONDS * fps - 14, SITE_INTRO_SECONDS * fps], [1, 0], clamp);
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', opacity: out}}>
      <Img src={staticFile('icon.png')} style={{width: 132, height: 132, borderRadius: 32, opacity: a(0), scale: String(0.85 + 0.15 * a(0))}} />
      <div style={{marginTop: 44, fontSize: 76, fontWeight: 800, color: C.fg, letterSpacing: lang === 'zh' ? 0 : -1.5, opacity: a(8), translate: `0px ${(1 - a(8)) * 20}px`}}>{copy.title}</div>
      <div style={{marginTop: 18, fontSize: 34, color: C.muted, opacity: a(16), maxWidth: 1400, textAlign: 'center'}}>{copy.subtitle}</div>
    </AbsoluteFill>
  );
};

const Outro: React.FC<{lang: Lang}> = ({lang}) => {
  const frame = useCurrentFrame();
  const copy = SITE_COPY[lang];
  const a = (d: number) => interpolate(frame, [d, d + 16], [0, 1], {...clamp, easing: ease});
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center'}}>
      <div style={{width: 1240}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 18, opacity: a(4)}}>
          <Img src={staticFile('icon.png')} style={{width: 64, height: 64, borderRadius: 16}} />
          <span style={{fontSize: 36, fontWeight: 700, color: C.fg}}>Roamtty</span>
        </div>
        <div style={{marginTop: 34, fontSize: 64, fontWeight: 800, lineHeight: 1.12, color: C.fg, letterSpacing: lang === 'zh' ? 0 : -1, opacity: a(8)}}>{copy.outroTitle}</div>
        <div style={{marginTop: 40, display: 'flex', flexDirection: 'column', gap: 20}}>
          {copy.outroBullets.map((b, i) => (
            <div key={b} style={{display: 'flex', gap: 20, alignItems: 'baseline', fontSize: 34, color: C.fg, opacity: a(16 + i * 6), translate: `${(1 - a(16 + i * 6)) * 24}px 0px`}}>
              <span style={{color: C.accent, fontWeight: 800}}>✓</span>
              {b}
            </div>
          ))}
        </div>
        <div style={{marginTop: 50, fontSize: 28, color: C.muted, opacity: a(36)}}>roamtty.com</div>
      </div>
    </AbsoluteFill>
  );
};

export const SiteDemo: React.FC<{lang: Lang}> = ({lang}) => {
  const {fps} = useVideoConfig();
  const plan = sitePlan(lang, fps);
  return (
    <AbsoluteFill style={{background: `radial-gradient(1100px 800px at 72% 45%, rgba(240,162,126,0.10), transparent 70%), ${C.bg}`, fontFamily: lang === 'zh' ? `${inter}, ${notoSC}` : inter}}>
      <Sequence name="Intro" durationInFrames={plan.intro + 2}>
        <Intro lang={lang} />
      </Sequence>
      <Caption plan={plan} lang={lang} />
      <Phone plan={plan} lang={lang} />
      <Sequence name="Outro" from={plan.outroFrom}>
        <Outro lang={lang} />
      </Sequence>
    </AbsoluteFill>
  );
};
