package md.utm.telecom.tracelink;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.tracelink.scenarios.TraceScenarioGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class TraceScenarioGatewayTest {
    @Test void malformedOrUnavailableUpstreamIs503AndValidationRejectionIs400() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/trace-link/v1/malformed",exchange->{
            byte[] bytes="not JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.createContext("/internal/trace-link/v1/invalid",exchange->{exchange.sendResponseHeaders(400,-1);exchange.close();});
        server.start();
        try {
            var gateway=new TraceScenarioGateway("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(WorkflowProblem.class,()->gateway.call("GET","/malformed",null)).status());
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(WorkflowProblem.class,()->gateway.call("GET","/invalid",null)).status());
        } finally {server.stop(0);}
    }
}
