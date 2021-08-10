package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import hudson.init.InitMilestone;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.RetentionStrategy;
import io.jenkins.plugins.huaweicloud.util.MinimumInstanceChecker;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.model.Jenkins;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ECSRetentionStrategy extends RetentionStrategy<ECSComputer> implements ExecutorListener {
    private static final Logger LOGGER = Logger.getLogger(ECSRetentionStrategy.class.getName());
    public static final boolean DISABLED = Boolean.getBoolean(ECSRetentionStrategy.class.getName() + ".disabled");
    private long nextCheckAfter = -1;
    private transient Clock clock;
    private final int idleTerminationMinutes;
    private transient ReentrantLock checkLock;
    private static final int STARTUP_TIME_DEFAULT_VALUE = 30;

    @DataBoundConstructor
    public ECSRetentionStrategy(String idleTerminationMinutes) {
        readResolve();
        if (idleTerminationMinutes == null || idleTerminationMinutes.trim().isEmpty()) {
            this.idleTerminationMinutes = 0;
        } else {
            int value = STARTUP_TIME_DEFAULT_VALUE;
            try {
                value = Integer.parseInt(idleTerminationMinutes);
            } catch (NumberFormatException nfe) {
                LOGGER.info("Malformed default idleTermination value: " + idleTerminationMinutes);
            }

            this.idleTerminationMinutes = value;
        }
    }

    ECSRetentionStrategy(String idleTerminationMinutes, Clock clock, long nextCheckAfter) {
        this(idleTerminationMinutes);
        this.clock = clock;
        this.nextCheckAfter = nextCheckAfter;
    }

    long getNextCheckAfter() {
        return this.nextCheckAfter;
    }

    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        clock = Clock.systemUTC();
        return this;
    }

    /**
     * Called when a new {@link ECSComputer} object is introduced (such as when Hudson started, or when
     * a new agent is added.)
     * <p>
     * When Jenkins has just started, we don't want to spin up all the instances, so we only start if
     * the EC2 instance is already running
     */
    @Override
    public void start(@NotNull ECSComputer c) {
        //Jenkins is in the process of starting up
        if (Jenkins.get().getInitLevel() != InitMilestone.COMPLETED) {
            String state = null;
            try {
                state = c.getStatus();
            } catch (SdkException | InterruptedException | NullPointerException e) {
                LOGGER.log(Level.FINE, "Error getting ECS instance state for " + c.getName(), e);
            }
            if (!"ACTIVE".equals(state)) {
                LOGGER.info("Ignoring start request for " + c.getName()
                        + " during Jenkins startup due to ECS instance state of " + state);
                return;
            }
        }
        LOGGER.info("Start requested for " + c.getName());
        c.connect(false);
    }

    // no registration since this retention strategy is used only for EC2 nodes
    // that we provision automatically.
    // @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public @NotNull String getDisplayName() {
            return "EC2";
        }
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
       /* TODO: we not impl limit the slave max total uses so think implement this logic
       ECSComputer computer = (ECSComputer) executor.getOwner();
        if (computer != null) {
            ECSAbstractSlave slaveNode = computer.getNode();
            if (slaveNode != null) {
                int maxTotalUses = slaveNode.maxTotalUses;
                if (maxTotalUses <= -1) {
                    LOGGER.fine("maxTotalUses set to unlimited (" + slaveNode.maxTotalUses + ") for agent " + slaveNode.getInstanceId());
                    return;
                } else if (maxTotalUses <= 1) {
                    LOGGER.info("maxTotalUses drained - suspending agent " + slaveNode.instanceId);
                    computer.setAcceptingTasks(false);
                } else {
                    slaveNode.maxTotalUses = slaveNode.maxTotalUses - 1;
                    LOGGER.info("Agent " + slaveNode.getInstanceId() + " has " + slaveNode.maxTotalUses + " builds left");
                }
            }
        }*/
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long l) {
        postJobAction(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long l, Throwable throwable) {
        postJobAction(executor);
    }

    private void postJobAction(Executor executor) {
        ECSComputer computer = (ECSComputer) executor.getOwner();
        ECSAbstractSlave slaveNode = computer.getNode();
        if (slaveNode != null) {
            // At this point, if agent is in suspended state and has 1 last executer running, it is safe to terminate.
            if (computer.countBusy() <= 1 && !computer.isAcceptingTasks()) {
                LOGGER.info("Agent " + slaveNode.getInstanceId() + " is terminated due to maxTotalUses ");
                slaveNode.terminate();
            }
        }
    }

    @Override
    public long check(@NotNull ECSComputer ecsComputer) {
        if (!checkLock.tryLock()) {
            return 1;
        } else {
            try {
                long currentTime = this.clock.millis();
                if (currentTime > nextCheckAfter) {
                    long intervalMinutes = internalCheck(ecsComputer);
                    nextCheckAfter = currentTime + TimeUnit.MINUTES.toMillis(intervalMinutes);
                    return intervalMinutes;
                } else {
                    return 1;
                }
            } finally {
                checkLock.unlock();
            }
        }
    }

    private long internalCheck(ECSComputer computer) {
        // If we've been told never to terminate, or node is null(deleted), no checks to perform
        if (idleTerminationMinutes == 0 || computer.getNode() == null) {
            return 1;
        }

        //If we have equal or less number of slaves than the template's minimum instance count, don't perform check.
        ECSTemplate slaveTemplate = computer.getSlaveTemplate();
        if (slaveTemplate != null) {
            long numberOfCurrentInstancesForTemplate = MinimumInstanceChecker.countCurrentNumberOfAgents(slaveTemplate);
            if (numberOfCurrentInstancesForTemplate > 0 && numberOfCurrentInstancesForTemplate <= slaveTemplate.getMinimumNumberOfInstances()) {
                // TODO:Check if we're in an active time-range for keeping minimum number of instances
                return 1;
            }
        }

        if (computer.isIdle() && !DISABLED) {
            final long uptime;
            String state;
            try {
                state = computer.getStatus();
                uptime = computer.getUptime();
            } catch (SdkException | InterruptedException e) {
                LOGGER.fine("Exception while checking host uptime for " + computer.getName()
                        + ", will retry next check. Exception: " + e);
                return 1;
            }
            if (VPCHelper.isTerminated(state) || (slaveTemplate != null && slaveTemplate.stopOnTerminate) && VPCHelper.isShutdown(state)) {
                if (computer.isOnline()) {
                    computer.disconnect(null);
                }
                return 1;
            }

            //on rare occasions, HWC may return fault instance which shows running in Huawei cloud console but can not be connected.
            //need terminate such fault instance.
            //an instance may also fail running user data scripts and need to be cleaned up.
            if (computer.isOffline()) {
                if (computer.isConnecting()) {
                    LOGGER.log(Level.FINE, "Computer {0} connecting and still offline, will check if the launch timeout has expired", computer.getInstanceId());
                }
                ECSAbstractSlave node = computer.getNode();
                if (Objects.isNull(node)) {
                    return 1;
                }
                long launchTimeout = node.getLaunchTimeoutInMillis();
                if (launchTimeout > 0 && uptime > launchTimeout) {
                    // Computer is offline and startup time has expired
                    LOGGER.info("Startup timeout of " + computer.getName() + " after "
                            + uptime +
                            " milliseconds (timeout: " + launchTimeout + " milliseconds), instance status: " + state);
                    node.launchTimeout();
                }
                return 1;
            } else {
                LOGGER.log(Level.FINE, "Computer {0} offline but not connecting, will check if it should be terminated because of the idle time configured", computer.getInstanceId());
            }
            final long idleMilliseconds = this.clock.millis() - computer.getIdleStartMilliseconds();
            if (idleTerminationMinutes > 0) {
                // TODO: really think about the right strategy here, see  JENKINS-23792
                if (idleMilliseconds > TimeUnit.MINUTES.toMillis(idleTerminationMinutes)) {

                    LOGGER.info("Idle timeout of " + computer.getName() + " after "
                            + TimeUnit.MILLISECONDS.toMinutes(idleMilliseconds) +
                            " idle minutes, instance status" + state);
                    ECSAbstractSlave slaveNode = computer.getNode();
                    if (slaveNode != null) {
                        slaveNode.idleTimeout();
                    }
                }
            }
        }

        return 1;
    }
}
