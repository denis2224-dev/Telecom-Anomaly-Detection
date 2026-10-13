import { Injectable, inject, signal, DestroyRef } from '@angular/core';
import { SessionStore } from '../../features/login-and-session/session.store';

export interface TraceQueryContext {
  datasetId: string;
  from: string;
  to: string;
  resolution: '5m' | '15m' | '1h' | '6h';
  timezone: 'UTC' | 'Europe/Chisinau';
  cityId: string;
  linkDirectionId?: string;
  countryId: string;
  roamingDirection: 'out' | 'in' | 'both';
  domain: 'volte' | 'transport' | 'roaming' | 'sms' | 'mobile-data';
  visibleFrom?: string;
  visibleTo?: string;
}
const HOURS = [6,24,48,168,336,720];
const defaults = (): TraceQueryContext => {
  const to = Math.floor(Date.now()/300000)*300000;
  return {datasetId:'',from:new Date(to-86400000).toISOString(),to:new Date(to).toISOString(),resolution:'5m',timezone:'UTC',
    cityId:'all',countryId:'all',roamingDirection:'both',domain:'volte'};
};
export function validTraceContext(context: TraceQueryContext): boolean {
  const from=Date.parse(context.from),to=Date.parse(context.to);
  const utc=(value:string)=>value.endsWith('Z') && Number.isFinite(Date.parse(value));
  return utc(context.from) && utc(context.to) && to>from && to-from<=30*86400000 && context.datasetId.length<=128
    && ['5m','15m','1h','6h'].includes(context.resolution) && ['UTC','Europe/Chisinau'].includes(context.timezone)
    && ['volte','transport','roaming','sms','mobile-data'].includes(context.domain) && ['in','out','both'].includes(context.roamingDirection)
    && /^[A-Za-z0-9_.:-]{1,96}$/.test(context.cityId) && /^[A-Za-z0-9_.:-]{1,96}$/.test(context.countryId)
    && (!context.linkDirectionId || /^[A-Za-z0-9_.:-]{1,128}$/.test(context.linkDirectionId))
    && ((!context.visibleFrom && !context.visibleTo) || (!!context.visibleFrom && !!context.visibleTo && utc(context.visibleFrom) && utc(context.visibleTo)
      && Date.parse(context.visibleFrom)>=from && Date.parse(context.visibleTo)<=to && Date.parse(context.visibleTo)>Date.parse(context.visibleFrom)));
}
export function traceContextParameters(context: TraceQueryContext): Record<string,string> {
  return Object.fromEntries(Object.entries(context).filter((entry):entry is [string,string]=>typeof entry[1]==='string' && entry[1]!==''));
}
@Injectable({providedIn:'root'})
export class TraceQueryStore {
  private readonly value=signal<Readonly<TraceQueryContext>>(Object.freeze(defaults()));
  readonly context=this.value.asReadonly();
  readonly revision=signal(0);
  constructor() {
    const ended=inject(SessionStore).ended$.subscribe(()=>this.reset());
    inject(DestroyRef).onDestroy(()=>ended.unsubscribe());
  }
  update(patch:Partial<TraceQueryContext>,compatibleLinks:readonly {id:string;endpointA:string;endpointB:string}[]=[]): boolean {
    const next={...this.value(),...patch};
    if (patch.cityId && patch.cityId!==this.value().cityId && next.cityId!=='all' && next.linkDirectionId) {
      const link=compatibleLinks.find(link=>next.linkDirectionId!.startsWith(link.id+':'));
      if (!link || ![link.endpointA,link.endpointB].includes(next.cityId)) next.linkDirectionId=undefined;
    }
    if ((patch.from!==undefined || patch.to!==undefined) && patch.visibleFrom===undefined && patch.visibleTo===undefined) {next.visibleFrom=undefined;next.visibleTo=undefined;}
    if(!validTraceContext(next)) return false;
    this.value.set(Object.freeze(next));this.revision.update(value=>value+1);return true;
  }
  preset(hours:number): void {
    if(!HOURS.includes(hours))return;
    this.update({from:new Date(Date.parse(this.value().to)-hours*3600000).toISOString()});
  }
  zoom(from:string,to:string): boolean {return this.update({visibleFrom:from,visibleTo:to});}
  resetZoom(): void {this.update({visibleFrom:undefined,visibleTo:undefined});}
  restore(parameters:Record<string,string>): boolean {
    const keys=Object.keys(defaults()).concat(['linkDirectionId','visibleFrom','visibleTo']);
    const patch=Object.fromEntries(Object.entries(parameters).filter(([key])=>keys.includes(key)));
    return this.update(patch);
  }
  private reset(): void {this.value.set(Object.freeze(defaults()));this.revision.update(value=>value+1);}
}
