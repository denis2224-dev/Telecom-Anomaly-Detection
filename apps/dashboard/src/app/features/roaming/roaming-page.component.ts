import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TraceQueryStore, validTraceContext } from '../../core/state/trace-query.store';
import { RoamingOverviewComponent } from './roaming-overview.component';

@Component({
  selector: 'app-roaming-page',
  imports: [RoamingOverviewComponent],
  template: `@if (selection(); as selection) {
    @if (selection.invalid) { <p role="alert">Invalid roaming context. Use inbound/outbound and a UTC interval up to 30 days.</p> }
    @else { <app-roaming-overview [from]="selection.context.from" [to]="selection.context.to" [datasetId]="selection.context.datasetId" [resolution]="selection.resolution" [direction]="selection.context.roamingDirection === 'in' ? 'in' : 'out'" [strictRange]="selection.strictRange" /> }
  } @else { <app-roaming-overview /> }`,
})
export class RoamingPageComponent {
  private readonly parameters = toSignal(inject(ActivatedRoute).queryParamMap);
  private readonly query = inject(TraceQueryStore);
  readonly selection = computed(() => {
    const params = this.parameters();
    const keys = ['datasetId', 'from', 'to', 'resolution', 'roamingDirection'];
    if (!params || !keys.some(key => params.has(key))) return null;
    const context = { ...this.query.context(), ...Object.fromEntries(keys.filter(key => params.has(key)).map(key => [key, params.get(key)!])),
      visibleFrom: undefined, visibleTo: undefined };
    const strictRange = params.has('from') || params.has('to');
    return { context, strictRange, resolution: params.has('resolution') ? context.resolution : undefined,
      invalid: keys.some(key => params.getAll(key).length > 1) || params.get('roamingDirection') === 'both' || params.has('from') !== params.has('to') || !validTraceContext(context) };
  });
}
