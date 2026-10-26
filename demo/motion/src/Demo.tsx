import React from "react";
import { AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame } from "remotion";
import { TransitionSeries, linearTiming } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { slide } from "@remotion/transitions/slide";
import {
  Backdrop, Beam, C, Callout, FontFace, Kicker, Phone, Pill, Step, Words, font, useSpring,
} from "./ui";

const FPS = 30;
const T = 18; // transition length (frames)

// Scene lengths (frames). Footage rates are set so each clip fills its scene.
const S = {
  intro: 135, problem: 165, cast: 150, prepare: 255, pay: 300, relay: 255, settle: 210, chain: 195,
  cheat: 240, limits: 210, outro: 180,
};
const CLIP = { prepare: 38.3, tundeQr: 37.1, adaReceive: 23.7, relay: 20.9, settled: 37.1 }; // seconds
const rate = (clipSec: number, frames: number) => clipSec / (frames / FPS - 0.6);

export const DEMO_FRAMES = Object.values(S).reduce((a, b) => a + b, 0) - T * (Object.keys(S).length - 1);

const Center: React.FC<{ children: React.ReactNode; style?: React.CSSProperties }> = ({ children, style }) => (
  <AbsoluteFill style={{ justifyContent: "center", alignItems: "center", ...style }}>{children}</AbsoluteFill>
);

// ---------------------------------------------------------------- scenes

const Intro: React.FC = () => {
  const p = useSpring(0, 12);
  const frame = useCurrentFrame();
  const ring = interpolate(frame, [0, 60], [0.6, 1.25], { extrapolateRight: "clamp" });
  return (
    <AbsoluteFill>
      <Backdrop />
      <Center style={{ flexDirection: "column", gap: 44 }}>
        <div style={{ position: "relative", width: 150, height: 150 }}>
          <div style={{ position: "absolute", inset: 0, borderRadius: 75, border: `2px solid ${C.zec}`,
            transform: `scale(${ring})`, opacity: 1.25 - ring }} />
          <div style={{ ...font, width: 150, height: 150, borderRadius: 75, background: C.zec, color: "#111",
            fontSize: 92, fontWeight: 800, display: "flex", alignItems: "center", justifyContent: "center",
            transform: `scale(${p}) rotate(${(1 - p) * -90}deg)` }}>Z</div>
        </div>
        <Words text="Zcash that works without signal" delay={14} size={104} style={{ justifyContent: "center" }} />
        <Words text="Private payments, phone to phone. Settle later." delay={34} size={40} weight={400}
          color={C.dim} stagger={2} style={{ justifyContent: "center" }} />
      </Center>
    </AbsoluteFill>
  );
};

const Problem: React.FC = () => (
  <AbsoluteFill>
    <Backdrop tint={C.red} />
    <AbsoluteFill style={{ padding: "0 180px", justifyContent: "center", gap: 18 }}>
      <Kicker text="The problem" color={C.red} />
      <Words text="The power's out." delay={6} size={110} />
      <Words text="The data's down." delay={22} size={110} />
      <Words text="People still need to pay." delay={38} size={110} color={C.zec} />
      <Words text="Cash gets stolen. Mobile money needs a network." delay={70} size={38} weight={400}
        color={C.dim} stagger={2} style={{ marginTop: 26 }} />
    </AbsoluteFill>
  </AbsoluteFill>
);

const Cast: React.FC = () => (
  <AbsoluteFill>
    <Backdrop />
    <div style={{ position: "absolute", left: 110, top: 86 }}><Kicker text="The demo · Zcash testnet" /></div>
    <Center style={{ flexDirection: "row", gap: 120, paddingTop: 40 }}>
      <Phone image="ada-still.png" height={560} label="Ada · sells bread" sub="real Android phone" delay={6}
        pill={{ text: "Receiver", color: C.green }} />
      <Phone image="tunde-still.png" height={560} label="Tunde · buyer" sub="emulator" delay={16}
        pill={{ text: "Payer", color: C.zec }} />
      <Phone image="relay-still.png" height={560} label="A stranger" sub="emulator, just passing by" delay={26}
        pill={{ text: "Relay", color: C.blue }} />
    </Center>
  </AbsoluteFill>
);

const Prepare: React.FC = () => (
  <AbsoluteFill>
    <Backdrop />
    <Step n="01" title="Prepare offline cash" />
    <Center style={{ flexDirection: "row", gap: 150, paddingTop: 60 }}>
      <Phone video="tunde-prepare.mp4" rate={rate(CLIP.prepare, S.prepare)} height={780} delay={4}
        glow={C.zec} />
      <div style={{ display: "flex", flexDirection: "column", gap: 60 }}>
        <Callout title="Set money aside while online" body="Like drawing cash from an ATM. Tunde picks how much." delay={18} />
        <Callout title="Split into notes, like bills" body="One private self-payment, ready about a minute after it's mined." delay={70} color={C.green} />
      </div>
    </Center>
  </AbsoluteFill>
);

const Pay: React.FC = () => (
  <AbsoluteFill>
    <Backdrop />
    <Step n="02" title="Pay with no internet" />
    <div style={{ position: "absolute", left: 360, top: 225 }}>
      <Phone video="tunde-qr.mp4" rate={rate(CLIP.tundeQr, S.pay)} height={600} label="Tunde"
        pill={{ text: "Offline", color: C.red }} delay={4} />
    </div>
    <div style={{ position: "absolute", left: 1240, top: 225 }}>
      <Phone video="ada-receive.mp4" rate={rate(CLIP.adaReceive, S.pay)} height={600} label="Ada · real phone"
        pill={{ text: "Airplane mode", color: C.red }} delay={12} glow={C.green} />
    </div>
    <Beam x1={690} y1={525} x2={1225} y2={525} color={C.zec} start={40} label="Signed payment · QR" />
    <div style={{ position: "absolute", left: 112, top: 156 }}>
      <Words text="Signed on Tunde's phone. Verified on Ada's. No network involved." delay={150} size={28}
        weight={500} color={C.dim} stagger={2} />
    </div>
  </AbsoluteFill>
);

const Relay: React.FC = () => {
  const frame = useCurrentFrame();
  const online = frame > 150;
  return (
    <AbsoluteFill>
      <Backdrop tint={C.blue} />
      <Step n="03" title="Carried over Bluetooth" />
      <div style={{ position: "absolute", left: 360, top: 225 }}>
        <Phone image="tunde-qr-last.png" height={600} label="Tunde" pill={{ text: "Offline", color: C.red }} delay={2} />
      </div>
      <div style={{ position: "absolute", left: 1240, top: 225 }}>
        <Phone video="relay.mp4" rate={rate(CLIP.relay, S.relay)} height={600} label="A stranger's phone"
          pill={online ? { text: "Got signal · broadcast", color: C.green } : { text: "Offline", color: C.red }}
          delay={10} glow={online ? C.green : C.blue} />
      </div>
      <Beam x1={690} y1={525} x2={1225} y2={525} color={C.blue} start={24} label="Bluetooth" />
      <div style={{ position: "absolute", left: 112, top: 156 }}>
        <Words text="Encrypted end to end: the stranger carries it, but can't read it." delay={60} size={28}
          weight={500} color={C.dim} stagger={2} />
      </div>
    </AbsoluteFill>
  );
};

const Settle: React.FC = () => (
  <AbsoluteFill>
    <Backdrop tint={C.green} />
    <Step n="04" title="Settled" />
    <Center style={{ flexDirection: "row", gap: 150, paddingTop: 60 }}>
      <Phone video="ada-settled.mp4" rate={rate(CLIP.settled, S.settle)} height={780} delay={4} glow={C.green} />
      <div style={{ display: "flex", flexDirection: "column", gap: 60 }}>
        <Callout title="Mined at block 4,544,461" body="The first phone with signal broadcast it." delay={16} color={C.green} />
        <Callout title="Ada reconnects: settled" body="Her balance moves from 'received' to 'settled'." delay={60} color={C.green} />
      </div>
    </Center>
  </AbsoluteFill>
);

const Chain: React.FC = () => {
  const rows: [string, string, boolean][] = [
    ["Transaction", "5e89ff96…d41710", false],
    ["Block", "4,544,461 · testnet", false],
    ["Shielded actions", "2 (Ironwood)", false],
    ["Who paid", "hidden", true],
    ["Who was paid", "hidden", true],
    ["How much", "hidden", true],
  ];
  return (
    <AbsoluteFill>
      <Backdrop tint={C.green} />
      <AbsoluteFill style={{ padding: "0 240px", justifyContent: "center" }}>
        <Kicker text="What the blockchain sees" color={C.green} />
        <div style={{ marginTop: 40 }}>
          {rows.map(([k, v, hidden], i) => <ChainRow key={k} k={k} v={v} hidden={hidden} delay={10 + i * 10} />)}
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const ChainRow: React.FC<{ k: string; v: string; hidden: boolean; delay: number }> = ({ k, v, hidden, delay }) => {
  const p = useSpring(delay, 18);
  const pop = useSpring(delay + 8, 10);
  return (
    <div style={{ ...font, display: "flex", alignItems: "center", padding: "22px 0", borderBottom: `1px solid ${C.line}`,
      opacity: p, transform: `translateY(${(1 - p) * 24}px)` }}>
      <div style={{ width: 560, fontSize: 40, color: C.dim }}>{k}</div>
      {hidden ? (
        <div style={{ fontSize: 34, fontWeight: 700, color: C.green, padding: "8px 26px", borderRadius: 999,
          background: `${C.green}1f`, border: `1.5px solid ${C.green}66`, transform: `scale(${pop})` }}>{v}</div>
      ) : <div style={{ fontSize: 40, color: C.text, fontWeight: 500 }}>{v}</div>}
    </div>
  );
};

const Cheat: React.FC = () => {
  const frame = useCurrentFrame();
  const hit = 120; // frame the second spend lands
  const a = useSpring(10, 16);
  const b = useSpring(40, 16);
  const merge = interpolate(frame, [hit - 30, hit], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const alert = frame >= hit + 15;
  const pulse = alert ? (frame - hit - 15) % 30 / 30 : 0;
  const timer = interpolate(frame, [hit, hit + 15], [0, 0.5], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const card = (title: string, sub: string, p: number, dy: number) => (
    <div style={{ ...font, width: 560, padding: "26px 32px", borderRadius: 26, background: C.panel,
      border: `1.5px solid ${alert ? C.red : C.line}`, opacity: p,
      transform: `translateX(${(1 - p) * -60}px) translateY(${dy}px)` }}>
      <div style={{ fontSize: 34, fontWeight: 700, color: C.text }}>{title}</div>
      <div style={{ fontSize: 26, color: C.dim, marginTop: 8 }}>{sub}</div>
      <div style={{ marginTop: 16, fontSize: 24, fontWeight: 600, color: alert ? C.red : C.zec,
        fontFamily: "Menlo, monospace" }}>note 1162c859…</div>
    </div>
  );
  return (
    <AbsoluteFill>
      <Backdrop tint={alert ? C.red : C.zec} />
      <Step n="05" title="The cheat" />
      <div style={{ position: "absolute", left: 200, top: 250, display: "flex", flexDirection: "column", gap: 60 }}>
        {card("Payment A", "to the first shop", a, merge * 40)}
        {card("Payment B", "same money, second phone", b, merge * -40)}
      </div>
      <div style={{ position: "absolute", left: 1150, top: 170 }}>
        <div style={{ position: "relative" }}>
          {alert ? <div style={{ position: "absolute", left: -40 - pulse * 60, top: -40 - pulse * 60,
            right: -40 - pulse * 60, bottom: 90 - pulse * 60, borderRadius: 90, border: `3px solid ${C.red}`,
            opacity: 1 - pulse }} /> : null}
          <Phone image={alert ? "alert.png" : "calm.png"} height={680} label="A phone nearby"
            pill={alert ? { text: "Double spend!", color: C.red } : { text: "Offline", color: C.dim }} delay={4}
            glow={alert ? C.red : undefined} />
        </div>
      </div>
      <div style={{ ...font, position: "absolute", left: 200, bottom: 110, opacity: frame > hit ? 1 : 0 }}>
        <span style={{ fontSize: 96, fontWeight: 800, color: C.red }}>{timer.toFixed(1)} s</span>
        <span style={{ fontSize: 32, color: C.dim, marginLeft: 24 }}>from second payment to alert (measured)</span>
      </div>
    </AbsoluteFill>
  );
};

const Limits: React.FC = () => {
  const items: [string, string, string][] = [
    ["01", "Stop honest mistakes", "The app never signs the same note twice."],
    ["02", "Catch cheats in seconds", "Every nearby phone hears the warning."],
    ["03", "Cap the loss", "Small offline amounts, settled fast."],
  ];
  return (
    <AbsoluteFill>
      <Backdrop />
      <AbsoluteFill style={{ padding: "0 160px", justifyContent: "center" }}>
        <Kicker text="Honest about the limits" />
        <Words text="No offline system can make double spending impossible." delay={6} size={58} style={{ marginTop: 24, maxWidth: 1500 }} />
        <div style={{ display: "flex", gap: 40, marginTop: 70 }}>
          {items.map(([n, t, b], i) => <LimitCard key={n} n={n} t={t} b={b} delay={36 + i * 12} />)}
        </div>
        <Words text="And the strangers carrying your money learn nothing." delay={110} size={36} weight={500}
          color={C.green} stagger={2} style={{ marginTop: 60 }} />
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const LimitCard: React.FC<{ n: string; t: string; b: string; delay: number }> = ({ n, t, b, delay }) => {
  const p = useSpring(delay, 16);
  return (
    <div style={{ ...font, flex: 1, padding: "40px 40px", borderRadius: 30, background: C.panel,
      border: `1.5px solid ${C.line}`, opacity: p, transform: `translateY(${(1 - p) * 50}px)` }}>
      <div style={{ fontSize: 28, fontWeight: 700, color: C.zec }}>{n}</div>
      <div style={{ fontSize: 40, fontWeight: 700, color: C.text, marginTop: 18 }}>{t}</div>
      <div style={{ fontSize: 26, color: C.dim, marginTop: 12, lineHeight: 1.4 }}>{b}</div>
    </div>
  );
};

const Outro: React.FC = () => (
  <AbsoluteFill>
    <Backdrop />
    <Center style={{ flexDirection: "column", gap: 34 }}>
      <Kicker text="Today ZEC · tomorrow a shielded dollar" delay={4} />
      <Words text="Pay offline. Privately." delay={14} size={130} style={{ justifyContent: "center" }} />
      <Words text="For markets that run on cash and patchy data." delay={40} size={42} weight={400}
        color={C.dim} stagger={2} style={{ justifyContent: "center" }} />
    </Center>
  </AbsoluteFill>
);

// ---------------------------------------------------------------- sequence

const scenes: [keyof typeof S, React.FC][] = [
  ["intro", Intro], ["problem", Problem], ["cast", Cast], ["prepare", Prepare], ["pay", Pay],
  ["relay", Relay], ["settle", Settle], ["chain", Chain], ["cheat", Cheat], ["limits", Limits], ["outro", Outro],
];

export const Demo: React.FC = () => (
  <AbsoluteFill style={{ background: C.bg }}>
    <FontFace />
    <TransitionSeries>
      {scenes.flatMap(([key, Scene], i) => {
        const items = [
          <TransitionSeries.Sequence key={key} durationInFrames={S[key]}><Scene /></TransitionSeries.Sequence>,
        ];
        if (i < scenes.length - 1) {
          const pres = i % 3 === 2 ? slide({ direction: "from-right" }) : fade();
          items.push(<TransitionSeries.Transition key={`${key}-t`} presentation={pres} timing={linearTiming({ durationInFrames: T })} />);
        }
        return items;
      })}
    </TransitionSeries>
  </AbsoluteFill>
);
