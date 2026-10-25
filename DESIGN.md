---
name: Chargis
description: In-vehicle EV charging co-pilot that prints the highway trip as a toll ticket, one stub per charging stop.
colors:
  night-ground: "#0F0E0D"
  night-stock: "#1A1917"
  night-ink: "#EDE9E2"
  night-ink-muted: "#A19C93"
  night-rule: "#3B3833"
  night-stamp: "#8FB2EE"
  night-on-stamp: "#0F1A2E"
  night-warn: "#E8AE4A"
  day-ground: "#E1E5E8"
  day-stock: "#F3F5F6"
  day-ink: "#181B1E"
  day-ink-muted: "#57606A"
  day-rule: "#C4CACF"
  day-stamp: "#2448A6"
  day-on-stamp: "#F3F5F6"
  day-warn: "#8A5300"
typography:
  figure-monumental:
    fontFamily: "Geist Mono, monospace"
    fontSize: "112px"
    fontWeight: 600
    lineHeight: "112px"
    letterSpacing: "-4px"
  figure-large:
    fontFamily: "Geist Mono, monospace"
    fontSize: "72px"
    fontWeight: 600
    lineHeight: "76px"
    letterSpacing: "-2px"
  figure-medium:
    fontFamily: "Geist Mono, monospace"
    fontSize: "40px"
    fontWeight: 500
    lineHeight: "44px"
    letterSpacing: "-1px"
  figure-small:
    fontFamily: "Geist Mono, monospace"
    fontSize: "22px"
    fontWeight: 500
    lineHeight: "28px"
  figure-unit:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "26px"
    fontWeight: 600
    lineHeight: "30px"
  display:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "48px"
    fontWeight: 800
    lineHeight: "56px"
    letterSpacing: "-1px"
  display-small:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "40px"
    fontWeight: 700
    lineHeight: "48px"
    letterSpacing: "-0.5px"
  headline:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "30px"
    fontWeight: 700
    lineHeight: "38px"
  title-large:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: "30px"
  title:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: "26px"
  body-large:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "20px"
    fontWeight: 500
    lineHeight: "28px"
  body:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "17px"
    fontWeight: 500
    lineHeight: "24px"
  label-large:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "26px"
    fontWeight: 700
    lineHeight: "30px"
  label:
    fontFamily: "Wanted Sans, sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: "20px"
rounded:
  none: "0px"
  stamp-inner: "2px"
  gauge-cell: "3px"
  stamp-outer: "4px"
  button: "6px"
spacing:
  hair: "4px"
  xs: "8px"
  sm: "12px"
  md: "16px"
  lg: "20px"
  xl: "24px"
  gutter: "28px"
  cell: "32px"
  stub: "40px"
  split: "56px"
components:
  ticket-stock:
    backgroundColor: "{colors.night-stock}"
    rounded: "{rounded.none}"
    padding: "28px 40px 20px 40px"
  button-ink:
    backgroundColor: "{colors.night-ink}"
    textColor: "{colors.night-stock}"
    typography: "{typography.label-large}"
    rounded: "{rounded.button}"
    padding: "0 24px"
    height: "104px"
  button-stamp:
    backgroundColor: "{colors.night-stamp}"
    textColor: "{colors.night-on-stamp}"
    typography: "{typography.label-large}"
    rounded: "{rounded.button}"
    padding: "0 24px"
    height: "104px"
  button-ruled:
    backgroundColor: "{colors.night-stock}"
    textColor: "{colors.night-ink}"
    typography: "{typography.label-large}"
    rounded: "{rounded.button}"
    padding: "0 24px"
    height: "104px"
  field-label:
    textColor: "{colors.night-ink-muted}"
    typography: "{typography.label}"
  stamp:
    textColor: "{colors.night-stamp}"
    typography: "{typography.title}"
    rounded: "{rounded.stamp-outer}"
    padding: "6px 14px"
  caption-bar:
    backgroundColor: "{colors.night-stock}"
    textColor: "{colors.night-ink}"
    typography: "{typography.body-large}"
    padding: "14px 28px"
  ticket-header:
    textColor: "{colors.night-ink}"
    typography: "{typography.title-large}"
    padding: "14px 28px"
---

# Design System: Chargis

## Overview

**Creative North Star: "The Highway Toll Ticket"**

Chargis prints the trip. Departure issues a ticket, each charging stop is a perforated stub with fixed printed fields, and arrival prints a settlement receipt. The whole system is paper and ink: a stock colour, an ink colour, a faded thermal grey for secondary print, hairline rules, dashed perforations with punched notches, and one stamp ink. Nothing floats, nothing glows; the screen reads like a document the driver can trust at a glance.

Density follows the driving state. While driving, one stub fills the display: one monumental number owns the left field, ruled cells hold the supporting measurements on the right, and a torn-off strip at the bottom holds at most two actions with targets of 104dp. While parked, the same ticket grammar extends into detail: comparison tables ruled with hairlines, a cell gauge, and a receipt with dotted leaders. Two palettes, night (warm charcoal stock, off-white ink) and day (cool thermal-white stock, near-black ink), follow the system UI mode.

The world explicitly refuses the EV-app category default: a centered rounded card with a glowing green battery icon. There are no cards, no shadows, no pills, no gradients, and no emoji.

**Key Characteristics:**
- Ticket stock on a darker (night) or greyer (day) ground; square-cornered, never floating.
- Every measured number in Geist Mono; every Korean word in Wanted Sans; units set in Sans beside the mono figure.
- One number owns each field; secondary measurements live in hairline-ruled cells with small muted labels.
- One stamp ink (cobalt), reserved for the active stop, the stamp-the-stop action, and stamps.
- Amber only for warnings, always with a drawn triangle glyph and words.
- Motion is near-absent: a single stamp "thunk", a fade-through between driving cards, a 0.98 press.

## Colors

Two paper-and-ink palettes with one reserved stamp ink and one warning ink; everything else is the ink at two strengths and the rule.

### Primary
- **Night Cobalt Stamp Ink** (night-stamp) / **Day Cobalt Stamp Ink** (day-stamp): the only accent. Fills the "accept as waypoint" and "choose this stop" buttons, inks the target percent, the active stop on the route strip, the target outline on the cell gauge, and every Stamp. Text on a stamp-filled surface uses on-stamp.

### Secondary
- **Thermal Amber** (night-warn / day-warn): warnings only (time-limit risk, another stop needed, congestion wait, no safe stop). Always paired with the drawn WarnGlyph and a sentence; never the sole carrier of meaning.

### Neutral
- **Charcoal Ground / Cool Grey Ground** (night-ground / day-ground): the surface outside the ticket, the window and system-bar colour, and the colour punched through the perforation notches.
- **Warm Charcoal Stock / Thermal-White Stock** (night-stock / day-stock): the ticket paper. Also the caption bar background.
- **Off-White Ink / Near-Black Ink** (night-ink / day-ink): all primary print, the ink-filled primary button, the ruled button's border, passed route segments.
- **Thermal Fade** (night-ink-muted / day-ink-muted): field labels, reasons, sources, the vertical stub-tail print, declined and passed stops.
- **Hairline Rule** (night-rule / day-rule): 1dp cell rules, dashed perforations and tear lines, receipt dotted leaders, untouched gauge cells.

### Named Rules
**The One Stamp Ink Rule.** Cobalt marks only the stop the driver is heading to and the act of stamping it. If two things on a screen are cobalt, they must be the same stop.

**The Glyph-and-Words Rule.** Amber never appears alone: every amber line carries the drawn warning triangle and a full sentence.

**The Two Papers Rule.** Night and day are separate stock and ink pairs, not an inverted palette. Always resolve colours through the active palette, never hard-code one mode.

## Typography

**Display Font:** Wanted Sans (OFL TTF, Medium/SemiBold/Bold/ExtraBold)
**Label/Mono Font:** Geist Mono (Medium/SemiBold/Bold) for every measured number
**Body Font:** Wanted Sans

**Character:** A sturdy Korean grotesque for words and a tight, tabular mono for figures, as if the ticket's fields were pre-printed in Sans and the values were struck by the machine in Mono. Korean text breaks by phrase (HighQuality line break, WordBreak.Phrase) on API 33+.

### Hierarchy
- **Figure Monumental** (Mono 600, 112sp, -4 tracking): the one number that owns the driving stub (distance ahead or left).
- **Figure Large** (Mono 600, 72sp): the target percent and the headline measurement of a choice.
- **Figure Medium** (Mono 500, 40sp): measurements inside ruled cells (charge minutes, limit, arrival percent, wait, ETA).
- **Figure Small** (Mono 500, 22sp): header clock and battery, receipt values, table numbers (at body-large size).
- **Figure Unit** (Sans 600, 26sp): the unit beside a figure (km 앞, %, 분), baseline-aligned and offset 6dp.
- **Display** (Sans 800, 48sp): station name under the monumental figure (non-rest-stop stations add one muted line: kind and round-trip detour); screen titles.
- **Display Small** (Sans 700, 40sp): status sentences on the driving stub, congestion title, planned stop name.
- **Headline** (Sans 700, 30sp): station names in parked and choice views, "unknown" values in cells.
- **Title Large / Title** (Sans 600, 24 / 20sp): wordmark in the header; reasons, sub-lines, stamp text, table titles.
- **Body Large / Body** (Sans 500, 20 / 17sp): voice captions, receipt lines; table cells and scenario title.
- **Label Large** (Sans 700, 26sp): button text.
- **Label** (Sans 600, 16sp): printed field labels, route-strip stop names, source and battery labels.

### Named Rules
**The Mono-for-Measures Rule.** Any distance, percent, minute count, time, or kW value is set in Geist Mono; its unit is set in Wanted Sans beside it. Words never go in Mono.

**The One Number Owns the Field Rule.** Each field has a single dominant figure; supporting values drop to Figure Medium inside ruled cells.

## Layout

Landscape in-vehicle display. A ticket header row (28dp horizontal, 14dp vertical) sits above the ticket; a caption bar sits below it whenever voice is speaking. The driving stub fills the remaining space with a 76dp stub tail on the left, separated by a vertical perforation, carrying the ticket number, ticket kind, and issue time printed vertically. The stub body is padded 40dp horizontally, 28dp top, 20dp bottom.

The body splits into a lead column (weight 1.3) and a cell column (weight 1), separated by a vertical hairline with 32dp gutters. Cells pair in rows bounded by horizontal hairlines, 16dp vertical cell padding, 20dp after the inner vertical rule. Actions sit below a horizontal tear line in a row with a 16dp gap: the stamp action takes weight 2, the secondary takes weight 1.

Parked views use 40dp outer padding and a 56dp split between the summary column and the ticket or receipt. Spacing steps observed: 4, 8, 12, 16, 20, 24, 28, 32, 40, 56dp. Driving screens never scroll and never take text input; parked detail panes may scroll.

### Named Rules
**The No-Scroll Stub Rule.** A driving stub fits the screen: no scrolling, no lists, no text input, two actions at most, each at least 96dp tall (shipped at 104dp).

## Elevation & Depth

Flat. There are no shadows anywhere. Depth is conveyed by paper on ground: the stock is one step lighter (night) or lighter-and-cooler (day) than the ground, and perforation notches punch the ground colour through the stock edge. Hierarchy within the ticket comes from hairline rules and type size, never from lift.

### Named Rules
**The Paper-Lies-Flat Rule.** No elevation, blur, glow, or gradient. If something needs separating, rule it with a hairline or tear it with a perforation.

## Shapes

The ticket stock is square-cornered. Its edges are shaped by perforations: a dashed line (14 on, 10 off) with 14dp-radius punched half-circle notches at each end, used vertically for the stub tail and horizontally as the tear line above actions. Rules are 1dp solid; receipt leaders are fine dotted (3 on, 7 off); the receipt total sits under a double hairline (two 1dp rules, 3dp apart). Small radii appear only on things that are physically struck or pressed: buttons (6dp), the stamp (4dp outer, 2dp inner), gauge cells (3dp). No pills, no circles as containers.

## Components

### Buttons (TicketButton)
Tactile, printed blocks on the tear strip.
- **Shape:** gently squared (6dp radius), minimum height 104dp, 24dp horizontal padding, Label Large text centred.
- **Stamp ink (button-stamp):** cobalt fill with on-stamp text. Only for stamping a stop: "accept as waypoint" and the chosen stop under congestion.
- **Ink (button-ink):** ink fill with stock-coloured text. All other primary actions (depart, reissue).
- **Ruled (button-ruled):** transparent with a 2dp ink border and ink text. Secondary actions ("다음 충전소", next stop).
- **Press / Disabled:** scales to 0.98 while pressed with a ripple in the text colour; disabled renders at 40% alpha.

### Ticket Stock (TicketStock)
- **Corner Style:** square.
- **Background:** stock.
- **Shadow Strategy:** none (see Elevation & Depth).
- **Border:** none; an optional vertical perforation with punched notches at a set x offset.
- **Internal Padding:** 28 to 40dp per the Layout section.

### Fields and Figures
- **Field:** a printed cell: Label in thermal fade at top-left, 6dp gap, then the value.
- **Figure:** a Mono value with an optional Sans unit, bottom-aligned, unit offset 6dp. Defaults to ink; cobalt only for the active target; amber only for a warning value.
- **Unknown values:** printed as words ("정보 없음", "없음", "—") in Headline thermal fade, never hidden.

### Hairlines and Tear Lines
- **HairlineH / HairlineV:** 1dp rule-coloured lines; the only dividers.
- **TearLine:** horizontal dashed perforation with notches, always directly above the action strip.

### Stamp (signature)
A double-bordered rectangular stamp (2dp outer border at 4dp radius, 3dp gap, 1dp inner border at 2dp radius), 14 x 6dp inner padding, Title text in stamp ink, rotated -6 degrees. On first appearance it drops from 1.35 scale to 1.0 over 260ms with a cubic ease-out after a 90ms fade; with system animations off it appears immediately. Used for 경유지, 충전 완료, 도착, 정산 완료. On the receipt the 정산 완료 stamp sits beside the receipt title, never over the lines, so it cannot hide a line when a trip has more charges. This is the app's only flourish.

### Route Strip
A printed segment band: travelled route as a 4px ink line, remaining route as a 3px dashed rule line, end bars at departure and arrival, a 5px current-position tick. Stops: target filled cobalt (1.25x), charged filled ink, ahead ink ring, passed muted ring, declined muted X with struck-through label. Labels in Label style beneath.

### Cell Gauge
Charging progress as 20 cells (5% each, 6dp gaps, 3dp radius, 40dp tall): charged cells filled ink, partial cell filled proportionally over a rule cell, cells up to the target outlined 2dp in cobalt, the rest outlined 1dp in rule. Carries a "current% / target%" content description.

### Receipt Line
Label (Body Large, or Title when the total) at left, a dotted leader in rule colour, value in Figure Small at right (Bold when the total). Declined stops print muted with "—".
A charge prints as two lines: the session ("충전 · B 휴게소 …… 37 → 50% · 9분", with "· 제한" when the charger's time limit ended it) and, when the price is known, a muted "요금 …… 3,800원" line beneath. One long line squeezed the station name onto two lines. Totals add "충전 요금" (with "일부 정보 없음" when some prices are unknown). Amounts are rounded to 100원 with thousands separators.

### Info Line and Warn Line
One-line notes shared by the driving and parked screens (`InfoLine`, `WarnLine` in TicketParts).
- **Info Line:** a 10dp cobalt dot and Title Medium ink text. Used for the second planned stop, "배터리 예열 중", and the preconditioning suggestion.
- **Warn Line:** the WarnGlyph (24dp on driving stubs, 26dp on parked screens) and Title Medium amber text. Used for time-limit risk, taper above 80%, a cold battery, and "one more stop needed".
- On driving stubs both get 10dp top padding and clip to one line with an ellipsis so the stub never grows.

### Settings
- **Issue screen:** the left column shows a three-line summary (vehicle; target cap · margin · destination reserve; station range · battery source · voice) in Label over Title Medium, and a ruled [설정] button. The full settings do not sit on the issue screen.
- **Settings screen:** two columns of setting rows (charging: vehicle, cap, margin, destination reserve; app: voice, station range, battery source) under a Display Small title and an ink [설정 완료] button. It fits 1280 x 800 without scrolling.
- **Choice Cell:** a 48dp-minimum cell with a 1.5dp ink-muted border; the selected cell fills with stamp ink. Rows separate with hairlines.

### Battery Correction (manual SoC)
When the battery value is entered by hand, parked screens show "계기판과 같으면 [맞음], 다르면 고쳐 주세요" above a single full-width row of ruled buttons: [맞음] (stamp-ink text, 120dp) and [−5] [−1] [+1] [+5] (104dp). The row wraps rather than clipping a button. Spacing above and below the hairline tightens from 20dp to 12dp so the action strip keeps its height.

### Preconditioning prompt
On the "안내 중" driving stub, when a cold battery would slow the accepted stop's charge by more than three minutes and arrival is within 30 minutes, the right column adds an Info Line ("배터리 예열을 켜면 충전 약 N분 단축") and one ruled 96dp button [배터리 예열 켜기]. Once preconditioning is on, the button gives way to "배터리 예열 중".

### Ticket Header and Caption Bar
- **Header:** wordmark in Title Large ink, scenario in Body muted, then clock and battery in Figure Small with a Label caption, then the data source label. Always visible.
- **Caption Bar:** stock background under a top hairline, drawn speaker glyph in muted ink, caption in Body Large ink, two lines max. Mirrors every voice prompt.

### Glyphs
WarnGlyph (stroked triangle with drawn exclamation) and SpeakerGlyph are drawn on canvas in the palette colours; there are no icon fonts or emoji.

## Do's and Don'ts

### Do:
- **Do** set every measured value in Geist Mono with its unit in Wanted Sans beside it.
- **Do** keep cobalt for the active stop, the stamp action, and stamps only (The One Stamp Ink Rule).
- **Do** separate content with 1dp hairlines, dashed perforations, and tear lines.
- **Do** put a tear line directly above the action strip, with the stamp action wide (weight 2) and the secondary narrow and ruled (weight 1).
- **Do** pair every amber warning with the drawn WarnGlyph and a sentence.
- **Do** print unknown data as words in thermal fade rather than hiding the field, and label the data source in the header.
- **Do** honour the system "remove animations" setting: stamp and card transitions become instant.

### Don't:
- **Don't** use cards, drop shadows, glows, gradients, or elevation.
- **Don't** use pill shapes, rounded containers, or a centered rounded card with a glowing green battery icon.
- **Don't** use emoji or icon fonts; draw any glyph on canvas.
- **Don't** introduce a second accent colour or use cobalt for decoration.
- **Don't** let a driving stub scroll, accept text input, or carry more than two actions or a touch target under 96dp.
- **Don't** convey state by colour alone; declined stops are struck through, warnings carry glyph and words.
