package md.utm.telecom.analysts.controller;

import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalystControllerTest extends IncidentServiceIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;

    @Test
    void listsOnlyRequestedEnabledStateWithoutIdentitySecrets() throws Exception {
        analysts.save(new Analyst("issuer", "enabled-subject", "Alice"));
        var disabled = new Analyst("issuer", "disabled-subject", "Bob");
        disabled.setEnabled(false);
        analysts.save(disabled);

        mvc.perform(get("/api/analysts"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analysts").session(authenticatedSession())
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].displayName").value("Alice"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].issuer").doesNotExist())
                .andExpect(jsonPath("$[0].subject").doesNotExist());
        mvc.perform(get("/api/analysts?enabled=false").session(authenticatedSession())
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].displayName").value("Bob"));
    }
}
