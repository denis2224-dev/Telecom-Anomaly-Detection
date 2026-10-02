import { TestBed } from '@angular/core/testing';
import { EVENT_SOURCE, LiveUpdates, REFRESH_MS } from './live-updates';

describe('Session-owned live notifications', () => {
  let live: LiveUpdates;
  let sources: { onopen: (() => void) | null; onerror: (() => void) | null; onmessage: (() => void) | null; close: ReturnType<typeof vi.fn>; addEventListener: ReturnType<typeof vi.fn> }[];
  beforeEach(() => {
    vi.useFakeTimers(); sources = [];
    TestBed.configureTestingModule({ providers: [{ provide: EVENT_SOURCE, useValue: vi.fn(() => {
      const source = { onopen: null, onerror: null, onmessage: null, close: vi.fn(), addEventListener: vi.fn() };
      sources.push(source); return source;
    }) }] });
    live = TestBed.inject(LiveUpdates);
  });
  afterEach(() => { live.stop(); vi.useRealTimers(); });
  it('reloads authoritative REST on each connection and coalesces events', () => {
    const refresh = vi.fn(); live.refresh$.subscribe(refresh); live.start();
    sources[0].onopen!(); expect(refresh).toHaveBeenCalledWith('reconnect');
    sources[0].onmessage!(); sources[0].onmessage!(); vi.advanceTimersByTime(250);
    expect(refresh.mock.calls.filter(call => call[0] === 'incidents')).toHaveLength(1);
    sources[0].onerror!(); expect(sources[0].close).toHaveBeenCalled();
    vi.advanceTimersByTime(REFRESH_MS); sources[1].onopen!();
    expect(refresh.mock.calls.filter(call => call[0] === 'reconnect')).toHaveLength(2);
  });
  it('bounds minute refresh and releases streams, retries and callbacks at session end', () => {
    const refresh = vi.fn(); live.refresh$.subscribe(refresh); live.start();
    vi.advanceTimersByTime(REFRESH_MS - 1); expect(refresh).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1); expect(refresh).toHaveBeenCalledWith('current');
    const stale = sources[0].onopen; sources[0].onerror!(); live.stop(); refresh.mockClear();
    vi.advanceTimersByTime(120000); stale!();
    expect(sources).toHaveLength(1); expect(refresh).not.toHaveBeenCalled();
    expect(live.state()).toBe('OFFLINE');
  });
  it('never opens a stream or starts polling in fixture mode', () => {
    live.start(true); vi.advanceTimersByTime(120000);
    expect(sources).toHaveLength(0); expect(live.state()).toBe('FIXTURE');
  });
  it('keeps one poller and stream across repeated starts, then ignores closed-stream events', () => {
    const refresh = vi.fn(); live.refresh$.subscribe(refresh);
    live.start(); live.start(); live.start();
    expect(sources).toHaveLength(1);
    vi.advanceTimersByTime(REFRESH_MS);
    expect(refresh.mock.calls.filter(call => call[0] === 'current')).toHaveLength(1);
    const old = sources[0]; live.stop(); live.start(); refresh.mockClear();
    old.onopen!(); old.onmessage!(); old.onerror!();
    vi.advanceTimersByTime(250);
    expect(refresh).not.toHaveBeenCalled();
    expect(sources).toHaveLength(2);
    expect(live.state()).toBe('CONNECTING');
  });
});
