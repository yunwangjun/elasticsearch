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

package org.elasticsearch.repositories.gcs;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpIOExceptionHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpUnsuccessfulResponseHandler;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.StorageScopes;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.SecureSetting;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

interface GoogleCloudStorageService {

    String SETTINGS_PREFIX = "gcs.client.";

    /** A json credentials file loaded from secure settings. */
    Setting.AffixSetting<InputStream> CREDENTIALS_FILE_SETTING = Setting.affixKeySetting(SETTINGS_PREFIX, "credentials_file",
        key -> SecureSetting.secureFile(key, null));

    /**
     * Creates a client that can be used to manage Google Cloud Storage objects.
     *
     * @param serviceAccount path to service account file
     * @param clientName     name of client settings to use from secure settings
     * @param application    name of the application
     * @param connectTimeout connection timeout for HTTP requests
     * @param readTimeout    read timeout for HTTP requests
     * @return a Client instance that can be used to manage objects
     */
    Storage createClient(String serviceAccount, String clientName, String application,
                         TimeValue connectTimeout, TimeValue readTimeout) throws Exception;

    /**
     * Default implementation
     */
    class InternalGoogleCloudStorageService extends AbstractComponent implements GoogleCloudStorageService {

        private static final String DEFAULT = "_default_";

        private final Environment environment;

        /** Credentials identified by client name. */
        private final Map<String, GoogleCredential> credentials;

        InternalGoogleCloudStorageService(Environment environment, Map<String, GoogleCredential> credentials) {
            super(environment.settings());
            this.environment = environment;
            this.credentials = credentials;
        }

        @Override
        public Storage createClient(String serviceAccountFile, String clientName, String application,
                                    TimeValue connectTimeout, TimeValue readTimeout) throws Exception {
            try {
                GoogleCredential credential = getCredential(serviceAccountFile, clientName);
                NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

                Storage.Builder storage = new Storage.Builder(httpTransport, JacksonFactory.getDefaultInstance(),
                        new DefaultHttpRequestInitializer(credential, connectTimeout, readTimeout));
                storage.setApplicationName(application);

                logger.debug("initializing client with service account [{}/{}]",
                        credential.getServiceAccountId(), credential.getServiceAccountUser());
                return storage.build();
            } catch (IOException e) {
                throw new ElasticsearchException("Error when loading Google Cloud Storage credentials file", e);
            }
        }

        // pkg private for tests
        GoogleCredential getCredential(String serviceAccountFile, String clientName) throws IOException {
            if (DEFAULT.equalsIgnoreCase(serviceAccountFile) == false) {
                deprecationLogger.deprecated("Using GCS service account file from disk is deprecated. " +
                    "Move the file into the elasticsearch keystore.");
                Path account = environment.configFile().resolve(serviceAccountFile);
                if (Files.exists(account) == false) {
                    throw new IllegalArgumentException("Unable to find service account file [" + serviceAccountFile
                        + "] defined for repository");
                }

                try (InputStream is = Files.newInputStream(account)) {
                    GoogleCredential credential = GoogleCredential.fromStream(is);
                    if (credential.createScopedRequired()) {
                        credential = credential.createScoped(Collections.singleton(StorageScopes.DEVSTORAGE_FULL_CONTROL));
                    }
                    return credential;
                }
            } else if (credentials.containsKey(clientName)) {
                return credentials.get(clientName);
            }
            return getDefaultCredential();
        }

        // pkg private for tests
        GoogleCredential getDefaultCredential() throws IOException {
            return GoogleCredential.getApplicationDefault();
        }

        /**
         * HTTP request initializer that set timeouts and backoff handler while deferring authentication to GoogleCredential.
         * See https://cloud.google.com/storage/transfer/create-client#retry
         */
        class DefaultHttpRequestInitializer implements HttpRequestInitializer {

            private final TimeValue connectTimeout;
            private final TimeValue readTimeout;
            private final GoogleCredential credential;
            private final HttpUnsuccessfulResponseHandler handler;
            private final HttpIOExceptionHandler ioHandler;

            DefaultHttpRequestInitializer(GoogleCredential credential, TimeValue connectTimeout, TimeValue readTimeout) {
                this.credential = credential;
                this.connectTimeout = connectTimeout;
                this.readTimeout = readTimeout;
                this.handler = new HttpBackOffUnsuccessfulResponseHandler(newBackOff());
                this.ioHandler = new HttpBackOffIOExceptionHandler(newBackOff());
            }

            @Override
            public void initialize(HttpRequest request) throws IOException {
                if (connectTimeout != null) {
                    request.setConnectTimeout((int) connectTimeout.millis());
                }
                if (readTimeout != null) {
                    request.setReadTimeout((int) readTimeout.millis());
                }

                request.setIOExceptionHandler(ioHandler);
                request.setInterceptor(credential);

                request.setUnsuccessfulResponseHandler((req, resp, supportsRetry) -> {
                            // Let the credential handle the response. If it failed, we rely on our backoff handler
                            return credential.handleResponse(req, resp, supportsRetry) || handler.handleResponse(req, resp, supportsRetry);
                        }
                );
            }

            private ExponentialBackOff newBackOff() {
                return new ExponentialBackOff.Builder()
                        .setInitialIntervalMillis(100)
                        .setMaxIntervalMillis(6000)
                        .setMaxElapsedTimeMillis(900000)
                        .setMultiplier(1.5)
                        .setRandomizationFactor(0.5)
                        .build();
            }
        }
    }

    /** Load all secure credentials from the settings. */
    static Map<String, GoogleCredential> loadClientCredentials(Settings settings) {
        Set<String> clientNames = settings.getGroups(SETTINGS_PREFIX).keySet();
        Map<String, GoogleCredential> credentials = new HashMap<>();
        for (String clientName : clientNames) {
            Setting<InputStream> concreteSetting = CREDENTIALS_FILE_SETTING.getConcreteSettingForNamespace(clientName);
            try (InputStream credStream = concreteSetting.get(settings)) {
                GoogleCredential credential = GoogleCredential.fromStream(credStream);
                if (credential.createScopedRequired()) {
                    credential = credential.createScoped(Collections.singleton(StorageScopes.DEVSTORAGE_FULL_CONTROL));
                }
                credentials.put(clientName, credential);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return credentials;
    }
}
