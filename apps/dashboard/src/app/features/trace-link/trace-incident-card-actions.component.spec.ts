import { nativeIncidentContext, nativeIncidentMetric } from './trace-incident-card-actions.component';
import type { components } from '../../core/api/schema';
import type { TraceQueryContext } from '../../core/state/trace-query.store';

const context:TraceQueryContext={datasetId:'archive',from:'2026-10-01T00:00:00Z',to:'2026-10-02T00:00:00Z',resolution:'1h',timezone:'UTC',cityId:'all',countryId:'all',roamingDirection:'both',domain:'volte'};
function incident(domain:string,scope:Record<string,string>) {
  const from='2026-10-01T01:00:00Z',to='2026-10-01T02:00:00Z';
  return {episodeId:'episode-1',firstObservedAt:from,detectedAt:from,latestWindowEnd:to,latestSequence:1,technicalState:'ONGOING',severity:'HIGH',latestDetection:{schemaVersion:'trace-link-detection-v1',synthetic:true,committedAt:to,context:{datasetId:'archive',domain,scope},cause:{},causeEvidence:{},lifecycle:{schemaVersion:'trace-link-lifecycle-v1',episodeId:'episode-1',detectionId:'detection-1',sequence:1,phase:'OPEN',technicalState:'ONGOING',severity:'HIGH',window:{},evaluation:{}}}} satisfies components['schemas']['TraceLinkIncidentView'];
}
describe('Native incident dashboard context',()=>{
  it('selects the incident procedure metric before supporting control measurements',()=>{
    for(const [procedure,metric] of Object.entries({REGISTRATION:'roaming.registration.success',DATA:'roaming.data.success',VOICE:'roaming.voice.success'})){
      const item:components['schemas']['TraceLinkIncidentView']=incident('ROAMING',{id:'partner:out',partnerId:'partner',directionId:'out',procedure});
      item.latestDetection.context['evidence']=[{metricId:'roaming.registration.success'}];
      expect(nativeIncidentMetric(item)).toBe(metric);
    }
  });
  it('preserves independent resolution and selects the recorded directional link',()=>{
    const next=nativeIncidentContext(incident('TRANSPORT',{id:'chi-hin:a-to-b'}),context);
    expect(next.linkDirectionId).toBe('chi-hin:a-to-b');expect(next.cityId).toBe('all');expect(next.resolution).toBe('1h');
    expect(next.from).toBe('2026-10-01T00:00:00.000Z');expect(next.to).toBe('2026-10-02T00:00:00.000Z');
  });
  it('keeps roaming partner and direction as an independent population',()=>{
    const next=nativeIncidentContext(incident('ROAMING',{id:'RO-DEMO-01:out',partnerId:'RO-DEMO-01',directionId:'out'}),context);
    expect(next.countryId).toBe('all');expect(next.partnerDirectionId).toBe('RO-DEMO-01:out');expect(next.roamingDirection).toBe('out');
    expect(next.linkDirectionId).toBeUndefined();
  });
  it('uses declared country metadata and never derives it from an opaque partner identifier',()=>{
    expect(nativeIncidentContext(incident('ROAMING',{id:'partner-42:out',partnerId:'partner-42',directionId:'out',countryId:'IT'}),context).countryId).toBe('IT');
    expect(nativeIncidentContext(incident('ROAMING',{id:'partner-42:out',partnerId:'partner-42',directionId:'out'}),context).countryId).toBe('all');
  });
  it('rejects incomplete and unsupported source contexts instead of inventing a scope',()=>{
    expect(()=>nativeIncidentContext(incident('VOLTE',{}),context)).toThrow('scope unavailable');
    expect(()=>nativeIncidentContext(incident('ROAMING',{id:'partner'}),context)).toThrow('partner population unavailable');
    expect(()=>nativeIncidentContext(incident('OTHER',{id:'chi'}),context)).toThrow('domain unavailable');
  });
});
