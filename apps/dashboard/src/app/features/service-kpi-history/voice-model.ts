import type { components } from '../../core/api/schema';
export type KpiWindow = components['schemas']['ServiceKpiWindow'];
export type Incident = components['schemas']['Incident'];
export function cssr(window: KpiWindow) {
  return window.kpis.find(kpi => kpi.name === 'cssrPct' && kpi.unit === 'PERCENT');
}
export function observed(window: KpiWindow): number | null {
  const kpi = cssr(window);
  return window.quality === 'MISSING' || kpi?.denominator === 0 ? null : kpi?.observed ?? null;
}
export function episodes(items: Incident[]): Incident[] {
  const unique = new Map<string, Incident>();
  for (const item of items) {
    const previous = unique.get(item.episodeId);
    if (!previous || item.version > previous.version) unique.set(item.episodeId, item);
  }
  return [...unique.values()].sort((a, b) => Date.parse(b.detectedAt) - Date.parse(a.detectedAt));
}
export function clock(value: string): string {
  return new Date(value).toISOString().slice(11, 19);
}
