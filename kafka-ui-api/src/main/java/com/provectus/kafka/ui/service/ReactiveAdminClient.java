package com.provectus.kafka.ui.service;

import static com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.provectus.kafka.ui.exception.IllegalEntityStateException;
import com.provectus.kafka.ui.exception.NotFoundException;
import com.provectus.kafka.ui.util.MapUtil;
import com.provectus.kafka.ui.util.NumberUtil;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeConfigsOptions;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionReplica;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.requests.DescribeLogDirsResponse;
import reactor.core.publisher.Mono;


@Log4j2
@RequiredArgsConstructor
public class ReactiveAdminClient implements Closeable {

  private enum SupportedFeature {
    INCREMENTAL_ALTER_CONFIGS,
    ALTER_CONFIGS
  }

  @Value
  public static class ClusterDescription {
    @Nullable
    Node controller;
    String clusterId;
    Collection<Node> nodes;
    Set<AclOperation> authorizedOperations;
  }

  public static Mono<ReactiveAdminClient> create(AdminClient adminClient) {
    return getClusterVersionImpl(adminClient)
        .map(ver ->
            new ReactiveAdminClient(
                adminClient,
                ver,
                Set.of(getSupportedUpdateFeatureForVersion(ver))));
  }

  private static SupportedFeature getSupportedUpdateFeatureForVersion(String versionStr) {
    float version = NumberUtil.parserClusterVersion(versionStr);
    return version <= 2.3f
        ? SupportedFeature.ALTER_CONFIGS
        : SupportedFeature.INCREMENTAL_ALTER_CONFIGS;
  }

  //TODO: discuss - maybe we should map kafka-library's exceptions to our exceptions here
  private static <T> Mono<T> toMono(KafkaFuture<T> future) {
    return Mono.create(sink -> future.whenComplete((res, ex) -> {
      if (ex != null) {
        sink.error(ex);
      } else {
        sink.success(res);
      }
    }));
  }

  //---------------------------------------------------------------------------------

  private final AdminClient client;
  private final String version;
  private final Set<SupportedFeature> features;

  public Mono<Set<String>> listTopics(boolean listInternal) {
    return toMono(client.listTopics(new ListTopicsOptions().listInternal(listInternal)).names());
  }

  public Mono<Void> deleteTopic(String topicName) {
    return toMono(client.deleteTopics(List.of(topicName)).all());
  }

  public String getVersion() {
    return version;
  }

  public Mono<Map<String, List<ConfigEntry>>> getTopicsConfig(Collection<String> topicNames) {
    List<ConfigResource> resources = topicNames.stream()
        .map(topicName -> new ConfigResource(ConfigResource.Type.TOPIC, topicName))
        .collect(toList());

    return toMono(
        client.describeConfigs(
            resources,
            new DescribeConfigsOptions().includeSynonyms(true)
        ).all())
        .map(config -> config.entrySet().stream()
            .collect(toMap(
                c -> c.getKey().name(),
                c -> new ArrayList<>(c.getValue().entries()))));
  }

  public Mono<Map<Integer, List<ConfigEntry>>> loadBrokersConfig(List<Integer> brokerIds) {
    List<ConfigResource> resources = brokerIds.stream()
        .map(brokerId -> new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(brokerId)))
        .collect(toList());
    return toMono(client.describeConfigs(resources).all())
        .map(config -> config.entrySet().stream()
            .collect(toMap(
                c -> Integer.valueOf(c.getKey().name()),
                c -> new ArrayList<>(c.getValue().entries()))));
  }

  public Mono<Map<String, TopicDescription>> describeTopics(Collection<String> topics) {
    return toMono(client.describeTopics(topics).all());
  }

  public Mono<Map<Integer, Map<String, DescribeLogDirsResponse.LogDirInfo>>> describeLogDirs() {
    return describeCluster()
        .map(d -> d.getNodes().stream().map(Node::id).collect(toList()))
        .flatMap(this::describeLogDirs);
  }

  public Mono<Map<Integer, Map<String, DescribeLogDirsResponse.LogDirInfo>>> describeLogDirs(
      Collection<Integer> brokerIds) {
    return toMono(client.describeLogDirs(brokerIds).all());
  }

  public Mono<ClusterDescription> describeCluster() {
    var r = client.describeCluster();
    var all = KafkaFuture.allOf(r.nodes(), r.clusterId(), r.controller(), r.authorizedOperations());
    return Mono.create(sink -> all.whenComplete((res, ex) -> {
      if (ex != null) {
        sink.error(ex);
      } else {
        try {
          sink.success(
              new ClusterDescription(
                  getUninterruptibly(r.controller()),
                  getUninterruptibly(r.clusterId()),
                  getUninterruptibly(r.nodes()),
                  getUninterruptibly(r.authorizedOperations())
              )
          );
        } catch (ExecutionException e) {
          // can't be here, because all futures already completed
        }
      }
    }));
  }

  private static Mono<String> getClusterVersionImpl(AdminClient client) {
    return toMono(client.describeCluster().controller()).flatMap(controller ->
        toMono(client.describeConfigs(
                List.of(new ConfigResource(
                    ConfigResource.Type.BROKER, String.valueOf(controller.id()))))
            .all()
            .thenApply(configs ->
                configs.values().stream()
                    .map(Config::entries)
                    .flatMap(Collection::stream)
                    .filter(entry -> entry.name().contains("inter.broker.protocol.version"))
                    .findFirst().map(ConfigEntry::value)
                    .orElse("1.0-UNKNOWN")
            )));
  }

  public Mono<String> getClusterVersion() {
    return getClusterVersionImpl(client);
  }

  public Mono<Void> deleteConsumerGroups(Collection<String> groupIds) {
    return toMono(client.deleteConsumerGroups(groupIds).all())
        .onErrorResume(GroupIdNotFoundException.class,
            th -> Mono.error(new NotFoundException("The group id does not exist")))
        .onErrorResume(GroupNotEmptyException.class,
            th -> Mono.error(new IllegalEntityStateException("The group is not empty")));
  }

  public Mono<Void> createTopic(String name,
                                int numPartitions,
                                short replicationFactor,
                                Map<String, String> configs) {
    return toMono(client.createTopics(
        List.of(new NewTopic(name, numPartitions, replicationFactor).configs(configs))).all());
  }

  public Mono<Void> alterPartitionReassignments(
      Map<TopicPartition, Optional<NewPartitionReassignment>> reassignments) {
    return toMono(client.alterPartitionReassignments(reassignments).all());
  }

  public Mono<Void> createPartitions(Map<String, NewPartitions> newPartitionsMap) {
    return toMono(client.createPartitions(newPartitionsMap).all());
  }

  public Mono<Void> updateTopicConfig(String topicName, Map<String, String> configs) {
    if (features.contains(SupportedFeature.INCREMENTAL_ALTER_CONFIGS)) {
      return incrementalAlterConfig(topicName, configs);
    } else {
      return alterConfig(topicName, configs);
    }
  }

  public Mono<List<String>> listConsumerGroups() {
    return toMono(client.listConsumerGroups().all())
        .map(lst -> lst.stream().map(ConsumerGroupListing::groupId).collect(toList()));
  }

  public Mono<Map<String, ConsumerGroupDescription>> describeConsumerGroups(List<String> groupIds) {
    return toMono(client.describeConsumerGroups(groupIds).all());
  }

  public Mono<Map<TopicPartition, OffsetAndMetadata>> listConsumerGroupOffsets(String groupId) {
    return toMono(client.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata())
        .map(MapUtil::removeNullValues);
  }

  public Mono<Void> alterConsumerGroupOffsets(String groupId, Map<TopicPartition, Long> offsets) {
    return toMono(client.alterConsumerGroupOffsets(
            groupId,
            offsets.entrySet().stream()
                .collect(toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue()))))
        .all());
  }

  public Mono<Map<TopicPartition, Long>> listOffsets(String topic,
                                                     OffsetSpec offsetSpec) {
    return topicPartitions(topic).flatMap(tps -> listOffsets(tps, offsetSpec));
  }

  public Mono<Map<TopicPartition, Long>> listOffsets(Collection<TopicPartition> partitions,
                                                     OffsetSpec offsetSpec) {
    return toMono(
        client.listOffsets(partitions.stream().collect(toMap(tp -> tp, tp -> offsetSpec))).all())
        .map(offsets -> offsets.entrySet()
            .stream()
            // filtering partitions for which offsets were not found
            .filter(e -> e.getValue().offset() >= 0)
            .collect(toMap(Map.Entry::getKey, e -> e.getValue().offset())));
  }

  private Mono<Set<TopicPartition>> topicPartitions(String topic) {
    return toMono(client.describeTopics(List.of(topic)).all())
        .map(r -> r.values().stream()
            .findFirst()
            .stream()
            .flatMap(d -> d.partitions().stream())
            .map(p -> new TopicPartition(topic, p.partition()))
            .collect(Collectors.toSet())
        );
  }

  public Mono<Void> updateBrokerConfigByName(Integer brokerId, String name, String value) {
    ConfigResource cr = new ConfigResource(ConfigResource.Type.BROKER, String.valueOf(brokerId));
    AlterConfigOp op = new AlterConfigOp(new ConfigEntry(name, value), AlterConfigOp.OpType.SET);
    return toMono(client.incrementalAlterConfigs(Map.of(cr, List.of(op))).all());
  }

  public Mono<Void> deleteRecords(Map<TopicPartition, Long> offsets) {
    var records = offsets.entrySet().stream()
        .map(entry -> Map.entry(entry.getKey(), RecordsToDelete.beforeOffset(entry.getValue())))
        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    return toMono(client.deleteRecords(records).all());
  }

  public Mono<Void> alterReplicaLogDirs(Map<TopicPartitionReplica, String> replicaAssignment) {
    return toMono(client.alterReplicaLogDirs(replicaAssignment).all());
  }

  private Mono<Void> incrementalAlterConfig(String topicName, Map<String, String> configs) {
    var config = configs.entrySet().stream()
        .flatMap(cfg -> Stream.of(
            new AlterConfigOp(
                new ConfigEntry(
                    cfg.getKey(),
                    cfg.getValue()),
                AlterConfigOp.OpType.SET)))
        .collect(toList());
    var topicResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
    return toMono(client.incrementalAlterConfigs(Map.of(topicResource, config)).all());
  }

  @SuppressWarnings("deprecation")
  private Mono<Void> alterConfig(String topicName, Map<String, String> configs) {
    List<ConfigEntry> configEntries = configs.entrySet().stream()
        .flatMap(cfg -> Stream.of(new ConfigEntry(cfg.getKey(), cfg.getValue())))
        .collect(toList());
    Config config = new Config(configEntries);
    var topicResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
    return toMono(client.alterConfigs(Map.of(topicResource, config)).all());
  }

  @Override
  public void close() {
    client.close();
  }
}
