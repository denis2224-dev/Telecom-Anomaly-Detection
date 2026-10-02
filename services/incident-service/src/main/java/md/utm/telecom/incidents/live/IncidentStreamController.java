package md.utm.telecom.incidents.live;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class IncidentStreamController {
    private final IncidentStream stream;
    public IncidentStreamController(IncidentStream stream) { this.stream = stream; }
    @GetMapping(value = "/api/incidents/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter open(HttpServletRequest request) { return stream.open(request.getSession(false)); }
}
