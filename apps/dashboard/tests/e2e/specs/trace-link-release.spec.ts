import {test,expect} from '../helpers/test';
import {randomBytes,createHash} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {command,admin,sql,root} from '../helpers/live-stack';
import {requireArchive,requireExactExport} from '../helpers/trace-release-acceptance';

test.skip(process.env.E2E_RELEASE_LIVE!=='1','Requires the local authenticated stack.');
test('requires populated persisted history through two authenticated context repetitions',async({browser},info)=>{
  test.setTimeout(180_000);
  const manifestBytes=readFileSync(resolve(root,'contracts/datasets/trace-link-md-30d-v1.manifest.json'));
  const manifest=JSON.parse(manifestBytes.toString()),manifestSha256=createHash('sha256').update(manifestBytes).digest('hex');
  const username='release-check-'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')+'!Aa1';
  let userId='';
  const report={revision:command('git',['rev-parse','HEAD']),schemaSha256:createHash('sha256').update(readFileSync(resolve(root,'contracts/openapi/incident-api.yaml'))).digest('hex'),browser:browser.version(),viewport:{width:1366,height:768},datasetId:manifest.dataset_id,manifestSha256,seed:42,historyRepetitions:[] as object[],populatedHistoryVerified:false,completeRehearsals:false,releaseGatesPassed:false};
  try{
    userId=admin(['create','users','-r','telecom','-i','-f','/dev/stdin'],JSON.stringify({username,enabled:true,firstName:'Release',lastName:'Verification',email:username+'@example.invalid',emailVerified:true,credentials:[{type:'password',value:password,temporary:false}]})).replaceAll('"','');
    if(!/^[0-9a-f-]{36}$/.test(userId))throw new Error('Invalid temporary actor identity.');
    admin(['add-roles','-r','telecom','--uid',userId,'--rolename','ANALYST']);command('./scripts/provision-analyst',['--username',username,'--display-name','Release verification']);
    for(const repetition of [1,2]){
      const context=await browser.newContext({baseURL:process.env.E2E_BASE_URL||'http://telecom.test:8080',viewport:report.viewport});const page=await context.newPage();
      try{
        await page.goto('/login');await page.getByRole('button',{name:'Continue to sign in',exact:true}).click();
        try{await page.getByLabel(/username|email/i).fill(username);await page.getByLabel('Password',{exact:true}).fill(password);await page.getByRole('button',{name:/sign in/i}).click();await expect.poll(()=>new URL(page.url()).pathname).toBe('/dashboard');}
        catch{throw new Error('Authenticated rehearsal login failed; credentials omitted.');}
        const datasets=await context.request.get('/api/v2/datasets');expect(datasets.status()).toBe(200);
        const dataset=(await datasets.json()).find((item:any)=>item.datasetId===manifest.dataset_id);
        requireArchive(dataset,manifest,manifestSha256);
        const version=new URLSearchParams({catalogueVersion:dataset.catalogueVersion});
        const inventory=await context.request.get('/api/v2/inventory/cities?'+version);expect(inventory.status()).toBe(200);
        const data=await inventory.json();expect(data.catalogueVersion).toBe(dataset.catalogueVersion);
        expect(data.cities.filter((city:any)=>city.active).map((city:any)=>city.id).sort()).toEqual(['bal','cah','cau','chi','com','edi','hin','orh','sor','ung']);
        const linkResponse=await context.request.get('/api/v2/inventory/links?'+version);expect(linkResponse.status()).toBe(200);
        const links=await linkResponse.json();expect(links.catalogueVersion).toBe(dataset.catalogueVersion);
        const expectedLinks=JSON.parse(readFileSync(resolve(root,'contracts/topology/trace-link-links-v1.json'),'utf8')).links.map((link:any)=>link.linkId).sort();
        expect(links.links.filter((link:any)=>link.active).map((link:any)=>link.id).sort()).toEqual(expectedLinks);
        const partnerResponse=await context.request.get('/api/v2/inventory/partners?'+version);expect(partnerResponse.status()).toBe(200);
        const partners=await partnerResponse.json();expect(partners.catalogueVersion).toBe(dataset.catalogueVersion);
        const partner=partners.partners.find((item:any)=>item.active&&item.country==='RO'&&item.direction==='out');expect(partner).toBeTruthy();
        const checks:object[]=[];
        const verify=async(domain:string,metric:string,scopeType:string,scopeId:string,hours:number,resolution:string)=>{
          const query={datasetId:manifest.dataset_id,domain,metric,scopeType,scopeId,from:new Date(Date.parse(dataset.to)-hours*3600000).toISOString(),to:dataset.to,resolution};
          const parameters=new URLSearchParams(query),response=await context.request.get('/api/v2/metrics/series?'+parameters);expect(response.status()).toBe(200);
          const series=await response.json();
          for(const key of ['datasetId','domain','metric','scopeType','scopeId','resolution'])expect(series[key]).toBe(query[key as keyof typeof query]);
          expect(Date.parse(series.requestedFrom)).toBe(Date.parse(query.from));expect(Date.parse(series.requestedTo)).toBe(Date.parse(query.to));
          const seconds=({'5m':300,'15m':900,'1h':3600,'6h':21600} as Record<string,number>)[resolution];
          const expectedFrom=Math.floor(Date.parse(query.from)/(seconds*1000))*seconds*1000,expectedTo=Math.ceil(Date.parse(query.to)/(seconds*1000))*seconds*1000;
          expect(series.points).toHaveLength((expectedTo-expectedFrom)/(seconds*1000));
          for(const [index,point] of series.points.entries()){expect(Date.parse(point.from)).toBe(expectedFrom+index*seconds*1000);expect(Date.parse(point.to)).toBe(expectedFrom+(index+1)*seconds*1000);}
          expect(series.points.some((point:any)=>point.value!==null)).toBe(true);
          const exported=await context.request.get('/api/v2/exports/metrics.csv?'+parameters);expect(exported.status()).toBe(200);
          const csv=await exported.text();requireExactExport(series,csv);
          checks.push({query,buckets:series.points.length,jsonSha256:createHash('sha256').update(JSON.stringify(series)).digest('hex'),csvSha256:createHash('sha256').update(csv).digest('hex')});
        };
        for(const hours of [24,168,720])await verify('volte','volte.setup.success','CITY','chi',hours,'5m');
        for(const resolution of ['15m','1h','6h'])await verify('volte','volte.setup.success','CITY','chi',24,resolution);
        await verify('transport','transport.traffic','LINK_DIRECTION','chi-hin:a-to-b',24,'15m');
        await verify('roaming','roaming.registration.success','PARTNER_DIRECTION',partner.id+':out',24,'1h');
        await verify('sms','sms.queue','CITY','chi',24,'15m');
        await verify('mobile-data','mobileData.activation.success','CITY','chi',24,'1h');
        await verify('mobile-data','mobileData.dns.success','CITY','chi',24,'1h');
        const history=await context.request.get('/api/notifications?page=0&size=20');expect(history.status()).toBe(200);const notifications=await history.json();expect(notifications.items.length).toBeLessThanOrEqual(20);
        const push=await context.request.get('/api/notifications/push/config');expect(push.status()).toBe(200);expect((await push.json()).enabled).toBe(false);
        const episodes=await context.request.get('/api/v2/trace-link/incidents?'+new URLSearchParams({datasetId:dataset.datasetId,from:new Date(Date.parse(dataset.to)-86400000).toISOString(),to:dataset.to,limit:'50',offset:'0'}));expect(episodes.status()).toBe(200);expect((await episodes.json()).items.length).toBeLessThanOrEqual(50);
        await page.goto('/trace-link?datasetId=trace-link-md-30d-v1&from=2026-10-09T09%3A00%3A00Z&to=2026-10-10T09%3A00%3A00Z&cityId=chi');
        await expect(page.getByRole('heading',{name:'Trace Link history',exact:true})).toBeVisible();
        await page.getByRole('combobox',{name:'Resolution',exact:true}).selectOption('1h');await page.getByRole('button',{name:'6h',exact:true}).click();await expect(page.getByRole('combobox',{name:'Resolution',exact:true})).toHaveValue('1h');
        await expect(page.getByRole('region',{name:'Approved topology'})).toContainText('10 active cities · 12 active links');
        await expect(page.getByRole('region',{name:'Persisted metric chart'})).toBeVisible();
        await page.getByRole('button',{name:'chi-hin a-to-b',exact:true}).click();await page.getByRole('button',{name:'Transport',exact:true}).click();
        await expect(page.getByRole('combobox',{name:'Metric',exact:true})).toBeVisible();
        await page.getByRole('combobox',{name:'Metric',exact:true}).selectOption('transport.traffic');await expect(page.getByRole('region',{name:'Persisted metric chart'})).toContainText('transport.traffic');
        await page.getByRole('button',{name:'Roaming',exact:true}).click();await page.getByRole('combobox',{name:'Country',exact:true}).selectOption('RO');await page.getByRole('combobox',{name:'Roaming direction',exact:true}).selectOption('out');
        await page.getByRole('combobox',{name:'Series partner and direction',exact:true}).selectOption(partner.id+':out');await page.getByRole('combobox',{name:'Metric',exact:true}).selectOption('roaming.registration.success');
        await expect(page.getByRole('region',{name:'Persisted metric chart'})).toContainText('roaming.registration.success');
        await page.getByRole('button',{name:'LTE mobile data',exact:true}).click();await page.getByRole('combobox',{name:'Metric',exact:true}).selectOption('mobileData.dns.success');await expect(page.getByRole('region',{name:'Persisted metric chart'})).toContainText('mobileData.dns.success');
        const expand=page.getByRole('button',{name:'Expand metric',exact:true});await expand.click();
        await expect(page.getByRole('dialog',{name:'Expanded persisted metric'})).toContainText('mobileData.dns.success');await page.keyboard.press('Escape');await expect(expand).toBeFocused();
        const selection=new URL(page.url()).searchParams;
        const uiQuery=new URLSearchParams({datasetId:dataset.datasetId,domain:'mobile-data',metric:'mobileData.dns.success',scopeType:'CITY',scopeId:'chi',from:selection.get('from')!,to:selection.get('to')!,resolution:selection.get('resolution')!});
        const uiResponse=await context.request.get('/api/v2/metrics/series?'+uiQuery);expect(uiResponse.status()).toBe(200);
        const downloadEvent=page.waitForEvent('download');await page.getByRole('button',{name:'Download exact server CSV',exact:true}).click();
        const download=await downloadEvent,uiCsv=readFileSync((await download.path())!,'utf8');requireExactExport(await uiResponse.json(),uiCsv);
        await page.screenshot({path:info.outputPath(`authenticated-history-${repetition}.png`),fullPage:true,mask:[page.locator('.identity')]});
        report.historyRepetitions.push({repetition,inventoryStatus:inventory.status(),notificationStatus:history.status(),pushStatus:push.status(),actor:'same temporary analyst',contextVerified:true,checks,uiCsvSha256:createHash('sha256').update(uiCsv).digest('hex')});
        await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect.poll(()=>new URL(page.url()).pathname).toBe('/signed-out');
      }finally{await context.close();}
    }
    expect(command('git',['rev-parse','HEAD'])).toBe(report.revision);
    expect(createHash('sha256').update(readFileSync(resolve(root,'contracts/openapi/incident-api.yaml'))).digest('hex')).toBe(report.schemaSha256);
    const repetitions=report.historyRepetitions as {checks:object[];uiCsvSha256:string}[];
    expect(repetitions[1].checks).toEqual(repetitions[0].checks);expect(repetitions[1].uiCsvSha256).toBe(repetitions[0].uiCsvSha256);
    report.populatedHistoryVerified=true;
  }finally{
    if(/^[0-9a-f-]{36}$/.test(userId)){admin(['delete',`users/${userId}`,'-r','telecom']);sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Release verification';`);}
    writeFileSync(info.outputPath('authenticated-history.json'),JSON.stringify(report,null,2));
  }
});
