import type { TelecomClient, TraceMetricSeries, TracePartner } from '../../core/api/telecom-client';
import type { KpiWindow } from '../service-kpi-history/voice-model';

type Point = TraceMetricSeries['points'][number];
export interface RoamingRow {
  country: string;
  users: number | null;
  registration: number | null;
  data: number | null;
  voice: number | null;
  sms: number | null;
}
export interface RoamingSelection { datasetId?: string; resolution?: '5m' | '15m' | '1h' | '6h'; strictRange?: boolean }
export interface RecordedRoaming {
  datasetId: string;
  direction: 'in' | 'out';
  resolution: NonNullable<RoamingSelection['resolution']>;
  from: string;
  to: string;
  note: string;
  rows: RoamingRow[];
  rates: { registration: number | null; data: number | null; voice: number | null; sms: number | null };
  windows: KpiWindow[];
}
const usable = (point: Point) => point.value !== null && Number.isFinite(point.value)
  && ['VALID', 'PARTIAL', 'INSUFFICIENT_EVIDENCE'].includes(point.quality);

// Ratios use source counts, never averages of country percentages.
export function roamingRate(points: readonly Point[]): number | null {
  const valid = points.filter(point => usable(point) && point.numerator !== null && point.denominator !== null
    && Number.isFinite(point.numerator) && Number.isFinite(point.denominator)
    && point.numerator >= 0 && point.denominator > 0 && point.numerator <= point.denominator);
  const numerator = valid.reduce((sum, point) => sum + point.numerator!, 0);
  const denominator = valid.reduce((sum, point) => sum + point.denominator!, 0);
  return denominator > 0 ? numerator / denominator * 100 : null;
}

export async function loadRecordedRoaming(api: TelecomClient, requestedFrom: string, requestedTo: string,
  direction: 'in' | 'out', signal: AbortSignal, selection: RoamingSelection = {}): Promise<RecordedRoaming> {
  const datasets = (await api.traceDatasets(signal)).filter(item => item.archiveReady)
    .sort((a, b) => Date.parse(b.to) - Date.parse(a.to));
  signal.throwIfAborted();
  const contains = (item: typeof datasets[number]) => Date.parse(requestedFrom) >= Date.parse(item.from)
    && Date.parse(requestedTo) <= Date.parse(item.to) && Date.parse(requestedTo) > Date.parse(requestedFrom);
  const dataset = selection.datasetId ? datasets.find(item => item.datasetId === selection.datasetId) : datasets.find(contains) ?? datasets[0];
  if (selection.datasetId && !dataset) throw new Error('The selected recorded roaming dataset is unavailable.');
  if (!dataset) throw new Error(selection.strictRange ? 'No recorded roaming data covers the selected interval.' : 'No recorded roaming dataset is available.');
  if (selection.strictRange && !contains(dataset)) throw new Error('No recorded roaming data covers the selected interval.');
  const to = contains(dataset) ? requestedTo : dataset.to;
  const from = contains(dataset) ? requestedFrom
    : new Date(Math.max(Date.parse(dataset.from), Date.parse(to) - 48 * 3600000)).toISOString();
  const inventory = await api.tracePartners(signal, dataset.catalogueVersion ?? undefined);
  signal.throwIfAborted();
  if (dataset.catalogueVersion && inventory.catalogueVersion !== dataset.catalogueVersion)
    throw new Error('The recorded partner catalogue does not match this dataset.');
  const partners = inventory.partners.filter(partner => partner.active && partner.direction === direction);
  if (!partners.length || partners.length > 100) throw new Error('The recorded partner catalogue is unavailable.');
  const duration = Date.parse(to) - Date.parse(from);
  const resolution = selection.resolution ?? (duration <= 4 * 3600000 ? '5m' : duration <= 7 * 86400000 ? '1h' : '6h');
  const metrics = ['roaming.registration.success', 'roaming.data.success', 'roaming.voice.success',
    direction === 'out' ? 'roaming.activeOutbound' : 'roaming.activeInbound'];
  const series = new Map<string, TraceMetricSeries>();
  const jobs = partners.flatMap(partner => metrics.map(metric => ({ partner, metric })));
  // Bound concurrency and cancel every outstanding read when the view or session changes.
  let next = 0;
  await Promise.all(Array.from({ length: 3 }, async () => {
    while (next < jobs.length) {
      signal.throwIfAborted();
      const { partner, metric } = jobs[next++];
      const result = await api.traceMetricSeries({ datasetId: dataset.datasetId, domain: 'roaming', metric,
        scopeType: 'PARTNER_DIRECTION', scopeId: `${partner.id}:${direction}`, from, to, resolution }, signal);
      signal.throwIfAborted();
      if (result.datasetId !== dataset.datasetId || result.scopeId !== `${partner.id}:${direction}`
        || result.domain !== 'roaming' || result.metric !== metric || result.scopeType !== 'PARTNER_DIRECTION'
        || result.resolution !== resolution || Date.parse(result.requestedFrom) !== Date.parse(from)
        || Date.parse(result.requestedTo) !== Date.parse(to) || result.points.length > 8641)
        throw new Error('Recorded roaming measurements do not match the selected population.');
      const ordered = [...result.points].sort((a,b) => Date.parse(a.from)-Date.parse(b.from));
      let previousEnd = Date.parse(from);
      for (const point of ordered) {
        const start = Date.parse(point.from), end = Date.parse(point.to);
        if (!Number.isFinite(start) || !Number.isFinite(end) || start < previousEnd || end <= start || end > Date.parse(to))
          throw new Error('Recorded roaming buckets overlap or fall outside the selected interval.');
        previousEnd = end;
      }
      series.set(`${partner.id}:${metric}`, {...result, points:ordered});
    }
  }));
  const points = (partner: TracePartner, index: number) => series.get(`${partner.id}:${metrics[index]}`)?.points ?? [];
  const countryNames = new Intl.DisplayNames(['en'], { type: 'region' });
  // Keep partner populations separate even if a catalogue has several partners in one country.
  const rows = partners.map(partner => {
    const name = countryNames.of(partner.country) ?? partner.country;
    const duplicate = partners.filter(item => item.country === partner.country).length > 1;
    const snapshot = points(partner, 3).at(-1);
    return { country: duplicate ? `${name} · ${partner.id}` : name,
      users: snapshot && usable(snapshot) ? snapshot.value : null,
      registration: roamingRate(points(partner, 0)), data: roamingRate(points(partner, 1)),
      voice: roamingRate(points(partner, 2)), sms: null };
  });
  const registration = partners.flatMap(partner => points(partner, 0));
  const buckets = new Map<number, Point[]>();
  for (const point of registration) {
    const start = Date.parse(point.from), bucket = buckets.get(start) ?? [];
    if (bucket.length && Date.parse(bucket[0].to) !== Date.parse(point.to))
      throw new Error('Recorded roaming populations have incompatible bucket bounds.');
    bucket.push(point); buckets.set(start, bucket);
  }
  const windows: KpiWindow[] = [...buckets].sort(([a], [b]) => a-b).map(([start, bucket]) => ({
    schemaVersion: 2, featureVersion: 2, windowId: `recorded-roaming-${direction}-${start}`,
    scopeId: `roaming:${direction}`, service: 'VOLTE', windowStart: new Date(start).toISOString(), windowEnd: bucket[0].to,
    quality: roamingRate(bucket) === null ? 'MISSING' : bucket.length === partners.length && bucket.every(point => point.quality === 'VALID') ? 'COMPLETE' : 'INCOMPLETE',
    baselineVersion: 'unavailable', topologyVersion: dataset.catalogueVersion ?? 'unavailable',
    kpis: [{ name: 'registrationSrPct', observed: roamingRate(bucket), baseline: null, unit: 'PERCENT',
      numerator: null, denominator: null }], featureNames: [], featureValues: [], mlEligible: false, sourceEventIds: [],
  }));
  return { datasetId:dataset.datasetId, direction, resolution, from, to, rows, windows, rates: { registration: roamingRate(registration),
    data: roamingRate(partners.flatMap(partner => points(partner, 1))),
    voice: roamingRate(partners.flatMap(partner => points(partner, 2))), sms: null },
    note: `${dataset.synthetic ? 'Synthetic recorded' : 'Recorded'} ${direction === 'out' ? 'outbound' : 'inbound'} · ${from.slice(0, 16).replace('T', ' ')} – ${to.slice(0, 16).replace('T', ' ')} UTC · Partner counts are estimates, not distinct subscribers. SMS delivery evidence is not recorded; — means missing. Source quality is unverified.` };
}
