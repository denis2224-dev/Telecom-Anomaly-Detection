/** Keep the operator's period; an off-screen incident gets context at the same scale. */
export function incidentDisplayRange(start: string, end: string, current?: {from:string;to:string} | null) {
  const first=Date.parse(start), last=Date.parse(end);
  if (!Number.isFinite(first) || !Number.isFinite(last) || last<=first) throw new Error('Incident interval unavailable.');
  const from=Date.parse(current?.from ?? ''), to=Date.parse(current?.to ?? '');
  const valid=Number.isFinite(from)&&Number.isFinite(to)&&to>from&&to-from<=30*86_400_000;
  if(valid && first>=from && last<=to)return {from:new Date(from).toISOString(),to:new Date(to).toISOString()};
  const span=Math.max(last-first,valid?to-from:86_400_000);
  const begin=first-(span-(last-first))/2;
  return {from:new Date(begin).toISOString(),to:new Date(begin+span).toISOString()};
}
