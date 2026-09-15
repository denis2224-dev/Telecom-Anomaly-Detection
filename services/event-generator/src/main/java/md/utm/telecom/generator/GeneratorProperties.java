package md.utm.telecom.generator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("generator")
public record GeneratorProperties(
        @DefaultValue("15092026") long seed,
        @DefaultValue("classpath:seeded-intervals-v2.json") @NotNull Resource fixtures,
        @DefaultValue("10") @Min(1) @Max(10000) int count,
        Instant logicalTime,
        @DefaultValue("false") boolean preview) {}
