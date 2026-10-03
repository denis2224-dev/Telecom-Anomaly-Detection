# Day 16 — Polish terminology and empty states

**Planned day:** Tuesday, 6 October 2026

**Goal:** Make percentages, model signals, and missing evidence understandable to someone unfamiliar with telecom.

## 1. Explain it like I am five

Imagine the app is showing a report card. Some numbers tell us what happened, some compare it with what we expected, and some are guesses from a computer helper.

Today we put small explanations beside those numbers. If a number is missing, we say what is missing and what to try next. We never replace “we do not know” with zero or a green “everything is fine.”

**Why do we add this?** A number can be correct but easy to misunderstand. For example, model rank `0.82` does not mean “82% chance of failure.”

**How does it make our app better?** Analysts can explain an incident, understand the limits of its evidence, and choose their next action without asking a developer to decode the screen.

## 2. What this guide changes

The screenshot's two main files map directly to this repository:

- `apps/dashboard/src/app/shared/metric-explanation.component.ts`
- `docs/ux/terminology.md`

We also connect the shared component to the screens where analysts actually read the metrics. We keep the current dark dashboard layout, Day 15 pagination/range limits, Day 13 reconnect behavior, and Day 14 workflow.

The screenshot names four exceptional conditions. For a concrete five-case acceptance check, this guide uses:

1. **Missing observations:** current measurements cannot be confirmed.
2. **Absent baseline:** the observed value may exist, but comparison is unavailable.
3. **Low sample volume:** too few completed-message samples make p95 fragile. Zero and unknown samples are also checked separately.
4. **ML unavailable:** measured evidence and rule-based investigation remain usable, but no model rank is available.
5. **Stale evidence:** retained values are historical and cannot confirm current health.

The teammate must also understand **percentage points versus relative percent**, **p95**, **estimated extra failed attempts**, and the difference between **severity, anomaly rank, and cause confidence**.

Use the existing contracts:

- `ServiceSummary.freshness`: `FRESH`, `STALE`, or `MISSING`.
- `ServiceKpiWindow.quality`: `COMPLETE`, `INCOMPLETE`, or `MISSING`.
- KPI `observed`, `baseline`, `numerator`, and `denominator` can be null.
- Model status: `OK`, `TIMEOUT`, `UNAVAILABLE`, `INSUFFICIENT_DATA`, or `NOT_APPLICABLE`.
- Model rank is nullable and ranges from 0 to 1. Its calibration meaning is documented in `services/ml-service/README.md`.

This is an implementation guide. Writing it does not implement Day 16 or establish human acceptance.

Preparation checks: the proposed edits passed Angular application/template and unit-spec type checking in a temporary copy. The new Playwright spec type-checked, and 13 runnable presentation checks passed. The production build, full unit/browser test runs, and human acceptance remain checks to complete after applying the guide.

## 3. Branch and prerequisites

Recommended branch: **`codex/day-16-terminology-empty-states`**.

Day 15's bounded-history implementation has been merged into `main`. This guide follows Day 15's structure: simple explanation, file-by-file code, commit checkpoints, verification, evidence, and merge criteria.

Run from the repository root:

```bash
git status --short
git switch main
git pull --ff-only origin main
git switch -c codex/day-16-terminology-empty-states
```

Review and save any edits before switching if your working tree has changed. Do not create a commit merely for creating a branch.

All file paths below are relative to the repository root. Run application commands from `apps/dashboard`, and Git commands from the repository root. Use a Node version supported by `apps/dashboard/package.json`; run `npm ci` if dependencies are missing. No new dependency is needed.

## 4. Create the shared explanations

### Create `apps/dashboard/src/app/shared/metric-explanation.component.ts`

**Why?** Keep short explanations and actionable missing-data labels consistent across screens.

**App improvement:** A native `<details>` gives optional help that works with keyboard and touch. Important exceptional states remain visible rather than being hidden in a tooltip.

```ts
import { Component, computed, input } from '@angular/core';

type Help = { title: string; text: string; action: string };

export const METRIC_HELP = {
  'percentage-points': {
    title: 'Percent or percentage points?',
    text: 'Percent describes a rate. Percentage points (pp) describe the difference between two rates. A change from 98% to 96% is a drop of 2 pp, or about 2.04% relative to the original 98%.',
    action: 'Read pp as a difference in rates. The trend on this screen uses pp, not relative percent.',
  },
  p95: {
    title: 'What does delivery p95 mean?',
    text: 'Delivery p95 is the delay at or below which 95% of completed-message samples fall. It is not the average. Pending messages are outside those samples, and a small sample can make the percentile unstable.',
    action: 'Read the completed-message count, queue depth, and oldest queued-message age together.',
  },
  'failed-attempts': {
    title: 'What are estimated extra failed attempts?',
    text: 'This estimates failures above the expected baseline for the same window. For example, 98% expected success and 96% actual success across 1,000 attempts imply about 20 extra failed attempts. Attempts are not unique customers.',
    action: 'Read this as an estimate based on recorded counters and the baseline, not a count of people.',
  },
  rank: {
    title: 'What does model anomaly rank mean?',
    text: 'Rank runs from 0 to 1 and compares unusualness with the model\'s normal calibration data. A higher rank looks more unusual. A rank of 0.82 is not a failure probability and does not mean an 82% chance of failure. An unavailable rank is not zero.',
    action: 'Use measured KPIs and service severity to investigate. Rank does not replace them.',
  },
  confidence: {
    title: 'What does cause confidence mean?',
    text: 'LOW, MEDIUM, and HIGH describe the support for a cause hypothesis. Confidence is not proof, a calibrated probability, or the service severity.',
    action: 'Use the recommended checks to confirm or reject the hypothesis.',
  },
  'missing-evidence': {
    title: 'Current measurements unavailable',
    text: 'An observation or required measurement is missing. This cannot confirm healthy service, an empty queue, or recovery.',
    action: 'Refresh this service. If evidence is still missing, check the telemetry source or ask the service owner.',
  },
  'baseline-missing': {
    title: 'Expected value unavailable',
    text: 'A recorded baseline is missing. The actual measurement may still be useful, but comparison with the expected value is unavailable.',
    action: 'Inspect the measured value and related evidence. Ask the baseline owner to check coverage for this scope and time.',
  },
  'low-volume': {
    title: 'Low sample volume',
    text: 'Fewer than 30 messages completed in this window. Interpret p95 cautiously; a few samples can change the percentile substantially.',
    action: 'Compare nearby windows and inspect the queue. Do not average per-window p95 values to invent a combined p95.',
  },
  'no-samples': {
    title: 'No completed messages in this window',
    text: 'Delivery p95 cannot be measured without completed-message samples. Zero completions does not prove that the queue is empty.',
    action: 'Inspect queue depth and oldest queued-message age; wait for a window with completed samples.',
  },
  'sample-count-missing': {
    title: 'Completed-message count unavailable',
    text: 'The number of completed samples is unknown. Delivery p95 cannot be interpreted as a supported measurement from this window.',
    action: 'Refresh the evidence and check the completed-message counter. Unknown is different from zero.',
  },
  'ml-unavailable': {
    title: 'Model rank unavailable',
    text: 'There is no usable model result for this update. This does not prove normal service or recovery; measured KPIs and rule-based severity remain separate evidence.',
    action: 'Continue with the recorded measurements and recommended checks. If model results remain unavailable, ask the model-service owner.',
  },
  'stale-evidence': {
    title: 'Historical evidence — current health unknown',
    text: 'The server marks the latest service evidence as stale. Retained values are historical evidence and cannot confirm the current state.',
    action: 'Refresh this service. If observations remain stale, check the telemetry source before declaring recovery.',
  },
} satisfies Record<string, Help>;

export type MetricTopic = keyof typeof METRIC_HELP;

@Component({
  selector: 'app-metric-explanation',
  styles: [`
    :host { display: block; min-width: 0; }
    details, aside { margin-block: .65rem; }
    summary { cursor: pointer; }
    p { margin-block: .4rem; line-height: 1.5; overflow-wrap: anywhere; }
  `],
  template: `
    @if (mode() === 'state') {
      <aside class="notice" role="note" [attr.aria-label]="copy().title" [attr.data-topic]="topic()">
        <strong>{{ copy().title }}</strong>
        <p>{{ copy().text }}</p>
        <p>{{ copy().action }}</p>
      </aside>
    } @else {
      <details class="metric-help" [attr.data-topic]="topic()">
        <summary>{{ copy().title }}</summary>
        <p>{{ copy().text }}</p>
        <p>{{ copy().action }}</p>
      </details>
    }
  `,
})
export class MetricExplanationComponent {
  readonly topic = input.required<MetricTopic>();
  readonly mode = input<'help' | 'state'>('help');
  readonly copy = computed(() => METRIC_HELP[this.topic()]);
}
```

The **30 completed messages** warning already exists in `SampleVolumeComponent`. It is a display caution for SMS p95, not a new detection threshold or a promise of statistical reliability at 30 samples. Keep backend eligibility thresholds and severity policy unchanged; Sergiu reviews the wording and this existing cutoff.

### Create `apps/dashboard/src/app/shared/metric-explanation.component.spec.ts`

```ts
import { TestBed } from '@angular/core/testing';
import { MetricExplanationComponent } from './metric-explanation.component';

describe('Contextual metric help', () => {
  it('uses native expandable help for optional explanations', () => {
    const fixture = TestBed.createComponent(MetricExplanationComponent);
    fixture.componentRef.setInput('topic', 'rank');
    fixture.detectChanges();
    const details: HTMLDetailsElement = fixture.nativeElement.querySelector('details');
    expect(details.open).toBe(false);
    expect(details.querySelector('summary')?.textContent).toContain('model anomaly rank');
    expect(details.textContent).toContain('not a failure probability');
  });

  it('keeps exceptional states and next actions visible', () => {
    const fixture = TestBed.createComponent(MetricExplanationComponent);
    fixture.componentRef.setInput('topic', 'baseline-missing');
    fixture.componentRef.setInput('mode', 'state');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('details')).toBeNull();
    const note = fixture.nativeElement.querySelector('[role="note"]');
    expect(note.textContent).toContain('Expected value unavailable');
    expect(note.textContent).toContain('Ask the baseline owner');
  });
});
```

## 5. Share the nullable-value rules

### Create `apps/dashboard/src/app/shared/metric-presentation.ts`

**Why?** Overview, incident summary, and evidence cells must agree on when a rate or p95 can be displayed.

**App improvement:** Real zero remains zero. Missing samples cannot produce a supported p95, and missing baselines cannot produce a numeric deviation.

```ts
import type { components } from '../core/api/schema';

type Window = components['schemas']['ServiceKpiWindow'];
type Detection = components['schemas']['ServiceDetection'];
export type Kpi = Window['kpis'][number];

export function primaryMetric(service: 'VOLTE' | 'SMS', kpis: Kpi[]): Kpi | undefined {
  const name = service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs';
  return kpis.find(kpi => kpi.name === name);
}

export function supportedValue(
  kpi: Kpi | undefined,
  kpis: Kpi[],
  quality: Window['quality'] = 'COMPLETE',
): number | null {
  if (!kpi || quality === 'MISSING' || kpi.observed === null
    || !Number.isFinite(kpi.observed)) return null;
  if ((kpi.unit === 'PERCENT' || kpi.unit === 'RATIO') && kpi.denominator === 0) return null;
  if (kpi.name === 'p95DeliveryMs') {
    const completed = kpis.find(item => item.name === 'deliveredMessages' && item.unit === 'COUNT')?.observed;
    if (completed === null || completed === undefined || !Number.isFinite(completed) || completed <= 0) return null;
  }
  return kpi.observed;
}

export function deviation(kpi: Kpi | undefined, observed: number | null): string {
  if (!kpi || observed === null || kpi.baseline === null
    || !Number.isFinite(kpi.baseline)) return 'Comparison unavailable';
  const change = Math.round((observed - kpi.baseline) * 100) / 100;
  const units: Record<string, string> = {
    PERCENT: 'pp', PERCENTAGE_POINTS: 'pp', MILLISECONDS: 'ms', SECONDS: 's',
    COUNT: 'count', RATIO: 'ratio', MBPS: 'Mbps',
  };
  return `${change > 0 ? '+' : ''}${change} ${units[kpi.unit] ?? kpi.unit}`;
}

export function metricLabel(name: string): string {
  const names: Record<string, string> = {
    cssrPct: 'Call setup success rate', p95DeliveryMs: 'Delivery p95',
    deliveredMessages: 'Completed messages', queueDepth: 'Queued messages',
    oldestPendingAgeSec: 'Oldest queued-message age',
    rrcSuccessPct: 'Radio connection success rate',
    bearerSuccessPct: 'Bearer setup success rate',
  };
  return names[name] ?? name;
}

export function rankValue(detection: Detection): string {
  const rank = detection.anomalyRank;
  return detection.mlStatus === 'OK' && rank !== null && Number.isFinite(rank)
    ? String(rank) : 'Unavailable';
}

export function modelStatusLabel(status: Detection['mlStatus']): string {
  return {
    OK: 'Model result available', TIMEOUT: 'Model response timed out',
    UNAVAILABLE: 'Model service unavailable',
    INSUFFICIENT_DATA: 'Not enough eligible data for the model',
    NOT_APPLICABLE: 'Model does not apply to this update',
  }[status];
}
```

`supportedValue` is a display guard. It does not rewrite the response or recalculate detection results. `INCOMPLETE` may retain independently measured queue values; `MISSING` cannot support an observed metric. The backend remains responsible for validating counters and calculation eligibility.

### Create `apps/dashboard/src/app/shared/metric-presentation.spec.ts`

```ts
import { voiceIncidents, voiceWindows } from '../../fixtures/voice';
import { deviation, rankValue, supportedValue, type Kpi } from './metric-presentation';

describe('Nullable metric presentation', () => {
  const rate: Kpi = { ...voiceWindows[0].kpis[0], observed: 96, baseline: 98 };
  const p95: Kpi = { name: 'p95DeliveryMs', observed: 45000, baseline: 2000,
    unit: 'MILLISECONDS', numerator: null, denominator: null };
  const count = (observed: number | null): Kpi => ({ ...p95,
    name: 'deliveredMessages', unit: 'COUNT', observed, baseline: null });

  it('uses percentage points and preserves a real zero baseline', () => {
    expect(deviation(rate, 96)).toBe('-2 pp');
    expect(deviation({ ...rate, baseline: null }, 96)).toBe('Comparison unavailable');
    expect(deviation({ ...rate, baseline: 0 }, 0)).toBe('0 pp');
    expect(supportedValue({ ...rate, observed: 0 }, [rate])).toBe(0);
    expect(supportedValue({ ...rate, denominator: 0 }, [rate])).toBeNull();
  });

  it('supports p95 only with completed samples and retains independent queue evidence', () => {
    expect(supportedValue(p95, [p95, count(0)])).toBeNull();
    expect(supportedValue(p95, [p95, count(null)])).toBeNull();
    expect(supportedValue(p95, [p95, count(5)])).toBe(45000);
    expect(supportedValue(p95, [p95, count(5)], 'MISSING')).toBeNull();
    const queue: Kpi = { ...count(0), name: 'queueDepth' };
    expect(supportedValue(queue, [queue], 'INCOMPLETE')).toBe(0);
  });

  it('keeps zero model rank distinct from missing or failed model results', () => {
    const record = voiceIncidents[0].latestDetection;
    expect(rankValue({ ...record, mlStatus: 'OK', anomalyRank: 0 })).toBe('0');
    expect(rankValue({ ...record, mlStatus: 'OK', anomalyRank: null })).toBe('Unavailable');
    expect(rankValue({ ...record, mlStatus: 'TIMEOUT', anomalyRank: 0.82 })).toBe('Unavailable');
  });
});
```

### First commit checkpoint

From `apps/dashboard`:

```bash
npm test -- --include='src/app/shared/metric-*.spec.ts'
npm run build
```

From the repository root:

```bash
git add apps/dashboard/src/app/shared/metric-explanation.component.ts apps/dashboard/src/app/shared/metric-explanation.component.spec.ts apps/dashboard/src/app/shared/metric-presentation.ts apps/dashboard/src/app/shared/metric-presentation.spec.ts
git diff --cached --check
git commit -m "feat(dashboard): add contextual metric explanations"
```

Commit when the new checks and build pass. Do not merge yet.

## 6. Explain metrics where analysts read them

For each Angular component that uses `<app-metric-explanation>`, add this import and add `MetricExplanationComponent` to its existing decorator's `imports` array. Keep its current imports, icons, styles, and layout.

```ts
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';
```

All feature-component paths below are two levels below `shared`, so that import works in each of them.

### Change `apps/dashboard/src/app/features/service-kpi-history/sample-volume.component.ts`

Replace this small component completely:

```ts
import { Component, input } from '@angular/core';
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';

@Component({
  selector: 'app-sample-volume',
  imports: [MetricExplanationComponent],
  template: `
    <p><strong>Completed-message samples:</strong> {{ count() ?? 'Unavailable' }}</p>
    @if (count() === null) {
      <app-metric-explanation topic="sample-count-missing" mode="state" />
    } @else if (count() === 0) {
      <app-metric-explanation topic="no-samples" mode="state" />
    } @else if (count()! < 30) {
      <app-metric-explanation topic="low-volume" mode="state" />
    }
  `,
})
export class SampleVolumeComponent {
  readonly count = input<number | null>(null);
}
```

**Why?** Null, zero, and a small positive sample count need different explanations.

**App improvement:** A missing count does not become “no messages,” and a zero count does not become “fast delivery.”

### Change `apps/dashboard/src/app/features/service-kpi-history/sms-quality.component.ts`

Add `MetricExplanationComponent` to its imports as described above. Replace its existing paragraph that starts “Delivery p95 describes…” with:

```html
<app-metric-explanation topic="p95" />
@if (baseline() === null) {
  <app-metric-explanation topic="baseline-missing" mode="state" />
}
```

Inside the existing `@if (window(); as window)` block, immediately after the observation time/quality paragraph, add:

```html
@if (freshness() === 'STALE') {
  <app-metric-explanation topic="stale-evidence" mode="state" />
} @else if (freshness() === 'MISSING' || window.quality === 'MISSING') {
  <app-metric-explanation topic="missing-evidence" mode="state" />
}
```

Keep the current `freshness() !== 'FRESH'` warning, queue/backlog warnings, calculations, and `<app-sample-volume>` call. In the no-window `@else`, keep the existing “No SMS observation available” text and add:

```html
<app-metric-explanation topic="missing-evidence" mode="state" />
```

**Why?** p95 excludes pending messages and needs sample context; an absent expected value must remain absent.

**App improvement:** The analyst can see a queue problem even when delivery p95 is unavailable, and gets a next step for stale or missing measurements.

### Change `apps/dashboard/src/app/features/service-kpi-history/sms-history.component.ts`

Add the shared explanation component to its imports. Immediately before the table scroll container, add:

```html
<app-metric-explanation topic="p95" />
```

Keep its Day 15 table pages and current `p95`, `value`, and `baseline` methods. Replace the empty table cell with:

```html
<td colspan="7">No SMS history in this interval. Try a nearby range or refresh the service. No returned windows does not prove healthy delivery.</td>
```

**Why?** A successful empty range differs from an HTTP failure or a measured zero.

**App improvement:** Analysts get a next step while all historical table values remain reachable.

### Change `apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts`

Add the shared explanation component to its imports. Add this computed member to the existing class:

```ts
readonly missingBaseline = computed(() => this.rows().some(row => cssr(row)?.baseline === null));
```

After the existing CSSR explanation paragraph, add:

```html
<app-metric-explanation topic="percentage-points" />
@if (missingBaseline()) {
  <p class="helper">Some windows have no expected success-rate value. Actual measurements and gaps are preserved.</p>
  <app-metric-explanation topic="baseline-missing" mode="state" />
}
```

Replace its empty `@else` message with:

```html
@else {
  <p role="status">No voice KPI history in this time range. Try a nearby range or refresh the service. An empty history cannot confirm recovery.</p>
}
```

Keep keyboard navigation, SVG paths, hover behavior, pagination, and null gaps. Do not draw a zero expected line when the baseline is null.

**Why?** The chart uses percent, while the service's change in that rate uses percentage points.

**App improvement:** A difference such as `-2 pp` is understandable, and absent expected data cannot look like a real 0% baseline.

### Change `apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts`

Add the shared explanation component to its imports. Add:

```ts
import { primaryMetric, supportedValue } from '../../shared/metric-presentation';
```

Inside `summary`, replace the current `const values = rows.map(...)` statement with:

```ts
const values = rows.map(row => supportedValue(
  primaryMetric(sms ? 'SMS' : 'VOLTE', row.kpis), row.kpis, row.quality,
));
```

Remove `observed` from the `voice-model` import if it is no longer used. Keep the rest of `summary`, including its bounded rows and sparkline logic.

Immediately after `.range-caption`, inside the existing loaded `item` block, add:

```html
@if (!item.latestWindow || item.latestWindow.quality === 'MISSING' || item.freshness === 'MISSING') {
  <app-metric-explanation topic="missing-evidence" mode="state" />
} @else if (item.freshness === 'STALE') {
  <app-metric-explanation topic="stale-evidence" mode="state" />
}
@if (item.scope.service === 'VOLTE') {
  <app-metric-explanation topic="percentage-points" />
}
```

The summary's “Latest” metric is the last window in the **selected range**, not necessarily the current service observation. Change its existing conditional label to:

```html
{{ item.scope.service === 'VOLTE' ? 'Last success rate in selected range' : 'Last delivery p95 in selected range' }}
```

**Why?** A selected historical range is different from current service health.

**App improvement:** Historical values stay useful without being presented as current measurements. SMS summary values follow the same completed-sample rule as the SMS panel.

### Change `apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts`

Replace this component with:

```ts
import { Component, input } from '@angular/core';
import type { components } from '../../core/api/schema';
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';
import { modelStatusLabel, rankValue } from '../../shared/metric-presentation';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-cause-evidence',
  imports: [MetricExplanationComponent],
  template: `
    <section aria-label="Cause hypothesis">
      <h4>Cause hypothesis</h4>
      <p>{{ detection().probableCause }}</p>
      <p>Cause confidence: {{ detection().causeConfidence }}</p>
      <p>This is a probable explanation, not a confirmed root cause.</p>
      <app-metric-explanation topic="confidence" />
      <p>ML result: {{ statusLabel(detection().mlStatus) }}</p>
      <p>Model anomaly rank: {{ rank(detection()) }}</p>
      <app-metric-explanation topic="rank" />
      @if (detection().mlStatus !== 'OK' || detection().anomalyRank === null) {
        <app-metric-explanation topic="ml-unavailable" mode="state" />
      }
      <h4>Recommended checks</h4>
      <ul>
        @for (check of detection().recommendedChecks; track $index) { <li>{{ check }}</li> }
        @empty { <li>No checks supplied for this update. Inspect the measured evidence or ask the service owner for a next step.</li> }
      </ul>
    </section>
  `,
})
export class CauseEvidenceComponent {
  readonly detection = input.required<Detection>();
  readonly statusLabel = modelStatusLabel;
  readonly rank = rankValue;
}
```

**Why?** A model timeout, a missing score, and a real zero score are different states.

**App improvement:** Rank keeps its original 0–1 scale and is never formatted as a probability. Plain-language model status tells the analyst why no result is available.

### Change `apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts`

Add `MetricExplanationComponent` and `SampleVolumeComponent` to the existing imports array, and add:

```ts
import { SampleVolumeComponent } from '../service-kpi-history/sample-volume.component';
import { metricLabel, primaryMetric, supportedValue, type Kpi } from '../../shared/metric-presentation';
```

Add these members to the class, keeping its existing input and `ordered` computation:

```ts
readonly label = metricLabel;

observedFor(detection: Detection, kpi: Kpi): number | null {
  return supportedValue(kpi, detection.kpis, detection.phase === 'UNKNOWN' ? 'MISSING' : 'COMPLETE');
}

baselineMissing(detection: Detection): boolean {
  return primaryMetric(detection.service, detection.kpis)?.baseline === null;
}

completedSamples(detection: Detection): number | null {
  const kpi = detection.kpis.find(item => item.name === 'deliveredMessages' && item.unit === 'COUNT');
  return supportedValue(kpi, detection.kpis, detection.phase === 'UNKNOWN' ? 'MISSING' : 'COMPLETE');
}
```

Change the KPI name cell and Actual value cell; keep `data-kpi`, all other columns, and raw recorded units:

```html
<th scope="row">{{ label(kpi.name) }}</th>
<td>{{ observedFor(detection, kpi) ?? 'Unavailable' }}</td>
```

Before the evidence table, add:

```html
@if (baselineMissing(detection)) {
  <app-metric-explanation topic="baseline-missing" mode="state" />
}
@if (detection.service === 'SMS') {
  <app-sample-volume [count]="completedSamples(detection)" />
  <app-metric-explanation topic="p95" />
} @else {
  <app-metric-explanation topic="percentage-points" />
}
```

After the existing “Attempts and messages are not unique customers” helper, add:

```html
@if (detection.service === 'VOLTE') {
  <app-metric-explanation topic="failed-attempts" />
}
```

Replace its empty detection-history message with:

```html
<p role="status">No evidence updates available on this page. Refresh the incident or return to its first evidence page. This does not prove recovery.</p>
```

Keep the existing `UNKNOWN` explanation that impact retains its last evaluated value. Do not recalculate `impact.extraFailedAttempts` in the browser or describe an UNKNOWN update's retained impact as a new evaluation.

**Why?** Evidence is read one update at a time, including its own baseline, samples, and hypothesis.

**App improvement:** An analyst can understand each record without confusing another window's evidence with this one. A missing actual value stays unavailable; independently observed queue values stay visible on non-UNKNOWN updates.

### Change `apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts`

Add:

```ts
import { deviation, metricLabel, primaryMetric, supportedValue } from '../../shared/metric-presentation';
```

Replace only the existing `latestDeviation` computed member:

```ts
readonly latestDeviation = computed(() => {
  const detection = this.incident()?.latestDetection;
  if (!detection || detection.phase === 'UNKNOWN') return 'Comparison unavailable';
  const kpi = primaryMetric(detection.service, detection.kpis);
  const actual = supportedValue(kpi, detection.kpis);
  if (!kpi || actual === null || kpi.baseline === null) return 'Comparison unavailable';
  return `${metricLabel(kpi.name)}: ${deviation(kpi, actual)}`;
});
```

Keep all live refresh, drawer, assignment, comment, audit, and paging logic. The current summary already explains severity and technical recovery. The evidence column now supplies contextual metric help through its child component.

**Why?** The old code selects the first comparable KPI, which may not be the service's main metric.

**App improvement:** The summary consistently compares CSSR for voice or delivery p95 for SMS, with percentage points for rate differences and milliseconds for delay differences.

## 7. Make unknown comparisons visible in the overview

### Change `apps/dashboard/src/app/features/service-overview/service.store.ts`

Add:

```ts
import { primaryMetric, supportedValue } from '../../shared/metric-presentation';
```

Inside `health`, immediately **after** the existing `STALE` return and before `hasDegradedKpi`, insert:

```ts
const window = service.latestWindow;
const main = primaryMetric(service.scope.service, window.kpis);
if (!main || main.baseline === null || window.quality !== 'COMPLETE'
  || supportedValue(main, window.kpis, window.quality) === null) {
  return 'UNKNOWN';
}
```

Keep the existing degradation comparisons after this guard. This fixes an unsupported “normal” display; it does not change server detection thresholds or incident severity.

Replace the `UNKNOWN` message in `healthExplanation` with:

```ts
UNKNOWN: 'Service health is unknown: current measurements, sample support, or an expected value are unavailable. Open the service evidence to check what is missing.',
```

**Why?** The existing loop skips null baselines and can then default to `NORMAL` without a supported main comparison.

**App improvement:** The overview cannot count that service as “Within baseline.” Missing expectations and missing telemetry remain distinct explanations on the detail screen.

### Change `apps/dashboard/src/app/features/service-overview/service-overview.component.ts`

Add:

```ts
import { primaryMetric, supportedValue } from '../../shared/metric-presentation';
```

Replace only `mainMetric`:

```ts
mainMetric(service: Parameters<ServiceStore['health']>[0]): string {
  const window = service.latestWindow;
  if (!window) return 'Unavailable';
  const kpi = primaryMetric(service.scope.service, window.kpis);
  const actual = supportedValue(kpi, window.kpis, window.quality);
  return kpi ? this.metric(actual, kpi.unit) : 'Unavailable';
}
```

Keep the current unit formatter, scope filtering, and summary cards. To give the overview's unknown state an immediate next step, add the following inside each service row's existing health/data-status cell in `service-overview.component.html`:

```html
@if (health(service) === 'UNKNOWN' || health(service) === 'STALE') {
  <p class="helper">{{ healthExplanation(service) }}</p>
}
```

For selected-scope metrics, keep their source values visible as evidence. Do not interpret them as current healthy service while the service is UNKNOWN or STALE; the health explanation remains visible above them.

## 8. Update the regression checks before committing

### Change `apps/dashboard/src/app/features/service-overview/service.store.spec.ts`

Add this test inside its existing `describe`:

```ts
it('does not call missing baselines or unsupported samples normal', () => {
  TestBed.configureTestingModule({ providers: [{ provide: TelecomClient, useValue: {} }] });
  const store = TestBed.inject(ServiceStore);
  const voice = structuredClone(services[0]) as ServiceSummary;
  const rate = voice.latestWindow!.kpis.find(kpi => kpi.name === 'cssrPct')!;
  rate.baseline = null;
  expect(store.health(voice)).toBe('UNKNOWN');
  rate.baseline = 99.3;
  rate.denominator = 0;
  expect(store.health(voice)).toBe('UNKNOWN');
  const sms = structuredClone(services[1]) as ServiceSummary;
  sms.latestWindow!.kpis.find(kpi => kpi.name === 'deliveredMessages')!.observed = 0;
  expect(store.health(sms)).toBe('UNKNOWN');
});
```

Keep the existing tests for normal/degraded/stale/unknown fixtures and request failures.

### Change `apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.spec.ts`

Its existing raw-ML-status assertion must follow the new human label. Replace:

```ts
expect(hypothesis.textContent).toContain('ML result: Model service unavailable');
```

Use that line **in place of** the old assertion containing `ML status: UNAVAILABLE`. Keep the existing rank, zero/null, severity, confidence, recovery, source-evidence, and SMS-impact checks. Their numeric cells and recorded units remain unchanged for supported measurements.

Add this test inside its existing `describe`:

```ts
it('explains absent baseline and refuses unsupported p95 even if a value was supplied', () => {
  const record = detection(1, 'OPEN');
  record.service = 'SMS';
  record.kpis = [
    { name: 'p95DeliveryMs', observed: 45000, baseline: null, unit: 'MILLISECONDS', numerator: null, denominator: null },
    { name: 'deliveredMessages', observed: 0, baseline: null, unit: 'COUNT', numerator: null, denominator: null },
    { name: 'queueDepth', observed: 250, baseline: null, unit: 'COUNT', numerator: null, denominator: null },
  ];
  const fixture = render([record]);
  const article: HTMLElement = fixture.nativeElement.querySelector('article');
  expect(article.querySelector('[data-kpi="p95DeliveryMs"] td')?.textContent?.trim()).toBe('Unavailable');
  expect(article.querySelector('[data-kpi="queueDepth"] td')?.textContent?.trim()).toBe('250');
  expect(article.textContent).toContain('Expected value unavailable');
  expect(article.textContent).toContain('No completed messages in this window');
});
```

This deliberately inconsistent input checks the frontend's defensive display rule. A valid backend zero-completion observation must supply null p95; Denis verifies that contract behavior separately.

### Keep existing SMS and workflow regressions

The current `sms-quality.component.spec.ts` already checks 29 versus 30 samples, unknown counts, zero completions with backlog, missing queue evidence, and stale/missing windows. Keep it. Keep Day 13 reconnect/session tests and Day 14 workflow tests.

The existing `service-explanations.spec.ts` also checks rank separately from confidence/severity. Its fixtures should continue to return null rank for failed model results. Do not weaken that test to make misleading UI pass.

### Second commit checkpoint

From `apps/dashboard`:

```bash
npm test
npm run build
```

From the repository root:

```bash
git add apps/dashboard/src/app/features/service-kpi-history/sample-volume.component.ts apps/dashboard/src/app/features/service-kpi-history/sms-quality.component.ts apps/dashboard/src/app/features/service-kpi-history/sms-history.component.ts apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.spec.ts apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts apps/dashboard/src/app/features/service-overview/service.store.ts apps/dashboard/src/app/features/service-overview/service.store.spec.ts apps/dashboard/src/app/features/service-overview/service-overview.component.ts apps/dashboard/src/app/features/service-overview/service-overview.component.html
git diff --cached --check
git commit -m "feat(dashboard): clarify metric states and unknown comparisons"
```

Commit when all unit tests and the build pass. Do not merge yet.

## 9. Test the five states in the browser

### Create `apps/dashboard/tests/e2e/specs/terminology.spec.ts`

**Why?** Unit checks cannot prove that the explanations are connected to the actual screens or usable with a keyboard.

**App improvement:** The five exceptional states are checked on rendered pages, and rank remains a number rather than a displayed probability.

```ts
import { test, expect, type Page } from '@playwright/test';
import services from '../../../src/fixtures/services.json';
import { voiceIncidents } from '../../../src/fixtures/voice';
import type { ServiceSummary } from '../../../src/app/core/api/telecom-client';

type Case = {
  id: string; service: 'VOLTE' | 'SMS'; topic: string;
  missing?: boolean; noBaseline?: boolean; samples?: number;
  stale?: boolean; modelTimeout?: boolean;
};
const cases: Case[] = [
  { id: 'missing-observation', service: 'VOLTE', topic: 'missing-evidence', missing: true },
  { id: 'absent-baseline', service: 'VOLTE', topic: 'baseline-missing', noBaseline: true },
  { id: 'low-volume', service: 'SMS', topic: 'low-volume', samples: 5 },
  { id: 'model-unavailable', service: 'VOLTE', topic: 'ml-unavailable', modelTimeout: true },
  { id: 'stale-evidence', service: 'SMS', topic: 'stale-evidence', stale: true },
];

async function setup(page: Page, scenario: Case) {
  const summary = structuredClone((services as ServiceSummary[])
    .find(item => item.scope.service === scenario.service && item.latestWindow !== null)!);
  summary.freshness = scenario.stale ? 'STALE' : scenario.missing ? 'MISSING' : 'FRESH';
  const window = summary.latestWindow!;
  const primaryName = scenario.service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs';
  if (scenario.noBaseline) window.kpis.find(kpi => kpi.name === primaryName)!.baseline = null;
  if (scenario.samples !== undefined) {
    window.kpis.find(kpi => kpi.name === 'deliveredMessages')!.observed = scenario.samples;
    if (scenario.samples === 0) window.kpis.find(kpi => kpi.name === 'p95DeliveryMs')!.observed = null;
  }
  const item = structuredClone(voiceIncidents[0]);
  item.service = scenario.service;
  item.scopeId = summary.scope.scopeId;
  item.latestSequence = 1;
  item.latestDetection = {
    ...item.latestDetection, service: scenario.service, scopeId: summary.scope.scopeId,
    sequence: 1, anomalyType: scenario.service === 'VOLTE' ? 'VOLTE_SETUP_DEGRADATION' : 'SMS_DELIVERY_DELAY',
    kpis: structuredClone(window.kpis), windowStart: window.windowStart, windowEnd: window.windowEnd,
    mlStatus: scenario.modelTimeout ? 'TIMEOUT' : 'OK',
    anomalyRank: scenario.modelTimeout ? null : 0, modelVersion: scenario.modelTimeout ? null : 'test-model',
  };
  if (scenario.missing) summary.latestWindow = null;
  await page.addInitScript(() => {
    class QuietSource {
      onopen = null;
      onerror = null;
      addEventListener() {}
      close() {}
    }
    (window as any).EventSource = QuietSource;
  });
  await page.route('**/api/auth/me', route => route.fulfill({ json: {
    analystId: 'day16-review', displayName: 'Day 16 reviewer', roles: ['ANALYST'],
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
  } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: {
    token: 'test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }));
  await page.route('**/api/services', route => route.fulfill({ json: [summary] }));
  await page.route('**/api/services/*/kpis?**', route => route.fulfill({ json: {
    items: scenario.missing ? [] : [window], total: scenario.missing ? 0 : 1,
    page: 0, size: 100, observedAt: window.windowEnd,
  } }));
  await page.route('**/api/incidents?**', route => route.fulfill({ json: {
    items: [item], total: 1, page: 0, size: 20,
  } }));
  await page.route('**/api/incidents/*', route => route.fulfill({ json: item }));
  await page.route('**/api/incidents/*/detections?**', route => route.fulfill({ json: {
    items: [item.latestDetection], total: 1, page: 0, size: 20,
  } }));
  await page.route('**/api/incidents/*/timeline?**', route => route.fulfill({ json: {
    items: [], total: 0, page: 0, size: 100,
  } }));
  await page.route('**/api/analysts?**', route => route.fulfill({ json: [] }));
  return { summary, item };
}

test.describe('Day 16 terminology', () => {
  test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled UI cases; human acceptance uses normal login separately');

  for (const width of [1366, 390]) for (const scenario of cases) {
    test(`${scenario.id} at ${width}px has an explanation and next action`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 });
      const { summary, item } = await setup(page, scenario);
      if (scenario.noBaseline) {
        await page.goto('/dashboard');
        await expect(page.locator('.service-row')).toContainText('UNKNOWN');
        await expect(page.locator('.service-row')).toContainText('expected value');
        await page.getByRole('link', { name: summary.scope.scopeId, exact: true }).click();
      } else {
        await page.goto(scenario.modelTimeout ? `/incidents/${item.id}` : `/services/${summary.scope.scopeId}`);
      }
      if (scenario.modelTimeout) {
        await page.locator('.cause-details > summary').click();
        await expect(page.locator('[aria-label="Cause hypothesis"]')).toContainText('Model response timed out');
        await expect(page.locator('[aria-label="Cause hypothesis"]')).toContainText('Model anomaly rank: Unavailable');
      }
      const state = page.locator(`aside[data-topic="${scenario.topic}"]`).first();
      await expect(state).toBeVisible();
      await expect(state.locator('strong')).not.toBeEmpty();
      await expect(state.locator('p').last()).not.toBeEmpty();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    });
  }

  test('preserves zero rank and provides help through the keyboard', async ({ page }) => {
    const { item } = await setup(page, { id: 'zero-rank', service: 'VOLTE', topic: 'rank' });
    await page.goto(`/incidents/${item.id}`);
    await page.locator('.cause-details > summary').click();
    const cause = page.locator('[aria-label="Cause hypothesis"]');
    await expect(cause).toContainText('Model anomaly rank: 0');
    await expect(cause).not.toContainText('Model anomaly rank: 0%');
    const details = cause.locator('details[data-topic="rank"]');
    await details.locator('summary').focus();
    await page.keyboard.press('Enter');
    await expect(details).toHaveAttribute('open', '');
    await expect(details).toContainText('not a failure probability');
    await page.keyboard.press('Enter');
    await expect(details).not.toHaveAttribute('open');
  });

  test('keeps queue evidence visible when no messages completed', async ({ page }) => {
    const { summary } = await setup(page, { id: 'zero-samples', service: 'SMS', topic: 'no-samples', samples: 0 });
    await page.goto(`/services/${summary.scope.scopeId}`);
    await expect(page.locator('app-sms-quality')).toContainText('No completed messages in this window');
    await expect(page.locator('app-sms-quality')).toContainText('Delivery p95 Unavailable');
    await expect(page.locator('app-sms-quality')).toContainText('250 messages');
  });
});
```

These cases test UI wiring; their mock stream deliberately emits no events. Keep the separate reconnect tests for live behavior. Passing controlled cases does not substitute for a teammate correctly explaining the real app.

### Run the checks

From `apps/dashboard`:

```bash
npm test
npm run build
npm run test:e2e -- tests/e2e/specs/terminology.spec.ts tests/e2e/specs/service-explanations.spec.ts tests/e2e/specs/evidence-timeline.spec.ts tests/e2e/specs/reconnect.spec.ts --workers=1
```

Use an unused `E2E_PORT` if needed. Install Playwright's Chromium through normal project setup if it is not installed. Run the existing Day 15 `resource-bounds.spec.ts` before merging as well; the new explanations add DOM nodes and must fit the accepted browser budget.

## 10. Create the terminology and acceptance document

### Create `docs/ux/terminology.md`

**Why?** The displayed wording needs one reviewed meaning and a record of the teammate's actual interpretation.

**App improvement:** Future changes can preserve the same units, nullable-field behavior, and analyst expectations.

Paste this document, then fill in real review results. Keep pending entries until the review occurs.

```md
# Dashboard terminology — Day 16

Planned date: 2026-10-06
Commit reviewed: pending
Metric reviewer: Sergiu — pending
Nullable-field reviewer: Denis — pending
Non-telecom teammate: pending
Review date/environment: pending
Status: pending human acceptance

## Percent and percentage points

Percent is a rate. Percentage points (pp) are the arithmetic difference between
two percentage rates. From 98% to 96% means -2 pp, approximately -2.04% relative
to 98%. Dashboard rate deviations use pp. Relative percent needs a nonzero
reference value and is not displayed as the same quantity.

## Delivery p95 and sample volume

Delivery p95 is the delay at or below which 95% of completed-message samples fall.
It is not the average delay. Pending messages are excluded, so inspect queue
depth and oldest queued-message age too. Do not average per-window p95 values.

Zero completed samples means p95 cannot be measured. A null completed count
means the sample count is unknown, not zero. The existing warning below 30
completed messages is a UI caution, not a new model/detection eligibility rule
or a reliability guarantee at 30. Sergiu must approve its wording.

## Estimated extra failed attempts

The backend estimates failures above its expected baseline using the recorded
window counters: max(0, expectedPercent * attempts / 100 - successfulAttempts).
For counters consistent with 96% actual and 98% expected across 1,000 attempts,
the estimate is 20. Do not recompute it from rounded display values in the UI.
An attempt is not a unique customer. Aggregate observations cannot identify
unique customers. UNKNOWN updates can retain the last evaluated impact;
retained impact is not a new evaluation or proof of current health.

## Severity, model rank, and cause confidence

Severity describes rule-based service-impact priority: MEDIUM, HIGH, CRITICAL.
Model anomaly rank compares unusualness with normal calibration data on a
0–1 scale. Rank 0.82 is not an 82% fault probability. A valid zero rank is zero;
a missing/failed result is unavailable. No new severity is inferred from rank.

Cause confidence describes support for a hypothesis: LOW, MEDIUM, HIGH.
It is not a calibrated probability, proof, or severity. Recommended checks
help confirm or reject the hypothesis.

## Five exceptional states and next actions

1. Missing observations: current measurements unavailable. Refresh and check
   the telemetry source. Missing data cannot establish recovery.
2. Absent baseline: expected value unavailable. Keep a supported actual value
   visible; ask the baseline owner about the scope/time coverage. Baseline zero
   is a real value, not missing.
3. Low volume: interpret p95 cautiously and inspect nearby windows/queue evidence.
   Keep low positive, zero, and unknown counts distinct.
4. ML unavailable: show the reason and no rank. Continue with measured KPIs,
   rule-based severity, and recommended checks; escalate persistent model issues.
5. Stale evidence: retain historical values and explicitly mark current health
   unknown. Refresh/check telemetry before declaring recovery.

The server's freshness field drives stale/current labels. An old historical
timeline record is not automatically stale for its own window. A disconnected
live stream means live refresh is interrupted; do not manufacture server
freshness or recovery from a client timestamp or connection status.

## Null and zero rules — Denis

- observed/baseline/numerator/denominator null display as unavailable.
- Real numeric zero stays zero when the metric is supported.
- Zero rate denominator cannot support a success rate.
- Unknown/zero completed samples cannot support delivery p95.
- Independently observed queue values remain useful on incomplete windows.
- No baseline means no numeric comparison or unsupported normal label.
- Model status is respected; failed ML cannot display a leftover usable rank.
- Existing permissions, versions, pagination, and SSE behavior remain enforced.

## Teammate explanation exercise

Use the app, with its contextual help, without developer tools or verbal coaching.
Record what the teammate says before revealing the expected interpretation.

- Missing observation: teammate's explanation/action: pending
- Absent baseline: teammate's explanation/action: pending
- Low volume: teammate's explanation/action: pending
- ML unavailable: teammate's explanation/action: pending
- Stale evidence: teammate's explanation/action: pending
- 98% → 96%: teammate distinguishes -2 pp from relative change: pending
- p95 versus average and pending messages: pending
- Estimated failures versus unique customers: pending
- Rank 0.82 versus probability, severity, and confidence: pending
- Zero versus unavailable: pending

## Acceptance and defects

Sergiu's metric wording/sign-off: pending
Denis's nullable-field/sign-off: pending
Teammate correctly interprets all five cases and rank: pending
Keyboard/touch readability: pending
Day 15 browser resource budget regression: pending
Remaining defects, owner, and target date: pending
Final team acceptance: pending
```

The extra-failures formula above comes from `VoiceSetupRule`; model-rank meaning comes from `services/ml-service/README.md` and its calibration scorer. Review these local sources when changing the wording.

## 11. Manual walkthrough and handoff

1. Sign in normally. Open a voice scope and expand “Percent or percentage points?” Read a rate and its pp trend correctly.
2. Open SMS evidence. Read p95 together with completed samples, queue depth, and oldest age. Check unknown, zero, 5, 29, and 30 completed samples.
3. Inspect an absent-baseline case. A supported actual value stays visible, but comparison and an unsupported “normal” classification are unavailable.
4. Inspect a model timeout/unavailable result. Read its reason, continue with other evidence, and confirm there is no invented zero rank.
5. Inspect stale and missing service observations. Read the visible next action. Historical values cannot establish current health.
6. Open an incident's cause details. Read severity, rank, and confidence separately. Check valid rank zero and null rank.
7. Read estimated extra failed attempts. Explain that this is an estimate relative to the baseline and not a unique-customer count.
8. Use the keyboard to open/close native help. Verify visible states and readable text at a narrow viewport.
9. Ask a teammate unfamiliar with telecom to explain the five cases and what they would do next. Let them use the help; do not coach the answers.
10. Record actual answers and defects in `docs/ux/terminology.md`. Correct confusing copy and repeat the affected question.

**Handoff:** Sergiu approves metric explanations; Denis verifies nullable fields and current backend meanings. Team acceptance is recorded after the teammate exercise. This guide does not send messages to them or assume their approval.

## 12. Final commit, review, and merge

After tests pass and actual review results are recorded, run from the repository root:

```bash
git add apps/dashboard/tests/e2e/specs/terminology.spec.ts docs/ux/terminology.md Guides/Day-16-polish-terminology-and-empty-states.md
git diff --cached --check
git diff --cached --stat
git commit -m "test(dashboard): verify terminology and record usability acceptance"
git push -u origin codex/day-16-terminology-empty-states
```

Open a pull request into `main` titled **“Day 16: polish terminology and empty states”**. Include:

- Screens/fields updated and the five explicit exceptional cases.
- Unit/build/browser/reconnect results.
- The change from unsupported NORMAL to UNKNOWN for missing comparisons.
- The reviewed 30-sample display caution and unchanged backend thresholds.
- The teammate's recorded interpretations and any remaining defects.
- Links to `docs/ux/terminology.md` and relevant recorded evidence.
- Sergiu's wording approval and Denis's nullable-field approval.

**Merge only when** checks pass, the Day 15 resource budget remains acceptable, Sergiu and Denis accept their review items, and the teammate correctly interprets all five cases without mistaking rank for probability. A populated glossary alone does not complete Day 16.

Use the repository's normal review/merge policy. After merge:

```bash
git switch main
git pull --ff-only origin main
```

## 13. Day 16 completion checklist

- [ ] Percentage points and relative percent are distinguished near rate changes.
- [ ] p95 is explained with completed sample count and queue context.
- [ ] Estimated extra failed attempts are distinguished from unique customers.
- [ ] Missing observations, absent baselines, low volume, unavailable ML, and stale evidence have visible labels and next actions.
- [ ] Zero, low positive, and unknown sample counts remain distinct.
- [ ] Zero baselines and valid zero rank remain real values.
- [ ] Missing/failed model results do not display a fabricated or leftover rank.
- [ ] Current severity, rank, confidence, and workflow/technical states remain separate.
- [ ] Unsupported comparisons do not produce a normal overview label.
- [ ] Keyboard/touch help works and Day 15 paging/resource behavior remains acceptable.
- [ ] Tests/build/reconnect checks pass.
- [ ] Teammate correctly explains all five cases and does not call rank a probability.
- [ ] Sergiu and Denis record actual approval; remaining defects have owners.
- [ ] Reviewed Day 16 pull request merged into `main`.
