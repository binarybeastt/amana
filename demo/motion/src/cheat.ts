// The double-spend take (stress/p6/shoot_cheat.sh, 2026-10-27, third take, SETTLE=25): three
// emulators, clips lined up on the second payment. From demo/raw/cheat.env (host clock, ms):
// inject 1793059739727; clip starts A 1793059733439, B 1793059734522, C 1793059735679. C's banner
// first shows at 28.25 s into its clip (first red pixels), i.e. 24.2 s after the second payment; A's at
// 30.55 s and B's at 29.0 s in their own clips (28.31 and 27.84 in C's time): all three within 0.5 s.
type Seg = { start: number; end: number; rate?: number };
type Dev = { w: number; h: number };
const INJECT_C = 4.05; // the second payment, in C's clip time
const ALERT_C = 28.25;
// C's timeline: lead-in, the wait at 8×, the alert at real speed.
const C: Seg[] = [{ start: 1.55, end: 7.05 }, { start: 7.05, end: 27.25, rate: 8 }, { start: 27.25, end: 33.25 }];
const shift = (segs: Seg[], d: number) => segs.map((s) => ({ ...s, start: s.start + d, end: s.end + d }));
const frames = (s: Seg) => Math.round(((s.end - s.start) / (s.rate ?? 1)) * 30);

export const CHEAT = {
  phones: [
    { src: "cheat-5554.mp4", dev: { w: 1080, h: 2400 } as Dev, segs: shift(C, 2.24), label: "Phone 1 · pays Seller A", alertC: 28.31 },
    { src: "cheat-5556.mp4", dev: { w: 720, h: 1280 } as Dev, segs: shift(C, 1.157), label: "Phone 2 · same wallet, pays Seller B", alertC: 27.84 },
    { src: "cheat-5558.mp4", dev: { w: 720, h: 1280 } as Dev, segs: C, label: "A phone nearby", alertC: ALERT_C },
  ],
  segsC: C,
  injectC: INJECT_C,
  alertC: ALERT_C,
  alertFrame: frames(C[0]) + frames(C[1]) + Math.round((ALERT_C - C[2].start) * 30),
  frameOf: (tC: number) => frames(C[0]) + frames(C[1]) + Math.round((tC - C[2].start) * 30),
  fastFrom: frames(C[0]),
  fastTo: frames(C[0]) + frames(C[1]),
  duration: frames(C[0]) + frames(C[1]) + frames(C[2]) + 20,
};
