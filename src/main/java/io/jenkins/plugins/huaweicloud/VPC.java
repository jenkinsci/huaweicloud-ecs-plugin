package io.jenkins.plugins.huaweicloud;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.auth.GlobalCredentials;
import com.huaweicloud.sdk.core.auth.ICredential;
import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.ecs.v2.EcsClient;
import com.huaweicloud.sdk.ecs.v2.model.*;
import com.huaweicloud.sdk.ecs.v2.region.EcsRegion;
import com.huaweicloud.sdk.eip.v2.EipClient;
import com.huaweicloud.sdk.eip.v2.region.EipRegion;
import com.huaweicloud.sdk.iam.v3.IamClient;
import com.huaweicloud.sdk.iam.v3.region.IamRegion;
import com.huaweicloud.sdk.ims.v2.ImsClient;
import com.huaweicloud.sdk.ims.v2.region.ImsRegion;
import hudson.Util;
import hudson.model.*;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.huaweicloud.credentials.AccessKeyCredentials;
import io.jenkins.plugins.huaweicloud.credentials.HWCAccessKeyCredentials;
import io.jenkins.plugins.huaweicloud.util.TimeUtils;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

public abstract class VPC extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(VPC.class.getName());
    private final String credentialsId;
    private final String sshKeysCredentialsId;
    private final String vpcID;
    private String region;
    private final int instanceCap;
    private static final SimpleFormatter sf = new SimpleFormatter();
    private final List<? extends ECSTemplate> templates;
    private transient NovaKeypair usableKeyPair;
    private transient ReentrantLock slaveCountingLock = new ReentrantLock();

    protected VPC(String id, @CheckForNull String credentialsId, @CheckForNull String sshKeysCredentialsId, String instanceCapStr,
                  String vpcID, List<? extends ECSTemplate> templates) {
        super(id);
        this.credentialsId = credentialsId;
        this.sshKeysCredentialsId = sshKeysCredentialsId;
        this.vpcID = vpcID;

        if (instanceCapStr == null || instanceCapStr.isEmpty()) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        if (templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        readResolve();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getRegion() {
        return region;
    }

    @DataBoundSetter
    public void setRegion(String region) {
        this.region = region;
    }

    protected Object readResolve() {
        this.slaveCountingLock = new ReentrantLock();
        for (ECSTemplate t : templates)
            t.parent = this;
        return this;
    }

    @CheckForNull
    public String getSshKeysCredentialsId() {
        return sshKeysCredentialsId;
    }

    public String getInstanceCapStr() {
        if (instanceCap == Integer.MAX_VALUE)
            return "";
        else
            return String.valueOf(instanceCap);
    }


    public String getVpcID() {
        return this.vpcID;
    }

    public List<ECSTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    @CheckForNull
    public ECSTemplate getTemplate(String template) {
        for (ECSTemplate t : templates) {
            if (t.description.equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets list of {@link ECSTemplate} that matches {@link Label}.
     */
    public Collection<ECSTemplate> getTemplates(Label label) {
        List<ECSTemplate> matchingTemplates = new ArrayList<>();
        for (ECSTemplate t : templates) {
            if (t.getMode() == Node.Mode.NORMAL) {
                if (label == null || label.matches(t.getLabelSet())) {
                    matchingTemplates.add(t);
                }
            } else if (t.getMode() == Node.Mode.EXCLUSIVE) {
                if (label != null && label.matches(t.getLabelSet())) {
                    matchingTemplates.add(t);
                }
            }
        }
        return matchingTemplates;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public EcsClient getEcsClient() {
        return createEcsClient(this.region, this.credentialsId);
    }

    public EipClient getEipClient() {
        return createEipClient(this.region, this.credentialsId);
    }

    public abstract URL getEc2EndpointUrl() throws IOException;

    @CheckForNull
    private static SSHUserPrivateKey getSshCredential(String id) {
        SSHUserPrivateKey credential = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        SSHUserPrivateKey.class, // (1)
                        (ItemGroup) null,
                        null,
                        Collections.emptyList()),
                CredentialsMatchers.withId(id));

        if (credential == null) {
            LOGGER.log(Level.WARNING, "ECS Plugin could not find the specified credentials ({0}) in the Jenkins Global Credentials Store, ECS Plugin for cloud must be manually reconfigured", new String[]{id});
        }

        return credential;
    }

    @CheckForNull
    public ECSPrivateKey resolvePrivateKey() {
        if (sshKeysCredentialsId != null) {
            SSHUserPrivateKey privateKeyCredential = getSshCredential(sshKeysCredentialsId);
            if (privateKeyCredential != null) {
                return new ECSPrivateKey(privateKeyCredential.getPrivateKey(), privateKeyCredential.getId(), getEcsClient());
            }
        }
        return null;
    }

    /**
     * Debug command to attach to a running instance.
     */
    @RequirePOST
    public void doAttach(StaplerRequest req, StaplerResponse rsp, @QueryParameter String id)
            throws ServletException, IOException, SdkException {
        checkPermission(PROVISION);
        ECSTemplate t = getTemplates().get(0);

        StringWriter sw = new StringWriter();
        StreamTaskListener listener = new StreamTaskListener(sw);
        ECSAbstractSlave node = t.attach(id, listener);
        Jenkins.get().addNode(node);

        rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
    }

    @RequirePOST
    public HttpResponse doProvision(@QueryParameter String template) throws ServletException, IOException {
        checkPermission(PROVISION);
        if (template == null) {
            throw hudson.util.HttpResponses.error(SC_BAD_REQUEST, "The 'template' query parameter is missing");
        }
        ECSTemplate t = getTemplate(template);
        if (t == null) {
            throw hudson.util.HttpResponses.error(SC_BAD_REQUEST, "No such template: " + template);
        }

        final Jenkins jenkinsInstance = Jenkins.get();
        if (jenkinsInstance.isQuietingDown()) {
            throw hudson.util.HttpResponses.error(SC_BAD_REQUEST, "Jenkins instance is quieting down");
        }
        if (jenkinsInstance.isTerminating()) {
            throw hudson.util.HttpResponses.error(SC_BAD_REQUEST, "Jenkins instance is terminating");
        }
        try {
            List<ECSAbstractSlave> nodes = getNewOrExistingAvailableSlave(t, 1, true);
            if (nodes == null || nodes.isEmpty())
                throw hudson.util.HttpResponses.error(SC_BAD_REQUEST, "Cloud or AMI instance cap would be exceeded for: " + template);

            // Reconnect a stopped instance, the ADD is invoking the connect only for the node creation
            Computer c = nodes.get(0).toComputer();
            if (nodes.get(0).getStopOnTerminate() && c != null) {
                c.connect(false);
            }
            jenkinsInstance.addNode(nodes.get(0));

            return hudson.util.HttpResponses.redirectViaContextPath("/computer/" + nodes.get(0).getNodeName());
        } catch (SdkException e) {
            throw HttpResponses.error(SC_INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * Gets the {@link NovaKeypairDetail} used for the launch.
     */
    @CheckForNull
    public synchronized NovaKeypair getKeyPair() throws SdkException, IOException {
        if (usableKeyPair == null) {
            ECSPrivateKey ecsPrivateKey = this.resolvePrivateKey();
            if (ecsPrivateKey != null) {
                usableKeyPair = ecsPrivateKey.find();
            }
        }
        return usableKeyPair;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(final Cloud.CloudState state, int excessWorkload) {
        Label label = state.getLabel();
        final Collection<ECSTemplate> matchingTemplates = getTemplates(label);
        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();

        Jenkins jenkinsInstance = Jenkins.get();
        if (jenkinsInstance.isQuietingDown()) {
            LOGGER.log(Level.FINE, "Not provisioning nodes, Jenkins instance is quieting down");
            return Collections.emptyList();
        } else if (jenkinsInstance.isTerminating()) {
            LOGGER.log(Level.FINE, "Not provisioning nodes, Jenkins instance is terminating");
            return Collections.emptyList();
        }

        for (ECSTemplate t : matchingTemplates) {
            try {
                LOGGER.log(Level.INFO, "{0}. Attempting to provision slave needed by excess workload of " + excessWorkload + " units", t);
                int number = Math.max(excessWorkload / t.getNumExecutors(), 1);
                final List<ECSAbstractSlave> slaves = getNewOrExistingAvailableSlave(t, number, false);

                if (slaves == null || slaves.isEmpty()) {
                    LOGGER.warning("Can't raise nodes for " + t);
                    continue;
                }
                for (final ECSAbstractSlave slave : slaves) {
                    if (slave == null) {
                        LOGGER.warning("Can't raise node for " + t);
                        continue;
                    }

                    plannedNodes.add(createPlannedNode(t, slave));
                    excessWorkload -= t.getNumExecutors();
                }
                LOGGER.log(Level.INFO, "{0}. Attempting provision finished, excess workload: " + excessWorkload, t);
                if (excessWorkload == 0) break;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
            }
        }
        LOGGER.log(Level.INFO, "We have now {0} computers, waiting for {1} more",
                new Object[]{jenkinsInstance.getComputers().length, plannedNodes.size()});
        return plannedNodes;
    }

    private NodeProvisioner.PlannedNode createPlannedNode(ECSTemplate t, ECSAbstractSlave slave) {
        return new NodeProvisioner.PlannedNode(t.getDisplayName(),
                Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    int retryCount = 0;
                    private static final int DESCRIBE_LIMIT = 2;

                    public Node call() throws Exception {
                        while (true) {
                            String instanceId = slave.getInstanceId();
                            ServerDetail instance = VPCHelper.getInstanceWithRetry(instanceId, slave.getCloud());
                            if (instance == null) {
                                LOGGER.log(Level.WARNING, "{0} Can't find instance with instance id `{1}` in cloud {2}. Terminate provisioning ",
                                        new Object[]{t, instanceId, slave.cloudName});
                                return null;
                            }

                            String state = instance.getStatus();
                            if (state.equals("ACTIVE")) {
                                Computer c = slave.toComputer();
                                if (slave.getStopOnTerminate() && (c != null)) {
                                    c.connect(false);
                                }

                                long startTime = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - TimeUtils.dateStrToLong(instance.getUpdated()));
                                LOGGER.log(Level.INFO, "{0} Node {1} moved to RUNNING state in {2} seconds and is ready to be connected by Jenkins",
                                        new Object[]{t, slave.getNodeName(), startTime});
                                return slave;
                            }

                            if (!state.equals("BUILD")) {
                                if (retryCount >= DESCRIBE_LIMIT) {
                                    LOGGER.log(Level.WARNING, "Instance {0} did not move to running after {1} attempts, terminating provisioning",
                                            new Object[]{instanceId, retryCount});
                                    return null;
                                }

                                LOGGER.log(Level.INFO, "Attempt {0}: {1}. Node {2} is neither pending, neither running, it''s {3}. Will try again after 5s",
                                        new Object[]{retryCount, t, slave.getNodeName(), state});
                                retryCount++;
                            }

                            Thread.sleep(5000);
                        }
                    }
                })
                , t.getNumExecutors());
    }

    @Override
    public boolean canProvision(Cloud.CloudState state) {
        return !getTemplates(state.getLabel()).isEmpty();
    }

    public void provision(ECSTemplate t, int number) {
        Jenkins jenkinsInstance = Jenkins.get();
        if (jenkinsInstance.isQuietingDown()) {
            LOGGER.log(Level.FINE, "Not provisioning nodes, Jenkins instance is quieting down");
            return;
        } else if (jenkinsInstance.isTerminating()) {
            LOGGER.log(Level.FINE, "Not provisioning nodes, Jenkins instance is terminating");
            return;
        }
        try {
            LOGGER.log(Level.INFO, "{0}. Attempting to provision {1} slave(s)", new Object[]{t, number});
            final List<ECSAbstractSlave> slaves = getNewOrExistingAvailableSlave(t, number, false);

            if (slaves == null || slaves.isEmpty()) {
                LOGGER.warning("Can't raise nodes for " + t);
                return;
            }

            attachSlavesToJenkins(jenkinsInstance, slaves, t);

            LOGGER.log(Level.INFO, "{0}. Attempting provision finished", t);
            LOGGER.log(Level.INFO, "We have now {0} computers, waiting for {1} more",
                    new Object[]{Jenkins.get().getComputers().length, number});
        } catch (SdkException | IOException e) {
            LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
        }

    }

    private static void attachSlavesToJenkins(Jenkins jenkins, List<ECSAbstractSlave> slaves, ECSTemplate t) throws IOException {
        for (final ECSAbstractSlave slave : slaves) {
            if (slave == null) {
                LOGGER.warning("Can't raise node for " + t);
                continue;
            }

            Computer c = slave.toComputer();
            if (slave.getStopOnTerminate() && c != null) {
                c.connect(false);
            }
            jenkins.addNode(slave);
        }
    }

    private List<ECSAbstractSlave> getNewOrExistingAvailableSlave(ECSTemplate t, int number, boolean forceCreateNew) {
        try {
            slaveCountingLock.lock();
            int possibleSlavesCount = getPossibleNewSlavesCount(t);
            if (possibleSlavesCount <= 0) {
                LOGGER.log(Level.INFO, "{0}. Cannot provision - no capacity for instances: " + possibleSlavesCount, t);
                return null;
            }

            try {
                EnumSet<ECSTemplate.ProvisionOptions> provisionOptions;
                if (forceCreateNew)
                    provisionOptions = EnumSet.of(ECSTemplate.ProvisionOptions.FORCE_CREATE);
                else
                    provisionOptions = EnumSet.of(ECSTemplate.ProvisionOptions.ALLOW_CREATE);
                if (number > possibleSlavesCount) {
                    LOGGER.log(Level.INFO, String.format("%d nodes were requested for the template %s, " +
                            "but because of instance cap only %d can be provisioned", number, t, possibleSlavesCount));
                    number = possibleSlavesCount;
                }
                return t.provision(number, provisionOptions);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
                return null;
            }
        } finally {
            slaveCountingLock.unlock();
        }
    }

    private int getPossibleNewSlavesCount(ECSTemplate t) {
        List<ServerDetail> allInstances = VPCHelper.getAllOfServerList(this);
        List<ServerDetail> tmpInstance = VPCHelper.getAllOfAvailableServerByTmp(t);
        int availableTotalSlave = instanceCap - allInstances.size();
        int availableTmpSlave = t.getInstanceCap() - tmpInstance.size();
        LOGGER.log(Level.FINE, "Available Total Slaves: " + availableTotalSlave + " Available AMI slaves: " + availableTmpSlave
                + " AMI: " + t.getImgID() + " TemplateDesc: " + t.description);
        return Math.min(availableTotalSlave, availableTmpSlave);
    }

    public static void log(Logger logger, Level level, TaskListener listener, String message) {
        log(logger, level, listener, message, null);
    }

    public static void log(Logger logger, Level level, TaskListener listener, String message, Throwable exception) {
        logger.log(level, message, exception);
        if (listener != null) {
            if (exception != null)
                message += " Exception: " + exception;
            LogRecord lr = new LogRecord(level, message);
            lr.setLoggerName(LOGGER.getName());
            PrintStream printStream = listener.getLogger();
            printStream.print(sf.format(lr));
        }
    }

    private static String getAccessKey(AccessKeyCredentials acCredentials) {
        if (acCredentials == null) {
            return "";
        }
        return acCredentials.getAccessKey() == null ? "" : acCredentials.getAccessKey().getPlainText();
    }

    private static String getSecretKey(AccessKeyCredentials acCredentials) {
        if (acCredentials == null) {
            return "";
        }
        return acCredentials.getSecretKey() == null ? "" : acCredentials.getSecretKey().getPlainText();
    }

    public static ICredential createGlobalCredential(String credentialsId) {
        AccessKeyCredentials acCredentials = getCredentials(credentialsId);
        String accessKey = getAccessKey(acCredentials);
        String secretKey = getSecretKey(acCredentials);
        return new GlobalCredentials().withAk(accessKey).withSk(secretKey);
    }

    public static ICredential createBasicCredential(String credentialsId) {
        AccessKeyCredentials acCredentials = getCredentials(credentialsId);
        String accessKey = getAccessKey(acCredentials);
        String secretKey = getSecretKey(acCredentials);
        return new BasicCredentials().withAk(accessKey).withSk(secretKey);
    }

    public static IamClient createIamClient(String region, String credentialsId) {
        ICredential auth = createGlobalCredential(credentialsId);
        return IamClient.newBuilder()
                .withCredential(auth)
                .withRegion(IamRegion.valueOf(region))
                .build();
    }

    public static EcsClient createEcsClient(String region, String credentialsId) {
        ICredential auth = createBasicCredential(credentialsId);
        return EcsClient.newBuilder()
                .withCredential(auth)
                .withRegion(EcsRegion.valueOf(region))
                .build();
    }

    public static EipClient createEipClient(String region, String credentialsId) {
        ICredential auth = createBasicCredential(credentialsId);
        return EipClient.newBuilder()
                .withCredential(auth)
                .withRegion(EipRegion.valueOf(region))
                .build();
    }

    public static ImsClient createImsClient(String region, String credentialsId) {
        ICredential auth = createBasicCredential(credentialsId);
        return ImsClient.newBuilder()
                .withCredential(auth)
                .withRegion(ImsRegion.valueOf(region))
                .build();
    }

    public static AccessKeyCredentials getCredentials(@CheckForNull String credentialsId) {
        if (StringUtils.isBlank(credentialsId)) {
            return null;
        }
        return (AccessKeyCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        HWCAccessKeyCredentials.class, Jenkins.get(), ACL.SYSTEM, Collections.EMPTY_LIST),
                CredentialsMatchers.withId(credentialsId));
    }

    public static abstract class DescriptorImpl extends Descriptor<Cloud> {

        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error(Messages.HuaweiECSCloud_MalformedCredentials());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckVpcID(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("no vpcID is specified");
            }
            int found = 0;
            for (Cloud c : Jenkins.get().clouds) {
                if (c instanceof VPC) {
                    VPC vpc = (VPC) c;
                    if (vpc.getVpcID() != null && vpc.getVpcID().equals(value)) {
                        found++;
                    }
                }
            }
            if (found > 1) {
                return FormValidation.error(Messages.HuaweiECSCloud_NonUniqName());
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .withMatching(
                            CredentialsMatchers.always(),
                            CredentialsProvider.lookupCredentials(HWCAccessKeyCredentials.class,
                                    Jenkins.get(),
                                    ACL.SYSTEM,
                                    Collections.emptyList()));
        }

        public ListBoxModel doFillSshKeysCredentialsIdItems(@QueryParameter String sshKeysCredentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            StandardListBoxModel result = new StandardListBoxModel();

            return result
                    .includeMatchingAs(Jenkins.getAuthentication(), Jenkins.get(), SSHUserPrivateKey.class, Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                    .includeMatchingAs(ACL.SYSTEM, Jenkins.get(), SSHUserPrivateKey.class, Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                    .includeCurrentValue(sshKeysCredentialsId);
        }

        @RequirePOST
        public FormValidation doCheckSshKeysCredentialsId(@QueryParameter String value) throws IOException, ServletException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("No ssh credentials selected");
            }

            SSHUserPrivateKey sshCredential = getSshCredential(value);
            String privateKey = "";
            if (sshCredential != null) {
                privateKey = sshCredential.getPrivateKey();
            } else {
                return FormValidation.error("Failed to find credential \"" + value + "\" in store.");
            }

            boolean hasStart = false, hasEnd = false;
            BufferedReader br = new BufferedReader(new StringReader(privateKey));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----") ||
                        line.equals("-----BEGIN OPENSSH PRIVATE KEY-----"))
                    hasStart = true;
                if (line.equals("-----END RSA PRIVATE KEY-----") ||
                        line.equals("-----END OPENSSH PRIVATE KEY-----"))
                    hasEnd = true;
            }
            if (!hasStart)
                return FormValidation.error("This doesn't look like a private key at all");
            if (!hasEnd)
                return FormValidation
                        .error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        protected FormValidation doTestConnection(String region, String credentialsId, String sshKeysCredentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            try {
                SSHUserPrivateKey sshCredential = getSshCredential(sshKeysCredentialsId);
                String privateKey = "";
                if (sshCredential != null) {
                    privateKey = sshCredential.getPrivateKey();
                } else {
                    return FormValidation.error("Failed to find credential \"" + sshKeysCredentialsId + "\" in store.");
                }
                if (privateKey.trim().length() > 0) {
                    EcsClient ecsClient = createEcsClient(region, credentialsId);
                    ECSPrivateKey pk = new ECSPrivateKey(privateKey, sshCredential.getId(), ecsClient);
                    if (pk.find() == null) {
                        return FormValidation
                                .error("The ECS key pair private key isn't registered to this EC2 region (fingerprint is "
                                        + pk.getPublicFingerprint() + ")");
                    }
                }
                return FormValidation.ok("success:");
            } catch (SdkException | IOException e) {
                e.printStackTrace();
                return FormValidation.error("connection exception...");
            }
        }
    }
}
