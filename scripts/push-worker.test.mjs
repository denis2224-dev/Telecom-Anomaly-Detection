import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const script=readFileSync(new URL('../apps/dashboard/src/push-worker.js',import.meta.url),'utf8');
function worker(windows=[]) {
  const handlers={},shown=[],opened=[];
  const self={location:{origin:'https://telecom.example'},addEventListener:(name,fn)=>handlers[name]=fn,
    registration:{showNotification:async(...args)=>shown.push(args)},clients:{matchAll:async()=>windows,openWindow:async url=>opened.push(url)}};
  vm.runInNewContext(script,{self,URL});
  return {shown,opened,async push(data){let pending;handlers.push({data:{json:()=>data},waitUntil:p=>pending=p});await pending;},
    async click(url){let pending;handlers.notificationclick({notification:{data:{url},close(){}},waitUntil:p=>pending=p});await pending;}};
}
test('lock-screen payload includes only generic title and severity',async()=>{
  const w=worker();await w.push({severity:'HIGH',scope:'private city',cause:'private cause',analyst:'private analyst',url:'/incidents/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'});
  assert.equal(w.shown[0][0],'Trace Link alert');assert.equal(w.shown[0][1].body,'HIGH');
  assert.doesNotMatch(JSON.stringify(w.shown),/private/);
});
test('visible controlled windows refresh instead of displaying another notification',async()=>{
  const messages=[],w=worker([{visibilityState:'visible',postMessage:message=>messages.push(message)}]);await w.push({severity:'HIGH'});
  assert.equal(w.shown.length,0);assert.equal(messages.length,1);assert.equal(messages[0].type,'refresh-notifications');
});
test('click-through rejects external, malformed and credential-bearing targets',async()=>{
  const w=worker();for(const url of ['https://evil.example/incidents/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','/incidents/not-an-id','/incidents/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa?token=secret','https://user:secret@telecom.example/incidents/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'])await w.click(url);
  assert.equal(w.opened.length,0);await w.click('/incidents/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');assert.equal(w.opened.length,1);
});
