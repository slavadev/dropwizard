package io.dropwizard.health;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import io.dropwizard.util.Duration;
import io.dropwizard.util.Maps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthCheckManagerTest {
    private static final String NAME = "test";
    private static final String NAME_2 = "test2";
    private static final HealthCheckType READY = HealthCheckType.READY;
    private static final Duration SHUTDOWN_WAIT = Duration.seconds(5);

    @Mock
    private HealthCheckScheduler scheduler;

    @Test
    void shouldIgnoreUnconfiguredAddedHealthChecks() {
        // given
        final HealthCheckManager manager = new HealthCheckManager(Collections.emptyList(), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, true, Collections.emptyList());

        // when
        manager.onHealthCheckAdded(NAME, mock(HealthCheck.class));

        // then
        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldScheduleHealthCheckWhenConfiguredHealthCheckAdded() {
        // given
        final HealthCheckConfiguration config = new HealthCheckConfiguration();
        config.setName(NAME);
        config.setCritical(true);
        config.setSchedule(new Schedule());
        final HealthCheckManager manager = new HealthCheckManager(singletonList(config), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, true, Collections.emptyList());

        // when
        manager.onHealthCheckAdded(NAME, mock(HealthCheck.class));

        // then
        verifyCheckWasScheduled(scheduler, true);
    }

    @Test
    void shouldUnscheduleTaskWhenHealthCheckRemoved() {
        // given
        final ScheduledHealthCheck healthCheck = mock(ScheduledHealthCheck.class);
        final HealthCheckManager manager = new HealthCheckManager(Collections.emptyList(), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, true, Collections.emptyList());
        manager.setChecks(singletonMap(NAME, healthCheck));

        // when
        manager.onHealthCheckRemoved(NAME, mock(HealthCheck.class));

        // then
        verify(scheduler).unschedule(NAME);
    }

    @Test
    void shouldDoNothingWhenStateChangesForUnconfiguredHealthCheck() {
        // given
        final HealthCheckManager manager = new HealthCheckManager(Collections.emptyList(), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, true, Collections.emptyList());

        // when
        manager.onStateChanged(NAME, false);

        // then
        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldReportUnhealthyWhenInitialOverallStateIsFalse() {
        // given
        final HealthCheckConfiguration config = new HealthCheckConfiguration();
        config.setName(NAME);
        config.setCritical(true);
        config.setInitialState(false);
        config.setSchedule(new Schedule());
        final HealthCheckManager manager = new HealthCheckManager(singletonList(config), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, false, Collections.emptyList());
        manager.initializeAppHealth();
        final HealthCheck check = mock(HealthCheck.class);

        // when
        manager.onHealthCheckAdded(NAME, check);
        boolean beforeSuccessReadyStatus = manager.isHealthy();
        boolean beforeSuccessAliveStatus = manager.isHealthy("alive");
        manager.onStateChanged(NAME, true);
        boolean afterSuccessReadyStatus = manager.isHealthy();
        boolean afterSuccessAliveStatus = manager.isHealthy("alive");

        // then
        assertThat(beforeSuccessReadyStatus).isFalse();
        assertThat(beforeSuccessAliveStatus).isTrue();
        assertThat(afterSuccessReadyStatus).isTrue();
        assertThat(afterSuccessAliveStatus).isTrue();
        verifyCheckWasScheduled(scheduler, true);
    }

    @Test
    void shouldMarkServerUnhealthyWhenCriticalHealthCheckFails() {
        // given
        final HealthCheckConfiguration config = new HealthCheckConfiguration();
        config.setName(NAME);
        config.setCritical(true);
        config.setSchedule(new Schedule());
        final HealthCheckManager manager = new HealthCheckManager(singletonList(config), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, true, Collections.emptyList());
        manager.initializeAppHealth();
        final HealthCheck check = mock(HealthCheck.class);

        // when
        manager.onHealthCheckAdded(NAME, check);
        boolean beforeFailureReadyStatus = manager.isHealthy();
        boolean beforeFailureAliveStatus = manager.isHealthy("alive");
        manager.onStateChanged(NAME, false);
        boolean afterFailureReadyStatus = manager.isHealthy();
        boolean afterFailureAliveStatus = manager.isHealthy("alive");

        // then
        assertThat(beforeFailureReadyStatus).isTrue();
        assertThat(beforeFailureAliveStatus).isTrue();
        assertThat(afterFailureReadyStatus).isFalse();
        assertThat(afterFailureAliveStatus).isTrue();
        verifyCheckWasScheduled(scheduler, true);
    }

    @Test
    void shouldMarkServerNotAliveAndUnhealthyWhenCriticalAliveCheckFails() {
        // given
        final HealthCheckConfiguration config = new HealthCheckConfiguration();
        config.setName(NAME);
        config.setType(HealthCheckType.ALIVE);
        config.setSchedule(new Schedule());
        final HealthCheckManager manager = new HealthCheckManager(singletonList(config), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, true, Collections.emptyList());
        manager.initializeAppHealth();
        final HealthCheck check = mock(HealthCheck.class);

        // when
        manager.onHealthCheckAdded(NAME, check);
        boolean beforeFailureReadyStatus = manager.isHealthy();
        boolean beforeFailureAliveStatus = manager.isHealthy("alive");
        manager.onStateChanged(NAME, false);
        boolean afterFailureReadyStatus = manager.isHealthy();
        boolean afterFailureAliveStatus = manager.isHealthy("alive");

        // then
        assertThat(beforeFailureReadyStatus).isTrue();
        assertThat(beforeFailureAliveStatus).isTrue();
        assertThat(afterFailureReadyStatus).isFalse();
        assertThat(afterFailureAliveStatus).isFalse();
        verifyCheckWasScheduled(scheduler, true);
    }

    @Test
    void shouldMarkServerHealthyWhenCriticalHealthCheckRecovers() {
        // given
        final HealthCheckConfiguration config = new HealthCheckConfiguration();
        config.setName(NAME);
        config.setCritical(true);
        config.setSchedule(new Schedule());
        final HealthCheckManager manager = new HealthCheckManager(singletonList(config), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, true, Collections.emptyList());
        final HealthCheck check = mock(HealthCheck.class);

        // when
        manager.onHealthCheckAdded(NAME, check);
        manager.onStateChanged(NAME, false);
        boolean beforeRecovery = manager.isHealthy();
        manager.onStateChanged(NAME, true);
        boolean afterRecovery = manager.isHealthy();

        // then
        assertThat(beforeRecovery)
            .isFalse();
        assertThat(afterRecovery)
            .isTrue();
        ArgumentCaptor<ScheduledHealthCheck> checkCaptor = ArgumentCaptor.forClass(ScheduledHealthCheck.class);
        ArgumentCaptor<Boolean> healthyCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(scheduler).scheduleInitial(checkCaptor.capture());
        verify(scheduler, times(2)).schedule(checkCaptor.capture(), healthyCaptor.capture());
        assertThat(checkCaptor.getAllValues().get(0).getName())
            .isEqualTo(NAME);
        assertThat(checkCaptor.getAllValues().get(0).isCritical())
            .isTrue();
        assertThat(checkCaptor.getAllValues().get(1).getName())
            .isEqualTo(NAME);
        assertThat(checkCaptor.getAllValues().get(1).isCritical())
            .isTrue();
        assertThat(healthyCaptor.getAllValues().get(0))
            .isFalse();
        assertThat(checkCaptor.getAllValues().get(2).getName())
            .isEqualTo(NAME);
        assertThat(checkCaptor.getAllValues().get(2).isCritical())
            .isTrue();
        assertThat(healthyCaptor.getAllValues().get(1))
            .isTrue();
    }

    @Test
    void shouldNotChangeServerStateWhenNonCriticalHealthCheckFails() {
        // given
        final HealthCheckConfiguration config = new HealthCheckConfiguration();
        config.setName(NAME);
        config.setCritical(false);
        config.setSchedule(new Schedule());
        final HealthCheck check = mock(HealthCheck.class);
        final HealthCheckManager manager = new HealthCheckManager(singletonList(config), scheduler,
            new MetricRegistry(), SHUTDOWN_WAIT, true, Collections.emptyList());
        manager.initializeAppHealth();

        // when
        manager.onHealthCheckAdded(NAME, check);
        manager.onStateChanged(NAME, false);
        boolean afterFailure = manager.isHealthy();

        // then
        verifyCheckWasScheduled(scheduler, false);
        assertThat(afterFailure).isTrue();
    }

    @Test
    void shouldNotChangeServerStateWhenNonCriticalHealthCheckRecovers() {
        // given
        final List<HealthCheckConfiguration> configs = new ArrayList<>();
        final HealthCheckConfiguration nonCriticalConfig = new HealthCheckConfiguration();
        nonCriticalConfig.setName(NAME);
        nonCriticalConfig.setCritical(false);
        nonCriticalConfig.setSchedule(new Schedule());
        configs.add(nonCriticalConfig);
        final HealthCheckConfiguration criticalConfig = new HealthCheckConfiguration();
        criticalConfig.setName(NAME_2);
        criticalConfig.setCritical(true);
        criticalConfig.setSchedule(new Schedule());
        configs.add(criticalConfig);
        final HealthCheckManager manager = new HealthCheckManager(unmodifiableList(configs), scheduler, new MetricRegistry(),
            SHUTDOWN_WAIT, true, Collections.emptyList());
        final HealthCheck check = mock(HealthCheck.class);

        // when
        manager.onHealthCheckAdded(NAME, check);
        manager.onHealthCheckAdded(NAME_2, check);
        manager.onStateChanged(NAME, false);
        manager.onStateChanged(NAME_2, false);
        boolean beforeRecovery = manager.isHealthy();
        manager.onStateChanged(NAME, true);
        boolean afterRecovery = manager.isHealthy();

        // then
        assertThat(beforeRecovery)
            .isFalse();
        assertThat(afterRecovery)
            .isFalse();
        ArgumentCaptor<ScheduledHealthCheck> checkCaptor = ArgumentCaptor.forClass(ScheduledHealthCheck.class);
        ArgumentCaptor<Boolean> healthyCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(scheduler, times(2)).scheduleInitial(checkCaptor.capture());
        verify(scheduler, times(3)).schedule(checkCaptor.capture(), healthyCaptor.capture());
        assertThat(checkCaptor.getAllValues().get(0).getName())
            .isEqualTo(NAME);
        assertThat(checkCaptor.getAllValues().get(0).isCritical())
            .isFalse();
        assertThat(checkCaptor.getAllValues().get(1).getName())
            .isEqualTo(NAME_2);
        assertThat(checkCaptor.getAllValues().get(1).isCritical())
            .isTrue();
        assertThat(checkCaptor.getAllValues().get(2).getName())
            .isEqualTo(NAME);
        assertThat(checkCaptor.getAllValues().get(2).isCritical())
            .isFalse();
        assertThat(healthyCaptor.getAllValues().get(0))
            .isFalse();
        assertThat(checkCaptor.getAllValues().get(3).getName())
            .isEqualTo(NAME_2);
        assertThat(checkCaptor.getAllValues().get(3).isCritical())
            .isTrue();
        assertThat(healthyCaptor.getAllValues().get(1))
            .isFalse();
        assertThat(checkCaptor.getAllValues().get(4).getName())
            .isEqualTo(NAME);
        assertThat(checkCaptor.getAllValues().get(4).isCritical())
            .isFalse();
        assertThat(healthyCaptor.getAllValues().get(2))
            .isTrue();
    }

    @Test
    void shouldRecordNumberOfHealthyAndUnhealthyHealthChecks() {
        // given
        final Schedule schedule = new Schedule();
        final List<HealthCheckConfiguration> configs = new ArrayList<>();
        final HealthCheckConfiguration nonCriticalConfig = new HealthCheckConfiguration();
        nonCriticalConfig.setName(NAME);
        nonCriticalConfig.setCritical(false);
        nonCriticalConfig.setSchedule(schedule);
        configs.add(nonCriticalConfig);
        final HealthCheckConfiguration criticalConfig = new HealthCheckConfiguration();
        criticalConfig.setName(NAME_2);
        criticalConfig.setCritical(true);
        criticalConfig.setSchedule(schedule);
        configs.add(criticalConfig);
        final HealthCheck check = mock(HealthCheck.class);
        final MetricRegistry metrics = new MetricRegistry();
        final AtomicInteger healthyCounter = new AtomicInteger();
        final AtomicInteger unhealthyCounter = new AtomicInteger();

        final HealthStateListener countingListener = new HealthStateListener() {
            @Override
            public void onHealthyCheck(String healthCheckName) {
                healthyCounter.incrementAndGet();
            }

            @Override
            public void onUnhealthyCheck(String healthCheckName) {
                unhealthyCounter.incrementAndGet();
            }

            @Override
            public void onStateChanged(String healthCheckName, boolean healthy) {
            }
        };
        final HealthCheckManager manager = new HealthCheckManager(unmodifiableList(configs), scheduler, metrics, SHUTDOWN_WAIT, true,
            Collections.singleton(countingListener));

        final ScheduledHealthCheck check1 = new ScheduledHealthCheck(NAME, READY, nonCriticalConfig.isCritical(), check,
            schedule, new State(NAME, schedule.getFailureAttempts(), schedule.getSuccessAttempts(), true, manager),
            metrics.counter(NAME + ".healthy"), metrics.counter(NAME + ".unhealthy"));
        final ScheduledHealthCheck check2 = new ScheduledHealthCheck(NAME_2, READY, criticalConfig.isCritical(), check,
            schedule, new State(NAME, schedule.getFailureAttempts(), schedule.getSuccessAttempts(), true, manager),
            metrics.counter(NAME_2 + ".healthy"), metrics.counter(NAME_2 + ".unhealthy"));
        manager.setChecks(Maps.of(NAME, check1, NAME_2, check2));

        // then
        assertThat(metrics.gauge(manager.getAggregateHealthyName(), null).getValue())
            .isEqualTo(2L);
        assertThat(metrics.gauge(manager.getAggregateUnhealthyName(), null).getValue())
            .isEqualTo(0L);

        // when
        when(check.execute()).thenReturn(HealthCheck.Result.unhealthy("because"));
        // Fail 3 times, to trigger unhealthy state change
        check2.run();
        check2.run();
        check2.run();

        // then
        assertThat(metrics.gauge(manager.getAggregateHealthyName(), null).getValue())
            .isEqualTo(1L);
        assertThat(metrics.gauge(manager.getAggregateUnhealthyName(), null).getValue())
            .isEqualTo(1L);
        assertThat(unhealthyCounter.get()).isEqualTo(3);
        assertThat(healthyCounter.get()).isZero();
    }

    @Test
    void shouldContinueScheduledCheckingWhileDelayingShutdown() throws Exception {
        // given
        final int checkIntervalMillis = 10;
        final int shutdownWaitTimeMillis = 50;
        final int expectedCount = shutdownWaitTimeMillis / checkIntervalMillis - 1;
        final AtomicBoolean shutdownFailure = new AtomicBoolean(false);
        final CountingHealthCheck check = new CountingHealthCheck();
        final Schedule schedule = new Schedule();
        schedule.setCheckInterval(Duration.milliseconds(checkIntervalMillis));
        final HealthCheckConfiguration checkConfig = new HealthCheckConfiguration();
        checkConfig.setName("check1");
        checkConfig.setCritical(true);
        checkConfig.setSchedule(schedule);
        final List<HealthCheckConfiguration> configs = singletonList(checkConfig);
        final ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1);
        final HealthCheckScheduler scheduler = new HealthCheckScheduler(executorService);
        final MetricRegistry metrics = new MetricRegistry();
        final Duration shutdownWaitPeriod = Duration.milliseconds(shutdownWaitTimeMillis);

        // when
        final HealthCheckManager manager = new HealthCheckManager(configs, scheduler, metrics, shutdownWaitPeriod,
            true, Collections.emptyList());
        manager.onHealthCheckAdded("check1", check);
        // simulate JVM shutdown hook
        final Thread shutdownThread = new Thread(() -> {
            try {
                manager.notifyShutdownStarted();
            } catch (Exception e) {
                shutdownFailure.set(true);
                e.printStackTrace();
            }
        });
        Thread.sleep(20);
        long beforeCount = check.getCount();
        shutdownThread.start();
        shutdownThread.join();
        Thread.sleep(20);
        long afterCount = check.getCount();

        // then
        assertThat(shutdownFailure.get()).isFalse();
        assertThat(afterCount - beforeCount).isGreaterThanOrEqualTo(expectedCount);
    }

    private void verifyCheckWasScheduled(HealthCheckScheduler scheduler, boolean critical) {
        ArgumentCaptor<ScheduledHealthCheck> checkCaptor = ArgumentCaptor.forClass(ScheduledHealthCheck.class);
        verify(scheduler).scheduleInitial(checkCaptor.capture());
        assertThat(checkCaptor.getValue().getName())
            .isEqualTo(HealthCheckManagerTest.NAME);
        assertThat(checkCaptor.getValue().isCritical())
            .isEqualTo(critical);
    }

    private static class CountingHealthCheck extends HealthCheck {
        private static final Logger log = LoggerFactory.getLogger(CountingHealthCheck.class);
        private final Counter counter = new Counter();

        public long getCount() {
            return counter.getCount();
        }

        @Override
        protected Result check() throws Exception {
            counter.inc();
            log.info("count={}", getCount());
            return Result.healthy();
        }
    }
}
