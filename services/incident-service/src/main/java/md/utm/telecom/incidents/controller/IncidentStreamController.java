package md.utm.telecom.incidents.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.stream.IncidentStreamRegistry;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class IncidentStreamController {
    private final IncidentStreamRegistry streams;
    private final AnalystAccess access;

    public IncidentStreamController(IncidentStreamRegistry streams, AnalystAccess access) {
        this.streams = streams;
        this.access = access;
    }

    @GetMapping(value = "/api/incidents/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication, HttpServletRequest request,
                             HttpServletResponse response) {
        access.requireEnabled(authentication);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.open(request.getSession(false));
    }
}
