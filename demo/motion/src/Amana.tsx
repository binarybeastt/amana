// Amana: the hackathon film (1920x1080, docs/DEMO.md). Problem → why → the solution, demoed on real
// recordings → how it holds up → where it goes. Black, white and Inter; green only for settled/safe.
import React from "react";
import {
  AbsoluteFill, Audio, Easing, Img, OffthreadVideo, Sequence, interpolate, spring, staticFile, useCurrentFrame,
} from "remotion";
import { TransitionSeries, linearTiming } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { TX_HEX } from "./txhex";
import { CHEAT } from "./cheat";

const FPS = 30;
const BG = "#050505";
const TEXT = "#F2F2F7";
const DIM = "#8E8E93";
const FAINT = "#5A5A5F";
const LINE = "#2A2A2D";
const GREEN = "#30D158";
const RED = "#FF453A";
const FONT = "Inter, sans-serif";

const out = Easing.bezier(0.16, 1, 0.3, 1);
const inOut = Easing.bezier(0.65, 0, 0.35, 1);
const cam = Easing.bezier(0.45, 0, 0.15, 1);
const k = (f: number, a: number, b: number, from = 0, to = 1, e = out) =>
  interpolate(f, [a, b], [from, to], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: e });
const sec = (s: number) => Math.round(s * FPS);

const Sfx: React.FC<{ at: number; src: string; volume?: number }> = ({ at, src, volume = 0.6 }) => (
  <Sequence from={Math.max(0, at)} layout="none"><Audio src={staticFile(src)} volume={volume} /></Sequence>
);

// ---------------------------------------------------------------- type

/**
 * Lines that rise out of a mask, one after another, then leave together. `lines` are separate
 * strings (no word-by-word animation). Visible from `at` until `until`.
 */
const Lines: React.FC<{
  lines: string[]; at?: number; until?: number; size?: number; weight?: number; color?: string;
  lead?: number; stagger?: number; style?: React.CSSProperties; spans?: Record<string, string>;
}> = ({ lines, at = 0, until = 1e9, size = 64, weight = 600, color = TEXT, lead = 1.12, stagger = 5, style, spans = {} }) => {
  const f = useCurrentFrame();
  if (f < at || f > until + 14) return null;
  const leave = k(f, until, until + 12, 0, 1, inOut);
  return (
    <div style={{ fontFamily: FONT, fontSize: size, fontWeight: weight, color, lineHeight: lead,
      letterSpacing: -size * 0.022, opacity: 1 - leave, transform: `translateY(${-leave * 14}px)`, ...style }}>
      {lines.map((l, i) => {
        const p = k(f, at + i * stagger, at + i * stagger + 24);
        return (
          <div key={i} style={{ overflow: "hidden", paddingBottom: size * 0.12, marginBottom: -size * 0.12 }}>
            <div style={{ transform: `translateY(${(1 - p) * 105}%)`, opacity: Math.min(1, p * 2) }}>
              {Object.keys(spans).length
                ? l.split(/(\{[^}]+\})/).map((part, j) => {
                  const m = part.match(/^\{([^}]+)\}$/);
                  return m ? <span key={j} style={{ color: spans[m[1]] ?? color }}>{m[1]}</span> : <span key={j}>{part}</span>;
                })
                : l}
            </div>
          </div>
        );
      })}
    </div>
  );
};

/** Small uppercase label, e.g. the chapter or the speaker. */
const Label: React.FC<{ text: string; at?: number; until?: number; color?: string; style?: React.CSSProperties }> = ({
  text, at = 0, until = 1e9, color = DIM, style,
}) => {
  const f = useCurrentFrame();
  const a = k(f, at, at + 18) * (1 - k(f, until, until + 10));
  return (
    <div style={{ fontFamily: FONT, fontSize: 20, fontWeight: 600, letterSpacing: 3.2, textTransform: "uppercase",
      color, opacity: a, transform: `translateX(${(1 - a) * -10}px)`, ...style }}>{text}</div>
  );
};

/** A source line, bottom left. */
const Source: React.FC<{ text: string; at?: number; until?: number; left?: number; bottom?: number }> = ({ text, at = 0, until = 1e9, left = 140, bottom = 70 }) => {
  const f = useCurrentFrame();
  const a = k(f, at, at + 20) * (1 - k(f, until, until + 10));
  return (
    <div style={{ position: "absolute", left, right: 80, bottom, fontFamily: FONT, fontSize: 19, fontWeight: 500,
      color: FAINT, letterSpacing: 0.2, lineHeight: 1.45, opacity: a }}>Source: {text}</div>
  );
};

/** A number that counts up once and then holds still. */
const Count: React.FC<{ to: number; at: number; dur?: number; decimals?: number; size?: number; suffix?: string }> = ({
  to, at, dur = 26, decimals = 0, size = 220, suffix = "",
}) => {
  const f = useCurrentFrame();
  const p = k(f, at, at + dur, 0, 1, Easing.bezier(0.2, 0.7, 0.2, 1));
  const v = (to * p).toFixed(decimals);
  const a = k(f, at, at + 10);
  return (
    <div style={{ fontFamily: FONT, fontSize: size, fontWeight: 650, color: TEXT, letterSpacing: -size * 0.045,
      lineHeight: 0.92, fontVariantNumeric: "tabular-nums", opacity: a }}>
      {v}<span style={{ fontWeight: 500, color: DIM, letterSpacing: -size * 0.02 }}>{suffix}</span>
    </div>
  );
};

// ---------------------------------------------------------------- the mark

const ARCH = "M27 82V50a23 23 0 0 1 46 0v32";
const ARCH_LEN = 32 + Math.PI * 23 + 32;

/** The Amana mark: the arch strokes in, then the green dot drops into it and settles. */
const Mark: React.FC<{ size: number; at?: number; still?: boolean }> = ({ size, at = 0, still }) => {
  const f = useCurrentFrame();
  const draw = still ? 1 : k(f, at, at + 30, 0, 1, inOut);
  const drop = still ? 1 : spring({ frame: f - at - 24, fps: FPS, config: { damping: 13, mass: 0.7, stiffness: 140 } });
  const dotY = 64 - (1 - drop) * 46;
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" style={{ overflow: "visible" }}>
      <path d={ARCH} fill="none" stroke={TEXT} strokeWidth={11} strokeDasharray={ARCH_LEN}
        strokeDashoffset={ARCH_LEN * (1 - draw)} />
      <circle cx={50} cy={dotY} r={8} fill={GREEN} opacity={still ? 1 : k(f, at + 22, at + 28)} />
    </svg>
  );
};

// ---------------------------------------------------------------- real screens

type Seg = { start: number; end: number; rate?: number }; // clip seconds
type Zoom = { f: number; x: number; y: number; s: number }; // film frame (in the shot), focus 0..1, scale
type Dev = { w: number; h: number };
const EMU: Dev = { w: 720, h: 1280 };
const PHONE: Dev = { w: 1080, h: 2400 };
const segFrames = (s: Seg) => Math.round(((s.end - s.start) / (s.rate ?? 1)) * FPS);

/** Film frame (within the shot) of clip time t, or null when t falls in a cut. */
const clipToFrame = (segs: Seg[], t: number) => {
  let at = 0;
  for (const s of segs) {
    if (t >= s.start && t <= s.end) return at + Math.round(((t - s.start) / (s.rate ?? 1)) * FPS);
    at += segFrames(s);
  }
  return null;
};

const zoomAt = (zooms: Zoom[], f: number) => {
  const kf = zooms.length ? zooms : [{ f: 0, x: 0.5, y: 0.5, s: 1 }];
  let z = kf[0];
  for (let i = 0; i < kf.length - 1; i++) {
    const a = kf[i], b = kf[i + 1];
    if (f >= a.f && f <= b.f) {
      const p = k(f, a.f, b.f, 0, 1, cam);
      z = { f, x: a.x + (b.x - a.x) * p, y: a.y + (b.y - a.y) * p, s: a.s + (b.s - a.s) * p };
    } else if (f > b.f) z = b;
  }
  return z;
};

/**
 * A real recording, the whole screen, no device frame. Plays `segs` back to back (jump cuts), with
 * camera push-ins (`zooms`) toward a detail and touch ripples at the logged taps.
 */
const Screen: React.FC<{
  src: string; image?: boolean; dev: Dev; segs?: Seg[]; zooms?: Zoom[]; taps?: [number, number, number][];
  cx?: number; top?: number; height?: number; enterAt?: number; fast?: { at: number; until: number; text: string }[];
}> = ({ src, image, dev, segs = [{ start: 0, end: 600 }], zooms = [], taps = [], cx = 1330, top = 60, height = 960,
  enterAt = 0, fast = [] }) => {
  const f = useCurrentFrame();
  const w = (height * dev.w) / dev.h;
  const z = zoomAt(zooms, f);
  const enter = k(f, enterAt, enterAt + 22);
  let at = 0;
  return (
    <>
      <div style={{ position: "absolute", left: cx - w / 2, top, width: w, height, borderRadius: height * 0.04,
        overflow: "hidden", background: "#000", boxShadow: `0 0 0 1.5px ${LINE}`, opacity: enter,
        transformOrigin: `${z.x * 100}% ${z.y * 100}%`,
        transform: `translateY(${(1 - enter) * 30}px) scale(${z.s})` }}>
        {image ? <Img src={staticFile(src)} style={{ width: w, height }} />
          : segs.map((s, i) => {
            const from = at;
            at += segFrames(s);
            return (
              <Sequence key={i} from={from} durationInFrames={i === segs.length - 1 ? Infinity : segFrames(s)} layout="none">
                <OffthreadVideo src={staticFile(src)} startFrom={sec(s.start)} playbackRate={s.rate ?? 1} muted
                  style={{ position: "absolute", width: w, height }} />
              </Sequence>
            );
          })}
        {taps.map(([t, x, y], i) => {
          const tf = clipToFrame(segs, t);
          if (tf === null) return null;
          const d = f - tf;
          if (d < 0 || d > 20) return null;
          const p = k(d, 0, 20, 0, 1, out);
          const r = (height / 960) * (22 + 46 * p);
          return <div key={i} style={{ position: "absolute", left: (x / dev.w) * w - r, top: (y / dev.h) * height - r,
            width: r * 2, height: r * 2, borderRadius: r, background: `rgba(255,255,255,${0.28 * (1 - p)})`,
            border: `2px solid rgba(255,255,255,${0.6 * (1 - p)})` }} />;
        })}
      </div>
      {fast.map((q, i) => {
        const a = k(f, q.at, q.at + 8) * (1 - k(f, q.until, q.until + 8));
        return a > 0 ? (
          <div key={i} style={{ position: "absolute", left: cx + w / 2 + 28, top: top + height - 30, fontFamily: FONT,
            fontSize: 19, fontWeight: 600, letterSpacing: 2, textTransform: "uppercase", color: DIM, opacity: a,
            whiteSpace: "nowrap" }}>{q.text}</div>
        ) : null;
      })}
    </>
  );
};

/** The text column beside a screen. Fades away while the camera pushes in. */
const Side: React.FC<{ children: React.ReactNode; hideFrom?: number; hideTo?: number }> = ({ children, hideFrom, hideTo }) => {
  const f = useCurrentFrame();
  const hide = hideFrom !== undefined ? k(f, hideFrom, hideFrom + 16, 0, 1, inOut) * (1 - k(f, hideTo ?? 1e9, (hideTo ?? 1e9) + 16, 0, 1, inOut)) : 0;
  return (
    <div style={{ position: "absolute", left: 150, top: 0, bottom: 0, width: 680, display: "flex",
      flexDirection: "column", justifyContent: "center", gap: 28, opacity: 1 - hide * 0.85 }}>{children}</div>
  );
};

// ---------------------------------------------------------------- 1. the problem

// NASA Black Marble 2016 (public domain), cropped to lon 0–21.33°E, lat 3–15°N: 240 px per degree.
const MAP = { w: 5120, h: 2880, lon0: 0, lat0: 15, ppd: 240 };
const CITIES: [string, number, number, number][] = [
  ["Lagos", 6.45, 3.39, 60], ["Abuja", 9.06, 7.49, 70], ["Kano", 12.0, 8.52, 80], ["Port Harcourt", 4.82, 7.03, 90],
];

/** A big number with its sentence, on the map's dark right side. */
const Stat: React.FC<{ at: number; until: number; to: number; decimals?: number; lines: string[] }> = ({
  at, until, to, decimals = 0, lines,
}) => {
  const f = useCurrentFrame();
  if (f < at || f > until + 16) return null;
  const leave = k(f, until, until + 12, 0, 1, inOut);
  return (
    <div style={{ position: "absolute", left: 1180, top: 330, width: 660, opacity: 1 - leave,
      transform: `translateY(${-leave * 14}px)` }}>
      <Count to={to} decimals={decimals} at={at} suffix="M" size={200} />
      <Lines lines={lines} at={at + 16} size={44} weight={500} style={{ marginTop: 28 }} spans={{ "The most of any country.": DIM }} />
    </div>
  );
};

const NightMap: React.FC = () => {
  const f = useCurrentFrame();
  const base = 1920 / MAP.w;
  const s = interpolate(f, [0, 720], [1.06, 1.2]);
  const ox = 820, oy = 520; // camera aims a little left of centre (Nigeria's middle)
  const pos = (lat: number, lon: number) => {
    const x = (lon - MAP.lon0) * MAP.ppd * base, y = (MAP.lat0 - lat) * MAP.ppd * base;
    return [ox + (x - ox) * s, oy + (y - oy) * s];
  };
  const appear = k(f, 0, 60, 0, 1, inOut);
  const dim = 1 - 0.45 * k(f, 150, 190, 0, 1, inOut);
  const leave = k(f, 690, 720, 0, 1, inOut);
  return (
    <AbsoluteFill style={{ background: BG }}>
      <AbsoluteFill style={{ opacity: appear * dim * (1 - leave) }}>
        <Img src={staticFile("nigeria_night.jpg")} style={{ position: "absolute", width: 1920, height: 1080,
          transformOrigin: `${ox}px ${oy}px`, transform: `scale(${s})` }} />
        <AbsoluteFill style={{ background: "linear-gradient(90deg, #05050500 45%, #050505d0 78%, #050505 100%)" }} />
        {CITIES.map(([name, lat, lon, at]) => {
          const [x, y] = pos(lat, lon);
          const a = k(f, at, at + 24) * (1 - k(f, 150, 175));
          return (
            <div key={name} style={{ position: "absolute", left: x, top: y, opacity: a }}>
              <div style={{ position: "absolute", left: -5, top: -5, width: 10, height: 10, borderRadius: 5,
                border: `1.5px solid ${TEXT}` }} />
              <div style={{ position: "absolute", left: 16, top: -12, fontFamily: FONT, fontSize: 20, fontWeight: 500,
                color: TEXT, whiteSpace: "nowrap", letterSpacing: 0.2 }}>{name}</div>
            </div>
          );
        })}
      </AbsoluteFill>
      <div style={{ position: "absolute", left: 140, top: 90 }}><Label text="The problem" at={20} until={690} /></div>
      <div style={{ position: "absolute", left: 1180, top: 430, width: 660 }}>
        <Lines lines={["This is Nigeria", "at night."]} at={40} until={150} size={72} />
      </div>
      <Stat at={180} until={420} to={86.8} decimals={1} lines={["people have no electricity.", "{The most of any country.}"]} />
      <Stat at={440} until={690} to={20} lines={["live where a phone gets", "no signal at all."]} />
      <Source text="NASA Earth Observatory, Black Marble 2016" at={30} until={170} left={1180} />
      <Source text="World Bank, Tracking SDG7 2025 (2023 data)" at={200} until={420} left={1180} />
      <Source text="Federal Government, NUCAP tower rollout, Oct 2026" at={460} until={690} left={1180} />
      <Sfx at={180} src="hit.wav" volume={0.55} />
      <Sfx at={440} src="hit.wav" volume={0.55} />
    </AbsoluteFill>
  );
};

const Pos: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <AbsoluteFill style={{ background: BG }}>
      <div style={{ position: "absolute", left: 140, top: 90 }}><Label text="The problem" /></div>
      <div style={{ position: "absolute", left: 140, top: 300 }}>
        <Lines lines={["Even in the city,"]} at={6} until={200} size={44} weight={500} color={DIM} />
        <div style={{ height: 22 }} />
        <div style={{ opacity: 1 - k(f, 200, 212), fontFamily: FONT, fontSize: 200, fontWeight: 650, color: TEXT,
          letterSpacing: -9, lineHeight: 0.95, display: "flex", gap: 40, alignItems: "baseline" }}>
          <span style={{ opacity: k(f, 16, 26), transform: `translateY(${(1 - k(f, 16, 40)) * 30}px)`, display: "inline-block" }}>1</span>
          <span style={{ fontSize: 110, fontWeight: 500, color: DIM, letterSpacing: -3, opacity: k(f, 22, 34) }}>in</span>
          <span style={{ opacity: k(f, 28, 38), transform: `translateY(${(1 - k(f, 28, 52)) * 30}px)`, display: "inline-block" }}>7</span>
        </div>
        <div style={{ height: 30 }} />
        <Lines lines={["card payments at a POS terminal fails."]} at={40} until={200} size={44} weight={500} />
      </div>
      <Source text="NIBSS: 13–15% of card transactions on POS terminals fail" at={46} until={200} />
      <Sfx at={28} src="hit.wav" volume={0.55} />
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- 2. why

const Excluded: React.FC = () => (
  <AbsoluteFill style={{ background: BG }}>
    <div style={{ position: "absolute", left: 140, top: 90 }}><Label text="Why it matters" /></div>
    <div style={{ position: "absolute", left: 140, top: 290 }}>
      <Count to={28.8} decimals={1} at={8} suffix="M" size={200} />
      <Lines lines={["adults are outside the financial system."]} at={24} until={220} size={44} weight={500}
        style={{ marginTop: 30 }} />
      <Lines lines={["In the Northwest, nearly half."]} at={70} until={220} size={44} weight={500} color={DIM}
        style={{ marginTop: 14 }} />
    </div>
    <Source text="EFInA, Access to Financial Services in Nigeria 2023 (26% excluded; Northwest 47%)" at={30} until={220} />
    <Sfx at={8} src="hit.wav" volume={0.55} />
  </AbsoluteFill>
);

const Turn: React.FC = () => (
  <AbsoluteFill style={{ background: BG }}>
    <div style={{ position: "absolute", left: 140, top: 90 }}><Label text="Why it matters" until={190} /></div>
    <div style={{ position: "absolute", left: 140, top: 0, bottom: 0, display: "flex", flexDirection: "column",
      justifyContent: "center", gap: 18 }}>
      <Lines lines={["Payments shouldn't need a signal."]} at={8} until={190} size={84} />
      <Lines lines={["And they shouldn't need to be watched."]} at={62} until={190} size={84} color={DIM} />
    </div>
  </AbsoluteFill>
);

const Brand: React.FC = () => {
  const f = useCurrentFrame();
  const word = k(f, 40, 66);
  return (
    <AbsoluteFill style={{ background: BG, justifyContent: "center", alignItems: "center" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 34 }}>
        <Mark size={150} at={6} />
        <div style={{ overflow: "hidden", paddingBottom: 20, marginBottom: -20 }}>
          <div style={{ fontFamily: FONT, fontSize: 150, fontWeight: 600, color: TEXT, letterSpacing: -6,
            transform: `translateX(${(1 - word) * -40}px)`, opacity: word, lineHeight: 1 }}>amana</div>
        </div>
      </div>
      <div style={{ marginTop: 40, fontFamily: FONT, fontSize: 32, fontWeight: 500, color: DIM, letterSpacing: 0.2,
        opacity: k(f, 74, 94), transform: `translateY(${(1 - k(f, 74, 98)) * 10}px)` }}>
        <i>amana</i> · Hausa: trust, something held in good faith for someone
      </div>
      <Sfx at={30} src="chime.wav" volume={0.35} />
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- 3. the solution, real recordings

const Who: React.FC<{ name: string; role: string; state: string; stateColor?: string; at?: number; until?: number }> = ({
  name, role, state, stateColor = DIM, at = 0, until = 1e9,
}) => {
  const f = useCurrentFrame();
  const a = k(f, at, at + 18) * (1 - k(f, until, until + 10));
  return (
    <div style={{ fontFamily: FONT, display: "flex", alignItems: "baseline", gap: 18, opacity: a,
      transform: `translateY(${(1 - a) * 8}px)` }}>
      <span style={{ fontSize: 30, fontWeight: 650, color: TEXT, letterSpacing: -0.5 }}>{name}</span>
      <span style={{ fontSize: 22, fontWeight: 500, color: DIM }}>{role}</span>
      <span style={{ fontSize: 22, fontWeight: 500, color: stateColor, display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ width: 8, height: 8, borderRadius: 4, background: stateColor, display: "inline-block",
          transform: "translateY(-2px)" }} />{state}
      </span>
    </div>
  );
};

/** "The solution" and the five steps, the current one lit. Same in every demo scene, so the cross-dissolve moves the light. */
const STEPS = ["Prepare", "Pay", "Receive", "Carry", "Settle"];
const Steps: React.FC<{ n: number }> = ({ n }) => (
  <div style={{ position: "absolute", left: 140, top: 84, display: "flex", alignItems: "center", gap: 40 }}>
    <Label text="The solution" />
    <div style={{ display: "flex", gap: 30, fontFamily: FONT, fontSize: 20, fontWeight: 600, letterSpacing: 0.4 }}>
      {STEPS.map((t, i) => (
        <span key={t} style={{ color: i === n ? TEXT : i < n ? DIM : "#3A3A3D", display: "flex", gap: 9 }}>
          <span style={{ fontVariantNumeric: "tabular-nums", color: i === n ? GREEN : undefined }}>{i + 1}</span>{t}
        </span>
      ))}
    </div>
  </div>
);

// Clip segments and taps (clip seconds; taps from demo/raw/taps.jsonl, device pixels).
const PREP: Seg[] = [{ start: 2.5, end: 5.2 }, { start: 13.6, end: 20.4, rate: 1.3 }, { start: 20.4, end: 31.8, rate: 5 },
  { start: 31.8, end: 36.5 }];
const PAY: Seg[] = [{ start: 7.6, end: 10.4 }, { start: 32.4, end: 39.4, rate: 1.25 }, { start: 39.4, end: 58.9, rate: 6 },
  { start: 58.9, end: 65.5 }];
const PAY_TAPS: [number, number, number][] = [[9.22, 160, 767], [33.07, 359, 1012], [33.62, 141, 1012],
  [34.21, 359, 1012], [34.78, 141, 647], [38.77, 360, 1144]];
const RECV: Seg[] = [{ start: 65.6, end: 73.2, rate: 1.2 }, { start: 73.2, end: 79, rate: 2 }, { start: 79, end: 84 },
  { start: 90.6, end: 96 }];
const RECV_TAPS: [number, number, number][] = [[66.93, 540, 1090], [71.8, 540, 1949]];
const RELAY: Seg[] = [{ start: 8.6, end: 13, rate: 1.1 }, { start: 13, end: 18.6, rate: 2.5 }, { start: 18.6, end: 22.2 }];
const SETTLE: Seg[] = [{ start: 9.6, end: 18.6 }];
const durOf = (segs: Seg[]) => segs.reduce((a, s) => a + segFrames(s), 0);

// Amana opening on Tunde's phone (cold start: the splash, "Opening wallet…", home with the mark).
const OPEN: Seg[] = [{ start: 2.2, end: 5.9 }, { start: 6.4, end: 10.4 }];

const Open: React.FC = () => {
  const f = useCurrentFrame();
  const d = durOf(OPEN);
  const move = k(f, d - 110, d - 50, 0, 1, cam);
  return (
    <AbsoluteFill style={{ background: BG }}>
      <div style={{ position: "absolute", left: 150, top: 0, bottom: 0, width: 700, display: "flex",
        flexDirection: "column", justifyContent: "center", gap: 26 }}>
        <Lines lines={["Tunde wants to buy", "bread from Ada."]} at={d - 70} size={64} />
        <Lines lines={["Both use Amana."]} at={d - 52} size={30} weight={500} color={DIM} />
      </div>
      <Screen src="v-open.mp4" dev={EMU} segs={OPEN} cx={960 + 370 * move} />
    </AbsoluteFill>
  );
};

const Prepare: React.FC = () => {
  const d = durOf(PREP);
  const fastAt = segFrames(PREP[0]) + segFrames(PREP[1]);
  return (
    <AbsoluteFill style={{ background: BG }}>
      <Steps n={0} />
      <Side hideFrom={d - 70}>
        <Who name="Tunde" role="buyer" state="Online, for now" stateColor={GREEN} at={6} />
        <Lines lines={["While he still has signal,", "Tunde sets aside", "offline cash."]} at={12} size={64} />
        <Lines lines={["Like drawing cash at an ATM."]} at={34} size={30} weight={500} color={DIM} />
      </Side>
      <Screen src="tunde-prepare.mp4" dev={EMU} segs={PREP}
        fast={[{ at: fastAt, until: fastAt + segFrames(PREP[2]), text: "Sped up 5×" }]}
        zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: d - 80, x: 0.5, y: 0.5, s: 1 }, { f: d - 40, x: 0.5, y: 0.2, s: 1.2 }]} />
    </AbsoluteFill>
  );
};

const Pay: React.FC = () => {
  const d = durOf(PAY);
  const fastAt = segFrames(PAY[0]) + segFrames(PAY[1]);
  const qr = clipToFrame(PAY, 59.3) ?? 0; // the payment QR appears
  return (
    <AbsoluteFill style={{ background: BG }}>
      <Steps n={1} />
      <Side hideFrom={qr + 30}>
        <Who name="Tunde" role="buyer" state="No network" />
        <Lines lines={["No network on", "either phone."]} at={6} until={qr - 6} size={64} />
        <Lines lines={["Tunde enters Ada's address", "and the amount."]} at={22} until={qr - 6} size={30} weight={500} color={DIM} />
        <Lines lines={["His phone signs a", "shielded Zcash", "payment. Right there."]} at={qr} size={64} />
        <Lines lines={["And shows it as a code for Ada."]} at={qr + 16} size={30} weight={500} color={DIM} />
      </Side>
      <Screen src="v-tunde-pay.mp4" dev={EMU} segs={PAY} taps={PAY_TAPS}
        fast={[{ at: fastAt, until: fastAt + segFrames(PAY[2]), text: "Signing · sped up 6×" }]}
        zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: qr + 30, x: 0.5, y: 0.5, s: 1 }, { f: qr + 75, x: 0.5, y: 0.42, s: 1.18 }]} />
      {PAY_TAPS.map(([t]) => <Sfx key={t} at={clipToFrame(PAY, t) ?? 0} src="softtick.wav" volume={0.25} />)}
      <Sfx at={qr} src="cue_sent.wav" volume={0.5} />
    </AbsoluteFill>
  );
};

const Receive: React.FC = () => {
  const ok = clipToFrame(RECV, 80.1) ?? 0;
  const home = durOf(RECV.slice(0, 3));
  return (
    <AbsoluteFill style={{ background: BG }}>
      <Steps n={2} />
      <Side hideFrom={ok + 4} hideTo={home - 20}>
        <Who name="Ada" role="seller · a real phone" state="Airplane mode" at={4} />
        <Lines lines={["Ada scans it."]} at={10} until={home - 10} size={64} />
        <Lines lines={["Her phone checks the payment", "itself. No network involved."]} at={26} until={home - 10}
          size={30} weight={500} color={DIM} />
        <Lines lines={["Received.", "Not yet settled."]} at={home} size={64} />
        <Lines lines={["Amana is honest about where", "the money is."]} at={home + 16} size={30} weight={500} color={DIM} />
      </Side>
      <Screen src="v-ada-pay.mp4" dev={PHONE} segs={RECV} taps={RECV_TAPS}
        fast={[{ at: segFrames(RECV[0]), until: segFrames(RECV[0]) + segFrames(RECV[1]), text: "Reading · sped up 2×" }]}
        zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: ok, x: 0.5, y: 0.5, s: 1 }, { f: ok + 30, x: 0.5, y: 0.3, s: 1.45 },
          { f: home - 24, x: 0.5, y: 0.3, s: 1.45 }, { f: home, x: 0.5, y: 0.5, s: 1 },
          { f: home + 40, x: 0.5, y: 0.5, s: 1 }, { f: home + 80, x: 0.5, y: 0.42, s: 1.3 }]} />
      {RECV_TAPS.map(([t]) => <Sfx key={t} at={clipToFrame(RECV, t) ?? 0} src="softtick.wav" volume={0.3} />)}
      <Sfx at={ok} src="cue_received.wav" volume={0.55} />
    </AbsoluteFill>
  );
};

const Relay: React.FC = () => {
  const sent = clipToFrame(RELAY, 19.0) ?? 0;
  const d = durOf(RELAY);
  return (
    <AbsoluteFill style={{ background: BG }}>
      <Steps n={3} />
      <Side>
        <Who name="A stranger" role="anyone with Amana" state="Bluetooth" at={4} />
        <Lines lines={["A stranger's phone", "carries it along,", "over Bluetooth."]} at={10} until={sent - 6} size={64} />
        <Lines lines={["It's encrypted.", "The stranger can't read it."]} at={28} until={sent - 6} size={30} weight={500} color={DIM} />
        <Lines lines={["The first phone", "to find signal", "sends it on."]} at={sent} size={64} />
      </Side>
      <Screen src="v-relay.mp4" dev={EMU} segs={RELAY} taps={[[11.5, 608, 967]]}
        fast={[{ at: segFrames(RELAY[0]), until: sent - 4, text: "Sped up 2.5×" }]}
        zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: sent - 10, x: 0.5, y: 0.5, s: 1 }, { f: sent + 30, x: 0.5, y: 0.2, s: 1.35 },
          { f: d, x: 0.5, y: 0.2, s: 1.38 }]} />
      <Sfx at={sent} src="cue_sent.wav" volume={0.45} />
    </AbsoluteFill>
  );
};

const Settle: React.FC = () => {
  const flip = clipToFrame(SETTLE, 13.05) ?? 0;
  return (
    <AbsoluteFill style={{ background: BG }}>
      <Steps n={4} />
      <Side hideFrom={flip + 70}>
        <Who name="Ada" role="seller · a real phone" state="Back online" stateColor={GREEN} at={4} />
        <Lines lines={["Ada's phone finds", "signal again."]} at={8} until={flip - 8} size={64} />
        <Lines lines={["Settled."]} at={flip} size={96} spans={{ "Settled.": GREEN }} />
        <Lines lines={["Mined in block 4,544,893", "on the Zcash testnet."]} at={flip + 14} size={30} weight={500} color={DIM} />
      </Side>
      <Screen src="v-ada-settle-clean.mp4" dev={PHONE} segs={SETTLE}
        zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: flip - 30, x: 0.5, y: 0.5, s: 1 }, { f: flip + 10, x: 0.5, y: 0.6, s: 1.4 },
          { f: 300, x: 0.5, y: 0.6, s: 1.45 }]} />
      <Sfx at={flip} src="chime.wav" volume={0.55} />
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- 4. how it holds up

/** Clip time at film frame f of a shot built from `segs`. */
const frameToClip = (segs: Seg[], f: number) => {
  let at = 0;
  for (const s of segs) {
    const n = segFrames(s);
    if (f < at + n) return s.start + ((f - at) / FPS) * (s.rate ?? 1);
    at += n;
  }
  const l = segs[segs.length - 1];
  return l.end;
};

const Cheat: React.FC = () => {
  const f = useCurrentFrame();
  const { phones, alertFrame, injectC, alertC, segsC, fastFrom, fastTo, frameOf } = CHEAT;
  const t = Math.max(0, Math.min(frameToClip(segsC, f), alertC) - injectC);
  const hit = f >= alertFrame;
  const xs = [470, 960, 1450];
  return (
    <AbsoluteFill style={{ background: BG }}>
      <div style={{ position: "absolute", left: 140, top: 90 }}><Label text="How it holds up" /></div>
      <div style={{ position: "absolute", left: 140, top: 150 }}>
        <Lines lines={["What if someone spends", "the same money twice?"]} at={6} size={56} />
      </div>
      <div style={{ position: "absolute", right: 140, top: 150, textAlign: "right", fontFamily: FONT }}>
        <div style={{ fontSize: 20, fontWeight: 600, letterSpacing: 3.2, textTransform: "uppercase", color: DIM,
          opacity: k(f, 20, 36) }}>Since the second payment</div>
        <div style={{ fontSize: 84, fontWeight: 600, letterSpacing: -2, fontVariantNumeric: "tabular-nums",
          color: hit ? RED : TEXT, opacity: k(f, 24, 40), marginTop: 6 }}>{t.toFixed(1)} s</div>
        <div style={{ fontSize: 19, fontWeight: 600, letterSpacing: 2, textTransform: "uppercase", color: DIM,
          opacity: k(f, fastFrom, fastFrom + 8) * (1 - k(f, fastTo, fastTo + 8)), marginTop: 4 }}>Sped up 8×</div>
      </div>
      {phones.map((p, i) => (
        <React.Fragment key={p.src}>
          <Screen src={p.src} dev={p.dev} segs={p.segs} cx={xs[i]} top={352} height={570} enterAt={4 + i * 5} />
          <div style={{ position: "absolute", left: xs[i] - 260, width: 520, top: 940, textAlign: "center", fontFamily: FONT,
            fontSize: 22, fontWeight: 500, color: f >= frameOf(p.alertC) ? TEXT : DIM, opacity: k(f, 14 + i * 5, 32 + i * 5) }}>
            {p.label}{f >= frameOf(p.alertC) ? <span style={{ color: RED }}> · warned</span> : null}
          </div>
        </React.Fragment>
      ))}
      <div style={{ position: "absolute", left: 140, top: 286, fontFamily: FONT, fontSize: 28, fontWeight: 500, color: DIM,
        opacity: k(f, alertFrame + 10, alertFrame + 30) }}>
        No network anywhere. All three phones warned, within half a second of each other.
      </div>
      <Source text="A real double-spend pair made for testing, never broadcast. Emulators over virtual Bluetooth; fastest run measured: 0.5 s"
        at={30} bottom={44} />
      <Sfx at={frameOf(27.84)} src="alert.wav" volume={0.6} />
      <Sfx at={frameOf(27.84)} src="hit.wav" volume={0.4} />
    </AbsoluteFill>
  );
};

const Holds: React.FC = () => {
  const f = useCurrentFrame();
  const rows: [string, string, number][] = [
    ["Real shielded Zcash.", "Orchard notes, on the Zcash testnet.", 10],
    ["Checked on the phone.", "The receiver verifies the proof itself, offline.", 40],
    ["Relays see nothing.", "Strangers carry encrypted bytes they can't read.", 70],
    ["Losses are capped.", "Offline double spends can't be prevented without special hardware, so they are caught fast and limited.", 100],
  ];
  return (
    <AbsoluteFill style={{ background: BG }}>
      <div style={{ position: "absolute", left: 1180, top: 0, right: 80, bottom: 0, fontFamily: "Menlo, monospace",
        fontSize: 17, lineHeight: 1.6, color: "#1c1c1e", wordBreak: "break-all", overflow: "hidden", paddingTop: 80,
        opacity: k(f, 0, 40) }}>
        {TX_HEX.slice(0, Math.floor(k(f, 0, 240, 0, 1, Easing.linear) * 2600))}
      </div>
      <div style={{ position: "absolute", left: 140, top: 90 }}><Label text="How it holds up" /></div>
      <div style={{ position: "absolute", left: 140, top: 230, width: 960, display: "flex", flexDirection: "column", gap: 44 }}>
        {rows.map(([a, b, at]) => (
          <div key={a}>
            <Lines lines={[a]} at={at} size={52} />
            <Lines lines={[b]} at={at + 8} size={26} weight={500} color={DIM} style={{ marginTop: 8 }} />
          </div>
        ))}
      </div>
      <div style={{ position: "absolute", left: 1180, bottom: 70, fontFamily: FONT, fontSize: 19, color: FAINT,
        opacity: k(f, 30, 50) }}>The real transaction, as every node stores it</div>
      {rows.map(([a, , at]) => <Sfx key={a} at={at} src="softtick.wav" volume={0.3} />)}
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- 5. where it goes

const Next: React.FC = () => (
  <AbsoluteFill style={{ background: BG }}>
    <div style={{ position: "absolute", left: 140, top: 90 }}><Label text="Where it goes" until={200} /></div>
    <div style={{ position: "absolute", left: 140, top: 0, bottom: 0, display: "flex", flexDirection: "column",
      justifyContent: "center", gap: 18 }}>
      <Lines lines={["Today it moves ZEC."]} at={8} until={200} size={84} />
      <Lines lines={["Next, a shielded digital dollar,", "on the same rails."]} at={56} until={200} size={84} color={DIM} />
    </div>
  </AbsoluteFill>
);

const End: React.FC = () => {
  const f = useCurrentFrame();
  const word = k(f, 30, 56);
  return (
    <AbsoluteFill style={{ background: BG, justifyContent: "center", alignItems: "center" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 26 }}>
        <Mark size={110} at={0} />
        <div style={{ fontFamily: FONT, fontSize: 110, fontWeight: 600, color: TEXT, letterSpacing: -4.4, lineHeight: 1,
          opacity: word, transform: `translateX(${(1 - word) * -30}px)` }}>amana</div>
      </div>
      <div style={{ marginTop: 44, fontFamily: FONT, fontSize: 44, fontWeight: 500, color: TEXT, letterSpacing: -0.8,
        opacity: k(f, 64, 86), transform: `translateY(${(1 - k(f, 64, 90)) * 10}px)` }}>Money held in trust.</div>
      <div style={{ position: "absolute", bottom: 80, fontFamily: FONT, fontSize: 20, fontWeight: 500, color: FAINT,
        letterSpacing: 0.3, opacity: k(f, 100, 124), textAlign: "center", lineHeight: 1.6 }}>
        Built on Zcash · ZECATHON 2026 · Shielded Payments<br />
        Real recordings on the Zcash testnet: one real phone, three emulators.
      </div>
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- timeline

const T = 16; // dissolve length
const SCENES: [React.FC, number][] = [
  [NightMap, 720], [Pos, 225], [Excluded, 240], [Turn, 210], [Brand, 175], [Open, durOf(OPEN) + 30],
  [Prepare, durOf(PREP) + 10], [Pay, durOf(PAY) + 40], [Receive, durOf(RECV) + 40], [Relay, durOf(RELAY) + 30],
  [Settle, durOf(SETTLE) + 20],
  [Cheat, CHEAT.duration], [Holds, 270], [Next, 215], [End, 200],
];
export const AMANA_FRAMES = SCENES.reduce((a, [, d]) => a + d, 0) - T * (SCENES.length - 1);

export const Amana: React.FC = () => {
  const items: React.ReactNode[] = [];
  SCENES.forEach(([Scene, d], i) => {
    if (i > 0) items.push(<TransitionSeries.Transition key={`t${i}`} presentation={fade()} timing={linearTiming({ durationInFrames: T })} />);
    items.push(<TransitionSeries.Sequence key={`s${i}`} durationInFrames={d}><Scene /></TransitionSeries.Sequence>);
  });
  return (
    <AbsoluteFill style={{ background: BG }}>
      <style>{`@font-face { font-family: Inter; src: url(${staticFile("inter.ttf")}); font-weight: 100 900; }`}</style>
      <TransitionSeries>{items}</TransitionSeries>
      <Audio src={staticFile("drone.wav")} volume={(f) => 0.3 * k(f, 0, 90) * (1 - k(f, AMANA_FRAMES - 120, AMANA_FRAMES - 10))} />
    </AbsoluteFill>
  );
};
