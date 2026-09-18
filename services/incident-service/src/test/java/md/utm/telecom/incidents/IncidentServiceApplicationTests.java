package md.utm.telecom.incidents;

import md.utm.telecom.incidents.security.OidcTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import({TestcontainersConfiguration.class, OidcTestConfiguration.class})
@SpringBootTest
class IncidentServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
