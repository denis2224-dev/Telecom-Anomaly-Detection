import type { components } from '../../core/api/schema';
import { probableCause } from '../../shared/metric-presentation';

type Incident = components['schemas']['Incident'];
type NativeIncident = components['schemas']['TraceLinkIncidentView'];
export interface AnalystIncident {
  id:string; source:'legacy'|'native'; dataset:string; domain:string; scope:string;
  topology:string; cities:string[]; nodes:string[]; from:string; to:string;
  severity:string; technical:string; workflow:string|null; cause:string;
}
const record=(value:unknown):Record<string,unknown>=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:{};
const strings=(value:unknown):string[]=>Array.isArray(value)?value.filter((entry):entry is string=>typeof entry==='string'&&!!entry):[];
export function legacyAnalystIncident(item:Incident):AnalystIncident {
  const location=item.location,reportedCause=probableCause(item.latestDetection);
  const cause=/^cause undetermined|^possible cause:/i.test(reportedCause)?reportedCause:'Possible cause: '+reportedCause.replace(/^probable\s+/i,'');
  return {id:item.id,source:'legacy',dataset:'',domain:item.service,scope:item.scopeId,
    topology:location?.topologyVersion??'',
    cities:location?.cityId&&!location.nullReason?[location.cityId]:[],nodes:location?.dependencyNodeIds??[],
    from:item.firstObservedAt,to:Date.parse(item.lastObservedAt)>Date.parse(item.latestDetection.windowEnd)?item.lastObservedAt:item.latestDetection.windowEnd,
    severity:item.severity,technical:item.technicalState,workflow:item.status,cause};
}
export function nativeAnalystIncident(item:NativeIncident,workflow:string|null=null):AnalystIncident {
  const context=item.latestDetection.context,scope=record(context['scope']),cause=item.latestDetection.cause;
  return {id:item.episodeId,source:'native',dataset:String(context['datasetId']??''),domain:String(context['domain']??'').toUpperCase(),scope:String(scope['id']??''),
    topology:String(context['topologyVersion']??''),cities:strings(scope['cityIds']),nodes:[],from:item.firstObservedAt,to:item.latestWindowEnd,
    severity:item.severity,technical:item.technicalState,workflow,
    cause:cause['status']==='HYPOTHESIS'&&typeof cause['probableCause']==='string'?'Possible cause: '+cause['probableCause']:'Cause undetermined'};
}
export function relatedReason(selected:AnalystIncident,other:AnalystIncident):string|null {
  if(selected.id===other.id||selected.source!==other.source||selected.dataset!==other.dataset)return null;
  const start=Math.max(Date.parse(selected.from),Date.parse(other.from)),end=Math.min(Date.parse(selected.to),Date.parse(other.to));
  if(!Number.isFinite(start)||!Number.isFinite(end)||end<=start)return null;
  if(selected.scope&&selected.scope===other.scope&&selected.domain===other.domain)return 'Same scope · overlapping time';
  // Shared locations and dependencies are comparable only under the same recorded topology.
  if(!selected.topology||selected.topology!==other.topology)return null;
  if(selected.cities.some(city=>other.cities.includes(city)))return 'Shared city · overlapping time';
  if(selected.nodes.some(node=>other.nodes.includes(node)))return 'Shared dependency · overlapping time';
  return null;
}
export function suggestedChecks(item:AnalystIncident):string[] {
  if(item.technical==='UNKNOWN')return ['Check missing measurements and source timestamps.','Restore evidence coverage before assessing recovery.'];
  if(item.technical==='RECOVERED')return ['Compare the recovered interval with the recorded baseline.',item.workflow==='RESOLVED'?'Review the resolution note before closing the handover.':'Verify sustained recovery, then record analyst resolution.'];
  const checks:Record<string,string[]>={
    VOLTE:['Compare setup success and attempts with the baseline.','Inspect SIP 503 responses and recorded IMS/dependency evidence.'],
    SMS:['Compare delivery success, delay and queue depth.','Check SMSC queue evidence and whether the backlog is draining.'],
    ROAMING:['Confirm the affected partner, country and roaming direction.','Compare location update, data, voice and SMS success for that partner.'],
    TRANSPORT:['Inspect utilization, loss and interface evidence in the affected direction.','Compare both directions and incidents in the connected cities.'],
    'MOBILE-DATA':['Compare session setup and DNS success with the baseline.','Inspect recorded transport and service dependency evidence.'],
  };
  return checks[item.domain.replaceAll('_','-')]??['Compare the affected scope with its recorded baseline.','Inspect the source evidence before recording a cause.'];
}
export function incidentRoute(item:AnalystIncident):string[] {return item.source==='native'?['/trace-link/incidents',item.id]:['/incidents',item.id];}
export function handoverText(items:AnalystIncident[],scope:string,from:string,to:string,coverage:string,at:string):string {
  const unresolved=items.filter(item=>item.workflow!=='RESOLVED');
  const time=(value:string)=>Number.isFinite(Date.parse(value))?new Date(value).toISOString().slice(0,19).replace('T',' ')+' UTC':value;
  return [`Shift handover · ${time(at)}`,scope,`Chart / context period (UTC): ${time(from)} — ${time(to)}`,coverage,
    `${items.filter(item=>item.workflow==='OPEN').length} awaiting acknowledgement · ${items.filter(item=>item.workflow==='INVESTIGATING').length} investigating · ${items.filter(item=>item.technical==='RECOVERED').length} technical recoveries`,
    ...(items.some(item=>item.workflow===null)?[`${items.filter(item=>item.workflow===null).length} workflow states unavailable`]:[]),
    ...unresolved.flatMap(item=>['',`${item.severity} · ${item.domain} · ${item.scope}`,`${item.technical} · ${item.workflow??'Workflow unavailable'}`,`Since ${time(item.from)} · latest evidence ${time(item.to)}`,item.cause,`Next check (suggested): ${suggestedChecks(item).join(' ')}`,`Incident: ${item.id}`]),
    ...items.filter(item=>item.workflow==='RESOLVED').map(item=>`\nResolved · ${item.domain} · ${item.scope} · ${item.id}`),
    ...(items.length?[]:['No incidents in this snapshot.'])].join('\n');
}
