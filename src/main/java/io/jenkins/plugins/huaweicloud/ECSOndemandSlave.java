package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ECSOndemandSlave extends ECSAbstractSlave {
    private static final Logger LOGGER = Logger.getLogger(ECSOndemandSlave.class.getName());

    @DataBoundConstructor
    public ECSOndemandSlave(String name, String instanceId, String templateDescription,
                            int numExecutors, String labelString, Mode mode,
                            String remoteFS, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin,
                            String idleTerminationMinutes, List<ECSTag> tags, String cloudName, int launchTimeout,
                            String initScript, String tmpDir,boolean stopOnTerminate) throws Descriptor.FormException, IOException {
        super(name, instanceId, templateDescription, remoteFS,
                numExecutors, mode, labelString, nodeProperties,
                remoteAdmin, tags, cloudName, idleTerminationMinutes,
                new ECSUnixLauncher(), launchTimeout, initScript, tmpDir,new ECSRetentionStrategy(idleTerminationMinutes),stopOnTerminate);
    }


    @Override
    public void terminate() {
        if (terminateScheduled.getCount() == 0) {
            synchronized (terminateScheduled) {
                if (terminateScheduled.getCount() == 0) {
                    Computer.threadPoolForRemoting.submit(() -> {
                        try {
                            if (!isAlive(true)) {
                                LOGGER.info("ECS instance already terminated: " + getInstanceId());
                            } else {
                                VPCHelper.deleteServer(getInstanceId(), getCloud());
                            }
                            Jenkins.get().removeNode(this);
                            LOGGER.info("Removed EC2 instance from jenkins master: " + getInstanceId());
                        } catch (SdkException | IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to terminate EC2 instance: " + getInstanceId(), e);
                        } finally {
                            synchronized (terminateScheduled) {
                                terminateScheduled.countDown();
                            }
                        }
                    });
                    terminateScheduled.reset();
                }
            }
        }
    }

    @Override
    public Node reconfigure(final StaplerRequest req, JSONObject form) throws Descriptor.FormException {
        if (form == null) {
            return null;
        }
        if (!isAlive(true)) {
            LOGGER.info("EC2 instance terminated externally: " + getInstanceId());
            try {
                Jenkins.get().removeNode(this);
            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Attempt to reconfigure EC2 instance which has been externally terminated: "
                        + getInstanceId(), ioe);
            }

            return null;
        }

        return super.reconfigure(req, form);
    }

    @Override
    public String getECSType() {
        return Messages.ECSOnDemandSlave_OnDemand();
    }


    @Extension
    public static final class DescriptorImpl extends ECSAbstractSlave.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return Messages.ECSOnDemandSlave_HuaweiECS();
        }
    }
}
