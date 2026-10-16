import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {signal} from '@angular/core';
import {Subject} from 'rxjs';
import {voiceIncidents} from '../../../fixtures/voice';
import {SessionStore} from '../login-and-session/session.store';
import {TelecomClient} from '../../core/api/telecom-client';
import {legacyAnalystIncident} from './analyst-support.model';
import {ShiftHandoverComponent} from './shift-handover.component';
import {IncidentAssistComponent} from './incident-assist.component';

describe('Analyst assistance lifecycle',()=>{
  it('keeps a snapshot fixed until refreshed and clears it when the session ends',async()=>{
    const ended=new Subject<void>();
    TestBed.configureTestingModule({providers:[{provide:SessionStore,useValue:{revision:0,ended$:ended.asObservable()}}]});
    const fixture=TestBed.createComponent(ShiftHandoverComponent),component=fixture.componentInstance;
    fixture.componentRef.setInput('items',[legacyAnalystIncident(voiceIncidents[0])]);fixture.componentRef.setInput('total',10);fixture.componentRef.setInput('atLeast',true);
    await fixture.whenStable();component.capture();const first=component.summary();expect(first).toContain('at least 10 episodes');
    fixture.componentRef.setInput('items',[]);await fixture.whenStable();expect(component.summary()).toBe(first);
    component.capture();expect(component.summary()).toContain('No incidents in this snapshot.');
    vi.spyOn(component.drawer()!,'close').mockImplementation(()=>{});ended.next();expect(component.summary()).toBe('');fixture.destroy();
  });
  it('cancels a related read and rejects its late result after the selected incident changes',async()=>{
    const pending:{resolve:(value:unknown)=>void;signal:AbortSignal}[]=[];
    const api={listIncidents:vi.fn((_query:unknown,signal:AbortSignal)=>new Promise(resolve=>pending.push({resolve,signal})))};
    TestBed.configureTestingModule({providers:[provideRouter([]),{provide:TelecomClient,useValue:api},{provide:SessionStore,useValue:{revision:0,actor:signal({analystId:'analyst'}),phase:signal('authenticated')}}]});
    const fixture=TestBed.createComponent(IncidentAssistComponent),component=fixture.componentInstance;
    const selected=legacyAnalystIncident(voiceIncidents[0]);fixture.componentRef.setInput('incident',selected);await fixture.whenStable();expect(api.listIncidents).not.toHaveBeenCalled();
    component.opened.set(true);await fixture.whenStable();expect(pending).toHaveLength(1);
    fixture.componentRef.setInput('incident',{...selected,id:'new',scope:'another-scope'});await fixture.whenStable();expect(pending[0].signal.aborted).toBe(true);expect(pending).toHaveLength(2);
    pending[1].resolve({items:[],total:0,page:0,size:100});await Promise.resolve();await fixture.whenStable();
    pending[0].resolve({items:[{...voiceIncidents[0],id:'late-related'}],total:1,page:0,size:100});await Promise.resolve();await fixture.whenStable();
    expect(component.matches()).toEqual([]);expect(component.total()).toBe(0);expect(component.error()).toBe('');fixture.destroy();
  });
});
