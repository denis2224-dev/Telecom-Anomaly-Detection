import { baseline, bindGeography, cities, cityForScope, cityServices, deviation, measured, geographyValue } from './dashboard-geography';
import type { GeographyCatalogue } from '../../core/api/telecom-client';
import { moldovaOutline, projectCity } from './moldova-map';
import { cityIncidents, citySummaries, cityWindows, fixtureCities } from '../../../fixtures/connected-dashboard';

describe('Connected dashboard contract', () => {
  it('joins only authoritative city memberships and preserves missing values and coverage', () => {
    const response: GeographyCatalogue = {
      generatedAt: '2026-10-06T12:00:00Z', catalogueVersion: 'active-catalogue', topologyVersion: 'active-topology',
      cities: [{ cityId: 'CHI', displayName: 'Chișinău', synthetic: true,
        catalogueVersion: 'active-catalogue', topologyVersion: 'active-topology',
        services: (['VOLTE', 'SMS'] as const).map(service => ({ service, scopeId: `${service}-MD-CHI`, latestWindowEnd: null,
          freshness: 'NEVER_SEEN', technicalActiveCount: 0, analystOpenCount: 0,
          coverage: { state: 'UNKNOWN', expectedSources: null, receivedSources: null, usableSources: null },
          metric: { name: service === 'VOLTE' ? 'TECHNICAL_CSSR' : 'SMS_DELIVERY_P95', unit: service === 'VOLTE' ? 'PERCENT' : 'MILLISECONDS', observed: null, baseline: null, deltaPp: null, delayRatio: null, nullReason: 'NEVER_SEEN' } })) }],
    };
    response.cities = cities.map(city => ({ ...response.cities[0], cityId: city.id, displayName: city.name,
      services: response.cities[0].services.map(state => ({ ...state, scopeId: `${state.service}-MD-${city.id}` })) }));
    const mapped = bindGeography(response);
    expect(mapped[0].scopeIds).toEqual(['VOLTE-MD-CHI', 'SMS-MD-CHI']);
    expect(mapped[0].geography?.services[0].coverage.state).toBe('UNKNOWN');
    expect(geographyValue(mapped[0].geography!.services[0])).toBeNull();
    expect(cityForScope(mapped, 'VOLTE-MD-CENTRAL')).toBeUndefined();
    expect(() => bindGeography({ ...response, cities: [] })).toThrow('Incomplete');
    const duplicate = structuredClone(response);
    duplicate.cities[0].services[1].scopeId = 'VOLTE-MD-CHI';
    expect(() => bindGeography(duplicate)).toThrow('Ambiguous');
    duplicate.cities[0].services[1].scopeId = 'SMS-MD-ROUTE-A';
    expect(() => bindGeography(duplicate)).toThrow('Ambiguous');
  });
  it('uses the real outline and the same geographic projection for city markers', () => {
    expect((moldovaOutline.match(/L/g) ?? []).length).toBe(720);
    const city = cities.find(item => item.id === 'CHI')!;
    expect(city.marker).toEqual(projectCity(city.location.longitude, city.location.latitude));
    for (const item of cities.filter(item => item.marker !== null)) {
      expect(item.marker!.x).toBeGreaterThan(0);
      expect(item.marker!.x).toBeLessThan(100);
      expect(item.marker!.y).toBeGreaterThan(0);
      expect(item.marker!.y).toBeLessThan(100);
    }
  });
  it('has nine map markers, five featured cities, and search-only Orhei', () => {
    expect(cities.filter(city => city.marker)).toHaveLength(9);
    expect(cities.filter(city => city.featured)).toHaveLength(5);
    expect(cities.find(city => city.id === 'ORH')?.marker).toBeNull();
    expect(fixtureCities.find(city => city.id === 'ORH')?.scopeIds).toHaveLength(2);
  });

  it('keeps live legacy data unmapped and fixture city joins exact', () => {
    expect(cityForScope(cities, 'VOLTE-MD-CENTRAL')).toBeUndefined();
    const city = fixtureCities.find(city => city.id === 'CHI')!;
    expect(cityServices(city, citySummaries, 'VOLTE').map(item => item.scope.scopeId)).toEqual(['fixture-VOLTE-CHI']);
    expect(cityForScope(fixtureCities, cityIncidents[0].scopeId)?.id).toBe(city.id);
    const ids = fixtureCities.flatMap(city => city.scopeIds);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('preserves unknown measurements, zero samples, and percentage-point deltas', () => {
    const missing = cityWindows.find(row => row.scopeId === 'fixture-VOLTE-SOR')!;
    expect(measured(missing, 'VOLTE')).toBeNull();
    const zero = cityWindows.filter(row => row.scopeId === 'fixture-VOLTE-UNG').at(-1)!;
    expect(measured(zero, 'VOLTE')).toBeNull();
    const sms = cityWindows.filter(row => row.scopeId === 'fixture-SMS-UNG').at(-1)!;
    expect(measured(sms, 'SMS')).toBeNull();
    const degraded = cityWindows.find(row => row.scopeId === 'fixture-VOLTE-CHI')!;
    expect(baseline(degraded, 'VOLTE')).toBe(99.3);
    expect(deviation(degraded, 'VOLTE')).toBe('-5.3 pp from baseline');
  });

  it('makes detail/history/incident fixture IDs agree without copied node evidence', () => {
    for (const incident of cityIncidents) {
      expect(citySummaries.some(item => item.scope.scopeId === incident.scopeId)).toBe(true);
      expect(cityWindows.some(row => row.scopeId === incident.scopeId)).toBe(true);
      expect(incident.latestDetection.scopeId).toBe(incident.scopeId);
      expect(incident.latestDetection.episodeId).toBe(incident.episodeId);
      expect(incident.latestDetection.evidence.every(item => item.nodeId === null)).toBe(true);
    }
    expect(cityIncidents[0].firstObservedAt < cityIncidents[0].detectedAt).toBe(true);
  });
});
