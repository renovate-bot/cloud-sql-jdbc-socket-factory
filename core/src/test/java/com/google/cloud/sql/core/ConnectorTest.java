/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.sql.core;

import static com.google.cloud.sql.core.InternalConnectorRegistry.DEFAULT_SERVER_PROXY_PORT;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.api.client.http.BasicAuthentication;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.services.sqladmin.model.DnsNameMapping;
import com.google.cloud.sql.AuthType;
import com.google.cloud.sql.ConnectorConfig;
import com.google.cloud.sql.CredentialFactory;
import com.google.cloud.sql.IpType;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import javax.naming.NameNotFoundException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConnectorTest extends CloudSqlCoreTestingBase {
  ListeningScheduledExecutorService defaultExecutor;
  private final long TEST_MAX_REFRESH_MS = 5000L;

  @Before
  public void setUp() throws Exception {
    super.setup();
    defaultExecutor = InternalConnectorRegistry.getDefaultExecutor();
  }

  @After
  public void tearDown() throws Exception {
    defaultExecutor.shutdownNow();
  }

  @Test
  public void create_throwsErrorForInvalidInstanceName() throws IOException {
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject")
            .withIpTypes("PRIMARY")
            .build();

    ConnectionConfig config2 =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion")
            .withIpTypes("PRIMARY")
            .build();

    Connector c =
        newConnector(config.getConnectorConfig(), DEFAULT_SERVER_PROXY_PORT, null, null, false);
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));

    assertThat(ex).hasMessageThat().contains("Cloud SQL connection name is invalid");

    ex =
        assertThrows(IllegalArgumentException.class, () -> c.connect(config2, TEST_MAX_REFRESH_MS));

    assertThat(ex).hasMessageThat().contains("Cloud SQL connection name is invalid");
  }

  @Test
  public void create_throwsErrorForInvalidTlsCommonNameMismatch()
      throws IOException, InterruptedException {
    // The server TLS certificate matches myProject:myRegion:myInstance
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:wrongwrongwrong")
            .withIpTypes("PRIMARY")
            .build();

    int port = sslServer.start(PUBLIC_IP);

    Connector connector = newConnector(config.getConnectorConfig(), port, null, null, false);
    SSLHandshakeException ex =
        assertThrows(
            SSLHandshakeException.class, () -> connector.connect(config, TEST_MAX_REFRESH_MS));

    assertThat(ex)
        .hasMessageThat()
        .isEqualTo(
            "Server certificate CN does not match instance name. "
                + "Server certificate CN=myProject:myInstance "
                + "Expected instance name: myProject:wrongwrongwrong");
  }

  /**
   * Start an SSL server on the private IP, and verifies that specifying a preference for private IP
   * results in a connection to the private IP.
   */
  @Test
  public void create_successfulPrivateConnection() throws IOException, InterruptedException {
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIVATE")
            .build();

    int port = sslServer.start(PRIVATE_IP);

    Connector connector = newConnector(config.getConnectorConfig(), port, null, null, false);

    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_successfulPublicConnection() throws IOException, InterruptedException {
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .build();

    int port = sslServer.start(PUBLIC_IP);

    Connector connector = newConnector(config.getConnectorConfig(), port, null, null, false);

    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_successfulPrivateConnection_UsesInstanceName_DomainNameIgnored()
      throws IOException, InterruptedException {
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("db.example.com")
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIVATE")
            .build();

    int port = sslServer.start(PRIVATE_IP);

    Connector connector = newConnector(config.getConnectorConfig(), port, null, null, false);

    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_successfulPrivateConnection_UsesInstanceName_EmptyDomainNameIgnored()
      throws IOException, InterruptedException {
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("")
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIVATE")
            .build();

    int port = sslServer.start(PRIVATE_IP);

    Connector connector = newConnector(config.getConnectorConfig(), port, null, null, false);

    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_successfulPublicConnectionWithDomainNameLegacyDns()
      throws IOException, InterruptedException {
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("db.example.com")
            .withIpTypes("PRIMARY")
            .build();

    int port = sslServer.start(PUBLIC_IP);

    Connector connector =
        newConnectorLegacyDnsField(
            config.getConnectorConfig(),
            port,
            "db.example.com",
            "myProject:myRegion:myInstance",
            false);

    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_successfulPublicConnectionWithDomainName()
      throws IOException, InterruptedException {
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("db.example.com")
            .withIpTypes("PRIMARY")
            .build();

    int port = sslServer.start(PUBLIC_IP);

    Connector connector =
        newConnector(
            config.getConnectorConfig(),
            port,
            "db.example.com",
            "myProject:myRegion:myInstance",
            false);

    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_successfulPrivateConnectionWhenDomainNameValueChanges()
      throws IOException, InterruptedException {

    MutableDnsResolver resolver =
        new MutableDnsResolver("db.example.com", "myProject:myRegion:myInstance");

    FakeSslServer myInstance =
        new FakeSslServer(
            TestKeys.getServerKeyPair().getPrivate(), TestKeys.getCasServerCertChain());

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("db.example.com")
            .withIpTypes("PRIMARY")
            .withIpTypes(Collections.singletonList(IpType.PRIVATE))
            .build();

    int port = myInstance.start(PRIVATE_IP);

    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(
            fakeSuccessHttpPscCasTransport(Duration.ofSeconds(0)));

    Connector connector =
        new Connector(
            config.getConnectorConfig(),
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(resolver));

    // Open socket to initial instance
    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);
    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);

    // Change the mock DNS value, and restart the fake SSL server for the new instance
    resolver.setInstanceName("myProject:myRegion:myInstance2");
    myInstance.stop();
    myInstance.start(PRIVATE_IP_2, port, TestKeys.getCasServerCertChain2());

    // Attempt to connect to the new instance
    Socket socket2 = connector.connect(config, TEST_MAX_REFRESH_MS);
    assertThat(readLine(socket2)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_refreshConnectorWhenDomainNameValueChanges()
      throws IOException, InterruptedException {

    MutableDnsResolver resolver =
        new MutableDnsResolver("db.example.com", "myProject:myRegion:myInstance");

    FakeSslServer myInstance = new FakeSslServer();
    Duration testFailoverPeriod = Duration.ofSeconds(1);

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("db.example.com")
            .withIpTypes("PRIMARY")
            .withIpTypes(Collections.singletonList(IpType.PRIVATE))
            .withConnectorConfig(
                new ConnectorConfig.Builder().withFailoverPeriod(testFailoverPeriod).build())
            .build();

    int port = myInstance.start(PRIVATE_IP);

    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeSuccessHttpTransport(Duration.ofSeconds(0)));

    Connector connector =
        new Connector(
            config.getConnectorConfig(),
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(resolver));

    // Open socket to initial instance
    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);
    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
    assertThat(socket.isClosed()).isFalse();

    // Change the mock DNS value, and restart the fake SSL server for the new instance
    resolver.setInstanceName("myProject:myRegion:myInstance2");
    myInstance.stop();
    myInstance.start(PRIVATE_IP_2, port, TestKeys.getCasServerCertChain2());

    // Wait for more than 1 failover period to ensure the connector refreshes.
    Thread.sleep(testFailoverPeriod.toMillis() * 2);

    // Check that the socket to the old instance was closed, indicating that the domain changed.
    assertThat(socket.isClosed()).isTrue();
  }

  @Test
  public void create_noChangeWhenDomainNameFailsToResolve()
      throws IOException, InterruptedException {

    MutableDnsResolver resolver =
        new MutableDnsResolver("db.example.com", "myProject:myRegion:myInstance");

    FakeSslServer myInstance = new FakeSslServer();
    Duration testFailoverPeriod = Duration.ofSeconds(1);

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("db.example.com")
            .withIpTypes("PRIMARY")
            .withIpTypes(Collections.singletonList(IpType.PRIVATE))
            .withConnectorConfig(
                new ConnectorConfig.Builder().withFailoverPeriod(testFailoverPeriod).build())
            .build();

    int port = myInstance.start(PRIVATE_IP);

    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeSuccessHttpTransport(Duration.ofSeconds(0)));

    Connector connector =
        new Connector(
            config.getConnectorConfig(),
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(resolver));

    // Open socket to initial instance
    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);
    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);

    // Change the mock DNS value, and restart the fake SSL server for the new instance
    resolver.clearInstanceName();

    // Wait for more than 1 failover period to ensure the connector refreshes.
    Thread.sleep(testFailoverPeriod.toMillis() * 2);

    // Check that the socket to the old instance is open even though the domain no longer resolves.
    assertThat(socket.isClosed()).isFalse();
  }

  @Test
  public void create_throwsErrorForUnresolvedDomainName() throws IOException {
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("baddomain.example.com")
            .withIpTypes("PRIMARY")
            .build();
    Connector c =
        newConnector(
            config.getConnectorConfig(),
            DEFAULT_SERVER_PROXY_PORT,
            "baddomain.example.com",
            "invalid-name",
            false);
    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));

    assertThat(ex)
        .hasMessageThat()
        .contains("Cloud SQL connection name is invalid: \"baddomain.example.com\"");
  }

  @Test
  public void create_throwsErrorForDomainNameBadTargetValue() throws IOException {
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("badvalue.example.com")
            .withIpTypes("PRIMARY")
            .build();
    Connector c =
        newConnector(config.getConnectorConfig(), DEFAULT_SERVER_PROXY_PORT, null, null, false);
    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));

    assertThat(ex)
        .hasMessageThat()
        .contains("Cloud SQL connection name is invalid: \"badvalue.example.com\"");
  }

  @Test
  public void create_throwsErrorForDomainNameDoesntMatchServerCert() throws Exception {
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("not-in-san.example.com")
            .withIpTypes("PRIMARY")
            .build();

    int port = sslServer.start(PUBLIC_IP);

    Connector c =
        newConnector(
            config.getConnectorConfig(),
            port,
            "db.example.com",
            "myProject:myRegion:otherInstance",
            true);

    SSLHandshakeException ex =
        assertThrows(SSLHandshakeException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));

    assertThat(ex).hasMessageThat().contains("Server certificate does not contain expected name");
  }

  @Test
  public void create_fallbackToInstanceWhenDomainNameDoesntMatchServerCert() throws Exception {
    FakeSslServer sslServer = new FakeSslServer();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withDomainName("not-in-san.example.com")
            .withIpTypes("PRIMARY")
            .build();

    int port = sslServer.start(PUBLIC_IP);

    Connector c =
        newConnector(
            config.getConnectorConfig(),
            port,
            "db.example.com",
            "myProject:myRegion:myInstance",
            true);

    c.connect(config, TEST_MAX_REFRESH_MS);
  }

  @Test
  public void create_successfulPublicCasConnection() throws IOException, InterruptedException {
    PrivateKey privateKey = TestKeys.getServerKeyPair().getPrivate();
    X509Certificate[] cert = TestKeys.getCasServerCertChain();

    FakeSslServer sslServer = new FakeSslServer(privateKey, cert);
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .build();

    int port = sslServer.start(PUBLIC_IP);

    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeSuccessHttpCasTransport(Duration.ZERO));

    Connector connector =
        new Connector(
            config.getConnectorConfig(),
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(
                new MockDnsResolver("example.com", "myProject:myRegion:myInstance")));

    Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  private boolean isWindows() {
    String os = System.getProperty("os.name").toLowerCase();
    return os.contains("win");
  }

  @Test
  public void create_successfulUnixSocketConnection() throws IOException, InterruptedException {
    if (isWindows()) {
      System.out.println("Skipping unix socket test on Windows.");
      return;
    }

    Path socketTestDir = Files.createTempDirectory("sockettest");
    Path socketPath = socketTestDir.resolve("test.sock");
    FakeUnixSocketServer unixSocketServer = new FakeUnixSocketServer(socketPath.toString());

    try {

      ConnectionConfig config =
          new ConnectionConfig.Builder()
              .withCloudSqlInstance("myProject:myRegion:myInstance")
              .withIpTypes("PRIMARY")
              .withUnixSocketPath(socketPath.toString())
              .build();

      unixSocketServer.start();

      Connector connector = newConnector(config.getConnectorConfig(), 10000, null, null, false);

      Socket socket = connector.connect(config, TEST_MAX_REFRESH_MS);

      assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
    } finally {
      unixSocketServer.close();
    }
  }

  @Test
  public void create_successfulDomainScopedConnection() throws IOException, InterruptedException {
    FakeSslServer sslServer =
        new FakeSslServer(
            TestKeys.getDomainServerKeyPair().getPrivate(), TestKeys.getDomainServerCert());
    CredentialFactoryProvider credentialFactoryProvider =
        new CredentialFactoryProvider(new StubCredentialFactory("foo", null));
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(
            fakeSuccessHttpTransport(
                TestKeys.getDomainServerCertPem(),
                Duration.ofSeconds(60),
                null,
                false,
                false,
                null,
                null));

    int port = sslServer.start(PUBLIC_IP);
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("example.com:myProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .build();
    Connector c =
        new Connector(
            config.getConnectorConfig(),
            factory,
            credentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver()));

    Socket socket = c.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_throwsErrorForInvalidInstanceRegion() throws IOException {
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:notMyRegion:myInstance")
            .withIpTypes("PRIMARY")
            .build();
    Connector c =
        newConnector(config.getConnectorConfig(), DEFAULT_SERVER_PROXY_PORT, null, null, false);
    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));

    assertThat(ex)
        .hasMessageThat()
        .contains("The region specified for the Cloud SQL instance is incorrect");
  }

  @Test
  public void create_failOnEmptyTargetPrincipal() throws IOException, InterruptedException {
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .withConnectorConfig(
                new ConnectorConfig.Builder()
                    .withDelegates(
                        Collections.singletonList("delegate-service-principal@example.com"))
                    .build())
            .build();
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                newConnector(
                    config.getConnectorConfig(), DEFAULT_SERVER_PROXY_PORT, null, null, false));

    assertThat(ex.getMessage()).contains(ConnectionConfig.CLOUD_SQL_TARGET_PRINCIPAL_PROPERTY);
  }

  @Test
  public void create_throwsException_adminApiNotEnabled() throws IOException {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeNotConfiguredException());
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("NotMyProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .build();
    Connector c =
        new Connector(
            config.getConnectorConfig(),
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            DEFAULT_SERVER_PROXY_PORT,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver()));

    // Use a different project to get Api Not Enabled Error.
    TerminalException ex =
        assertThrows(TerminalException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));

    assertThat(ex)
        .hasMessageThat()
        .contains(
            String.format(
                "[%s] The Google Cloud SQL Admin API failed for the project",
                "NotMyProject:myRegion:myInstance"));
  }

  @Test
  public void create_throwsException_adminApiReturnsNotAuthorized() throws IOException {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeNotAuthorizedException());
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:NotMyInstance")
            .withIpTypes("PRIMARY")
            .build();
    Connector c =
        new Connector(
            config.getConnectorConfig(),
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            DEFAULT_SERVER_PROXY_PORT,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver()));

    // Use a different instance to simulate incorrect permissions.
    TerminalException ex =
        assertThrows(TerminalException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));

    assertThat(ex)
        .hasMessageThat()
        .contains(
            String.format(
                "[%s] The Google Cloud SQL Admin API failed for the project",
                "myProject:myRegion:NotMyInstance"));
  }

  @Test
  public void create_throwsException_badGateway() throws IOException {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeBadGatewayException());
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:NotMyInstance")
            .withIpTypes("PRIMARY")
            .build();
    Connector c =
        new Connector(
            config.getConnectorConfig(),
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            DEFAULT_SERVER_PROXY_PORT,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver()));

    // If the gateway is down, then this is a temporary error, not a fatal error.
    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));

    // The Connector.connect() method will timeout, and will include details about the instance
    // data in the test.
    assertThat(ex).hasMessageThat().contains("Unable to get valid instance data within");

    assertThat(ex).hasMessageThat().contains("502");

    assertThat(ex).hasMessageThat().contains("myProject:myRegion:NotMyInstance");
  }

  @Test
  public void create_successfulPublicConnection_withIntermittentBadGatewayErrors()
      throws IOException, InterruptedException {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(
            fakeIntermittentErrorHttpTransport(
                fakeSuccessHttpTransport(Duration.ofSeconds(0)), fakeBadGatewayException()));

    FakeSslServer sslServer = new FakeSslServer();

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .build();

    int port = sslServer.start(PUBLIC_IP);

    Connector c =
        new Connector(
            config.getConnectorConfig(),
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver()));

    Socket socket = c.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void supportsCustomCredentialFactoryWithIAM() throws InterruptedException, IOException {
    FakeSslServer sslServer = new FakeSslServer();
    CredentialFactoryProvider credentialFactoryProvider =
        new CredentialFactoryProvider(
            new StubCredentialFactory("foo", Instant.now().plusSeconds(3600).toEpochMilli()));
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(
            fakeSuccessHttpTransport(TestKeys.getServerCertPem(), Duration.ofSeconds(0)));

    int port = sslServer.start(PUBLIC_IP);
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .withAuthType(AuthType.IAM)
            .build();
    Connector c =
        new Connector(
            config.getConnectorConfig(),
            factory,
            credentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver()));

    Socket socket = c.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void supportsCustomCredentialFactoryWithNoExpirationTime()
      throws InterruptedException, IOException {
    FakeSslServer sslServer = new FakeSslServer();
    CredentialFactoryProvider credentialFactoryProvider =
        new CredentialFactoryProvider(new StubCredentialFactory("foo", null));
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeSuccessHttpTransport(Duration.ofSeconds(0)));

    int port = sslServer.start(PUBLIC_IP);
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .withAuthType(AuthType.IAM)
            .build();
    Connector c =
        new Connector(
            config.getConnectorConfig(),
            factory,
            credentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver()));

    Socket socket = c.connect(config, TEST_MAX_REFRESH_MS);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void doesNotSupportNonGoogleCredentialWithIAM() throws InterruptedException {
    class BasicAuthStubCredentialFactory implements CredentialFactory {

      @Override
      public HttpRequestInitializer create() {
        return new BasicAuthentication("user", "password");
      }
    }

    CredentialFactory stubCredentialFactory = new BasicAuthStubCredentialFactory();
    CredentialFactoryProvider credentialFactoryProvider =
        new CredentialFactoryProvider(stubCredentialFactory);
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeSuccessHttpTransport(Duration.ofSeconds(0)));

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withCloudSqlInstance("myProject:myRegion:myInstance")
            .withIpTypes("PRIMARY")
            .withAuthType(AuthType.IAM)
            .build();
    Connector c =
        new Connector(
            config.getConnectorConfig(),
            factory,
            credentialFactoryProvider.getInstanceCredentialFactory(config.getConnectorConfig()),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            DEFAULT_SERVER_PROXY_PORT,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver()));

    assertThrows(RuntimeException.class, () -> c.connect(config, TEST_MAX_REFRESH_MS));
  }

  private Connector newConnectorLegacyDnsField(
      ConnectorConfig config, int port, String domainName, String instanceName, boolean cas) {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(
            fakeSuccessHttpTransport(
                TestKeys.getServerCertPem(),
                Duration.ofSeconds(0),
                null,
                cas,
                false,
                domainName,
                null));
    Connector connector =
        new Connector(
            config,
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver(domainName, instanceName)));
    return connector;
  }

  private Connector newConnector(
      ConnectorConfig config, int port, String domainName, String instanceName, boolean cas) {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(
            fakeSuccessHttpTransport(
                TestKeys.getServerCertPem(),
                Duration.ofSeconds(0),
                null,
                cas,
                false,
                null,
                Collections.singletonList(
                    new DnsNameMapping()
                        .setName(domainName)
                        .setConnectionType("PRIVATE_SERVICE_CONNECT")
                        .setDnsScope("INSTANCE"))));
    Connector connector =
        new Connector(
            config,
            factory,
            stubCredentialFactoryProvider.getInstanceCredentialFactory(config),
            defaultExecutor,
            clientKeyPair,
            10,
            TEST_MAX_REFRESH_MS,
            port,
            new DnsInstanceConnectionNameResolver(new MockDnsResolver(domainName, instanceName)));
    return connector;
  }

  private String readLine(Socket socket) throws IOException {
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
    return bufferedReader.readLine();
  }

  private static class MockDnsResolver implements DnsResolver {
    private final String domainName;
    private final String instanceName;

    public MockDnsResolver() {
      this.domainName = null;
      this.instanceName = null;
    }

    public MockDnsResolver(String domainName, String instanceName) {
      this.domainName = domainName;
      this.instanceName = instanceName;
    }

    @Override
    public Collection<String> resolveTxt(String domainName) throws NameNotFoundException {
      if (this.domainName != null && this.domainName.equals(domainName)) {
        return Collections.singletonList(this.instanceName);
      }
      if ("not-in-san.example.com".equals(domainName)) {
        return Collections.singletonList(this.instanceName);
      }
      if ("badvalue.example.com".equals(domainName)) {
        return Collections.singletonList("not-an-instance-name");
      }
      throw new NameNotFoundException("Not found: " + domainName);
    }
  }

  private static class MutableDnsResolver implements DnsResolver {
    private final String domainName;
    private String instanceName;

    public MutableDnsResolver(String domainName, String instanceName) {
      this.domainName = domainName;
      this.instanceName = instanceName;
    }

    public synchronized void setInstanceName(String instanceName) {
      this.instanceName = instanceName;
    }

    public synchronized void clearInstanceName() {
      this.instanceName = null;
    }

    @Override
    public synchronized Collection<String> resolveTxt(String domainName)
        throws NameNotFoundException {
      if (this.domainName != null
          && this.domainName.equals(domainName)
          && this.instanceName != null) {
        return Collections.singletonList(this.instanceName);
      }
      throw new NameNotFoundException("Not found: " + domainName);
    }
  }
}
