# DESIGN — UI/UX

Two surfaces share one visual language:

- **Till** — plain HTML/CSS driven by `src/ui/static/app.js`, optimized for a
  touchscreen at arm's length, one-handed use, fast repeated taps.
- **Admin mobile** — Expo/React Native app in `admin-mobile/`, optimized for
  reading numbers on a phone away from the shop.

## 1. Visual direction and principles

1. **Numbers first.** Money is the content; chrome recedes. White cards on a
   light neutral canvas, one accent colour for actions.
2. **Tappable, not dense.** Minimum 44 px targets; the catalog is a grid of
   cards, not a table.
3. **Honest state.** Every failure has a visible, plain-language line
   (`role="alert"`), never a silent no-op.
4. **Offline is normal.** The till never shows a blocking spinner for network;
   it shows "Sync pending — uploads when the network is back."
5. **One accent.** Blue for navigation actions (`#208AEF`), green only for
   money-positive/status (`#0a7d33`), red only for errors (`#a32121`).

## 2. Colours

| Token | Value | Use |
| --- | --- | --- |
| Canvas (till) | `#f4f5f7` | Page background |
| Canvas (mobile) | `#f7f8fa` | Screen background |
| Surface | `#ffffff` | Cards, header, dialog |
| Text | `#1c2733` | Primary text, prices |
| Muted text | `#5a6a7a` (till) / `#777`–`#888` (mobile) | Secondary, meta |
| Border | `#d8dde3` | Card and header separators |
| Hairline | `#eef1f4` | List row separators |
| Accent | `#208AEF` | Links/actions in the mobile app |
| Positive | `#0a7d33` | Shift open status |
| Danger | `#a32121` (till) / `#c0392b` (mobile) | Error text, alerts |
| Overlay | `rgba(0,0,0,0.45)` | Receipt dialog backdrop |

## 3. Typography

- Family: `system-ui, sans-serif` (no webfont download — offline-safe, native
  feel on Android).
- Scale: page title 1.1 rem, card title 0.95–1rem (weight 600–700), body
  0.85–0.9 rem, meta 12–13 px.
- Money uses `fontVariant: ['tabular-nums']` in the mobile app so columns of
  figures align while scrolling.
- Prices are weight 700; everything else is 400–600.

## 4. Spacing and layout system

- Base unit: **4 px**; practical steps of 4 / 6 / 8 / 12 / 16 / 24.
- Cards: padding 12–16 px, radius 8 px (till) / 12 px (mobile), 1 px border or
  white-on-grey (no heavy shadows; one soft shadow on the login card).
- Till sells screen: two-column grid, `grid-template-columns: 2fr 1fr`
  (catalog : cart), 1 rem gap.
- Catalog: `repeat(auto-fill, minmax(150px, 1fr))` — reflows from phone to
  tablet without breakpoints.
- Mobile screens: full-width cards, `marginHorizontal: 16`, 12 px vertical gap.

## 5. Reusable components

| Component | Where | Notes |
| --- | --- | --- |
| Product card | Till | Name, price, on-hand; dims to 55% opacity when out of stock |
| Cart line | Till | Name flexes, quantity controls, line total |
| Totals block | Till | UI shows domain-computed totals, never its own arithmetic |
| Receipt dialog | Till | Native `<dialog>`, detailed table layout for invoicing |
| Metric row | Mobile | Label left, tabular money right |
| Window card | Mobile | Today / this week / this month with identical metrics |
| Section card | Mobile | Low stock list, contract list |
| Inline error | Both | Red text with `role="alert"` (till) or an error `<Text>` (mobile) |
| Primary button | Mobile | Accent text on white card; disabled while a request is in flight |

## 6. Navigation and forms

- **Till:** single page with two views toggled by state — login and sell. Manager
  and admin action groups are hidden unless the role allows them
  (`#manager-actions`, `#admin-actions`, `display:none` by default). Receipt is a
  modal dialog; "New sale" closes it and returns to the grid.
- **Mobile:** Expo Router stack. `/(admin)/_layout.tsx` re-checks the auth gate on
  every navigation into the group and redirects to `/login` when not authorized —
  deep links cannot bypass it. Tabs within a screen are avoided; the dashboard
  links out to each management screen.
- **Forms:** labels are implicit (placeholder + `aria-label` on the till),
  numeric PIN input uses `inputmode="numeric"` and `autocomplete="off"`, and every
  submit has a visible inline error slot beneath it.

## 7. Loading, empty and error states

- Loading: `ActivityIndicator` (mobile); the till shows nothing blocking because
  data is local.
- Empty: sentence case, muted, actionable — "Cart is empty.", "No sales recorded
  in this window."
- Error: red line adjacent to the action; the mobile dashboard keeps previously
  loaded data on screen and adds an error row rather than blanking the page.
- Offline: informational, not an error — "Sync pending — uploads when the network
  is back."

## 8. Interactions and animations

- Tap feedback only (opacity/active state). No decorative animation on the till —
  a slow animation is a slow sale.
- Mobile uses platform defaults (`RefreshControl` pull-to-refresh) and no custom
  easing, so the app feels native and stays cheap on low-end devices.
- Destructive actions (void/refund, fault denial) require an explicit confirm
  step and a reason where the schema requires one.

## 9. Responsive behaviour

- Till: 2-column layout above ~700 px, collapsing to a single column (catalog
  above cart) on narrow phones via the grid rule; the cart stays reachable at the
  bottom of the scroll.
- Mobile: phone-first; the same layout runs on tablet and web (Expo web) without
  separate breakpoints.
- Receipt dialog has `min-width: 320px` and scrolls internally on small screens.

## 10. Accessibility

- Every interactive element has an accessible name (`aria-label` on the till's
  select/PIN, visible text elsewhere); the till uses semantic `section`, `main`,
  `aside`, `header`, `dialog`.
- Alerts use `role="alert"`; the payment progress line uses `role="status"`.
- Contrast: `#1c2733` on `#fff` ≈ 14:1; muted `#5a6a7a` on `#fff` ≈ 5.7:1;
  accent `#208AEF` on white ≈ 3.3:1 (used for large/bold text only).
- Targets ≥ 44 px on the till; PIN entry is numeric to avoid tiny keys.
- Colour is never the only signal: out-of-stock dims **and** shows the quantity;
  errors are text, not just red.

## 11. Page-level layout guidance

**Till login.** Centered white card, max 280 px, 12 vh from the top, stacked
select → PIN → button → error slot. Nothing else on screen.

**Till sell.** Sticky white header (cashier, shift status, actions). Left: product
grid of cards. Right: cart card with lines, empty hint, total, payment method
select, full-width Sell button, error and status lines. Receipt dialog on top.

**Admin dashboard.** Header row (title + actions) → "Updated HH:MM" → three window
cards → low-stock card (first 8 rows). Pull to refresh.

**Products / Customers / Hire purchase / Inventory / Payments.** List of cards;
each row is a name plus the numbers that matter for that domain; actions are text
buttons in accent colour at the top of the screen. Payment reconciliation asks for
reference + amount and reports the server's verdict verbatim.
