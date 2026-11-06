// Operate only the explicitly identified, ownership-labelled local demo.
const fs=require('node:fs');
const path=require('node:path');
const {execFileSync}=require('node:child_process');
const {assertOwned,composeArgs}=require('./pr77-connected-verification.cjs');

async function main(){
 const [action,filename]=process.argv.slice(2);
 if(!['status','start','stop','open'].includes(action)||!filename||!path.isAbsolute(filename)||process.argv.length!==4)
  throw Error('Use status|start|stop|open followed by the private absolute stack-context.json path.');
 const file=fs.realpathSync(filename),directory=path.dirname(file);
 const c=JSON.parse(fs.readFileSync(file,'utf8'));
 const marker=JSON.parse(fs.readFileSync(path.join(directory,'ownership.json'),'utf8'));
 if(c.verificationMode!=='volte-sms-demo'||!/^pr77-[a-z0-9-]+$/.test(c.projectName)
  ||c.ownerLabelName!=='io.telecom.pr77.owner'||marker.directory!==directory
  ||marker.owner!==c.ownerLabelValue||marker.project!==c.projectName||fs.realpathSync(c.privateDir)!==directory)
  throw Error('Demo directory ownership mismatch.');
 const docker=args=>execFileSync('docker',args,{encoding:'utf8',windowsHide:true,timeout:180_000,stdio:['pipe','pipe','pipe']}).trim();
 const ids=docker(composeArgs(c,['ps','--all','--quiet'])).split(/\r?\n/).filter(Boolean);
 if(!ids.length||ids.some(id=>! /^[0-9a-f]{12,64}$/.test(id)))throw Error('Expected existing owned demo containers.');
 const containers=JSON.parse(docker(['inspect',...ids]));
 containers.forEach(item=>assertOwned(item.Config.Labels,c));
 if(action==='status'){
  console.log(JSON.stringify({sourceSha:c.sourceSha,baseURL:c.baseURL,services:containers.map(x=>({service:x.Config.Labels['com.docker.compose.service'],status:x.State.Status,health:x.State.Health?.Status}))},null,2));return;
 }
 if(action==='start'||action==='stop'){
  docker(composeArgs(c,action==='start'?['start','--wait','--wait-timeout','180']:['stop']));
  console.log(`Owned demo ${action==='start'?'started':'stopped'}; data retained.`);return;
 }
 const {chromium}=require(path.resolve(__dirname,'../apps/dashboard/node_modules/@playwright/test'));
 const browser=await chromium.launch({headless:false,args:['--host-resolver-rules=MAP telecom.test 127.0.0.1','--no-proxy-server']});
 try{
  const page=await browser.newPage({viewport:{width:1366,height:900}});
  await page.goto(c.baseURL+'/login');await page.getByRole('button',{name:'Continue to sign in',exact:true}).click();
  await page.getByLabel(/username|email/i).fill(c.supervisor.username);
  await page.getByLabel('Password',{exact:true}).fill(c.supervisor.password);
  await page.getByRole('button',{name:/sign in/i}).click();await page.waitForURL('**/dashboard');
  await page.getByLabel('Region',{exact:true}).fill('Orhei');
  console.log('Opened the verified local demo; credentials remain private. Close the browser to finish.');
  await new Promise(resolve=>browser.on('disconnected',resolve));
 }catch{await browser.close();throw Error('Demo browser sign-in failed; sensitive diagnostics omitted.');}
}
main().catch(error=>{console.error(error.message.startsWith('Command failed')?'Owned demo operation failed; sensitive diagnostics omitted.':error.message);process.exitCode=1;});
