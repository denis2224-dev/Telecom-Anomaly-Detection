import { Component, input } from '@angular/core';
import type { components } from '../../core/api/schema';
import { modelStatusLabel, rankValue, probableCause } from '../../shared/metric-presentation';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-cause-evidence',
  template: `
    <section aria-label="Cause hypothesis">
      <h4>Probable cause</h4>
      <p class="cause-summary" [attr.title]="cause(detection())">{{ summary() }}</p>
      <dl class="cause-facts">
        <div data-fact="confidence"><dt>Confidence</dt><dd><span class="badge" [attr.data-confidence]="detection().causeConfidence" title="Support for a hypothesis, not a confirmed root cause.">{{ detection().causeConfidence }}</span></dd></div>
        <div data-fact="model"><dt>Model result</dt><dd><span class="badge" [attr.data-available]="rank(detection()) !== 'Unavailable'" [attr.title]="statusLabel(detection().mlStatus)">{{ modelLabel() }}</span></dd></div>
        <div data-fact="rank"><dt>Anomaly rank</dt><dd class="cause-rank" [attr.data-available]="rank(detection()) !== 'Unavailable'" [attr.title]="'Model anomaly rank: ' + rank(detection()) + '; not a failure probability.'">{{ compactRank() }}</dd></div>
        <div data-fact="paths"><dt>Affected paths</dt><dd class="muted">Unavailable</dd></div>
        <div data-fact="classification"><dt>Supporting / contradicting evidence</dt><dd class="muted">Unavailable</dd></div>
      </dl>
      <h4>Recommended checks</h4>
      <ul>
        @for (check of detection().recommendedChecks; track $index) { <li>{{ check }}</li> }
        @empty { <li class="muted">No checks supplied.</li> }
      </ul>
    </section>
  `,
})
export class CauseEvidenceComponent {
  readonly detection = input.required<Detection>();
  readonly statusLabel = modelStatusLabel;
  readonly rank = rankValue;
  readonly cause = probableCause;

  summary(): string {
    const cause = this.cause(this.detection());
    return /^(Cause undetermined\b|No cause established\b)/i.test(cause) ? 'Cause undetermined' : cause;
  }

  modelLabel(): string {
    return { OK: 'Available', TIMEOUT: 'Timed out', UNAVAILABLE: 'Unavailable',
      INSUFFICIENT_DATA: 'Insufficient data', NOT_APPLICABLE: 'Not applicable' }[this.detection().mlStatus];
  }

  compactRank(): string {
    const rank = this.rank(this.detection());
    return rank === 'Unavailable' ? rank : Number(rank).toLocaleString('en-US', { maximumFractionDigits: 3, useGrouping: false });
  }
}
