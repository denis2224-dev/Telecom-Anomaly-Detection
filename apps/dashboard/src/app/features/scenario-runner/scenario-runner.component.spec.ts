import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { ScenarioRunnerComponent } from './scenario-runner.component';
import services from '../../../fixtures/services.json';

describe('Approved scenario scopes', () => {
  it('keeps geographic monitoring scopes out of commands unsupported by the backend', async () => {
    TestBed.configureTestingModule({ providers: [
      { provide: SessionStore, useValue: { actor: signal({ analystId: 'scope-test', roles: ['SUPERVISOR'] }), phase: signal('authenticated') } },
      { provide: TelecomClient, useValue: { listServices: async () => [...services,
        { ...services[0], scope: { ...services[0].scope, scopeId: 'VOLTE-MD-ORH' } },
        { ...services[1], scope: { ...services[1].scope, scopeId: 'SMS-MD-ORH' } },
      ] } },
    ] });
    const fixture = TestBed.createComponent(ScenarioRunnerComponent);
    await fixture.whenStable();
    const component = fixture.componentInstance;
    expect(component.availableScopes().map(item => item.scope.scopeId)).toEqual(['VOLTE-MD-CENTRAL']);
    component.scopeId.set('VOLTE-MD-ORH');
    expect(component.canStart()).toBe(false);
    component.type.set('SMS_QUEUE_DELAY');
    expect(component.availableScopes().map(item => item.scope.scopeId)).toEqual(['SMS-MD-ROUTE-A']);
    component.type.set('NORMAL_CONTROL');
    expect(component.availableScopes().map(item => item.scope.scopeId)).toEqual(['VOLTE-MD-CENTRAL', 'SMS-MD-ROUTE-A']);
    fixture.destroy();
  });
});
