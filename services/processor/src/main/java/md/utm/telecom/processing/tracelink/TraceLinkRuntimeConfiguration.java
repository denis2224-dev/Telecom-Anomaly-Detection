package md.utm.telecom.processing.tracelink;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="telecom.trace-link.enabled",havingValue="true")
public class TraceLinkRuntimeConfiguration {
    @Bean TraceLinkWindowAdapter traceLinkWindowAdapter() { return new TraceLinkWindowAdapter(); }
    @Bean TraceLinkWindowStore traceLinkWindowStore(JdbcTemplate jdbc,PlatformTransactionManager manager,TraceLinkWindowAdapter adapter) {
        return new TraceLinkWindowStore(jdbc,manager,adapter);
    }
    @Bean TraceLinkDetectionWorker traceLinkDetectionWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,
            TraceLinkWindowAdapter adapter,Clock clock,MeterRegistry registry,
            @org.springframework.beans.factory.annotation.Value("${telecom.trace-link.mode:LIVE}") TraceLinkRuntimeEvaluation.Mode mode) {
        return new TraceLinkDetectionWorker(jdbc,manager,adapter,clock,registry,mode);
    }
    @Bean TraceLinkDeliveryPublisher traceLinkDeliveryPublisher(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka,
            @org.springframework.beans.factory.annotation.Value("${telecom.trace-link.topic:telecom.detections.trace-link.v1}") String topic) {
        return new TraceLinkDeliveryPublisher(jdbc,kafka,topic);
    }
    @Bean(name="traceLinkTaskScheduler") ThreadPoolTaskScheduler traceLinkTaskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("trace-link-poll-"); return scheduler;
    }
    @Bean(destroyMethod="close") Poller traceLinkPoller(TraceLinkDetectionWorker worker,TraceLinkDeliveryPublisher publisher,
            JdbcTemplate jdbc,MeterRegistry registry,
            @org.springframework.beans.factory.annotation.Value("${telecom.trace-link.workers:4}") int workers) {
        return new Poller(worker,publisher,jdbc,registry,workers);
    }
    public static final class Poller implements AutoCloseable {
        private final TraceLinkDetectionWorker worker;
        private final TraceLinkDeliveryPublisher publisher;
        private final JdbcTemplate jdbc;
        private final ExecutorService executor;
        private final Semaphore slots;
        private final int workers;
        Poller(TraceLinkDetectionWorker worker,TraceLinkDeliveryPublisher publisher,JdbcTemplate jdbc,MeterRegistry registry) {
            this(worker,publisher,jdbc,registry,4);
        }
        Poller(TraceLinkDetectionWorker worker,TraceLinkDeliveryPublisher publisher,JdbcTemplate jdbc,MeterRegistry registry,int workers) {
            if(workers<1 || workers>16) throw new IllegalArgumentException("Trace Link workers must be between 1 and 16");
            this.worker=worker;this.publisher=publisher;this.jdbc=jdbc;
            this.workers=workers;executor=Executors.newFixedThreadPool(workers);slots=new Semaphore(workers);
            registry.gauge("telecom.trace_link.workers.active",this,p->p.workers-p.slots.availablePermits());
            io.micrometer.core.instrument.Gauge.builder("telecom.trace_link.jobs.pending",this,p->p.pending()).register(registry);
            io.micrometer.core.instrument.Gauge.builder("telecom.trace_link.jobs.oldest.age",this,p->p.age()).register(registry);
        }
        @Scheduled(fixedDelayString="${telecom.trace-link.poll-interval:1000}",scheduler="traceLinkTaskScheduler")
        public void detect() {
            for(int i=0;i<workers && slots.tryAcquire();i++) {
                try { executor.submit(()-> {
                    try { for(int n=0;n<100 && !Thread.currentThread().isInterrupted() && worker.runOnce();n++) { } }
                    catch(Exception failure) { log(failure); }
                    finally { slots.release(); }
                }); } catch(java.util.concurrent.RejectedExecutionException shuttingDown) { slots.release(); return; }
            }
        }
        @Scheduled(fixedDelayString="${telecom.trace-link.poll-interval:1000}",scheduler="traceLinkTaskScheduler")
        public void deliver() { try { publisher.publish(); } catch(Exception failure) { log(failure); } }
        private double pending() { return jdbc.queryForObject("SELECT count(*) FROM app.trace_link_job WHERE completed_at IS NULL",Double.class); }
        private double age() { return jdbc.queryForObject("""
            SELECT COALESCE(GREATEST(0,extract(epoch FROM clock_timestamp()-min(w.created_at))),0)
            FROM app.trace_link_window w JOIN app.trace_link_job j USING(window_id) WHERE j.completed_at IS NULL
            """,Double.class); }
        private void log(Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Trace Link work retained for retry",failure); }
        @Override public void close() { executor.shutdownNow(); }
    }
}
