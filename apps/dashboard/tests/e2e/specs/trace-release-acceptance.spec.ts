import {test, expect} from '../helpers/test';
import {requireArchive, csvRecords, requireExactExport} from '../helpers/trace-release-acceptance';

test('release acceptance rejects absent, incomplete and substituted archives', () => {
  const manifest = {dataset_id:'pinned', from:'2026-09-10T09:00:00Z', to_exclusive:'2026-10-10T09:00:00Z'};
  const dataset = {datasetId:'pinned', from:manifest.from, to:manifest.to_exclusive, archiveReady:true, synthetic:true, manifestSha256:'a'.repeat(64), catalogueVersion:'recorded'};
  expect(() => requireArchive(dataset, manifest, dataset.manifestSha256)).not.toThrow();
  for (const invalid of [null, {...dataset, archiveReady:false}, {...dataset, manifestSha256:'b'.repeat(64)}, {...dataset, to:'2026-09-10T09:20:00Z'}, {...dataset, catalogueVersion:null}])
    expect(() => requireArchive(invalid, manifest, dataset.manifestSha256)).toThrow();
});

test('exact export acceptance reads quoted JSON, empty fields and multiline qualifications', () => {
  expect(csvRecords('reason,bins,value\r\n"Missing, then \"\"late\"\"\nsource","[1,2]",\r\n'))
    .toEqual([{reason:'Missing, then "late"\nsource',bins:'[1,2]',value:''}]);
  for (const invalid of ['a,a\r\n1,2\r\n', 'a,b\r\n1\r\n', 'a\r\n"unfinished', 'a\r\n"closed"extra'])
    expect(() => csvRecords(invalid)).toThrow();
});

test('exact export acceptance rejects altered values, qualification, scope and provenance', () => {
  const series: any = {datasetId:'pinned',domain:'volte',metric:'volte.setup.success',scopeType:'CITY',scopeId:'chi',resolution:'5m',unit:'PERCENT',aggregation:'RATIO_OF_SUMS',policyVersion:'v1',topologyVersion:'v1',sourceResolutionSeconds:300,synthetic:true,requestedFrom:'2026-10-10T08:55:00Z',requestedTo:'2026-10-10T09:00:00Z',points:[{from:'2026-10-10T08:55:00Z',to:'2026-10-10T09:00:00Z',value:0,coverage:1,quality:'PARTIAL',numerator:0,denominator:10,sumValue:null,sampleCount:null,expectedSlots:1,receivedSlots:1,nullReason:null,qualityReason:'Source quality unavailable',sourceQuality:null,sourceCoverage:null,snapshotAt:null,sourceAgeSeconds:null,histogramCounts:[],histogramEdges:[],provenance:[{sourceRowSha256:'pinned'}]}]};
  const fields = {...series,...series.points[0]}; delete fields.points;
  const encode = (value: any) => value == null ? '' : '"' + (typeof value === 'object' ? JSON.stringify(value) : String(value)).replaceAll('"','""') + '"';
  const csv = Object.keys(fields).join(',')+'\r\n'+Object.values(fields).map(encode).join(',')+'\r\n';
  expect(() => requireExactExport(series,csv)).not.toThrow();
  for (const changed of [{...series,scopeId:'bal'}, {...series,points:[{...series.points[0],value:null}]}, {...series,points:[{...series.points[0],quality:'VALID'}]}, {...series,points:[{...series.points[0],provenance:[]}]}, {...series,points:[]}])
    expect(() => requireExactExport(changed,csv)).toThrow();
});
