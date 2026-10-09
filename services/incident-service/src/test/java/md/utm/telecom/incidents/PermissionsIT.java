package md.utm.telecom.incidents;

import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.simulator.repository.ScenarioCommandRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers persisted identity and permission boundaries against the real schema. */
class PermissionsIT extends IncidentServiceIntegrationTestSupport {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";

    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;
    @Autowired ScenarioCommandRepository commands;

    @Test
    void claimedRoleInRequestCannotStartScenario() throws Exception {
        analysts.saveAndFlush(new Analyst(ISSUER, "reader", "Reader"));
        UUID requestId = UUID.randomUUID();
        String forged = "{\"requestId\":\"" + requestId
                + "\",\"seed\":1,\"scopeId\":\"VOLTE-MD-CENTRAL\",\"role\":\"ADMIN\"}";
        mvc.perform(post("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD")
                        .session(authenticatedSession())
                        .with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject("reader"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isForbidden());
        assertEquals(0, commands.count());
    }

    @Test
    void sameSubjectFromAnotherIssuerIsNotTheSameAnalyst() throws Exception {
        Analyst known = analysts.saveAndFlush(new Analyst(ISSUER, "opaque-subject", "Known"));
        mvc.perform(me(ISSUER, "opaque-subject", authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analystId").value(known.getId().toString()));
        mvc.perform(me("https://different.example/realm", "opaque-subject", authenticatedSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void disabledAnalystCannotKeepUsingProfile() throws Exception {
        Analyst analyst = analysts.saveAndFlush(new Analyst(ISSUER, "disabled", "Disabled"));
        MockHttpSession session = authenticatedSession();
        mvc.perform(me(ISSUER, "disabled", session)).andExpect(status().isOk());
        analyst.setEnabled(false);
        analysts.saveAndFlush(analyst);
        mvc.perform(me(ISSUER, "disabled", session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private MockHttpServletRequestBuilder me(String issuer, String subject, MockHttpSession session) {
        return get("/api/auth/me").session(session)
                .with(oidcLogin().idToken(t -> t.issuer(issuer).subject(subject))
                        .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")));
    }
}
