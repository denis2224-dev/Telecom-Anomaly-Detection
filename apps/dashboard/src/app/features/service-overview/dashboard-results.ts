import { metricValue } from '../service-kpi-history/assurance-model';
import type { Window } from './dashboard-geography';

// Counts describe setup attempts, not traffic in Erlangs or dropped established calls.
export function callResults(window: Window | undefined) {
  const kpi = window?.kpis.find(item => item.name === 'cssrPct');
  const rate = metricValue(window, 'cssrPct');
  const total = kpi?.denominator, successful = kpi?.numerator;
  if (window?.quality !== 'COMPLETE' || kpi?.unit !== 'PERCENT' || rate === null || rate < 0 || rate > 100
    || total == null || successful == null || !Number.isSafeInteger(total) || !Number.isSafeInteger(successful)
    || total <= 0 || successful < 0 || successful > total
    || Math.abs(rate - successful / total * 100) > .1) return null;
  return { total, successful, failed: total - successful, rate: successful / total * 100 };
}
