const cp=require('node:child_process'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..');
const compose=['compose','-p','telecom-g4-pr77','--env-file',path.join(__dirname,'private.env'),'-f',path.join(__dirname,'compose.yaml')];
function command(args,input){const r=cp.spawnSync('docker',args,{cwd:root,input,encoding:'utf8',timeout:120000});if(r.status!==0)throw Error('Isolated docker command failed ('+args.slice(0,3).join(' ')+'); sensitive output omitted.');return r.stdout.trim();}
function dc(args,input){return command([...compose,...args],input);}
function admin(args,input){return dc(['exec','-T','keycloak','bash','-ec',`set -eu
config=$(mktemp)
trap 'rm -f "$config"' EXIT
/opt/keycloak/bin/kcadm.sh config credentials --config "$config" --server http://localhost:8080/auth --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
/opt/keycloak/bin/kcadm.sh "$@" --config "$config"`, '--',...args],input);}
function sql(db,q){return dc(['exec','-T','postgres','bash','-ec','exec psql -X -q -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"','--',db],q);}
function user(role){const username='g4-'+crypto.randomBytes(6).toString('hex'),password=crypto.randomBytes(24).toString('base64url')+'!Aa1';
const id=admin(['create','users','-r','telecom','-i','-f','/dev/stdin'],JSON.stringify({username,enabled:true,firstName:'G4',lastName:role,email:username+'@example.invalid',emailVerified:true,credentials:[{type:'password',value:password,temporary:false}]})).replaceAll('"','');
if(!/^[0-9a-f-]{36}$/.test(id))throw Error('Invalid temporary identity.');
admin(['add-roles','-r','telecom','--uid',id,'--rolename',role]);
sql('incidents_db',`INSERT INTO app.analysts(id,issuer,subject,display_name,enabled) VALUES('${crypto.randomUUID()}','http://telecom.test:8080/auth/realms/telecom','${id}','G4 ${role}',true);`);
return {id,username,password,role};}
function cleanup(u){admin(['delete','users/'+u.id,'-r','telecom']);sql('incidents_db',`UPDATE app.analysts SET enabled=false WHERE subject='${u.id}' AND display_name='G4 ${u.role}';`);}
module.exports={root,compose,dc,sql,admin,user,cleanup};
