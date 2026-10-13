# Repair accessibility evidence and remaining checks

Status: component/controlled-browser checks only; human acceptance pending.

Verified by Chromium tests: desktop and 390px layouts without page overflow;
scenario form accessible names and keyboard submission; notification dialog
keyboard open, Escape and focus return; non-color lifecycle/read/quality text;
native history exact-value table and gaps; directional topology table selection;
expanded metric dialog keyboard entry, Escape and focus return. Brush zoom has
keyboard zoom, pan and reset alternatives sharing the same visible context.
The SVG topology has keyboard city actions and a table alternative. Wide map
and bucket content scroll inside their own labelled containers.

Not verified: screen-reader session, reduced-motion/high-zoom browser matrix,
Safari/Firefox behavior, contrast audit, long-text human review, authenticated
idle/absolute expiry rehearsal, secure-origin browser push and coordinated-load
capacity. No human reviewer has signed this document. Existing automated auth
and session checks do not substitute for those release gates.
