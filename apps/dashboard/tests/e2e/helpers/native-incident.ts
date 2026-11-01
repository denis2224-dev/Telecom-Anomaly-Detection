export function nativeIncidentFixture(){
  const episodeId='a'.repeat(64),scope={id:'RO-DEMO-01:in',partnerId:'RO-DEMO-01',directionId:'in',procedure:'REGISTRATION',cityIds:[],type:'PARTNER_DIRECTION'};
  const record=(sequence:number,phase:string,actual:number)=>({schemaVersion:'trace-link-detection-v1',synthetic:true,committedAt:'2026-10-07T01:10:00Z',
    lifecycle:{schemaVersion:'trace-link-lifecycle-v1',episodeId,detectionId:String(sequence),sequence,phase,technicalState:phase==='RECOVERY'?'RECOVERED':'ONGOING',severity:'HIGH',window:{from:sequence===1?'2026-10-07T01:00:00Z':'2026-10-07T01:05:00Z',to:sequence===1?'2026-10-07T01:05:00Z':'2026-10-07T01:10:00Z'},evaluation:{}},
    context:{datasetId:'recorded-roaming',domain:'ROAMING',scope,evidence:[{metricId:'roaming.registration.success',actual,baseline:99,threshold:95,unit:'PERCENT',
      primitives:{numerator:actual,denominator:100},scope,quality:{state:'VALID',reason:null},window:{from:'2026-10-07T01:00:00Z',to:'2026-10-07T01:05:00Z'},
      provenance:{datasetId:'recorded-roaming',synthetic:true,origin:'CONTROLLED_TEST',sourceChecksum:'controlled-source-sha'}}]},cause:{status:'UNDETERMINED'},causeEvidence:{}});
  const records=[record(1,'OPEN',80),record(2,'RECOVERY',99)];
  return {records,incident:{episodeId,firstObservedAt:'2026-10-07T01:00:00Z',detectedAt:'2026-10-07T01:05:00Z',latestWindowEnd:'2026-10-07T01:10:00Z',latestSequence:2,technicalState:'RECOVERED',severity:'HIGH',latestDetection:records[1]}};
}
