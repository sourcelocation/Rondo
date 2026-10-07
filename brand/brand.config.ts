import {
  AndroidColors,
  androidArtwork,
  AppIconSvg,
  appleAssets,
  ArtworkPng,
  ArtworkSvg,
  BrandExports,
  CssEmitter,
  defineBrand,
  FaviconSvg,
  JsonEmitter,
  KotlinEmitter,
  SwiftEmitter,
  TypeScriptEmitter,
} from "@sourcelocation/shasp";

const svg = "dist/svg";
const androidRes = "dist/android/res";

/** Rondo's brand: everything under dist/ that the apps, the sites and the server read. */
export default defineBrand({
  name: "Rondo",
  artwork: ["mark", "app-icon", "logo"],
  // Vector drawables replaced by the PNGs androidArtwork renders.
  obsolete: [
    "drawable/ic_launcher_foreground.xml",
    "drawable/ic_launcher_monochrome.xml",
    "drawable/ic_splash.xml",
    "drawable-night/ic_splash.xml",
    "drawable/ic_notification.xml",
    "drawable/ic_logo.xml",
  ].map((file) => `${androidRes}/${file}`),
  outputs: (artwork) => {
    const brand = BrandExports.from(artwork);
    return [
      new CssEmitter({ prefix: "rd" }),
      new TypeScriptEmitter({ prefix: "rd" }),
      new SwiftEmitter({ outputPath: "dist/swift/RondoTokens.swift", typeName: "RondoTokens" }),
      new KotlinEmitter({
        outputPath: "dist/kotlin/RondoTokens.kt",
        packageName: "app.rondo.android.designsystem.generated",
        objectName: "RondoTokens",
      }),
      new JsonEmitter(),
      // Brand artwork: the design file's exports (artwork/exports) in the brand colours.
      new ArtworkSvg(`${svg}/mark.svg`, brand.mark, "light"),
      new ArtworkSvg(`${svg}/mark-on-dark.svg`, brand.mark, "dark"),
      new ArtworkSvg(`${svg}/mark-mono.svg`, brand.mark, "currentColor"),
      new ArtworkSvg(`${svg}/logo.svg`, brand.logo, "light"),
      new ArtworkSvg(`${svg}/logo-on-dark.svg`, brand.logo, "dark"),
      new ArtworkSvg(`${svg}/logo-mono.svg`, brand.logo, "currentColor"),
      new FaviconSvg(`${svg}/favicon.svg`, brand.mark),
      // Email clients don't show SVG: the server's mail carries this PNG inline.
      new ArtworkPng("dist/email/mark.png", brand.mark, { width: 96, height: 96, paint: "light" }),
      new AppIconSvg(`${svg}/app-icon.svg`, brand.appIcon),
      ...androidArtwork(androidRes, brand),
      new AndroidColors(`${androidRes}/values/colors.xml`, "light"),
      new AndroidColors(`${androidRes}/values-night/colors.xml`, "dark"),
      ...appleAssets("dist/apple/Assets.xcassets", "dist/apple/AppIcon.icon", brand),
    ];
  },
});
