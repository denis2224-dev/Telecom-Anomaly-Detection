import { grafanaContextUrl } from './grafana-link.service';
import type { TraceQueryContext } from '../../core/state/trace-query.store';
const context:TraceQueryContext={datasetId:'recorded',from:'2026-10-10T09:00:00Z',to:'2026-10-10T15:00:00Z',resolution:'15m',timezone:'Europe/Chisinau',cityId:'chi',linkDirectionId:'chi-orh:a-to-b',countryId:'RO',roamingDirection:'out',domain:'roaming',visibleFrom:'2026-10-10T10:00:00Z',visibleTo:'2026-10-10T12:00:00Z'};
it('carries the absolute visible interval and all scope dimensions',()=>{
  const url=new URL(grafanaContextUrl('https://grafana.example/d/trace',context)!);
  expect(url.searchParams.get('from')).toBe(String(Date.parse(context.visibleFrom!)));
  for(const [key,value]of Object.entries(context))expect(url.searchParams.get('var-'+key)).toBe(value);
  expect(url.searchParams.get('timezone')).toBe('Europe/Chisinau');
});
it('rejects invalid and credential-bearing Grafana configuration',()=>{
  for(const base of ['', 'javascript:alert(1)','https://user:secret@grafana.example/d/trace','https://grafana.example/d/trace?token=secret'])expect(grafanaContextUrl(base,context)).toBeNull();
});

it('clears stale configured scope variables before applying the selected context',()=>{
  const url=new URL(grafanaContextUrl('https://grafana.example/d/trace?var-partnerDirectionId=OLD:in&var-linkDirectionId=old:a-to-b',{...context,linkDirectionId:undefined,partnerDirectionId:undefined})!);
  expect(url.searchParams.has('var-partnerDirectionId')).toBe(false);expect(url.searchParams.has('var-linkDirectionId')).toBe(false);
});
