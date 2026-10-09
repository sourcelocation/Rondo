# Rondo brand

The logo is the mark beside the wordmark **Rondo**, set in Juana Regular. The mark is one colour: ink
on paper, reversed on dark.

## Colour

| Token | Light | Dark |
|-------|-------|------|
| Ink (`color.brand.ink`) | `#000000` | `#FFFFFF` |
| Paper (`color.brand.paper`) | `#F2EFE7` | `#0D0D0C` |
| Accent (`color.brand.accent`, the product's accent — not part of the mark) | `#2F3BFF` | `#6E78FF` |

## Exporting artwork

Export three artboards as SVG into `exports/`, named exactly:

| Artboard | Size | Contents |
|----------|------|----------|
| `mark` | tight around the mark | the mark |
| `app-icon` | 1024 × 1024 | the mark placed as in the app icon, without the paper background |
| `logo` | as exported | the mark and the wordmark |

- Outline text, and export black on transparent with no artboard background.
- Keep the app icon's mark inside the centred 939 px circle (what Android launchers always show).

Then run `pnpm build` in `brand/` and commit the exports with the regenerated `dist/`.

## License

Brand assets (name, mark, logo, icons) are trademarks of the Rondo project and are **not** covered
by the repository's AGPL-3.0 license. Forks must use their own name and mark.
