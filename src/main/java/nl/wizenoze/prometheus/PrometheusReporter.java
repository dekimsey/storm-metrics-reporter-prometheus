package nl.wizenoze.prometheus;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metered;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import com.codahale.metrics.graphite.GraphiteSender;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A reporter which publishes metric values to a Prometheus Gateway.
 *
 * This implementation is based upon {@code com.codahale.metrics.graphite.GraphiteReporter}.
 *
 * @see <a href="https://github.com/prometheus/pushgateway">Push acceptor for ephemeral and batch jobs</a>
 * @see <a href="https://metrics.dropwizard.io/3.1.0/apidocs/com/codahale/metrics/graphite/GraphiteReporter.html">com.codahale.metrics.graphite.GraphiteReporter</a>
 */
public class PrometheusReporter extends ScheduledReporter {

    /**
     * Returns a new {@link PrometheusReporter.Builder} for {@link PrometheusReporter}.
     *
     * @param registry the registry to report
     * @return a {@link PrometheusReporter.Builder} instance for a {@link PrometheusReporter}
     */
    public static PrometheusReporter.Builder forRegistry(MetricRegistry registry) {
        return new PrometheusReporter.Builder(registry);
    }

    /**
     * A builder for {@link PrometheusReporter} instances. Defaults to not using a prefix, using the
     * default clock, converting rates to events/second, converting durations to milliseconds, and
     * not filtering metrics.
     */
    public static class Builder {

        private final MetricRegistry registry;
        private Clock clock;
        private String prefix;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        private MetricFilter filter;

        private Builder(MetricRegistry registry) {
            this.registry = registry;
            this.clock = Clock.defaultClock();
            this.prefix = null;
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.filter = MetricFilter.ALL;
        }

        /**
         * Use the given {@link Clock} instance for the time.
         *
         * @param clock a {@link Clock} instance
         * @return {@code this}
         */
        public PrometheusReporter.Builder withClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Prefix all metric names with the given string.
         *
         * @param prefix the prefix for all metric names
         * @return {@code this}
         */
        public PrometheusReporter.Builder prefixedWith(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /**
         * Convert rates to the given time unit.
         *
         * @param rateUnit a unit of time
         * @return {@code this}
         */
        public PrometheusReporter.Builder convertRatesTo(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Convert durations to the given time unit.
         *
         * @param durationUnit a unit of time
         * @return {@code this}
         */
        public PrometheusReporter.Builder convertDurationsTo(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        /**
         * Only report metrics which match the given filter.
         *
         * @param filter a {@link MetricFilter}
         * @return {@code this}
         */
        public PrometheusReporter.Builder filter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Builds a {@link PrometheusReporter} with the given properties, sending metrics using the
         * given {@link GraphiteSender}.
         *
         * @param graphite a {@link GraphiteSender}
         * @return a {@link PrometheusReporter}
         */
        public PrometheusReporter build(GraphiteSender graphite) {
            return new PrometheusReporter(registry,
                    graphite,
                    clock,
                    prefix,
                    rateUnit,
                    durationUnit,
                    filter);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(PrometheusReporter.class);

    private final GraphiteSender graphite;
    private final Clock clock;
    private final String prefix;

    private PrometheusReporter(MetricRegistry registry,
            GraphiteSender graphite,
            Clock clock,
            String prefix,
            TimeUnit rateUnit,
            TimeUnit durationUnit,
            MetricFilter filter) {
        super(registry, "prometheus-reporter", filter, rateUnit, durationUnit);
        this.graphite = graphite;
        this.clock = clock;
        this.prefix = prefix;
    }

    @Override
    public void report(SortedMap<String, Gauge> gauges,
            SortedMap<String, Counter> counters,
            SortedMap<String, Histogram> histograms,
            SortedMap<String, Meter> meters,
            SortedMap<String, Timer> timers) {
        final long timestamp = clock.getTime() / 1000;

        // oh it'd be lovely to use Java 7 here
        try {
            if (!graphite.isConnected()) {
                graphite.connect();
            }

            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                reportGauge(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                reportCounter(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                reportHistogram(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                reportMetered(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                reportTimer(entry.getKey(), entry.getValue(), timestamp);
            }

            graphite.flush();
        } catch (IOException e) {
            LOGGER.warn("Unable to report to Graphite", graphite, e);
            try {
                graphite.close();
            } catch (IOException e1) {
                LOGGER.warn("Error closing Graphite", graphite, e);
            }
        }
    }

    @Override
    public void stop() {
        try {
            super.stop();
        } finally {
            try {
                graphite.close();
            } catch (IOException e) {
                LOGGER.debug("Error disconnecting from Graphite", graphite, e);
            }
        }
    }

    private void reportTimer(String name, Timer timer, long timestamp) throws IOException {
        final Snapshot snapshot = timer.getSnapshot();

        graphite.send(prefix(name, "max"), format(convertDuration(snapshot.getMax())), timestamp);
        graphite.send(prefix(name, "mean"), format(convertDuration(snapshot.getMean())), timestamp);
        graphite.send(prefix(name, "min"), format(convertDuration(snapshot.getMin())), timestamp);
        graphite.send(prefix(name, "stddev"),
                format(convertDuration(snapshot.getStdDev())),
                timestamp);
        graphite.send(prefix(name, "p50"),
                format(convertDuration(snapshot.getMedian())),
                timestamp);
        graphite.send(prefix(name, "p75"),
                format(convertDuration(snapshot.get75thPercentile())),
                timestamp);
        graphite.send(prefix(name, "p95"),
                format(convertDuration(snapshot.get95thPercentile())),
                timestamp);
        graphite.send(prefix(name, "p98"),
                format(convertDuration(snapshot.get98thPercentile())),
                timestamp);
        graphite.send(prefix(name, "p99"),
                format(convertDuration(snapshot.get99thPercentile())),
                timestamp);
        graphite.send(prefix(name, "p999"),
                format(convertDuration(snapshot.get999thPercentile())),
                timestamp);

        reportMetered(name, timer, timestamp);
    }

    private void reportMetered(String name, Metered meter, long timestamp) throws IOException {
        graphite.send(prefix(name, "count"), format(meter.getCount()), timestamp);
        graphite.send(prefix(name, "m1_rate"),
                format(convertRate(meter.getOneMinuteRate())),
                timestamp);
        graphite.send(prefix(name, "m5_rate"),
                format(convertRate(meter.getFiveMinuteRate())),
                timestamp);
        graphite.send(prefix(name, "m15_rate"),
                format(convertRate(meter.getFifteenMinuteRate())),
                timestamp);
        graphite.send(prefix(name, "mean_rate"),
                format(convertRate(meter.getMeanRate())),
                timestamp);
    }

    private void reportHistogram(String name, Histogram histogram, long timestamp)
            throws IOException {
        final Snapshot snapshot = histogram.getSnapshot();
        graphite.send(prefix(name, "count"), format(histogram.getCount()), timestamp);
        graphite.send(prefix(name, "max"), format(snapshot.getMax()), timestamp);
        graphite.send(prefix(name, "mean"), format(snapshot.getMean()), timestamp);
        graphite.send(prefix(name, "min"), format(snapshot.getMin()), timestamp);
        graphite.send(prefix(name, "stddev"), format(snapshot.getStdDev()), timestamp);
        graphite.send(prefix(name, "p50"), format(snapshot.getMedian()), timestamp);
        graphite.send(prefix(name, "p75"), format(snapshot.get75thPercentile()), timestamp);
        graphite.send(prefix(name, "p95"), format(snapshot.get95thPercentile()), timestamp);
        graphite.send(prefix(name, "p98"), format(snapshot.get98thPercentile()), timestamp);
        graphite.send(prefix(name, "p99"), format(snapshot.get99thPercentile()), timestamp);
        graphite.send(prefix(name, "p999"), format(snapshot.get999thPercentile()), timestamp);
    }

    private void reportCounter(String name, Counter counter, long timestamp) throws IOException {
        graphite.send(prefix(name, "count"), format(counter.getCount()), timestamp);
    }

    private void reportGauge(String name, Gauge gauge, long timestamp) throws IOException {
        final String value = format(gauge.getValue());
        if (value != null) {
            graphite.send(prefix(name), value, timestamp);
        }
    }

    private String format(Object o) {
        if (o instanceof Float) {
            return format(((Float) o).doubleValue());
        } else if (o instanceof Double) {
            return format(((Double) o).doubleValue());
        } else if (o instanceof Byte) {
            return format(((Byte) o).longValue());
        } else if (o instanceof Short) {
            return format(((Short) o).longValue());
        } else if (o instanceof Integer) {
            return format(((Integer) o).longValue());
        } else if (o instanceof Long) {
            return format(((Long) o).longValue());
        }
        return null;
    }

    private String prefix(String... components) {
        return MetricRegistry.name(prefix, components);
    }

    private String format(long n) {
        return Long.toString(n);
    }

    private String format(double v) {
        // the Carbon plaintext format is pretty underspecified, but it seems like it just wants
        // US-formatted digits
        return String.format(Locale.US, "%2.2f", v);
    }

}
