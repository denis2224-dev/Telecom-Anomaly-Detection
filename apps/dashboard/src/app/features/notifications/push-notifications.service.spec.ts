import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subject } from 'rxjs';
import { HttpHeaders } from '@angular/common/http';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { PushNotificationsService, vapidBytes } from './push-notifications.service';

const publicKey = btoa(String.fromCharCode(4,...Array(64).fill(1))).replace(/=/g,'');
function setup(enabled=true) {
  vi.stubGlobal('isSecureContext',true);
  const permission = vi.fn().mockResolvedValue('granted');
  vi.stubGlobal('Notification',{permission:'default',requestPermission:permission});
  vi.stubGlobal('PushManager',class {});
  const subscription={toJSON:()=>({endpoint:'https://push.example/sub',keys:{p256dh:'key',auth:'auth'}}),unsubscribe:vi.fn().mockResolvedValue(true)};
  const registration={active:{},pushManager:{getSubscription:vi.fn().mockResolvedValue(null),subscribe:vi.fn().mockResolvedValue(subscription)}};
  const register=vi.fn().mockResolvedValue(registration);
  vi.stubGlobal('navigator',{serviceWorker:{register}});
  const api={notificationPushConfig:vi.fn().mockResolvedValue({enabled,vapidPublicKey:publicKey}),savePushSubscription:vi.fn().mockResolvedValue({headers:new HttpHeaders({'Location':'/api/notifications/push-subscriptions/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'})}),deletePushSubscription:vi.fn().mockResolvedValue(undefined)};
  const ended=new Subject<void>(),session={phase:signal('authenticated'),revision:1,ended$:ended};
  TestBed.configureTestingModule({providers:[{provide:TelecomClient,useValue:api},{provide:SessionStore,useValue:session}]});
  return {service:TestBed.inject(PushNotificationsService),api,session,ended,permission,register,subscription};
}
afterEach(()=>{TestBed.resetTestingModule();vi.unstubAllGlobals();});
describe('Opt-in browser alerts',()=>{
  it('does not request permission on construction or when the feature is off',async()=>{
    const {service,permission,register}=setup(false);expect(permission).not.toHaveBeenCalled();await service.enable();
    expect(service.state()).toBe('off');expect(permission).not.toHaveBeenCalled();expect(register).not.toHaveBeenCalled();
  });
  it('handles denied, unsupported and insecure contexts without registration',async()=>{
    const {service,permission,register}=setup();
    vi.stubGlobal('isSecureContext',false);await service.enable();expect(service.state()).toBe('insecure');
    vi.stubGlobal('isSecureContext',true);vi.stubGlobal('PushManager',undefined);await service.enable();expect(service.state()).toBe('unsupported');
    vi.stubGlobal('PushManager',class {});vi.stubGlobal('Notification',{permission:'denied',requestPermission:permission});await service.enable();
    expect(service.state()).toBe('denied');expect(permission).not.toHaveBeenCalled();expect(register).not.toHaveBeenCalled();
  });
  it('saves the typed subscription and disables with its server identity',async()=>{
    const {service,api,permission,subscription}=setup();await service.enable();expect(service.state()).toBe('enabled');expect(permission).toHaveBeenCalledTimes(1);
    expect(api.savePushSubscription).toHaveBeenCalledWith({endpoint:'https://push.example/sub',p256dh:'key',auth:'auth',expirationTime:null},expect.any(AbortSignal));
    await service.disable();expect(api.deletePushSubscription).toHaveBeenCalledWith('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',expect.any(AbortSignal));
    expect(subscription.unsubscribe).toHaveBeenCalledOnce();expect(service.state()).toBe('idle');
  });
  it('rolls back the browser subscription if the server omits its removable identity',async()=>{
    const {service,api,subscription}=setup();api.savePushSubscription.mockResolvedValue({headers:new HttpHeaders()});
    await service.enable();expect(service.state()).toBe('error');expect(subscription.unsubscribe).toHaveBeenCalledOnce();
  });
  it('ignores configuration from an ended session',async()=>{
    const {service,api,session,ended,permission}=setup();let resolve!:(value:{enabled:boolean;vapidPublicKey:string})=>void;
    api.notificationPushConfig.mockImplementation(()=>new Promise(r=>resolve=r));const pending=service.enable();session.revision++;session.phase.set('expired');ended.next();
    resolve({enabled:true,vapidPublicKey:publicKey});await pending;expect(service.state()).toBe('idle');expect(permission).not.toHaveBeenCalled();
  });
  it('validates the VAPID key before any permission prompt',()=>{expect(vapidBytes(publicKey)).toHaveLength(65);expect(()=>vapidBytes('invalid')).toThrow();});
});
