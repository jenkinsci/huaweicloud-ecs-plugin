package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.ecs.v2.model.ServerDetail;
import com.huaweicloud.sdk.ecs.v2.model.ServerTag;
import hudson.model.*;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.util.Secret;
import io.jenkins.plugins.huaweicloud.util.ResettableCountDownLatch;
import io.jenkins.plugins.huaweicloud.util.TimeUtils;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class ECSAbstractSlave extends Slave {
    private static final long serialVersionUID = -997180106037953766L;
    private static final Logger LOGGER = Logger.getLogger(ECSAbstractSlave.class.getName());
    private String instanceId;
    public final String initScript;
    public final String tmpDir;
    public final String remoteAdmin; // e.g. 'ubuntu'
    public final String templateDescription;
    public boolean isConnected = false;
    public List<ECSTag> tags;
    public final String cloudName;
    public final String idleTerminationMinutes;
    public final String offlineTimeout;
    public final boolean stopOnTerminate;
    public transient String slaveCommandPrefix;

    public transient String slaveCommandSuffix;

    /* The last instance data to be fetched for the slave */
    protected transient ServerDetail lastFetchInstance = null;

    /* The time at which we fetched the last instance data */
    protected transient long lastFetchTime;

    protected final int launchTimeout;

    /**
     * Terminate was scheduled
     */
    protected transient ResettableCountDownLatch terminateScheduled = new ResettableCountDownLatch(1, false);

    private transient long createdTime;

    protected static final long MIN_FETCH_TIME = Long.getLong("io.jenkins.plugins.huaweicloud.ECSAbstractSlave.MIN_FETCH_TIME",
            TimeUnit.SECONDS.toMillis(20));

    public ECSAbstractSlave(String name, String instanceId, String templateDescription,
                            String remoteFS, int numExecutors, Mode mode, String labelString,
                            List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin,
                            List<ECSTag> tags, String cloudName, String idleTerminationMinutes,
                            ComputerLauncher launcher, int launchTimeout, String initScript,
                            String tmpDir, RetentionStrategy<ECSComputer> retentionStrategy,
                            boolean stopOnTerminate, String offlineTimeout) throws Descriptor.FormException, IOException {
        super(name, remoteFS, launcher);
        setNumExecutors(numExecutors);
        setMode(mode);
        setLabelString(labelString);
        setRetentionStrategy(retentionStrategy);
        setNodeProperties(nodeProperties);
        this.instanceId = instanceId;
        this.templateDescription = templateDescription;
        this.remoteAdmin = remoteAdmin;
        this.initScript = initScript;
        this.tmpDir = tmpDir;
        this.tags = tags;
        this.cloudName = cloudName;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.launchTimeout = launchTimeout;
        this.stopOnTerminate = stopOnTerminate;
        this.offlineTimeout = offlineTimeout;
        readResolve();
    }

    public long getOfflineTimeoutMills() {
        long offlineTimeoutMinutes = 720;
        try {
            offlineTimeoutMinutes = Integer.parseInt(this.offlineTimeout.trim());
        } catch (Exception e) {
            LOGGER.info(e.getMessage());
        }
        return TimeUnit.MINUTES.toMillis(offlineTimeoutMinutes);
    }

    @Override
    protected Object readResolve() {
        /*
         * If instanceId is null, this object was deserialized from an old version of the plugin, where this field did
         * not exist (prior to version 1.18). In those versions, the node name *was* the instance ID, so we can get it
         * from there.
         */
        if (instanceId == null) {
            instanceId = getNodeName();
        }

        /*
         * If this field is null (as it would be if this object is deserialized and not constructed normally) then
         * we need to explicitly initialize it, otherwise we will cause major blocker issues such as this one which
         * made Jenkins entirely unusable for some in the 1.50 release:
         * https://issues.jenkins-ci.org/browse/JENKINS-62043
         */
        if (terminateScheduled == null) {
            terminateScheduled = new ResettableCountDownLatch(1, false);
        }

        return this;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public VPC getCloud() {
        return (VPC) Jenkins.get().getCloud(cloudName);
    }

    public boolean getStopOnTerminate() {
        return stopOnTerminate;
    }

    @Override
    public Computer createComputer() {
        return new ECSComputer(this);
    }

    /**
     * Terminates the instance in ECS.
     */
    public abstract void terminate();

    /*
     * Used to determine if the slave is On Demand or Spot
     */
    abstract public String getECSType();


    @Override
    public Node reconfigure(final StaplerRequest req, JSONObject form) throws Descriptor.FormException {
        if (form == null) {
            return null;
        }
        ECSAbstractSlave result = (ECSAbstractSlave) super.reconfigure(req, form);
        if (result != null) {
            //Get rid of the old tags, as represented by ourselves.
            clearLiveInstanceData();
            //Set the new tags, as represented by our successor
            result.pushLiveInstanceData();
            return result;
        }
        return null;
    }

    @Override
    public boolean isAcceptingTasks() {
        return terminateScheduled.getCount() == 0;
    }

    public String getRemoteAdmin() {
        if (StringUtils.isBlank(remoteAdmin)) {
            return "root";
        }
        return remoteAdmin;
    }

    String getRootCommandPrefix() {
        return "sudo";
    }

    String getSlaveCommandPrefix() {
        if (StringUtils.isEmpty(slaveCommandPrefix)) {
            return "";
        }
        return slaveCommandPrefix + " ";
    }

    String getSlaveCommandSuffix() {
        if (StringUtils.isEmpty(slaveCommandSuffix)) {
            return "";
        }
        return " " + slaveCommandSuffix;
    }

    public int getSshPort() {
        return 22;
    }

    public void onConnected() {
        isConnected = true;
    }

    protected boolean isAlive(boolean force) {
        fetchLiveInstanceData(force);
        if (lastFetchInstance == null)
            return false;
        return !VPCHelper.isTerminated(lastFetchInstance.getStatus());
    }

    protected void fetchLiveInstanceData(boolean force) throws SdkException {
        long now = System.currentTimeMillis();
        if ((lastFetchTime > 0) && (now - lastFetchTime < MIN_FETCH_TIME) && !force) {
            return;
        }

        if (StringUtils.isEmpty(getInstanceId())) {
            return;
        }
        ServerDetail instance = getServerDetail();
        if (instance == null) return;
        lastFetchTime = now;
        lastFetchInstance = instance;
        createdTime = TimeUtils.dateStrToLong(instance.getCreated());
        try {
            List<ServerTag> serverTags = VPCHelper.getServerTags(getInstanceId(), getCloud());
            if (!serverTags.isEmpty()) {
                //update tags
                tags = new LinkedList<>();
                for (ServerTag tag : serverTags) {
                    tags.add(new ECSTag(tag.getKey(), tag.getValue()));
                }
            }
        } catch (SdkException e) {
            LOGGER.log(Level.FINE, e.getMessage());
        }

    }

    @Nullable
    private ServerDetail getServerDetail() {
        ServerDetail instance = null;
        try {
            instance = VPCHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        } catch (InterruptedException e) {
            LOGGER.fine("InterruptedException while get " + getInstanceId()
                    + " Exception: " + e);
        }
        return instance;
    }

    protected void clearLiveInstanceData() throws SdkException {
        ServerDetail instance = getServerDetail();
        if (instance == null) return;
        List<ServerTag> serverTags = VPCHelper.getServerTags(instance.getId(), getCloud());
        if (!serverTags.isEmpty()) {
            VPCHelper.deleteServerTags(getInstanceId(), serverTags, getCloud());
        }
    }

    protected void pushLiveInstanceData() throws SdkException {
        ServerDetail instance = getServerDetail();
        if (instance == null) return;
        if (tags != null && !tags.isEmpty()) {
            List<ServerTag> srvTags = new ArrayList<>();
            for (ECSTag tag : tags) {
                srvTags.add(new ServerTag().withKey(tag.getName()).withValue(tag.getValue()));
            }
            VPCHelper.createServerTags(getInstanceId(), srvTags, getCloud());
        }
    }

    public List<ECSTag> getTags() {
        fetchLiveInstanceData(false);
        return Collections.unmodifiableList(tags);
    }

    public long getCreatedTime() {
        fetchLiveInstanceData(false);
        return createdTime;
    }

    public Secret getAdminPassword() {
        return Secret.fromString("");
    }

    public boolean isUseHTTPS() {
        return false;
    }

    public int getBootDelay() {
        return 0;
    }

    public boolean isSpecifyPassword() {
        return false;
    }

    public boolean isAllowSelfSignedCertificate() {
        return false;
    }

    void idleTimeout() {
        LOGGER.info("ECS instance idle time expired: " + getInstanceId());
        if (stopOnTerminate && !imgHasChanged()) {
            stop();
            return;
        }
        terminate();
    }

    private boolean imgHasChanged() {
        ServerDetail instance = getServerDetail();
        if (instance == null) {
            return false;
        }
        ECSTemplate tmp = getCloud().getTemplate(templateDescription);
        if (tmp == null) {
            return false;
        }
        String insImgId = instance.getImage().getId();
        String tmpImgId = tmp.getImgID();
        if (StringUtils.isEmpty(insImgId) || StringUtils.isEmpty(tmpImgId)) {
            return false;
        }
        return !insImgId.equals(tmpImgId);
    }

    private void stop() {
        try {
            VPCHelper.stopECSInstance(instanceId, getCloud());
            Computer computer = toComputer();
            if (computer != null) {
                computer.disconnect(null);
            }
        } catch (SdkException e) {
            LOGGER.log(Level.WARNING, "ECS instance idle time out stop and disconnected exception:" + e.getMessage());
        }
    }

    void launchTimeout() {
        LOGGER.info("ECS instance failed to launch: " + getInstanceId());
        terminate();
    }

    public long getLaunchTimeoutInMillis() {
        // this should be fine as long as launchTimeout remains an int type
        return launchTimeout * 1000L;
    }

    public static abstract class DescriptorImpl extends SlaveDescriptor {

        @Override
        public abstract @NotNull String getDisplayName();

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
