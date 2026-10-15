import {test,expect} from '../helpers/test';
import {randomBytes,createHash} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {command,admin,sql,root} from '../helpers/live-stack';

test.skip(process.env.E2E_RELEASE_LIVE!=='1','Requires the local authenticated stack.');
test('repeats the same release context through actual sign-in and explicit backend availability',async({browser},info)=>{
  const username='release-check-'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')+'!Aa1';
  let userId='';
  const report={revision:command('git',['rev-parse','HEAD']),schemaSha256:createHash('sha256').update(readFileSync(resolve(root,'contracts/openapi/incident-api.yaml'))).digest('hex'),browser:browser.version(),viewport:{width:1366,height:768},datasetId:'trace-link-md-30d-v1',seed:42,rehearsals:[] as object[],populatedHistoryVerified:false};
  try{
    userId=admin(['create','users','-r','telecom','-i','-f','/dev/stdin'],JSON.stringify({username,enabled:true,firstName:'Release',lastName:'Verification',email:username+'@example.invalid',emailVerified:true,credentials:[{type:'password',value:password,temporary:false}]})).replaceAll('"','');
    if(!/^[0-9a-f-]{36}$/.test(userId))throw new Error('Invalid temporary actor identity.');
    admin(['add-roles','-r','telecom','--uid',userId,'--rolename','ANALYST']);command('./scripts/provision-analyst',['--username',username,'--display-name','Release verification']);
    for(const rehearsal of [1,2]){
      const context=await browser.newContext({baseURL:'http://telecom.test:8080',viewport:report.viewport});const page=await context.newPage();
      try{
        await page.goto('/login');await page.getByRole('button',{name:'Continue to sign in',exact:true}).click();
        try{await page.getByLabel(/username|email/i).fill(username);await page.getByLabel('Password',{exact:true}).fill(password);await page.getByRole('button',{name:/sign in/i}).click();await expect.poll(()=>new URL(page.url()).pathname).toBe('/dashboard');}
        catch{throw new Error('Authenticated rehearsal login failed; credentials omitted.');}
        const inventory=await context.request.get('/api/v2/inventory/cities');expect([200,503]).toContain(inventory.status());
        if(inventory.status()===200){const data=await inventory.json();expect(data.cities.filter((city:any)=>city.active)).toHaveLength(10);const links=await(await context.request.get('/api/v2/inventory/links')).json();expect(links.links.filter((link:any)=>link.active)).toHaveLength(12);}
        const history=await context.request.get('/api/notifications?page=0&size=20');expect(history.status()).toBe(200);const notifications=await history.json();expect(notifications.items.length).toBeLessThanOrEqual(20);
        const push=await context.request.get('/api/notifications/push/config');expect([200,404]).toContain(push.status());if(push.status()===200)expect((await push.json()).enabled).toBe(false);
        const native=await context.request.get('/api/v2/trace-domain-measurements');expect(native.status()).toBe(404);
        await page.goto('/trace-link?datasetId=trace-link-md-30d-v1&from=2026-10-09T09%3A00%3A00Z&to=2026-10-10T09%3A00%3A00Z&cityId=chi');
        await expect(page.getByRole('heading',{name:'Trace Link history',exact:true})).toBeVisible();
        await page.getByRole('combobox',{name:'Resolution',exact:true}).selectOption('1h');await page.getByRole('button',{name:'6h',exact:true}).click();await expect(page.getByRole('combobox',{name:'Resolution',exact:true})).toHaveValue('1h');
        if(inventory.status()===503)await expect(page.getByRole('button',{name:'Retry inventory',exact:true})).toBeVisible();
        await page.screenshot({path:info.outputPath(`authenticated-rehearsal-${rehearsal}.png`),fullPage:true,mask:[page.locator('.identity')]});
        report.rehearsals.push({rehearsal,inventoryStatus:inventory.status(),notificationStatus:history.status(),pushStatus:push.status(),nativeStatus:native.status(),actor:'same temporary analyst',contextVerified:true});
        await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect.poll(()=>new URL(page.url()).pathname).toBe('/signed-out');
      }finally{await context.close();}
    }
  }finally{
    if(/^[0-9a-f-]{36}$/.test(userId)){admin(['delete',`users/${userId}`,'-r','telecom']);sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Release verification';`);}
    writeFileSync(info.outputPath('authenticated-rehearsals.json'),JSON.stringify(report,null,2));
  }
});
