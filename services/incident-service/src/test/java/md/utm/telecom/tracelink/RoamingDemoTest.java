package md.utm.telecom.tracelink;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import md.utm.telecom.tracelink.demo.RoamingDemoData;
import md.utm.telecom.tracelink.metrics.TraceQuery;
import org.junit.jupiter.api.Test;

class RoamingDemoTest {
    private static final Instant CYCLE = Instant.parse("2026-11-01T18:00:00Z");
    @Test void lifecycleUsesTheSameCountsAsTheGraphAndRecoversAfterThreeHealthyWindows() {
        var previous = RoamingDemoEvidence.envelope("RO-DEMO-01","out",RoamingDemoData.METRICS.getFirst(),CYCLE,1);
        for (int step=1;step<=6;step++) {
            var value = RoamingDemoEvidence.envelope("RO-DEMO-01","out",RoamingDemoData.METRICS.getFirst(),CYCLE,step);
            if (step>1) TraceLinkIncidentService.validateSuccessor(previous,value);
            assertEquals(value,RoamingDemoEvidence.envelope("RO-DEMO-01","out",RoamingDemoData.METRICS.getFirst(),CYCLE,step));
            var points = RoamingDemoData.series(new TraceQuery(RoamingDemoData.DATASET,"roaming",RoamingDemoData.METRICS.getFirst(),
                    "PARTNER_DIRECTION","RO-DEMO-01:out",CYCLE.plusSeconds(step*300L),CYCLE.plusSeconds((step+1)*300L),"5m"),CYCLE.plusSeconds(3600)).points();
            var evidence = value.path("context").path("evidence").get(0);
            assertEquals(0,points.getFirst().value().compareTo(evidence.path("actual").decimalValue()));
            assertEquals(0,points.getFirst().numerator().compareTo(evidence.path("primitives").path("numerator").decimalValue()));
            assertEquals(step==6?"RECOVERY":step==1?"OPEN":"UPDATE",value.path("lifecycle").path("phase").asText());
            assertEquals(step<4?"BREACH":"HEALTHY",value.path("lifecycle").path("evaluation").path("verdict").asText());
            previous=value;
        }
    }
    @Test void rollupsUseCountWeightedRatesAndDirectionSpecificSnapshots() {
        var q = new TraceQuery(RoamingDemoData.DATASET,"roaming",RoamingDemoData.METRICS.getFirst(),"PARTNER_DIRECTION", "RO-DEMO-01:out",CYCLE,CYCLE.plusSeconds(3600),"1h");
        var point = RoamingDemoData.series(q,CYCLE.plusSeconds(3600)).points().getFirst();
        long n=0,d=0;
        for(int i=0;i<12;i++){var counts=RoamingDemoData.counts("RO-DEMO-01","out",q.metric(),CYCLE.plusSeconds(i*300));n+=counts.successes();d+=counts.attempts();}
        assertEquals(0,point.value().compareTo(new RoamingDemoData.Counts(n,d).rate()));
        assertEquals(BigDecimal.ONE,point.coverage());assertEquals(RoamingDemoData.CENTER,point.baseline());
        var snapshot = new TraceQuery(q.datasetId(),q.domain(),"roaming.activeOutbound",q.scopeType(),q.scopeId(),q.from(),q.to(),"1h");
        assertEquals(CYCLE.plusSeconds(3599),RoamingDemoData.series(snapshot,q.to()).points().getFirst().snapshotAt());
        assertThrows(RuntimeException.class,()->RoamingDemoData.series(snapshot,CYCLE));
        assertNotEquals(RoamingDemoData.counts("RO-DEMO-01","in",q.metric(),CYCLE),RoamingDemoData.counts("RO-DEMO-01","out",q.metric(),CYCLE));
    }
}
