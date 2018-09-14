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

package org.elasticsearch.http.netty3;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.http.BindHttpException;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.http.NullDispatcher;
import org.elasticsearch.http.netty3.cors.Netty3CorsConfig;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.junit.After;
import org.junit.Before;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_CREDENTIALS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_HEADERS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_METHODS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_ORIGIN;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ENABLED;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for the {@link Netty3HttpServerTransport} class.
 */
public class Netty3HttpServerTransportTests extends ESTestCase {

    private NetworkService networkService;
    private ThreadPool threadPool;
    private MockBigArrays bigArrays;

    @Before
    public void setup() throws Exception {
        networkService = new NetworkService(Settings.EMPTY, Collections.emptyList());
        threadPool = new TestThreadPool("test");
        bigArrays = new MockBigArrays(Settings.EMPTY, new NoneCircuitBreakerService());
    }

    @After
    public void shutdown() throws Exception {
        assertWarnings("http type [netty3] is deprecated");
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        threadPool = null;
        networkService = null;
        bigArrays = null;
    }

    public void testCorsConfig() {
        final Set<String> methods = new HashSet<>(Arrays.asList("get", "options", "post"));
        final Set<String> headers = new HashSet<>(Arrays.asList("Content-Type", "Content-Length"));
        final Settings settings = Settings.builder()
                                      .put(SETTING_CORS_ENABLED.getKey(), true)
                                      .put(SETTING_CORS_ALLOW_ORIGIN.getKey(), "*")
                                      .put(SETTING_CORS_ALLOW_METHODS.getKey(), Strings.collectionToCommaDelimitedString(methods))
                                      .put(SETTING_CORS_ALLOW_HEADERS.getKey(), Strings.collectionToCommaDelimitedString(headers))
                                      .put(SETTING_CORS_ALLOW_CREDENTIALS.getKey(), true)
                                      .build();
        final Netty3HttpServerTransport transport = new Netty3HttpServerTransport(settings, networkService, bigArrays, threadPool,
                xContentRegistry(), new NullDispatcher());
        final Netty3CorsConfig corsConfig = transport.getCorsConfig();
        assertThat(corsConfig.isAnyOriginSupported(), equalTo(true));
        assertThat(corsConfig.allowedRequestHeaders(), equalTo(headers));
        assertThat(corsConfig.allowedRequestMethods().stream().map(HttpMethod::getName).collect(Collectors.toSet()), equalTo(methods));
        transport.close();
    }

    public void testCorsConfigDefaults() {
        final Set<String> headers = Sets.newHashSet("X-Requested-With", "Content-Type", "Content-Length");
        final Set<String> methods = Sets.newHashSet("OPTIONS", "HEAD", "GET", "POST", "PUT", "DELETE");
        final Settings settings = Settings.builder()
                                      .put(SETTING_CORS_ENABLED.getKey(), true)
                                      .put(SETTING_CORS_ALLOW_ORIGIN.getKey(), "*")
                                      .put(SETTING_CORS_ALLOW_CREDENTIALS.getKey(), true)
                                      .build();
        final Netty3HttpServerTransport transport = new Netty3HttpServerTransport(settings, networkService, bigArrays, threadPool,
                xContentRegistry(), new NullDispatcher());
        final Netty3CorsConfig corsConfig = transport.getCorsConfig();
        assertThat(corsConfig.allowedRequestHeaders(), equalTo(headers));
        assertThat(corsConfig.allowedRequestMethods().stream().map(HttpMethod::getName).collect(Collectors.toSet()), equalTo(methods));
        transport.close();
    }

    public void testBindUnavailableAddress() {
        try (Netty3HttpServerTransport transport = new Netty3HttpServerTransport(Settings.EMPTY, networkService, bigArrays, threadPool,
                xContentRegistry(), new NullDispatcher())){
            transport.start();
            TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());
            Settings settings = Settings.builder().put("http.port", remoteAddress.getPort()).build();
            try (Netty3HttpServerTransport otherTransport = new Netty3HttpServerTransport(settings, networkService, bigArrays, threadPool,
                    xContentRegistry(), new NullDispatcher())) {
                BindHttpException bindHttpException = expectThrows(BindHttpException.class, () -> otherTransport.start());
                assertEquals("Failed to bind to [" + remoteAddress.getPort() + "]", bindHttpException.getMessage());
            }
        }
    }

    public void testDispatchDoesNotModifyThreadContext() throws InterruptedException {
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {

            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                threadContext.putHeader("foo", "bar");
                threadContext.putTransient("bar", "baz");
            }

            @Override
            public void dispatchBadRequest(final RestRequest request,
                                           final RestChannel channel,
                                           final ThreadContext threadContext,
                                           final Throwable cause) {
                threadContext.putHeader("foo_bad", "bar");
                threadContext.putTransient("bar_bad", "baz");
            }

        };

        try (Netty3HttpServerTransport transport =
                 new Netty3HttpServerTransport(Settings.EMPTY, networkService, bigArrays, threadPool, xContentRegistry(), dispatcher)) {
            transport.start();

            transport.dispatchRequest(null, null);
            assertNull(threadPool.getThreadContext().getHeader("foo"));
            assertNull(threadPool.getThreadContext().getTransient("bar"));
        }
    }
}
