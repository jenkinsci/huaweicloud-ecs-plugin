package io.jenkins.plugins.huaweicloud.util;

import hudson.Extension;
import hudson.model.Descriptor;
import io.jenkins.plugins.huaweicloud.ECSOndemandSlave;

import java.io.IOException;

@Extension
public class ECSAgentFactoryImpl implements ECSAgentFactory {
    @Override
    public ECSOndemandSlave createOnDemandAgent(ECSAgentConfig.OnDemand config) throws Descriptor.FormException, IOException {
        return new ECSOndemandSlave(config.name, config.instanceId, config.description, config.numExecutors,
                config.labelString, config.mode, config.remoteFS, config.nodeProperties, config.remoteAdmin,
                config.idleTerminationMinutes, config.tags, config.cloudName, config.launchTimeout, config.initScript,
                config.tmpDir, config.stopOnTerminate, config.offlineTimeout);
    }
}
