import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { traceContextParameters, validTraceContext, type TraceQueryContext } from '../../core/state/trace-query.store';
export function grafanaContextUrl(base: string, context: TraceQueryContext): string | null {
  if (!base || !validTraceContext(context)) return null;
  try {
    const url = new URL(base);
    let credentials=false;url.searchParams.forEach((_,key)=>{if(/token|secret|password|credential|auth/i.test(key))credentials=true;});
    if (!['http:','https:'].includes(url.protocol) || url.username || url.password
      || credentials) return null;
    for (const [key,value] of Object.entries(traceContextParameters(context))) url.searchParams.set('var-'+key,value);
    url.searchParams.set('from',String(Date.parse(context.visibleFrom??context.from)));
    url.searchParams.set('to',String(Date.parse(context.visibleTo??context.to)));
    url.searchParams.set('timezone',context.timezone);
    return url.href;
  } catch { return null; }
}
@Injectable({providedIn:'root'})
export class GrafanaLinkService {
  private readonly document=inject(DOCUMENT);
  url(context:TraceQueryContext):string|null {
    return grafanaContextUrl(this.document.querySelector<HTMLMetaElement>('meta[name="trace-link-grafana-url"]')?.content??'',context);
  }
}
