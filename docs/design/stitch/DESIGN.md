---
name: Modern Material Messenger
colors:
  surface: '#f7f9fb'
  surface-dim: '#d8dadc'
  surface-bright: '#f7f9fb'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f2f4f6'
  surface-container: '#eceef0'
  surface-container-high: '#e6e8ea'
  surface-container-highest: '#e0e3e5'
  on-surface: '#191c1e'
  on-surface-variant: '#464555'
  inverse-surface: '#2d3133'
  inverse-on-surface: '#eff1f3'
  outline: '#777587'
  outline-variant: '#c7c4d8'
  surface-tint: '#4d44e3'
  primary: '#3525cd'
  on-primary: '#ffffff'
  primary-container: '#4f46e5'
  on-primary-container: '#dad7ff'
  inverse-primary: '#c3c0ff'
  secondary: '#505f76'
  on-secondary: '#ffffff'
  secondary-container: '#d0e1fb'
  on-secondary-container: '#54647a'
  tertiary: '#004d70'
  on-tertiary: '#ffffff'
  tertiary-container: '#006693'
  on-tertiary-container: '#b8e0ff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e2dfff'
  primary-fixed-dim: '#c3c0ff'
  on-primary-fixed: '#0f0069'
  on-primary-fixed-variant: '#3323cc'
  secondary-fixed: '#d3e4fe'
  secondary-fixed-dim: '#b7c8e1'
  on-secondary-fixed: '#0b1c30'
  on-secondary-fixed-variant: '#38485d'
  tertiary-fixed: '#c9e6ff'
  tertiary-fixed-dim: '#89ceff'
  on-tertiary-fixed: '#001e2f'
  on-tertiary-fixed-variant: '#004c6e'
  background: '#f7f9fb'
  on-background: '#191c1e'
  surface-variant: '#e0e3e5'
typography:
  headline-lg:
    fontFamily: Roboto Flex
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Roboto Flex
    fontSize: 26px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Roboto Flex
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.015em
  headline-sm:
    fontFamily: Roboto Flex
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  title-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.02em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-tablet: 1.25rem
  gutter-desktop: 1.5rem
  margin: 1rem
  margin-tablet: 1.5rem
  margin-desktop: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system translates the structured logic of Material 3 into an agile, real-time messaging interface. The design narrative prioritizes low cognitive overhead, effortless navigation, and crystal-clear visual hierarchy.

- **Brand Personality**: Responsive, focused, and intuitive. It communicates real-time reliability without rigid enterprise sterility.
- **Target Audience**: Digital teams, collaborators, and active communicators who depend on swift context switching, rapid content scanning, and seamless multi-device continuity.
- **Emotional Response**: Calibrated focus, communicative ease, and tactile assurance across every touchpoint and swipe gesture.
- **Visual Style**: Modern Material 3 hybrid. The interface features tonal surface containers, soft ambient elevations, crisp structural hairpins, and signature round pill indicators.

## Colors

The palette leverages a crisp indigo primary paired with cool slate neutrals to build precise structural depth in light mode.

- **Primary (`#4F46E5`)**: Active action states, outgoing message bubbles, unread notification badges, and the primary Floating Action Button (FAB).
- **Secondary (`#64748B`)**: Secondary text, inactive tab states, metadata, timestamp cues, and subtle interface icons.
- **Tertiary (`#0EA5E9`)**: Informational callouts, active link previews, online presence pulses, and read receipts.
- **Neutral (`#F8FAFC`)**: Base canvas tone. Surfaces build upward through crisp container shades (`#FFFFFF` for raised cards, `#F1F5F9` for incoming chat bubbles and low-emphasis inputs).
- **Text & Contrast**: Primary body text sits at deep slate (`#0F172A`) to ensure WCAG AAA compliance, while divider strokes anchor at hairline cool slate (`#E2E8F0`).

## Typography

Typography balances structured legibility and dense communication needs:

- **Headlines (`Roboto Flex`)**: Variable, mechanical precision provides clear structural signposts in top app bars, channel titles, and settings views without crowding the viewport.
- **Body & Labels (`Inter`)**: Engineered for screen legibility at dense scale. Used across chat message threads, timestamps, user tags, and pill chips.
- **Hierarchy Rules**:
  - Chat feed names and channel headers utilize `title-md`.
  - Message contents render via `body-md` (mobile standard) or `body-lg` (expanded desktop panels).
  - Timestamps, delivery statuses, and badge counters use `label-sm` to maintain visual restraint.

## Layout & Spacing

The interface applies a responsive grid architecture calibrated to real-time conversation flows:

- **Mobile Viewports (< 640px)**: Single-pane view. 4-column layout with `margin: 1rem` and `gutter: 1rem`. Thread list and message panel swap via state transitions with edge-to-edge touch targets.
- **Tablet Viewports (640px - 1024px)**: Two-pane split view (320px thread list pane + fluid conversational pane). Outer margin expands to `1.5rem`.
- **Desktop Viewports (> 1024px)**: Three-pane layout (collapsible navigation rail 72px, thread roster 360px, flexible active chat pane, and an optional 320px contextual inspector).
- **Rhythm Rules**: Vertical padding inside conversation feeds adheres to `space-xs` (grouped messages from the same sender) up to `space-md` (transitions between distinct senders). Component interiors strictly respect an 8pt baseline rhythm.

## Elevation & Depth

Visual hierarchy leverages Material 3 tonal surface layering combined with ambient slate shadows:

- **Surface Levels**:
  - `Level 0 (Canvas)`: Base background (`#F8FAFC`), entirely flat.
  - `Level 1 (Incoming Bubbles & Inputs)`: Light neutral fill (`#F1F5F9`) resting flush with 1px border stroke (`#E2E8F0`).
  - `Level 2 (Cards, App Bars & Sticky Headers)`: Solid `#FFFFFF` elevated via ambient drop shadow: `0 1px 3px rgba(15, 23, 42, 0.06), 0 1px 2px rgba(15, 23, 42, 0.04)`.
  - `Level 3 (Floating Action Button & Dropdowns)`: Elevated surface with shadow: `0 4px 6px -1px rgba(79, 70, 229, 0.15), 0 2px 4px -2px rgba(15, 23, 42, 0.06)`.
  - `Level 4 (Modals & Media Viewers)`: Elevated scrim overlay with deep ambient focus shadow: `0 20px 25px -5px rgba(15, 23, 42, 0.1), 0 8px 10px -6px rgba(15, 23, 42, 0.05)`.
- **Divider Treatment**: Hairline rules (`1px solid #E2E8F0`) isolate persistent headers, channel item rows, and the message composition dock.

## Shapes

The system relies on intentional geometric contrast:

- **Avatars**: Strictly circular (`rounded-full` / 9999px) across all sizes (24px, 40px, 56px) to reinforce human presence.
- **Message Bubbles**: Asymmetric corners. Outgoing indigo bubbles feature full curvature except for a tucked bottom-right anchor corner (4px). Incoming slate bubbles mirror this with a tucked bottom-left corner (4px).
- **Badges & Counters**: Pill-shaped (`rounded-full`) with a minimum 20px height and horizontal padding for count figures.
- **Floating Action Button (M3)**: Squircle/rounded container with `rounded-xl` (16px / 1rem radius) to align with Material 3 geometry.
- **Input Fields & Panels**: Consistent rounded corners (`rounded-lg` / 8px) with subtle internal clearance.

## Components

### Buttons & FAB
- **Filled Button**: Solid `#4F46E5` fill, `#FFFFFF` text, `title-md` font size, 40px height, `rounded-lg` perimeter. Active state scales down slightly with a 10% black overlay.
- **M3 Floating Action Button (FAB)**: Primary Indigo surface, 56x56px footprint, centered white icon, 16px corner radius (`rounded-xl`). Sits fixed at bottom-right viewport (16px inset on mobile, 24px on desktop) with Level 3 elevation.
- **Tonal Button**: `#EEF2FF` surface with `#4F46E5` label, used for secondary inline actions (e.g., "Join Call", "View Details").

### Message Bubbles
- **Sent**: Primary `#4F46E5` background, white text (`body-md`), top-left/top-right/bottom-left 16px radius, bottom-right 4px radius. Timestamp right-aligned in semi-opaque white (`rgba(255, 255, 255, 0.7)`).
- **Received**: Surface `#F1F5F9` background, slate-900 text (`#0F172A`), top-left/top-right/bottom-right 16px radius, bottom-left 4px radius. Timestamp aligned right in `#64748B`.

### Lists & Chat Roster Items
- **Channel / Chat Tile**: 72px fixed height, `#FFFFFF` default background with an active state of `#F8FAFC`.
- **Content Row**: Left 48px circular avatar with online status badge dot (10px emerald `#10B981` at bottom-right rim); center stack of sender title (`title-md`) and preview snippet (`body-sm` in slate); right column of timestamp (`label-sm`) and unread badge.
- **Dividers**: 1px inset divider (`#E2E8F0`) starting at avatar offset (72px) to the right viewport edge.

### Chips & Badges
- **Unread Counter**: Saturated `#4F46E5` pill badge, 20px height, min-width 20px, 6px horizontal padding, crisp white `label-sm` centered text.
- **Filter Chips**: 32px height, 1px `#CBD5E1` border, `#FFFFFF` background, `label-md` slate typography. Selected state transitions to `#4F46E5` background with white text and no border.

### Input Fields & Chat Input Bar
- **Bottom input dock**: Sticky footer with `#FFFFFF` background, Level 2 elevation top-shadow, integrated attachments menu, voice button, and expandable multi-line text input field.
- **Text Entry**: Background `#F1F5F9`, `rounded-lg`, borderless until focused (transitions to 1.5px `#4F46E5` outline), 40px min-height, vertical padding 8px, horizontal padding 14px.

### Checkboxes & Radio Controls
- **Form Controls**: Indigo `#4F46E5` fill when selected, border stroke `#94A3B8` when unselected. Checkboxes feature smooth 4px rounded corners; radio circles maintain strict concentric rings with a 4px white spacer.
