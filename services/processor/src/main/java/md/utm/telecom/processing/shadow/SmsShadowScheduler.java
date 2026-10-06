package md.utm.telecom.processing.shadow;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A dedicated scheduler and eight workers keep shadow HTTP off the rule scheduler. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="telecom.sms-shadow.enabled",havingValue="true")
public class SmsShadowScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(SmsShadowScheduler.class);
    private final ExecutorService pool=Executors.newFixedThreadPool(8);
    private final SmsShadowWorker worker;
    public SmsShadowScheduler(SmsShadowWorker worker) { this.worker=worker; }
    @Scheduled(fixedDelayString="${telecom.sms-shadow.poll-interval:1000}",scheduler="smsShadowTaskScheduler")
    public void poll() {
        var tasks=new ArrayList<Callable<Void>>();
        for(int i=0;i<8;i++) tasks.add(() -> {
            for(int n=0;n<25 && !Thread.currentThread().isInterrupted();n++) if(!worker.evaluateOne()) break;
            return null;
        });
        try { for(var result:pool.invokeAll(tasks)) result.get(); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(Exception failure) { LOG.error("Shadow evidence retained for lease recovery",failure); }
    }
    @PreDestroy public void close() { pool.shutdownNow(); }
}
