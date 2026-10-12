import { ScrollTrigger } from "gsap/ScrollTrigger";
import { fontsLoaded, pageShown } from "./dom";
import { frame, type Moods } from "./frame";
import { Intro } from "./intro";
import { mountScenes } from "./scenes";
import { Stage } from "./stage";
import { Curtain } from "./transition";

/**
 * Every page's motion, started by its layout (layouts/Page.astro): the stage, the intro a page opens with or the
 * curtain a link opens it through, the frame, and whichever scenes the page's components declared. Text is
 * split once the fonts are in; the page's opening plays once it's on screen and the intro or the curtain has gone.
 */
async function start(): Promise<void> {
  const intro = Intro.find();
  const stage = new Stage(intro?.entered ?? null);
  const curtain = Curtain.find(stage);
  curtain?.listen();
  // Whatever goes wrong getting the page ready, it doesn't stay under the curtain, or the intro.
  const moods = await ready(stage, intro).finally(() => {
    void curtain?.lift();
    intro?.leave();
  });
  stage.arrive(() => moods.begin());
}

/**
 * Gets the page ready: its text split once the fonts are in, its scenes and frame mounted, it on screen, and the
 * scenery's pictures ready. Under the intro on a visit's first page the mark sweeps in once the scenery's code is
 * in, with nothing else running, and then holds until everything the page draws is ready; under the curtain the page
 * waits for them only a moment, and they come in as they can.
 */
async function ready(stage: Stage, intro: Intro | null): Promise<Moods> {
  await fontsLoaded();
  stage.reveals.prepare();
  mountScenes(stage);
  const moods = frame(stage);
  ScrollTrigger.refresh();
  await pageShown();
  if (intro != null) {
    await stage.loaded;
    await intro.hold();
  }
  await stage.prepared(intro == null ? 2500 : 15000);
  return moods;
}

void start();
