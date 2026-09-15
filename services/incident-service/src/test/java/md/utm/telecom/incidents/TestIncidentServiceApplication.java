package md.utm.telecom.incidents;

import org.springframework.boot.SpringApplication;

public class TestIncidentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(IncidentServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
