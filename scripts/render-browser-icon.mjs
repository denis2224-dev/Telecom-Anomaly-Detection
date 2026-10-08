import { chromium } from '../apps/dashboard/node_modules/playwright/index.mjs';
import { readFile, writeFile } from 'node:fs/promises';

const source = new URL('../apps/dashboard/src/branding/favicon.svg', import.meta.url);
const directory = new URL('../apps/dashboard/src/branding/', import.meta.url);
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 64, height: 64 }, deviceScaleFactor: 1 });
  const svg = await readFile(source, 'utf8');
  const icons = [];
  for (const size of [16, 32, 48, 64, 180]) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<style>body{margin:0}svg{display:block;width:${size}px;height:${size}px}</style>` + svg);
    const png = await page.screenshot();
    if (size === 64) await writeFile(new URL('tracelink-icon.png', directory), png);
    if (size === 180) await writeFile(new URL('apple-touch-icon.png', directory), png);
    else icons.push({ size, png });
  }
  // ICO directory entries point to PNG images at the common tab-icon sizes.
  const header = Buffer.alloc(6 + 16 * icons.length);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(icons.length, 4);
  let offset = header.length;
  icons.forEach(({ size, png }, index) => {
    const entry = 6 + 16 * index;
    header[entry] = header[entry + 1] = size;
    header.writeUInt16LE(1, entry + 4);
    header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(png.length, entry + 8);
    header.writeUInt32LE(offset, entry + 12);
    offset += png.length;
  });
  await writeFile(new URL('favicon.ico', directory), Buffer.concat([header, ...icons.map(({ png }) => png)]));
} finally {
  await browser.close();
}
