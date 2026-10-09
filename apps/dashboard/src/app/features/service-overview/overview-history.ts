import type { Window } from './dashboard-geography';
import { allPages } from '../service-kpi-history/assurance-model';

export const REQUEST_INTERVAL_MS = 86_400_000;
export const OVERVIEW_RANGE_MS = 30 * REQUEST_INTERVAL_MS;

export function historyIntervals(from: string, to: string) {
  const start = Date.parse(from), end = Date.parse(to);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end - start > OVERVIEW_RANGE_MS)
    throw new Error('Choose an end after the start, with an overview range of at most 30 days.');
  const intervals: { from: string; to: string }[] = [];
  for (let cursor = start; cursor < end; cursor += REQUEST_INTERVAL_MS)
    intervals.push({ from: new Date(cursor).toISOString(), to: new Date(Math.min(cursor + REQUEST_INTERVAL_MS, end)).toISOString() });
  return intervals;
}

export async function overviewHistory(scopeId: string, from: string, to: string,
  fetch: (from: string, to: string, page: number) => Promise<{ items: Window[]; total: number; observedAt?: string }>,
  valid: () => boolean,
  previous?: { from: string; to: string; rows: Window[]; error: string }) {
  historyIntervals(from, to);
  const start = Date.parse(from), end = Date.parse(to);
  // Retain the already loaded archive; refresh the latest interval on live updates.
  const reuse = end - start > REQUEST_INTERVAL_MS && previous && !previous.error
    && Date.parse(previous.from) <= start && Date.parse(previous.to) <= end
    && Date.parse(previous.to) >= end - REQUEST_INTERVAL_MS;
  const refreshFrom = reuse ? Math.max(start, end - REQUEST_INTERVAL_MS) : start;
  const rows = reuse ? previous.rows.filter(row => Date.parse(row.windowStart) >= start
    && Date.parse(row.windowStart) < refreshFrom) : [];
  let observedAt: string | undefined;
  for (const interval of historyIntervals(new Date(refreshFrom).toISOString(), to)) {
    const result = await allPages(async page => {
      if (page >= 15) throw new Error('History exceeded its interval page limit.');
      const result = await fetch(interval.from, interval.to, page);
      if (result.total > 1440) throw new Error('History exceeded its interval window limit.');
      return result;
    }, valid);
    for (const row of result.items) {
      const time = Date.parse(row.windowStart);
      if (row.scopeId !== scopeId || !Number.isFinite(time)
        || time < Date.parse(interval.from) || time >= Date.parse(interval.to))
        throw new Error('Unexpected history windows. Retry the range.');
    }
    rows.push(...result.items);
    observedAt = result.observedAt ?? observedAt;
  }
  if (rows.length > 43_200 || new Set(rows.map(row => row.windowId)).size !== rows.length)
    throw new Error('History exceeded its window limit or contains duplicate windows.');
  return { items: rows, observedAt };
}
