// Vertical launch film (1080x1920). Act 1: why it matters. Act 2: the real flows, full screens,
// with animated zooms to the detail and touch ripples where the real taps happened. Act 3: value.
import React from "react";
import {
  AbsoluteFill, Audio, Easing, Img, OffthreadVideo, Sequence, interpolate, staticFile, useCurrentFrame,
} from "remotion";
import { TX_HEX } from "./txhex";

const FPS = 30;
const INK = "#0a0a0b";
const TEXT = "#f3f3f1";
const DIM = "#8b8b87";
const ACCENT = "#f4b728";
const RED = "#ff4d57";
const FONT = "Inter, Helvetica Neue, sans-serif";

const out = Easing.bezier(0.16, 1, 0.3, 1);
const inOut = Easing.bezier(0.65, 0, 0.35, 1);
const k = (f: number, a: number, b: number, from = 0, to = 1, e = out) =>
  interpolate(f, [a, b], [from, to], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: e });
const sec = (s: number) => Math.round(s * FPS);

const Sfx: React.FC<{ at: number; src: string; volume?: number }> = ({ at, src, volume = 0.7 }) => (
  <Sequence from={at} layout="none"><Audio src={staticFile(src)} volume={volume} /></Sequence>
);

// ---------------------------------------------------------------- pieces

const Signal: React.FC<{ level: number; cross?: number; size?: number; color?: string }> = ({
  level, cross = 0, size = 200, color = TEXT,
}) => {
  const bw = size * 0.16;
  const gap = size * 0.1;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      {[0, 1, 2, 3].map((i) => {
        const h = size * (0.28 + i * 0.24);
        const on = Math.max(0, Math.min(1, level - i));
        return <rect key={i} x={i * (bw + gap)} y={size - h} width={bw} height={h} rx={bw * 0.22}
          fill={color} opacity={0.14 + 0.86 * on} />;
      })}
      {cross > 0 ? (
        <g stroke={RED} strokeWidth={size * 0.06} strokeLinecap="round" opacity={cross}>
          <line x1={size * 0.66} y1={size * 0.06} x2={size * 0.66 + size * 0.28 * cross} y2={size * 0.06 + size * 0.28 * cross} />
          <line x1={size * 0.94} y1={size * 0.06} x2={size * 0.94 - size * 0.28 * cross} y2={size * 0.06 + size * 0.28 * cross} />
        </g>
      ) : null}
    </svg>
  );
};

/** Headline above the screen: a bold line and a quieter one. `hl` words get the accent colour. */
const Head: React.FC<{ title: string; sub?: string; at?: number; hl?: string[]; color?: string }> = ({
  title, sub, at = 0, hl = [], color = TEXT,
}) => {
  const f = useCurrentFrame();
  const a = k(f, at, at + 16);
  const b = k(f, at + 8, at + 26);
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top: 0, height: 470, zIndex: 2, padding: "130px 80px 0",
      background: `linear-gradient(${INK} 78%, ${INK}00)`, fontFamily: FONT }}>
      <div style={{ fontSize: 66, fontWeight: 700, color, letterSpacing: -1.8, lineHeight: 1.08, opacity: a,
        transform: `translateY(${(1 - a) * 22}px)` }}>
        {title.split(" ").map((w, i) => (
          <span key={i} style={{ color: hl.includes(w.replace(/[.,]/g, "")) ? ACCENT : undefined }}>{w} </span>
        ))}
      </div>
      {sub ? <div style={{ fontSize: 34, color: DIM, marginTop: 16, lineHeight: 1.3, opacity: b,
        transform: `translateY(${(1 - b) * 14}px)` }}>{sub}</div> : null}
    </div>
  );
};

type Zoom = { f: number; x: number; y: number; s: number }; // frame, focus (0..1 of screen), scale
type Tap = { f: number; x: number; y: number }; // frame, position (0..1 of screen)

/**
 * A real screen recording, shown whole (no device frame), with keyframed zooms toward a detail
 * and touch ripples. Plays `src` from `start` seconds at `rate`.
 */
const Screen: React.FC<{
  src: string; image?: boolean; aspect: number; start?: number; rate?: number; zooms?: Zoom[]; taps?: Tap[];
  height?: number; top?: number;
}> = ({ src, image, aspect, start = 0, rate = 1, zooms = [], taps = [], height = 1380, top = 470 }) => {
  const f = useCurrentFrame();
  const w = height * aspect;
  const left = (1080 - w) / 2;
  const kf = zooms.length ? zooms : [{ f: 0, x: 0.5, y: 0.5, s: 1 }];
  let z = kf[0];
  for (let i = 0; i < kf.length - 1; i++) {
    const a = kf[i], b = kf[i + 1];
    if (f >= a.f && f <= b.f) {
      const p = k(f, a.f, b.f, 0, 1, inOut);
      z = { f, x: a.x + (b.x - a.x) * p, y: a.y + (b.y - a.y) * p, s: a.s + (b.s - a.s) * p };
    } else if (f > b.f) z = b;
  }
  const enter = k(f, 0, 14);
  return (
    <div style={{ position: "absolute", left, top, width: w, height, borderRadius: 40, overflow: "hidden",
      background: "#000", boxShadow: "0 0 0 2px #1d1d1c", opacity: enter,
      transformOrigin: `${z.x * 100}% ${z.y * 100}%`,
      transform: `translateY(${(1 - enter) * 40}px) scale(${z.s})` }}>
      <div style={{ position: "absolute", left: 0, top: 0, width: w, height }}>
        {image ? <Img src={staticFile(src)} style={{ width: w, height }} />
          : <OffthreadVideo src={staticFile(src)} startFrom={sec(start)} playbackRate={rate} muted style={{ width: w, height }} />}
        {taps.map((t, i) => {
          const d = f - t.f;
          if (d < 0 || d > 18) return null;
          const p = d / 18;
          const r = 30 + 60 * p;
          return <div key={i} style={{ position: "absolute", left: t.x * w - r, top: t.y * height - r, width: r * 2,
            height: r * 2, borderRadius: r, background: `rgba(255,255,255,${0.35 * (1 - p)})`,
            border: `3px solid rgba(255,255,255,${0.7 * (1 - p)})` }} />;
        })}
      </div>
    </div>
  );
};

// Device sizes, for converting logged taps to 0..1.
const EMU = { w: 720, h: 1280 };
const PHONE = { w: 1080, h: 2400 };
const tapAt = (dev: { w: number; h: number }, x: number, y: number, clipT: number, start: number, rate: number): Tap =>
  ({ f: Math.round(((clipT - start) / rate) * FPS), x: x / dev.w, y: y / dev.h });

// ---------------------------------------------------------------- act 1: why it matters

const NoSignal: React.FC = () => {
  const f = useCurrentFrame();
  const level = 4 - k(f, 8, 44, 0, 4, Easing.linear);
  const cross = k(f, 48, 58);
  const t = k(f, 58, 76);
  return (
    <AbsoluteFill style={{ background: INK, justifyContent: "center", alignItems: "center", flexDirection: "column", gap: 60 }}>
      <Signal level={level} cross={cross} size={260} />
      <div style={{ fontFamily: FONT, fontSize: 92, fontWeight: 700, color: TEXT, letterSpacing: -3, opacity: t,
        transform: `translateY(${(1 - t) * 18}px)` }}>No signal.</div>
      {[8, 20, 32, 44].map((a) => <Sfx key={a} at={a} src="tick.wav" volume={0.45} />)}
      <Sfx at={48} src="drop.wav" />
    </AbsoluteFill>
  );
};

const Line: React.FC<{ text: string; at: number; out: number; color?: string; size?: number; hl?: string[] }> = ({
  text, at, out: o, color = TEXT, size = 96, hl = [],
}) => {
  const f = useCurrentFrame();
  const a = k(f, at, at + 16) * (1 - k(f, o, o + 10));
  return (
    <div style={{ position: "absolute", left: 90, right: 90, top: "50%", transform: `translateY(calc(-50% + ${(1 - a) * 26}px))`,
      fontFamily: FONT, fontSize: size, fontWeight: 700, letterSpacing: -size * 0.03, lineHeight: 1.06, color, opacity: a }}>
      {text.split(" ").map((w, i) => (
        <span key={i} style={{ color: hl.includes(w.replace(/[.,?]/g, "")) ? ACCENT : undefined }}>{w} </span>
      ))}
    </div>
  );
};

const Why: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <div style={{ position: "absolute", left: 90, top: 150 }}><Signal level={0} cross={1} size={80} color={DIM} /></div>
    <Line text="Ada sells bread at a busy market." at={0} out={58} />
    <Line text="The network keeps dropping." at={66} out={118} />
    <Line text="Cash is easy to lose." at={126} out={172} />
    <Line text="Mobile money needs signal, and keeps a record of every payment." at={180} out={256} size={80} />
    <Line text="What if Ada could get paid privately, with no signal at all?" at={264} out={9999} size={88}
      hl={["privately", "no", "signal"]} />
  </AbsoluteFill>
);

const Title: React.FC = () => {
  const f = useCurrentFrame();
  const a = k(f, 2, 20);
  const b = k(f, 14, 32);
  return (
    <AbsoluteFill style={{ background: INK, justifyContent: "center", alignItems: "center", fontFamily: FONT }}>
      <div style={{ fontSize: 120, fontWeight: 800, color: TEXT, letterSpacing: -4, opacity: a,
        transform: `scale(${0.94 + 0.06 * a})` }}>Offline <span style={{ color: ACCENT }}>ZEC</span></div>
      <div style={{ fontSize: 40, color: DIM, marginTop: 20, opacity: b }}>Zcash that works without signal.</div>
      <Sfx at={2} src="chime.wav" volume={0.35} />
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- act 2: the real flows

// Tunde, the evening before, while he still had signal (the earlier recording).
const Prepare: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Head title="Earlier, with signal, Tunde set aside offline cash." sub="Like drawing cash from an ATM." hl={["offline", "cash"]} />
    <Screen src="tunde-prepare.mp4" aspect={EMU.w / EMU.h} start={2} rate={1.6}
      zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 40, x: 0.5, y: 0.4, s: 1.25 }, { f: 120, x: 0.5, y: 0.4, s: 1.25 }]} />
  </AbsoluteFill>
);

// Tunde pays: three jump-cut segments of one real recording, taps as logged during the shoot.
const TUNDE_TAPS = [[9.22, 160, 767], [13.19, 296, 539], [33.07, 359, 1012], [33.62, 141, 1012], [34.21, 359, 1012],
  [34.78, 141, 647], [38.77, 360, 1144]] as const;
const tundeTaps = (start: number, rate: number) => TUNDE_TAPS.map(([t, x, y]) => tapAt(EMU, x, y, t, start, rate));

const TundePays: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Head title="Tunde pays. No internet." sub="He enters Ada's address and the amount." hl={["No", "internet"]} />
    <Sequence durationInFrames={75}>
      <Screen src="v-tunde-pay.mp4" aspect={EMU.w / EMU.h} start={7.5} rate={1.2} taps={tundeTaps(7.5, 1.2)} />
    </Sequence>
    <Sequence from={75} durationInFrames={180}>
      <Screen src="v-tunde-pay.mp4" aspect={EMU.w / EMU.h} start={31} rate={1.5} taps={tundeTaps(31, 1.5)}
        zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 30, x: 0.5, y: 0.3, s: 1.3 }, { f: 95, x: 0.5, y: 0.3, s: 1.3 },
          { f: 140, x: 0.5, y: 0.85, s: 1.3 }]} />
      {[33.07, 33.62, 34.21, 34.78].map((t) => <Sfx key={t} at={Math.round(((t - 31) / 1.5) * FPS)} src="tick.wav" volume={0.3} />)}
      <Sfx at={Math.round(((38.77 - 31) / 1.5) * FPS)} src="tick.wav" volume={0.5} />
    </Sequence>
  </AbsoluteFill>
);

const TundeSigns: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Head title="His phone signs a private payment." sub="And shows it as a code for Ada to scan." hl={["private"]} />
    <Screen src="v-tunde-pay.mp4" aspect={EMU.w / EMU.h} start={58} rate={1}
      zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 70, x: 0.5, y: 0.5, s: 1 }, { f: 120, x: 0.5, y: 0.5, s: 1.3 }]} />
  </AbsoluteFill>
);

// Ada's real phone, in airplane mode: Receive, Scan a payment, the check.
const ADA_TAPS = [[66.93, 540, 1090], [71.8, 540, 1949]] as const;
const AdaScans: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Head title="Ada scans it. In airplane mode." sub="Her phone checks the proof by itself. No network involved."
      hl={["airplane", "mode"]} />
    <Screen src="v-ada-pay.mp4" aspect={PHONE.w / PHONE.h} start={65} rate={1.5}
      taps={ADA_TAPS.map(([t, x, y]) => tapAt(PHONE, x, y, t, 65, 1.5))}
      zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 205, x: 0.5, y: 0.5, s: 1 }, { f: 240, x: 0.5, y: 0.15, s: 1.5 }]} />
    <Sfx at={Math.round(((66.93 - 65) / 1.5) * FPS)} src="tick.wav" volume={0.45} />
    <Sfx at={Math.round(((71.8 - 65) / 1.5) * FPS)} src="tick.wav" volume={0.45} />
    <Sfx at={Math.round(((75.6 - 65) / 1.5) * FPS)} src="chime.wav" volume={0.5} />
  </AbsoluteFill>
);

const AdaPending: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Head title="Received. Not yet settled." sub="The app is honest about where the money is." />
    <Screen src="v-ada-pay.mp4" aspect={PHONE.w / PHONE.h} start={90} rate={1}
      zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 30, x: 0.5, y: 0.5, s: 1 }, { f: 75, x: 0.5, y: 0.4, s: 1.35 }]} />
  </AbsoluteFill>
);

// A stranger's phone carries it over Bluetooth, then finds signal and broadcasts.
const Relay: React.FC = () => {
  const f = useCurrentFrame();
  const later = f > 190;
  return (
    <AbsoluteFill style={{ background: INK }}>
      <Head title={later ? "The first phone with signal sends it on." : "A stranger's phone carries it, over Bluetooth."}
        sub={later ? "Any phone running the app can be that phone." : "It's encrypted. The stranger can't read it."}
        at={later ? 190 : 0} hl={["Bluetooth", "signal"]} />
      <Screen src="v-relay.mp4" aspect={EMU.w / EMU.h} start={9} rate={1.6}
        taps={[tapAt(EMU, 608, 967, 11.5, 9, 1.6)]}
        zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 60, x: 0.5, y: 0.5, s: 1 }, { f: 110, x: 0.5, y: 0.28, s: 1.3 },
          { f: 230, x: 0.5, y: 0.28, s: 1.3 }]} />
      <Sfx at={190} src="whoosh.wav" volume={0.4} />
    </AbsoluteFill>
  );
};

// Ada back online: pending flips to settled (13 s into the clip).
const Settled: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Head title="Back online: settled." sub="Mined in block 4,544,893 on the Zcash testnet." hl={["settled"]} at={4} />
    <Screen src="v-ada-settle.mp4" aspect={PHONE.w / PHONE.h} start={9.5} rate={1}
      zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 40, x: 0.5, y: 0.64, s: 1.35 }, { f: 170, x: 0.5, y: 0.64, s: 1.35 }]} />
    <Sfx at={sec(3.5)} src="chime.wav" volume={0.6} />
  </AbsoluteFill>
);

const OnChain: React.FC = () => {
  const f = useCurrentFrame();
  const shown = Math.floor(k(f, 0, 60, 0, 1, Easing.linear) * 640);
  const rows: [string, string, number][] = [
    ["Who paid", "hidden", 70], ["Who was paid", "hidden", 86], ["How much", "hidden", 102],
  ];
  return (
    <AbsoluteFill style={{ background: INK, fontFamily: FONT }}>
      <Head title="And on the blockchain?" sub="Transaction e04f50a9…, as every node stores it." />
      <div style={{ position: "absolute", left: 80, right: 80, top: 470, fontFamily: "Menlo, monospace", fontSize: 30,
        lineHeight: 1.55, color: "#4a4a46", wordBreak: "break-all", height: 640, overflow: "hidden" }}>
        {TX_HEX.slice(0, shown)}
      </div>
      <div style={{ position: "absolute", left: 80, right: 80, top: 1180 }}>
        {rows.map(([l, v, at]) => {
          const a = k(f, at, at + 16);
          return (
            <div key={l} style={{ display: "flex", justifyContent: "space-between", padding: "30px 0",
              borderTop: "1px solid #232321", opacity: a, transform: `translateY(${(1 - a) * 16}px)` }}>
              <span style={{ fontSize: 50, color: DIM }}>{l}</span>
              <span style={{ fontSize: 50, fontWeight: 700, color: ACCENT }}>{v}</span>
            </div>
          );
        })}
      </div>
      {rows.map(([l, , at]) => <Sfx key={l} at={at} src="tick.wav" volume={0.4} />)}
    </AbsoluteFill>
  );
};

const Cheat: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Head title="Same money spent twice?" sub="Phones nearby raise the alarm, 0.5 s after the second payment." color={RED} />
    <Screen src="alert.png" image aspect={EMU.w / EMU.h}
      zooms={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 30, x: 0.5, y: 0.5, s: 1 }, { f: 75, x: 0.5, y: 0.2, s: 1.3 }]} />
    <Sfx at={20} src="alert.wav" volume={0.7} />
  </AbsoluteFill>
);

// ---------------------------------------------------------------- act 3: value

const Value: React.FC = () => {
  const f = useCurrentFrame();
  const lines: [string, string, number][] = [
    ["Private", "like cash.", 4], ["Digital", "like mobile money.", 22], ["Works", "with no signal.", 40],
  ];
  const end = k(f, 90, 110);
  return (
    <AbsoluteFill style={{ background: INK, fontFamily: FONT, padding: "0 90px", justifyContent: "center" }}>
      {lines.map(([a, b, at]) => {
        const p = k(f, at, at + 18);
        return (
          <div key={a} style={{ fontSize: 104, fontWeight: 700, letterSpacing: -3, lineHeight: 1.12, opacity: p,
            transform: `translateY(${(1 - p) * 24}px)` }}>
            <span style={{ color: ACCENT }}>{a}</span> <span style={{ color: TEXT }}>{b}</span>
          </div>
        );
      })}
      <div style={{ marginTop: 90, opacity: end }}>
        <div style={{ fontSize: 56, fontWeight: 800, color: TEXT, letterSpacing: -1.5 }}>Offline <span style={{ color: ACCENT }}>ZEC</span></div>
        <div style={{ fontSize: 30, color: DIM, marginTop: 12 }}>Built on Zcash · ZECATHON · Shielded Payments</div>
        <div style={{ fontSize: 26, color: "#5e5e5a", marginTop: 8 }}>Real recordings on the Zcash testnet. One real phone, three emulators.</div>
      </div>
      <Sfx at={90} src="chime.wav" volume={0.4} />
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- timeline (hard cuts)

const SCENES: [React.FC, number][] = [
  [NoSignal, 96], [Why, 360], [Title, 66], [Prepare, 120], [TundePays, 255], [TundeSigns, 130], [AdaScans, 260],
  [AdaPending, 105], [Relay, 260], [Settled, 165], [OnChain, 160], [Cheat, 130], [Value, 190],
];
export const VERTICAL_FRAMES = SCENES.reduce((a, [, d]) => a + d, 0);

export const Vertical: React.FC = () => {
  let at = 0;
  return (
    <AbsoluteFill style={{ background: INK }}>
      <style>{`@font-face { font-family: Inter; src: url(${staticFile("inter.ttf")}); }`}</style>
      {SCENES.map(([Scene, d], i) => {
        const from = at;
        at += d;
        return <Sequence key={i} from={from} durationInFrames={d}><Scene /></Sequence>;
      })}
    </AbsoluteFill>
  );
};
