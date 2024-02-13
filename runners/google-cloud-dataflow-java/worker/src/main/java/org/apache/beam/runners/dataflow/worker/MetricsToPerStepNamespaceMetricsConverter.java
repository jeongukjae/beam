/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.dataflow.worker;

import com.google.api.services.dataflow.model.Base2Exponent;
import com.google.api.services.dataflow.model.BucketOptions;
import com.google.api.services.dataflow.model.DataflowHistogramValue;
import com.google.api.services.dataflow.model.Linear;
import com.google.api.services.dataflow.model.MetricValue;
import com.google.api.services.dataflow.model.OutlierStats;
import com.google.api.services.dataflow.model.PerStepNamespaceMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import org.apache.beam.sdk.io.gcp.bigquery.BigQuerySinkMetrics;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.util.HistogramData;

/**
 * Converts metric updates to {@link PerStepNamespaceMetrics} protos. Currently we only support
 * converting metrics from {@link BigQuerySinkMetrics} with this converter.
 */
public class MetricsToPerStepNamespaceMetricsConverter {
  /**
   * @param metricName The {@link MetricName} that represents this counter.
   * @param value The counter value.
   * @return If the conversion succeeds, {@code MetricValue} that represents this counter. Otherwise
   *     returns an empty optional
   */
  private static Optional<MetricValue> convertCounterToMetricValue(
      MetricName metricName, Long value) {
    if (value == 0 || !metricName.getNamespace().equals(BigQuerySinkMetrics.METRICS_NAMESPACE)) {
      return Optional.empty();
    }

    return BigQuerySinkMetrics.parseMetricName(metricName.getName())
        .filter(labeledName -> !labeledName.getBaseName().isEmpty())
        .map(
            labeledName ->
                new MetricValue()
                    .setMetric(labeledName.getBaseName())
                    .setMetricLabels(labeledName.getMetricLabels())
                    .setValueInt64(value));
  }

  /**
   * Adds {@code outlierStats} to {@code outputHistogram} if {@code inputHistogram} has recorded
   * overflow or underflow values.
   *
   * @param inputHistogram
   * @param outputHistogram
   */
  private static void addOutlierStatsToHistogram(
      HistogramData inputHistogram, DataflowHistogramValue outputHistogram) {
    long overflowCount = inputHistogram.getTopBucketCount();
    long underflowCount = inputHistogram.getBottomBucketCount();
    if (underflowCount == 0 && overflowCount == 0) {
      return;
    }

    OutlierStats outlierStats = new OutlierStats();
    if (underflowCount > 0) {
      outlierStats
          .setUnderflowCount(underflowCount)
          .setUnderflowMean(inputHistogram.getBottomBucketMean());
    }
    if (overflowCount > 0) {
      outlierStats
          .setOverflowCount(overflowCount)
          .setOverflowMean(inputHistogram.getTopBucketMean());
    }
    outputHistogram.setOutlierStats(outlierStats);
  }

  /**
   * @param metricName The {@link MetricName} that represents this Histogram.
   * @param value The histogram value. Currently we only support converting histograms that use
   *     {@code linear} or {@code exponential} buckets.
   * @return If this conversion succeeds, a {@code MetricValue} that represents this histogram.
   *     Otherwise returns an empty optional.
   */
  private static Optional<MetricValue> convertHistogramToMetricValue(
      MetricName metricName, HistogramData inputHistogram) {
    if (inputHistogram.getTotalCount() == 0L) {
      return Optional.empty();
    }

    Optional<BigQuerySinkMetrics.ParsedMetricName> labeledName =
        BigQuerySinkMetrics.parseMetricName(metricName.getName());
    if (!labeledName.isPresent() || labeledName.get().getBaseName().isEmpty()) {
      return Optional.empty();
    }

    DataflowHistogramValue outputHistogram = new DataflowHistogramValue();
    int numberOfBuckets = inputHistogram.getBucketType().getNumBuckets();

    if (inputHistogram.getBucketType() instanceof HistogramData.LinearBuckets) {
      HistogramData.LinearBuckets buckets =
          (HistogramData.LinearBuckets) inputHistogram.getBucketType();
      Linear linearOptions =
          new Linear()
              .setNumberOfBuckets(numberOfBuckets)
              .setWidth(buckets.getWidth())
              .setStart(buckets.getStart());
      outputHistogram.setBucketOptions(new BucketOptions().setLinear(linearOptions));
    } else if (inputHistogram.getBucketType() instanceof HistogramData.ExponentialBuckets) {
      HistogramData.ExponentialBuckets buckets =
          (HistogramData.ExponentialBuckets) inputHistogram.getBucketType();
      Base2Exponent expoenntialOptions =
          new Base2Exponent().setNumberOfBuckets(numberOfBuckets).setScale(buckets.getScale());
      outputHistogram.setBucketOptions(new BucketOptions().setExponential(expoenntialOptions));
    } else {
      return Optional.empty();
    }

    outputHistogram.setCount(inputHistogram.getTotalCount());
    List<Long> bucketCounts = new ArrayList<>(inputHistogram.getBucketType().getNumBuckets());

    for (int i = 0; i < inputHistogram.getBucketType().getNumBuckets(); i++) {
      bucketCounts.add(inputHistogram.getCount(i));
    }

    // Remove trailing 0 buckets.
    for (int i = bucketCounts.size() - 1; i >= 0; i--) {
      if (bucketCounts.get(i) != 0) {
        break;
      }
      bucketCounts.remove(i);
    }

    outputHistogram.setBucketCounts(bucketCounts);

    addOutlierStatsToHistogram(inputHistogram, outputHistogram);

    return Optional.of(
        new MetricValue()
            .setMetric(labeledName.get().getBaseName())
            .setMetricLabels(labeledName.get().getMetricLabels())
            .setValueHistogram(outputHistogram));
  }

  /**
   * @param stepName The unfused stage that these metrics are associated with.
   * @param counters Counter updates to convert.
   * @param histograms Histogram updates to convert.
   * @return Collection of {@code PerStepNamespaceMetrics} that represent these metric updates. Each
   *     {@code PerStepNamespaceMetrics} contains a list of {@code MetricUpdates} for a {unfused
   *     stage, metrics namespace} pair.
   */
  public static Collection<PerStepNamespaceMetrics> convert(
      String stepName, Map<MetricName, Long> counters, Map<MetricName, HistogramData> histograms) {

    Map<String, PerStepNamespaceMetrics> metricsByNamespace = new HashMap<>();
    for (Entry<MetricName, Long> entry : counters.entrySet()) {
      MetricName metricName = entry.getKey();

      Optional<MetricValue> metricValue = convertCounterToMetricValue(metricName, entry.getValue());
      if (!metricValue.isPresent()) {
        continue;
      }

      PerStepNamespaceMetrics stepNamespaceMetrics =
          metricsByNamespace.get(metricName.getNamespace());
      if (stepNamespaceMetrics == null) {
        stepNamespaceMetrics =
            new PerStepNamespaceMetrics()
                .setMetricValues(new ArrayList<>())
                .setOriginalStep(stepName)
                .setMetricsNamespace(metricName.getNamespace());
        metricsByNamespace.put(metricName.getNamespace(), stepNamespaceMetrics);
      }

      stepNamespaceMetrics.getMetricValues().add(metricValue.get());
    }

    for (Entry<MetricName, HistogramData> entry : histograms.entrySet()) {
      MetricName metricName = entry.getKey();
      Optional<MetricValue> metricValue =
          convertHistogramToMetricValue(metricName, entry.getValue());
      if (!metricValue.isPresent()) {
        continue;
      }

      PerStepNamespaceMetrics stepNamespaceMetrics =
          metricsByNamespace.get(metricName.getNamespace());
      if (stepNamespaceMetrics == null) {
        stepNamespaceMetrics =
            new PerStepNamespaceMetrics()
                .setMetricValues(new ArrayList<>())
                .setOriginalStep(stepName)
                .setMetricsNamespace(metricName.getNamespace());
        metricsByNamespace.put(metricName.getNamespace(), stepNamespaceMetrics);
      }

      stepNamespaceMetrics.getMetricValues().add(metricValue.get());
    }

    return metricsByNamespace.values();
  }
}
