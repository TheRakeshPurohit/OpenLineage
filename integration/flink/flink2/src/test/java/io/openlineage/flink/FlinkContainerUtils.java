/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.flink;

import com.google.common.io.Resources;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import lombok.SneakyThrows;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public class FlinkContainerUtils {

  private static final String CONFLUENT_VERSION = "7.6.0";
  private static final String SCHEMA_REGISTRY_IMAGE = getRegistryImage();
  private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.6.0";
  private static final String ZOOKEEPER_IMAGE = "confluentinc/cp-zookeeper:" + CONFLUENT_VERSION;

  static final String FLINK_IMAGE =
      String.format("flink:%s-java11", System.getProperty("flink.version"));

  static MockServerContainer makeMockServerContainer(Network network) {
    return new MockServerContainer(
            DockerImageName.parse("mockserver/mockserver").withTag("mockserver-5.15.0"))
        .withNetwork(network)
        .withNetworkAliases("openlineageclient");
  }

  static GenericContainer<?> makeSchemaRegistryContainer(Network network, Startable startable) {
    return genericContainer(network, SCHEMA_REGISTRY_IMAGE, "schema-registry")
        .withExposedPorts(28081)
        .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka-host:9092")
        .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
        .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://schema-registry:8081,http://0.0.0.0:28081")
        .withEnv("SCHEMA_REGISTRY_LOG4J_ROOT_LOGLEVEL", "WARN")
        .dependsOn(startable);
  }

  static GenericContainer<?> makeKafkaContainer(Network network, Startable zookeeper) {
    return genericContainer(network, KAFKA_IMAGE, "kafka-host")
        .withExposedPorts(9092, 19092)
        .withEnv(
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
            "LISTENER_DOCKER_INTERNAL:PLAINTEXT,LISTENER_DOCKER_EXTERNAL:PLAINTEXT")
        .withEnv(
            "KAFKA_LISTENERS",
            "LISTENER_DOCKER_INTERNAL://kafka-host:9092,LISTENER_DOCKER_EXTERNAL://127.0.0.1:19092")
        .withEnv(
            "KAFKA_ADVERTISED_LISTENERS",
            "LISTENER_DOCKER_INTERNAL://kafka-host:9092,LISTENER_DOCKER_EXTERNAL://127.0.0.1:19092")
        .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "LISTENER_DOCKER_INTERNAL")
        .withEnv("KAFKA_ZOOKEEPER_CONNECT", "zookeeper:2181")
        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .withEnv("TOPIC_AUTO_CREATE", "true")
        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .withEnv("KAFKA_BROKER_ID", "1")
        .withEnv(
            "KAFKA_LOG4J_LOGGERS",
            "kafka.controller=WARN,kafka.producer.async.DefaultEventHandler=WARN,state.change.logger=INFO")
        .withEnv("LOG4J_LOGGER_KAFKA", "WARN")
        .dependsOn(zookeeper);
  }

  @SneakyThrows
  static GenericContainer<?> makeGenerateEventsContainer(Network network, Startable initTopics) {
    return genericContainer(network, SCHEMA_REGISTRY_IMAGE, "generate-events")
        .withCopyFileToContainer(
            MountableFile.forHostPath(Resources.getResource("InputEvent.avsc").getPath()),
            "/tmp/InputEvent.avsc")
        .withCopyFileToContainer(
            MountableFile.forHostPath(Resources.getResource("events.jsonl").getPath()),
            "/tmp/events.jsonl")
        .withCommand(
            "/bin/bash",
            "-c",
            Resources.toString(Resources.getResource("generate_events.sh"), StandardCharsets.UTF_8))
        .withEnv("SCHEMA_REGISTRY_LOG4J_ROOT_LOGLEVEL", "WARN")
        .dependsOn(initTopics);
  }

  static GenericContainer<?> makeZookeeperContainer(Network network) {
    return genericContainer(network, ZOOKEEPER_IMAGE, "zookeeper")
        .withExposedPorts(2181)
        .withEnv("ZOOKEEPER_CLIENT_PORT", "2181")
        .withEnv("ZOOKEEPER_SERVER_ID", "1")
        .withEnv("ZOOKEEPER_LOG4J_ROOT_LOGLEVEL", "WARN");
  }

  static GenericContainer<?> makeFlinkJobManagerContainer(
      String entrypointClass,
      Network network,
      List<Startable> startables,
      Properties jobProperties) {

    String inputTopics =
        jobProperties.getProperty("inputTopics", "io.openlineage.flink.kafka.input1");
    String outputTopics =
        jobProperties.getProperty("outputTopics", "io.openlineage.flink.kafka.output");
    String jobNameParam = "";
    if (jobProperties.getProperty("jobName") != null) {
      jobNameParam = " --job-name " + jobProperties.get("jobName") + " ";
    }
    String configPath = jobProperties.getProperty("configPath", "/opt/flink/lib/openlineage.yml");
    GenericContainer<?> container =
        genericContainer(network, FLINK_IMAGE, "jobmanager")
            .withExposedPorts(8081)
            .withFileSystemBind(getOpenLineageJarPath(), "/opt/flink/lib/openlineage.jar")
            .withFileSystemBind(getExampleAppJarPath(), "/opt/flink/lib/example-app.jar")
            .withCopyFileToContainer(
                MountableFile.forHostPath(Resources.getResource("openlineage.yml").getPath()),
                configPath)
            .withCopyFileToContainer(
                MountableFile.forHostPath(
                    Resources.getResource("log4j-console.properties").getPath()),
                "/opt/flink/conf/log4j-console.properties")
            .withCommand(
                "standalone-job "
                    + String.format("--job-classname %s ", entrypointClass)
                    + "--input-topics "
                    + inputTopics
                    + " --output-topics "
                    + outputTopics
                    + " --bootstraps "
                    + "kafka-host:9092"
                    + jobNameParam)
            .withEnv(
                "FLINK_PROPERTIES",
                "jobmanager.rpc.address: jobmanager\n"
                    + "execution.attached: true\n"
                    + "execution.job-status-changed-listeners: io.openlineage.flink.listener.OpenLineageJobStatusChangedListenerFactory")
            .withEnv("OPENLINEAGE_CONFIG", configPath)
            .withStartupTimeout(Duration.of(5, ChronoUnit.MINUTES))
            .dependsOn(startables);
    return container;
  }

  static GenericContainer<?> makeFlinkTaskManagerContainer(
      Network network, List<Startable> startables) {
    return genericContainer(network, FLINK_IMAGE, "taskmanager")
        .withFileSystemBind(getOpenLineageJarPath(), "/opt/flink/lib/openlineage.jar")
        .withFileSystemBind(getExampleAppJarPath(), "/opt/flink/lib/example-app.jar")
        .withCopyFileToContainer(MountableFile.forHostPath("../data/iceberg"), "/tmp/warehouse/")
        .withCopyFileToContainer(
            MountableFile.forHostPath(Resources.getResource("log4j-console.properties").getPath()),
            "/opt/flink/conf/log4j-console.properties")
        .withEnv(
            "FLINK_PROPERTIES",
            "jobmanager.rpc.address: jobmanager"
                + System.lineSeparator()
                + "taskmanager.numberOfTaskSlots: 2")
        .withCommand("taskmanager")
        .dependsOn(startables);
  }

  static void stopAll(List<GenericContainer<?>> containers) {
    containers.stream()
        .forEach(
            container -> {
              try {
                container.stop();
              } catch (Exception e) {
                // do nothing, perhaps already stopped
              }
            });
  }

  static boolean verifyJobManagerLogs(GenericContainer jobManager, String expectedLogMessage) {
    String logs = jobManager.getLogs();

    return logs.contains(expectedLogMessage);
  }

  static GenericContainer<?> genericContainer(Network network, String image, String hostname) {
    return new GenericContainer<>(DockerImageName.parse(image))
        .withNetwork(network)
        .withLogConsumer(of -> consumeOutput(hostname, of))
        .withNetworkAliases(hostname)
        .withReuse(true);
  }

  private static void consumeOutput(
      String prefix, org.testcontainers.containers.output.OutputFrame of) {
    try {
      switch (of.getType()) {
        case STDOUT:
          System.out.write(prefixEachLine(prefix, of.getUtf8String()).getBytes());
          break;
        case STDERR:
          System.err.write(prefixEachLine(prefix, of.getUtf8String()).getBytes());
          break;
        case END:
          System.out.println(of.getUtf8String()); // NOPMD
          break;
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  private static String prefixEachLine(String prefix, String output) {
    String prefixTag = "[" + prefix + "]";
    return prefixTag + output.replace(System.lineSeparator(), System.lineSeparator() + prefixTag);
  }

  static String getOpenLineageJarPath() {
    return Arrays.stream((new File("build/libs")).listFiles())
        .filter(file -> file.getName().startsWith("openlineage-flink"))
        .map(file -> file.getPath())
        .findAny()
        .get();
  }

  static String getExampleAppJarPath() {
    return Arrays.stream((new File("../fixtures")).listFiles())
        .filter(file -> file.getName().startsWith("flink2-examples"))
        .map(file -> file.getPath())
        .findAny()
        .get();
  }

  private static String getRegistryImage() {
    return "confluentinc/cp-schema-registry:" + CONFLUENT_VERSION;
  }
}
