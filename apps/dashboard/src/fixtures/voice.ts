import type { components } from '../app/core/api/schema';
import incident from '../../../../contracts/fixtures/incidents/incident-open.json';

type Window = components['schemas']['ServiceKpiWindow'];
export const voiceRange = { from: '2026-09-15T10:00:00Z', to: '2026-09-15T10:10:00Z' };
// Explicit design fixture: normal, degraded, unavailable, recovered, zero calls.
export const voiceWindows: Window[] = [99.6, 99.4, 95, 94, null, 96, 99.4, 99.6, null, 99.5].map((value, minute) => ({
  schemaVersion: 2, featureVersion: 2, windowId: `voice-preview-${minute}`,
  scopeId: 'VOLTE-MD-CENTRAL', service: 'VOLTE',
  windowStart: `2026-09-15T10:0${minute}:00Z`,
  windowEnd: minute === 9 ? voiceRange.to : `2026-09-15T10:0${minute + 1}:00Z`,
  quality: minute === 4 ? 'MISSING' : 'COMPLETE',
  baselineVersion: 'design-fixture-v2', topologyVersion: 'topology-v2',
  kpis: [{ name: 'cssrPct', observed: value, baseline: 99.3, unit: 'PERCENT',
    numerator: value === null ? (minute === 8 ? 0 : null) : Math.round(value * 10),
    denominator: minute === 4 ? null : minute === 8 ? 0 : 1000 }],
  featureNames: [], featureValues: [], mlEligible: false, sourceEventIds: [],
}));
export const voiceIncidents: components['schemas']['Incident'][] = [{
  ...incident, status: 'OPEN', technicalState: 'RECOVERED', version: 4,
  lastObservedAt: '2026-09-15T10:08:00Z', updatedAt: '2026-09-15T10:08:10Z', latestSequence: 5,
  latestDetection: { ...incident.latestDetection, phase: 'RECOVERY', technicalState: 'RECOVERED',
    sequence: 5, windowStart: '2026-09-15T10:07:00Z', windowEnd: '2026-09-15T10:08:00Z',
    detectedAt: '2026-09-15T10:08:10Z', kpis: voiceWindows[7].kpis },
} as components['schemas']['Incident']];
