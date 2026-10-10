const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const yaml = require('../../apps/dashboard/node_modules/yaml');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const sha = cp.execFileSync('git', ['rev-parse', 'HEAD'], {cwd:root,encoding:'utf8'}).trim();
const envPath = path.join(__dirname,'private.env');
if(fs.existsSync(envPath)) throw Error('An isolated environment already exists; do not overwrite credentials.');
const env = {};
for(const name of ['POSTGRES_PASSWORD','PROCESSING_DB_PASSWORD','PROCESSING_MIGRATOR_PASSWORD','INCIDENT_DB_PASSWORD','INCIDENT_MIGRATOR_PASSWORD','KEYCLOAK_DB_PASSWORD','KEYCLOAK_ADMIN_PASSWORD','KEYCLOAK_CLIENT_SECRET']) env[name]=crypto.randomBytes(24).toString('hex');
Object.assign(env,{POSTGRES_PORT:'15432',KAFKA_HOST_PORT:'19094',GENERATOR_HOST_PORT:'18081',TELECOM_GEOGRAPHY_ENABLED:'true',TELECOM_GEOGRAPHY_EFFECTIVE_FROM:'2026-10-08T00:00:00Z',HISTORICAL_BOOTSTRAP_ENABLED:'false',CONTINUOUS_TELEMETRY_ENABLED:'true'});
fs.writeFileSync(envPath,Object.entries(env).map(([k,v])=>`${k}=${v}`).join('\n')+'\n',{mode:0o600});
const realm=JSON.parse(fs.readFileSync(path.join(root,'infra/keycloak/telecom-realm.json')));
realm.clients.find(c=>c.clientId==='telecom-web').secret=env.KEYCLOAK_CLIENT_SECRET;
fs.writeFileSync(path.join(__dirname,'private-realm.json'),JSON.stringify(realm),{mode:0o600});
let nginx=fs.readFileSync(path.join(root,'infra/nginx/default.conf'),'utf8').replace('http://host.docker.internal:8082','http://incident-service:8082');
fs.writeFileSync(path.join(__dirname,'nginx.conf'),nginx);
const compose=yaml.parse(fs.readFileSync(path.join(root,'compose.yaml'),'utf8'));
compose.name='telecom-g4-pr77';
for(const [name,s] of Object.entries(compose.services)){
 if(s.build){s.build.context=root;s.image=`telecom-g4-pr77-${name==='history-bootstrap'?'processor':name}:${sha.slice(0,7)}`;s.build.labels={'org.opencontainers.image.revision':sha};}
 if(s.volumes) s.volumes=s.volumes.map(v=>{
  if(typeof v==='object'){v.source=path.resolve(root,v.source);return v;}
  if(!v.startsWith('./'))return v;
  const [source,target,mode]=v.split(':');
  return {type:'bind',source:path.resolve(root,source),target,read_only:mode==='ro'};
 });
}
compose.services.keycloak.volumes[0].source=path.join(__dirname,'private-realm.json');
compose.services.proxy.volumes[0].source=path.join(__dirname,'nginx.conf');
compose.services['incident-service'].ports=['127.0.0.1:18082:8082'];
fs.writeFileSync(path.join(__dirname,'compose.yaml'),yaml.stringify(compose));
fs.writeFileSync(path.join(__dirname,'candidate.json'),JSON.stringify({sha,project:compose.name,publicOrigin:'http://telecom.test:8080',geographyEffectiveFrom:env.TELECOM_GEOGRAPHY_EFFECTIVE_FROM,historyBootstrap:false,proxyBackend:'incident-service:8082',ports:{postgres:15432,kafka:19094,generator:18081,incident:18082,proxy:8080}},null,2));
console.log('Isolated candidate prepared at '+sha+'; private credentials generated without changing the repository .env.');
