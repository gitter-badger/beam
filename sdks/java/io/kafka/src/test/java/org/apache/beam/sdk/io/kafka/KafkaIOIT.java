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
package org.apache.beam.sdk.io.kafka;

import static org.apache.beam.sdk.io.synthetic.SyntheticOptions.fromJsonString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.cloud.Timestamp;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.PipelineResult.State;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.io.common.HashingFn;
import org.apache.beam.sdk.io.common.IOITHelper;
import org.apache.beam.sdk.io.common.IOTestPipelineOptions;
import org.apache.beam.sdk.io.synthetic.SyntheticBoundedSource;
import org.apache.beam.sdk.io.synthetic.SyntheticSourceOptions;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.ExperimentalOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestPipelineOptions;
import org.apache.beam.sdk.testutils.NamedTestResult;
import org.apache.beam.sdk.testutils.metrics.IOITMetrics;
import org.apache.beam.sdk.testutils.metrics.MetricsReader;
import org.apache.beam.sdk.testutils.metrics.TimeMonitor;
import org.apache.beam.sdk.testutils.publishing.InfluxDBSettings;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.Keys;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableSet;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Duration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * IO Integration test for {@link org.apache.beam.sdk.io.kafka.KafkaIO}.
 *
 * <p>{@see https://beam.apache.org/documentation/io/testing/#i-o-transform-integration-tests} for
 * more details.
 *
 * <p>NOTE: This test sets retention policy of the messages so that all messages are retained in the
 * topic so that we could read them back after writing.
 */
@RunWith(JUnit4.class)
public class KafkaIOIT {

  private static final String READ_TIME_METRIC_NAME = "read_time";

  private static final String WRITE_TIME_METRIC_NAME = "write_time";

  private static final String RUN_TIME_METRIC_NAME = "run_time";

  private static final String READ_ELEMENT_METRIC_NAME = "kafka_read_element_count";

  private static final String NAMESPACE = KafkaIOIT.class.getName();

  private static final String TEST_ID = UUID.randomUUID().toString();

  private static final String TIMESTAMP = Timestamp.now().toString();

  private static String expectedHashcode;

  private static SyntheticSourceOptions sourceOptions;

  private static Options options;

  private static InfluxDBSettings settings;

  @Rule public TestPipeline writePipeline = TestPipeline.create();

  @Rule public TestPipeline readPipeline = TestPipeline.create();

  private static ExperimentalOptions sdfPipelineOptions;

  static {
    sdfPipelineOptions = PipelineOptionsFactory.create().as(ExperimentalOptions.class);
    ExperimentalOptions.addExperiment(sdfPipelineOptions, "use_sdf_read");
    ExperimentalOptions.addExperiment(sdfPipelineOptions, "beam_fn_api");
    sdfPipelineOptions.as(TestPipelineOptions.class).setBlockOnRun(false);
  }

  @Rule public TestPipeline sdfReadPipeline = TestPipeline.fromOptions(sdfPipelineOptions);

  private static KafkaContainer kafkaContainer;

  @BeforeClass
  public static void setup() throws IOException {
    options = IOITHelper.readIOTestPipelineOptions(Options.class);
    sourceOptions = fromJsonString(options.getSourceOptions(), SyntheticSourceOptions.class);
    if (options.isWithTestcontainers()) {
      setupKafkaContainer();
    } else {
      settings =
          InfluxDBSettings.builder()
              .withHost(options.getInfluxHost())
              .withDatabase(options.getInfluxDatabase())
              .withMeasurement(options.getInfluxMeasurement())
              .get();
    }
  }

  @AfterClass
  public static void afterClass() {
    if (kafkaContainer != null) {
      kafkaContainer.stop();
    }
  }

  @Test
  public void testKafkaIOReadsAndWritesCorrectlyInStreaming() throws IOException {
    // Use batch pipeline to write records.
    writePipeline
        .apply("Generate records", Read.from(new SyntheticBoundedSource(sourceOptions)))
        .apply("Measure write time", ParDo.of(new TimeMonitor<>(NAMESPACE, WRITE_TIME_METRIC_NAME)))
        .apply("Write to Kafka", writeToKafka());

    // Use streaming pipeline to read Kafka records.
    readPipeline.getOptions().as(Options.class).setStreaming(true);
    readPipeline
        .apply("Read from unbounded Kafka", readFromKafka())
        .apply("Measure read time", ParDo.of(new TimeMonitor<>(NAMESPACE, READ_TIME_METRIC_NAME)))
        .apply("Map records to strings", MapElements.via(new MapKafkaRecordsToStrings()))
        .apply("Counting element", ParDo.of(new CountingFn(NAMESPACE, READ_ELEMENT_METRIC_NAME)));

    PipelineResult writeResult = writePipeline.run();
    writeResult.waitUntilFinish();

    PipelineResult readResult = readPipeline.run();
    PipelineResult.State readState =
        readResult.waitUntilFinish(Duration.standardSeconds(options.getReadTimeout()));

    cancelIfTimeouted(readResult, readState);

    assertEquals(
        sourceOptions.numRecords,
        readElementMetric(readResult, NAMESPACE, READ_ELEMENT_METRIC_NAME));

    if (!options.isWithTestcontainers()) {
      Set<NamedTestResult> metrics = readMetrics(writeResult, readResult);
      IOITMetrics.publishToInflux(TEST_ID, TIMESTAMP, metrics, settings);
    }
  }

  @Test
  public void testKafkaIOReadsAndWritesCorrectlyInBatch() throws IOException {
    // Map of hashes of set size collections with 100b records - 10b key, 90b values.
    Map<Long, String> expectedHashes =
        ImmutableMap.of(
            1000L, "4507649971ee7c51abbb446e65a5c660",
            100_000_000L, "0f12c27c9a7672e14775594be66cad9a");
    expectedHashcode = getHashForRecordCount(sourceOptions.numRecords, expectedHashes);
    writePipeline
        .apply("Generate records", Read.from(new SyntheticBoundedSource(sourceOptions)))
        .apply("Measure write time", ParDo.of(new TimeMonitor<>(NAMESPACE, WRITE_TIME_METRIC_NAME)))
        .apply("Write to Kafka", writeToKafka().withTopic(options.getKafkaTopic() + "-batch"));

    PCollection<String> hashcode =
        readPipeline
            .apply(
                "Read from bounded Kafka",
                readFromBoundedKafka().withTopic(options.getKafkaTopic() + "-batch"))
            .apply(
                "Measure read time", ParDo.of(new TimeMonitor<>(NAMESPACE, READ_TIME_METRIC_NAME)))
            .apply("Map records to strings", MapElements.via(new MapKafkaRecordsToStrings()))
            .apply("Calculate hashcode", Combine.globally(new HashingFn()).withoutDefaults());

    PAssert.thatSingleton(hashcode).isEqualTo(expectedHashcode);

    PipelineResult writeResult = writePipeline.run();
    writeResult.waitUntilFinish();

    PipelineResult readResult = readPipeline.run();
    PipelineResult.State readState =
        readResult.waitUntilFinish(Duration.standardSeconds(options.getReadTimeout()));

    cancelIfTimeouted(readResult, readState);

    if (!options.isWithTestcontainers()) {
      Set<NamedTestResult> metrics = readMetrics(writeResult, readResult);
      IOITMetrics.publishToInflux(TEST_ID, TIMESTAMP, metrics, settings);
    }
  }

  // This test roundtrips a single KV<Null,Null> to verify that externalWithMetadata
  // can handle null keys and values correctly.
  @Test
  public void testKafkaIOExternalRoundtripWithMetadataAndNullKeysAndValues() throws IOException {

    writePipeline
        .apply(Create.of(KV.<byte[], byte[]>of(null, null)))
        .apply(
            "Write to Kafka", writeToKafka().withTopic(options.getKafkaTopic() + "-nullRoundTrip"));

    PCollection<Row> rows =
        readPipeline.apply(
            KafkaIO.<byte[], byte[]>read()
                .withBootstrapServers(options.getKafkaBootstrapServerAddresses())
                .withTopic(options.getKafkaTopic() + "-nullRoundTrip")
                .withConsumerConfigUpdates(ImmutableMap.of("auto.offset.reset", "earliest"))
                .withKeyDeserializerAndCoder(
                    ByteArrayDeserializer.class, NullableCoder.of(ByteArrayCoder.of()))
                .withValueDeserializerAndCoder(
                    ByteArrayDeserializer.class, NullableCoder.of(ByteArrayCoder.of()))
                .withMaxNumRecords(1)
                .externalWithMetadata());

    PAssert.thatSingleton(rows)
        .satisfies(
            actualRow -> {
              assertNull(actualRow.getString("key"));
              assertNull(actualRow.getString("value"));
              return null;
            });

    PipelineResult writeResult = writePipeline.run();
    writeResult.waitUntilFinish();

    PipelineResult readResult = readPipeline.run();
    PipelineResult.State readState =
        readResult.waitUntilFinish(Duration.standardSeconds(options.getReadTimeout()));

    cancelIfTimeouted(readResult, readState);
  }

  @Test
  public void testKafkaWithDynamicPartitions() throws IOException {
    AdminClient client =
        AdminClient.create(
            ImmutableMap.of("bootstrap.servers", options.getKafkaBootstrapServerAddresses()));
    String topicName = "DynamicTopicPartition-" + UUID.randomUUID();
    Map<Integer, String> records = new HashMap<>();
    for (int i = 0; i < 100; i++) {
      records.put(i, String.valueOf(i));
    }
    Map<Integer, String> moreRecords = new HashMap<>();
    for (int i = 100; i < 200; i++) {
      moreRecords.put(i, String.valueOf(i));
    }
    try {
      client.createTopics(ImmutableSet.of(new NewTopic(topicName, 1, (short) 1)));
      client.createPartitions(ImmutableMap.of(topicName, NewPartitions.increaseTo(1)));

      writePipeline
          .apply("Generate Write Elements", Create.of(records))
          .apply(
              "Write to Kafka",
              KafkaIO.<Integer, String>write()
                  .withBootstrapServers(options.getKafkaBootstrapServerAddresses())
                  .withTopic(topicName)
                  .withKeySerializer(IntegerSerializer.class)
                  .withValueSerializer(StringSerializer.class));

      writePipeline.run().waitUntilFinish(Duration.standardSeconds(15));

      Thread delayedWriteThread =
          new Thread(
              () -> {
                try {
                  Thread.sleep(20 * 1000); // wait 20 seconds before changing kafka
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }

                client.createPartitions(ImmutableMap.of(topicName, NewPartitions.increaseTo(2)));

                writePipeline
                    .apply("Second Pass generate Write Elements", Create.of(moreRecords))
                    .apply(
                        "Write more to Kafka",
                        KafkaIO.<Integer, String>write()
                            .withBootstrapServers(options.getKafkaBootstrapServerAddresses())
                            .withTopic(topicName)
                            .withKeySerializer(IntegerSerializer.class)
                            .withValueSerializer(StringSerializer.class));

                writePipeline.run().waitUntilFinish(Duration.standardSeconds(15));
              });

      delayedWriteThread.start();

      PCollection<Integer> values =
          sdfReadPipeline
              .apply(
                  "Read from Kafka",
                  KafkaIO.<Integer, String>read()
                      .withBootstrapServers(options.getKafkaBootstrapServerAddresses())
                      .withConsumerConfigUpdates(ImmutableMap.of("auto.offset.reset", "earliest"))
                      .withTopic(topicName)
                      .withDynamicRead(Duration.standardSeconds(5))
                      .withKeyDeserializer(IntegerDeserializer.class)
                      .withValueDeserializer(StringDeserializer.class))
              .apply("Key by Partition", ParDo.of(new KeyByPartition()))
              .apply(Window.into(FixedWindows.of(Duration.standardMinutes(1))))
              .apply("Group by Partition", GroupByKey.create())
              .apply("Get Partitions", Keys.create());

      PAssert.that(values).containsInAnyOrder(0, 1);

      PipelineResult readResult = sdfReadPipeline.run();

      State readState =
          readResult.waitUntilFinish(Duration.standardSeconds(options.getReadTimeout() / 2));

      cancelIfTimeouted(readResult, readState);

    } finally {
      client.deleteTopics(ImmutableSet.of(topicName));
    }
  }

  private static class KeyByPartition
      extends DoFn<KafkaRecord<Integer, String>, KV<Integer, KafkaRecord<Integer, String>>> {

    @ProcessElement
    public void processElement(
        @Element KafkaRecord<Integer, String> record,
        OutputReceiver<KV<Integer, KafkaRecord<Integer, String>>> receiver) {
      receiver.output(KV.of(record.getPartition(), record));
    }
  }

  private long readElementMetric(PipelineResult result, String namespace, String name) {
    MetricsReader metricsReader = new MetricsReader(result, namespace);
    return metricsReader.getCounterMetric(name);
  }

  private Set<NamedTestResult> readMetrics(PipelineResult writeResult, PipelineResult readResult) {
    BiFunction<MetricsReader, String, NamedTestResult> supplier =
        (reader, metricName) -> {
          long start = reader.getStartTimeMetric(metricName);
          long end = reader.getEndTimeMetric(metricName);
          return NamedTestResult.create(TEST_ID, TIMESTAMP, metricName, (end - start) / 1e3);
        };

    NamedTestResult writeTime =
        supplier.apply(new MetricsReader(writeResult, NAMESPACE), WRITE_TIME_METRIC_NAME);
    NamedTestResult readTime =
        supplier.apply(new MetricsReader(readResult, NAMESPACE), READ_TIME_METRIC_NAME);
    NamedTestResult runTime =
        NamedTestResult.create(
            TEST_ID, TIMESTAMP, RUN_TIME_METRIC_NAME, writeTime.getValue() + readTime.getValue());

    return ImmutableSet.of(readTime, writeTime, runTime);
  }

  private void cancelIfTimeouted(PipelineResult readResult, PipelineResult.State readState)
      throws IOException {

    // TODO(lgajowy) this solution works for dataflow only - it returns null when
    //  waitUntilFinish(Duration duration) exceeds provided duration.
    if (readState == null) {
      readResult.cancel();
    }
  }

  private KafkaIO.Write<byte[], byte[]> writeToKafka() {
    return KafkaIO.<byte[], byte[]>write()
        .withBootstrapServers(options.getKafkaBootstrapServerAddresses())
        .withKeySerializer(ByteArraySerializer.class)
        .withValueSerializer(ByteArraySerializer.class);
  }

  private KafkaIO.Read<byte[], byte[]> readFromBoundedKafka() {
    return readFromKafka().withMaxNumRecords(sourceOptions.numRecords);
  }

  private KafkaIO.Read<byte[], byte[]> readFromKafka() {
    return KafkaIO.readBytes()
        .withBootstrapServers(options.getKafkaBootstrapServerAddresses())
        .withConsumerConfigUpdates(ImmutableMap.of("auto.offset.reset", "earliest"));
  }

  private static class CountingFn extends DoFn<String, Void> {

    private final Counter elementCounter;

    CountingFn(String namespace, String name) {
      elementCounter = Metrics.counter(namespace, name);
    }

    @ProcessElement
    public void processElement() {
      elementCounter.inc(1L);
    }
  }
  /** Pipeline options specific for this test. */
  public interface Options extends IOTestPipelineOptions, StreamingOptions {

    @Description("Options for synthetic source.")
    @Validation.Required
    String getSourceOptions();

    void setSourceOptions(String sourceOptions);

    @Description("Kafka bootstrap server addresses")
    @Default.String("localhost:9092")
    String getKafkaBootstrapServerAddresses();

    void setKafkaBootstrapServerAddresses(String address);

    @Description("Kafka topic")
    @Validation.Required
    String getKafkaTopic();

    void setKafkaTopic(String topic);

    @Description("Time to wait for the events to be processed by the read pipeline (in seconds)")
    @Validation.Required
    Integer getReadTimeout();

    void setReadTimeout(Integer readTimeout);

    @Description("Whether to use testcontainers")
    @Default.Boolean(false)
    Boolean isWithTestcontainers();

    void setWithTestcontainers(Boolean withTestcontainers);

    @Description("Kafka container version in format 'X.Y.Z'. Use when useTestcontainers is true")
    @Nullable
    String getKafkaContainerVersion();

    void setKafkaContainerVersion(String kafkaContainerVersion);
  }

  private static class MapKafkaRecordsToStrings
      extends SimpleFunction<KafkaRecord<byte[], byte[]>, String> {
    @Override
    public String apply(KafkaRecord<byte[], byte[]> input) {
      String key = Arrays.toString(input.getKV().getKey());
      String value = Arrays.toString(input.getKV().getValue());
      return String.format("%s %s", key, value);
    }
  }

  public static String getHashForRecordCount(long recordCount, Map<Long, String> hashes) {
    String hash = hashes.get(recordCount);
    if (hash == null) {
      throw new UnsupportedOperationException(
          String.format("No hash for that record count: %s", recordCount));
    }
    return hash;
  }

  private static void setupKafkaContainer() {
    kafkaContainer =
        new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka")
                .withTag(options.getKafkaContainerVersion()));
    kafkaContainer.start();
    options.setKafkaBootstrapServerAddresses(kafkaContainer.getBootstrapServers());
  }
}
