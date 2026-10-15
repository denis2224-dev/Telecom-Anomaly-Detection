package md.utm.telecom.processing.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkShadowClientTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ObjectNode request() throws Exception {
        var features=TraceLinkFeatures.build(TraceLinkFeaturesTest.contexts().get(0).path("input").path("context"));
        var request=JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("requestedAt",Clock.systemUTC().instant().toString())
                .put("requestedModelVersion",TraceLinkFeatures.MODEL);request.set("features",features);return request;
    }
    private ObjectNode response(ObjectNode request) {
        var body=JSON.createObjectNode();var features=request.path("features");
        for(String field:java.util.List.of("datasetId","domain","scope","window","revision","topologyVersion","featureSchemaVersion"))body.set(field,features.path(field).deepCopy());
        body.set("requestId",request.path("requestId"));body.put("modelVersion",TraceLinkFeatures.MODEL)
                .put("modelSha256",TraceLinkFeatures.resource("ml-shadow/trace-link-model-pin-v1.json").path("domains").path("TRANSPORT").asText())
                .put("inferenceStatus","OK").put("anomalyScore",.83).put("label","TRANSPORT_DEGRADATION")
                .put("latencyMs",1).put("generatedAt",Clock.systemUTC().instant().toString());body.putArray("featureContributions");return body;
    }
    @Test void oversizedSuccessAndErrorBodiesAreRejectedBeforeBufferingAndDecoding() throws Exception {
        byte[] attack=("["+"{},".repeat(2_000_000)+"{}]").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var mode=new java.util.concurrent.atomic.AtomicReference<>("OK");
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/inference/trace-link",exchange->{
            ObjectNode input=(ObjectNode)JSON.readTree(exchange.getRequestBody());String selected=mode.get();
            byte[] bytes=selected.equals("OK")?response(input).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8):attack;
            try {
                exchange.sendResponseHeaders(selected.startsWith("ERROR")?503:200,selected.endsWith("CHUNKED")?0:bytes.length);
                exchange.getResponseBody().write(bytes);
            }catch(java.io.IOException cancelled){ /* Overflow must cancel the server's response stream. */ }
            finally{exchange.close();}
        });server.setExecutor(executor);server.start();
        try {
            var permits=new Semaphore(8);
            var client=new TraceLinkShadowClient("http://127.0.0.1:"+server.getAddress().getPort(),Clock.systemUTC(),HttpClient.newHttpClient(),permits);
            assertEquals("OK",client.score(request()).status());
            for(String selected:java.util.List.of("SUCCESS_FIXED","SUCCESS_CHUNKED","ERROR_FIXED","ERROR_CHUNKED")) {
                mode.set(selected);var result=client.score(request());
                System.out.println(selected+" 6000004 bytes: "+result.reason()+" at "+result.latencyMs()+" ms; permits="+permits.availablePermits());
                assertEquals("RESPONSE_TOO_LARGE",result.reason(),selected);
                assertEquals("FALLBACK",result.status());assertNull(result.response());
                assertTrue(result.latencyMs()<600,"Oversized response retained worker: "+result.latencyMs());
                assertEquals(8,permits.availablePermits());assertEquals(0,client.active());
                mode.set("OK");assertEquals("OK",client.score(request()).status(),"Permit reuse after "+selected);
            }
        }finally{server.stop(0);executor.close();}
    }
    @Test void exactByteBoundaryAndJsonComplexityLimitsPreservePermitReuse() throws Exception {
        var selected=new java.util.concurrent.atomic.AtomicReference<>("OK");
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/inference/trace-link",exchange->{
            ObjectNode input=(ObjectNode)JSON.readTree(exchange.getRequestBody());String valid=response(input).toString();
            String output=switch(selected.get()) {
                case "EXACT" -> valid+" ".repeat(TraceLinkResponseBody.MAX_BYTES-valid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                case "OVER" -> valid+" ".repeat(TraceLinkResponseBody.MAX_BYTES+1-valid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                case "DEPTH" -> "{\"x\":"+"[".repeat(20)+"0"+"]".repeat(20)+"}";
                case "TOKENS" -> "{\"x\":["+"0,".repeat(600)+"0]}";
                case "STRING" -> "{\"x\":\""+"a".repeat(2049)+"\"}";
                case "NUMBER" -> "{\"x\":"+"1".repeat(65)+"}";
                case "DUPLICATE" -> "{\"x\":0,\"x\":1}";
                case "TRAILING" -> valid+"{}";
                default -> valid;
            };
            try {exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(output.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            catch(java.io.IOException cancelled){ /* Over-limit body rejected before EOF. */ }
            finally{exchange.close();}
        });server.setExecutor(executor);server.start();
        try {
            var client=new TraceLinkShadowClient("http://127.0.0.1:"+server.getAddress().getPort(),Clock.systemUTC());
            assertEquals("OK",client.score(request()).status());
            selected.set("EXACT");assertEquals("OK",client.score(request()).status(),"A valid response at the exact byte cap is accepted");
            for(String failure:java.util.List.of("OVER","DEPTH","TOKENS","STRING","NUMBER","DUPLICATE","TRAILING")) {
                selected.set(failure);var result=client.score(request());
                assertEquals(failure.equals("OVER")?"RESPONSE_TOO_LARGE":"MALFORMED_RESPONSE",result.reason(),failure);
                assertEquals("FALLBACK",result.status());assertNull(result.response());assertEquals(0,client.active());
                selected.set("OK");assertEquals("OK",client.score(request()).status(),failure);
            }
        }finally{server.stop(0);executor.close();}
    }
    @Test void deadlineIncludesHeadersFirstStalledBodyAndReleasesPermit() throws Exception {
        var stalled=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var stall=new java.util.concurrent.atomic.AtomicBoolean(true);
        server.createContext("/internal/inference/trace-link",exchange->{
            ObjectNode input=(ObjectNode)JSON.readTree(exchange.getRequestBody());
            try {
                if(stall.get()) {
                    exchange.sendResponseHeaders(200,0);
                    exchange.getResponseBody().write('{');exchange.getResponseBody().flush();stalled.countDown();
                    try {release.await(2,java.util.concurrent.TimeUnit.SECONDS);}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                } else {
                    byte[] bytes=response(input).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }
            } finally {exchange.close();}
        });server.setExecutor(executor);server.start();
        try {
            var permits=new Semaphore(8);
            var client=new TraceLinkShadowClient("http://127.0.0.1:"+server.getAddress().getPort(),Clock.systemUTC(),HttpClient.newHttpClient(),permits);
            var result=client.score(request());
            assertEquals(0,stalled.getCount(),"The server delivered headers and a body byte");
            assertEquals("TIMEOUT",result.reason());assertEquals("FALLBACK",result.status());assertNull(result.response());
            assertTrue(result.latencyMs()<600,"Full body deadline: "+result.latencyMs());
            assertEquals(8,permits.availablePermits());assertEquals(0,client.active());
            // The original handler is still stalled; a new request must nevertheless work.
            stall.set(false);assertEquals("OK",client.score(request()).status());
        } finally {release.countDown();server.stop(0);executor.close();}
    }
    @Test void acceptsBoundIdentityAndRejectsFailureMatrixWithoutScores() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var mode=new java.util.concurrent.atomic.AtomicReference<>("OK");
        server.createContext("/internal/inference/trace-link",exchange->{
            ObjectNode request=(ObjectNode)JSON.readTree(exchange.getRequestBody()),body=response(request);
            switch(mode.get()) {
                case "REQUEST" -> body.put("requestId",UUID.randomUUID().toString());
                case "SCOPE" -> body.withObject("scope").put("id","other");
                case "WINDOW" -> body.withObject("window").put("id","other");
                case "SCHEMA" -> body.put("featureSchemaVersion","wrong");
                case "MODEL" -> body.put("modelSha256","a".repeat(64));
                case "LABEL" -> body.put("label","ROAMING_DEGRADATION");
                case "STALE" -> body.put("generatedAt","2020-01-01T00:00:00Z");
                case "LATENCY" -> body.put("latencyMs",251);
                case "NAN" -> body.put("anomalyScore","NaN");
                case "TIMEOUT" -> {try {Thread.sleep(350);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
                default -> { }
            }
            String output=mode.get().equals("JSON")?"{bad":body.toString();int code=mode.get().equals("HTTP")?500:200;
            byte[] bytes=output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());server.start();
        try {
            var client=new TraceLinkShadowClient("http://127.0.0.1:"+server.getAddress().getPort(),Clock.systemUTC());
            // Initialize schema caches and connection separately from measured inference checks.
            TraceLinkFeatures.validate("ml-shadow/trace-link-response-v1.schema.json",response(request()));
            client.score(request());
            assertEquals("OK",client.score(request()).status());
            for(String failure:java.util.List.of("REQUEST","SCOPE","WINDOW","SCHEMA","MODEL","LABEL","STALE","LATENCY","NAN","JSON","HTTP","TIMEOUT")) {
                mode.set(failure);var result=client.score(request());assertEquals("FALLBACK",result.status(),failure);assertNull(result.response(),failure);assertNotNull(result.reason());
                if(failure.equals("TIMEOUT"))assertEquals("TIMEOUT",result.reason());
            }
            mode.set("OK");assertEquals("OK",client.score(request()).status(),"Every failure releases its permit");
            var saturated=new TraceLinkShadowClient("http://127.0.0.1:"+server.getAddress().getPort(),Clock.systemUTC(),HttpClient.newHttpClient(),new Semaphore(0));
            assertEquals("SATURATED",saturated.score(request()).reason());
        }finally{server.stop(0);}
        assertEquals("CONNECTION_UNAVAILABLE",new TraceLinkShadowClient("http://127.0.0.1:"+server.getAddress().getPort(),Clock.systemUTC()).score(request()).reason());
    }
}
