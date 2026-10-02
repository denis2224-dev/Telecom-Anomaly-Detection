import type { ServiceSummary, Incident } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import type { KpiWindow } from './voice-model';

export type Detection = components['schemas']['ServiceDetection'];
export const VOLTE_METRICS = [
  ['cssrPct', 'CSSR'], ['eligibleAttempts', 'Eligible attempts'], ['rrcSrPct', 'RRC SR'],
  ['bearerSrPct', 'Bearer SR'], ['sip503Count', 'SIP 503 count'], ['sip503Ratio', 'SIP 503 rate'],
  ['imsCpuPct', 'IMS CPU'], ['packetLossRatio', 'Packet loss'],
] as const;
export const SMS_METRICS = [
  ['deliverySrPct', 'Delivery Success Rate'], ['p95DeliveryMs', 'P95 Delivery Delay'],
  ['deliveredMessages', 'Delivered Messages'], ['queueDepth', 'Queue Depth'],
  ['oldestPendingAgeSec', 'Oldest Pending Message Age'],
] as const;
export function metricValue(window: KpiWindow | null | undefined, name: string): number | null {
  if (!window || window.quality === 'MISSING') return null;
  const kpi = window.kpis.find(item => item.name === name);
  if ((name.endsWith('SrPct') || name === 'cssrPct' || name === 'sip503Ratio') && kpi?.denominator === 0) return null;
  if (name === 'p95DeliveryMs' && (window.kpis.find(item => item.name === 'deliveredMessages')?.observed ?? 0) === 0) return null;
  return kpi?.observed ?? null;
}
export function formatMetric(value: number | null | undefined, unit = ''): string {
  if (value == null || !Number.isFinite(value)) return 'Unavailable';
  const suffix = ({ PERCENT: '%', PERCENTAGE_POINTS: 'pp', RATIO: 'ratio', COUNT: '', MILLISECONDS: 'ms', SECONDS: 's' } as Record<string, string>)[unit] ?? unit;
  return `${new Intl.NumberFormat('en', { maximumFractionDigits: 3 }).format(value)} ${suffix}`.trim();
}
export function delta(window: KpiWindow | null, name: string): string {
  const value = metricValue(window, name), kpi = window?.kpis.find(item => item.name === name);
  if (value === null || kpi?.baseline == null) return 'Baseline unavailable';
  const difference = value - kpi.baseline;
  return `${difference > 0 ? '+' : ''}${formatMetric(difference, kpi.unit === 'PERCENT' ? 'PERCENTAGE_POINTS' : kpi.unit)} vs baseline`;
}
export function serviceHealth(service: ServiceSummary, incidents: Incident[] = []): 'NORMAL' | 'DEGRADED' | 'STALE' | 'UNKNOWN' {
  if (service.freshness === 'MISSING' || !service.latestWindow || service.latestWindow.quality !== 'COMPLETE') return 'UNKNOWN';
  if (service.freshness === 'STALE') return 'STALE';
  const relevant = incidents.filter(item => item.scopeId === service.scope.scopeId);
  if (relevant.some(item => item.technicalState === 'UNKNOWN')) return 'UNKNOWN';
  if (relevant.some(item => item.technicalState === 'ONGOING')) return 'DEGRADED';
  // An unresolved workflow count is not a technical health verdict.
  const primary = service.scope.service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs';
  if (metricValue(service.latestWindow, primary) === null) return 'UNKNOWN';
  return 'NORMAL';
}
export function phaseAt(window: KpiWindow, detections: Detection[]): 'NORMAL' | 'DEGRADED' | 'RECOVERY' | 'UNKNOWN' {
  if (window.quality !== 'COMPLETE') return 'UNKNOWN';
  if (metricValue(window, window.service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs') === null) return 'UNKNOWN';
  const time = Date.parse(window.windowStart);
  const latestByEpisode = new Map<string, Detection>();
  for (const item of detections) {
    if (item.scopeId !== window.scopeId || Date.parse(item.windowStart) > time) continue;
    const previous = latestByEpisode.get(item.episodeId);
    if (!previous || item.sequence > previous.sequence) latestByEpisode.set(item.episodeId, item);
  }
  const current = [...latestByEpisode.values()];
  if (current.some(item => item.phase === 'UNKNOWN')) return 'UNKNOWN';
  if (current.some(item => item.phase === 'OPEN' || item.phase === 'UPDATE')) return 'DEGRADED';
  if (current.some(item => item.phase === 'RECOVERY' && time < Date.parse(item.windowEnd))) return 'RECOVERY';
  return 'NORMAL';
}
export function mergeWindows(...groups: KpiWindow[][]): KpiWindow[] {
  const byId = new Map<string, KpiWindow>();
  for (const row of groups.flat()) byId.set(row.windowId, row);
  const byStart = new Map<string, KpiWindow>();
  for (const row of byId.values()) byStart.set(`${row.scopeId}:${Date.parse(row.windowStart)}`, row);
  return [...byStart.values()].sort((a, b) => Date.parse(a.windowStart) - Date.parse(b.windowStart));
}
export function historySlices(from: string, to: string) {
  const start = Date.parse(from), end = Date.parse(to);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end - start > 172800000) throw new Error('Choose a valid range of at most 48 hours.');
  const slices: { from: string; to: string }[] = [];
  for (let cursor = start; cursor < end; cursor += 86400000) slices.push({ from: new Date(cursor).toISOString(), to: new Date(Math.min(end, cursor + 86400000)).toISOString() });
  return slices;
}
export async function allPages<T>(fetch: (page: number) => Promise<{ items: T[]; total: number; observedAt?: string }>, valid = () => true) {
  const items: T[] = []; let observedAt: string | undefined;
  for (let page = 0; page < 100; page++) {
    if (!valid()) throw new Error('View changed while loading.');
    const result = await fetch(page);
    if (!valid()) throw new Error('View changed while loading.');
    items.push(...result.items); observedAt = result.observedAt ?? observedAt;
    if (items.length >= result.total) return { items, observedAt };
    if (!result.items.length) throw new Error('Evidence changed while loading. Retry to get a complete view.');
  }
  throw new Error('Too much evidence to load. Narrow the selected interval.');
}
