# NexaChat Brand Identity & Logo Specification

Official logo design system, vector master assets, Android adaptive icon specifications, and brand guidelines for **NexaChat**.

---

## 1. Design Rationale & Concept

### 1.1 Brand Identity
**NexaChat** combines **"Nexus"** (the central link, connection, and binding point) with **"Chat"** (real-time messaging, 1-to-1 voice calls, and AI conversation summaries). The mark embodies continuous real-time dialogue and intelligent connectivity.

### 1.2 The Mark: "The Nexus 'N' Monogram"
The NexaChat master mark is an original geometric monogram that harmonizes three foundational pillars of modern communication:
1. **The Incoming Dialogue Stem (Left)**: A clean vertical ribbon terminating in a friendly, organic conversation tail pointing down-left, signifying speech bubbles and incoming direct messages.
2. **The Outgoing Channel Stem (Right)**: An upright vertical pillar representing transmission, voice clarity, and active response.
3. **The Nexus Bridge (Diagonal)**: A dynamic diagonal ribbon traversing from top-left to bottom-right, embodying the real-time bridge connecting two interlocutors across the network.
4. **The Central AI Spark / Acoustic Node (Center)**: Positioned at the exact focal center `(54, 54)` of the grid, a radiant 4-point diamond star (pinched quadratic bezier curves) symbolizes both acoustic sound waves during voice calls and artificial intelligence in catch-up conversation summaries.

### 1.3 Exploration & Candidate Selection
During the design exploration phase, three distinct design candidates were evaluated:

| Candidate | Concept | Evaluation & Decision |
|---|---|---|
| **Candidate 1: The Nexus Wave** | Twin overlapping speech bubbles whose intersection forms an abstract 'N' with a circular connection node. | Strong visual metaphor, but twin overlapping bubble tails caused asymmetry and visual crowding when scaled down to 24px. |
| **Candidate 2: The Nexus 'N' Monogram (Selected Winner)** | A bold, unified capital 'N' formed by two conversational ribbons with an integrated chat tail and central 4-point AI spark. | **Winner**. Unrivaled optical legibility at both 24px and 512px; pure mathematical symmetry on the 108x108dp grid; flawless 0% clipping inside the 66dp safe zone; natural single-color silhouette for Android 13 themed icons. |
| **Candidate 3: The Waveform Spark Bubble** | A classic chat bubble containing three audio waveform vertical bars and an AI diamond. | Thematic for voice calls and AI, but multiple vertical bars degraded into visual noise below 48px and lacked a memorable monogram identity. |

---

## 2. Brand Color Palette

The NexaChat palette derives from the **Modern Material Messenger** design system, pairing deep technical Indigo with luminous Electric Sky and Cool Slate neutrals.

| Role | Color Name | Hex Code | RGB | Usage |
|---|---|---|---|---|
| **Primary** | Brand Indigo | `#4F46E5` | `rgb(79, 70, 229)` | Primary action buttons, FAB, outgoing bubbles, master gradient start |
| **Primary Dark** | Deep Indigo | `#3525CD` / `#312E81` | `rgb(49, 46, 129)` | Brand gradient base, high-contrast dark surface tint |
| **Primary Light** | Light Indigo | `#818CF8` | `rgb(129, 140, 248)` | Wordmark "Chat", dark mode accent, focus states |
| **Primary Container**| Soft Indigo | `#EEF2FF` / `#E0E7FF` | `rgb(238, 242, 255)` | Inbound bubbles, tonal glyph surface, hover highlights |
| **Tertiary / Spark**| Electric Sky | `#38BDF8` / `#0EA5E9` | `rgb(56, 189, 248)` | Central AI summary spark, presence indicator, call status |
| **Secondary Neutral**| Deep Slate | `#0F172A` | `rgb(15, 23, 42)` | Wordmark "Nexa", primary headings on light canvas |
| **Cool Slate** | Slate Gray | `#64748B` | `rgb(100, 116, 139)` | Subtitles, metadata labels, icon strokes |
| **Canvas Dark** | Dark Canvas | `#0B0F19` | `rgb(11, 15, 25)` | AMOLED dark mode canvas, social preview background |
| **Canvas Light** | Off-White | `#F8FAFC` | `rgb(248, 250, 252)` | Light mode background surface |
| **Monochrome** | Pure White / Black| `#FFFFFF` / `#000000` | — | Single-color themed icons, print engravings |

---

## 3. Clear-Space & Typography Rules

### 3.1 Clear-Space Rule
To maintain visual integrity and brand prominence, an exclusion zone free of text, borders, or other graphics must be maintained around the logo mark:
- **Minimum Clear Space $X$**: Defined as $0.25 \times H$ (where $H$ is the height of the mark).
- For an 80px mark, the clear space must be at least 20px on all four sides.

### 3.2 Lockup Proportions (`nexachat-wordmark.svg`)
- In horizontal lockups, the height of the wordmark typography matches $0.70 \times H$ of the mark.
- The spacing between the mark and the wordmark text is strictly $0.40 \times H$.
- "Nexa" is typeset in **Segoe UI Bold** / **Inter Bold** (`#0F172A` on light, `#FFFFFF` on dark).
- "Chat" is typeset in **Segoe UI Semibold** / **Inter Semibold** (`#4F46E5` on light, `#818CF8` on dark).
- Text paths in `nexachat-wordmark.svg` are hand-compiled to pure geometric vector paths (`d="..."`), ensuring zero font download requirements and zero cross-platform font rendering drift.

---

## 4. Brand Do's & Don'ts

| Do | Don't |
|---|---|
| **DO** maintain the exact 1:1 aspect ratio when scaling. | **DON'T** stretch, compress, skew, or rotate the mark. |
| **DO** use the monochrome asset (`nexachat-logo-mono.svg`) for single-color print, laser engraving, or dark-room displays. | **DON'T** recolor individual stems of the 'N' with arbitrary colors. |
| **DO** maintain a minimum contrast ratio of 4.5:1 (WCAG AA) against background surfaces. | **DON'T** place the master colored logo directly on low-contrast blue or purple backgrounds. |
| **DO** use the official Android adaptive icon drawables for launcher integration. | **DON'T** enclose the squircle badge inside another circle (double container effect). |
| **DO** allow the transparent spark cutout in monochrome mode to reveal dynamic system wallpaper. | **DON'T** fill the monochrome spark with a contrasting solid color. |

---

## 5. Android Adaptive Icon Derivation

Android 8.0+ (API 26+) adaptive icons require a two-layer system operating on a strict geometric grid:
- **Viewport Canvas**: $108 \times 108\,\text{dp}$.
- **OEM Visible Mask Area**: Center $72 \times 72\,\text{dp}$ (from `x=18` to `x=90`).
- **Guaranteed Safe Zone**: A circle of diameter $66\,\text{dp}$ centered at `(54, 54)` ($r = 33\,\text{dp}$).

### 5.1 Safe Zone Containment Audit
All points $(x, y)$ of the foreground glyph satisfy:
$$\sqrt{(x - 54)^2 + (y - 54)^2} \le 31.06\,\text{dp} < 33.00\,\text{dp}$$
- **Maximum Distance**: $31.06\,\text{dp}$ at the curved conversational tail tip `(28.5, 71.0)`.
- **Safety Cushion**: $\approx 1.94\,\text{dp}$ safety margin inside the safe zone circle.
- **Verification Result**: **0% clipping** under Circle (Pixel), Squircle (One UI), Rounded Square (Xiaomi), and Teardrop (Motorola) OEM masks.

### 5.2 Asset Architecture

```
android/app/src/main/res/
├── drawable/
│   ├── ic_launcher_background.xml    # 108dp Vector: 3-stop Brand Indigo gradient (#4F46E5 -> #4338CA -> #312E81)
│   ├── ic_launcher_foreground.xml    # 108dp Vector: White Nexus 'N' glyph + Electric Sky (#38BDF8) spark
│   └── ic_launcher_monochrome.xml    # 108dp Vector: Pure #FFFFFF with evenOdd spark cutout for Android 13 Monet
└── mipmap-anydpi-v26/
    ├── ic_launcher.xml               # Standard adaptive icon linking background + foreground + monochrome
    └── ic_launcher_round.xml         # Round adaptive icon linking background + foreground + monochrome
```

### 5.3 Android 13+ Themed Icons (Monochrome Layer)
In `ic_launcher_monochrome.xml`, every path utilizes `android:fillColor="#FFFFFF"`, and the diagonal ribbon incorporates `android:fillType="evenOdd"` with the central spark path. When the user enables "Themed icons" on Android 13+ (API 33+), the launcher dynamically tints the white silhouette with the Monet dynamic accent (`#D0E1FB` in dark mode, `#3525CD` in light mode), while the transparent diamond spark cutout allows the background wallpaper tint to shine through.

### 5.4 Vector Consistency
The colored master and wordmark use the same four-part N and spark geometry as the Android launcher foreground; their SVG transforms only change scale and placement for each canvas. The in-app mark uses the same silhouette at its own viewBox scale. The monochrome SVG uses the Android adaptive icon's 108×108 grid and cuts only the central spark out of the diagonal ribbon, leaving the single-color N on a transparent canvas.

---

## 6. Shipped Deliverables Inventory

| File Path | Description | Dimensions / Type |
|---|---|---|
| `docs/media/logo/nexachat-logo.svg` | Master app logo with squircle container & ambient depth | 512x512 Scalable Vector |
| `docs/media/logo/nexachat-logo-mono.svg` | Single-color adaptive-grid mark with a transparent spark cutout | 512x512 Scalable Vector (108×108 viewBox) |
| `docs/media/logo/nexachat-wordmark.svg` | Master logo mark + "NexaChat" typographic lockup | Vector with paths (no fonts) |
| `docs/media/logo/social-preview-1280x640.png` | Minimal GitHub & OpenGraph card: mark, wordmark, and one tagline | 1280x640 PNG (≥120px safe margins; ≤300KB) |
| `docs/media/logo/banner.png` | Minimal README header with the same mark, wordmark, and tagline | 1280x360 PNG |
| `docs/media/logo/preview-masks.png` | Comprehensive adaptive icon preview sheet | 1300x920 PNG |
| `android/.../ic_launcher_background.xml` | Adaptive icon background vector | 108x108dp VectorDrawable |
| `android/.../ic_launcher_foreground.xml` | Adaptive icon foreground vector (safe zone verified) | 108x108dp VectorDrawable |
| `android/.../ic_launcher_monochrome.xml`| Android 13+ monochrome themed icon vector | 108x108dp VectorDrawable |
| `android/.../ic_launcher.xml` | Adaptive icon XML reference | XML Resource |
| `android/.../ic_launcher_round.xml` | Round adaptive icon XML reference | XML Resource |
