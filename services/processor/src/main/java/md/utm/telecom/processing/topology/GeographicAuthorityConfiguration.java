package md.utm.telecom.processing.topology;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import md.utm.telecom.observation.GeographicRuntimeConfiguration;

@Configuration(proxyBeanMethods = false)
@Import(GeographicRuntimeConfiguration.class)
public class GeographicAuthorityConfiguration { }
