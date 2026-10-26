// Launch film, second take: one idea (a signal icon) carries the story; the real screens are the
// hero, cropped tight; no device mockups, glows or badges. Hard cuts, purposeful motion, UI sounds.
import React from "react";
import {
  AbsoluteFill, Audio, Easing, Img, OffthreadVideo, Sequence, interpolate, staticFile, useCurrentFrame,
} from "remotion";
import { TX_HEX } from "./txhex";

const INK = "#0a0a0b";
const TEXT = "#f2f2f0";
const DIM = "#8a8a86";
const ACCENT = "#f4b728";
const RED = "#ff4d57";
const FONT = "Inter, Helvetica Neue, sans-serif";

const ease = Easing.bezier(0.16, 1, 0.3, 1); // ease-out, for things entering
const easeInOut = Easing.bezier(0.65, 0, 0.35, 1); // for things moving across the frame

const k = (f: number, a: number, b: number, from = 0, to = 1, e = ease) =>
  interpolate(f, [a, b], [from, to], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: e });

// ---------------------------------------------------------------- building blocks

/** Four signal bars; `level` 0..4 (fractional fades a bar); an ✕ when `cross` > 0. */
const Signal: React.FC<{ level: number; cross?: number; size?: number; color?: string }> = ({
  level, cross = 0, size = 220, color = TEXT,
}) => {
  const bw = size * 0.16;
  const gap = size * 0.1;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      {[0, 1, 2, 3].map((i) => {
        const h = size * (0.28 + i * 0.24);
        const on = Math.max(0, Math.min(1, level - i));
        return (
          <rect key={i} x={i * (bw + gap)} y={size - h} width={bw} height={h} rx={bw * 0.22}
            fill={color} opacity={0.14 + 0.86 * on} />
        );
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

/** Caption block, bottom-left: a line and an optional quieter line under it. */
const Caption: React.FC<{ title: string; sub?: string; at?: number; color?: string }> = ({ title, sub, at = 0, color = TEXT }) => {
  const f = useCurrentFrame();
  const a = k(f, at, at + 16);
  const b = k(f, at + 8, at + 24);
  return (
    <div style={{ position: "absolute", left: 140, bottom: 130, fontFamily: FONT }}>
      <div style={{ fontSize: 60, fontWeight: 600, color, letterSpacing: -1.5, opacity: a,
        transform: `translateY(${(1 - a) * 18}px)` }}>{title}</div>
      {sub ? <div style={{ fontSize: 32, color: DIM, marginTop: 12, opacity: b,
        transform: `translateY(${(1 - b) * 12}px)` }}>{sub}</div> : null}
    </div>
  );
};

type Rect = [number, number, number, number]; // x, y, w, h in source pixels

/** A real screen recording (or still) cropped to `from`, easing to `to`; no frame around it. */
const Screen: React.FC<{
  src: string; video?: boolean; sw: number; sh: number; from: Rect; to?: Rect; t0?: number; t1?: number;
  w: number; h: number; x: number; y: number; startFrom?: number; rate?: number; enter?: number;
}> = ({ src, video, sw, sh, from, to, t0 = 0, t1 = 60, w, h, x, y, startFrom = 0, rate = 1, enter = 0 }) => {
  const f = useCurrentFrame();
  const p = to ? k(f, t0, t1, 0, 1, easeInOut) : 0;
  const r = from.map((v, i) => v + ((to ?? from)[i] - v) * p) as Rect;
  const s = w / r[2];
  const e = k(f, enter, enter + 18);
  const media = { position: "absolute" as const, left: -r[0] * s, top: -r[1] * s, width: sw * s, height: sh * s };
  return (
    <div style={{ position: "absolute", left: x, top: y, width: w, height: h, overflow: "hidden", borderRadius: 22,
      background: "#000", opacity: e, transform: `translateY(${(1 - e) * 30}px)` }}>
      {video ? <OffthreadVideo src={staticFile(src)} startFrom={startFrom} playbackRate={rate} muted style={media} />
        : <Img src={staticFile(src)} style={media} />}
    </div>
  );
};

/** The payment as a sealed object: a card of scrambled bytes that never reveals its contents. */
const Sealed: React.FC<{ x: number; y: number; label?: string; red?: number; seed?: number; scale?: number }> = ({
  x, y, label, red = 0, seed = 0, scale = 1,
}) => {
  const f = useCurrentFrame();
  const chars = "0123456789abcdef";
  const rows = Array.from({ length: 4 }, (_, r) =>
    Array.from({ length: 14 }, (_, c) => chars[(Math.floor(f / 3) * 7 + r * 31 + c * 13 + seed * 17) % 16]).join(""));
  const border = red > 0 ? RED : "#3a3a37";
  return (
    <div style={{ position: "absolute", left: x, top: y, transform: `translate(-50%,-50%) scale(${scale})`,
      fontFamily: FONT, textAlign: "center" }}>
      <div style={{ width: 300, padding: "22px 24px", borderRadius: 18, background: "#141414",
        border: `2px solid ${border}`, fontFamily: "Menlo, monospace", fontSize: 21, lineHeight: 1.45,
        color: red > 0 ? RED : "#6f6f6a", letterSpacing: 1.5 }}>
        {rows.map((r, i) => <div key={i}>{r}</div>)}
      </div>
      {label ? <div style={{ marginTop: 16, fontSize: 26, color: red > 0 ? RED : DIM }}>{label}</div> : null}
    </div>
  );
};

const Sfx: React.FC<{ at: number; src: string; volume?: number }> = ({ at, src, volume = 0.8 }) => (
  <Sequence from={at} layout="none"><Audio src={staticFile(src)} volume={volume} /></Sequence>
);

// ---------------------------------------------------------------- scenes

const Hook: React.FC = () => {
  const f = useCurrentFrame();
  const level = 4 - k(f, 10, 52, 0, 4, Easing.linear);
  const cross = k(f, 56, 66);
  const t = k(f, 66, 84);
  return (
    <AbsoluteFill style={{ background: INK, justifyContent: "center", alignItems: "center", flexDirection: "column", gap: 54 }}>
      <Signal level={level} cross={cross} />
      <div style={{ fontFamily: FONT, fontSize: 72, fontWeight: 600, color: TEXT, letterSpacing: -2, opacity: t,
        transform: `translateY(${(1 - t) * 16}px)` }}>No signal.</div>
      {[10, 24, 38, 52].map((a) => <Sfx key={a} at={a} src="tick.wav" volume={0.5} />)}
      <Sfx at={56} src="drop.wav" />
    </AbsoluteFill>
  );
};

const Context: React.FC = () => {
  const f = useCurrentFrame();
  const a = k(f, 4, 22);
  const b = k(f, 20, 38);
  return (
    <AbsoluteFill style={{ background: INK, fontFamily: FONT, padding: "0 140px", justifyContent: "center" }}>
      <div style={{ position: "absolute", left: 140, top: 110 }}><Signal level={0} cross={1} size={64} color={DIM} /></div>
      <div style={{ fontSize: 110, fontWeight: 700, color: TEXT, letterSpacing: -3, opacity: a,
        transform: `translateY(${(1 - a) * 24}px)` }}>Ada sells bread.</div>
      <div style={{ fontSize: 110, fontWeight: 700, color: DIM, letterSpacing: -3, opacity: b,
        transform: `translateY(${(1 - b) * 24}px)` }}>Tunde wants to buy.</div>
    </AbsoluteFill>
  );
};

// Tunde's QR (720x1280 emulator recording): push in from the screen to the code.
const TundeQr: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Screen src="tunde-qr-last.png" sw={720} sh={1280} from={[0, 160, 720, 900]} to={[95, 330, 530, 662]}
      t0={6} t1={90} w={720} h={900} x={1060} y={90} />
    <div style={{ position: "absolute", left: 140, top: 110 }}><Signal level={0} cross={1} size={64} color={DIM} /></div>
    <Caption title="Tunde pays offline." sub="His phone signs a private Zcash payment and shows it as a code." at={10} />
    <Sfx at={4} src="tick.wav" volume={0.4} />
  </AbsoluteFill>
);

// Ada's real phone (1080x2400): the scan finishing, then home: "received, not yet settled".
const AdaChecks: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Sequence durationInFrames={60}>
      <Screen src="ada-receive.mp4" video startFrom={Math.round(18.5 * 30)} sw={1080} sh={2400}
        from={[0, 380, 1080, 1350]} w={720} h={900} x={1060} y={90} />
    </Sequence>
    <Sequence from={60}>
      <Screen src="ada-flip.mp4" video sw={1080} sh={2400} from={[0, 120, 1080, 1350]} to={[0, 760, 1080, 1350]}
        t0={10} t1={70} w={720} h={900} x={1060} y={90} />
      <Sfx at={0} src="tick.wav" volume={0.6} />
    </Sequence>
    <div style={{ position: "absolute", left: 140, top: 110 }}><Signal level={0} cross={1} size={64} color={DIM} /></div>
    <Caption title="Ada's phone checks it." sub="Airplane mode. It verifies the proof by itself." at={6} />
  </AbsoluteFill>
);

const ReceivedLine: React.FC = () => (
  <AbsoluteFill style={{ background: INK }}>
    <Screen src="ada-flip.mp4" video startFrom={60} rate={0.5} sw={1080} sh={2400} from={[0, 820, 1080, 860]}
      w={1080} h={860} x={760} y={110} />
    <Caption title="Received. Not yet settled." sub="Honest about where the money is." at={4} />
  </AbsoluteFill>
);

// The payment travels: Tunde → a stranger's phone over Bluetooth; the stranger finds signal.
const Relay: React.FC = () => {
  const f = useCurrentFrame();
  const travel = k(f, 14, 70, 0, 1, easeInOut);
  const bars = k(f, 110, 150, 0, 4, Easing.linear);
  return (
    <AbsoluteFill style={{ background: INK, fontFamily: FONT }}>
      <div style={{ position: "absolute", left: 300, top: 250, fontSize: 30, color: DIM }}>Tunde</div>
      <div style={{ position: "absolute", left: 300, top: 300 }}><Signal level={0} cross={1} size={70} color={DIM} /></div>
      <div style={{ position: "absolute", left: 1380, top: 250, fontSize: 30, color: DIM }}>A stranger's phone</div>
      <div style={{ position: "absolute", left: 1380, top: 300 }}>
        <Signal level={bars} cross={bars > 0.2 ? 0 : 1} size={70} color={bars > 0.2 ? ACCENT : DIM} />
      </div>
      <div style={{ position: "absolute", inset: 0, opacity: 1 - k(f, 140, 152) }}>
      <svg width={1920} height={1080} style={{ position: "absolute" }}>
        <line x1={420} y1={560} x2={1500} y2={560} stroke="#2a2a28" strokeWidth={2} strokeDasharray="4 14" />
      </svg>
      <Sealed x={420 + 1080 * travel} y={560} label="over Bluetooth" />
      </div>
      <Sequence from={150}>
        <Screen src="relay.mp4" video startFrom={Math.round(17 * 30)} sw={720} sh={1280}
          from={[0, 300, 720, 330]} w={960} h={440} x={480} y={420} enter={0} />
      </Sequence>
      <Caption title={f < 105 ? "A stranger's phone carries it." : "The first phone with signal sends it on."}
        sub={f < 105 ? "Encrypted. It can't read a thing." : undefined} at={f < 105 ? 4 : 105} />
      <Sfx at={14} src="whoosh.wav" volume={0.5} />
      {[110, 120, 130, 140].map((a) => <Sfx key={a} at={a} src="tick.wav" volume={0.45} />)}
      <Sfx at={150} src="chime.wav" volume={0.5} />
    </AbsoluteFill>
  );
};

// Ada back online: the activity row flips from pending to settled (13.1 s into ada-flip).
const Settled: React.FC = () => {
  const f = useCurrentFrame();
  const bars = k(f, 0, 30, 0, 4, Easing.linear);
  return (
    <AbsoluteFill style={{ background: INK }}>
      <Screen src="ada-flip.mp4" video startFrom={Math.round(11.3 * 30)} sw={1080} sh={2400}
        from={[0, 1420, 1080, 300]} w={1260} h={350} x={560} y={330} />
      <div style={{ position: "absolute", left: 140, top: 110 }}>
        <Signal level={bars} size={64} color={ACCENT} />
      </div>
      <Caption title="Settled." sub="Mined in block 4,544,461 on the Zcash testnet." at={56} />
      <Sfx at={54} src="chime.wav" volume={0.7} />
    </AbsoluteFill>
  );
};

const Proof: React.FC = () => {
  const f = useCurrentFrame();
  const shown = Math.floor(k(f, 0, 70, 0, 1, Easing.linear) * 520);
  const rows: [string, number][] = [["Who paid", 84], ["Who was paid", 100], ["How much", 116]];
  return (
    <AbsoluteFill style={{ background: INK, fontFamily: FONT }}>
      <div style={{ position: "absolute", left: 140, top: 150, width: 820, fontFamily: "Menlo, monospace",
        fontSize: 24, lineHeight: 1.6, color: "#4d4d49", wordBreak: "break-all" }}>{TX_HEX.slice(0, shown)}</div>
      <div style={{ position: "absolute", left: 1060, top: 210 }}>
        <div style={{ fontSize: 30, color: DIM, marginBottom: 34 }}>What the blockchain sees</div>
        {rows.map(([label, at]) => {
          const a = k(f, at, at + 16);
          return (
            <div key={label} style={{ display: "flex", gap: 40, alignItems: "baseline", padding: "20px 0",
              borderTop: "1px solid #222", opacity: a, transform: `translateX(${(1 - a) * 20}px)` }}>
              <div style={{ width: 300, fontSize: 40, color: DIM }}>{label}</div>
              <div style={{ fontSize: 40, fontWeight: 600, color: ACCENT }}>hidden</div>
            </div>
          );
        })}
      </div>
      <Caption title="Private, even on chain." sub="Transaction 5e89ff96…d41710, as the network stores it." at={130} />
      {rows.map(([l, at]) => <Sfx key={l} at={at} src="tick.wav" volume={0.45} />)}
    </AbsoluteFill>
  );
};

// The cheat: two payments of the same money meet; the real alert banner.
const Cheat: React.FC = () => {
  const f = useCurrentFrame();
  const meet = k(f, 10, 64, 0, 1, easeInOut);
  const hit = f >= 64;
  const shake = hit && f < 76 ? Math.sin(f * 2.4) * 10 * (76 - f) / 12 : 0;
  return (
    <AbsoluteFill style={{ background: INK }}>
      <Sequence durationInFrames={100}>
        <div style={{ position: "absolute", inset: 0, transform: `translateX(${shake}px)` }}>
          <Sealed x={520 + 300 * meet} y={470} label="Payment A · note 1162c8…" red={hit ? 1 : 0} seed={1} />
          <Sealed x={1400 - 300 * meet} y={470} label="Payment B · note 1162c8…" red={hit ? 1 : 0} seed={2} />
        </div>
        <Caption title="Same money, spent twice." sub="A copied wallet on a second phone." at={6} color={hit ? RED : TEXT} />
        <Sfx at={10} src="whoosh.wav" volume={0.4} />
        <Sfx at={64} src="alert.wav" volume={0.8} />
      </Sequence>
      <Sequence from={100}>
        <Screen src="alert.png" sw={720} sh={1280} from={[0, 120, 720, 330]} to={[20, 160, 680, 312]} t0={0} t1={90}
          w={1300} h={596} x={310} y={110} />
        <Caption title="Every phone nearby is warned." sub="0.5 seconds after the second payment, measured on the test run." at={8} />
      </Sequence>
    </AbsoluteFill>
  );
};

const End: React.FC = () => {
  const f = useCurrentFrame();
  const a = k(f, 6, 26);
  const b = k(f, 26, 44);
  return (
    <AbsoluteFill style={{ background: INK, justifyContent: "center", alignItems: "center", fontFamily: FONT }}>
      <div style={{ display: "flex", alignItems: "center", gap: 40, opacity: a, transform: `translateY(${(1 - a) * 20}px)` }}>
        <Signal level={0} cross={1} size={96} color={TEXT} />
        <div style={{ fontSize: 88, fontWeight: 700, color: TEXT, letterSpacing: -2.5 }}>Zcash that works without signal.</div>
      </div>
      <div style={{ marginTop: 40, fontSize: 30, color: DIM, opacity: b }}>
        ZECATHON · Shielded Payments · recorded on the Zcash testnet
      </div>
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- timeline (hard cuts)

const SCENES: [React.FC, number][] = [
  [Hook, 100], [Context, 84], [TundeQr, 120], [AdaChecks, 150], [ReceivedLine, 90], [Relay, 240],
  [Settled, 150], [Proof, 210], [Cheat, 230], [End, 140],
];
export const LAUNCH_FRAMES = SCENES.reduce((a, [, d]) => a + d, 0);

export const Launch: React.FC = () => {
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
