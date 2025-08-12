package com.xammer.cloud.service.gcp;

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.*;
import com.google.protobuf.util.Timestamps;
import com.xammer.cloud.dto.gcp.GcpMetricDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class GcpMetricsService {

    private final GcpClientProvider gcpClientProvider;

    public GcpMetricsService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    public List<GcpMetricDto> getCpuUtilization(String gcpProjectId, String instanceId) throws IOException {
        Optional<MetricServiceClient> clientOpt = gcpClientProvider.getMetricServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();

        List<GcpMetricDto> metrics = new ArrayList<>();
        try (MetricServiceClient metricServiceClient = clientOpt.get()) {
            long startMillis = System.currentTimeMillis() - ((60 * 60 * 1000) * 24); // 24 hours
            TimeInterval interval = TimeInterval.newBuilder()
                    .setStartTime(Timestamps.fromMillis(startMillis))
                    .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()))
                    .build();

            String filter = String.format(
                "metric.type = \"compute.googleapis.com/instance/cpu/utilization\" AND resource.labels.instance_id = \"%s\"", instanceId);

            ListTimeSeriesRequest request = ListTimeSeriesRequest.newBuilder()
                    .setName("projects/" + gcpProjectId)
                    .setFilter(filter)
                    .setInterval(interval)
                    .setView(ListTimeSeriesRequest.TimeSeriesView.FULL)
                    .build();
            
            MetricServiceClient.ListTimeSeriesPagedResponse response = metricServiceClient.listTimeSeries(request);
            
            for (TimeSeries ts : response.iterateAll()) {
                for (Point point : ts.getPointsList()) {
                    metrics.add(new GcpMetricDto(
                        Timestamps.toString(point.getInterval().getStartTime()),
                        point.getValue().getDoubleValue() * 100 // as percentage
                    ));
                }
            }
            return metrics;
        } catch (ApiException e) {
            log.error("Failed to get metrics for project {}: {}", gcpProjectId, e.getStatusCode());
            return List.of();
        }
    }
}