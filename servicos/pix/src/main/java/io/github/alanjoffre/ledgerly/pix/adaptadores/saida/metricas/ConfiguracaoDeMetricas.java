package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.metricas;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** {@code http.server.requests} com histograma: sem ele, o painel não calcula p50 nem p99 (ADR-0042). */
@Singleton
class ConfiguracaoDeMetricas {

    /**
     * Faixas de tempo do histograma: de 5 ms a 5 s. A ponte do Micrometer para o OpenTelemetry só usa faixas
     * dadas assim; o {@code percentilesHistogram} chega ao Grafana sem nenhuma, e sem elas não há p99.
     */
    private static final double[] FAIXAS_EM_NANOS = {5e6, 10e6, 25e6, 50e6, 100e6, 250e6, 500e6, 1e9, 2.5e9, 5e9};

    @Produces
    @Singleton
    MeterFilter histogramaDasRotas() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().equals("http.server.requests")) {
                    return config;
                }
                return DistributionStatisticConfig.builder().serviceLevelObjectives(FAIXAS_EM_NANOS).build().merge(config);
            }
        };
    }
}
