package md.utm.telecom.tracelink;

import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties="telecom.trace-link.enabled=false")
class TraceLinkDisabledIT extends IncidentServiceIntegrationTestSupport {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;

    @Test void disabledFeatureExposesNeitherReceiverNorReadRoutes() throws Exception {
        assertTrue(context.getBeansOfType(TraceLinkIncidentService.class).isEmpty());
        assertTrue(context.getBeansOfType(TraceLinkDetectionConsumer.class).isEmpty());
        var actor=oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        String base="/api/v2/trace-link/incidents";
        for (String path : java.util.List.of(base,base+"/"+"a".repeat(64),base+"/"+"a".repeat(64)+"/evidence"))
            mvc.perform(get(path).with(actor).session(authenticatedSession())).andExpect(status().isNotFound());
    }
}
