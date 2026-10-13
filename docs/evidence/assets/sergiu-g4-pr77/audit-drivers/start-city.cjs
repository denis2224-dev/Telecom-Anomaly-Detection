const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process');
const lib=require('./lib.cjs');
const u=lib.user('SUPERVISOR');
fs.writeFileSync(path.join(__dirname,'private-city-user.json'),JSON.stringify(u),{mode:0o600});
fs.writeFileSync(path.join(__dirname,'private-city.env'),`DAY3_USERNAME=${u.username}\nDAY3_PASSWORD=${u.password}\nDAY3_BASE_URL=http://telecom.test:8080\n`,{mode:0o600});
const child=cp.spawn(process.execPath,['scripts/day3-geographic-live.cjs',path.join(__dirname,'private-city.env'),'tmp/g4-pr77/city'],{cwd:lib.root,stdio:'inherit'});
child.on('close',code=>{try{lib.cleanup(u);}catch{console.error('Temporary city identity cleanup failed; retry using the private local identity record.');}process.exitCode=code??1;});
