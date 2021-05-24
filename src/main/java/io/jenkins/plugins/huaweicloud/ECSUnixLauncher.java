package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.ecs.v2.model.NovaKeypair;
import com.huaweicloud.sdk.ecs.v2.model.ServerAddress;
import com.huaweicloud.sdk.ecs.v2.model.ServerDetail;
import com.huaweicloud.sdk.eip.v2.model.PublicipShowResp;
import com.trilead.ssh2.*;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import io.jenkins.plugins.huaweicloud.util.AssociateEIP;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ECSUnixLauncher extends ECSComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(ECSUnixLauncher.class.getName());

    private static final String BOOTSTRAP_AUTH_SLEEP_MS = "jenkins.huaweicloud.bootstrapAuthSleepMs";
    private static final String BOOTSTRAP_AUTH_TRIES = "jenkins.huaweicloud.bootstrapAuthTries";
    private static final String READINESS_SLEEP_MS = "jenkins.huaweicloud.readinessSleepMs";
    private static final String READINESS_TRIES = "jenkins.huaweicloud.readinessTries";

    private static int bootstrapAuthSleepMs = 30000;
    private static int bootstrapAuthTries = 30;

    private static int readinessSleepMs = 1000;
    private static int readinessTries = 120;

    static {
        String prop = System.getProperty(BOOTSTRAP_AUTH_SLEEP_MS);
        if (prop != null)
            bootstrapAuthSleepMs = Integer.parseInt(prop);
        prop = System.getProperty(BOOTSTRAP_AUTH_TRIES);
        if (prop != null)
            bootstrapAuthTries = Integer.parseInt(prop);
        prop = System.getProperty(READINESS_TRIES);
        if (prop != null)
            readinessTries = Integer.parseInt(prop);
        prop = System.getProperty(READINESS_SLEEP_MS);
        if (prop != null)
            readinessSleepMs = Integer.parseInt(prop);
    }

    protected void log(Level level, ECSComputer computer, TaskListener listener, String message) {
        VPC.log(LOGGER, level, listener, message);
    }

    protected void logException(ECSComputer computer, TaskListener listener, String message, Throwable exception) {
        VPC.log(LOGGER, Level.WARNING, listener, message, exception);
    }

    protected void logInfo(ECSComputer computer, TaskListener listener, String message) {
        log(Level.INFO, computer, listener, message);
    }

    protected void logWarning(ECSComputer computer, TaskListener listener, String message) {
        log(Level.WARNING, computer, listener, message);
    }

    protected String buildUpCommand(ECSComputer computer, String command) {
        String remoteAdmin = computer.getRemoteAdmin();
        if (remoteAdmin != null && !remoteAdmin.equals("root")) {
            command = computer.getRootCommandPrefix() + " " + command;
        }
        return command;
    }

    @Override
    protected void launchScript(ECSComputer computer, TaskListener listener) throws SdkException, IOException, InterruptedException {
        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final doesn't work that well.
        boolean successful = false;
        PrintStream logger = listener.getLogger();
        ECSAbstractSlave node = computer.getNode();
        ECSTemplate template = computer.getSlaveTemplate();

        if (node == null) {
            throw new IllegalStateException();
        }

        if (template == null) {
            throw new IOException("Could not find corresponding slave template for " + computer.getDisplayName());
        }

        logInfo(computer, listener, "Launching instance: " + node.getInstanceId());

        try {
            boolean isBootstrapped = bootstrap(computer, listener, template);
            if (isBootstrapped) {
                // connect fresh as ROOT
                logInfo(computer, listener, "connect fresh as root");
                cleanupConn = connectToSsh(computer, listener, template);
                NovaKeypair key = computer.getCloud().getKeyPair();
                if (key == null || !cleanupConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getPrivateKey().toCharArray(), "")) {
                    logWarning(computer, listener, "Authentication failed");
                    return; // failed to connect as root.
                }
            } else {
                logWarning(computer, listener, "bootstrapresult failed");
                return; // bootstrap closed for us.
            }
            conn = cleanupConn;
            SCPClient scp = conn.createSCPClient();
            String initScript = node.initScript;
            String tmpDir = (Util.fixEmptyAndTrim(node.tmpDir) != null ? node.tmpDir : "/tmp");
            logInfo(computer, listener, "Creating tmp directory (" + tmpDir + ") if it does not exist");
            conn.exec("mkdir -p " + tmpDir, logger);
            if (initScript != null && initScript.trim().length() > 0
                    && conn.exec("test -e ~/.hudson-run-init", logger) != 0) {
                logInfo(computer, listener, "Executing init script");
                scp.put(initScript.getBytes("UTF-8"), "init.sh", tmpDir, "0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout
                // and stderr
                sess.execCommand(buildUpCommand(computer, tmpDir + "/init.sh"));

                sess.getStdin().close(); // nothing to write here
                sess.getStderr().close(); // we are not supposed to get anything
                // from stderr
                IOUtils.copy(sess.getStdout(), logger);

                int exitStatus = waitCompletion(sess);
                if (exitStatus != 0) {
                    logWarning(computer, listener, "init script failed: exit code=" + exitStatus);
                    return;
                }
                sess.close();

                logInfo(computer, listener, "Creating ~/.hudson-run-init");

                // Needs a tty to run sudo.
                sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout
                // and stderr
                sess.execCommand(buildUpCommand(computer, "touch ~/.hudson-run-init"));

                sess.getStdin().close(); // nothing to write here
                sess.getStderr().close(); // we are not supposed to get anything
                // from stderr
                IOUtils.copy(sess.getStdout(), logger);

                exitStatus = waitCompletion(sess);
                if (exitStatus != 0) {
                    logWarning(computer, listener, "init script failed: exit code=" + exitStatus);
                    return;
                }
                sess.close();
            }
            checkAndInstallJava(computer, conn, "java -fullversion",  logger, listener);
            executeRemote(computer, conn, "which scp", "sudo yum install -y openssh-clients", logger, listener);
            // Always copy so we get the most recent slave.jar
            logInfo(computer, listener, "Copying remoting.jar to: " + tmpDir);
            scp.put(Jenkins.get().getJnlpJars("remoting.jar").readFully(), "remoting.jar", tmpDir);
            final String prefix = computer.getSlaveCommandPrefix();
            final String suffix = computer.getSlaveCommandSuffix();
            final String remoteFS = node.getRemoteFS();
            final String workDir = Util.fixEmptyAndTrim(remoteFS) != null ? remoteFS : tmpDir;
            String launchString = prefix + " java " + " -jar " + tmpDir + "/remoting.jar -workDir " + workDir + suffix;
            // launchString = launchString.trim();
            //TODO: use  ssh process if (slaveTemplate != null && slaveTemplate.isConnectBySSHProcess())
            logInfo(computer, listener, "Launching remoting agent (via Trilead SSH2 Connection): " + launchString);
            final Session sess = conn.openSession();
            sess.execCommand(launchString);
            computer.setChannel(sess.getStdout(), sess.getStdin(), logger, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });
            successful = true;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            if (cleanupConn != null && !successful)
                cleanupConn.close();
        }
    }

    private boolean bootstrap(ECSComputer computer, TaskListener listener, ECSTemplate template) throws IOException,
            InterruptedException, SdkException {
        logInfo(computer, listener, "bootstrap()");
        Connection bootstrapConn = null;
        try {
            int tries = bootstrapAuthTries;
            boolean isAuthenticated = false;
            logInfo(computer, listener, "Getting keypair...");
            NovaKeypair key = computer.getCloud().getKeyPair();
            if (key == null) {
                logWarning(computer, listener, "Cloud not retrieve a valid key pair .");
                return false;
            }
            logInfo(computer, listener,
                    String.format("Using private key %s (SHA-1 fingerprint %s)", key.getName(), key.getFingerprint()));
            while (tries-- > 0) {
                logInfo(computer, listener, "Authenticating as " + computer.getRemoteAdmin());
                try {
                    bootstrapConn = connectToSsh(computer, listener, template);
                    isAuthenticated = bootstrapConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getPrivateKey().toCharArray(), "");
                } catch (IOException e) {
                    logException(computer, listener, "Exception trying to authenticate", e);
                    bootstrapConn.close();
                }
                if (isAuthenticated) {
                    break;
                }
                logWarning(computer, listener, "Authentication failed. Trying again...");
                Thread.sleep(bootstrapAuthSleepMs);
            }
            if (!isAuthenticated) {
                logWarning(computer, listener, "Authentication failed");
                return false;
            }
        } finally {
            if (bootstrapConn != null) {
                bootstrapConn.close();
            }
        }
        return true;
    }

    private Connection connectToSsh(ECSComputer computer, TaskListener listener, ECSTemplate template)
            throws SdkException, InterruptedException {
        final ECSAbstractSlave node = computer.getNode();
        final long timeout = node == null ? 0L : node.getLaunchTimeoutInMillis();
        final long startTime = System.currentTimeMillis();
        while (true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (timeout > 0 && waitTime > timeout) {
                    throw new SdkException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for ssh to become available. (maximum timeout configured is "
                            + (timeout / 1000) + ")");
                }
                String host = getECSHostAddress(computer, template, true);
                if ("0.0.0.0".equals(host)) {
                    logWarning(computer, listener, "Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }
                int port = computer.getSshPort();
                Integer slaveConnectTimeout = Integer.getInteger("jenkins.hwc.slaveConnectTimeout", 10000);
                logInfo(computer, listener, "Connecting to " + host + " on port " + port + ", with timeout " + slaveConnectTimeout
                        + ".");
                Connection conn = new Connection(host, port);
                ProxyConfiguration proxyConfig = Jenkins.get().proxy;
                Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(host);
                if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
                    InetSocketAddress address = (InetSocketAddress) proxy.address();
                    HTTPProxyData proxyData = null;
                    if (null != proxyConfig.getUserName()) {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort(), proxyConfig.getUserName(), proxyConfig.getPassword());
                    } else {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort());
                    }
                    conn.setProxyData(proxyData);
                    logInfo(computer, listener, "Using HTTP Proxy Configuration");

                }
                conn.connect(new ServerHostKeyVerifierImpl(computer, listener), slaveConnectTimeout, slaveConnectTimeout);
                logInfo(computer, listener, "Connected via SSH.");
                return conn; // successfully connected
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logInfo(computer, listener, "Failed to connect via ssh: " + e.getMessage());

                // If the computer was set offline because it's not trusted, we avoid persisting in connecting to it.
                // The computer is offline for a long period
                if (computer.isOffline() && StringUtils.isNotBlank(computer.getOfflineCauseReason())
                        && computer.getOfflineCauseReason().equals(Messages.OfflineCause_SSHKeyCheckFailed())) {
                    throw new SdkException("The connection couldn't be established and the computer is now offline", e);
                } else {
                    logInfo(computer, listener, "Waiting for SSH to come up. Sleeping 5.");
                    Thread.sleep(5000);
                }

            }

        }
    }

    private String getECSHostAddress(ECSComputer computer, ECSTemplate template, boolean isPrivateIP) throws InterruptedException ,SdkException{
        ServerDetail serverDetail = computer.updateInstanceDescription();
        List<ServerAddress> serverAddresses = serverDetail.getAddresses().get(template.getParent().getVpcID());
        String publicIP = "";
        String privateIP = "";
        for (ServerAddress addr : serverAddresses) {
            if (addr.getOsEXTIPSType() == ServerAddress.OsEXTIPSTypeEnum.FIXED) {
                privateIP = addr.getAddr();
                continue;
            }
            if (addr.getOsEXTIPSType() == ServerAddress.OsEXTIPSTypeEnum.FLOATING) {
                publicIP = addr.getAddr();
            }
        }
        if (template.getAssociateEIP() && StringUtils.isNotEmpty(publicIP))
            return publicIP;
        else
            return privateIP;

    }

    private int waitCompletion(Session session) throws InterruptedException {
        // I noticed that the exit status delivery often gets delayed. Wait up
        // to 1 sec.
        for (int i = 0; i < 10; i++) {
            Integer r = session.getExitStatus();
            if (r != null)
                return r;
            Thread.sleep(100);
        }
        return -1;
    }

    private boolean executeRemote(ECSComputer computer, Connection conn, String checkCommand, String command, PrintStream logger, TaskListener listener)
            throws IOException, InterruptedException {
        logInfo(computer, listener, "Verifying: " + checkCommand);
        if (conn.exec(checkCommand, logger) != 0) {
            logInfo(computer, listener, "Installing: " + command);
            if (conn.exec(command, logger) != 0) {
                logWarning(computer, listener, "Failed to install: " + command);
                return false;
            }
        }
        return true;
    }

    private boolean checkAndInstallJava(ECSComputer computer, Connection conn, String checkCommand,
                                        PrintStream logger, TaskListener listener) throws IOException, InterruptedException {
        logInfo(computer, listener, "Verifying: " + checkCommand);
        if (conn.exec(checkCommand, logger) != 0) {
            //Since don’t know the corresponding package management tool of the system, optimization is needed here
            String rpmInstallCommand = "sudo yum install -y java-1.8.0-openjdk.x86_64";
            String aptInstallCommand = "sudo apt-get install -y openjdk-8-jdk";
            if (conn.exec(rpmInstallCommand, logger) == 0) {
                return true;
            }
            return conn.exec(aptInstallCommand, logger) == 0;
        }
        return true;

    }

    private static class ServerHostKeyVerifierImpl implements ServerHostKeyVerifier {
        private final ECSComputer computer;
        private final TaskListener listener;

        public ServerHostKeyVerifierImpl(final ECSComputer computer, final TaskListener listener) {
            this.computer = computer;
            this.listener = listener;
        }

        @Override
        public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
            VPC.log(LOGGER, Level.INFO, computer.getListener(), String.format("No SSH key verification (%s) for connections to %s", serverHostKeyAlgorithm, computer.getName()));
            return true;
        }
    }
}
