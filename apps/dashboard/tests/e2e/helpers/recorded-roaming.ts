import type { Page } from '@playwright/test';
const countries = ['RO','TR','IT','DE','FR','UA','ES','GB','US','BG'];
export async function recordedRoaming(page: Page) {
  const state = { unavailable: false, reads: 0, queries: [] as Record<string,string>[], baseline: null as number|null, fluctuate:false };
  await page.route('**/api/v2/datasets', route => route.fulfill({ json: [{
    datasetId: 'recorded-roaming', from: '2026-10-06T00:00:00Z', to: '2026-10-08T00:00:00Z',
    catalogueVersion: 'recorded-partners', synthetic: true, archiveReady: true,
  }] }));
  await page.route('**/api/v2/inventory/partners?**', route => route.fulfill({ json: {
    catalogueVersion: 'recorded-partners', partners: countries.flatMap(country => ['out','in'].map(direction => ({
      id: `${country}-DEMO-01`, country, direction, active: true, synthetic: true,
    }))),
  } }));
  await page.route('**/api/v2/metrics/series?**', route => {
    state.reads++;
    if (state.unavailable) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } });
    const params = Object.fromEntries(new URL(route.request().url()).searchParams);
    state.queries.push(params);
    const step = ({'5m':300000,'15m':900000,'1h':3600000,'6h':21600000})[params.resolution] ?? 3600000;
    const start = Date.parse(params.from), end = Date.parse(params.to);
    return route.fulfill({ json: { ...params, requestedFrom: params.from, requestedTo: params.to,
      effectiveFrom:params.from,effectiveTo:params.to,partialStart:false,partialEnd:false,
      unit:params.metric.includes('.active')?'COUNT':'PERCENT',aggregation:params.metric.includes('.active')?'SNAPSHOT_LAST':'RATIO_OF_SUMS',
      synthetic: true, sourceResolutionSeconds: 300, points: Array.from({ length: Math.ceil((end-start)/step) }, (_, i) => ({
        from: new Date(start + i*step).toISOString(), to: new Date(Math.min(end, start+(i+1)*step)).toISOString(),
        value: params.metric.includes('active') ? 100 : state.fluctuate ? 98 + (i%3)/4 : 98, baseline:params.metric.includes('active')?null:state.baseline, baselineVersion:state.baseline===null?null:'controlled-static-v1', quality: 'INSUFFICIENT_EVIDENCE', coverage: 1,
        numerator: state.fluctuate ? 9800+(i%3)*25 : 98, denominator: state.fluctuate ? 10000 : 100,
        snapshotAt:new Date(Math.min(end,start+(i+1)*step)-300000).toISOString(),
      })),
    } });
  });
  return state;
}
