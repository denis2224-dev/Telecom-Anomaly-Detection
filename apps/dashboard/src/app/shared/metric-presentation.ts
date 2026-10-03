import type { components } from '../core/api/schema';

type Window = components['schemas']['ServiceKpiWindow'];
type Detection = components['schemas']['ServiceDetection'];
export type Kpi = Window['kpis'][number];

export function primaryMetric(service: 'VOLTE' | 'SMS', kpis: Kpi[]): Kpi | undefined {
  const name = service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs';
  return kpis.find(kpi => kpi.name === name);
}

export function supportedValue(
  kpi: Kpi | undefined,
  kpis: Kpi[],
  quality: Window['quality'] = 'COMPLETE',
): number | null {
  if (!kpi || quality === 'MISSING' || kpi.observed === null
    || !Number.isFinite(kpi.observed)) return null;
  if ((kpi.unit === 'PERCENT' || kpi.unit === 'RATIO') && kpi.denominator === 0) return null;
  if (kpi.name === 'p95DeliveryMs') {
    const completed = kpis.find(item => item.name === 'deliveredMessages' && item.unit === 'COUNT')?.observed;
    if (completed === null || completed === undefined || !Number.isFinite(completed) || completed <= 0) return null;
  }
  return kpi.observed;
}

export function deviation(kpi: Kpi | undefined, observed: number | null): string {
  if (!kpi || observed === null || kpi.baseline === null
    || !Number.isFinite(kpi.baseline)) return 'Comparison unavailable';
  const change = Math.round((observed - kpi.baseline) * 100) / 100;
  const units: Record<string, string> = {
    PERCENT: 'pp', PERCENTAGE_POINTS: 'pp', MILLISECONDS: 'ms', SECONDS: 's',
    COUNT: 'count', RATIO: 'ratio', MBPS: 'Mbps',
  };
  return `${change > 0 ? '+' : ''}${change} ${units[kpi.unit] ?? kpi.unit}`;
}

export function metricLabel(name: string): string {
  const names: Record<string, string> = {
    cssrPct: 'Call setup success rate', p95DeliveryMs: 'Delivery p95',
    deliveredMessages: 'Completed messages', queueDepth: 'Queued messages',
    oldestPendingAgeSec: 'Oldest queued-message age',
    rrcSuccessPct: 'Radio connection success rate',
    bearerSuccessPct: 'Bearer setup success rate',
  };
  return names[name] ?? name;
}

export function rankValue(detection: Detection): string {
  const rank = detection.anomalyRank;
  return detection.mlStatus === 'OK' && rank !== null && Number.isFinite(rank)
    ? String(rank) : 'Unavailable';
}

export function modelStatusLabel(status: Detection['mlStatus']): string {
  return {
    OK: 'Model result available', TIMEOUT: 'Model response timed out',
    UNAVAILABLE: 'Model service unavailable',
    INSUFFICIENT_DATA: 'Not enough eligible data for the model',
    NOT_APPLICABLE: 'Model does not apply to this update',
  }[status];
}
