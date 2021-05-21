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
       try {
           ECSComputer computer = (ECSComputer) slaveComputer;
           launchScript(computer,listener);
       }catch (SdkException | IOException e) {
           if (slaveComputer.getNode() instanceof  ECSAbstractSlave) {
               LOGGER.log(Level.FINE, String.format("Terminating the ecs agent %s due a problem launching or connecting to it", slaveComputer.getName()), e);
               ECSAbstractSlave ec2AbstractSlave = (ECSAbstractSlave) slaveComputer.getNode();
               if (ec2AbstractSlave != null) {
                   ec2AbstractSlave.terminate();
               }
           }
           e.printStackTrace(listener.error(e.getMessage()));
       }catch (InterruptedException e){
           Thread.currentThread().interrupt();
           e.printStackTrace(listener.error(e.getMessage()));
           if (slaveComputer.getNode() instanceof  ECSAbstractSlave) {
               LOGGER.log(Level.FINE, String.format("Terminating the ecs agent %s due a problem launching or connecting to it", slaveComputer.getName()), e);
               ECSAbstractSlave ec2AbstractSlave = (ECSAbstractSlave) slaveComputer.getNode();
               if (ec2AbstractSlave != null) {
                   ec2AbstractSlave.terminate();
               }
           }
       }
    }

    /**
     * Stage 2 of the launch. Called after the EC2 instance comes up.
     */
    protected abstract void launchScript(ECSComputer computer, TaskListener listener)
            throws SdkException, IOException, InterruptedException;

}
