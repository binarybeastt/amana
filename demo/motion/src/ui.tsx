import React from "react";
import {
  AbsoluteFill, Img, OffthreadVideo, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig,
} from "remotion";

export const C = {
  bg: "#07080a",
  panel: "#121317",
  line: "#23252b",
  text: "#f4f4f5",
  dim: "#8d8e96",
  green: "#3ddc84",
  zec: "#f4b728",
  red: "#ff4d57",
  blue: "#5aa9ff",
};

export const FontFace: React.FC = () => (
  <style>{`@font-face { font-family: Inter; src: url(${staticFile("inter.ttf")}); }`}</style>
);

export const font: React.CSSProperties = { fontFamily: "Inter, Helvetica Neue, sans-serif" };

/** 0 → 1 spring starting at `delay` frames. */
export const useSpring = (delay = 0, damping = 200, durationInFrames?: number) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - delay, fps, config: { damping }, durationInFrames });
};

/** Dark background with a slowly drifting glow and a faint dot grid. */
export const Backdrop: React.FC<{ tint?: string }> = ({ tint = C.zec }) => {
  const frame = useCurrentFrame();
  const x = 50 + 18 * Math.sin(frame / 90);
  const y = 40 + 12 * Math.cos(frame / 110);
  return (
    <AbsoluteFill style={{ background: C.bg }}>
      <AbsoluteFill
        style={{ background: `radial-gradient(900px 600px at ${x}% ${y}%, ${tint}22, transparent 70%)` }}
      />
      <AbsoluteFill
        style={{
          backgroundImage: "radial-gradient(#ffffff10 1.2px, transparent 1.2px)",
          backgroundSize: "36px 36px",
          maskImage: "radial-gradient(ellipse at center, black 30%, transparent 75%)",
        }}
      />
    </AbsoluteFill>
  );
};

/** Words rise and fade in one after another. */
export const Words: React.FC<{
  text: string; delay?: number; size?: number; weight?: number; color?: string; stagger?: number;
  style?: React.CSSProperties;
}> = ({ text, delay = 0, size = 96, weight = 700, color = C.text, stagger = 4, style }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <div style={{ ...font, fontSize: size, fontWeight: weight, color, letterSpacing: -size * 0.025,
      lineHeight: 1.08, display: "flex", flexWrap: "wrap", gap: `0 ${size * 0.26}px`, ...style }}>
      {text.split(" ").map((w, i) => {
        const p = spring({ frame: frame - delay - i * stagger, fps, config: { damping: 18, mass: 0.6 } });
        return (
          <span key={i} style={{ display: "inline-block", opacity: Math.min(1, p * 1.4),
            transform: `translateY(${(1 - p) * size * 0.45}px)` }}>{w}</span>
        );
      })}
    </div>
  );
};

/** Small uppercase label with an accent dot. */
export const Kicker: React.FC<{ text: string; color?: string; delay?: number }> = ({ text, color = C.zec, delay = 0 }) => {
  const p = useSpring(delay);
  return (
    <div style={{ ...font, display: "flex", alignItems: "center", gap: 14, fontSize: 26, fontWeight: 600,
      letterSpacing: 4, textTransform: "uppercase", color, opacity: p, transform: `translateX(${(1 - p) * -30}px)` }}>
      <div style={{ width: 10, height: 10, borderRadius: 5, background: color }} />
      {text}
    </div>
  );
};

/** Step header in the top-left corner: "02  Pay offline". */
export const Step: React.FC<{ n: string; title: string }> = ({ n, title }) => {
  const p = useSpring(2);
  return (
    <div style={{ position: "absolute", left: 110, top: 86, display: "flex", alignItems: "baseline", gap: 22,
      ...font, opacity: p, transform: `translateY(${(1 - p) * -20}px)` }}>
      <span style={{ fontSize: 30, fontWeight: 600, color: C.zec, letterSpacing: 2 }}>{n}</span>
      <span style={{ fontSize: 46, fontWeight: 700, color: C.text, letterSpacing: -1 }}>{title}</span>
    </div>
  );
};

/** Status pill, e.g. OFFLINE / AIRPLANE MODE / ONLINE. */
export const Pill: React.FC<{ text: string; color: string; delay?: number }> = ({ text, color, delay = 0 }) => {
  const p = useSpring(delay, 14);
  return (
    <div style={{ ...font, display: "inline-flex", alignItems: "center", gap: 10, padding: "10px 20px",
      borderRadius: 999, background: `${color}1f`, border: `1.5px solid ${color}66`, color, fontSize: 22,
      fontWeight: 600, letterSpacing: 1.5, textTransform: "uppercase", transform: `scale(${0.6 + 0.4 * p})`, opacity: p }}>
      <div style={{ width: 9, height: 9, borderRadius: 5, background: color }} />
      {text}
    </div>
  );
};

/** A phone mockup that plays a screen recording (or shows a still). */
export const Phone: React.FC<{
  video?: string; image?: string; rate?: number; height?: number; label?: string; sub?: string;
  pill?: { text: string; color: string }; delay?: number; glow?: string; children?: React.ReactNode;
}> = ({ video, image, rate = 1, height = 760, label, sub, pill, delay = 0, glow, children }) => {
  const p = useSpring(delay, 16);
  const w = height * 0.5;
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 26,
      opacity: p, transform: `translateY(${(1 - p) * 80}px) scale(${0.92 + 0.08 * p})` }}>
      <div style={{ position: "relative", width: w, height, borderRadius: 58, padding: 14,
        background: "linear-gradient(145deg,#2a2c33,#111215)",
        boxShadow: `0 40px 120px #000c, 0 0 0 1.5px #3a3c44${glow ? `, 0 0 90px ${glow}55` : ""}` }}>
        <div style={{ width: "100%", height: "100%", borderRadius: 46, overflow: "hidden", background: "#000" }}>
          {video ? <OffthreadVideo src={staticFile(video)} playbackRate={rate} muted
            style={{ width: "100%", height: "100%", objectFit: "cover", objectPosition: "top" }} /> : null}
          {image ? <Img src={staticFile(image)} style={{ width: "100%", height: "100%", objectFit: "cover", objectPosition: "top" }} /> : null}
        </div>
        {children}
      </div>
      {label ? (
        <div style={{ ...font, textAlign: "center" }}>
          <div style={{ fontSize: 34, fontWeight: 700, color: C.text }}>{label}</div>
          {sub ? <div style={{ fontSize: 24, color: C.dim, marginTop: 6 }}>{sub}</div> : null}
          {pill ? <div style={{ marginTop: 16 }}><Pill text={pill.text} color={pill.color} delay={delay + 12} /></div> : null}
        </div>
      ) : null}
    </div>
  );
};

/** Callout text block that slides in from the right. */
export const Callout: React.FC<{ title: string; body?: string; delay?: number; color?: string }> = ({
  title, body, delay = 0, color = C.zec,
}) => {
  const p = useSpring(delay, 18);
  return (
    <div style={{ ...font, opacity: p, transform: `translateX(${(1 - p) * 60}px)`, borderLeft: `4px solid ${color}`,
      paddingLeft: 28, maxWidth: 620 }}>
      <div style={{ fontSize: 46, fontWeight: 700, color: C.text, letterSpacing: -1, lineHeight: 1.12 }}>{title}</div>
      {body ? <div style={{ fontSize: 28, color: C.dim, marginTop: 14, lineHeight: 1.4 }}>{body}</div> : null}
    </div>
  );
};

/** Dots travelling along a straight line from (x1,y1) to (x2,y2), looping. */
export const Beam: React.FC<{ x1: number; y1: number; x2: number; y2: number; color: string; start?: number;
  label?: string; dots?: number }> = ({ x1, y1, x2, y2, color, start = 0, label, dots = 6 }) => {
  const frame = useCurrentFrame();
  const t = frame - start;
  if (t < 0) return null;
  const appear = interpolate(t, [0, 12], [0, 1], { extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ pointerEvents: "none", opacity: appear }}>
      <svg width={1920} height={1080}>
        <line x1={x1} y1={y1} x2={x2} y2={y2} stroke={`${color}44`} strokeWidth={3} strokeDasharray="10 12" />
        {Array.from({ length: dots }).map((_, i) => {
          const u = ((t / 40 + i / dots) % 1);
          return <circle key={i} cx={x1 + (x2 - x1) * u} cy={y1 + (y2 - y1) * u} r={7}
            fill={color} opacity={Math.sin(u * Math.PI)} />;
        })}
      </svg>
      {label ? (
        <div style={{ ...font, position: "absolute", left: (x1 + x2) / 2, top: (y1 + y2) / 2 - 64,
          transform: "translateX(-50%)", color, fontSize: 24, fontWeight: 600, letterSpacing: 2,
          textTransform: "uppercase", background: C.bg, padding: "6px 16px", borderRadius: 999,
          border: `1.5px solid ${color}55` }}>{label}</div>
      ) : null}
    </AbsoluteFill>
  );
};
