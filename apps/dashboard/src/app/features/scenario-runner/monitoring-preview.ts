import type { KpiWindow } from '../service-kpi-history/voice-model';

// Local design previews are never sent to the scenario API or incident store.
export const previewScenarios = [
  { id: 'VOLTE_CALL_SUCCESS_DROP', label: 'VoLTE call success rate decrease', metric: 'cssrPct', unit: 'PERCENT', target: 'IMS core' },
  { id: 'VOLTE_IMS_OVERLOAD', label: 'VoLTE IMS CPU overload', metric: 'imsCpuPct', unit: 'PERCENT', target: 'IMS core' },
  { id: 'VOLTE_INTERCONNECT_OVERLOAD', label: 'VoLTE interconnect link overload', metric: 'linkUtilizationPct', unit: 'PERCENT', target: 'Interconnect line' },
  { id: 'VOLTE_CITY_TRANSPORT_OVERLOAD', label: 'VoLTE city transport link overload', metric: 'linkUtilizationPct', unit: 'PERCENT', target: 'City transport link' },
  { id: 'VOLTE_ROAMING_INBOUND_FAILURE', label: 'VoLTE inbound roaming call failures', metric: 'roamingVoiceSrPct', unit: 'PERCENT', target: 'Inbound roaming' },
  { id: 'VOLTE_ROAMING_OUTBOUND_FAILURE', label: 'VoLTE outbound roaming call failures', metric: 'roamingVoiceSrPct', unit: 'PERCENT', target: 'Outbound roaming' },
  { id: 'VOLTE_ROAMING_REGISTRATION_FAILURE', label: 'VoLTE roaming registration failures', metric: 'registrationSrPct', unit: 'PERCENT', target: 'Roaming registration' },
  { id: 'SMS_DELAYED_DELIVERY', label: 'SMS delayed delivery without loss', metric: 'p95DeliveryMs', unit: 'MILLISECONDS', target: 'SMSC queue' },
] as const;
export type PreviewType = typeof previewScenarios[number]['id'];
export const countries = ['Romania', 'Turkey', 'Italy', 'Germany', 'France', 'Ukraine', 'Spain', 'United Kingdom', 'USA', 'Bulgaria'];
export function randomSource(seed: number) {
  let state = seed >>> 0;
  return () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 4294967296; };
}
export function previewWindow(scopeId: string, start: number, end: number, kpis: KpiWindow['kpis'], service: 'VOLTE' | 'SMS' = 'VOLTE'): KpiWindow {
  return { schemaVersion: 2, featureVersion: 2, windowId: `preview-${scopeId}-${start}`, scopeId, service,
    windowStart: new Date(start).toISOString(), windowEnd: new Date(end).toISOString(), quality: 'COMPLETE',
    baselineVersion: 'local-preview', topologyVersion: 'local-preview', kpis,
    featureNames: [], featureValues: [], mlEligible: false, sourceEventIds: [] };
}
export function previewMetric(name: string, observed: number, baseline: number | null, unit: KpiWindow['kpis'][number]['unit'], numerator: number | null = null, denominator: number | null = null) {
  return { name, observed, baseline, unit, numerator, denominator };
}
export function countryPreview(seed: number, affected = '', metric = 'voice') {
  const random = randomSource(seed);
  return countries.map((country, index) => ({ country, users: Math.round(42000 / (1 + index * .8) * (.9 + random() * .2)),
    registration: +(99.2 + random() * .6 - (country === affected && metric === 'registration' ? 13 : 0)).toFixed(2),
    data: +(98 + random()).toFixed(2), voice: +(97.5 + random() * 1.5 - (country === affected && metric === 'voice' ? 14 : 0)).toFixed(2),
    sms: +(98.4 + random()).toFixed(2) }));
}
export function scenarioPreview(type: PreviewType, seed: number, scopeId: string, country: string, startUTC: string) {
  const random = randomSource(seed), start = Date.parse(startUTC);
  if (!Number.isSafeInteger(seed) || seed < 0 || !Number.isFinite(start) || !scopeId) throw new Error('Choose a valid seed, scope and UTC start.');
  let queued = 0, cumulativeReceived = 0, cumulativeDelivered = 0;
  const rows = Array.from({ length: 8 }, (_, index) => {
    const disrupted = index >= 2 && index <= 4;
    const overload = disrupted && ['VOLTE_IMS_OVERLOAD', 'VOLTE_INTERCONNECT_OVERLOAD', 'VOLTE_CITY_TRANSPORT_OVERLOAD'].includes(type);
    const attempts = 1000 + Math.floor(random() * 200);
    const success = Math.round(attempts * ((disrupted ? 86 + random() * 4 : 99.2 + random() * .5) / 100));
    const rate = success / attempts * 100;
    const received = 2000 + Math.floor(random() * 100);
    cumulativeReceived += received;
    const priorQueue = queued;
    const delivered = type === 'SMS_DELAYED_DELIVERY' && disrupted ? Math.round(received * .55) : Math.min(priorQueue + received, received + 1600);
    queued = priorQueue + received - delivered;
    cumulativeDelivered += delivered;
    const late = Math.min(priorQueue, delivered);
    const cpu = type === 'VOLTE_IMS_OVERLOAD' && disrupted ? 92 + random() * 6 : 35 + random() * 5;
    const utilization = overload && type !== 'VOLTE_IMS_OVERLOAD' ? 95 + random() * 4 : 40 + random() * 6;
    return previewWindow(scopeId, start + index * 60000, start + (index + 1) * 60000, [
      previewMetric('cssrPct', rate, 99.3, 'PERCENT', success, attempts),
      previewMetric('failedCallSharePct', 100 - rate, .7, 'PERCENT', attempts - success, attempts),
      previewMetric('eligibleAttempts', attempts, 1100, 'COUNT'),
      previewMetric('setupTimeMs', Math.round(disrupted ? 1800 + random() * 300 : 450 + random() * 60), 480, 'MILLISECONDS'),
      previewMetric('imsCpuPct', +cpu.toFixed(2), 37, 'PERCENT'),
      previewMetric('linkUtilizationPct', +utilization.toFixed(2), 43, 'PERCENT'),
      previewMetric('crossOperatorFailurePct', +(type === 'VOLTE_INTERCONNECT_OVERLOAD' && disrupted ? 16 + random() * 4 : .7 + random() * .2).toFixed(2), .8, 'PERCENT'),
      previewMetric('roamingVoiceSrPct', type === 'VOLTE_ROAMING_REGISTRATION_FAILURE' ? 99.3 : rate, 99.3, 'PERCENT'),
      previewMetric('registrationSrPct', type === 'VOLTE_ROAMING_REGISTRATION_FAILURE' ? rate : 99.3, 99.3, 'PERCENT'),
      previewMetric('activeRoamingUsers', countryPreview(seed).find(row => row.country === country)?.users ?? 0, null, 'COUNT'),
      previewMetric('queueDepth', queued, 0, 'COUNT'),
      previewMetric('avgDeliveryMs', Math.round(disrupted ? 3000 + random() * 500 : 650 + random() * 50), 680, 'MILLISECONDS'),
      previewMetric('p95DeliveryMs', Math.round(disrupted ? 6500 + random() * 800 : 1000 + random() * 100), 1050, 'MILLISECONDS'),
      previewMetric('deliveredLatePct', delivered ? late / delivered * 100 : 0, 0, 'PERCENT', late, delivered),
      previewMetric('deliveredMessages', delivered, 2050, 'COUNT'),
      previewMetric('receivedMessages', received, 2050, 'COUNT'),
      previewMetric('lostMessages', 0, 0, 'COUNT'),
    ], type === 'SMS_DELAYED_DELIVERY' ? 'SMS' : 'VOLTE');
  });
  return { rows, cumulativeReceived, cumulativeDelivered, queued,
    timeline: rows.slice(2).map((row, index) => ({ sequence: index + 1, at: row.windowStart,
      state: index < 3 ? 'ONGOING' : 'RECOVERED', target: type.includes('ROAMING') ? country : scopeId,
      phase: index === 0 ? 'OPEN' : index < 3 ? 'UPDATE' : 'RECOVERY' })) };
}
