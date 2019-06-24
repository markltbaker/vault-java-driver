package com.bettercloud.vault.util;

import com.bettercloud.vault.SslConfig;
import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Capability;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.HttpWaitStrategy;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.function.Consumer;

/**
 * Sets up and exposes utilities for dealing with a Docker-hosted instance of Vault, for integration tests.
 */
public class VaultContainer implements TestRule, TestConstants {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultContainer.class);

    private final GenericContainer container;

    private String rootToken;
    private String unsealKey;

    /**
     * Establishes a running Docker container, hosting a Vault server instance.
     */
    public VaultContainer() {
        container = new GenericContainer("vault:1.1.3")
                .withClasspathResourceMapping("/startup.sh", CONTAINER_STARTUP_SCRIPT, BindMode.READ_ONLY)
                .withClasspathResourceMapping("/config.json", CONTAINER_CONFIG_FILE, BindMode.READ_ONLY)
                .withClasspathResourceMapping("/libressl.conf", CONTAINER_OPENSSL_CONFIG_FILE, BindMode.READ_ONLY)
                .withFileSystemBind(SSL_DIRECTORY, CONTAINER_SSL_DIRECTORY, BindMode.READ_WRITE)
                .withCreateContainerCmdModifier(new Consumer<CreateContainerCmd>() {
                    // TODO: Why does the compiler freak out when this anonymous class is converted to a lambda?
                    @Override
                    public void accept(final CreateContainerCmd createContainerCmd) {
                        createContainerCmd.withCapAdd(Capability.IPC_LOCK);
                    }
                })
                .withExposedPorts(8200, 8280)
                .withCommand("/bin/sh " + CONTAINER_STARTUP_SCRIPT)
                .waitingFor(
                        // All of the tests in this integration test suite use HTTPS connections.  However, Vault
                        // is configured to run a plain HTTP listener on port 8280, purely for purposes of detecting
                        // when the Docker container is fully ready.
                        //
                        // Unfortunately, we can't use HTTPS at this point in the flow.  Because that would require
                        // configuring SSL to trust the self-signed cert that's generated inside of the Docker
                        // container.  A chicken-and-egg problem, as we need to wait for the container to be fully
                        // ready before we access that cert.
                        new HttpWaitStrategy() {
                            @Override
                            protected Integer getLivenessCheckPort() {
                                return container.getMappedPort(8280);
                            }
                        }
                                .forPath("/v1/sys/seal-status")
                                .forStatusCode(HttpURLConnection.HTTP_OK) // The expected response when "vault init" has not yet run
                );
    }

    /**
     * Called by JUnit automatically after the constructor method.  Launches the Docker container that was configured
     * in the constructor.
     *
     * @param base
     * @param description
     * @return
     */
    @Override
    public Statement apply(final Statement base, final Description description) {
        return container.apply(base, description);
    }

    /**
     * To be called by a test class method annotated with {@link org.junit.BeforeClass}.  This logic doesn't work
     * when placed inside of the constructor or {@link this#apply(Statement, Description)} methods here, presumably
     * because the Docker container spawned by TestContainers is not ready to accept commonds until after those
     * methods complete.
     *
     * <p>This method initializes the Vault server, capturing the unseal key and root token that are displayed on the
     * console.  It then uses the key to unseal the Vault instance, and stores the token in a member field so it
     * will be available to other methods.</p>
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void initAndUnsealVault() throws IOException, InterruptedException {
        final Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(LOGGER);
        container.followOutput(logConsumer);

        // Initialize the Vault server
        final Container.ExecResult initResult = runCommand("vault", "operator", "init", "-ca-cert=" +
                CONTAINER_CERT_PEMFILE, "-key-shares=1", "-key-threshold=1");
        final String stdout = initResult.getStdout().replaceAll(System.lineSeparator(), "").split("Vault initialized")[0];
        final String[] tokens = stdout.split("Initial Root Token: ");
        this.unsealKey = tokens[0].replace("Unseal Key 1: ", "");
        this.rootToken = tokens[1];

        System.out.println("Root token: " + rootToken);

        // Unseal the Vault server
        runCommand("vault", "operator", "unseal", "-ca-cert=" + CONTAINER_CERT_PEMFILE, unsealKey);
    }

    /**
     * Prepares the Vault server for testing of the AppID auth backend (i.e. mounts the backend and populates test
     * data).
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void setupBackendAppId() throws IOException, InterruptedException {
        runCommand("vault", "login", "-ca-cert=" + CONTAINER_CERT_PEMFILE, rootToken);

        runCommand("vault", "auth", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "app-id");
        runCommand("vault", "write", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "auth/app-id/map/app-id/" + APP_ID,
                "display_name=" + APP_ID);
        runCommand("vault", "write", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "auth/app-id/map/user-id/" + USER_ID,
                "value=" + APP_ID);
    }

    /**
     * Prepares the Vault server for testing of the Username and Password auth backend (i.e. mounts the backend and
     * populates test data).
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void setupBackendUserPass() throws IOException, InterruptedException {
        runCommand("vault", "login", "-ca-cert=" + CONTAINER_CERT_PEMFILE, rootToken);

        runCommand("vault", "auth", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "userpass");
        runCommand("vault", "write", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "auth/userpass/users/" + USER_ID,
                "password=" + PASSWORD);
    }

    /**
     * Prepares the Vault server for testing of the AppRole auth backend (i.e. mounts the backend and populates test
     * data).
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void setupBackendAppRole() throws IOException, InterruptedException {
        runCommand("vault", "login", "-ca-cert=" + CONTAINER_CERT_PEMFILE, rootToken);

        runCommand("vault", "auth", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "approle");
        runCommand("vault", "write", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "auth/approle/role/testrole",
                "secret_id_ttl=10m", "token_ttl=20m", "token_max_ttl=30m", "secret_id_num_uses=40");
    }

    /**
     * Prepares the Vault server for testing of the PKI auth backend (i.e. mounts the backend and populates test data).
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void setupBackendPki() throws IOException, InterruptedException {
        runCommand("vault", "login", "-ca-cert=" + CONTAINER_CERT_PEMFILE, rootToken);

        runCommand("vault", "secrets", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "-path=pki", "pki");
        runCommand("vault", "secrets", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "-path=other-pki", "pki");

        runCommand("vault", "secrets", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "-path=pki-custom-path-1", "pki");
        runCommand("vault", "secrets", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "-path=pki-custom-path-2", "pki");
        runCommand("vault", "secrets", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "-path=pki-custom-path-3", "pki");

        runCommand("vault", "write", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "pki/root/generate/internal",
                "common_name=myvault.com", "ttl=99h");
    }

    /**
     * Prepares the Vault server for testing of the TLS Certificate auth backend (i.e. mounts the backend and registers
     * the certificate and private key for client auth).
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void setupBackendCert() throws IOException, InterruptedException {
        runCommand("vault", "login", "-ca-cert=" + CONTAINER_CERT_PEMFILE, rootToken);

        runCommand("vault", "auth", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "cert");
        runCommand("vault", "write", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "auth/cert/certs/web", "display_name=web",
                "policies=web,prod", "certificate=@" + CONTAINER_CLIENT_CERT_PEMFILE, "ttl=3600");
    }

    public void setEngineVersions() throws IOException, InterruptedException {
        // Upgrade default secrets/ Engine to V2, set a new V1 secrets path at "kv-v1/"
        runCommand("vault", "kv", "enable-versioning", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "secret/");
        runCommand("vault", "secrets", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "-path=secret", "-version=2", "kv");
        runCommand("vault", "secrets", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "-path=kv-v1", "-version=1", "kv");
        runCommand("vault", "secrets", "enable", "-ca-cert=" + CONTAINER_CERT_PEMFILE, "-path=kv-v1-Upgrade-Test", "-version=1", "kv");
    }
    
    /**
     * <p>Constructs an instance of the Vault driver, providing maximum flexibility to control all options
     * explicitly.</p>
     *
     * <p>If <code>maxRetries</code> and <code>retryMillis</code> are BOTH null, then the <code>Vault</code>
     * instance will be constructed with retry logic disabled.  If one OR the other are null, the the class-level
     * default value will be used in place of the missing one.</p>
     *
     * @param config
     * @param maxRetries
     * @param retryMillis
     * @return
     */
    public Vault getVault(final VaultConfig config, final Integer maxRetries, final Integer retryMillis) {
        Vault vault = new Vault(config);
        if (maxRetries != null && retryMillis != null) {
            vault = vault.withRetries(maxRetries, retryMillis);
        } else if (maxRetries != null) {
            vault = vault.withRetries(maxRetries, RETRY_MILLIS);
        } else if (retryMillis != null) {
            vault = vault.withRetries(MAX_RETRIES, retryMillis);
        }
        return vault;
    }

    /**
     * Constructs an instance of the Vault driver, using sensible defaults.
     *
     * @return
     * @throws VaultException
     */
    public Vault getVault() throws VaultException {
        final VaultConfig config =
                new VaultConfig()
                        .address(getAddress())
                        .openTimeout(5)
                        .readTimeout(30)
                        .sslConfig(new SslConfig().pemFile(new File(CERT_PEMFILE)).build())
                        .build();
        return getVault(config, MAX_RETRIES, RETRY_MILLIS);
    }

    /**
     * Constructs a VaultConfig that can be used to configure your own tests
     *
     * @return
     * @throws VaultException
     */

    public VaultConfig getVaultConfig() throws VaultException {
        return new VaultConfig()
                .address(getAddress())
                .openTimeout(5)
                .readTimeout(30)
                .sslConfig(new SslConfig().pemFile(new File(CERT_PEMFILE)).build())
                .build();
    }

    /**
     * Constructs an instance of the Vault driver with sensible defaults, configured to use the supplied token
     * for authentication.
     *
     * @param token
     * @return
     * @throws VaultException
     */
    public Vault getVault(final String token) throws VaultException {
        final VaultConfig config =
                new VaultConfig()
                        .address(getAddress())
                        .token(token)
                        .openTimeout(5)
                        .readTimeout(30)
                        .sslConfig(new SslConfig().pemFile(new File(CERT_PEMFILE)).build())
                        .build();
        return new Vault(config).withRetries(MAX_RETRIES, RETRY_MILLIS);
    }

    /**
     * Constructs an instance of the Vault driver using a custom Vault config.
     *
     * @return
     * @throws VaultException
     */
    public Vault getRootVaultWithCustomVaultConfig(VaultConfig vaultConfig) throws VaultException {
        final VaultConfig config =
                vaultConfig
                        .address(getAddress())
                        .token(rootToken)
                        .openTimeout(5)
                        .readTimeout(30)
                        .sslConfig(new SslConfig().pemFile(new File(CERT_PEMFILE)).build())
                        .build();
        return new Vault(config).withRetries(MAX_RETRIES, RETRY_MILLIS);
    }

    /**
     * Constructs an instance of the Vault driver with sensible defaults, configured to the use the root token
     * for authentication.
     *
     * @return
     * @throws VaultException
     */
    public Vault getRootVault() throws VaultException {
        return getVault(rootToken).withRetries(MAX_RETRIES, RETRY_MILLIS);
    }

    /**
     * The Docker container uses bridged networking.  Meaning that Vault listens on port 8200 inside the container,
     * but the tests running on the host machine cannot reach that port directly.  Instead, the Vault connection
     * config has to use a port that is mapped to the container's port 8200.  There is no telling what the mapped
     * port will be until runtime, so this method is necessary to build a Vault connection URL with the appropriate
     * values.
     *
     * @return The URL of the Vault instance
     */
    public String getAddress() {
        return String.format("https://%s:%d", container.getContainerIpAddress(), container.getMappedPort(8200));
    }

    /**
     * Returns the master key for unsealing the Vault instance.  This method should really ONLY be used by tests
     * specifically for sealing and unsealing functionality (i.e. SealTests.java).  Generally, tests should
     * retrieve Vault instances from the "getVault(...)" or "getRootVault()" methods here, and never directly
     * concern themselves with the root token or unseal key at all.
     *
     * @return The master key for unsealing this Vault instance
     */
    public String getUnsealKey() {
        return unsealKey;
    }

    /**
     * Runs the specified command from within the Docker container.
     *
     * @param command The command to run, broken up by whitespace
     *                (e.g. "vault mount -path=pki pki" becomes "vault", "mount", "-path=pki", "pki")
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    private Container.ExecResult runCommand(final String... command) throws IOException, InterruptedException {
        LOGGER.info("Command: {}", String.join(" ", command));
        final Container.ExecResult result = this.container.execInContainer(command);
        final String out = result.getStdout();
        final String err = result.getStderr();
        if (out != null && !out.isEmpty()) {
            LOGGER.info("Command stdout: {}", result.getStdout());
        }
        if (err != null && !err.isEmpty()) {
            LOGGER.info("Command stderr: {}", result.getStderr());
        }
        return result;
    }
}
