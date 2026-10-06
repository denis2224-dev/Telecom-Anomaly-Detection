import type { components } from '../app/core/api/schema';
import { cities } from '../app/features/service-overview/dashboard-geography';
import { voiceIncidents, voiceRange, voiceWindows } from './voice';
import { presentationFor } from './incident-presentation';
import legacy from './services.json';

type Summary = components['schemas']['ServiceSummary'];
type Window = components['schemas']['ServiceKpiWindow'];
type Incident = components['schemas']['Incident'];
type Service = Summary['scope']['service'];

export const fixtureVersion = 'connected-dashboard-fixture-v2';
export const fixtureRange = voiceRange;
export const fixtureCities = cities.map(city => ({
  ...city,
  scopeIds: [`fixture-VOLTE-${city.id}`, `fixture-SMS-${city.id}`],
}));

// These are UI-only IDs. Never send these objects to Kafka, a detector, or a database.
// Existing fixture values are reused as design examples, never asserted as city observations.
const smsTemplate = legacy.find(item => item.scope.service === 'SMS' && item.latestWindow)!.latestWindow!;
export const cityWindows: Window[] = fixtureCities.flatMap((city, index) =>
  (['VOLTE', 'SMS'] as const).flatMap(service => voiceWindows.map((source, minute): Window => {
    const scopeId = `fixture-${service}-${city.id}`;
    const missing = city.id === 'SOR' || (city.id === 'ORH' && minute === 4);
    const zero = city.id === 'UNG' && minute === 9;
    const degraded = city.id === 'CHI' && service === 'VOLTE';
    const kpis: Window['kpis'] = service === 'VOLTE'
      ? [{ name: 'cssrPct', observed: missing || zero ? null : degraded ? 94 : 99.6,
          baseline: 99.3, unit: 'PERCENT', numerator: missing ? null : zero ? 0 : degraded ? 940 : 996,
          denominator: missing ? null : zero ? 0 : 1000 }]
      : structuredClone(smsTemplate.kpis).map(kpi => ({
          ...kpi,
          observed: missing ? null : kpi.name === 'p95DeliveryMs' ? zero ? null : 1500 + minute * 20 + index * 10
            : kpi.name === 'deliveredMessages' ? zero ? 0 : 100
            : kpi.name === 'queueDepth' ? 5 : kpi.name === 'oldestPendingAgeSec' ? 2 : kpi.observed,
        })) as Window['kpis'];
    return {
      schemaVersion: 2, featureVersion: 2,
      windowId: `${fixtureVersion}:${scopeId}:${minute}`,
      scopeId, service, windowStart: source.windowStart, windowEnd: source.windowEnd,
      quality: missing ? 'MISSING' : 'COMPLETE',
      baselineVersion: fixtureVersion, topologyVersion: 'design-only-no-topology',
      kpis, featureNames: [], featureValues: [], mlEligible: false, sourceEventIds: [],
    };
  })),
);

const template = structuredClone(voiceIncidents[0]);
const scopeId = 'fixture-VOLTE-CHI';
const latest = cityWindows.filter(item => item.scopeId === scopeId).at(-1)!;
export const cityIncidents: Incident[] = [{
  ...template,
  id: '00000000-0000-4000-8000-000000000001',
  episodeId: '1'.repeat(64), scopeId, service: 'VOLTE',
  status: 'OPEN', technicalState: 'ONGOING', severity: 'HIGH',
  firstObservedAt: voiceWindows[2].windowStart,
  detectedAt: voiceWindows[3].windowEnd,
  lastObservedAt: latest.windowEnd,
  createdAt: voiceWindows[3].windowEnd, updatedAt: latest.windowEnd,
  version: 0, latestSequence: 1,
  latestDetection: {
    ...template.latestDetection,
    detectionId: '2'.repeat(64), episodeId: '1'.repeat(64), correlationKey: '3'.repeat(64),
    scopeId, service: 'VOLTE', phase: 'OPEN', technicalState: 'ONGOING', sequence: 1,
    windowStart: latest.windowStart, windowEnd: latest.windowEnd, detectedAt: latest.windowEnd,
    baselineVersion: fixtureVersion, topologyVersion: 'design-only-no-topology',
    rulesetVersion: 'design-only', kpis: latest.kpis,
    impact: { extraFailedAttempts: 53, affectedDeliveredMessages: 0, pendingMessages: 0, uniqueSubscribers: null },
    probableCause: 'Synthetic design example: probable IMS capacity pressure; confirm dependency evidence.',
    causeConfidence: 'LOW',
    evidence: [{ code: 'DESIGN_FIXTURE', summary: 'Illustrative city scenario; no live node or source evidence.', nodeId: null, sourceEventIds: [] }],
    recommendedChecks: ['Confirm source freshness', 'Review approved dependency evidence'],
    mlStatus: 'NOT_APPLICABLE', modelVersion: null, anomalyRank: null,
  },
}];
cityIncidents[0].presentation = presentationFor(
  cityIncidents[0].id, cityIncidents[0].latestDetection,
);

export const citySummaries: Summary[] = fixtureCities.flatMap(city =>
  (['VOLTE', 'SMS'] as const).map((service: Service): Summary => {
    const scopeId = `fixture-${service}-${city.id}`;
    const rows = cityWindows.filter(item => item.scopeId === scopeId);
    const missing = city.id === 'SOR';
    return {
      scope: { scopeId, service, region: city.name, rat: 'LTE', partner: 'Synthetic design fixture',
        route: 'Design fixture; dependency mapping unavailable', dependencyIds: [] },
      freshness: missing ? 'MISSING' : city.id === 'EDI' ? 'STALE' : 'FRESH',
      observedAt: fixtureRange.to,
      openIncidents: cityIncidents.filter(item => item.scopeId === scopeId && item.status !== 'RESOLVED').length,
      latestWindow: missing ? null : rows.at(-1)!,
    };
  }),
);
