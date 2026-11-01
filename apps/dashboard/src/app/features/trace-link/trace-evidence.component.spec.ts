import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import type { components } from '../../core/api/schema';
import { TraceEvidenceComponent, evidenceSamples, validateEvidence } from './trace-evidence.component';

const scope={id:'partner-42:in',partnerId:'partner-42',directionId:'in',procedure:'REGISTRATION'};
const envelope:components['schemas']['TraceLinkDetectionEnvelope']={
  schemaVersion:'trace-link-detection-v1',synthetic:true,committedAt:'2026-10-06T01:00:00Z',
  lifecycle:{schemaVersion:'trace-link-lifecycle-v1',episodeId:'episode',detectionId:'detection',sequence:1,phase:'OPEN',technicalState:'ONGOING',severity:'HIGH',window:{from:'2026-10-06T00:55:00Z',to:'2026-10-06T01:00:00Z'},evaluation:{}},
  context:{datasetId:'archive',domain:'ROAMING',scope,evidence:[{metricId:'roaming.registration.success',actual:0,unit:'PERCENT',baseline:99,threshold:null,
    primitives:{numerator:0,denominator:100},quality:{state:'VALID',reason:null},provenance:{datasetId:'archive',synthetic:true,origin:'NATIVE_300',sourceChecksum:'source-sha'}}]},cause:{},causeEvidence:{},
};
const incident:components['schemas']['TraceLinkIncidentView']={episodeId:'episode',firstObservedAt:'2026-10-06T00:55:00Z',detectedAt:'2026-10-06T01:00:00Z',latestWindowEnd:'2026-10-06T01:00:00Z',latestSequence:1,technicalState:'ONGOING',severity:'HIGH',latestDetection:envelope};

describe('Committed roaming evidence',()=>{
  it('retains zero counts and separates missing thresholds and source provenance',()=>{
    expect(evidenceSamples(envelope)[0]).toMatchObject({actual:'0',numerator:'0',denominator:'100',baseline:'99',threshold:'Unavailable',provenance:'Synthetic source',checksum:'source-sha'});
    expect(evidenceSamples({...envelope,context:{}})).toEqual([]);
  });
  it('rejects a different archive, partner, procedure, episode or unordered sequence',()=>{
    expect(()=>validateEvidence(incident,[envelope])).not.toThrow();
    for(const invalid of [
      {...envelope,context:{...envelope.context,datasetId:'other'}},
      {...envelope,context:{...envelope.context,scope:{...scope,partnerId:'other'}}},
      {...envelope,context:{...envelope.context,scope:{...scope,procedure:'VOICE'}}},
      {...envelope,lifecycle:{...envelope.lifecycle,episodeId:'other'}},
    ])expect(()=>validateEvidence(incident,[invalid])).toThrow('population or sequence');
    expect(()=>validateEvidence(incident,[envelope,envelope])).toThrow('sequence');
  });
  it('clears visible evidence on session expiry and ignores an old pending response',async()=>{
    const phase=signal('authenticated');let resolve!:(value:unknown)=>void;
    const traceIncidentEvidence=vi.fn(()=>new Promise(value=>resolve=value));
    TestBed.configureTestingModule({providers:[{provide:TelecomClient,useValue:{traceIncidentEvidence}},
      {provide:SessionStore,useValue:{phase,actor:signal({}),revision:1}}]});
    const fixture=TestBed.createComponent(TraceEvidenceComponent);fixture.componentRef.setInput('incident',incident);fixture.detectChanges();
    expect(traceIncidentEvidence).toHaveBeenCalledTimes(1);
    const request=traceIncidentEvidence.mock.calls[0] as unknown as [string,number,AbortSignal];
    phase.set('expired');fixture.detectChanges();expect(request[2].aborted).toBe(true);
    resolve({items:[envelope],total:1,limit:50,offset:0});await fixture.whenStable();fixture.detectChanges();
    expect(fixture.componentInstance.rows()).toEqual([]);
    expect(fixture.nativeElement.textContent).not.toContain('source-sha');
    fixture.destroy();
  });
  it('shows a retryable error instead of evidence from another population',async()=>{
    const traceIncidentEvidence=vi.fn().mockResolvedValue({items:[{...envelope,context:{...envelope.context,datasetId:'other'}}],total:1,offset:0});
    TestBed.configureTestingModule({providers:[{provide:TelecomClient,useValue:{traceIncidentEvidence}},
      {provide:SessionStore,useValue:{phase:signal('authenticated'),actor:signal({}),revision:1}}]});
    const fixture=TestBed.createComponent(TraceEvidenceComponent);fixture.componentRef.setInput('incident',incident);fixture.detectChanges();
    await vi.waitFor(()=>expect(fixture.componentInstance.error()).toContain('population or sequence'));fixture.detectChanges();
    expect(fixture.componentInstance.rows()).toEqual([]);
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('population or sequence');
    expect(fixture.nativeElement.textContent).toContain('Retry evidence');
    fixture.destroy();
  });
});
