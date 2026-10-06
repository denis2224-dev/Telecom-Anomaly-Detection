import { TestBed } from '@angular/core/testing';
import { DOCUMENT } from '@angular/common';
import { Subject } from 'rxjs';
import { IncidentStream } from './incident-stream';
import { LiveUpdates } from './live-updates';
import { SessionStore } from '../../features/login-and-session/session.store';

describe('Visible, minute-aligned live fallback', () => {
  let refresh = vi.fn<() => void>(), close = vi.fn<() => void>();
  let upsert: () => void, interrupt: () => void, connect: () => void;
  let ended: Subject<void>, hidden: boolean, live: LiveUpdates;
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-06T10:00:25Z'));
    refresh = vi.fn(); close = vi.fn(); ended = new Subject<void>(); hidden = false;
    vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
    TestBed.configureTestingModule({ providers: [
      { provide: DOCUMENT, useValue: document },
      { provide: SessionStore, useValue: { phase: () => 'authenticated', ended$: ended } },
      { provide: IncidentStream, useValue: { connect: (a: () => void, b: () => void, c: () => void) => {
        upsert = a; interrupt = b; connect = c; return close;
      } } },
    ] });
    live = TestBed.inject(LiveUpdates);
  });
  afterEach(() => { ended.next(); TestBed.resetTestingModule(); vi.restoreAllMocks(); vi.useRealTimers(); });

  it('polls at the next minute, stops on reconnect, and resumes aligned after interruption', () => {
    const stop = live.watch(refresh, vi.fn(), vi.fn());
    vi.advanceTimersByTime(34_999); expect(refresh).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1); expect(refresh).toHaveBeenCalledTimes(1);
    connect(); vi.advanceTimersByTime(60_000); expect(refresh).toHaveBeenCalledTimes(1);
    upsert(); expect(refresh).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(15_000); interrupt();
    vi.advanceTimersByTime(45_000); expect(refresh).toHaveBeenCalledTimes(3);
    stop(); vi.advanceTimersByTime(120_000);
    expect(refresh).toHaveBeenCalledTimes(3); expect(close).toHaveBeenCalledTimes(1);
  });

  it('pauses in hidden tabs and catches up immediately on visibility, including with a healthy stream', () => {
    live.watch(refresh, vi.fn(), vi.fn());
    hidden = true; document.dispatchEvent(new Event('visibilitychange'));
    upsert(); vi.advanceTimersByTime(120_000); expect(refresh).not.toHaveBeenCalled();
    hidden = false; document.dispatchEvent(new Event('visibilitychange'));
    expect(refresh).toHaveBeenCalledTimes(1);
    connect(); hidden = true; document.dispatchEvent(new Event('visibilitychange'));
    hidden = false; document.dispatchEvent(new Event('visibilitychange'));
    expect(refresh).toHaveBeenCalledTimes(2);
    ended.next(); upsert(); vi.advanceTimersByTime(120_000);
    expect(refresh).toHaveBeenCalledTimes(2); expect(close).toHaveBeenCalledTimes(1);
  });

  it('marks only successful snapshots live and becomes stale after two minutes', () => {
    live.watch(refresh, vi.fn(), vi.fn()); connect();
    expect(live.lastUpdated()).toBeNull(); expect(live.stale()).toBe(true);
    live.updated(); expect(live.stale()).toBe(false);
    vi.advanceTimersByTime(119_000); expect(live.stale()).toBe(false);
    vi.advanceTimersByTime(1000); expect(live.stale()).toBe(true);
    live.updated(); expect(live.stale()).toBe(false);
  });
});
