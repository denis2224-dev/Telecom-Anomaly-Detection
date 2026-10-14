import { nativeScope, nativeChartSeries } from './native-domain-panel.component';
import type { TraceQueryContext } from '../../core/state/trace-query.store';
import type { TraceNativeSeries } from '../../core/api/telecom-client';
const context:TraceQueryContext={datasetId:'recorded',from:'2026-09-10T09:00:00Z',to:'2026-09-10T09:05:00Z',resolution:'5m',timezone:'UTC',cityId:'hin',linkDirectionId:'chi-orh:a-to-b',countryId:'RO',roamingDirection:'out',domain:'transport'};
it('keeps complete link direction and procedure identities independent',()=>{
  expect(nativeScope(context,'transport.traffic')).toEqual({scopeNamespace:'trace-link-reference-v1',domain:'TRANSPORT',scopeType:'LINK_DIRECTION',scopeId:'chi-orh',linkId:'chi-orh',directionId:'a-to-b'});
  const activation=nativeScope({...context,domain:'mobile-data'},'mobileData.activation.success')!;
  const dns=nativeScope({...context,domain:'mobile-data'},'mobileData.dns.success')!;
  expect(activation.procedure).toBe('EPS_BEARER_ACTIVATION');expect(dns.procedure).toBe('DNS');expect(dns.contractCityId).toBe('HIN');
  const partner={id:'RO-DEMO-01',country:'RO',direction:'in' as const,active:true,validFrom:context.from,validTo:null,synthetic:true};
  expect(nativeScope({...context,domain:'roaming'},'roaming.registration.success',partner)?.direction).toBe('in');
});
it('adapts exact source values without fabricating quality, coverage or primitives',()=>{
  const source={datasetId:'recorded',domain:'mobile-data',metricId:'mobileData.dns.success',scope:{scopeType:'CITY_SERVICE',scopeId:'hin'},from:context.from,to:context.to,sourceResolutionSeconds:300,unit:'PERCENT',aggregationType:'RATIO_OF_SUMS',synthetic:true,topologyVersion:'topology',policyVersion:'policy',points:[{from:context.from,to:context.to,value:null,quality:'INSUFFICIENT_EVIDENCE',sourceCoverage:null,primitives:null,nullReason:'ZERO_DENOMINATOR',qualityReason:'SOURCE_QUALITY_UNAVAILABLE'}]} as TraceNativeSeries;
  const chart=nativeChartSeries(source);expect(chart.points[0].value).toBeNull();expect(chart.points[0].coverage).toBeNull();expect(chart.points[0].denominator).toBeNull();
  expect(chart.points[0].quality).toBe('INSUFFICIENT_EVIDENCE');expect(chart.requestedFrom).toBe(source.from);expect(source.points[0].primitives).toBeNull();
});
