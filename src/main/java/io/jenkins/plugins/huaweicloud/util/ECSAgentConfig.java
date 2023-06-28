package io.jenkins.plugins.huaweicloud.util;

import hudson.model.Node;
import hudson.slaves.NodeProperty;
import io.jenkins.plugins.huaweicloud.ECSTag;

import java.util.List;

public abstract class ECSAgentConfig {
    final String name;
    final String description;
    final String remoteFS;
    final int numExecutors;
    final String labelString;
    final Node.Mode mode;
    final String initScript;
    final String tmpDir;
    final List<? extends NodeProperty<?>> nodeProperties;
    final String remoteAdmin;
    final List<ECSTag> tags;
    final String cloudName;
    final String idleTerminationMinutes;
    final String offlineTimeout;
    final int launchTimeout;

    private ECSAgentConfig(Builder<? extends Builder, ? extends ECSAgentConfig> builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.remoteFS = builder.remoteFS;
        this.numExecutors = builder.numExecutors;
        this.labelString = builder.labelString;
        this.mode = builder.mode;
        this.initScript = builder.initScript;
        this.tmpDir = builder.tmpDir;
        this.nodeProperties = builder.nodeProperties;
        this.remoteAdmin = builder.remoteAdmin;
        this.idleTerminationMinutes = builder.idleTerminationMinutes;
        this.offlineTimeout = builder.offlineTimeout;
        this.tags = builder.tags;
        this.cloudName = builder.cloudName;
        this.launchTimeout = builder.launchTimeout;
    }

    public static class OnDemand extends ECSAgentConfig {

        final String instanceId;
        final boolean stopOnTerminate;

        private OnDemand(OnDemandBuilder builder) {
            super(builder);
            this.instanceId = builder.getInstanceId();
            this.stopOnTerminate = builder.isStopOnTerminate();
        }
    }

    private static abstract class Builder<B extends Builder<B, C>, C extends ECSAgentConfig> {
        private String name;
        private String description;
        private String remoteFS;
        private int numExecutors;
        private String labelString;
        private Node.Mode mode;
        private String initScript;
        private String tmpDir;
        private List<? extends NodeProperty<?>> nodeProperties;
        private String remoteAdmin;
        private String idleTerminationMinutes;
        private String offlineTimeout;
        private List<ECSTag> tags;
        private String cloudName;
        private int launchTimeout;


        public B withName(String name) {
            this.name = name;
            return self();
        }

        public B withDescription(String description) {
            this.description = description;
            return self();
        }

        public B withRemoteFS(String remoteFS) {
            this.remoteFS = remoteFS;
            return self();
        }

        public B withNumExecutors(int numExecutors) {
            this.numExecutors = numExecutors;
            return self();
        }

        public B withLabelString(String labelString) {
            this.labelString = labelString;
            return self();
        }

        public B withMode(Node.Mode mode) {
            this.mode = mode;
            return self();
        }

        public B withNodeProperties(List<? extends NodeProperty<?>> nodeProperties) {
            this.nodeProperties = nodeProperties;
            return self();
        }

        public List<? extends NodeProperty<?>> getNodeProperties() {
            return nodeProperties;
        }

        public B withRemoteAdmin(String remoteAdmin) {
            this.remoteAdmin = remoteAdmin;
            return self();
        }

        public B withTags(List<ECSTag> tags) {
            this.tags = tags;
            return self();
        }

        public B withLaunchTimeout(int launchTimeout) {
            this.launchTimeout = launchTimeout;
            return self();
        }

        public B withIdleTerminationMinutes(String idleTerminationMinutes) {
            this.idleTerminationMinutes = idleTerminationMinutes;
            return self();
        }

        public B withOfflineTimeout(String offlineTimeout) {
            this.offlineTimeout = offlineTimeout;
            return self();
        }

        public B withCloudName(String cloudName) {
            this.cloudName = cloudName;
            return self();
        }

        public B withInitScript(String initScript) {
            this.initScript = initScript;
            return self();
        }

        public B withTmpDir(String tmpDir) {
            this.tmpDir = tmpDir;
            return self();
        }

        protected abstract B self();

        public abstract C build();
    }

    public static class OnDemandBuilder extends Builder<OnDemandBuilder, OnDemand> {

        private String instanceId;
        private boolean stopOnTerminate;

        public String getInstanceId() {
            return instanceId;
        }

        public OnDemandBuilder withInstanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public OnDemandBuilder withStopOnTerminate(boolean stopOnTerminate) {
            this.stopOnTerminate = stopOnTerminate;
            return this;
        }

        public boolean isStopOnTerminate() {
            return stopOnTerminate;
        }

        @Override
        protected OnDemandBuilder self() {
            return this;
        }

        @Override
        public OnDemand build() {
            return new OnDemand(this);
        }
    }
}
