package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import md.utm.telecom.processing.shadow.SmsShadowClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmsShadowClientTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void noMoreThanEightRequestsReachTheServerAtOnce() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        var received=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/internal/inference/sms-classifier",exchange->{
            received.incrementAndGet();
            try { Thread.sleep(600); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(9)) {
            var client=new SmsShadowClient("http://127.0.0.1:"+server.getAddress().getPort());
            var barrier=new java.util.concurrent.CyclicBarrier(9);
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<SmsShadowClient.Result>>();
            for(int i=0;i<9;i++) tasks.add(pool.submit(()->{barrier.await();return client.score(json.createObjectNode());}));
            int unavailable=0,timeouts=0;
            for(var task:tasks) {
                var result=task.get(3,java.util.concurrent.TimeUnit.SECONDS);
                assertNull(result.score());assertNull(result.detection());
                if(result.status().equals("UNAVAILABLE")) unavailable++;
                if(result.status().equals("TIMEOUT")) timeouts++;
            }
            assertEquals(1,unavailable);assertEquals(8,timeouts);
            assertTrue(received.get()<=8);
        } finally { server.stop(0);executor.shutdownNow(); }
    }
    @Test void failuresAreNullAndResponsesMustMatchFrozenPackageAndInclusiveCutoff() throws Exception {
        var response = new AtomicReference<>("""
                {"schemaVersion":1,"mlStatus":"OK","classifierScore":0.55,"detection":true,
                "threshold":0.55,"modelVersion":"sms-supervised-v1-2",
                "modelSha256":"f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19"}
                """);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/inference/sms-classifier", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var window = json.readTree("{}");
        var client = new SmsShadowClient("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertEquals("OK", client.score(window).status());
            assertTrue(client.score(window).detection());
            for (String bad : new String[]{response.get().replace("0.55", "0.5"),
                    response.get().replace("true", "false"), response.get().replace("v1-2", "v1-3"),
                    response.get().replace("f3baf6", "a3baf6"), "{}", "null", "not-json"}) {
                response.set(bad);
                var result = client.score(window);
                assertEquals("MALFORMED_RESPONSE", result.status());
                assertNull(result.score()); assertNull(result.detection());
            }
        } finally { server.stop(0); }
        assertEquals("UNAVAILABLE", client.score(window).status());
        var slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/internal/inference/sms-classifier", exchange -> {
            try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        slow.start();
        try {
            var result = new SmsShadowClient("http://127.0.0.1:" + slow.getAddress().getPort()).score(window);
            assertEquals("TIMEOUT", result.status()); assertNull(result.score()); assertNull(result.detection());
        } finally { slow.stop(0); }
    }
}
