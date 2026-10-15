package md.utm.telecom.incidents.security;

import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EnabledAnalystAccessIT extends IncidentServiceIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;

    @Test
    void disabledProfileCannotReadLegacyOrVersionedPrivateApis() throws Exception {
        var actor = analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Actor"));
        var login = oidcLogin().idToken(token -> token.issuer(actor.getIssuer()).subject(actor.getSubject()))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        mvc.perform(get("/api/incidents").session(authenticatedSession()).with(login)).andExpect(status().isOk());
        actor.setEnabled(false);
        analysts.flush();
        for (String path : new String[] {"/api/incidents", "/api/services", "/api/notifications",
                "/api/v2/inventory/cities", "/api/incidents/stream"}) {
            mvc.perform(get(path).session(authenticatedSession()).with(login))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/api/auth/csrf").session(authenticatedSession()).with(login))
                .andExpect(status().isOk());
    }
}
