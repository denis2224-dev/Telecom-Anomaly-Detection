import { voiceIncidents } from '../../../fixtures/voice';
import { handoverText, legacyAnalystIncident, nativeAnalystIncident, relatedReason, suggestedChecks } from './analyst-support.model';
import type { components } from '../../core/api/schema';

const selected=()=>({...legacyAnalystIncident(voiceIncidents[0]),id:'selected',domain:'VOLTE',scope:'scope-a',topology:'topology',cities:['CHI'],nodes:['IMS-A'],from:'2026-10-15T10:00:00Z',to:'2026-10-15T10:15:00Z',workflow:'OPEN',technical:'ONGOING'});
describe('Incident assistance',()=>{
  it('requires overlapping recorded time and an actual shared scope, city or dependency',()=>{
    const item=selected(),other={...item,id:'other'};
    expect(relatedReason(item,other)).toContain('Same scope');
    expect(relatedReason(item,{...other,scope:'other',domain:'SMS'})).toContain('Shared city');
    expect(relatedReason(item,{...other,scope:'other',cities:[]})).toContain('Shared dependency');
    expect(relatedReason(item,{...other,scope:'other',cities:[],nodes:[]})).toBeNull();
    expect(relatedReason(item,{...other,from:item.to,to:'2026-10-15T10:20:00Z'})).toBeNull();
    expect(relatedReason(item,{...other,to:'invalid'})).toBeNull();
    expect(relatedReason(item,item)).toBeNull();
  });
  it('does not join unknown locations, different topologies, sources or archives',()=>{
    const item=selected(),other={...item,id:'other',scope:'other'};
    expect(relatedReason(item,{...other,topology:'old'})).toBeNull();
    expect(relatedReason(item,{...other,topology:''})).toBeNull();
    expect(relatedReason(item,{...other,source:'native'})).toBeNull();
    expect(relatedReason({...item,source:'native',dataset:'one'},{...other,source:'native',dataset:'two'})).toBeNull();
    const unknown=legacyAnalystIncident({...voiceIncidents[0],location:{cityId:null,topologyVersion:null,catalogueVersion:null,measuredScopeId:'scope',containmentPath:[],dependencyNodeIds:[],nullReason:'UNALLOCATED'}});
    expect(unknown.cities).toEqual([]);
  });
  it('uses recorded endpoints for native transport and keeps missing workflow unknown',()=>{
    const native={episodeId:'episode',firstObservedAt:'2026-10-15T10:00:00Z',latestWindowEnd:'2026-10-15T10:15:00Z',technicalState:'ONGOING',severity:'HIGH',latestDetection:{context:{domain:'TRANSPORT',datasetId:'archive',topologyVersion:'topology',scope:{id:'chi-hin:a-to-b',cityIds:['CHI','HIN']}},cause:{status:'UNDETERMINED'}}} as unknown as components['schemas']['TraceLinkIncidentView'];
    const item=nativeAnalystIncident(native);
    expect(item.cities).toEqual(['CHI','HIN']);expect(item.workflow).toBeNull();expect(item.cause).toBe('Cause undetermined');
    expect(relatedReason(item,{...item,id:'other',scope:'hin',domain:'VOLTE',cities:['HIN']})).toContain('Shared city');
  });
  it('prioritizes evidence restoration and distinguishes technical recovery from analyst resolution',()=>{
    expect(suggestedChecks({...selected(),technical:'UNKNOWN'})[0]).toContain('missing measurements');
    expect(suggestedChecks({...selected(),technical:'RECOVERED',workflow:'INVESTIGATING'}).join(' ')).toContain('analyst resolution');
    expect(suggestedChecks({...selected(),domain:'ROAMING'}).join(' ')).toContain('partner');
    expect(suggestedChecks({...selected(),domain:'SMS'}).join(' ')).toContain('queue');
    expect(suggestedChecks({...selected(),domain:'TRANSPORT'}).join(' ')).toContain('direction');
  });
  it('exports a qualified snapshot without treating technical recovery or unknown workflow as resolution',()=>{
    const rows=[{...selected(),technical:'RECOVERED'}, {...selected(),id:'other',workflow:null}, {...selected(),id:'resolved',workflow:'RESOLVED'}];
    const text=handoverText(rows,'VoLTE · Chișinău','from','to','3 of 100 · partial snapshot','now');
    expect(text).toContain('1 awaiting acknowledgement');expect(text).toContain('1 workflow states unavailable');
    expect(text).toContain('partial snapshot');expect(text).toContain('Workflow unavailable');expect(text).toContain('Next check (suggested)');
    expect(text).toContain('Resolved · VOLTE · scope-a · resolved');expect(text).not.toContain('Confirmed cause');
  });
});
