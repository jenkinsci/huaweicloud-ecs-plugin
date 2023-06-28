package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.ecs.v2.model.ServerDetail;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Node;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.huaweicloud.util.TimeUtils;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;

import java.io.IOException;

public class ECSComputer extends SlaveComputer {

    /**
     * Cached description of this ECS instance. Lazily fetched.
     */
    private volatile ServerDetail ecsInstanceDescription;

    public ECSComputer(ECSAbstractSlave slave) {
        super(slave);
    }

    @Override
    public ECSAbstractSlave getNode() {
        return (ECSAbstractSlave) super.getNode();
    }

    @CheckForNull
    public String getInstanceId() {
        ECSAbstractSlave node = getNode();
        return node == null ? null : node.getInstanceId();
    }

    public ServerDetail getEcsInstanceDescription() {
        return ecsInstanceDescription;
    }

    public String getEc2Type() {
        ECSAbstractSlave node = getNode();
        return node == null ? null : node.getECSType();
    }

    public String getSpotInstanceRequestId() {
        return "";
    }

    public VPC getCloud() {
        ECSAbstractSlave node = getNode();
        return node == null ? null : node.getCloud();
    }

    @CheckForNull
    public ECSTemplate getSlaveTemplate() {
        ECSAbstractSlave node = getNode();
        if (node != null) {
            return node.getCloud().getTemplate(node.templateDescription);
        }
        return null;
    }

    /**
     * Obtains the instance state description in ECS.
     *
     * <p>
     * This method returns a cached state, so it's not suitable to check {@link ServerDetail#getStatus()} from the returned
     * instance (but all the other fields are valid as it won't change.)
     * <p>
     * The cache can be flushed using {@link #updateInstanceDescription()}
     */
    public ServerDetail describeInstance() throws SdkException, InterruptedException {
        if (ecsInstanceDescription == null)
            ecsInstanceDescription = VPCHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        return ecsInstanceDescription;
    }

    /**
     * This will flush any cached description held by {@link #describeInstance()}.
     */
    public ServerDetail updateInstanceDescription() throws SdkException, InterruptedException {
        return ecsInstanceDescription = VPCHelper.getInstanceWithRetry(getInstanceId(), getCloud());
    }

    public String getStatus() throws SdkException, InterruptedException {
        ecsInstanceDescription = VPCHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        return ecsInstanceDescription.getStatus();
    }

    /**
     * Number of milli-secs since the instance was started.
     */
    public long getUptime() throws SdkException, InterruptedException {
        return System.currentTimeMillis() - TimeUtils.dateStrToLong(describeInstance().getUpdated());
    }

    /**
     * Returns uptime in the human readable form.
     */
    public String getUptimeString() throws SdkException, InterruptedException {
        return Util.getTimeSpanString(getUptime());
    }

    /**
     * When the slave is deleted, terminate the instance.
     */
    @Override
    public HttpResponse doDoDelete() throws IOException {
        checkPermission(DELETE);
        ECSAbstractSlave node = getNode();
        if (node != null)
            node.terminate();
        return new HttpRedirect("..");
    }

    /**
     * What username to use to run root-like commands
     *
     * @return remote admin or {@code null} if the associated {@link Node} is {@code null}
     */
    @CheckForNull
    public String getRemoteAdmin() {
        ECSAbstractSlave node = getNode();
        return node == null ? null : node.getRemoteAdmin();
    }

    public int getSshPort() {
        ECSAbstractSlave node = getNode();
        return node == null ? 22 : node.getSshPort();
    }

    public String getRootCommandPrefix() {
        ECSAbstractSlave node = getNode();
        return node == null ? "" : node.getRootCommandPrefix();
    }

    public String getSlaveCommandPrefix() {
        ECSAbstractSlave node = getNode();
        return node == null ? "" : node.getSlaveCommandPrefix();
    }

    public String getSlaveCommandSuffix() {
        ECSAbstractSlave node = getNode();
        return node == null ? "" : node.getSlaveCommandSuffix();
    }

    public void onConnected() {
        ECSAbstractSlave node = getNode();
        if (node != null) {
            node.onConnected();
        }
    }
}
