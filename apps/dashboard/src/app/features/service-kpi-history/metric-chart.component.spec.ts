import { TestBed } from '@angular/core/testing';
import { MetricChartComponent } from './metric-chart.component';
import { voiceIncidents, voiceWindows, voiceRange } from '../../../fixtures/voice';
import suite from '../../../../../../contracts/fixtures/detections/service-explanation-cases.json';
import type { KpiWindow } from './voice-model';

describe('Shared hero chart', () => {
  it('merges overlapping incidents, keeps gaps and scales small fluctuations', async () => {
    const fixture = TestBed.createComponent(MetricChartComponent);
    fixture.componentRef.setInput('name', 'cssrPct');
    fixture.componentRef.setInput('title', 'CSSR');
    fixture.componentRef.setInput('from', voiceRange.from);
    fixture.componentRef.setInput('to', voiceRange.to);
    fixture.componentRef.setInput('windows', voiceWindows);
    fixture.componentRef.setInput('incidents', Array.from({ length: 20 }, (_, i) => ({ ...voiceIncidents[0], id: String(i) })));
    await fixture.whenStable();
    const chart = fixture.componentInstance;
    expect(chart.incidentBands()).toHaveLength(1);
    expect(chart.path(false).match(/M/g)).toHaveLength(3);
    expect(chart.bounds()[0]).toBeGreaterThan(0);
    chart.navigate(new KeyboardEvent('keydown', { key: 'End' }));
    expect(chart.hovered()).toEqual(voiceWindows.at(-1));
    fixture.componentRef.setInput('name', 'imsCpuPct');
    await fixture.whenStable();
    expect(chart.hovered()).toBeNull();
    expect(chart.hasValues()).toBe(false);
    expect(chart.path(false)).toBe('');
  });
  it('uses only valid success-rate counters for traffic and keeps the inspected window on live updates', async () => {
    for (const service of ['volte', 'sms']) {
      const rows = structuredClone(suite.cases.find(item => item.id === `${service}-fault`)!.windows.map(item => item.feature)) as KpiWindow[];
      const name = service === 'volte' ? 'cssrPct' : 'deliverySrPct';
      const fixture = TestBed.createComponent(MetricChartComponent);
      for (const [key, value] of Object.entries({ name, title: 'Traffic', unit: 'PERCENT', traffic: true, hero: true,
        from: rows[0].windowStart, to: rows.at(-1)!.windowEnd, windows: rows })) fixture.componentRef.setInput(key, value);
      await fixture.whenStable();
      const chart = fixture.componentInstance, kpi = rows[0].kpis.find(item => item.name === name)!;
      expect(chart.counts(rows[0])).toEqual({ success: kpi.numerator, failed: kpi.denominator! - kpi.numerator!, total: kpi.denominator });
      expect(chart.trafficRows()).toHaveLength(rows.length);
      expect(chart.trafficPath(true).match(/[ML]/g)).toHaveLength(rows.length);
      expect(chart.trafficPath(false).match(/[ML]/g)).toHaveLength(rows.length);
      for (const counters of [{ numerator: null }, { denominator: 0 }, { numerator: -1 }, { numerator: kpi.denominator! + 1 }, { denominator: 1.5 }]) {
        const invalid = structuredClone(rows[0]);
        Object.assign(invalid.kpis.find(item => item.name === name)!, counters);
        expect(chart.counts(invalid)).toBeNull();
      }
      expect(chart.counts({ ...rows[0], quality: 'MISSING' })).toBeNull();
      const selected = vi.fn(); chart.windowSelected.subscribe(selected);
      chart.navigate(new KeyboardEvent('keydown', { key: 'End' })); await fixture.whenStable();
      const inspected = chart.hovered()!;
      fixture.componentRef.setInput('windows', [rows.at(-1)]); await fixture.whenStable();
      expect(chart.hovered()?.windowId).toBe(inspected.windowId);
      fixture.componentRef.setInput('to',new Date(Date.parse(rows.at(-1)!.windowEnd)+60_000).toISOString());
      await fixture.whenStable();expect(chart.hovered()?.windowId).toBe(inspected.windowId);
      expect(chart.path(true)).toContain('L'); // A single measured window still has a visible baseline segment.
      expect(selected).toHaveBeenCalledWith(inspected);
      chart.hoveredId.set(null); await fixture.whenStable(); expect(selected).toHaveBeenLastCalledWith(null);
      fixture.destroy();
    }
  });
});

describe('Historical chart navigation', () => {
  it('focuses recorded data without changing selected bounds, source rows or missing gaps', async () => {
    const fixture = TestBed.createComponent(MetricChartComponent);
    for (const [key,value] of Object.entries({name:'cssrPct',title:'History',unit:'PERCENT',hero:true,
      from:'2026-09-01T00:00:00Z',to:'2026-11-01T00:00:00Z',windows:voiceWindows})) fixture.componentRef.setInput(key,value);
    await fixture.whenStable();const chart=fixture.componentInstance;
    expect(chart.canvasWidth()).toBe(6000);
    chart.focusRecorded();await fixture.whenStable();
    expect(chart.visibleFrom()).toBe(new Date(Date.parse(voiceWindows[0].windowStart)).toISOString());
    expect(chart.visibleTo()).toBe(new Date(Date.parse(voiceWindows.at(-1)!.windowEnd)).toISOString());
    expect(chart.from()).toBe('2026-09-01T00:00:00Z');
    expect(chart.rows()).toEqual(voiceWindows);
    expect(chart.path(false).match(/M/g)).toHaveLength(3);
    chart.visibleRange.set(null);await fixture.whenStable();expect(chart.canvasWidth()).toBe(6000);
    expect(chart.visibleTo()).toBe('2026-11-01T00:00:00Z');
  });
  it('clamps zoom and pan to the selected interval and leaves source data intact',async()=>{
    const fixture=TestBed.createComponent(MetricChartComponent);
    for(const [key,value] of Object.entries({name:'cssrPct',title:'History',from:voiceRange.from,to:voiceRange.to,windows:voiceWindows}))fixture.componentRef.setInput(key,value);
    await fixture.whenStable();const chart=fixture.componentInstance;
    chart.zoom(.5);const span=chart.visibleDuration();
    for(let i=0;i<20;i++)chart.pan(1);
    expect(Date.parse(chart.visibleTo())).toBe(Date.parse(voiceRange.to));expect(chart.visibleDuration()).toBe(span);
    for(let i=0;i<20;i++)chart.pan(-1);
    expect(Date.parse(chart.visibleFrom())).toBe(Date.parse(voiceRange.from));
    chart.zoom(100);expect(chart.visibleRange()).toBeNull();expect(chart.rows()).toEqual(voiceWindows);
  });
});

 describe('Readable chart time scale',()=>{
  it('spreads a full interval, caps long canvases and keeps sparse bars within their source slots',async()=>{
    const fixture=TestBed.createComponent(MetricChartComponent);
    for(const [key,value] of Object.entries({name:'cssrPct',title:'CSSR',hero:true,traffic:true,trafficBars:true,from:'2026-09-15T00:00:00Z',to:'2026-09-16T00:00:00Z',windows:voiceWindows}))fixture.componentRef.setInput(key,value);
    await fixture.whenStable();const chart=fixture.componentInstance;
    expect(chart.canvasWidth()).toBe(4320);
    expect(chart.axisDateFormat()).toBe('dd MMM HH:mm');
    const row=chart.rows()[0];
    expect(chart.barWidth()).toBeLessThanOrEqual(Math.max(1,(chart.x(row.windowEnd)-chart.x(row.windowStart))*.7));
    chart.zoom(.25);expect(chart.canvasWidth()).toBe(1200);
    expect(chart.from()).toBe('2026-09-15T00:00:00Z');
    fixture.componentRef.setInput('to','2026-10-15T00:00:00Z');await fixture.whenStable();
    expect(chart.canvasWidth()).toBe(6000);
    expect(chart.path(false).match(/M/g)).toHaveLength(3);
  });
 });

 it('keeps the inspected sample in view when toolbar focus closes its tooltip',async()=>{
  const fixture=TestBed.createComponent(MetricChartComponent);
  for(const [key,value] of Object.entries({name:'cssrPct',title:'CSSR',hero:true,from:'2026-09-14T10:10:00Z',to:'2026-09-15T10:10:00Z',windows:voiceWindows}))fixture.componentRef.setInput(key,value);
  await fixture.whenStable();const chart=fixture.componentInstance;
  chart.navigate(new KeyboardEvent('keydown',{key:'End'}));chart.hoveredId.set(null);chart.zoom(.5);
  expect(chart.drawingRows()).toContain(voiceWindows.at(-1));
  expect(chart.path(false)).not.toBe('');
 });
