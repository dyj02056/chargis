# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Drivers of Pleos Connect-equipped electric vehicles on long highway trips, typically weekends and national holidays when rest-stop chargers are crowded. While driving they glance at an in-vehicle landscape display; while parked and charging, passengers also read the same screen to know when the car will leave. The redesign optimizes for the real driver first, not for demo viewers.

## Product Purpose

Chargis reads the navigation route and battery state and tells the driver, before they have to ask, which charging stop to use (a highway rest stop first, a drowsy-driver shelter, or an off-route charger near the road when it is clearly faster), to what percentage, and for how long. It adds the chosen stop as a navigation waypoint and keeps track of the charger's time limit so a session does not end short of the target. Success means the driver makes one glance-and-tap decision per stop and arrives with a safe margin.

## Positioning

Map and charging apps show where chargers are. Chargis computes how much and how long from the car's own data and the route, and the car speaks first. It answers "how much, how long", not "where".

## Operating Context

- In-vehicle, landscape infotainment display (Pleos Connect, Android-based). Day and night highway driving.
- Two states: driving (one card plus voice only, no scrolling, no text input, large touch targets) and parked or charging (detail views allowed, passengers read the screen).
- Voice announcements (TTS) accompany every driving-state prompt; on-screen captions mirror them.
- Contest deliverable: a demo recorded on the Pleos Connect emulator, reproduced through the built-in scenario simulator.

## Capabilities and Constraints

- F1 charging-stop recommendation (highway rest stops and shelters first; local rest stops, roadside/IC-adjacent, commercial and public chargers as alternatives, with round-trip detour and access time counted), F2 target-charge coaching, F3 charger time-limit alerts, F4 waypoint addition. F1–F4 run end to end in the demo simulator. F3 needs a known time limit: the public charger data has no time-limit field, so real-data chargers show the limit as unknown until a sourced rule is added. Real vehicle/navigation SDK integration is still pending.
- All station names, coordinates, and charger data are fictional ("A 휴게소", "I 마트" style). Chargers that outsiders cannot use (apartments, workplaces, user-restricted) are excluded. No real facility or operator trademarks.
- Unknown data is shown as unknown ("정보 없음"), never hidden; data source (simulated, manual, vehicle) is always labeled.
- Kotlin + Jetpack Compose, minSdk 28. Strings live in `strings.xml` (Korean first).
- Vehicle SDK availability (SoC, range, driving state, battery temperature, preconditioning control) is unconfirmed. Manual SoC input is the fallback: the app estimates SoC from distance, elevation and the charge curve, and the driver corrects it while parked.
- Also built: connector compatibility, cold-battery charge times with a preconditioning prompt, charge cost (shown, not ranked), destination arrival reserve, possibly-broken charger flagging, and elevation-aware targets. Their coefficients are assumptions until measured.

## Brand Commitments

- Product name: Chargis (차지스). Everything else about the visual identity may change.

## Evidence on Hand

- Working demo app with six scenarios (S1 basic, S2 holiday congestion, S3 charger time limit, S4 winter consumption and preconditioning, S5 off-route charger, S6 mountain pass) and 22 screenshots in `docs/screenshots/` (retaken 2026-10-25).
- A tested relay server for the public charger API (`server/`), not yet deployed. CI (GitHub Actions) runs the app and relay tests on every push to main.
- No real users, testimonials, partner logos, or measured outcomes exist; none may be invented.

## Product Principles

1. The driver's eyes return to the road within one glance; anything that needs reading time waits until the car stops.
2. Say the number that matters (target %, minutes, distance) and nothing that does not change the decision.
3. Be honest about uncertainty: label estimated and unknown data instead of guessing silently.
4. The car speaks first; the screen confirms what the voice said.

## Accessibility & Inclusion

- In-vehicle legibility in day and night: high contrast, large type, touch targets well above phone minimums.
- Meaning never depends on color alone.
