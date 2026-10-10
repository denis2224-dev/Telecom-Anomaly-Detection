import { test, expect } from '@playwright/test';
import { createServer, type ServerResponse } from 'node:http';
import { observe } from '../helpers/pr77-stack';

test('native SSE observer follows document replacement, SPA routing and explicit closure', async ({ browser }) => {
  let connections = 0;
  const streams = new Set<ServerResponse>();
  const server = createServer((request, response) => {
    if (request.url === '/api/incidents/stream') {
      connections++;
      streams.add(response);
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      response.write('retry: 50\ndata: ready\n\n');
      request.on('close', () => { connections--; streams.delete(response); });
    } else {
      response.writeHead(200, { 'Content-Type': 'text/html' });
      response.end('<script>window.stream = new EventSource("/api/incidents/stream")</script>');
    }
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw Error('Expected loopback server address');
  const context = await browser.newContext();
  const page = await context.newPage();
  const network = await observe(page);
  try {
    for (let index = 0; index < 4; index++) {
      await page.goto(`http://127.0.0.1:${address.port}/document-${index}`);
      await expect.poll(() => connections).toBe(1);
      await expect.poll(() => network.activeStreams.size).toBe(1);
    }
    await page.evaluate(() => history.pushState({}, '', '/same-document'));
    expect(connections).toBe(1);
    expect(network.activeStreams.size).toBe(1);
    const beforeReconnect = network.responses.length;
    for (const response of streams) response.end();
    await expect.poll(() => network.responses.length).toBeGreaterThan(beforeReconnect);
    await expect.poll(() => connections).toBe(1);
    await expect.poll(() => network.activeStreams.size).toBe(1);
    await page.evaluate(() => (window as unknown as { stream: EventSource }).stream.close());
    await expect.poll(() => connections).toBe(0);
    await expect.poll(() => network.activeStreams.size).toBe(0);
    await page.evaluate(() => {
      (window as unknown as { stream: EventSource }).stream = new EventSource('/api/incidents/stream');
    });
    await expect.poll(() => connections).toBe(1);
    await expect.poll(() => network.activeStreams.size).toBe(1);
    await page.evaluate(() => (window as unknown as { stream: EventSource }).stream.close());
    await expect.poll(() => connections).toBe(0);
    await expect.poll(() => network.activeStreams.size).toBe(0);
  } finally {
    await context.close();
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});
