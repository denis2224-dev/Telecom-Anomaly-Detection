package md.utm.telecom.generator.tracelink;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;
import static md.utm.telecom.generator.tracelink.TraceLinkTransportScenarioTest.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioEngine.Status.*;

class TraceLinkScenarioEngineTest {
    static final class TestClock extends Clock {
        Instant now = START.minusSeconds(1);
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @Test void ticksReleaseExactlyOnceOnlyAfterWindowEndAndFinishEightIdentities() throws Exception {
        var clock = new TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        var c = command(Type.TRANSPORT_LINK_OVERLOAD, Scope.transport("chi-hin", "a-to-b"));
        var started = engine.start(c); assertEquals(c.runId(), started.runId()); assertEquals(8, new HashSet<>(started.expectedWindowIdentities()).size());
        assertTrue(engine.advance().isEmpty()); clock.now = START; assertTrue(engine.advance().isEmpty());
        for (int i = 0; i < 8; i++) {
            clock.now = START.plusSeconds((i + 1L) * 60); var release = engine.advance(); assertEquals(1, release.size());
            assertEquals(started.expectedWindowIdentities().get(i), release.getFirst().identity()); assertTrue(engine.advance().isEmpty());
        }
        assertEquals(COMPLETED, engine.status(c.runId()).status()); assertEquals(8, engine.status(c.runId()).history().size());
    }
    @Test void exactCommandReplayIsIdempotentButChangedPayloadUnderSameRunIdConflicts() throws Exception {
        var engine = new TraceLinkScenarioEngine(new TestClock(), 8); var c = command(Type.NORMAL_CONTROL, Scope.data("chi"));
        assertEquals(engine.start(c), engine.start(c));
        var changed = new Command(c.type(), c.version(), c.scope(), 43, c.start(), c.parameters());
        assertNotEquals(c.runId(), changed.runId());
        assertThrows(IllegalArgumentException.class, () -> engine.start(c.runId(), changed));
        assertThrows(IllegalArgumentException.class, () -> engine.start(changed));
        assertThrows(IllegalArgumentException.class, () -> engine.start("not-an-id", c));
    }
    @Test void distinctScopesRunSimultaneouslyAndOrderDoesNotAffectContent() throws Exception {
        var clock = new TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        var a = command(Type.TRANSPORT_LINK_OVERLOAD, Scope.transport("chi-hin", "a-to-b"));
        var b = command(Type.ROAMING_REGISTRATION_FAILURE, Scope.roaming("RO-DEMO-01","RO","out"));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var f1 = workers.submit(() -> { barrier.await(); return engine.start(a); });
            var f2 = workers.submit(() -> { barrier.await(); return engine.start(b); });
            assertEquals(SCHEDULED, f1.get(10, TimeUnit.SECONDS).status()); assertEquals(SCHEDULED, f2.get(10, TimeUnit.SECONDS).status());
        }
        clock.now = START.plusSeconds(480); assertEquals(16, engine.advance().size());
        assertEquals(new TraceLinkFixtureGenerator().generate(a), engine.status(a.runId()).history());
        assertEquals(new TraceLinkFixtureGenerator().generate(b), engine.status(b.runId()).history());
    }
    @Test void racingSameScopeAcceptsOnlyOneAndRetainsReservationAfterStop() throws Exception {
        var engine = new TraceLinkScenarioEngine(new TestClock(), 8);
        var a = command(Type.NORMAL_CONTROL, Scope.transport("chi-hin","a-to-b"));
        var b = command(Type.TRANSPORT_LINK_OVERLOAD, a.scope());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2); var results = new ArrayList<Future<Boolean>>();
            for (var c : List.of(a,b)) results.add(workers.submit(() -> { barrier.await(); try { engine.start(c); return true; } catch (IllegalArgumentException conflict) { return false; } }));
            assertNotEquals(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
        }
        var winner = engine.checkpoint().getFirst(); engine.stop(winner.runId());
        assertThrows(IllegalArgumentException.class, () -> engine.start(winner.command().equals(a) ? b : a));
    }
    @Test void halfOpenNonoverlappingRunsAndOppositeDirectionsAreIndependent() throws Exception {
        var engine = new TraceLinkScenarioEngine(new TestClock(), 8); var a = command(Type.NORMAL_CONTROL, Scope.transport("chi-hin","a-to-b"));
        engine.start(a); engine.start(command(Type.NORMAL_CONTROL, Scope.transport("chi-hin","b-to-a")));
        engine.start(new Command(a.type(), a.version(), a.scope(), a.seed(), a.end(), a.parameters()));
        assertEquals(3, engine.checkpoint().size());
    }
    @Test void stopAfterFaultRetainsHistoryWithoutRecovery() throws Exception {
        var clock = new TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8); var c = command(Type.MOBILE_DATA_DNS_FAILURE, Scope.data("chi"));
        engine.start(c); clock.now = START.plusSeconds(300); assertEquals(5, engine.advance().size());
        var stopped = engine.stop(c.runId()); assertEquals(STOPPED, stopped.status()); assertEquals(5, stopped.history().size());
        clock.now = START.plusSeconds(480); assertTrue(engine.advance().isEmpty()); assertEquals(stopped, engine.start(c));
    }
    @Test void restartKeepsCompletedHistoryAndInterruptsActiveRunsWithoutCatchup() throws Exception {
        var clock = new TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8); var c = command(Type.NORMAL_CONTROL, Scope.data("chi"));
        engine.start(c); clock.now = START.plusSeconds(180); engine.advance();
        var restored = TraceLinkScenarioEngine.restore(clock, 8, engine.checkpoint());
        assertEquals(INTERRUPTED, restored.status(c.runId()).status()); assertEquals(3, restored.status(c.runId()).history().size());
        clock.now = START.plusSeconds(480); assertTrue(restored.advance().isEmpty()); assertEquals(INTERRUPTED, restored.start(c).status());
        assertThrows(IllegalArgumentException.class, () -> restored.start(command(Type.MOBILE_DATA_DNS_FAILURE, c.scope())));
        var future = new TestClock(); var finishing = new TraceLinkScenarioEngine(future,8); finishing.start(c); future.now = c.end(); finishing.advance();
        assertEquals(COMPLETED, TraceLinkScenarioEngine.restore(future,8,finishing.checkpoint()).status(c.runId()).status());
    }
    @Test void unknownPastStartAndUnknownStatusFailClosed() throws Exception {
        var clock = new TestClock(); clock.now = START; var engine = new TraceLinkScenarioEngine(clock,8);
        assertEquals(INTERRUPTED, engine.start(command(Type.NORMAL_CONTROL,Scope.data("chi"))).status()); assertTrue(engine.advance().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> engine.status("unknown"));
    }
    @Test void boundedRetainedRunsAndCheckpointValidation() throws Exception {
        var clock = new TestClock(); var engine = new TraceLinkScenarioEngine(clock,1); var c = command(Type.NORMAL_CONTROL,Scope.data("chi"));
        var s = engine.start(c); assertEquals(s, engine.start(c));
        assertThrows(IllegalArgumentException.class, () -> engine.start(command(Type.NORMAL_CONTROL,Scope.data("hin"))));
        assertThrows(IllegalArgumentException.class, () -> new TraceLinkScenarioEngine(clock,129));
        var corrupt = new TraceLinkScenarioEngine.Snapshot(s.runId(),s.command(),COMPLETED,s.history(),s.expectedWindowIdentities());
        assertThrows(IllegalArgumentException.class, () -> TraceLinkScenarioEngine.restore(clock,1,List.of(corrupt)));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkScenarioEngine.restore(clock,2,List.of(s,s)));
    }
}
