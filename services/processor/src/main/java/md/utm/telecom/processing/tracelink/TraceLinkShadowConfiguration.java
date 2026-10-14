package md.utm.telecom.processing.tracelink;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import md.utm.telecom.processing.shadow.TraceLinkShadowClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="telecom.trace-link",name={"enabled","shadow.enabled"},havingValue="true")
public class TraceLinkShadowConfiguration {
    @Bean TraceLinkShadowClient traceLinkShadowClient(@Value("${ML_SERVICE_URL:http://ml-service:8090}")String url,Clock clock,MeterRegistry registry) {
        var client=new TraceLinkShadowClient(url,clock);
        registry.gauge("telecom.trace_link.shadow.inference.active",client,TraceLinkShadowClient::active);return client;
    }
    @Bean TraceLinkShadowWorker traceLinkShadowWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,TraceLinkWindowAdapter adapter,
            @Value("${telecom.trace-link.mode:LIVE}")TraceLinkRuntimeEvaluation.Mode mode,Clock clock,MeterRegistry registry,TraceLinkShadowClient client) {
        return new TraceLinkShadowWorker(jdbc,manager,adapter,mode,clock,registry,client::score);
    }
    @Bean(name="traceLinkShadowTaskScheduler")ThreadPoolTaskScheduler traceLinkShadowTaskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("trace-link-shadow-poll-");return scheduler;
    }
    @Bean(destroyMethod="close")Poller traceLinkShadowPoller(TraceLinkShadowWorker worker,JdbcTemplate jdbc,MeterRegistry registry,
            @Value("${telecom.trace-link.shadow.workers:2}")int workers) { return new Poller(worker,jdbc,registry,workers); }
    static final class Poller implements AutoCloseable {
        private final TraceLinkShadowWorker worker;
        private final ExecutorService executor;
        private final Semaphore slots;
        private final int workers;
        Poller(TraceLinkShadowWorker worker,JdbcTemplate jdbc,MeterRegistry registry,int workers) {
            if(workers<1 || workers>8)throw new IllegalArgumentException("Shadow workers must be between 1 and 8");
            this.worker=worker;this.workers=workers;slots=new Semaphore(workers);executor=Executors.newFixedThreadPool(workers);
            registry.gauge("telecom.trace_link.shadow.workers.active",this,p->p.workers-p.slots.availablePermits());
            io.micrometer.core.instrument.Gauge.builder("telecom.trace_link.shadow.jobs.pending",jdbc,j->j.queryForObject("SELECT count(*) FROM app.trace_link_shadow_job WHERE completed_at IS NULL",Double.class)).register(registry);
            io.micrometer.core.instrument.Gauge.builder("telecom.trace_link.shadow.jobs.oldest.age",jdbc,j->j.queryForObject("""
                SELECT COALESCE(GREATEST(0,extract(epoch FROM clock_timestamp()-min(e.evaluated_at))),0)
                FROM app.trace_link_evaluated_window e LEFT JOIN app.trace_link_shadow_result r USING(window_id)
                WHERE r.window_id IS NULL
                """,Double.class)).register(registry);
        }
        @Scheduled(fixedDelayString="${telecom.trace-link.shadow.poll-interval:1000}",scheduler="traceLinkShadowTaskScheduler")
        public void poll() {
            for(int i=0;i<workers && slots.tryAcquire();i++) {
                try { executor.submit(()->{try { for(int n=0;n<100 && !Thread.currentThread().isInterrupted() && worker.runOnce();n++) { } }
                    catch(Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Trace Link shadow work retained for retry",failure); }
                    finally {slots.release();}}); }
                catch(java.util.concurrent.RejectedExecutionException closing) {slots.release();return;}
            }
        }
        @Override public void close() {executor.shutdownNow();}
    }
}
