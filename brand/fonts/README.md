# Fonts

| Role | Family | Faces the apps embed | Folder |
|------|--------|----------------------|--------|
| Sans | **Geist** | 300–700 | `geist/` |
| Mono | **Geist Mono** | 400–500 | `geist/` |
| Serif | **Juana** | 300–700, with italics | `juana/` |

Each folder holds the family's files; the apps embed the faces above.

- **Apple** — XcodeGen bundles the faces; they are registered at launch with
  `CTFontManagerRegisterFontURLs`.
- **Android** — the build generates font resources and a Kotlin catalog from this folder.
- **Web** — the build copies the faces into the page and declares them with `@font-face` (Geist and
  Geist Mono as variable fonts).
