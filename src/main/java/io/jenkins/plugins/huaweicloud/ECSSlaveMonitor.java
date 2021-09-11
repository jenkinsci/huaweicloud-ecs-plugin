package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import io.jenkins.plugins.huaweicloud.util.MinimumInstanceChecker;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class ECSSlaveMonitor extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(ECSSlaveMonitor.class.getName());

    private final Long recurrencePeriod;

    public ECSSlaveMonitor() {
        super("ECS Slave monitor");
        recurrencePeriod = Long.getLong("jenkins.hwc.checkSlavePeriod", TimeUnit.MINUTES.toMillis(2));
        LOGGER.log(Level.FINE, "huaweicloud ECS check slave period is {0}ms", recurrencePeriod);
    }

    @Override
    protected void execute(TaskListener taskListener) throws IOException, InterruptedException {
        LOGGER.log(Level.INFO, "huaweicloud ECS check slave period at {0}ms", System.currentTimeMillis());
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof ECSAbstractSlave) {
                ECSAbstractSlave ecsSlave = (ECSAbstractSlave) node;
                if (removeDeadNodes(ecsSlave)) {
                    continue;
                }
                checkOfflineNode(ecsSlave);
            }
        }
        MinimumInstanceChecker.checkForMinimumInstances();
    }

    private void checkOfflineNode(ECSAbstractSlave ecsSlave) {
        Computer computer = ecsSlave.toComputer();
        if (!(computer instanceof ECSComputer)) {
            return;
        }
        ECSComputer ecsComputer = (ECSComputer) computer;
        if (ecsComputer.isConnecting() || !ecsComputer.isOffline()) {
            return;
        }
        try {
            final String state = ecsComputer.getStatus();
            final long uptime = ecsComputer.getUptime();
            if (VPCHelper.isRunning(state)) {
                LOGGER.info("node is offline but ecs instance is running delete this node");
                VPCHelper.stopECSInstance(ecsSlave.getInstanceId(), ecsSlave.getCloud());
                return;
            }
            if (VPCHelper.isShutdown(state)) {
                long timoutMills = ecsSlave.getOfflineTimeoutMills();
                if (timoutMills == 0) {
                    return;
                }
                if (uptime > timoutMills) {
                    LOGGER.info("node is offline timeout by config delete this node");
                    ecsSlave.terminate();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return recurrencePeriod;
    }

    private boolean removeDeadNodes(ECSAbstractSlave ecsSlave) {
        boolean removed = false;
        try {
            if (!ecsSlave.isAlive(true)) {
                LOGGER.info("ECS instance is dead:" + ecsSlave.getInstanceId());
                ecsSlave.terminate();
                removed = true;
            }
        } catch (Exception e) {
            LOGGER.info("EC2 instance is dead and failed to terminate: " + ecsSlave.getInstanceId());
            removed = removeNode(ecsSlave);
        }
        return removed;
    }

    private boolean removeNode(ECSAbstractSlave ecsSlave) {
        try {
            Jenkins.get().removeNode(ecsSlave);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to remove node: " + ecsSlave.getInstanceId());
            return false;
        }
    }
}
