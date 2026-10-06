import { TestBed } from '@angular/core/testing';
import { ApiFailure } from '../../core/api/api-errors';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { RunStore } from './run.store';

describe('Scenario command retry', () => {
  it('reuses the original request ID after an uncertain failure', async () => {
    const actor = {
      analystId: 'supervisor-1',
      displayName: 'Supervisor',
      roles: ['SUPERVISOR' as const],
      expiresAt: new Date(
        Date.now() + 60000,
      ).toISOString(),
    };

    sessionStorage.removeItem(
      'telecom.scenario-runner:supervisor-1',
    );

    const accepted = {
      runId: crypto.randomUUID(),
      status: 'SCHEDULED' as const,
      scheduledStartAt: '2026-09-30T12:00:00Z',
      scheduledEndAt: '2026-09-30T12:08:00Z',
      scenarioType: 'VOLTE_IMS_OVERLOAD' as const,
      scopeId: 'VOLTE-MD-CENTRAL',
    };

    const startScenario = vi.fn()
      .mockRejectedValueOnce(new ApiFailure(503))
      .mockResolvedValueOnce(accepted);

    TestBed.configureTestingModule({
      providers: [
        {
          provide: TelecomClient,
          useValue: { startScenario },
        },
        {
          provide: SessionStore,
          useValue: {
            actor: () => actor,
            phase: () => 'authenticated',
          },
        },
      ],
    });

    const store = TestBed.inject(RunStore);

    await store.start(
      'VOLTE_IMS_OVERLOAD',
      42,
      'VOLTE-MD-CENTRAL',
    );

    const firstId = store.command()?.requestId;

    expect(firstId).toBeTruthy();
    expect(store.run()).toBeNull();
    expect(startScenario).toHaveBeenCalledTimes(1);

    await store.start(
      'VOLTE_IMS_OVERLOAD',
      42,
      'VOLTE-MD-CENTRAL',
    );

    expect(startScenario).toHaveBeenCalledTimes(2);
    expect(startScenario.mock.calls[0]).toEqual(
      startScenario.mock.calls[1],
    );
    expect(store.command()?.requestId).toBe(firstId);
    expect(store.run()?.runId).toBe(accepted.runId);

    sessionStorage.removeItem(
      'telecom.scenario-runner:supervisor-1',
    );
  });

  it('does not start or save a command after the session ends', async () => {
    const startScenario = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: TelecomClient, useValue: { startScenario } },
        {
          provide: SessionStore,
          useValue: { actor: () => null, phase: () => 'expired' },
        },
      ],
    });
    sessionStorage.removeItem('telecom.scenario-runner:unknown');
    const store = TestBed.inject(RunStore);

    await store.start('VOLTE_IMS_OVERLOAD', 42, 'VOLTE-MD-CENTRAL');
    expect(startScenario).not.toHaveBeenCalled();

    store.command.set({
      type: 'VOLTE_IMS_OVERLOAD',
      requestId: 'previous-request',
      seed: 42,
      scopeId: 'VOLTE-MD-CENTRAL',
    });
    store.canAbandon.set(true);
    store.newCommand();
    expect(sessionStorage.getItem('telecom.scenario-runner:unknown')).toBeNull();
  });
});
