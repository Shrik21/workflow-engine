package com.orchpilot.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Thread pools for asynchronous workflow execution and for the engine's own periodic work.
 *
 * <p>Asynchronous executions get a bounded queue and a caller-runs rejection policy on purpose: when
 * the engine is saturated it is better to slow the submitting request down than to accept work it
 * cannot durably track.
 */
@Configuration(proxyBeanMethods = false)
public class AsyncConfig {

    /** Bean name of the pool that runs asynchronous workflow executions. */
    public static final String WORKFLOW_EXECUTOR = "workflowExecutor";

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * @param properties engine configuration
     * @return pool used for asynchronous, scheduled and event-triggered executions
     */
    @Bean(name = WORKFLOW_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor workflowExecutor(WorkflowEngineProperties properties) {
        WorkflowEngineProperties.Execution config = properties.getExecution();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCorePoolSize());
        executor.setMaxPoolSize(config.getMaxPoolSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setThreadNamePrefix("wf-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("Workflow executor initialised: core={} max={} queue={}",
                config.getCorePoolSize(), config.getMaxPoolSize(), config.getQueueCapacity());
        return executor;
    }

    /**
     * @return scheduler for the cron poller, heartbeat refresh and recovery sweeps
     */
    @Bean(destroyMethod = "shutdown")
    public TaskScheduler workflowTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("wf-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.initialize();
        return scheduler;
    }
}
