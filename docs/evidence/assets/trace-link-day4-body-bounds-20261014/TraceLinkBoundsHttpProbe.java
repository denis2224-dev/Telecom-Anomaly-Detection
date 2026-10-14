import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.processing.shadow.*;

class TraceLinkBoundsHttpProbe {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();
        var rows=json.readTree(Files.readString(Path.of("contracts/fixtures/trace-link/day3-persisted-windows.json"))).path("windows");
        var client=new TraceLinkShadowClient(args[0],Clock.systemUTC());
        var output=json.createArrayNode();
        for(String domain:List.of("TRANSPORT","ROAMING","MOBILE_DATA")) {
            JsonNode context=null;
            for(JsonNode row:rows)if(row.path("domain").asText().equals(domain) && row.path("step").asInt()==0){context=row.path("input").path("context");break;}
            var request=json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("requestedModelVersion",TraceLinkFeatures.MODEL);
            request.set("features",TraceLinkFeatures.build(context));
            if(domain.equals("TRANSPORT")){request.put("requestedAt",Instant.now().toString());client.score(request);}
            request.put("requestId",UUID.randomUUID().toString()).put("requestedAt",Instant.now().toString());
            var result=client.score(request);
            if(!result.status().equals("OK") || result.response()==null)throw new IllegalStateException(domain+":"+result.reason()+" "+result.latencyMs());
            output.addObject().put("domain",domain).put("status",result.status()).put("latencyMs",result.latencyMs())
                    .put("decodedResponseBytes",result.response().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .put("anomalyScore",result.response().path("anomalyScore").asDouble());
        }
        if(client.active()!=0)throw new IllegalStateException("Permit leak");
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }
}
