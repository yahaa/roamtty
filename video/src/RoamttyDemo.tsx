import React from 'react';
import {Video} from '@remotion/media';
import {loadFont as loadInter} from '@remotion/google-fonts/Inter';
import {loadFont as loadNotoSC} from '@remotion/google-fonts/NotoSansSC';
import {
  AbsoluteFill,
  Easing,
  Img,
  Sequence,
  interpolate,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';
import {COPY, type Lang, type Variant} from './copy';
import {
  FREEZE_SECONDS,
  HIGHLIGHTS,
  INTRO_SECONDS,
  MASKS,
  OUTRO_SECONDS,
  SEGMENTS,
  SWIPES,
  TAPS,
  ZOOMS,
} from './timeline';

const {fontFamily: inter} = loadInter('normal', {weights: ['400', '500', '600', '700', '800'], subsets: ['latin']});
const {fontFamily: notoSC} = loadNotoSC('normal', {weights: ['400', '500', '700']});

export type DemoProps = {variant: Variant; lang: Lang};

const C = {
  bg: '#17181a',
  card: '#222326',
  fg: '#f3f1f7',
  muted: '#a29eab',
  dim: '#6f6b77',
  accent: '#f0a27e',
  rule: '#34353a',
};

const SRC_W = 1080;
const SRC_H = 2400;
const SCREEN_H = 960;
const S = SCREEN_H / SRC_H; // source px -> screen px on the composition
const SCREEN_W = SRC_W * S;
const BEZEL = 12;
const PHONE_CX = 1360;
const PHONE_CY = 540;

const ease = Easing.bezier(0.16, 1, 0.3, 1);
const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

// Frame ranges of every part of the video, computed once from the edit list.
export const buildPlan = (fps: number) => {
  const intro = Math.round(INTRO_SECONDS * fps);
  let at = intro;
  const segs = SEGMENTS.map((s) => {
    const len = Math.round(((s.end - s.start) / s.rate) * fps);
    const r = {...s, from: at, len};
    at += len;
    return r;
  });
  const freezeFrom = at;
  const freeze = Math.round(FREEZE_SECONDS * fps);
  const outroFrom = freezeFrom + freeze;
  const outro = Math.round(OUTRO_SECONDS * fps);
  return {intro, segs, freezeFrom, freeze, outroFrom, outro, total: outroFrom + outro};
};

export const demoDuration = (fps: number) => buildPlan(fps).total;

// Source-recording time shown at a composition frame (clamped to the edit list).
const sourceTimeAt = (frame: number, plan: ReturnType<typeof buildPlan>, fps: number) => {
  for (const s of plan.segs) {
    if (frame < s.from + s.len) return s.start + (Math.max(0, frame - s.from) / fps) * s.rate;
  }
  return SEGMENTS[SEGMENTS.length - 1].end;
};

const zoomAt = (t: number) => {
  const easeS = 0.45;
  for (const z of ZOOMS) {
    if (t < z.t0 - easeS || t > z.t1 + easeS) continue;
    const k =
      t < z.t0 ? interpolate(t, [z.t0 - easeS, z.t0], [0, 1], {...clamp, easing: Easing.inOut(Easing.cubic)})
      : t > z.t1 ? interpolate(t, [z.t1, z.t1 + easeS], [1, 0], {...clamp, easing: Easing.inOut(Easing.cubic)})
      : 1;
    return {scale: 1 + (z.scale - 1) * k, ox: z.ox, oy: z.oy, k};
  }
  return {scale: 1, ox: SRC_W / 2, oy: SRC_H / 2, k: 0};
};

const Overlays: React.FC<{t: number}> = ({t}) => (
  <>
    {MASKS.filter((m) => t >= m.t0 && t <= m.t1).map((m, i) => (
      <div
        key={`m${i}`}
        style={{
          position: 'absolute',
          left: m.x * S,
          top: m.y * S,
          width: m.w * S,
          height: m.h * S,
          borderRadius: 6,
          backdropFilter: 'blur(7px)',
          WebkitBackdropFilter: 'blur(7px)',
          background: 'rgba(60,60,64,0.35)',
        }}
      />
    ))}
    {HIGHLIGHTS.map((h, i) => {
      const o = interpolate(t, [h.t0, h.t0 + 0.3, h.t1 - 0.2, h.t1], [0, 1, 1, 0], clamp);
      if (o <= 0) return null;
      return (
        <div
          key={`h${i}`}
          style={{
            position: 'absolute',
            left: h.x * S - 4,
            top: h.y * S - 4,
            width: h.w * S + 8,
            height: h.h * S + 8,
            borderRadius: 14,
            border: `3px solid ${C.accent}`,
            boxShadow: `0 0 0 6px rgba(240,162,126,0.18)`,
            opacity: o,
          }}
        />
      );
    })}
    {TAPS.map((p, i) => {
      const d = t - p.t;
      if (d < -0.18 || d > 0.55) return null;
      const press = interpolate(d, [-0.18, 0], [0.6, 1], clamp);
      const ring = interpolate(d, [0, 0.55], [1, 2.4], {...clamp, easing: ease});
      const o = interpolate(d, [-0.18, -0.05, 0.25, 0.55], [0, 1, 0.8, 0], clamp);
      const r = 26;
      return (
        <React.Fragment key={`t${i}`}>
          <div
            style={{
              position: 'absolute',
              left: p.x * S - r,
              top: p.y * S - r,
              width: r * 2,
              height: r * 2,
              borderRadius: '50%',
              background: 'rgba(255,255,255,0.55)',
              border: '2px solid rgba(255,255,255,0.9)',
              scale: String(press),
              opacity: o,
            }}
          />
          <div
            style={{
              position: 'absolute',
              left: p.x * S - r,
              top: p.y * S - r,
              width: r * 2,
              height: r * 2,
              borderRadius: '50%',
              border: `3px solid ${C.accent}`,
              scale: String(ring),
              opacity: d > 0 ? o : 0,
            }}
          />
        </React.Fragment>
      );
    })}
    {SWIPES.map((s, i) => {
      const d = t - s.t;
      if (d < -0.1 || d > 0.75) return null;
      const y = interpolate(d, [0, 0.45], [30, 1450], {...clamp, easing: Easing.inOut(Easing.quad)});
      const o = interpolate(d, [-0.1, 0, 0.5, 0.75], [0, 1, 1, 0], clamp);
      return (
        <div
          key={`s${i}`}
          style={{
            position: 'absolute',
            left: 540 * S - 24,
            top: y * S - 24,
            width: 48,
            height: 48,
            borderRadius: '50%',
            background: 'rgba(255,255,255,0.55)',
            border: '2px solid rgba(255,255,255,0.9)',
            boxShadow: '0 -40px 30px -10px rgba(255,255,255,0.18)',
            opacity: o,
          }}
        />
      );
    })}
  </>
);

const Phone: React.FC<{plan: ReturnType<typeof buildPlan>}> = ({plan}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const t = sourceTimeAt(frame, plan, fps);
  const z = zoomAt(t);
  const enter = interpolate(frame, [plan.intro, plan.intro + 16], [0, 1], {...clamp, easing: ease});
  const exit = interpolate(frame, [plan.outroFrom - 4, plan.outroFrom + 14], [1, 0], {...clamp, easing: ease});
  const ox = z.ox * S + BEZEL;
  const oy = z.oy * S + BEZEL;
  // Move the zoom origin toward the middle of the phone so the zoomed detail sits near the frame center.
  const shiftY = (SCREEN_H / 2 - z.oy * S) * z.k * 0.9;
  return (
    <div
      style={{
        position: 'absolute',
        left: PHONE_CX - SCREEN_W / 2 - BEZEL,
        top: PHONE_CY - SCREEN_H / 2 - BEZEL,
        width: SCREEN_W + BEZEL * 2,
        height: SCREEN_H + BEZEL * 2,
        opacity: enter * exit,
        translate: `0px ${(1 - enter) * 60 + (1 - exit) * -30 + shiftY}px`,
        scale: String(z.scale),
        transformOrigin: `${ox}px ${oy}px`,
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: 54,
          background: '#0b0b0c',
          boxShadow: '0 40px 90px rgba(0,0,0,0.55), 0 0 0 2px #3a3b40, inset 0 0 0 2px #1d1e21',
        }}
      />
      <div
        style={{
          position: 'absolute',
          left: BEZEL,
          top: BEZEL,
          width: SCREEN_W,
          height: SCREEN_H,
          borderRadius: 42,
          overflow: 'hidden',
          background: '#000',
        }}
      >
        {plan.segs.map((s) => (
          <Sequence key={s.name} name={s.name} from={s.from} durationInFrames={s.len} layout="none">
            <Video
              src={staticFile('demo.mp4')}
              trimBefore={Math.round(s.start * fps)}
              playbackRate={s.rate}
              muted
              style={{position: 'absolute', width: SCREEN_W, height: SCREEN_H}}
            />
          </Sequence>
        ))}
        <Sequence name="Freeze" from={plan.freezeFrom} layout="none">
          <Img src={staticFile('last.png')} style={{position: 'absolute', width: SCREEN_W, height: SCREEN_H}} />
        </Sequence>
        <Overlays t={t} />
      </div>
    </div>
  );
};

const SpeedBadge: React.FC<{plan: ReturnType<typeof buildPlan>; label: (r: number) => string}> = ({plan, label}) => {
  const frame = useCurrentFrame();
  const seg = plan.segs.find((s) => frame >= s.from && frame < s.from + s.len);
  if (!seg || seg.rate === 1) return null;
  const o = interpolate(frame, [seg.from, seg.from + 6, seg.from + seg.len - 6, seg.from + seg.len], [0, 1, 1, 0], clamp);
  return (
    <div
      style={{
        position: 'absolute',
        left: PHONE_CX + SCREEN_W / 2 + 40,
        top: PHONE_CY - SCREEN_H / 2 + 10,
        padding: '10px 20px',
        borderRadius: 999,
        background: 'rgba(240,162,126,0.14)',
        color: C.accent,
        fontSize: 26,
        fontWeight: 600,
        opacity: o,
        display: 'flex',
        alignItems: 'center',
        gap: 10,
      }}
    >
      <span style={{letterSpacing: -4}}>▶▶</span>
      {label(seg.rate)}
    </div>
  );
};

const StepText: React.FC<{plan: ReturnType<typeof buildPlan>; variant: Variant; lang: Lang}> = ({plan, variant, lang}) => {
  const frame = useCurrentFrame();
  const copy = COPY[variant][lang];
  const firsts = copy.steps.map((_, i) => plan.segs.find((s) => s.step === i)!.from);
  const current = Math.max(0, firsts.filter((f) => frame >= f).length - 1);
  const since = frame - firsts[current];
  const show = interpolate(frame, [plan.intro, plan.intro + 14, plan.outroFrom - 4, plan.outroFrom + 10], [0, 1, 1, 0], clamp);
  const inK = interpolate(since, [0, 16], [0, 1], {...clamp, easing: ease});
  const step = copy.steps[current];
  const zh = lang === 'zh';
  return (
    <div style={{position: 'absolute', left: 130, top: 0, width: 760, height: 1080, opacity: show}}>
      <div style={{position: 'absolute', top: 92, display: 'flex', alignItems: 'center', gap: 16}}>
        <Img src={staticFile('icon.png')} style={{width: 48, height: 48, borderRadius: 12}} />
        <span style={{fontSize: 30, fontWeight: 700, color: C.fg}}>Roamtty</span>
      </div>
      <div style={{position: 'absolute', top: 250, width: 740, opacity: inK, translate: `0px ${(1 - inK) * 24}px`}}>
        <div style={{fontSize: 24, fontWeight: 700, letterSpacing: zh ? 2 : 3, color: C.accent}}>
          {copy.stepWord} {current + 1} / {copy.steps.length}
        </div>
        <div style={{fontSize: zh ? 66 : 64, fontWeight: 800, lineHeight: 1.12, color: C.fg, marginTop: 18, letterSpacing: zh ? 0 : -1}}>
          {step.title}
        </div>
        <div style={{fontSize: 32, lineHeight: 1.45, color: C.muted, marginTop: 26}}>{step.body}</div>
      </div>
      <div style={{position: 'absolute', top: 700, display: 'flex', flexDirection: 'column', gap: 14}}>
        {copy.steps.map((s, i) => {
          const done = i < current;
          const on = i === current;
          return (
            <div key={s.label} style={{display: 'flex', alignItems: 'center', gap: 16, fontSize: 25, color: on ? C.fg : done ? C.muted : C.dim, fontWeight: on ? 600 : 400}}>
              <div
                style={{
                  width: 30,
                  height: 30,
                  borderRadius: '50%',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 16,
                  fontWeight: 700,
                  background: on ? C.accent : done ? 'rgba(240,162,126,0.18)' : 'transparent',
                  color: on ? '#2a1a12' : done ? C.accent : C.dim,
                  border: on || done ? 'none' : `2px solid ${C.rule}`,
                }}
              >
                {done ? '✓' : i + 1}
              </div>
              {s.label}
            </div>
          );
        })}
      </div>
      <div style={{position: 'absolute', top: 990, fontSize: 19, color: C.dim}}>{copy.footnote}</div>
    </div>
  );
};

const Intro: React.FC<{variant: Variant; lang: Lang}> = ({variant, lang}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const copy = COPY[variant][lang];
  const a = (d: number) => interpolate(frame, [d, d + 18], [0, 1], {...clamp, easing: ease});
  const out = interpolate(frame, [INTRO_SECONDS * fps - 14, INTRO_SECONDS * fps], [1, 0], clamp);
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', opacity: out}}>
      <Img src={staticFile('icon.png')} style={{width: 132, height: 132, borderRadius: 32, opacity: a(0), scale: String(0.85 + 0.15 * a(0))}} />
      <div style={{marginTop: 34, fontSize: 24, fontWeight: 700, letterSpacing: 4, color: C.accent, opacity: a(6)}}>{copy.eyebrow}</div>
      <div style={{marginTop: 14, fontSize: 84, fontWeight: 800, color: C.fg, letterSpacing: lang === 'zh' ? 0 : -1.5, opacity: a(10), translate: `0px ${(1 - a(10)) * 20}px`}}>
        {copy.title}
      </div>
      <div style={{marginTop: 18, fontSize: 34, color: C.muted, opacity: a(18), maxWidth: 1300, textAlign: 'center'}}>{copy.subtitle}</div>
    </AbsoluteFill>
  );
};

const Outro: React.FC<{variant: Variant; lang: Lang}> = ({variant, lang}) => {
  const frame = useCurrentFrame();
  const copy = COPY[variant][lang];
  const a = (d: number) => interpolate(frame, [d, d + 16], [0, 1], {...clamp, easing: ease});
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center'}}>
      <div style={{width: 1240}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 18, opacity: a(4)}}>
          <Img src={staticFile('icon.png')} style={{width: 64, height: 64, borderRadius: 16}} />
          <span style={{fontSize: 36, fontWeight: 700, color: C.fg}}>Roamtty</span>
        </div>
        <div style={{marginTop: 34, fontSize: 68, fontWeight: 800, lineHeight: 1.12, color: C.fg, letterSpacing: lang === 'zh' ? 0 : -1, opacity: a(8)}}>
          {copy.outroTitle}
        </div>
        <div style={{marginTop: 40, display: 'flex', flexDirection: 'column', gap: 22}}>
          {copy.outroBullets.map((b, i) => (
            <div key={b} style={{display: 'flex', gap: 20, alignItems: 'baseline', fontSize: 36, color: C.fg, opacity: a(16 + i * 6), translate: `${(1 - a(16 + i * 6)) * 24}px 0px`}}>
              <span style={{color: C.accent, fontWeight: 800}}>✓</span>
              {b}
            </div>
          ))}
        </div>
        <div style={{marginTop: 54, fontSize: 28, color: C.muted, opacity: a(36)}}>{copy.outroFooter}</div>
      </div>
    </AbsoluteFill>
  );
};

export const RoamttyDemo: React.FC<DemoProps> = ({variant, lang}) => {
  const {fps} = useVideoConfig();
  const plan = buildPlan(fps);
  const copy = COPY[variant][lang];
  return (
    <AbsoluteFill
      style={{
        background: `radial-gradient(1100px 800px at 72% 45%, rgba(240,162,126,0.10), transparent 70%), ${C.bg}`,
        fontFamily: lang === 'zh' ? `${inter}, ${notoSC}` : inter,
      }}
    >
      <Sequence name="Intro" durationInFrames={plan.intro + 2}>
        <Intro variant={variant} lang={lang} />
      </Sequence>
      <StepText plan={plan} variant={variant} lang={lang} />
      <Phone plan={plan} />
      <SpeedBadge plan={plan} label={copy.speed} />
      <Sequence name="Outro" from={plan.outroFrom}>
        <Outro variant={variant} lang={lang} />
      </Sequence>
    </AbsoluteFill>
  );
};
