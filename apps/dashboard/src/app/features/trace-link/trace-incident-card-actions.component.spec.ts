import { nativeIncidentContext } from './trace-incident-card-actions.component';
import type { components } from '../../core/api/schema';
import type { TraceQueryContext } from '../../core/state/trace-query.store';

const context:TraceQueryContext={datasetId:'archive',from:'2026-10-01T00:00:00Z',to:'2026-10-02T00:00:00Z',resolution:'1h',timezone:'UTC',cityId:'all',countryId:'all',roamingDirection:'both',domain:'volte'};
function incident(domain:string,scope:Record<string,string>) {
  const from='2026-10-01T01:00:00Z',to='2026-10-01T02:00:00Z';
  return {episodeId:'episode-1',firstObservedAt:from,detectedAt:from,latestWindowEnd:to,latestSequence:1,technicalState:'ONGOING',severity:'HIGH',latestDetection:{schemaVersion:'trace-link-detection-v1',synthetic:true,committedAt:to,context:{datasetId:'archive',domain,scope},cause:{},causeEvidence:{},lifecycle:{schemaVersion:'trace-link-lifecycle-v1',episodeId:'episode-1',detectionId:'detection-1',sequence:1,phase:'OPEN',technicalState:'ONGOING',severity:'HIGH',window:{},evaluation:{}}}} satisfies components['schemas']['TraceLinkIncidentView'];
}
describe('Native incident dashboard context',()=>{
  it('preserves independent resolution and selects the recorded directional link',()=>{
    const next=nativeIncidentContext(incident('TRANSPORT',{id:'chi-hin:a-to-b'}),context);
    expect(next.linkDirectionId).toBe('chi-hin:a-to-b');expect(next.cityId).toBe('all');expect(next.resolution).toBe('1h');
    expect(next.from).toBe('2026-10-01T01:00:00Z');expect(next.to).toBe('2026-10-01T02:00:00Z');
  });
  it('keeps roaming partner and direction as an independent population',()=>{
    const next=nativeIncidentContext(incident('ROAMING',{id:'RO-DEMO-01:out',partnerId:'RO-DEMO-01',directionId:'out'}),context);
    expect(next.countryId).toBe('RO');expect(next.partnerDirectionId).toBe('RO-DEMO-01:out');expect(next.roamingDirection).toBe('out');
    expect(next.linkDirectionId).toBeUndefined();
  });
  it('rejects incomplete and unsupported source contexts instead of inventing a scope',()=>{
    expect(()=>nativeIncidentContext(incident('VOLTE',{}),context)).toThrow('scope unavailable');
    expect(()=>nativeIncidentContext(incident('ROAMING',{id:'partner'}),context)).toThrow('partner population unavailable');
    expect(()=>nativeIncidentContext(incident('OTHER',{id:'chi'}),context)).toThrow('domain unavailable');
  });
});
