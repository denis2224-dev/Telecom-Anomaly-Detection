import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subject } from 'rxjs';
import { TelecomClient, type NotificationItem } from '../../core/api/telecom-client';
import { IncidentStream } from '../../core/state/incident-stream';
import { SessionStore } from '../login-and-session/session.store';
import { ToastService } from '../../shared/toast.service';
import { NotificationStore } from './notification.store';

const item: NotificationItem = {id:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',incidentId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  kind:'OPEN',severity:'HIGH',service:'VOLTE',scopeId:'hin',summary:'Recorded symptom',occurredAt:'2026-10-12T09:00:00Z',readAt:null,
  causeStatus:'UNDETERMINED',probableCause:null,causeConfidence:null,incidentUrl:'/incidents/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'};
const page = (items: NotificationItem[]) => ({items,total:items.length,unreadCount:items.filter(item=>!item.readAt).length,page:0,size:20});
function setup(api: object) {
  vi.stubGlobal('BroadcastChannel',undefined);
  const ended=new Subject<void>(),phase=signal('authenticated'); let revision=1,refresh=()=>{};
  const session={phase,actor:signal({analystId:'one'}),ended$:ended,get revision(){return revision;}};
  const toast={show:vi.fn(),current:signal(null)};
  TestBed.configureTestingModule({providers:[{provide:TelecomClient,useValue:api},{provide:SessionStore,useValue:session},
    {provide:IncidentStream,useValue:{connect:(callback:()=>void)=>{refresh=callback;return vi.fn();}}},{provide:ToastService,useValue:toast}]});
  const store=TestBed.inject(NotificationStore);TestBed.tick();
  return {store,toast,refresh:()=>refresh(),end(){phase.set('expired');revision++;ended.next();}};
}
afterEach(()=>{TestBed.resetTestingModule();vi.useRealTimers();vi.unstubAllGlobals();});
describe('Durable private notification reconciliation',()=>{
  it('loads startup history without toast and emits only one new high opening',async()=>{
    vi.useFakeTimers();let items=[item];
    const {store,toast,refresh}=setup({listNotifications:async()=>page(items)});
    await Promise.resolve();expect(store.items()).toEqual([item]);expect(toast.show).not.toHaveBeenCalled();
    items=[{...item,id:'new',severity:'CRITICAL'},item];refresh();await Promise.resolve();await vi.advanceTimersByTimeAsync(250);
    expect(toast.show).toHaveBeenCalledTimes(1);
    refresh();await Promise.resolve();await vi.advanceTimersByTimeAsync(250);expect(toast.show).toHaveBeenCalledTimes(1);
    items=[{...item,id:'medium',severity:'MEDIUM'},{...item,id:'recovered',kind:'RECOVERED'},...items];
    refresh();await Promise.resolve();await vi.advanceTimersByTimeAsync(250);expect(toast.show).toHaveBeenCalledTimes(1);
  });
  it('does not optimistically mark read and retains state when the write fails',async()=>{
    const api={listNotifications:vi.fn().mockResolvedValue(page([item])),markNotificationRead:vi.fn().mockRejectedValue(new Error('Read save unavailable'))};
    const {store}=setup(api);await Promise.resolve();await store.markRead(item.id);
    expect(store.items()[0].readAt).toBeNull();expect(store.unread()).toBe(1);expect(store.error()).toContain('unavailable');
    api.markNotificationRead.mockResolvedValue(undefined);api.listNotifications.mockResolvedValue(page([{...item,readAt:'2026-10-12T09:02:00Z'}]));
    await store.markRead(item.id);expect(store.items()[0].readAt).toBe('2026-10-12T09:02:00Z');expect(store.unread()).toBe(0);
  });
  it('aborts and ignores previous-user history and clears private state on session end',async()=>{
    let resolve!:(value:ReturnType<typeof page>)=>void;
    const {store,end}=setup({listNotifications:()=>new Promise<ReturnType<typeof page>>(r=>resolve=r)});
    store.open.set(true);end();resolve(page([item]));await Promise.resolve();
    expect(store.items()).toEqual([]);expect(store.unread()).toBe(0);expect(store.open()).toBe(false);expect(store.busy()).toBe(false);
  });
  it('keeps one bounded deduplicated page and rejects an older request result',async()=>{
    let resolve!:(value:ReturnType<typeof page>)=>void;
    const api={listNotifications:vi.fn().mockImplementationOnce(()=>new Promise<ReturnType<typeof page>>(r=>resolve=r)).mockResolvedValue(page([{...item,id:'fresh'}]))};
    const {store}=setup(api);await store.load();resolve(page([item]));await Promise.resolve();expect(store.items()[0].id).toBe('fresh');
    api.listNotifications.mockResolvedValue(page(Array.from({length:100},(_,n)=>({...item,id:String(n)}))));await store.load();expect(store.items()).toHaveLength(20);
  });
  it('does not toast the current page again after browsing more than the bounded seen history',async()=>{
    vi.useFakeTimers();const api={listNotifications:vi.fn().mockResolvedValue(page([item]))};
    const {store,toast}=setup(api);await Promise.resolve();
    for(let offset=0;offset<26;offset++){
      api.listNotifications.mockResolvedValue({...page(Array.from({length:20},(_,index)=>({...item,id:`older-${offset}-${index}`}))),page:1});
      await store.load(1);
    }
    api.listNotifications.mockResolvedValue(page([item]));await store.load(0,true);await vi.advanceTimersByTimeAsync(250);
    expect(toast.show).not.toHaveBeenCalled();
  });
});
