/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.status.KafkaStatus;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.certs.CertManager;
import io.strimzi.certs.OpenSslCertManager;
import io.strimzi.operator.cluster.operator.resource.MockSharedEnvironmentProvider;
import io.strimzi.operator.cluster.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.common.model.ClientsCa;
import io.strimzi.operator.cluster.model.ClusterCa;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.KafkaPool;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.KafkaVersionChange;
import io.strimzi.operator.cluster.model.NodeRef;
import io.strimzi.operator.cluster.model.PodSetUtils;
import io.strimzi.operator.cluster.model.RestartReason;
import io.strimzi.operator.cluster.model.RestartReasons;
import io.strimzi.operator.cluster.model.ZookeeperCluster;
import io.strimzi.operator.cluster.model.nodepools.NodePoolUtils;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.StatefulSetOperator;
import io.strimzi.operator.common.MetricsAndLogging;
import io.strimzi.operator.common.PasswordGenerator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.cluster.operator.resource.SharedEnvironmentProvider;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.MockCertManager;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.PodOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.strimzi.operator.common.operator.resource.StrimziPodSetOperator;
import io.strimzi.platform.KubernetesVersion;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaAssemblyOperatorPodSetTest {
    private static final KafkaVersion.Lookup VERSIONS = KafkaVersionTestUtils.getKafkaVersionLookup();
    private static final SharedEnvironmentProvider SHARED_ENV_PROVIDER = new MockSharedEnvironmentProvider();
    private static final KubernetesVersion KUBERNETES_VERSION = KubernetesVersion.MINIMAL_SUPPORTED_VERSION;
    private static final MockCertManager CERT_MANAGER = new MockCertManager();
    private static final PasswordGenerator PASSWORD_GENERATOR = new PasswordGenerator(10, "a", "a");
    private final static KafkaVersionChange VERSION_CHANGE = new KafkaVersionChange(
            VERSIONS.defaultVersion(),
            VERSIONS.defaultVersion(),
            VERSIONS.defaultVersion().protocolVersion(),
            VERSIONS.defaultVersion().messageVersion()
    );
    private static final String NAMESPACE = "my-ns";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final Kafka KAFKA = new KafkaBuilder()
                .withNewMetadata()
                    .withName(CLUSTER_NAME)
                    .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withReplicas(3)
                        .withListeners(new GenericKafkaListenerBuilder()
                                .withName("plain")
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .build())
                        .withNewEphemeralStorage()
                        .endEphemeralStorage()
                    .endKafka()
                    .withNewZookeeper()
                        .withReplicas(3)
                        .withNewEphemeralStorage()
                        .endEphemeralStorage()
                    .endZookeeper()
                .endSpec()
                .build();
    private static final List<KafkaPool> POOLS = NodePoolUtils.createKafkaPools(Reconciliation.DUMMY_RECONCILIATION, KAFKA, null, Map.of(), Map.of(), false, SHARED_ENV_PROVIDER);
    private static final KafkaCluster KAFKA_CLUSTER = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, POOLS, VERSIONS, false, null, SHARED_ENV_PROVIDER);

    private static final Map<Integer, Map<String, String>> ADVERTISED_HOSTNAMES = Map.of(
            0, Map.of("PLAIN_9092", "broker-0"),
            1, Map.of("PLAIN_9092", "broker-1"),
            2, Map.of("PLAIN_9092", "broker-2"),
            3, Map.of("PLAIN_9092", "broker-3"),
            4, Map.of("PLAIN_9092", "broker-4")
    );

    private static final Map<Integer, Map<String, String>> ADVERTISED_PORTS = Map.of(
            0, Map.of("PLAIN_9092", "10000"),
            1, Map.of("PLAIN_9092", "10001"),
            2, Map.of("PLAIN_9092", "10002"),
            3, Map.of("PLAIN_9092", "10003"),
            4, Map.of("PLAIN_9092", "10004")
    );

    private final static ClusterCa CLUSTER_CA = new ClusterCa(
            Reconciliation.DUMMY_RECONCILIATION,
            CERT_MANAGER,
            PASSWORD_GENERATOR,
            CLUSTER_NAME,
            ResourceUtils.createInitialCaCertSecret(NAMESPACE, CLUSTER_NAME, AbstractModel.clusterCaCertSecretName(CLUSTER_NAME), MockCertManager.clusterCaCert(), MockCertManager.clusterCaCertStore(), "123456"),
            ResourceUtils.createInitialCaKeySecret(NAMESPACE, CLUSTER_NAME, AbstractModel.clusterCaKeySecretName(CLUSTER_NAME), MockCertManager.clusterCaKey())
    );

    private final static ClientsCa CLIENTS_CA = new ClientsCa(
            Reconciliation.DUMMY_RECONCILIATION,
            new OpenSslCertManager(),
            new PasswordGenerator(10, "a", "a"),
            KafkaResources.clientsCaCertificateSecretName(CLUSTER_NAME),
            ResourceUtils.createInitialCaCertSecret(NAMESPACE, CLUSTER_NAME, AbstractModel.clusterCaCertSecretName(CLUSTER_NAME), MockCertManager.clusterCaCert(), MockCertManager.clusterCaCertStore(), "123456"),
            KafkaResources.clientsCaKeySecretName(CLUSTER_NAME),
            ResourceUtils.createInitialCaKeySecret(NAMESPACE, CLUSTER_NAME, AbstractModel.clusterCaKeySecretName(CLUSTER_NAME), MockCertManager.clusterCaKey()),
            365,
            30,
            true,
            null
    );

    protected static Vertx vertx;
    private static WorkerExecutor sharedWorkerExecutor;

    @BeforeAll
    public static void beforeAll() {
        vertx = Vertx.vertx();
        sharedWorkerExecutor = vertx.createSharedWorkerExecutor("kubernetes-ops-pool");
    }

    @AfterAll
    public static void afterAll() {
        sharedWorkerExecutor.close();
        vertx.close();
    }

    /**
     * Tests the regular reconciliation of the Kafka cluster when the UseStrimziPodsSet is already enabled for some time
     *
     * @param context   Test context
     */
    @Test
    public void testRegularReconciliation(VertxTestContext context)  {
        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS, SHARED_ENV_PROVIDER);
        StrimziPodSet zkPodSet = zkCluster.generatePodSet(KAFKA.getSpec().getZookeeper().getReplicas(), false, null, null, podNum -> null);
        StrimziPodSet kafkaPodSet = KAFKA_CLUSTER.generatePodSets(false, null, null, brokerId -> null).get(0);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(List.of()));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Zoo
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getComponentName()))).thenReturn(Future.succeededFuture(zkPodSet));
        when(mockPodSetOps.reconcile(any(), any(), eq(zkCluster.getComponentName()), any())).thenReturn(Future.succeededFuture(ReconcileResult.noop(zkPodSet)));
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(List.of(kafkaPodSet)));
        when(mockPodSetOps.batchReconcile(any(), any(), any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.noop(kafkaPodSet));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(zkCluster.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS is queried and deleted if it still exists
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(zkCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS);

        MockZooKeeperReconciler zr = new MockZooKeeperReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                null,
                0,
                CLUSTER_CA);

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                Map.of(),
                Map.of(),
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config,
                zr,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    assertThat(zr.maybeRollZooKeeperInvocations, is(1));
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-0")), empty());
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-1")), empty());
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-2")), empty());

                    assertThat(kr.maybeRollKafkaInvocations, is(1));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSet, "my-cluster-kafka-0")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSet, "my-cluster-kafka-1")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSet, "my-cluster-kafka-2")), is(RestartReasons.empty()));

                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(4));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-kafka-0", "my-cluster-kafka-1", "my-cluster-kafka-2", "my-cluster-kafka-config")));

                    assertThat(cmDeletionCaptor.getAllValues().size(), is(0));

                    async.flag();
                })));
    }

    /**
     * Tests the first reconciliation of the Kafka cluster after the UseStrimziPodsSet is enabled for the first time
     *
     * @param context   Test context
     */
    @Test
    public void testFirstReconciliation(VertxTestContext context)  {
        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS, SHARED_ENV_PROVIDER);
        StrimziPodSet zkPodSet = zkCluster.generatePodSet(KAFKA.getSpec().getZookeeper().getReplicas(), false, null, null, podNum -> null);
        StrimziPodSet kafkaPodSet = KAFKA_CLUSTER.generatePodSets(false, null, null, brokerId -> null).get(0);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(KAFKA_CLUSTER.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Zoo
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getComponentName()))).thenReturn(Future.succeededFuture(null)); // The PodSet does not exist yet in the first reconciliation
        when(mockPodSetOps.reconcile(any(), any(), eq(zkCluster.getComponentName()), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(zkPodSet)));
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(List.of()));
        when(mockPodSetOps.batchReconcile(any(), any(), any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.created(kafkaPodSet));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        // Zoo
        when(mockStsOps.getAsync(any(), eq(zkCluster.getComponentName()))).thenReturn(Future.succeededFuture(new StatefulSetBuilder().withNewMetadata().withName(zkCluster.getComponentName()).withNamespace(NAMESPACE).endMetadata().build())); // Zoo STS still exists in the first reconciliation
        when(mockStsOps.deleteAsync(any(), any(), eq(zkCluster.getComponentName()), eq(false))).thenReturn(Future.succeededFuture()); // The Zoo STS will be deleted during the reconciliation
        // Kafka
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(new StatefulSetBuilder().withNewMetadata().withName(KAFKA_CLUSTER.getComponentName()).withNamespace(NAMESPACE).endMetadata().build()));
        when(mockStsOps.deleteAsync(any(), any(), eq(KAFKA_CLUSTER.getComponentName()), eq(false))).thenReturn(Future.succeededFuture()); // The Kafka STS will be deleted during the reconciliation

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(zkCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS);

        MockZooKeeperReconciler zr = new MockZooKeeperReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                null,
                0,
                CLUSTER_CA);

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                Map.of(),
                Map.of(),
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config,
                zr,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Test that the old Zoo STS was deleted
                    verify(mockStsOps, times(1)).deleteAsync(any(), any(), eq(zkCluster.getComponentName()), eq(false));

                    assertThat(zr.maybeRollZooKeeperInvocations, is(1));
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-0")), empty());
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-1")), empty());
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-2")), empty());

                    assertThat(kr.maybeRollKafkaInvocations, is(1));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSet, "my-cluster-kafka-0")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSet, "my-cluster-kafka-1")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSet, "my-cluster-kafka-2")), is(RestartReasons.empty()));

                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(4));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-kafka-0", "my-cluster-kafka-1", "my-cluster-kafka-2", "my-cluster-kafka-config")));

                    assertThat(cmDeletionCaptor.getAllValues().size(), is(0));

                    async.flag();
                })));
    }

    /**
     * Tests the regular reconciliation of the Kafka cluster which results in some rolling updates
     *
     * @param context   Test context
     */
    @Test
    public void testReconciliationWithRoll(VertxTestContext context)  {
        Kafka oldKafka = new KafkaBuilder(KAFKA)
                .editSpec()
                    .editZookeeper()
                        .withImage("old-image:latest")
                    .endZookeeper()
                    .editKafka()
                        .withImage("old-image:latest")
                    .endKafka()
                .endSpec()
                .build();

        ZookeeperCluster oldZkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, VERSIONS, SHARED_ENV_PROVIDER);
        StrimziPodSet oldZkPodSet = oldZkCluster.generatePodSet(KAFKA.getSpec().getZookeeper().getReplicas(), false, null, null, podNum -> null);
        List<KafkaPool> oldPools = NodePoolUtils.createKafkaPools(Reconciliation.DUMMY_RECONCILIATION, oldKafka, null, Map.of(), Map.of(), false, SHARED_ENV_PROVIDER);
        KafkaCluster oldKafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, oldPools, VERSIONS, false, null, SHARED_ENV_PROVIDER);
        StrimziPodSet oldKafkaPodSet = oldKafkaCluster.generatePodSets(false, null, null, brokerId -> null).get(0);

        ZookeeperCluster newZkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS, SHARED_ENV_PROVIDER);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(oldKafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaCluster.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        when(mockCmOps.reconcile(any(), any(), startsWith("my-cluster-kafka-"), any())).thenReturn(Future.succeededFuture());
        when(mockCmOps.deleteAsync(any(), any(), eq("my-cluster-kafka-config"), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.getAsync(any(), eq(newZkCluster.getComponentName()))).thenReturn(Future.succeededFuture(oldZkPodSet));
        when(mockPodSetOps.reconcile(any(), any(), eq(newZkCluster.getComponentName()), any())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(List.of(oldKafkaPodSet)));
        when(mockPodSetOps.batchReconcile(any(), any(), any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.noop(podSet));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(newZkCluster.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS is queried and deleted if it still exists
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(newZkCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS);

        MockZooKeeperReconciler zr = new MockZooKeeperReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                null,
                0,
                CLUSTER_CA);

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                Map.of(),
                Map.of(),
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config,
                zr,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    assertThat(zr.maybeRollZooKeeperInvocations, is(1));
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(oldZkPodSet, "my-cluster-zookeeper-0")), is(List.of("Pod has old revision")));
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(oldZkPodSet, "my-cluster-zookeeper-1")), is(List.of("Pod has old revision")));
                    assertThat(zr.zooPodNeedsRestart.apply(podFromPodSet(oldZkPodSet, "my-cluster-zookeeper-2")), is(List.of("Pod has old revision")));

                    assertThat(kr.maybeRollKafkaInvocations, is(1));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSet, "my-cluster-kafka-0")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSet, "my-cluster-kafka-1")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSet, "my-cluster-kafka-2")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));

                    async.flag();
                })));
    }

    /**
     * Tests reconciliation with scale-up from 1 to 3 ZooKeeper pods
     *
     * @param context   Test context
     */
    @Test
    public void testScaleUp(VertxTestContext context)  {
        Kafka oldKafka = new KafkaBuilder(KAFKA)
                .editSpec()
                    .editZookeeper()
                        .withReplicas(1)
                    .endZookeeper()
                    .editKafka()
                        .withReplicas(1)
                    .endKafka()
                .endSpec()
                .build();

        ZookeeperCluster oldZkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, VERSIONS, SHARED_ENV_PROVIDER);
        StrimziPodSet oldZkPodSet = oldZkCluster.generatePodSet(oldKafka.getSpec().getZookeeper().getReplicas(), false, null, null, podNum -> null);
        List<KafkaPool> oldPools = NodePoolUtils.createKafkaPools(Reconciliation.DUMMY_RECONCILIATION, oldKafka, null, Map.of(), Map.of(), false, SHARED_ENV_PROVIDER);
        KafkaCluster oldKafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, oldPools, VERSIONS, false, null, SHARED_ENV_PROVIDER);
        StrimziPodSet oldKafkaPodSet = oldKafkaCluster.generatePodSets(false, null, null, brokerId -> null).get(0);

        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS, SHARED_ENV_PROVIDER);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.getAsync(any(), any())).thenReturn(Future.succeededFuture(new Secret()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(oldKafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaCluster.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Kafka
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getComponentName()))).thenReturn(Future.succeededFuture(oldZkPodSet));
        ArgumentCaptor<StrimziPodSet> zkPodSetCaptor =  ArgumentCaptor.forClass(StrimziPodSet.class);
        when(mockPodSetOps.reconcile(any(), any(), eq(zkCluster.getComponentName()), zkPodSetCaptor.capture())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));
        // Zoo
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(List.of(oldKafkaPodSet)));
        ArgumentCaptor<List<StrimziPodSet>> kafkaPodSetBatchCaptor =  ArgumentCaptor.forClass(List.class);
        when(mockPodSetOps.batchReconcile(any(), any(), kafkaPodSetBatchCaptor.capture(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.noop(podSet));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(zkCluster.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS is queried and deleted if it still exists
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(zkCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.readiness(any(), any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS);

        MockZooKeeperReconciler zr = new MockZooKeeperReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                null,
                1,
                CLUSTER_CA);

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                Map.of(),
                Map.of(),
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config,
                zr,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Scale-up of Zoo is done pod by pod => the reconcile method is called 3 times with 1, 2 and 3 pods.
                    assertThat(zkPodSetCaptor.getAllValues().size(), is(3));
                    assertThat(zkPodSetCaptor.getAllValues().get(0).getSpec().getPods().size(), is(1)); // => first capture is from zkPodSet() with old replica count
                    assertThat(zkPodSetCaptor.getAllValues().get(1).getSpec().getPods().size(), is(2)); // => second capture is from zkScalingUp() with new replica count
                    assertThat(zkPodSetCaptor.getAllValues().get(2).getSpec().getPods().size(), is(3)); // => third capture is from zkScalingUp() with new replica count

                    // Still one maybe-roll invocation
                    assertThat(zr.maybeRollZooKeeperInvocations, is(1));

                    // Scale-up of Kafka is done in one go => we should see two invocations from regular patching
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(0).getSpec().getPods().size(), is(3));

                    // Still one maybe-roll invocation
                    assertThat(kr.maybeRollKafkaInvocations, is(1));

                    // CMs for all pods are reconciled
                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(4));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-kafka-0", "my-cluster-kafka-1", "my-cluster-kafka-2", "my-cluster-kafka-config")));

                    // Only the shared CM is deleted
                    assertThat(cmDeletionCaptor.getAllValues().size(), is(0));

                    async.flag();
                })));
    }

    /**
     * Tests reconciliation with scale-down from 5 to 3 ZooKeeper pods
     *
     * @param context   Test context
     */
    @Test
    public void testScaleDown(VertxTestContext context)  {
        Kafka oldKafka = new KafkaBuilder(KAFKA)
                .editSpec()
                    .editZookeeper()
                        .withReplicas(5)
                    .endZookeeper()
                    .editKafka()
                        .withReplicas(5)
                    .endKafka()
                .endSpec()
                .build();

        ZookeeperCluster oldZkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, VERSIONS, SHARED_ENV_PROVIDER);
        StrimziPodSet oldZkPodSet = oldZkCluster.generatePodSet(oldKafka.getSpec().getZookeeper().getReplicas(), false, null, null, podNum -> null);
        List<KafkaPool> oldPools = NodePoolUtils.createKafkaPools(Reconciliation.DUMMY_RECONCILIATION, oldKafka, null, Map.of(), Map.of(), false, SHARED_ENV_PROVIDER);
        KafkaCluster oldKafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, oldPools, VERSIONS, false, null, SHARED_ENV_PROVIDER);
        StrimziPodSet oldKafkaPodSet = oldKafkaCluster.generatePodSets(false, null, null, brokerId -> null).get(0);

        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS, SHARED_ENV_PROVIDER);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.getAsync(any(), any())).thenReturn(Future.succeededFuture(new Secret()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(oldKafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaCluster.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Zoo
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getComponentName()))).thenReturn(Future.succeededFuture(oldZkPodSet));
        ArgumentCaptor<StrimziPodSet> zkPodSetCaptor =  ArgumentCaptor.forClass(StrimziPodSet.class);
        when(mockPodSetOps.reconcile(any(), any(), eq(zkCluster.getComponentName()), zkPodSetCaptor.capture())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(List.of(oldKafkaPodSet)));
        ArgumentCaptor<List<StrimziPodSet>> kafkaPodSetBatchCaptor =  ArgumentCaptor.forClass(List.class);
        when(mockPodSetOps.batchReconcile(any(), any(), kafkaPodSetBatchCaptor.capture(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.noop(podSet));
            }

            return Future.succeededFuture(result);
        });
        ArgumentCaptor<StrimziPodSet> kafkaPodSetCaptor =  ArgumentCaptor.forClass(StrimziPodSet.class);
        when(mockPodSetOps.reconcile(any(), any(), eq(KAFKA_CLUSTER.getComponentName()), kafkaPodSetCaptor.capture())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(zkCluster.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS is queried and deleted if it still exists
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(zkCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.readiness(any(), any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockPodOps.waitFor(any(), any(), any(), any(), anyLong(), anyLong(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS);

        MockZooKeeperReconciler zr = new MockZooKeeperReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                null,
                5,
                CLUSTER_CA);

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                config,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                VERSION_CHANGE,
                Map.of(),
                Map.of(CLUSTER_NAME + "-kafka", IntStream.rangeClosed(0, 4).mapToObj(i -> CLUSTER_NAME + "-kafka-" + i).toList()),
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config,
                zr,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Scale-down of Zoo is done pod by pod => the reconcile method is called 3 times with 1, 2 and 3 pods.
                    assertThat(zkPodSetCaptor.getAllValues().size(), is(3));
                    assertThat(zkPodSetCaptor.getAllValues().get(0).getSpec().getPods().size(), is(5)); // => first capture is from zkPodSet() with old replica count
                    assertThat(zkPodSetCaptor.getAllValues().get(1).getSpec().getPods().size(), is(4)); // => second capture is from zkScalingDown() with new replica count
                    assertThat(zkPodSetCaptor.getAllValues().get(2).getSpec().getPods().size(), is(3)); // => third capture is from zkScalingDown() with new replica count

                    // Still one maybe-roll invocation
                    assertThat(zr.maybeRollZooKeeperInvocations, is(1));

                    // Scale-down of Kafka is done in one go => we should see two invocations (first from scale-down and second from regular patching)
                    assertThat(kafkaPodSetCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetCaptor.getAllValues().get(0).getSpec().getPods().size(), is(3)); // => first capture is from kafkaScaleDown() with new replica count
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(0).getSpec().getPods().size(), is(3)); // => second capture is from kafkaPodSet() again with new replica count

                    // Still one maybe-roll invocation
                    assertThat(kr.maybeRollKafkaInvocations, is(1));

                    // CMs for all remaining pods + the old shared config CM are reconciled
                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(4));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-kafka-0", "my-cluster-kafka-1", "my-cluster-kafka-2", "my-cluster-kafka-config")));

                    // The  CMs for scaled down pods are deleted
                    assertThat(cmDeletionCaptor.getAllValues().size(), is(2));
                    assertThat(cmDeletionCaptor.getAllValues(), is(List.of("my-cluster-kafka-3", "my-cluster-kafka-4")));

                    async.flag();
                })));
    }

    // Internal utility methods
    private Pod podFromPodSet(StrimziPodSet podSet, String name) {
        return PodSetUtils.podSetToPods(podSet).stream().filter(p -> name.equals(p.getMetadata().getName())).findFirst().orElse(null);
    }

    static class MockKafkaAssemblyOperator extends KafkaAssemblyOperator  {
        ZooKeeperReconciler mockZooKeeperReconciler;
        KafkaReconciler mockKafkaReconciler;

        public MockKafkaAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa, CertManager certManager, PasswordGenerator passwordGenerator, ResourceOperatorSupplier supplier, ClusterOperatorConfig config, ZooKeeperReconciler mockZooKeeperReconciler, KafkaReconciler mockKafkaReconciler) {
            super(vertx, pfa, certManager, passwordGenerator, supplier, config);
            this.mockZooKeeperReconciler = mockZooKeeperReconciler;
            this.mockKafkaReconciler = mockKafkaReconciler;
        }

        ReconciliationState createReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
            return new MockReconciliationState(reconciliation, kafkaAssembly);
        }

        @Override
        Future<Void> reconcile(ReconciliationState reconcileState)  {
            return Future.succeededFuture(reconcileState)
                    .compose(state -> state.reconcileCas(this.clock))
                    .compose(state -> state.reconcileZooKeeper(this.clock))
                    .compose(state -> state.reconcileKafka(this.clock))
                    .mapEmpty();
        }

        class MockReconciliationState extends ReconciliationState {
            MockReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
                super(reconciliation, kafkaAssembly);
            }

            @Override
            Future<ZooKeeperReconciler> zooKeeperReconciler()    {
                return Future.succeededFuture(mockZooKeeperReconciler);
            }

            @Override
            Future<KafkaReconciler> kafkaReconciler()    {
                return Future.succeededFuture(mockKafkaReconciler);
            }
        }
    }

    static class MockZooKeeperReconciler extends ZooKeeperReconciler   {
        int maybeRollZooKeeperInvocations = 0;
        Function<Pod, List<String>> zooPodNeedsRestart = null;

        public MockZooKeeperReconciler(Reconciliation reconciliation, Vertx vertx, ClusterOperatorConfig config, ResourceOperatorSupplier supplier, PlatformFeaturesAvailability pfa, Kafka kafkaAssembly, KafkaVersionChange versionChange, Storage oldStorage, int currentReplicas, ClusterCa clusterCa) {
            super(reconciliation, vertx, config, supplier, pfa, kafkaAssembly, versionChange, oldStorage, currentReplicas, clusterCa);
        }

        @Override
        public Future<Void> reconcile(KafkaStatus kafkaStatus, Clock clock)    {
            return manualPodCleaning()
                    .compose(i -> manualRollingUpdate())
                    .compose(i -> migrateFromStatefulSetToPodSet())
                    .compose(i -> podSet())
                    .compose(i -> scaleDown())
                    .compose(i -> rollingUpdate())
                    .compose(i -> scaleUp());
        }

        @Override
        Future<Void> maybeRollZooKeeper(Function<Pod, List<String>> podNeedsRestart) {
            maybeRollZooKeeperInvocations++;
            zooPodNeedsRestart = podNeedsRestart;
            return Future.succeededFuture();
        }
    }

    static class MockKafkaReconciler extends KafkaReconciler   {
        int maybeRollKafkaInvocations = 0;
        Function<Pod, RestartReasons> kafkaPodNeedsRestart = null;

        public MockKafkaReconciler(Reconciliation reconciliation, Vertx vertx, ClusterOperatorConfig config, ResourceOperatorSupplier supplier, PlatformFeaturesAvailability pfa, Kafka kafkaAssembly, KafkaVersionChange versionChange, Map<String, Storage> oldStorage, Map<String, List<String>> currentPods, ClusterCa clusterCa, ClientsCa clientsCa) {
            super(reconciliation, kafkaAssembly, null, oldStorage, currentPods, clusterCa, clientsCa, versionChange, config, supplier, pfa, vertx);
        }

        @Override
        public Future<Void> reconcile(KafkaStatus kafkaStatus, Clock clock)    {
            return manualPodCleaning()
                    .compose(i -> manualRollingUpdate())
                    .compose(i -> scaleDown())
                    .compose(i -> listeners())
                    .compose(i -> brokerConfigurationConfigMaps())
                    .compose(i -> migrateFromStatefulSetToPodSet())
                    .compose(i -> podSet())
                    .compose(podSetDiffs -> rollingUpdate(podSetDiffs))
                    .compose(i -> sharedKafkaConfigurationCleanup());
        }

        @Override
        protected Future<Void> maybeRollKafka(
                Set<NodeRef> nodes,
                Function<Pod, RestartReasons> podNeedsRestart,
                Map<Integer, Map<String, String>> kafkaAdvertisedHostnames,
                Map<Integer, Map<String, String>> kafkaAdvertisedPorts,
                boolean allowReconfiguration
        ) {
            maybeRollKafkaInvocations++;
            kafkaPodNeedsRestart = podNeedsRestart;
            return Future.succeededFuture();
        }

        @Override
        protected Future<Void> listeners()  {
            listenerReconciliationResults = new KafkaListenersReconciler.ReconciliationResult();
            listenerReconciliationResults.advertisedHostnames.putAll(ADVERTISED_HOSTNAMES);
            listenerReconciliationResults.advertisedPorts.putAll(ADVERTISED_PORTS);

            return Future.succeededFuture();
        }
    }
}
