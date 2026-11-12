# NexaChat logo — Concept B (Note)

Owner-selected mark (2026-11-12). A solid **N** with a short reply foot on the left stem. Geometry is original to brief 352; canonical path in a **108×108** viewBox:

`M24 20H36L64 60V20H80V84H66L40 47V72L24 84Z`

## Palette

| Role | Hex | Usage |
| --- | --- | --- |
| Aubergine plate | `#733F68` | Launcher background, favicon plate, master logo container |
| On-brand mark | `#FFFCFF` | Mark on aubergine surfaces |
| Light mark | `#733F68` | Standalone mark on light backgrounds |
| Dark mark | `#DDA7CC` | Standalone mark on dark backgrounds |
| Ink | `#35142F` | Wordmark on light surfaces |

## Size contract

- **16–24px:** favicon uses the plate + on-brand mark; strokes stay legible without outlines.
- **48px+:** same path, no optical alternate.
- **108dp:** Android adaptive layers use `translate(20.52 20.52) scale(.62)` on the foreground path (see `android/.../ic_launcher_foreground.xml` on the visual-refresh branch).
- **512px:** flat master in `nexachat-logo.svg` (no gradients).

Wordmark lockups use **system UI** `<text>` in SVG (`nexachat-wordmark.svg`, `site/og-image.svg`); only paths are converted for Android VectorDrawable.

## Files in this directory

| File | Description |
| --- | --- |
| `nexachat-logo.svg` | 512×512 master: aubergine squircle + on-brand Note mark |
| `nexachat-logo-mono.svg` | Single-color mark (`#35142F`) on transparent 108×108 grid |
| `nexachat-wordmark.svg` | Horizontal mark + NexaChat wordmark |
| `social-preview-1280x640.png` | Raster social/OG card (from `site/og-image.svg` when regenerated) |
| `banner.png` | README header raster (from wordmark when regenerated) |
| `preview-masks.png` | Adaptive-icon mask preview sheet (pending Note refresh) |

## PNG regeneration

When vectors change, from the **repository root**:

```text
rsvg-convert -w 1280 -h 640 site/og-image.svg -o docs/media/logo/social-preview-1280x640.png
rsvg-convert -w 1280 -h 360 docs/media/logo/nexachat-wordmark.svg -o docs/media/logo/banner.png
```

`preview-masks.png` has no committed Note source SVG in this repo; regenerate after a mask-preview asset is added or update manually.

## Android parity

Colored launcher stack on `feat/android-visual-refresh`: `ic_launcher_background.xml` (`#733F68`), `ic_launcher_foreground.xml` and `ic_logo_mark.xml` (path above). Monochrome layer matches `research/design/logo/concept-b/ic_launcher_monochrome.svg`.

## Do / don't

| Do | Don't |
| --- | --- |
| Keep the path data exact when scaling | Stretch, rotate, or recolor individual segments |
| Use `nexachat-logo-mono.svg` for single-color print | Ship the wordmark as launcher foreground |
| Maintain clear space ≥ 0.25× mark height | Add sparkles, outlines, or third-party glyph traces |

Trademark clearance and registry search: **not claimed** — design comparison only in concept-b research README.
