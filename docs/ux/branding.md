# TraceLink

![TraceLink — Follow service evidence](../assets/tracelink-logo.svg)

TraceLink connects service observations to incident evidence and investigation workflows.
The bent trace and its two round endpoints match the supplied reference, with
a warm orange glow, a white wordmark and the tagline “Follow service evidence”.
The app’s existing interface accent remains unchanged.
The downloadable logo SVG includes the glow and works without the app’s CSS.

## SVG assets

- [Download the logo SVG](../assets/tracelink-logo.svg): wordmark and symbol on navy, suitable for documentation and presentations.
- [Symbol SVG](../../apps/dashboard/src/branding/mark.svg): transparent, single-color mark used in the sidebar and sign-in header.
- Browser icons: [SVG source](../../apps/dashboard/src/branding/favicon.svg), [PNG tab icon](../../apps/dashboard/src/branding/tracelink-icon.png), [ICO favicon](../../apps/dashboard/src/branding/favicon.ico) and [Apple touch icon](../../apps/dashboard/src/branding/apple-touch-icon.png), with the same glowing symbol on navy.

Keep the logo’s proportions and enough clear space to distinguish it from nearby
text. The mark is decorative beside the TraceLink name; icon-only navigation
retains an accessible name.

Colors come from `design/branding.json`, and the SVG geometry is maintained in
`scripts/sync-branding.mjs`. Regenerate the shared dashboard, sign-in and
documentation assets with:

```sh
node scripts/sync-branding.mjs
node scripts/sync-branding.mjs --check
```

After changing the browser icon, export its PNG, ICO and Apple touch assets with the existing installed
Playwright browser:

```sh
node scripts/render-browser-icon.mjs
```
