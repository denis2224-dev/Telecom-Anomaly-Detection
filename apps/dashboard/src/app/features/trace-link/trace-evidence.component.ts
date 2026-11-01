import { Component, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TelecomClient } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { SessionStore } from '../login-and-session/session.store';
import { metricLabel } from '../../shared/metric-presentation';

type Envelope = components['schemas']['TraceLinkDetectionEnvelope'];
type Incident = components['schemas']['TraceLinkIncidentView'];
const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const text = (value: unknown) => typeof value === 'string' && value ? value : 'Unavailable';
const number = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString('en', {maximumFractionDigits:3}) : 'Unavailable';

export function evidenceSamples(envelope: Envelope) {
  const evidence = envelope.context['evidence'];
  return (Array.isArray(evidence) ? evidence : []).map(value => {
    const row=object(value), primitives=object(row['primitives']), source=object(row['provenance']), quality=object(row['quality']), window=object(row['window']), scope=object(row['scope']);
    const metric=text(row['metricId']);
    return { metric, label:metricLabel(metric), actual:number(row['actual']), unit:text(row['unit']),
      baseline:number(row['baseline']), threshold:number(row['threshold']), numerator:number(primitives['numerator']), denominator:number(primitives['denominator']),
      quality:text(quality['state']), reason:typeof quality['reason']==='string'?quality['reason']:'',
      from:text(window['from']), to:text(window['to']), dataset:text(source['datasetId']),
      origin:text(source['origin']), checksum:text(source['sourceChecksum']),
      scope:[scope['partnerId']??scope['id'],scope['directionId'],scope['procedure']].filter(value=>typeof value==='string').join(' · ') || 'Population unavailable',
      baselineVersion:text(row['baselineVersion']),coverage:number(row['coverage']),
      provenance:source['synthetic']===true?'Synthetic source':'Source provenance unverified' };
  });
}

export function validateEvidence(incident: Incident, rows: Envelope[]): void {
  let previous = 0;
  let previousEnd=Date.parse(incident.firstObservedAt);
  const expected=incident.latestDetection.context, scope=object(expected['scope']);
  for(const row of rows) {
    const actual=object(row.context['scope']);
    if(row.lifecycle.episodeId!==incident.episodeId || row.context['datasetId']!==expected['datasetId'] || row.context['domain']!==expected['domain']
      || ['id','partnerId','directionId','procedure'].some(key=>actual[key]!==scope[key]) || row.lifecycle.sequence<=previous)
      throw new Error('Evidence does not match the selected incident population or sequence.');
    previous=row.lifecycle.sequence;
    const from=Date.parse(String(row.lifecycle.window['from'])),to=Date.parse(String(row.lifecycle.window['to']));
    if(!Number.isFinite(from)||!Number.isFinite(to)||from<previousEnd||to<=from||to>Date.parse(incident.latestWindowEnd))
      throw new Error('Evidence windows do not match the selected incident interval.');
    previousEnd=to;
  }
}

@Component({
  selector:'app-trace-evidence', imports:[DatePipe],
  templateUrl: './trace-evidence.component.html',
  styleUrl: './trace-evidence.component.css',
})
export class TraceEvidenceComponent {
  readonly incident=input.required<Incident>();
  readonly rows=signal<Envelope[]>([]);readonly page=signal(0);readonly total=signal(0);readonly busy=signal(false);readonly error=signal('');readonly refresh=signal(0);
  readonly samples=evidenceSamples;
  window(item:Envelope):{from:string|null;to:string|null}{const window=item.lifecycle.window;return {from:typeof window['from']==='string'?window['from']:null,to:typeof window['to']==='string'?window['to']:null};}
  serialize(value:unknown):string{return JSON.stringify(value,null,2);}
  constructor(){
    const api=inject(TelecomClient),session=inject(SessionStore);
    effect(()=>{this.incident().episodeId;this.page.set(0);});
    effect(cleanup=>{
      const incident=this.incident(),page=this.page(),revision=session.revision;this.refresh();session.actor();
      this.rows.set([]);this.total.set(0);this.error.set('');this.busy.set(false);
      if(session.phase()!=='authenticated')return;
      const request=new AbortController();cleanup(()=>request.abort());this.busy.set(true);
      void api.traceIncidentEvidence(incident.episodeId,page*50,request.signal).then(result=>{
        if(request.signal.aborted||revision!==session.revision)return;
        if(result.items.length>50 || result.offset!==page*50 || result.total<result.items.length)throw new Error('Evidence response does not match the selected page.');
        validateEvidence(incident,result.items);this.rows.set(result.items);this.total.set(result.total);
      }).catch(error=>{if(!request.signal.aborted && revision===session.revision)this.error.set(error instanceof Error?error.message:'Incident evidence unavailable.');})
        .finally(()=>{if(!request.signal.aborted && revision===session.revision)this.busy.set(false);});
    });
  }
}
