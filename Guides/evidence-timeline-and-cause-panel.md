# Evidence timeline and cause panel

## Investigation requirements

Build an incident investigation view that:

- Displays immutable detection records in sequence.
- Shows actual KPI values beside baselines.
- Includes source scope and source evidence.
- Separates probable cause and confidence from observed evidence.
- Shows recommended checks.
- Keeps estimated failed attempts separate from unavailable unique customers.
- Lets analysts browse historical bad and recovery windows.

The screenshot’s older `features/incidents/` folder is now:

```text
apps/dashboard/src/app/features/incident-investigation/
```

The code below is proposed implementation code. Copy it into the indicated files and run the checks. No new dependency or API schema is needed.

## Task 2 — Create the cause panel

The existing `ServiceDetection` type already contains:

```text
probableCause
causeConfidence
recommendedChecks
impact
evidence
kpis
```

Reuse this type.

The following command creates the file and fills it with the complete code. You do not also need to run the Angular generator.

```sh
mkdir -p apps/dashboard/src/app/features/incident-investigation

cat > apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts <<'EOF'
import { Component, input } from '@angular/core';
import type { components } from '../../core/api/schema';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-cause-evidence',
  template: `
    <section aria-label="Cause hypothesis">
      <h4>Cause hypothesis</h4>

      <p>{{ detection().probableCause }}</p>

      <p>Cause confidence: {{ detection().causeConfidence }}</p>

      <p>
        This is a probable explanation, not a confirmed root cause.
        Confidence describes supporting evidence, not proof.
      </p>

      <p>ML status: {{ detection().mlStatus }}</p>

      <h4>Recommended checks</h4>

      <ul>
        @for (check of detection().recommendedChecks; track $index) {
          <li>{{ check }}</li>
        } @empty {
          <li>No checks supplied for this update.</li>
        }
      </ul>
    </section>
  `,
})
export class CauseEvidenceComponent {
  readonly detection = input.required<Detection>();
}
EOF
```

**Why do we add this?** The current page shows the probable cause beside measurements without clearly identifying it as a hypothesis.

**How does it make our app better?** Analysts can understand the proposed explanation and next checks without treating confidence as proof. ML status remains separate from cause confidence.

### Check and commit

If dependencies are missing, first run:

```sh
npm --prefix apps/dashboard ci
```

Then:

```sh
npm --prefix apps/dashboard run build

git diff --check
git add apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts
git diff --cached

git commit -m "feat(dashboard): distinguish cause hypotheses from evidence"
```

Commit only after the build passes.

## Task 3 — Create the evidence timeline

Create and populate the file:

```sh
cat > apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts <<'EOF'
import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { components } from '../../core/api/schema';
import { CauseEvidenceComponent } from './cause-evidence.component';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-evidence-timeline',
  imports: [DatePipe, CauseEvidenceComponent],
  styles: [`
    .evidence-table {
      overflow-x: auto;
    }

    table {
      width: 100%;
      border-collapse: collapse;
    }

    th, td {
      padding: 10px;
      text-align: left;
      border-bottom: 1px solid #d8e2dd;
    }

    code {
      overflow-wrap: anywhere;
    }

    article {
      margin-top: 20px;
    }
  `],
  template: `
    <h2>Evidence timeline</h2>

    <p>
      Accepted detection records, ordered by sequence from earliest to latest.
      Each update preserves the evidence recorded for its own window.
    </p>

    @for (detection of ordered(); track detection.detectionId) {
      <article
        class="detail-panel"
        [attr.data-detection-id]="detection.detectionId"
      >
        <h3>Update {{ detection.sequence }} · {{ detection.phase }}</h3>

        <p>
          {{ detection.windowStart | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          –
          {{ detection.windowEnd | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          UTC
        </p>

        <p>
          Server detected at
          {{ detection.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          UTC
        </p>

        <p>
          Source scope: {{ detection.scopeId }}
          · Service: {{ detection.service }}
        </p>

        <p>
          Technical state: {{ detection.technicalState }}
          · Severity: {{ detection.severity }}
        </p>

        @if (detection.phase === 'UNKNOWN') {
          <p class="notice">
            Evidence is incomplete. This update does not prove recovery;
            impact retains the last evaluated value.
          </p>
        }

        <h4>Observed evidence</h4>

        <div
          class="evidence-table"
          tabindex="0"
          role="region"
          [attr.aria-label]="'KPI evidence for update ' + detection.sequence"
        >
          <table>
            <caption>
              Measured KPIs for update {{ detection.sequence }}
            </caption>

            <thead>
              <tr>
                <th scope="col">KPI</th>
                <th scope="col">Actual</th>
                <th scope="col">Baseline</th>
                <th scope="col">Unit</th>
                <th scope="col">Numerator</th>
                <th scope="col">Denominator</th>
              </tr>
            </thead>

            <tbody>
              @for (kpi of detection.kpis; track kpi.name) {
                <tr [attr.data-kpi]="kpi.name">
                  <th scope="row">{{ kpi.name }}</th>
                  <td>{{ kpi.observed ?? 'Unavailable' }}</td>
                  <td>{{ kpi.baseline ?? 'Unavailable' }}</td>
                  <td>{{ kpi.unit }}</td>
                  <td>{{ kpi.numerator ?? 'Unavailable' }}</td>
                  <td>{{ kpi.denominator ?? 'Unavailable' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <h4>Estimated impact</h4>

        @if (detection.service === 'VOLTE') {
          <p>
            Estimated extra failed attempts:
            {{ detection.impact.extraFailedAttempts }}
          </p>
        } @else {
          <p>
            Affected delivered messages:
            {{ detection.impact.affectedDeliveredMessages }}
          </p>

          <p>Pending messages: {{ detection.impact.pendingMessages }}</p>
        }

        <p>
          Unique customers:
          {{ detection.impact.uniqueSubscribers ?? 'Unavailable' }}
        </p>

        <p>
          Attempts and messages are not unique customers.
          Aggregate observations do not identify distinct subscribers.
        </p>

        <p>
          Rules: {{ detection.rulesetVersion }}
          · Baseline: {{ detection.baselineVersion }}
          · Topology: {{ detection.topologyVersion }}
        </p>

        <details>
          <summary>Source evidence</summary>

          @for (evidence of detection.evidence; track $index) {
            <p>{{ evidence.summary }}</p>

            <p>
              Evidence code: {{ evidence.code }}
              · Node: {{ evidence.nodeId ?? 'Unavailable' }}
            </p>

            <ul>
              @for (id of evidence.sourceEventIds; track id) {
                <li><code>{{ id }}</code></li>
              } @empty {
                <li>No source event IDs supplied.</li>
              }
            </ul>
          } @empty {
            <p>No source evidence supplied.</p>
          }
        </details>

        <app-cause-evidence [detection]="detection" />
      </article>
    } @empty {
      <p role="status">No evidence updates available.</p>
    }
  `,
})
export class EvidenceTimelineComponent {
  readonly detections = input<Detection[]>([]);

  readonly ordered = computed(() =>
    [...this.detections()].sort((a, b) => a.sequence - b.sequence),
  );
}
EOF
```

**Why do we add this?** Historical records need to show their own measured values, scope and impact rather than borrowing the latest incident values.

**How does it make our app better?** Analysts can compare degraded and recovery windows, trace sources and distinguish failed attempts from unavailable customer counts.

The sort copies the array before sorting, preserving its original order and the detection records.

Use `sequence` for ordering because the detections endpoint specifies sequence ascending. Keep recovery and unknown records in the timeline.

Use `??` for missing numeric values. Using `||` would incorrectly replace measured zero.

### Check and commit

```sh
npm --prefix apps/dashboard run build

git diff --check
git add apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts
git diff --cached

git commit -m "feat(dashboard): add ordered detection evidence timeline"
```

## Task 4 — Connect the timeline and preserve pagination

The current incident page already loads historical detection pages. Reuse that flow.

**This command replaces the existing file.** Review any changes you or a teammate have made before running it. If your version differs, merge these changes into it instead of overwriting that work.

```sh
cat > apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts <<'EOF'
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, type Incident } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { dataSource } from '../../core/api/data-source';
import { EvidenceTimelineComponent } from './evidence-timeline.component';

@Component({
  selector: 'app-incident-detail',
  imports: [DatePipe, RouterLink, EvidenceTimelineComponent],
  template: `
    <h1>Incident investigation</h1>

    @if (loading()) {
      <p role="status">Loading incident evidence…</p>
    }

    @if (error()) {
      <section role="alert">
        <h2>Evidence unavailable</h2>
        <p>{{ error() }}</p>
        <button (click)="load()">Retry</button>
      </section>
    }

    @if (!loading() && !error() && incident(); as item) {
      <a [routerLink]="['/services', item.scopeId]">
        ← Back to service
      </a>

      <section class="detail-panel">
        <h2>{{ item.scopeId }}</h2>

        <p>
          Technical state: <strong>{{ item.technicalState }}</strong>
          · Workflow state: <strong>{{ item.status }}</strong>
        </p>

        <p>Episode: <code>{{ item.episodeId }}</code></p>

        <p>
          First observed
          {{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          UTC
          · Last observed
          {{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          UTC
        </p>

        <p>
          {{ fixture
            ? 'Synthetic preview: only the sample latest update is available.'
            : 'Server-recorded evidence. Times below are UTC.' }}
        </p>

        @if (!fixture) {
          <p class="muted">
            Synthetic telecom demo
            · measurements processed by the running backend.
          </p>
        }

        <p>
          Recovery describes the service.
          The investigation remains open until an analyst resolves it.
        </p>
      </section>

      <app-evidence-timeline [detections]="detections()" />
    }
  `,
})
export class IncidentDetailComponent {
  private readonly api = inject(TelecomClient);
  private id = '';
  private generation = 0;

  readonly fixture = dataSource.fixture;
  readonly loading = signal(true);
  readonly error = signal('');
  readonly incident = signal<Incident | null>(null);

  readonly detections =
    signal<components['schemas']['ServiceDetection'][]>([]);

  constructor() {
    const destroy = inject(DestroyRef);

    destroy.onDestroy(() => {
      this.generation++;
    });

    inject(ActivatedRoute).paramMap
      .pipe(takeUntilDestroyed(destroy))
      .subscribe(params => {
        this.id = params.get('id') ?? '';
        void this.load();
      });
  }

  async load() {
    const generation = ++this.generation;
    const id = this.id;

    this.loading.set(true);
    this.error.set('');
    this.incident.set(null);
    this.detections.set([]);

    try {
      const item = await this.api.getIncident(id);

      if (generation !== this.generation) return;

      const updates: components['schemas']['ServiceDetection'][] = [];

      for (let page = 0; ; page++) {
        if (page >= 100) {
          throw new Error(
            'Too much evidence to load. Contact your administrator.',
          );
        }

        const result = await this.api.getDetections(id, page);

        if (generation !== this.generation) return;

        updates.push(...result.items);

        if (updates.length >= result.total) break;

        if (!result.items.length) {
          throw new Error(
            'Evidence changed while loading. Please retry.',
          );
        }
      }

      this.incident.set(item);
      this.detections.set(updates);
    } catch (error) {
      if (generation === this.generation) {
        this.error.set(
          error instanceof Error
            ? error.message
            : 'Could not load incident evidence.',
        );
      }
    } finally {
      if (generation === this.generation) {
        this.loading.set(false);
      }
    }
  }
}
EOF
```

**Why do we change this?** Connect the components to the incident route and preserve history across API pages.

**How does it make our app better?** The page retains loading, error and retry handling. If a later page fails, it avoids presenting a partial timeline as complete.

The route-change guard prevents an old request replacing evidence for a newly opened incident.

This implementation loads up to 100 pages and displays a scrollable history. It does not add previous/next page buttons.

No change is needed in `TelecomClient.getDetections`; it already accepts a page number.

### Check and commit

```sh
npm --prefix apps/dashboard run build

git diff --check
git add apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts
git diff --cached

git commit -m "feat(dashboard): connect historical evidence and cause panels"
```

## Task 5 — Test ordering, evidence semantics and pagination

### Create the timeline tests

```sh
cat > apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.spec.ts <<'EOF'
import { TestBed } from '@angular/core/testing';
import { EvidenceTimelineComponent } from './evidence-timeline.component';
import { voiceIncidents } from '../../../fixtures/voice';
import type { components } from '../../core/api/schema';

type Detection = components['schemas']['ServiceDetection'];

function detection(
  sequence: number,
  phase: Detection['phase'],
): Detection {
  const value = structuredClone(voiceIncidents[0].latestDetection);

  return {
    ...value,
    detectionId: String(sequence).padStart(64, '0'),
    sequence,
    phase,
    technicalState: phase === 'RECOVERY' ? 'RECOVERED' : 'ONGOING',
    impact: {
      ...value.impact,
      extraFailedAttempts: 0,
      uniqueSubscribers: null,
    },
  };
}

describe('Evidence timeline', () => {
  it('orders historical records without mutating its input', () => {
    const fixture = TestBed.createComponent(EvidenceTimelineComponent);

    const records = [
      detection(3, 'RECOVERY'),
      detection(1, 'OPEN'),
      detection(2, 'UPDATE'),
    ];

    const before = structuredClone(records);

    fixture.componentRef.setInput('detections', records);
    fixture.detectChanges();

    expect(
      fixture.componentInstance.ordered().map(item => item.sequence),
    ).toEqual([1, 2, 3]);

    expect(records).toEqual(before);

    const headings = Array.from(
      fixture.nativeElement.querySelectorAll('h3'),
    ).map(element => (element as HTMLElement).textContent);

    expect(headings[0]).toContain('Update 1');
    expect(headings[2]).toContain('RECOVERY');
  });

  it('keeps zero attempts, unavailable customers and hypotheses distinct', () => {
    const fixture = TestBed.createComponent(EvidenceTimelineComponent);

    const record = detection(1, 'UNKNOWN');
    record.kpis[0].observed = null;

    fixture.componentRef.setInput('detections', [record]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;

    expect(text).toContain('Estimated extra failed attempts: 0');
    expect(text).toContain('Unique customers: Unavailable');
    expect(text).toContain('Observed evidence');
    expect(text).toContain('Cause hypothesis');
    expect(text).toContain('not a confirmed root cause');
    expect(text).toContain('does not prove recovery');

    expect(
      fixture.nativeElement.querySelector('[data-kpi]').textContent,
    ).toContain('Unavailable');
  });
});
EOF
```

### Create the pagination tests

These tests supply two pages and check that an empty page before the total is reached produces an error.

```sh
cat > apps/dashboard/src/app/features/incident-investigation/incident-detail.component.spec.ts <<'EOF'
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import { of } from 'rxjs';
import { IncidentDetailComponent } from './incident-detail.component';
import { TelecomClient } from '../../core/api/telecom-client';
import { voiceIncidents } from '../../../fixtures/voice';

async function setup(emptySecondPage = false) {
  const incident = structuredClone(voiceIncidents[0]);

  const open = {
    ...incident.latestDetection,
    sequence: 1,
    phase: 'OPEN' as const,
    technicalState: 'ONGOING' as const,
    detectionId: '1'.padStart(64, '0'),
  };

  const recovery = {
    ...incident.latestDetection,
    sequence: 2,
    phase: 'RECOVERY' as const,
    detectionId: '2'.padStart(64, '0'),
  };

  const requested: number[] = [];

  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: {
          paramMap: of(convertToParamMap({ id: incident.id })),
        },
      },
      {
        provide: TelecomClient,
        useValue: {
          getIncident: async () => incident,

          getDetections: async (_id: string, page: number) => {
            requested.push(page);

            return {
              items: page === 0
                ? [open]
                : emptySecondPage
                  ? []
                  : [recovery],
              total: 2,
              page,
              size: 1,
            };
          },
        },
      },
    ],
  });

  const fixture = TestBed.createComponent(IncidentDetailComponent);

  await fixture.whenStable();
  fixture.detectChanges();

  return { fixture, requested };
}

describe('evidence timeline history loading', () => {
  it('fetches every page and renders bad and recovery updates', async () => {
    const { fixture, requested } = await setup();

    expect(requested).toEqual([0, 1]);
    expect(fixture.componentInstance.detections()).toHaveLength(2);

    expect(
      fixture.nativeElement.querySelectorAll('[data-detection-id]'),
    ).toHaveLength(2);

    expect(fixture.nativeElement.textContent).toContain('RECOVERY');
  });

  it('shows an error rather than a partial timeline when paging stops early', async () => {
    const { fixture } = await setup(true);

    expect(fixture.componentInstance.error()).toContain(
      'Evidence changed while loading',
    );

    expect(fixture.componentInstance.detections()).toHaveLength(0);

    expect(
      fixture.nativeElement.querySelector('[role="alert"]'),
    ).not.toBeNull();
  });
});
EOF
```

**Why do we add these tests?** Ordering, unavailable customer counts and historical pagination are explicit evidence timeline requirements. A one-record preview cannot verify paging.

**How does it make our app better?** Tests catch reversed history, mutated input, misleading customer counts and silently incomplete timelines.

### Run checks and commit

```sh
npm --prefix apps/dashboard test
npm --prefix apps/dashboard run build
npm --prefix apps/dashboard run build:fixtures

git diff --check

git add \
  apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.spec.ts \
  apps/dashboard/src/app/features/incident-investigation/incident-detail.component.spec.ts

git diff --cached

git commit -m "test(dashboard): verify detection order and paged history"
```

If a check fails, fix the issue before committing this step.

## Task 6 — Check the page in a browser

Start the synthetic preview:

```sh
npm --prefix apps/dashboard run start:fixtures
```

Open:

```text
http://127.0.0.1:4200/login
```

Use the preview flow, open the voice service and click **View incident evidence**.

The current fixture client returns only the latest sample detection. Use it for layout and wording checks. The tests above supply multiple records.

For real stored history, follow:

```text
docs/tasks/protected-voice-investigation/README.md
```

Generate the existing voice scenario and open the incident through the protected application on port 8080.

Check:

- Each update has its own sequence, phase, UTC interval and detected time.
- Source scope, actual/baseline values and units are visible.
- Source evidence expands and shows node/source event IDs.
- Cause hypothesis is separate from observed evidence.
- Confidence and recommended checks are visible.
- Estimated attempts are separate from unavailable unique customers.
- Bad and recovery windows remain in the history.
- UNKNOWN states that incomplete evidence does not prove recovery.
- Recovery does not automatically resolve the analyst workflow.
- On a narrow screen, the KPI table scrolls without pushing the whole page sideways.

### Run the existing browser regression

Stop the preview on port 4200 first because Playwright starts its own server.

```sh
npm --prefix apps/dashboard run test:e2e -- tests/e2e/specs/voice-investigation.spec.ts
```

Alternatively, choose a free port:

```sh
E2E_PORT=4300 npm --prefix apps/dashboard run test:e2e -- tests/e2e/specs/voice-investigation.spec.ts
```

If Playwright reports missing browsers:

```sh
npm --prefix apps/dashboard exec -- playwright install chromium
```

Then rerun the browser test.

These controlled tests do not prove real login or backend ingestion.

No commit is needed just for running checks. If you find and fix a layout issue, make a separate commit, for example:

```sh
git add apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts

git commit -m "fix(dashboard): keep evidence table readable on mobile"
```

## Task 7 — Record verification and handoff

Create the documentation file:

```sh
mkdir -p docs/tasks/evidence-timeline-and-cause

cat > docs/tasks/evidence-timeline-and-cause/README.md <<'EOF'
# Evidence timeline and cause panel

The incident page shows accepted detections in sequence, with actual/baseline
KPIs, source scope, estimated impact, source evidence and a separate cause hypothesis.
Unique customers remain unavailable in this aggregate demo.

- [Timeline](../../../apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts)
- [Cause panel](../../../apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts)
- [Incident page](../../../apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts)
- [Protected startup](../protected-voice-investigation/README.md)

Preview: `npm --prefix apps/dashboard run start:fixtures`.
The current preview has one latest sample detection; it does not demonstrate paging.

## Verification — replace pending with actual results

- Unit tests: pending.
- Normal build: pending.
- Fixture build: pending.
- Existing voice browser regression: pending.
- Browser layout and source-evidence expansion: pending.
- Historical bad/recovery windows in the protected app: pending.
- Live integration limitations: record any unavailable history or startup blockers.

## Handoff

- Denis: paging/order verification pending.
- Sergiu: explanation wording approval pending.
EOF
```

Update the file with the actual results. Replace “pending” only after running the check or receiving approval.

### Add the task to the documentation index

This command adds a link without replacing the existing index:

```sh
python3 - <<'PY'
from pathlib import Path

path = Path('docs/tasks/README.md')
text = path.read_text()

link = '- [Evidence timeline and cause panel](evidence-timeline-and-cause/README.md) — historical detections, source evidence, impact and cause hypotheses.\n'
marker = '## Frontend folders'

if link not in text:
    if marker not in text:
        raise SystemExit(
            'Task index changed: add the evidence timeline link manually under Feature guides.'
        )

    path.write_text(text.replace(marker, link + '\n' + marker, 1))
PY
```

**Why do we add this?** Make evidence timeline easy to find and give reviewers an accurate verification record.

**How does it improve our workflow?** Denis can verify paging and ordering, and Sergiu can approve the explanation wording against the implemented screen.

Ask:

- **Denis:** Are all pages loaded and displayed by sequence, including recovery windows?
- **Sergiu:** Is the hypothesis/confidence wording clear, and are estimated attempts distinguished from unavailable customers?

Record approval when received.

### Commit the documentation

```sh
git diff --check

git add \
  Guides/evidence-timeline-and-cause-panel.md \
  docs/tasks/evidence-timeline-and-cause/README.md \
  docs/tasks/README.md

git diff --cached

git commit -m "docs: record evidence timeline verification and review handoff"
```

## Task 8 — Push, review and merge

You now have five coherent commits:

1. Cause hypothesis panel.
2. Ordered evidence timeline.
3. Incident page integration.
4. Ordering and pagination tests.
5. Verification and handoff documentation.

Push:

```sh
git push -u origin feature/evidence-timeline
```

Open a PR targeting `main`.

Suggested title:

```text
Add historical evidence and cause panels
```

Suggested description:

> Show accepted incident detections in sequence with actual/baseline KPIs, source scope and impact. Separate probable cause/confidence from observed evidence, and keep unavailable unique customers distinct from estimated failed attempts. Preserve paged history and bad/recovery windows.
>
> Validation: list actual checks and results.
>
> Denis paging/order review: pending or approved.
>
> Sergiu wording review: pending or approved.
>
> Record any live integration limitation.

Use a draft PR while required checks or approvals are pending.

Resolve review comments and commit fixes with clear messages.

### When to merge

Merge after:

- Required checks pass.
- Code review is complete.
- Denis verifies paging and ordering.
- Sergiu approves the wording.

If `main` changes during review, update your branch using the team’s workflow and rerun affected checks.

Use the team’s approved merge method. Merge or rebase merge retains individual commits on `main`; squash merge combines them into one.

After merging:

```sh
git switch main
git pull --ff-only origin main
```

## Completion checklist

- [ ] Historical detections appear by sequence without mutating records.
- [ ] Bad, recovery and unknown windows remain visible.
- [ ] Each update shows actual/baseline values, units and source scope.
- [ ] Source evidence includes node and event IDs where supplied.
- [ ] Probable cause is clearly labelled as a hypothesis.
- [ ] Confidence and recommended checks are visible.
- [ ] Estimated failed attempts remain separate from unique customers.
- [ ] Unique customers remain unavailable.
- [ ] Paging and premature empty-page errors are tested.
- [ ] Existing voice flow and narrow screen layout work.
- [ ] Actual validation results and integration limits are documented.
- [ ] Denis and Sergiu complete their handoff checks.
- [ ] The PR is reviewed and merged.