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
package org.elasticsearch.action.search;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.index.Index;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.InternalScrollSearchRequest;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class SearchScrollAsyncActionTests extends ESTestCase {

    public void testSendRequestsToNodes() throws InterruptedException {

        ParsedScrollId scrollId = getParsedScrollId(
            new ScrollIdForNode("node1", 1),
            new ScrollIdForNode("node2", 2),
            new ScrollIdForNode("node3", 17),
            new ScrollIdForNode("node1", 0),
            new ScrollIdForNode("node3", 0));
        DiscoveryNodes discoveryNodes = DiscoveryNodes.builder()
            .add(new DiscoveryNode("node1", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node2", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node3", LocalTransportAddress.buildUnique(), Version.CURRENT)).build();

        AtomicArray<SearchAsyncActionTests.TestSearchPhaseResult> results = new AtomicArray<>(scrollId.getContext().length);
        SearchScrollRequest request = new SearchScrollRequest();
        request.scroll(new Scroll(TimeValue.timeValueMinutes(1)));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger movedCounter = new AtomicInteger(0);
        SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult> action =
            new SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult>(scrollId, logger, discoveryNodes, null, null, request)
            {
                @Override
                protected void executeInitialPhase(DiscoveryNode node, InternalScrollSearchRequest internalRequest,
                                                   SearchActionListener<SearchAsyncActionTests.TestSearchPhaseResult> searchActionListener)
                {
                    new Thread(() -> {
                        SearchAsyncActionTests.TestSearchPhaseResult testSearchPhaseResult =
                            new SearchAsyncActionTests.TestSearchPhaseResult(internalRequest.id(), node);
                        testSearchPhaseResult.setSearchShardTarget(new SearchShardTarget(node.getId(), new Index("test", "_na_"), 1));
                        searchActionListener.onResponse(testSearchPhaseResult);
                    }).start();
                }

                @Override
                protected SearchPhase moveToNextPhase() {
                    assertEquals(1, movedCounter.incrementAndGet());
                    return new SearchPhase("test") {
                        @Override
                        public void run() throws IOException {
                            latch.countDown();
                        }
                    };
                }

                @Override
                protected void onFirstPhaseResult(int shardId, SearchAsyncActionTests.TestSearchPhaseResult result) {
                    results.setOnce(shardId, result);
                }
            };

        action.run();
        latch.await();
        ShardSearchFailure[] shardSearchFailures = action.buildShardFailures();
        assertEquals(0, shardSearchFailures.length);
        ScrollIdForNode[] context = scrollId.getContext();
        for (int i = 0; i < results.length(); i++) {
            assertNotNull(results.get(i));
            assertEquals(context[i].getScrollId(), results.get(i).getRequestId());
            assertEquals(context[i].getNode(), results.get(i).node.getId());
        }
    }

    public void testFailNextPhase() throws InterruptedException {

        ParsedScrollId scrollId = getParsedScrollId(
            new ScrollIdForNode("node1", 1),
            new ScrollIdForNode("node2", 2),
            new ScrollIdForNode("node3", 17),
            new ScrollIdForNode("node1", 0),
            new ScrollIdForNode("node3", 0));
        DiscoveryNodes discoveryNodes = DiscoveryNodes.builder()
            .add(new DiscoveryNode("node1", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node2", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node3", LocalTransportAddress.buildUnique(), Version.CURRENT)).build();

        AtomicArray<SearchAsyncActionTests.TestSearchPhaseResult> results = new AtomicArray<>(scrollId.getContext().length);
        SearchScrollRequest request = new SearchScrollRequest();
        request.scroll(new Scroll(TimeValue.timeValueMinutes(1)));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger movedCounter = new AtomicInteger(0);
        ActionListener listener = new ActionListener() {
            @Override
            public void onResponse(Object o) {
                try {
                    fail("got a result");
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(Exception e) {
                try {
                    assertTrue(e instanceof SearchPhaseExecutionException);
                    SearchPhaseExecutionException ex = (SearchPhaseExecutionException) e;
                    assertEquals("BOOM", ex.getCause().getMessage());
                    assertEquals("TEST_PHASE", ex.getPhaseName());
                    assertEquals("Phase failed", ex.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        };
        SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult> action =
            new SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult>(scrollId, logger, discoveryNodes, listener, null,
                request) {
                @Override
                protected void executeInitialPhase(DiscoveryNode node, InternalScrollSearchRequest internalRequest,
                                                   SearchActionListener<SearchAsyncActionTests.TestSearchPhaseResult> searchActionListener)
                {
                    new Thread(() -> {
                        SearchAsyncActionTests.TestSearchPhaseResult testSearchPhaseResult =
                            new SearchAsyncActionTests.TestSearchPhaseResult(internalRequest.id(), node);
                        testSearchPhaseResult.setSearchShardTarget(new SearchShardTarget(node.getId(), new Index("test", "_na_"), 1));
                        searchActionListener.onResponse(testSearchPhaseResult);
                    }).start();
                }

                @Override
                protected SearchPhase moveToNextPhase() {
                    assertEquals(1, movedCounter.incrementAndGet());
                    return new SearchPhase("TEST_PHASE") {
                        @Override
                        public void run() throws IOException {
                            throw new IllegalArgumentException("BOOM");
                        }
                    };
                }

                @Override
                protected void onFirstPhaseResult(int shardId, SearchAsyncActionTests.TestSearchPhaseResult result) {
                    results.setOnce(shardId, result);
                }
            };

        action.run();
        latch.await();
        ShardSearchFailure[] shardSearchFailures = action.buildShardFailures();
        assertEquals(0, shardSearchFailures.length);
        ScrollIdForNode[] context = scrollId.getContext();
        for (int i = 0; i < results.length(); i++) {
            assertNotNull(results.get(i));
            assertEquals(context[i].getScrollId(), results.get(i).getRequestId());
            assertEquals(context[i].getNode(), results.get(i).node.getId());
        }
    }

    public void testNodeNotAvailable() throws InterruptedException {
        ParsedScrollId scrollId = getParsedScrollId(
            new ScrollIdForNode("node1", 1),
            new ScrollIdForNode("node2", 2),
            new ScrollIdForNode("node3", 17),
            new ScrollIdForNode("node1", 0),
            new ScrollIdForNode("node3", 0));
        // node2 is not available
        DiscoveryNodes discoveryNodes = DiscoveryNodes.builder()
            .add(new DiscoveryNode("node1", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node3", LocalTransportAddress.buildUnique(), Version.CURRENT)).build();

        AtomicArray<SearchAsyncActionTests.TestSearchPhaseResult> results = new AtomicArray<>(scrollId.getContext().length);
        SearchScrollRequest request = new SearchScrollRequest();
        request.scroll(new Scroll(TimeValue.timeValueMinutes(1)));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger movedCounter = new AtomicInteger(0);
        SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult> action =
            new SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult>(scrollId, logger, discoveryNodes, null, null, request)
            {
                @Override
                protected void executeInitialPhase(DiscoveryNode node, InternalScrollSearchRequest internalRequest,
                                                   SearchActionListener<SearchAsyncActionTests.TestSearchPhaseResult> searchActionListener)
                {
                    assertNotEquals("node2 is not available", "node2", node.getId());
                    new Thread(() -> {
                        SearchAsyncActionTests.TestSearchPhaseResult testSearchPhaseResult =
                            new SearchAsyncActionTests.TestSearchPhaseResult(internalRequest.id(), node);
                        testSearchPhaseResult.setSearchShardTarget(new SearchShardTarget(node.getId(), new Index("test", "_na_"), 1));
                        searchActionListener.onResponse(testSearchPhaseResult);
                    }).start();
                }

                @Override
                protected SearchPhase moveToNextPhase() {
                    assertEquals(1, movedCounter.incrementAndGet());
                    return new SearchPhase("test") {
                        @Override
                        public void run() throws IOException {
                            latch.countDown();
                        }
                    };
                }

                @Override
                protected void onFirstPhaseResult(int shardId, SearchAsyncActionTests.TestSearchPhaseResult result) {
                    results.setOnce(shardId, result);
                }
            };

        action.run();
        latch.await();
        ShardSearchFailure[] shardSearchFailures = action.buildShardFailures();
        assertEquals(1, shardSearchFailures.length);
        assertEquals("IllegalStateException[node [node2] is not available]", shardSearchFailures[0].reason());

        ScrollIdForNode[] context = scrollId.getContext();
        for (int i = 0; i < results.length(); i++) {
            if (context[i].getNode().equals("node2")) {
                assertNull(results.get(i));
            } else {
                assertNotNull(results.get(i));
                assertEquals(context[i].getScrollId(), results.get(i).getRequestId());
                assertEquals(context[i].getNode(), results.get(i).node.getId());
            }
        }
    }

    public void testShardFailures() throws InterruptedException {
        ParsedScrollId scrollId = getParsedScrollId(
            new ScrollIdForNode("node1", 1),
            new ScrollIdForNode("node2", 2),
            new ScrollIdForNode("node3", 17),
            new ScrollIdForNode("node1", 0),
            new ScrollIdForNode("node3", 0));
        DiscoveryNodes discoveryNodes = DiscoveryNodes.builder()
            .add(new DiscoveryNode("node1", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node2", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node3", LocalTransportAddress.buildUnique(), Version.CURRENT)).build();

        AtomicArray<SearchAsyncActionTests.TestSearchPhaseResult> results = new AtomicArray<>(scrollId.getContext().length);
        SearchScrollRequest request = new SearchScrollRequest();
        request.scroll(new Scroll(TimeValue.timeValueMinutes(1)));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger movedCounter = new AtomicInteger(0);
        SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult> action =
            new SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult>(scrollId, logger, discoveryNodes, null, null, request)
            {
                @Override
                protected void executeInitialPhase(DiscoveryNode node, InternalScrollSearchRequest internalRequest,
                                                   SearchActionListener<SearchAsyncActionTests.TestSearchPhaseResult> searchActionListener)
                {
                    new Thread(() -> {
                        if (internalRequest.id() == 17) {
                            searchActionListener.onFailure(new IllegalArgumentException("BOOM on shard"));
                        } else {
                            SearchAsyncActionTests.TestSearchPhaseResult testSearchPhaseResult =
                                new SearchAsyncActionTests.TestSearchPhaseResult(internalRequest.id(), node);
                            testSearchPhaseResult.setSearchShardTarget(new SearchShardTarget(node.getId(), new Index("test", "_na_"), 1));
                            searchActionListener.onResponse(testSearchPhaseResult);
                        }
                    }).start();
                }

                @Override
                protected SearchPhase moveToNextPhase() {
                    assertEquals(1, movedCounter.incrementAndGet());
                    return new SearchPhase("test") {
                        @Override
                        public void run() throws IOException {
                            latch.countDown();
                        }
                    };
                }

                @Override
                protected void onFirstPhaseResult(int shardId, SearchAsyncActionTests.TestSearchPhaseResult result) {
                    results.setOnce(shardId, result);
                }
            };

        action.run();
        latch.await();
        ShardSearchFailure[] shardSearchFailures = action.buildShardFailures();
        assertEquals(1, shardSearchFailures.length);
        assertEquals("IllegalArgumentException[BOOM on shard]", shardSearchFailures[0].reason());

        ScrollIdForNode[] context = scrollId.getContext();
        for (int i = 0; i < results.length(); i++) {
            if (context[i].getScrollId() == 17) {
                assertNull(results.get(i));
            } else {
                assertNotNull(results.get(i));
                assertEquals(context[i].getScrollId(), results.get(i).getRequestId());
                assertEquals(context[i].getNode(), results.get(i).node.getId());
            }
        }
    }

    public void testAllShardsFailed() throws InterruptedException {
        ParsedScrollId scrollId = getParsedScrollId(
            new ScrollIdForNode("node1", 1),
            new ScrollIdForNode("node2", 2),
            new ScrollIdForNode("node3", 17),
            new ScrollIdForNode("node1", 0),
            new ScrollIdForNode("node3", 0));
        DiscoveryNodes discoveryNodes = DiscoveryNodes.builder()
            .add(new DiscoveryNode("node1", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node2", LocalTransportAddress.buildUnique(), Version.CURRENT))
            .add(new DiscoveryNode("node3", LocalTransportAddress.buildUnique(), Version.CURRENT)).build();

        AtomicArray<SearchAsyncActionTests.TestSearchPhaseResult> results = new AtomicArray<>(scrollId.getContext().length);
        SearchScrollRequest request = new SearchScrollRequest();
        request.scroll(new Scroll(TimeValue.timeValueMinutes(1)));
        CountDownLatch latch = new CountDownLatch(1);
        ActionListener listener = new ActionListener() {
            @Override
            public void onResponse(Object o) {
                try {
                    fail("got a result");
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(Exception e) {
                try {
                    assertTrue(e instanceof SearchPhaseExecutionException);
                    SearchPhaseExecutionException ex = (SearchPhaseExecutionException) e;
                    assertEquals("BOOM on shard", ex.getCause().getMessage());
                    assertEquals("query", ex.getPhaseName());
                    assertEquals("all shards failed", ex.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        };
        SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult> action =
            new SearchScrollAsyncAction<SearchAsyncActionTests.TestSearchPhaseResult>(scrollId, logger, discoveryNodes, listener, null,
                request) {
                @Override
                protected void executeInitialPhase(DiscoveryNode node, InternalScrollSearchRequest internalRequest,
                                                   SearchActionListener<SearchAsyncActionTests.TestSearchPhaseResult> searchActionListener)
                {
                    new Thread(() -> searchActionListener.onFailure(new IllegalArgumentException("BOOM on shard"))).start();
                }

                @Override
                protected SearchPhase moveToNextPhase() {
                   fail("don't move all shards failed");
                   return null;
                }

                @Override
                protected void onFirstPhaseResult(int shardId, SearchAsyncActionTests.TestSearchPhaseResult result) {
                    results.setOnce(shardId, result);
                }
            };

        action.run();
        latch.await();
        ScrollIdForNode[] context = scrollId.getContext();

        ShardSearchFailure[] shardSearchFailures = action.buildShardFailures();
        assertEquals(context.length, shardSearchFailures.length);
        assertEquals("IllegalArgumentException[BOOM on shard]", shardSearchFailures[0].reason());

        for (int i = 0; i < results.length(); i++) {
            assertNull(results.get(i));
        }
    }

    private static ParsedScrollId getParsedScrollId(ScrollIdForNode... idsForNodes) {
        List<ScrollIdForNode> scrollIdForNodes = Arrays.asList(idsForNodes);
        Collections.shuffle(scrollIdForNodes, random());
        return new ParsedScrollId("", "test", scrollIdForNodes.toArray(new ScrollIdForNode[0]));
    }
}
