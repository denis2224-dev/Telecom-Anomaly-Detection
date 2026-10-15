import geography from '../../../../../../contracts/geography/trace-link-geography-v2.json';
import topology from '../../../../../../contracts/topology/trace-link-links-v1.json';
import type { components } from '../../core/api/schema';
import { projectCity } from './moldova-map';
import type { GeographyCatalogue } from '../../core/api/telecom-client';

export type Summary = components['schemas']['ServiceSummary'];
export type Window = components['schemas']['ServiceKpiWindow'];
export type Episode = components['schemas']['Incident'];
export type Service = Summary['scope']['service'];
export type Filter = 'ALL' | Service;

export interface City {
  id: string;
  name: string;
  // Projected geographic percentages; null means search/detail only.
  marker: { x: number; y: number } | null;
  location: { latitude: number; longitude: number; geonamesId?: number };
  labelSide: 'left' | 'right';
  featured: boolean;
  // Populate only with approved exact backend scope IDs in live mode.
  scopeIds: readonly string[];
  geography?: components['schemas']['GeographyCitySummary'];
}

// Geographic coordinates and display choices only; LIVE membership is supplied by the backend.
export const cities: readonly City[] = geography.cities.filter(city => city.active).map(city => ({
  id: city.contractCityId, name: city.displayName,
  marker: projectCity(city.longitude, city.latitude),
  location: {latitude: city.latitude, longitude: city.longitude},
  labelSide: ['CHI','BAL','EDI','CAH','HIN'].includes(city.contractCityId) ? 'left' : 'right',
  featured: ['CHI','BAL','SOR','UNG','CAH'].includes(city.contractCityId), scopeIds: [],
}));

// Add only catalogue-approved edges. Empty means no network links are asserted.
export interface ApprovedConnection {
  id: string;
  fromCityId: string;
  toCityId: string;
}
export const approvedConnections: readonly ApprovedConnection[] = topology.links.map(link => ({
  id: link.linkId, fromCityId: link.endpointAContractCityId, toCityId: link.endpointBContractCityId,
}));

// Static data supplies coordinates only. Membership and measurements come from protected reads.
export function bindGeography(response: GeographyCatalogue): readonly City[] {
  if (response.cities.length !== cities.length || !response.catalogueVersion || !response.topologyVersion
    || !Number.isFinite(Date.parse(response.generatedAt))) throw new Error('Incomplete city catalogue.');
  const ids = new Set<string>(), scopes = new Set<string>();
  const mapped = response.cities.filter(item => !['RIB','TIR'].includes(item.cityId)).map(item => {
    const city = cities.find(city => city.id === item.cityId);
    if (!city || ids.has(item.cityId) || item.catalogueVersion !== response.catalogueVersion
      || item.topologyVersion !== response.topologyVersion || item.services.length !== 2
      || new Set(item.services.map(state => state.service)).size !== 2) throw new Error('Invalid city catalogue.');
    ids.add(item.cityId);
    for (const state of item.services) {
      if (!state.scopeId || scopes.has(state.scopeId)
        || state.scopeId === 'VOLTE-MD-CENTRAL' || state.scopeId === 'SMS-MD-ROUTE-A') throw new Error('Ambiguous city membership.');
      scopes.add(state.scopeId);
    }
    return { ...city, name: item.displayName, scopeIds: item.services.map(state => state.scopeId), geography: item };
  });
  // A legacy catalogue cannot assert memberships for new cities. Keep those explicitly unavailable.
  return cities.map(city => mapped.find(item => item.id === city.id) ?? city);
}

export function geographyState(city: City, filter: Filter) {
  return city.geography?.services.filter(state => filter === 'ALL' || state.service === filter) ?? [];
}

export function geographyValue(state: components['schemas']['GeographyServiceState']): number | null {
  const value = state.metric.observed;
  if (state.freshness === 'MISSING' || state.freshness === 'NEVER_SEEN' || value === null || !Number.isFinite(value)) return null;
  const volume = state.service === 'VOLTE' ? state.metric.denominator : state.metric.sampleCount;
  return volume != null && volume > 0 ? value : null;
}

export function cityServices(city: City, summaries: readonly Summary[], filter: Filter): Summary[] {
  return summaries.filter(item => city.scopeIds.includes(item.scope.scopeId)
    && (filter === 'ALL' || item.scope.service === filter));
}

export function cityForScope(catalogue: readonly City[], scopeId: string): City | undefined {
  return catalogue.find(city => city.scopeIds.includes(scopeId));
}

export function primaryKpi(window: Window | null, service: Service) {
  const name = service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs';
  return window?.kpis.find(kpi => kpi.name === name);
}

export function measured(window: Window | null, service: Service): number | null {
  if (!window || window.quality !== 'COMPLETE') return null;
  const kpi = primaryKpi(window, service);
  if (!kpi || kpi.observed === null || !Number.isFinite(kpi.observed)) return null;
  if (service === 'VOLTE') {
    if (kpi.unit !== 'PERCENT' || kpi.denominator === null || kpi.denominator <= 0) return null;
  } else {
    const delivered = window.kpis.find(item => item.name === 'deliveredMessages')?.observed;
    if (kpi.unit !== 'MILLISECONDS' || delivered === null || delivered === undefined || delivered <= 0) return null;
  }
  return kpi.observed;
}

export function baseline(window: Window | null, service: Service): number | null {
  const value = primaryKpi(window, service)?.baseline;
  return value !== undefined && value !== null && Number.isFinite(value) ? value : null;
}

export function deviation(window: Window | null, service: Service): string {
  const actual = measured(window, service), expected = baseline(window, service);
  if (actual === null || expected === null) return 'Delta unavailable';
  const value = actual - expected;
  return `${value > 0 ? '+' : ''}${number(value)} ${service === 'VOLTE' ? 'pp' : 'ms'} from baseline`;
}

export function number(value: number): string {
  return new Intl.NumberFormat('en', { maximumFractionDigits: 2 }).format(value);
}

export function metric(window: Window | null, service: Service): string {
  const value = measured(window, service);
  return value === null ? 'Unavailable' : `${number(value)} ${service === 'VOLTE' ? '%' : 'ms'}`;
}

export function sampleVolume(window: Window | null, service: Service): string {
  const value = service === 'VOLTE' ? primaryKpi(window, service)?.denominator
    : window?.kpis.find(item => item.name === 'deliveredMessages')?.observed;
  return value === null || value === undefined ? 'Unavailable' : number(value);
}

export function cityLabel(catalogue: readonly City[], scopeId: string): string {
  return cityForScope(catalogue, scopeId)?.name ?? 'Unmapped / legacy scope';
}
