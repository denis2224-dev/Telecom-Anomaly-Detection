import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { ScenarioRunnerComponent } from './scenario-runner.component';
import services from '../../../fixtures/services.json';
import { cities } from '../service-overview/dashboard-geography';

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

  it('offers active catalogue city scopes only for compatible services', async () => {
    const cityServices = cities.flatMap(city => (['VOLTE', 'SMS'] as const).map(service => ({
      ...services[service === 'VOLTE' ? 0 : 1],
      scope: { ...services[service === 'VOLTE' ? 0 : 1].scope, scopeId: `${service}-MD-${city.id}` },
    })));
    const catalogue = { catalogueVersion: 'catalogue-v2', topologyVersion: 'topology-v2',
      cities: cities.map(city => ({ cityId: city.id, catalogueVersion: 'catalogue-v2', topologyVersion: 'topology-v2',
        services: (['VOLTE', 'SMS'] as const).map(service => ({ service, scopeId: `${service}-MD-${city.id}` })) })) };
    TestBed.configureTestingModule({ providers: [
      { provide: SessionStore, useValue: { actor: signal({ analystId: 'scope-test', roles: ['SUPERVISOR'] }), phase: signal('authenticated') } },
      { provide: TelecomClient, useValue: { listServices: async () => [...services, ...cityServices],
        listGeographyCities: async () => catalogue } },
    ] });
    const fixture = TestBed.createComponent(ScenarioRunnerComponent);
    await fixture.whenStable();
    const component = fixture.componentInstance;
    expect(component.cityScopesAvailable()).toBe(true);
    expect(component.availableScopes().map(item => item.scope.scopeId)).toContain('VOLTE-MD-ORH');
    expect(component.availableScopes().map(item => item.scope.scopeId)).not.toContain('SMS-MD-ORH');
    component.type.set('SMS_QUEUE_DELAY');
    expect(component.availableScopes().map(item => item.scope.scopeId)).toContain('SMS-MD-ORH');
    expect(component.availableScopes().map(item => item.scope.scopeId)).not.toContain('VOLTE-MD-ORH');
    fixture.destroy();
  });
});
