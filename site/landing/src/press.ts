import { strToU8, zipSync } from "fflate";
import sharp from "sharp";
import appIcon from "../../../brand/dist/svg/app-icon.svg?raw";
import markOnDark from "../../../brand/dist/svg/mark-on-dark.svg?raw";
import mark from "../../../brand/dist/svg/mark.svg?raw";
import { artwork, crops } from "./brand";
import { about, facts } from "./content/press";
import { releaseAt } from "./launch/launch";

/**
 * The press kit's files (served under /press, pages/press/[file].ts), made at build time from the brand's artwork:
 * the logo cropped tight in ink and reversed, the brand's own marks and app icon, PNGs of each, and all of it in one
 * ZIP with the words to quote. Nothing here is drawn by hand, so the files follow the brand.
 */

/** A file of the kit: its name, what kind it is, and how it's made. */
export interface PressFile {
  readonly name: string;
  readonly type: string;
  readonly make: () => Promise<Uint8Array>;
}

/** The whole logo, cropped to its edges, in one colour. */
function logo(colour: string): string {
  const [, , width, height] = crops.logo.split(" ");
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${crops.logo}" width="${width ?? ""}" height="${height ?? ""}" ` +
    `color="${colour}" role="img" aria-label="Rondo"><title>Rondo</title>${artwork}</svg>`
  );
}

const svg = (source: string) => async () => Promise.resolve(strToU8(source));

const png = (source: string, width: number) => async () =>
  new Uint8Array(
    await sharp(Buffer.from(source), { density: 300 }).resize({ width }).png({ compressionLevel: 9 }).toBuffer(),
  );

const logoInk = logo("#000000");
const logoReversed = logo("#FFFFFF");

/** The images, in the order the page lists them. */
const images: readonly PressFile[] = [
  { name: "rondo-logo.svg", type: "image/svg+xml", make: svg(logoInk) },
  { name: "rondo-logo.png", type: "image/png", make: png(logoInk, 2608) },
  { name: "rondo-logo-reversed.svg", type: "image/svg+xml", make: svg(logoReversed) },
  { name: "rondo-logo-reversed.png", type: "image/png", make: png(logoReversed, 2608) },
  { name: "rondo-mark.svg", type: "image/svg+xml", make: svg(mark) },
  { name: "rondo-mark.png", type: "image/png", make: png(mark, 1024) },
  { name: "rondo-mark-reversed.svg", type: "image/svg+xml", make: svg(markOnDark) },
  { name: "rondo-mark-reversed.png", type: "image/png", make: png(markOnDark, 1024) },
  { name: "rondo-app-icon.svg", type: "image/svg+xml", make: svg(appIcon) },
  { name: "rondo-app-icon.png", type: "image/png", make: png(appIcon, 1024) },
];

/** The words of the kit, as plain text, for the ZIP. */
function readme(): string {
  return [
    "Rondo: press kit",
    "",
    "In a sentence",
    about.sentence,
    "",
    "In a paragraph",
    `${about.paragraph} ${about.launch[releaseAt(Date.now())]}`,
    "",
    ...facts.map((fact) => `${fact.term}: ${fact.value}`),
    "",
    "Using the logo",
    "The mark is one colour: ink on light backgrounds, and the reversed files on dark ones. Please don't recolour, stretch, turn or outline it, or add effects, and leave room around it. The Rondo name, mark and logo are trademarks of the Rondo project and aren't covered by the AGPL-3.0: use them to refer to Rondo.",
    "",
  ].join("\n");
}

const kit: PressFile = {
  name: "rondo-press-kit.zip",
  type: "application/zip",
  make: async () => {
    const files: Record<string, Uint8Array> = { "Rondo press kit/About Rondo.txt": strToU8(readme()) };
    for (const image of images) files[`Rondo press kit/${image.name}`] = await image.make();
    return zipSync(files, { level: 9 });
  },
};

export const pressFiles: readonly PressFile[] = [...images, kit];
