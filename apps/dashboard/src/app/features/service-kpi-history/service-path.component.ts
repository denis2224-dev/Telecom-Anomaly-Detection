import { Component, input } from '@angular/core';
import type { ServiceSummary } from '../../core/api/telecom-client';
import topology from '../../../../../../contracts/topology/demo-scopes-v2.json';
import geographicTopology from '../../../../../../contracts/topology/geographic-scopes-v2.json';
import geography from '../../../../../../contracts/geography/demo-geography-v1.json';
import { Detection, formatMetric, metricValue } from './assurance-model';
@Component({
  selector: 'app-service-path',
  template: `<section class="detail-panel"><h2>Monitored service dependencies</h2>
    <p class="muted">{{ service().scope.scopeId }} · {{ service().latestWindow?.topologyVersion ?? 'Topology version unavailable' }} · Logical support relationships</p>
    <div class="dependency-flow" aria-label="Service source and monitored dependencies">
      <article class="dependency-node"><h3>{{ source() }}</h3><strong>{{ service().freshness }} / {{ service().latestWindow?.quality ?? 'MISSING' }}</strong><p>Service observation source</p></article>
      <span class="dependency-arrow" aria-hidden="true">→</span>
      @for (node of service().scope.dependencyIds; track node) {
        <article class="dependency-node" [attr.data-node-id]="node" [class.no-measurement]="!measured(node)">
          <h3>{{ node }}</h3><strong>{{ measured(node) ? 'OBSERVED CONTEXT' : 'UNKNOWN / NO CURRENT MEASUREMENT' }}</strong>
          @for (name of metrics(node); track name) { <p>{{ name }}: {{ format(value(name), unit(name)) }}</p> }
          @for (item of evidence(node); track $index) { <p class="evidence-summary">Supporting evidence ({{ item.windowStart }}): {{ item.summary }}</p> }
          @if (!measured(node)) { <p>Dependency membership does not demonstrate current health.</p> }
        </article>
      }
    </div><p class="muted">No link alarm is required for service degradation. Observations and correlation do not prove causation.</p>
  </section>`,
})
export class ServicePathComponent {
  readonly service = input.required<ServiceSummary>(); readonly detections = input<Detection[]>([]);
  readonly format = formatMetric;
  source() {
    const authority = this.service().latestWindow?.topologyVersion === geographicTopology.topologyVersion ? geographicTopology : topology;
    return authority.scopes.find(item => item.scopeId === this.service().scope.scopeId)?.serviceSourceId ?? 'Service source unavailable';
  }
  metrics(node: string): string[] {
    if (this.service().latestWindow?.topologyVersion === geography.topologyVersion) {
      const role = geography.scopes.find(item => item.scopeId === this.service().scope.scopeId)?.roles.find(item => item.nodeId === node)?.role;
      return role === 'VOLTE_IMS' ? ['imsCpuPct'] : role === 'VOLTE_TRANSPORT' ? ['packetLossRatio']
        : role === 'SMS_SMSC' ? ['queueDepth', 'oldestPendingAgeSec'] : [];
    }
    if (this.service().scope.service === 'VOLTE') return node === 'IMS-A' ? ['imsCpuPct'] : node === 'TRANSPORT-A' ? ['packetLossRatio'] : [];
    return node === 'SMSC-A' ? ['queueDepth', 'oldestPendingAgeSec'] : [];
  }
  value(name: string) { return metricValue(this.service().latestWindow, name); }
  unit(name: string) { return this.service().latestWindow?.kpis.find(item => item.name === name)?.unit; }
  measured(node: string) { return this.service().freshness === 'FRESH' && this.metrics(node).some(name => this.value(name) !== null); }
  evidence(node: string) {
    return this.detections().flatMap(detection => detection.evidence.filter(item => item.nodeId === node).map(item => ({ ...item, windowStart: detection.windowStart }))).slice(-2);
  }
}
