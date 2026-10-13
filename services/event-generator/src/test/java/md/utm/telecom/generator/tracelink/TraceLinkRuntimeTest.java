package md.utm.telecom.generator.tracelink;

import java.nio.file.*;
import md.utm.telecom.generator.tracecontrol.TraceLinkRuntime;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;

class TraceLinkRuntimeTest {
    @TempDir Path directory;
    static final Instant START = Instant.parse("2026-10-12T09:00:00Z");
    static class MutableClock extends Clock {
        Instant now = START.minusSeconds(10);
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    Command command(Type type, Scope scope) { return new Command(type,VERSION,scope,42,START,Parameters.defaults()); }
    String body(Command command) { return new TraceLinkScenarioCommandCodec().write(command).toString(); }
    @Test void durableCompletedHistoryHasStableIdentityAndNoDeliveryClaims() throws Exception {
        var clock = new MutableClock(); var c = command(Type.MOBILE_DATA_DNS_FAILURE,Scope.data("chi"));
        String completed;
        try (var runtime = new TraceLinkRuntime(clock,directory)) {
            assertEquals(18,runtime.catalogue().path("definitions").size());
            assertEquals(54,runtime.catalogue().path("scopes").size());
            runtime.start(c.runId(),body(c)); runtime.tick();
            assertEquals(0,runtime.status(c.runId()).path("completedWindows").asInt());
            clock.now=START.plusSeconds(59); runtime.tick();
            assertEquals(0,runtime.status(c.runId()).path("completedWindows").asInt());
            clock.now=START.plusSeconds(480); runtime.tick();
            completed=runtime.status(c.runId()).toString(); runtime.tick();
            assertEquals(completed,runtime.start(c.runId(),body(c)).toString());
        }
        try(var restarted=new TraceLinkRuntime(clock,directory)) {
            assertEquals(completed,restarted.status(c.runId()).toString());
            var response=restarted.status(c.runId());
            assertEquals(16,response.path("generatedObservations").asInt());
            assertEquals(0,response.path("acknowledged").asInt());
            assertTrue(response.path("accepted").isNull());
            assertEquals("DISABLED_PENDING_REVIEW",response.path("deliveryStatus").asText());
        }
    }
    @ParameterizedTest @ValueSource(ints={0,3,6})
    void restartBeforeFirstDuringFaultAndRecoveryNeverResumes(int minutes) throws Exception {
        var clock=new MutableClock(); var c=command(Type.TRANSPORT_LINK_OVERLOAD,Scope.transport("chi-hin","a-to-b"));
        String history;
        try(var runtime=new TraceLinkRuntime(clock,directory)) {
            runtime.start(c.runId(),body(c)); clock.now=START.plusSeconds(minutes*60L); runtime.tick();
            history=runtime.status(c.runId()).path("history").toString();
        }
        try(var runtime=new TraceLinkRuntime(clock,directory)) {
            clock.now=START.plusSeconds(480); runtime.tick();
            var restored=runtime.start(c.runId(),body(c));
            assertEquals("INTERRUPTED",restored.path("status").asText());
            assertEquals(history,restored.path("history").toString());
            assertEquals(minutes,restored.path("completedWindows").asInt());
        }
    }
    @Test void strictValidationAllFiveTypesAndThirteenUnsupportedDefinitions() throws Exception {
        try(var runtime=new TraceLinkRuntime(new MutableClock(),directory)) {
            int executable=0;
            for(var type:Type.values()) {
                Scope scope=type==Type.TRANSPORT_LINK_OVERLOAD ? Scope.transport("chi-orh","a-to-b")
                        : type==Type.ROAMING_REGISTRATION_FAILURE ? Scope.roaming("RO-DEMO-01","RO","out") : Scope.data("chi");
                var c=command(type,scope);
                if(new TraceLinkScenarioCatalogue().definition(type).implemented()) { runtime.validate(body(c)); executable++; }
                else assertThrows(IllegalArgumentException.class,()->runtime.validate(body(c)));
            }
            assertEquals(5,executable);
            String b=body(command(Type.NORMAL_CONTROL,Scope.data("chi")));
            assertThrows(Exception.class,()->runtime.validate(b.replace("\"seed\":42","\"seed\":42,\"seed\":43")));
            assertThrows(Exception.class,()->runtime.validate(b.replace("\"seed\":42","\"seed\":1.5")));
            assertThrows(Exception.class,()->runtime.validate(b.replace("\"missingMinutes\":[]","\"missingMinutes\":[7,7]")));
            var omitted=new TraceLinkScenarioCommandCodec().write(command(Type.NORMAL_CONTROL,Scope.data("chi"))); omitted.remove("parameters");
            assertEquals(Parameters.defaults(),runtime.validate(omitted.toString()).parameters());
        }
    }
    @ParameterizedTest @ValueSource(ints={0,3,6})
    void stopRetainsHistoryAndReservationAcrossRestart(int minutes) throws Exception {
        var clock=new MutableClock(); var c=command(Type.TELEMETRY_GAP,Scope.roaming("RO-DEMO-01","RO","out"));
        String stopped;
        try(var runtime=new TraceLinkRuntime(clock,directory)) {
            runtime.start(c.runId(),body(c));clock.now=START.plusSeconds(minutes*60L);runtime.tick();
            stopped=runtime.stop(c.runId()).toString();
        }
        try(var runtime=new TraceLinkRuntime(clock,directory)) {
            clock.now=START.plusSeconds(480);runtime.tick();assertEquals(stopped,runtime.status(c.runId()).toString());
            var changed=command(Type.NORMAL_CONTROL,c.scope());
            assertThrows(IllegalArgumentException.class,()->runtime.start(changed.runId(),body(changed)));
            var opposite=command(Type.NORMAL_CONTROL,Scope.roaming("RO-DEMO-01","RO","in"));
            assertEquals("INTERRUPTED",runtime.start(opposite.runId(),body(opposite)).path("status").asText());
        }
    }
    @Test void corruptCheckpointAndSecondWriterFailClosed() throws Exception {
        try(var runtime=new TraceLinkRuntime(new MutableClock(),directory)) {
            assertThrows(Exception.class,()->new TraceLinkRuntime(new MutableClock(),directory));
            var c=command(Type.NORMAL_CONTROL,Scope.data("chi"));runtime.start(c.runId(),body(c));
        }
        var last=directory.resolve("checkpoint-000000000001.json");Files.writeString(last,"{}");
        assertThrows(Exception.class,()->new TraceLinkRuntime(new MutableClock(),directory));
    }
}
