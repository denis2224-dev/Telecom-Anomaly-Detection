import { Component, computed, input, signal, effect, output } from '@angular/core';
import type { TraceMetricSeries } from '../../core/api/telecom-client';

export type MetricChartSeries = Omit<TraceMetricSeries,'points'> & { points: (Pick<TraceMetricSeries['points'][number], 'from'|'to'|'value'|'quality'|'numerator'|'denominator'|'sumValue'|'sampleCount'> & Partial<Pick<TraceMetricSeries['points'][number], 'qualityReason'|'snapshotAt'|'sourceAgeSeconds'|'histogramCounts'|'histogramEdges'|'provenance'>> & {coverage:number|null})[] };

/** Native Trace Link series remains distinct from the legacy ServiceKpiWindow population. */
@Component({
  selector:'app-trace-metric-chart',
  styles:[`svg {width:100%;height:260px;} path {fill:none;stroke:var(--brand,#ff7900);stroke-width:2;} text {fill:currentColor;font-size:12px;} .values {overflow:auto;} table {min-width:800px;} pre {white-space:pre-wrap;overflow-wrap:anywhere;} button {min-height:44px;}`],
  template:`<section aria-label="Persisted metric chart">
    <p>{{ series().metric }} · {{ series().unit }} · {{ series().aggregation }}. Baseline unavailable in this read contract. Gaps indicate unavailable observations.</p>
    @if(!series().points.length){<p role="status">No recorded buckets for this query.</p>}
    <svg viewBox="0 0 900 260" role="img" [attr.aria-label]="series().metric + ' in ' + series().unit + '; exact values follow in the table'" (pointerdown)="beginBrush($event)" (pointerup)="endBrush($event)" (pointercancel)="brushStart=undefined">
      <title>{{ series().metric }} · {{ series().unit }}</title><desc>Only server measurements are drawn. Missing intervals break the line. Stale measurements retain their quality in the exact table. Drag horizontally to select a visible interval, or use the keyboard-accessible zoom controls.</desc>
      <text x="20" y="18">{{ bounds().max }} {{ series().unit }}</text><text x="20" y="247">{{ bounds().min }} {{ series().unit }}</text>
      <path [attr.d]="path()" />
      @for(point of markers();track point.from){<circle [attr.cx]="point.x" [attr.cy]="point.y" r="3" fill="currentColor"><title>{{point.from}} · {{point.value}} {{series().unit}} · {{point.quality}}</title></circle>}
    </svg>
    <details open><summary>Exact server buckets · {{ series().points.length }} rows</summary><div class="values" tabindex="0" aria-label="Scrollable exact metric buckets"><table><thead><tr><th>Start · {{ timezone() }}</th><th>End</th><th>Value · {{ series().unit }}</th><th>Quality</th><th>Coverage</th><th>Numerator</th><th>Denominator</th><th>Sum</th><th>Samples</th><th>Qualification</th><th>Snapshot</th><th>Source age · seconds</th></tr></thead><tbody>
      @for(point of rows();track point.from) {<tr><th><time [attr.datetime]="point.from">{{ time(point.from) }}</time></th><td>{{ time(point.to) }}</td><td>{{ reading(point) }}</td><td>{{ point.quality }}</td><td>{{ point.coverage ?? 'Unavailable' }}</td><td>{{ point.numerator ?? 'Unavailable' }}</td><td>{{ point.denominator ?? 'Unavailable' }}</td><td>{{ point.sumValue ?? 'Unavailable' }}</td><td>{{ point.sampleCount ?? 'Unavailable' }}</td><td>{{point.qualityReason ?? 'Unavailable'}}</td><td>{{point.snapshotAt ? time(point.snapshotAt) : 'Unavailable'}}</td><td>{{point.sourceAgeSeconds ?? 'Unavailable'}}</td></tr>}
    </tbody></table></div><details><summary>Exact primitives and provenance for this bucket page</summary>@for(point of rows();track point.from){<pre>{{ provenance(point) }}</pre>}</details><button type="button" [disabled]="page()===0" (click)="page.set(page()-1)">Previous buckets</button> <span>Page {{ page()+1 }} of {{ pages() }}</span> <button type="button" [disabled]="page()+1>=pages()" (click)="page.set(page()+1)">Next buckets</button></details>
  </section>`,
})
export class TraceMetricChartComponent {
  provenance(point:MetricChartSeries['points'][number]):string{return JSON.stringify({from:point.from,histogramCounts:point.histogramCounts,histogramEdges:point.histogramEdges,provenance:point.provenance},null,2);}
  readonly zoomRequested=output<{from:string;to:string}>();
  brushStart?:number;
  readonly series=input.required<MetricChartSeries>();
  readonly timezone=input<'UTC'|'Europe/Chisinau'>('UTC');
  readonly page=signal(0);
  readonly pages=computed(()=>Math.max(1,Math.ceil(this.series().points.length/50)));
  readonly rows=computed(()=>this.series().points.slice(this.page()*50,(this.page()+1)*50));
  readonly bounds=computed(()=>{
    const values=this.series().points.flatMap(point=>point.value===null?[]:[point.value]);
    return {min:Math.min(0,...values),max:Math.max(1,...values)};
  });
  readonly path=computed(()=>{
    const series=this.series(),bounds=this.bounds(),start=Date.parse(series.effectiveFrom),span=Date.parse(series.effectiveTo)-start;
    let path='',drawing=false,previousEnd='';
    const stride=Math.max(1,Math.ceil(series.points.length/600));
    const selected=new Set<number>([0,series.points.length-1]);
    for(let startIndex=0;startIndex<series.points.length;startIndex+=stride){
      let low=-1,high=-1;
      for(let index=startIndex;index<Math.min(series.points.length,startIndex+stride);index++){
        const value=series.points[index].value;if(value===null)continue;
        if(low<0||value<series.points[low].value!)low=index;
        if(high<0||value>series.points[high].value!)high=index;
      }
      if(low>=0)selected.add(low);if(high>=0)selected.add(high);
    }
    for(const [index,point] of series.points.entries()) {
      if(point.value===null || point.quality==='MISSING' || (series.aggregation==='RATIO_OF_SUMS' && point.denominator===0)) {drawing=false;previousEnd=point.to;continue;}
      const contiguous=previousEnd && Date.parse(previousEnd)===Date.parse(point.from);
      if(!contiguous)drawing=false;
      previousEnd=point.to;
      const next=series.points[index+1];
      if(drawing&&!selected.has(index)&&next?.value!==null&&next && Date.parse(next.from)===Date.parse(point.to))continue;
      path+=`${drawing&&contiguous?'L':'M'}${(40+(Date.parse(point.from)-start)/span*830).toFixed(2)},${(230-(point.value-bounds.min)/(bounds.max-bounds.min)*200).toFixed(2)} `;
      drawing=true;previousEnd=point.to;
    }
    return path;
  });
  readonly markers=computed(()=>{
    const series=this.series(),bounds=this.bounds(),start=Date.parse(series.effectiveFrom),span=Date.parse(series.effectiveTo)-start;
    if(series.points.length>120)return [];
    return series.points.filter(point=>point.value!==null&&point.quality!=='MISSING'&&(series.aggregation!=='RATIO_OF_SUMS'||point.denominator!==0))
      .map(point=>({...point,x:40+(Date.parse(point.from)-start)/span*830,y:230-(point.value!-bounds.min)/(bounds.max-bounds.min)*200}));
  });
  constructor(){effect(()=>{this.series();this.page.set(0);});}
  time(value:string):string{return new Intl.DateTimeFormat('en-GB',{dateStyle:'short',timeStyle:'medium',timeZone:this.timezone()}).format(new Date(value));}
  reading(point:MetricChartSeries['points'][number]):string{return point.value!==null?String(point.value):point.quality==='MISSING'?'Unavailable · missing':this.series().aggregation==='RATIO_OF_SUMS'&&point.denominator===0?'Unavailable · zero denominator':'Unavailable';}
  beginBrush(event:PointerEvent):void{if(event.button!==0)return;this.brushStart=event.clientX;(event.currentTarget as SVGSVGElement).setPointerCapture(event.pointerId);}
  endBrush(event:PointerEvent):void{
    const initial=this.brushStart;this.brushStart=undefined;if(initial===undefined||Math.abs(event.clientX-initial)<6)return;
    const box=(event.currentTarget as SVGSVGElement).getBoundingClientRect(),series=this.series(),start=Date.parse(series.effectiveFrom),span=Date.parse(series.effectiveTo)-start;
    const at=(x:number)=>start+Math.max(0,Math.min(1,((x-box.left)/box.width*900-40)/830))*span;
    const from=Math.max(Date.parse(series.requestedFrom),at(Math.min(initial,event.clientX))),to=Math.min(Date.parse(series.requestedTo),at(Math.max(initial,event.clientX)));
    if(to>from)this.zoomRequested.emit({from:new Date(from).toISOString(),to:new Date(to).toISOString()});
  }
}
