import React from "react";
import { Composition } from "remotion";
import { Demo, DEMO_FRAMES } from "./Demo";

export const Root: React.FC = () => (
  <Composition id="Demo" component={Demo} durationInFrames={DEMO_FRAMES} fps={30} width={1920} height={1080} />
);
