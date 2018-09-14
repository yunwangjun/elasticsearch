/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.transport;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RemoteClusterServiceTests extends ESTestCase {

    private final ThreadPool threadPool = new TestThreadPool(getClass().getName());

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
    }

    private MockTransportService startTransport(
            final String id,
            final List<DiscoveryNode> knownNodes,
            final Version version) {
        return startTransport(id, knownNodes, version, Settings.EMPTY);
    }

    private MockTransportService startTransport(
            final String id,
            final List<DiscoveryNode> knownNodes,
            final Version version,
            final Settings settings) {
        return RemoteClusterConnectionTests.startTransport(
                id, knownNodes, version, threadPool, settings);
    }

    public void testSettingsAreRegistered() {
        assertTrue(ClusterSettings.BUILT_IN_CLUSTER_SETTINGS.contains(RemoteClusterAware.REMOTE_CLUSTERS_SEEDS));
        assertTrue(ClusterSettings.BUILT_IN_CLUSTER_SETTINGS.contains(RemoteClusterService.REMOTE_CONNECTIONS_PER_CLUSTER));
        assertTrue(ClusterSettings.BUILT_IN_CLUSTER_SETTINGS.contains(RemoteClusterService.REMOTE_INITIAL_CONNECTION_TIMEOUT_SETTING));
        assertTrue(ClusterSettings.BUILT_IN_CLUSTER_SETTINGS.contains(RemoteClusterService.REMOTE_NODE_ATTRIBUTE));
    }

    public void testRemoteClusterSeedSetting() {
        // simple validation
        Settings settings = Settings.builder()
            .put("search.remote.foo.seeds", "192.168.0.1:8080")
            .put("search.remote.bar.seed", "[::1]:9090").build();
        RemoteClusterAware.REMOTE_CLUSTERS_SEEDS.getAllConcreteSettings(settings).forEach(setting -> setting.get(settings));

        Settings brokenSettings = Settings.builder()
            .put("search.remote.foo.seeds", "192.168.0.1").build();
        expectThrows(IllegalArgumentException.class, () ->
        RemoteClusterAware.REMOTE_CLUSTERS_SEEDS.getAllConcreteSettings(brokenSettings).forEach(setting -> setting.get(brokenSettings)));
    }

    public void testBuiltRemoteClustersSeeds() throws Exception {
        Map<String, List<DiscoveryNode>> map = RemoteClusterService.buildRemoteClustersSeeds(
            Settings.builder().put("search.remote.foo.seeds", "192.168.0.1:8080").put("search.remote.bar.seeds", "[::1]:9090").build());
        assertEquals(2, map.size());
        assertTrue(map.containsKey("foo"));
        assertTrue(map.containsKey("bar"));
        assertEquals(1, map.get("foo").size());
        assertEquals(1, map.get("bar").size());

        DiscoveryNode foo = map.get("foo").get(0);

        assertEquals(foo.getAddress(), new InetSocketTransportAddress(new InetSocketAddress(InetAddress.getByName("192.168.0.1"), 8080)));
        assertEquals(foo.getId(), "foo#192.168.0.1:8080");
        assertEquals(foo.getVersion(), Version.CURRENT.minimumCompatibilityVersion());

        DiscoveryNode bar = map.get("bar").get(0);
        assertEquals(bar.getAddress(), new InetSocketTransportAddress(new InetSocketAddress(InetAddress.getByName("[::1]"), 9090)));
        assertEquals(bar.getId(), "bar#[::1]:9090");
        assertEquals(bar.getVersion(), Version.CURRENT.minimumCompatibilityVersion());
    }


    public void testGroupClusterIndices() throws IOException {
        List<DiscoveryNode> knownNodes = new CopyOnWriteArrayList<>();
        try (MockTransportService seedTransport = startTransport("cluster_1_node", knownNodes, Version.CURRENT);
             MockTransportService otherSeedTransport = startTransport("cluster_2_node", knownNodes, Version.CURRENT)) {
            DiscoveryNode seedNode = seedTransport.getLocalDiscoNode();
            DiscoveryNode otherSeedNode = otherSeedTransport.getLocalDiscoNode();
            knownNodes.add(seedTransport.getLocalDiscoNode());
            knownNodes.add(otherSeedTransport.getLocalDiscoNode());
            Collections.shuffle(knownNodes, random());

            try (MockTransportService transportService = MockTransportService.mockTcp(Settings.EMPTY, Version.CURRENT, threadPool,
                null)) {
                transportService.start();
                transportService.acceptIncomingRequests();
                Settings.Builder builder = Settings.builder();
                builder.putArray("search.remote.cluster_1.seeds", seedNode.getAddress().toString());
                builder.putArray("search.remote.cluster_2.seeds", otherSeedNode.getAddress().toString());
                try (RemoteClusterService service = new RemoteClusterService(builder.build(), transportService)) {
                    assertFalse(service.isCrossClusterSearchEnabled());
                    service.initializeRemoteClusters();
                    assertTrue(service.isCrossClusterSearchEnabled());
                    assertTrue(service.isRemoteClusterRegistered("cluster_1"));
                    assertTrue(service.isRemoteClusterRegistered("cluster_2"));
                    assertFalse(service.isRemoteClusterRegistered("foo"));
                    Map<String, List<String>> perClusterIndices = service.groupClusterIndices(new String[]{"foo:bar", "cluster_1:bar",
                        "cluster_2:foo:bar", "cluster_1:test", "cluster_2:foo*", "foo", "cluster*:baz", "*:boo", "no*match:boo"},
                        i -> false);
                    String[] localIndices = perClusterIndices.computeIfAbsent(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY,
                        k -> Collections.emptyList()).toArray(new String[0]);
                    assertNotNull(perClusterIndices.remove(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY));
                    assertArrayEquals(new String[]{"foo:bar", "foo", "no*match:boo"}, localIndices);
                    assertEquals(2, perClusterIndices.size());
                    assertEquals(Arrays.asList("bar", "test", "baz", "boo"), perClusterIndices.get("cluster_1"));
                    assertEquals(Arrays.asList("foo:bar", "foo*", "baz", "boo"), perClusterIndices.get("cluster_2"));

                    IllegalArgumentException iae = expectThrows(IllegalArgumentException.class, () ->
                        service.groupClusterIndices(new String[]{"foo:bar", "cluster_1:bar",
                            "cluster_2:foo:bar", "cluster_1:test", "cluster_2:foo*", "foo"}, i -> "cluster_1:bar".equals(i)));

                    assertEquals("Can not filter indices; index cluster_1:bar exists but there is also a remote cluster named:" +
                            " cluster_1", iae.getMessage());
                }
            }
        }
    }

    public void testIncrementallyAddClusters() throws IOException {
        List<DiscoveryNode> knownNodes = new CopyOnWriteArrayList<>();
        try (MockTransportService seedTransport = startTransport("cluster_1_node", knownNodes, Version.CURRENT);
             MockTransportService otherSeedTransport = startTransport("cluster_2_node", knownNodes, Version.CURRENT)) {
            DiscoveryNode seedNode = seedTransport.getLocalDiscoNode();
            DiscoveryNode otherSeedNode = otherSeedTransport.getLocalDiscoNode();
            knownNodes.add(seedTransport.getLocalDiscoNode());
            knownNodes.add(otherSeedTransport.getLocalDiscoNode());
            Collections.shuffle(knownNodes, random());

            try (MockTransportService transportService = MockTransportService.mockTcp(Settings.EMPTY, Version.CURRENT, threadPool,
                null)) {
                transportService.start();
                transportService.acceptIncomingRequests();
                Settings.Builder builder = Settings.builder();
                builder.putArray("search.remote.cluster_1.seeds", seedNode.getAddress().toString());
                builder.putArray("search.remote.cluster_2.seeds", otherSeedNode.getAddress().toString());
                try (RemoteClusterService service = new RemoteClusterService(Settings.EMPTY, transportService)) {
                    assertFalse(service.isCrossClusterSearchEnabled());
                    service.initializeRemoteClusters();
                    assertFalse(service.isCrossClusterSearchEnabled());
                    service.updateRemoteCluster("cluster_1", Collections.singletonList(((InetSocketTransportAddress)seedNode.getAddress())
                        .address()));
                    assertTrue(service.isCrossClusterSearchEnabled());
                    assertTrue(service.isRemoteClusterRegistered("cluster_1"));
                    service.updateRemoteCluster("cluster_2", Collections.singletonList(((InetSocketTransportAddress)otherSeedNode
                        .getAddress()).address()));
                    assertTrue(service.isCrossClusterSearchEnabled());
                    assertTrue(service.isRemoteClusterRegistered("cluster_1"));
                    assertTrue(service.isRemoteClusterRegistered("cluster_2"));
                    service.updateRemoteCluster("cluster_2", Collections.emptyList());
                    assertFalse(service.isRemoteClusterRegistered("cluster_2"));
                    IllegalArgumentException iae = expectThrows(IllegalArgumentException.class,
                        () -> service.updateRemoteCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY, Collections.emptyList()));
                    assertEquals("remote clusters must not have the empty string as its key", iae.getMessage());
                }
            }
        }
    }

    // we need at least one node that is >= 5.3 for cross cluster search
    public void testPre53NodesAreRejected() throws IOException {
        List<DiscoveryNode> knownNodes = new CopyOnWriteArrayList<>();
        try (MockTransportService seedTransport = startTransport("cluster_1_node", knownNodes, Version.fromString("5.1.99"));
             MockTransportService otherSeedTransport = startTransport("cluster_2_node", knownNodes, Version.CURRENT)) {
            DiscoveryNode seedNode = seedTransport.getLocalDiscoNode();
            DiscoveryNode otherSeedNode = otherSeedTransport.getLocalDiscoNode();
            knownNodes.add(seedNode);
            knownNodes.add(otherSeedNode);
            assertTrue(seedNode.getVersion().before(Version.V_5_3_0));
            Collections.shuffle(knownNodes, random());

            try (MockTransportService transportService = MockTransportService.mockTcp(Settings.EMPTY, Version.CURRENT, threadPool,
                null)) {
                transportService.start();
                transportService.acceptIncomingRequests();
                Settings.Builder builder = Settings.builder();
                builder.putArray("search.remote.cluster.seeds", knownNodes.get(0).getAddress().toString());
                try (RemoteClusterService service = new RemoteClusterService(builder.build(), transportService)) {
                    service.initializeRemoteClusters();
                    assertTrue(service.isCrossClusterSearchEnabled());
                    assertTrue(service.isRemoteClusterRegistered("cluster"));
                    assertFalse(transportService.nodeConnected(seedNode));
                    assertTrue(transportService.nodeConnected(otherSeedNode));
                }
            }
        }
    }

    public void testRemoteNodeAttribute() throws IOException, InterruptedException {
        final Settings settings =
                Settings.builder().put("search.remote.node.attr", "gateway").build();
        final List<DiscoveryNode> knownNodes = new CopyOnWriteArrayList<>();
        final Settings gateway = Settings.builder().put("node.attr.gateway", true).build();
        try (MockTransportService c1N1 =
                     startTransport("cluster_1_node_1", knownNodes, Version.CURRENT);
             MockTransportService c1N2 =
                     startTransport("cluster_1_node_2", knownNodes, Version.CURRENT, gateway);
             MockTransportService c2N1 =
                     startTransport("cluster_2_node_1", knownNodes, Version.CURRENT);
             MockTransportService c2N2 =
                     startTransport("cluster_2_node_2", knownNodes, Version.CURRENT, gateway)) {
            final DiscoveryNode c1N1Node = c1N1.getLocalDiscoNode();
            final DiscoveryNode c1N2Node = c1N2.getLocalDiscoNode();
            final DiscoveryNode c2N1Node = c2N1.getLocalDiscoNode();
            final DiscoveryNode c2N2Node = c2N2.getLocalDiscoNode();
            knownNodes.add(c1N1Node);
            knownNodes.add(c1N2Node);
            knownNodes.add(c2N1Node);
            knownNodes.add(c2N2Node);
            Collections.shuffle(knownNodes, random());

            try (MockTransportService transportService = MockTransportService.mockTcp(
                    settings,
                    Version.CURRENT,
                    threadPool,
                    null)) {
                transportService.start();
                transportService.acceptIncomingRequests();
                final Settings.Builder builder = Settings.builder();
                builder.putArray(
                        "search.remote.cluster_1.seeds", c1N1Node.getAddress().toString());
                builder.putArray(
                        "search.remote.cluster_2.seeds", c2N1Node.getAddress().toString());
                try (RemoteClusterService service =
                             new RemoteClusterService(settings, transportService)) {
                    assertFalse(service.isCrossClusterSearchEnabled());
                    service.initializeRemoteClusters();
                    assertFalse(service.isCrossClusterSearchEnabled());

                    final InetSocketAddress c1N1Address =
                            ((InetSocketTransportAddress)c1N1Node.getAddress()).address();
                    final InetSocketAddress c1N2Address =
                            ((InetSocketTransportAddress)c1N2Node.getAddress()).address();
                    final InetSocketAddress c2N1Address =
                            ((InetSocketTransportAddress)c2N1Node.getAddress()).address();
                    final InetSocketAddress c2N2Address =
                            ((InetSocketTransportAddress)c2N2Node.getAddress()).address();

                    final CountDownLatch firstLatch = new CountDownLatch(1);
                    service.updateRemoteCluster(
                            "cluster_1",
                            Arrays.asList(c1N1Address, c1N2Address),
                            connectionListener(firstLatch));
                    firstLatch.await();

                    final CountDownLatch secondLatch = new CountDownLatch(1);
                    service.updateRemoteCluster(
                            "cluster_2",
                            Arrays.asList(c2N1Address, c2N2Address),
                            connectionListener(secondLatch));
                    secondLatch.await();

                    assertTrue(service.isCrossClusterSearchEnabled());
                    assertTrue(service.isRemoteClusterRegistered("cluster_1"));
                    assertFalse(service.isRemoteNodeConnected("cluster_1", c1N1Node));
                    assertTrue(service.isRemoteNodeConnected("cluster_1", c1N2Node));
                    assertTrue(service.isRemoteClusterRegistered("cluster_2"));
                    assertFalse(service.isRemoteNodeConnected("cluster_2", c2N1Node));
                    assertTrue(service.isRemoteNodeConnected("cluster_2", c2N2Node));
                }
            }
        }
    }

    private ActionListener<Void> connectionListener(final CountDownLatch latch) {
        return ActionListener.wrap(x -> latch.countDown(), x -> fail());
    }

}
