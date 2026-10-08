import sharp from "sharp";
import { artwork, crops, logoAspect } from "../brand";

const WIDTH = 1200;
const HEIGHT = 630;

/** The light, as the hero has it: a colour, its centre and its radii on the card. */
const lights = [
  { colour: "#a9caff", x: 180, y: 170, rx: 430, ry: 330 },
  { colour: "#cfbfff", x: 1020, y: 150, rx: 420, ry: 320 },
  { colour: "#ffcbb4", x: 930, y: 560, rx: 460, ry: 300 },
  { colour: "#b4ead9", x: 260, y: 560, rx: 420, ry: 280 },
];

const logoWidth = 560;
const logoHeight = logoWidth / logoAspect;

/**
 * The picture that links to the site show (Open Graph): the logo on the page's light, drawn at build time from the
 * brand's artwork, so it follows the logo without being redrawn by hand.
 */
export async function GET(): Promise<Response> {
  const glow = lights
    .map(({ colour, x, y, rx, ry }) => `<ellipse cx="${x}" cy="${y}" rx="${rx}" ry="${ry}" fill="${colour}" />`)
    .join("");
  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${HEIGHT}" viewBox="0 0 ${WIDTH} ${HEIGHT}">
      <defs>
        <filter id="soft" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="90" /></filter>
      </defs>
      <rect width="${WIDTH}" height="${HEIGHT}" fill="#ffffff" />
      <g filter="url(#soft)" opacity="0.85">${glow}</g>
      <svg x="${(WIDTH - logoWidth) / 2}" y="${(HEIGHT - logoHeight) / 2}" width="${logoWidth}" height="${logoHeight}"
        viewBox="${crops.logo}" color="#0b0b0d">${artwork}</svg>
    </svg>`;
  const png = await sharp(Buffer.from(svg)).png({ compressionLevel: 9 }).toBuffer();
  return new Response(new Uint8Array(png), { headers: { "Content-Type": "image/png" } });
}
