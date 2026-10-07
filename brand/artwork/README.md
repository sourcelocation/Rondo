# Rondo brand

## Name

In music, a **rondo** is a form in which the principal theme keeps returning between contrasting
episodes — *A B A C A D A*. That is exactly how durable memory is built: the things you are learning
return between new material, each time a little later than before. Rondo is two syllables, spelled
the way it sounds in every major language, and carries no negative meaning.

## The mark

A bookmark with an arrow cut through it, turning back on itself: the place you keep, and what keeps
coming back to it. The mark is one colour — ink on paper, reversed on dark.

The logo sets the wordmark **Rondo** in Juana Regular beside the mark.

## Source and exports

The editable design is kept outside the repository.
Three of its artboards are exported as SVG into `exports/`, and
`pnpm build` (in `brand/`) draws every platform's assets from them into `dist/`, where the apps read them:

| Artboard | Size | Contents | Becomes |
|----------|------|----------|---------|
| `mark` | tight around the mark | the mark | the in-app mark, the favicon, Android's notification icon, the web marks |
| `app-icon` | 1024 × 1024 | the mark where the app icon places it (the paper is the artboard's background and isn't exported) | the iOS and macOS icon, Android's launcher and splash icons, the web app icon |
| `logo` | as exported | the mark and the wordmark | the sign-in screens, the sites, the README |

The exports are complete images. The pipeline copies them for web and Apple and renders them as
PNGs for Android; it does not extract paths or rebuild the artwork.

- Name the artboards exactly as above, make each exportable as **SVG**, and export them to
  `artwork/exports/` (the file name is the artboard name).
- Keep the shared mark as a symbol. Groups, transforms, unions, differences, masks and SVG
  definitions can stay in the export; there is no flattening requirement.
- Outline text before exporting so the artwork does not depend on fonts installed on a build
  machine. Keep the exported artwork black on transparent for the platform template images.
- Leave the artboard background out of the export. The pipeline composes the app icon's paper and
  monochrome appearance variants around the complete image.
- Keep the icon's mark inside the centred circle 939 px across: that is what Android's launchers
  always show (66 of 72 dp).

Then run `pnpm build` and commit the exports together with what it generates (`dist/`). Rendering uses
Sharp's SVG renderer, so symbols and geometry are interpreted by an image library rather than a
custom SVG parser. The only layout requirement checked here is the app icon's 1024 × 1024 canvas.

## Generated assets

| Platform | Assets | Where |
|----------|--------|-------|
| Web | `mark.svg`, `mark-on-dark.svg`, `mark-mono.svg` (`currentColor`), `logo.svg`, `logo-on-dark.svg`, `logo-mono.svg`, `favicon.svg` (follows the system appearance), `app-icon.svg` | `dist/svg/` — the sites and the web app import them from there |
| Apple | `AppIcon.icon` (Icon Composer: light, dark, tinted and clear appearances; Xcode renders the images older systems use), `Mark` and `Logo` template images, the accent and launch colours | `dist/apple/` — the Apple project adds it as a source |
| Android | PNG drawables at mdpi through xxxhdpi for the adaptive launcher layers, splash (light and dark), notification icon and `ic_logo`; launch colours | `dist/android/res/` — the Android app adds it as a resource directory |

## Colour

| Token | Light | Dark |
|-------|-------|------|
| Ink (`color.brand.ink`) | `#000000` | `#FFFFFF` |
| Paper (`color.brand.paper`) | `#F2EFE7` | `#0D0D0C` |
| Accent (`color.brand.accent`, the product's accent — not part of the mark) | `#2F3BFF` | `#6E78FF` |

All colours used in product code come from `tokens/`, compiled into `dist/`.

## License

Brand assets (name, mark, logo, icons) are trademarks of the Rondo project and are **not** covered
by the repository's AGPL-3.0 license. Forks must use their own name and mark.
