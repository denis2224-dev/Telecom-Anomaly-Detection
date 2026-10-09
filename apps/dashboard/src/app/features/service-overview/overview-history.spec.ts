import { describe, expect, it } from 'vitest';
import { historyIntervals, overviewHistory, REQUEST_INTERVAL_MS } from './overview-history';
import { voiceWindows } from '../../../fixtures/voice';

const to = '2026-10-08T12:00:00Z';
const from = new Date(Date.parse(to) - 30 * REQUEST_INTERVAL_MS).toISOString();
const scope = voiceWindows[0].scopeId;
const row = (start: number) => ({ ...voiceWindows[0], windowId: String(start),
  windowStart: new Date(start).toISOString(), windowEnd: new Date(start + 60_000).toISOString() });

describe('Overview extended history', () => {
  it('covers a full 30-period range in adjacent legal requests', () => {
    const intervals = historyIntervals(from, to);
    expect(intervals).toHaveLength(30);
    expect(intervals[0].from).toBe(from);
    expect(intervals.at(-1)?.to).toBe(new Date(to).toISOString());
    intervals.forEach((interval, index) => {
      expect(Date.parse(interval.to) - Date.parse(interval.from)).toBe(REQUEST_INTERVAL_MS);
      if (index) expect(interval.from).toBe(intervals[index - 1].to);
    });
    expect(() => historyIntervals(from, new Date(Date.parse(to) + 1).toISOString())).toThrow('at most 30 days');
  });

  it('loads 43,200 real-shaped minute rows with bounded pages and no gap filling', async () => {
    let requests = 0;
    const result = await overviewHistory(scope, from, to, async (from, to, page) => {
      requests++;
      expect(Date.parse(to) - Date.parse(from)).toBeLessThanOrEqual(REQUEST_INTERVAL_MS);
      expect(page).toBeLessThan(15);
      return { total: 1440, items: Array.from({ length: Math.min(100, 1440 - page * 100) },
        (_, i) => row(Date.parse(from) + (page * 100 + i) * 60_000)) };
    }, () => true);
    expect(requests).toBe(450);
    expect(result.items).toHaveLength(43_200);
    const empty = await overviewHistory(scope, from, to, async () => ({ items: [], total: 0 }), () => true);
    expect(empty.items).toEqual([]);
  });

  it('refreshes the latest legal interval and trims retained archive during rolling updates', async () => {
    const newTo = new Date(Date.parse(to) + 60_000).toISOString();
    const newFrom = new Date(Date.parse(from) + 60_000).toISOString();
    const preserved = row(Date.parse(newFrom));
    const calls: string[] = [];
    const result = await overviewHistory(scope, newFrom, newTo, async (from, to) => {
      calls.push(from, to);
      return { items: [row(Date.parse(to) - 60_000)], total: 1 };
    }, () => true, { from, to, rows: [row(Date.parse(from)), preserved], error: '' });
    expect(calls).toHaveLength(2);
    expect(Date.parse(calls[1]) - Date.parse(calls[0])).toBe(REQUEST_INTERVAL_MS);
    expect(result.items).toEqual([preserved, row(Date.parse(to))]);
  });

  it('rejects out-of-range data, duplicate windows and cancelled work', async () => {
    const interval = historyIntervals(from, to)[0];
    await expect(overviewHistory(scope, interval.from, interval.to,
      async () => ({ items: [row(Date.parse(to))], total: 1 }), () => true)).rejects.toThrow('Unexpected history');
    const item = row(Date.parse(from));
    await expect(overviewHistory(scope, interval.from, interval.to,
      async () => ({ items: [item, item], total: 2 }), () => true)).rejects.toThrow('duplicate');
    await expect(overviewHistory(scope, from, to, async () => ({ items: [], total: 0 }), () => false)).rejects.toThrow('View changed');
  });
});
