package io.jenkins.plugins.huaweicloud.util;

import hudson.model.Descriptor;
import io.jenkins.plugins.huaweicloud.ECSOndemandSlave;
import jenkins.model.Jenkins;

import java.io.IOException;

public interface ECSAgentFactory {
    static ECSAgentFactory getInstance() {
        ECSAgentFactory instance = null;
        for (ECSAgentFactory implementation : Jenkins.get().getExtensionList(ECSAgentFactory.class)) {
            if (instance != null) {
                throw new IllegalStateException("Multiple implementations of " + ECSAgentFactory.class.getName()
                        + " found. If overriding, please consider using ExtensionFilter");
            }
            instance = implementation;
        }
        return instance;
    }

    ECSOndemandSlave createOnDemandAgent(ECSAgentConfig.OnDemand config) throws Descriptor.FormException, IOException;
}
