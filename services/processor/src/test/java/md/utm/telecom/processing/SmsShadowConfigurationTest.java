package md.utm.telecom.processing;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.shadow.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsShadowConfigurationTest {
    @Test void defaultOffDoesNoInferenceAndEnabledShadowCannotBlockRuleScheduler() {
        var worker=mock(SmsShadowWorker.class);
        var runner=new ApplicationContextRunner().withBean(SmsShadowWorker.class,()->worker)
                .withUserConfiguration(SmsShadowScheduling.class,SmsShadowScheduler.class);
        runner.run(context->{
            assertTrue(context.getBeansOfType(SmsShadowScheduler.class).isEmpty());
            verifyNoInteractions(worker);
        });
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(worker.evaluateOne()).thenAnswer(ignored->{entered.countDown();release.await(3,TimeUnit.SECONDS);return false;});
        runner.withPropertyValues("telecom.sms-shadow.enabled=true").run(context->{
            try {
                assertFalse(context.getBeansOfType(SmsShadowScheduler.class).isEmpty());
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                var rules=context.getBean("taskScheduler",ThreadPoolTaskScheduler.class);
                var shadow=context.getBean("smsShadowTaskScheduler",ThreadPoolTaskScheduler.class);
                assertNotSame(rules,shadow);
                assertEquals("rules still run",rules.submit(()->"rules still run").get(1,TimeUnit.SECONDS));
            } finally { release.countDown(); }
        });
    }
}
