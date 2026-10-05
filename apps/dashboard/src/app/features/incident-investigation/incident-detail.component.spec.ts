import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, type ParamMap } from '@angular/router';
import { Subject } from 'rxjs';
import { TelecomClient } from '../../core/api/telecom-client';
import { voiceIncidents } from '../../../fixtures/voice';
import { IncidentStream } from '../../core/state/incident-stream';
import { IncidentDetailComponent } from './incident-detail.component';

describe('Paged incident evidence', () => {
  const incident = voiceIncidents[0];
  const latest = incident.latestDetection;
  let params: Subject<ParamMap>;
  let api: { getIncident: ReturnType<typeof vi.fn>; getDetections: ReturnType<typeof vi.fn>; getTimeline: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    params = new Subject<ParamMap>();
    api = {
      getTimeline: vi.fn().mockResolvedValue({ items: [], total: 0 }),
      getIncident: vi.fn().mockResolvedValue(incident),
      getDetections: vi.fn().mockResolvedValue({ items: [latest], total: 1 }),
    };
    TestBed.configureTestingModule({ providers: [
      provideRouter([]),
      { provide: IncidentStream, useValue: { connect: vi.fn().mockReturnValue(() => {}) } },
      { provide: ActivatedRoute, useValue: { paramMap: params } },
      { provide: TelecomClient, useValue: api },
    ] });
  });

  async function open(id = incident.id) {
    const fixture = TestBed.createComponent(IncidentDetailComponent);
    params.next(convertToParamMap({ id }));
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();
    return fixture;
  }

  it('loads only the selected page and replaces the previous updates', async () => {
    const first = { ...latest, detectionId: 'first', sequence: 1,
      phase: 'UNKNOWN', probableCause: 'Earlier hypothesis', causeConfidence: 'LOW' };
    api.getDetections
      .mockResolvedValueOnce({ items: [first], total: 21 })
      .mockResolvedValueOnce({ items: [latest], total: 21 });
    const fixture = await open();
    expect(api.getDetections).toHaveBeenCalledTimes(1);
    expect(api.getDetections).toHaveBeenLastCalledWith(incident.id, 0, 20, expect.any(AbortSignal));
    expect(fixture.nativeElement.textContent).toContain('does not prove recovery');
    expect(fixture.nativeElement.textContent).toContain('not a confirmed root cause');
    await fixture.componentInstance.load(1);
    fixture.detectChanges();
    expect(fixture.componentInstance.page()).toBe(1);
    expect(fixture.componentInstance.detections()).toEqual([latest]);
    expect(fixture.nativeElement.querySelectorAll('[data-detection-id]')).toHaveLength(1);
  });

  it('aborts the old route and ignores its late response', async () => {
    let finish!: (value: unknown) => void;
    api.getIncident.mockImplementation(async (id: string) => ({ ...incident, id }));
    api.getDetections.mockImplementation((id: string) => id === 'old'
      ? new Promise(resolve => { finish = resolve; })
      : Promise.resolve({ items: [latest], total: 1 }));
    const fixture = TestBed.createComponent(IncidentDetailComponent);
    params.next(convertToParamMap({ id: 'old' }));
    const oldSignal = api.getDetections.mock.calls[0][3] as AbortSignal;
    params.next(convertToParamMap({ id: 'new' }));
    await vi.waitFor(() => expect(fixture.componentInstance.incident()?.id).toBe('new'));
    expect(oldSignal.aborted).toBe(true);
    finish({ items: [], total: 0 });
    await Promise.resolve();
    await Promise.resolve();
    expect(fixture.componentInstance.incident()?.id).toBe('new');
    expect(fixture.componentInstance.detections()).toEqual([latest]);
    fixture.destroy();
    expect((api.getDetections.mock.calls[1][3] as AbortSignal).aborted).toBe(true);
    params.next(convertToParamMap({ id: 'departed' }));
    expect(api.getDetections).toHaveBeenCalledTimes(2);
  });

  it('rejects an oversized page instead of publishing partial evidence', async () => {
    api.getDetections.mockResolvedValue({ items: Array(21).fill(latest), total: 21 });
    const fixture = await open();
    expect(fixture.componentInstance.error()).toContain('exceeded its limit');
    expect(fixture.componentInstance.detections()).toEqual([]);
    expect(fixture.componentInstance.incident()).toBeNull();
  });

  it('shows an empty history without inventing evidence', async () => {
    api.getDetections.mockResolvedValue({ items: [], total: 0 });
    const fixture = await open();
    expect(fixture.nativeElement.textContent).toContain('No evidence updates available');
    expect(fixture.componentInstance.incident()).toEqual(incident);
  });

  it('hides failed evidence and retries from the first page', async () => {
    api.getDetections.mockRejectedValueOnce(new Error('History is unavailable'));
    const fixture = await open();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('History is unavailable');
    expect(fixture.componentInstance.detections()).toEqual([]);
    await fixture.componentInstance.load();
    fixture.detectChanges();
    expect(fixture.componentInstance.error()).toBe('');
    expect(fixture.componentInstance.detections()).toEqual([latest]);
  });

  it('keeps current technical recovery separate from analyst workflow', async () => {
    const fixture = await open();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Technical state: RECOVERED');
    expect(text).toContain('Workflow state: OPEN');
    expect(text).toContain(`Severity: ${incident.severity}`);
    expect(text).toContain('The service has recovered.');
    expect(fixture.nativeElement.querySelector('a').getAttribute('href')).toBe(`/services/${incident.scopeId}`);
  });

  it('refreshes an ongoing incident to show later recovery', async () => {
    api.getIncident
      .mockResolvedValueOnce({ ...incident, technicalState: 'ONGOING' })
      .mockResolvedValueOnce(incident);
    const fixture = await open();
    expect(fixture.nativeElement.textContent).toContain('The issue is still ongoing');
    const refresh = [...fixture.nativeElement.querySelectorAll('button')]
      .find(button => button.textContent.trim() === 'Refresh incident');
    expect(refresh).toBeDefined();
    refresh.click();
    await vi.waitFor(() => expect(fixture.componentInstance.incident()?.technicalState).toBe('RECOVERED'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('The service has recovered.');
  });

  it('refreshes an equal-version comment without hiding the selected evidence page', async () => {
    const fixture = await open();
    const comment = { id: 'comment-1', action: 'COMMENT', note: 'Inspecting the IMS node',
      occurredAt: '2026-10-02T09:00:00Z', actorKind: 'ANALYST' };
    api.getTimeline.mockResolvedValue({ items: [comment], total: 1 });
    await fixture.componentInstance.load(0, true);
    fixture.detectChanges();
    expect(fixture.componentInstance.incident()?.version).toBe(incident.version);
    expect(fixture.nativeElement.textContent).toContain(comment.note);
    expect(fixture.componentInstance.detections()).toEqual([latest]);
  });

  it('keeps a newer action result when a background request returns an older version', async () => {
    const fixture = await open();
    fixture.componentInstance.incident.set({ ...incident, version: incident.version + 1 });
    await fixture.componentInstance.load(0, true);
    expect(fixture.componentInstance.incident()?.version).toBe(incident.version + 1);
    fixture.destroy();
  });
});
