---
version: 1
slug: "app-src-main-java-com-chargis-app-ui"
primary_target: "app/src/main/java/com/chargis/app/ui"
related_targets: []
---

# Surface brief: Chargis in-vehicle app (all screens)

Scope: every screen of the Android app (driving card, parked detail, charging, trip end). Visitor mode: Operate.
Audience and job: EV driver on a long highway trip deciding where, how much, and how long to charge in one glance; passengers read the charging screen.
Constraints: driving state shows one surface plus voice, no scroll, no text input, touch targets ≥ 96dp; parked state may show detail. Fictional rest-stop data, labeled sources. Product name Chargis kept; everything else replaced.
Memorable moment: accepting a recommendation stamps the ticket; arriving prints a settlement receipt of the whole trip.
Unresolved: real Pleos emulator resolution; real SDK day/night signal (follows system ui mode for now).

## Direction contract

THESIS: The trip is a highway toll ticket. Chargis issues it at departure, each charging stop is a perforated stub with fixed printed fields, and arrival prints the settlement receipt. Refuses the category default: a centered rounded card with a glowing green battery icon.

OWN-WORLD: Ticket stock and ink. Night: warm charcoal stock, warm off-white ink, thermal-fade grey for secondary print, 1dp hairline rules, perforation edges with punched notches. Day: cool thermal-white stock, near-black ink. One stamp ink (cobalt) reserved for the active stop, the stamp-the-stop actions, and stamps; other primary actions are ink-filled; amber only for warnings, always with a drawn glyph and words. Wanted Sans for Hangul (SUIT was planned but ships only web woff2; Wanted Sans is an OFL TTF in the same register), Geist Mono for every measured number. No cards, no shadows, no pills, no emoji.

STORY: The driver sees which stop, how far, what target percent; taps once to stamp it as a waypoint; at the stop reads minutes to target and departure time; leaves with a receipt.

FIRST VIEWPORT: Driving recommendation fills the screen as one stub: perforated left edge, distance in monumental mono at left, rest-stop name beneath, target percent in stamp ink at right with minutes and limit in ruled cells; a perforated tear strip at the bottom holds the two actions, accept wide and filled, next-stop narrow and ruled.

FORM: Highway toll ticket and receipt (grounded list position 5), raised with: hairline grid discipline from the dense-module challenger, one number owns the field from the catalog-sleeve challenger, reserved ink for the active leg from the orienteering challenger, nothing disappears it is stamped from the ticket-wallet challenger. Seed key 985d4cbb.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
