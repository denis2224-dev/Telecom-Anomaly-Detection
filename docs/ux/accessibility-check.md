# Trace Link accessibility verification

The controlled browser suite covers desktop/narrow layouts, keyboard navigation, visible focus, Escape and modal focus return, reduced motion, 200% zoom, long evidence and non-color status. Exact chart tables provide a textual alternative to SVG drawing; each map city/link action has a keyboard and table counterpart.

Notification counts are part of the bell's accessible name. Toasts use a restrained polite region and do not move keyboard focus. Native dialogs trap focus, and closing returns it to a persistent control. Shared context labels identify timezone, exclusive end and units. Domain controls and table buttons provide usable touch targets; narrow tables scroll within their labelled regions.

The focus regression was reproduced by closing an expanded chart during a range refresh. The original expand control could be replaced by asynchronous rendering. The repaired control persists while the chart loads; desktop and narrow-browser checks pass.

These automated checks do not constitute a human screen-reader certification. A teammate-assisted screen-reader and guide-only investigation remain release acceptance work. Actual populated Trace Link investigations require the full registered archive and its recorded inventory version. Historical inventory does not require activation of a live catalogue. See the [current acceptance record](../evidence/final-ui-signoff.md).

The current populated production browser run passes at desktop and 390px width, including expanded-chart Escape/focus return, exact table/export and restored dataset/country/partner selection. Actual masked captures are indexed in `docs/evidence/assets/trace-link-published/artifact-index.json`. These do not replace screen-reader or supported-browser human reviews.
