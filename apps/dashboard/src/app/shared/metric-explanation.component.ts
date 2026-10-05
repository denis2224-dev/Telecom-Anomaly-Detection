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
