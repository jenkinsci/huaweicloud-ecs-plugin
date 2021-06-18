package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class ECSComputerLauncher extends ComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(ECSComputerLauncher.class.getName());

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener listener) {
        //when launch fail delete the ecs instance and  avoid repeated creation of offline nodes
        try {
            ECSComputer computer = (ECSComputer) slaveComputer;
            if (!launchScript(computer, listener)) {
                shutdownInstance(slaveComputer);
            }
        } catch (SdkException | IOException e) {
            LOGGER.log(Level.FINE, String.format("Terminating the ecs agent %s due a problem launching or connecting to it", slaveComputer.getName()), e);
            shutdownInstance(slaveComputer);
            e.printStackTrace(listener.error(e.getMessage()));
        } catch (InterruptedException e) {
            LOGGER.log(Level.FINE, String.format("Terminating the ecs agent %s due a problem launching or connecting to it", slaveComputer.getName()), e);
            Thread.currentThread().interrupt();
            shutdownInstance(slaveComputer);
            e.printStackTrace(listener.error(e.getMessage()));
        }
    }

    private void shutdownInstance(SlaveComputer slaveComputer) {
        if (slaveComputer.getNode() instanceof ECSAbstractSlave) {
            ECSAbstractSlave ec2AbstractSlave = (ECSAbstractSlave) slaveComputer.getNode();
            if (ec2AbstractSlave != null) {
                ec2AbstractSlave.terminate();
            }
        }
    }

    // Stage 2 of the launch. Called after the ECS instance comes up.
    protected abstract boolean launchScript(ECSComputer computer, TaskListener listener)
            throws SdkException, IOException, InterruptedException;

}
