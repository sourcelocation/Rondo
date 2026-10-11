import { ScrollTrigger } from "gsap/ScrollTrigger";
import { fontsLoaded, pageShown } from "./dom";
import { frame, type Moods } from "./frame";
import { mountScenes } from "./scenes";
import { Stage } from "./stage";
import { Curtain } from "./transition";

/**
 * Every page's motion, started by its layout (layouts/Page.astro): the stage, the curtain pages open through, the frame,
 * and whichever scenes the page's components declared. Text is split once the fonts are in; the page's opening
 * plays once it's on screen and any curtain has lifted off it.
 */
async function start(): Promise<void> {
  const stage = new Stage();
  const curtain = Curtain.find(stage);
  curtain?.listen();
  // Whatever goes wrong getting the page ready, it doesn't stay under the curtain.
  const moods = await ready(stage).finally(() => curtain?.lift());
  stage.arrive(() => moods.begin());
}

/** Gets the page ready: its text split once the fonts are in, its scenes and frame mounted, and it on screen. */
async function ready(stage: Stage): Promise<Moods> {
  await fontsLoaded();
  stage.reveals.prepare();
  mountScenes(stage);
  const moods = frame(stage);
  ScrollTrigger.refresh();
  await pageShown();
  return moods;
}

void start();
