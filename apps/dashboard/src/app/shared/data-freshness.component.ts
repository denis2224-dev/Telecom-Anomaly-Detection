import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';

export function freshnessLabel(at:string|null,end:string,now:number,sample=false,loading=false,interval=60_000):{state:string;text:string} {
  if(sample)return {state:'SAMPLE',text:'Sample data'};
  if(loading)return {state:'LOADING',text:'Loading measurements…'};
  const measured=Date.parse(at??''),rangeEnd=Date.parse(end);
  if(!Number.isFinite(measured)||!Number.isFinite(rangeEnd))return {state:'MISSING',text:'No recorded measurements'};
  if(measured>now+60_000||measured>rangeEnd+1000)return {state:'UNKNOWN',text:'Check measurement timestamp'};
  const age=Math.max(0,now-measured),gap=Math.max(0,rangeEnd-measured),tolerance=Math.max(300_000,interval*2);
  const duration=(ms:number)=>ms<60_000?'less than a minute':ms<3_600_000?`${Math.floor(ms/60_000)}m`:ms<86_400_000?`${Math.floor(ms/3_600_000)}h`:`${Math.floor(ms/86_400_000)}d`;
  const timestamp=new Date(measured).toISOString().slice(5,16).replace('T',' ')+' UTC';
  if(gap>tolerance)return {state:'GAP',text:`Last measurement ${duration(age)} ago · ${duration(gap)} before selected end`};
  if(now-rangeEnd>tolerance)return {state:'HISTORICAL',text:`Historical · ${timestamp} · ${duration(age)} ago`};
  return {state:age>tolerance?'STALE':'RECENT',text:`Last measurement ${duration(age)} ago`};
}
@Component({
  selector:'app-data-freshness',
  template:`<p class="data-freshness" aria-label="Data freshness" [attr.data-state]="label().state">{{label().text}}</p>`,
  styles:[`:host{display:block;}p{margin:6px 0;font-size:11px;color:var(--text-muted);overflow-wrap:anywhere;}p[data-state=GAP],p[data-state=STALE],p[data-state=UNKNOWN]{color:var(--warning);}p[data-state=RECENT]{color:var(--success);}p[data-state=SAMPLE]{color:var(--warning);}`],
})
export class DataFreshnessComponent {
  readonly at=input<string|null>(null);readonly end=input('');readonly sample=input(false);readonly loading=input(false);readonly interval=input(60_000);
  private readonly now=signal(Date.now());
  readonly label=computed(()=>freshnessLabel(this.at(),this.end(),this.now(),this.sample(),this.loading(),this.interval()));
  constructor(){const timer=setInterval(()=>this.now.set(Date.now()),30_000);inject(DestroyRef).onDestroy(()=>clearInterval(timer));}
}
