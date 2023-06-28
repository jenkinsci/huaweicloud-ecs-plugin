package io.jenkins.plugins.huaweicloud.model;

import hudson.slaves.NodeProvisioner;

import java.util.ArrayList;
import java.util.List;

public class ValidNodeContainer {
    private int excessWorkload;
    private List<NodeProvisioner.PlannedNode> nodes;

    public ValidNodeContainer() {
        this(0, new ArrayList<>());
    }

    public ValidNodeContainer(int excessWorkload, List<NodeProvisioner.PlannedNode> nodes) {
        this.excessWorkload = excessWorkload;
        this.nodes = nodes;
    }

    public int getExcessWorkload() {
        return excessWorkload;
    }

    public void setExcessWorkload(int excessWorkload) {
        this.excessWorkload = excessWorkload;
    }

    public List<NodeProvisioner.PlannedNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeProvisioner.PlannedNode> nodes) {
        this.nodes = nodes;
    }

    public void addSlave(NodeProvisioner.PlannedNode slave, int excessWorkload) {
        this.excessWorkload += excessWorkload;
        this.nodes.add(slave);
    }
}
