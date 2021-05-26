package io.jenkins.plugins.huaweicloud.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Queue;
import io.jenkins.plugins.huaweicloud.ECSComputer;
import io.jenkins.plugins.huaweicloud.ECSTemplate;
import io.jenkins.plugins.huaweicloud.VPC;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

@Restricted(NoExternalUse.class)
public class MinimumInstanceChecker {
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Needs to be overridden from tests")
    public static Clock clock = Clock.systemDefaultZone();

    private static Stream<Computer> agentsForTemplate(@Nonnull ECSTemplate agentTemplate) {
        return (Stream<Computer>) Arrays.stream(Jenkins.get().getComputers())
                .filter(computer -> computer instanceof ECSComputer)
                .filter(computer -> {
                    ECSTemplate computerTemplate = ((ECSComputer) computer).getSlaveTemplate();
                    return computerTemplate != null
                            && Objects.equals(computerTemplate.description, agentTemplate.description);
                });
    }

    public static int countCurrentNumberOfAgents(@Nonnull ECSTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate).count();
    }

    public static int countCurrentNumberOfSpareAgents(@Nonnull ECSTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate)
                .filter(computer -> computer.countBusy() == 0)
                .filter(Computer::isOnline)
                .count();
    }

    public static int countCurrentNumberOfProvisioningAgents(@Nonnull ECSTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate)
                .filter(computer -> computer.countBusy() == 0)
                .filter(Computer::isOffline)
                .filter(Computer::isConnecting)
                .count();
    }

    /*
        Get the number of queued builds that match an AMI (agentTemplate)
    */
    public static int countQueueItemsForAgentTemplate(@Nonnull ECSTemplate agentTemplate) {
        return (int)
                Queue
                        .getInstance()
                        .getBuildableItems()
                        .stream()
                        .map((Function<Queue.Item, Label>) Queue.Item::getAssignedLabel)
                        .filter(Objects::nonNull)
                        .filter((Label label) -> label.matches(agentTemplate.getLabelSet()))
                        .count();
    }

    public static void checkForMinimumInstances() {
        Jenkins.get().clouds.stream()
                .filter(cloud -> cloud instanceof VPC)
                .map(cloud -> (VPC) cloud)
                .forEach(cloud -> {
                    cloud.getTemplates().forEach(agentTemplate -> {
                        /*TODO: Minimum instances now have a time range, check to see  if we are within that time range and return early if not.
                        if (! minimumInstancesActive(agentTemplate.getMinimumNumberOfInstancesTimeRangeConfig())) {
                            return;
                        }*/
                        int requiredMinAgents = agentTemplate.getMinimumNumberOfInstances();
                        //int requiredMinSpareAgents = agentTemplate.getMinimumNumberOfSpareInstances();
                        int requiredMinSpareAgents = 0;
                        int currentNumberOfAgentsForTemplate = countCurrentNumberOfAgents(agentTemplate);
                        int currentNumberOfSpareAgentsForTemplate = countCurrentNumberOfSpareAgents(agentTemplate);
                        int currentNumberOfProvisioningAgentsForTemplate = countCurrentNumberOfProvisioningAgents(agentTemplate);
                        int currentBuildsWaitingForTemplate = countQueueItemsForAgentTemplate(agentTemplate);
                        int provisionForMinAgents = 0;
                        int provisionForMinSpareAgents = 0;

                        // Check if we need to provision any agents because we  don't have the minimum number of agents
                        provisionForMinAgents = requiredMinAgents - currentNumberOfAgentsForTemplate;
                        if (provisionForMinAgents < 0) {
                            provisionForMinAgents = 0;
                        }

                        // Check if we need to provision any agents because we don't have the minimum number of spare agents.
                        // Don't double provision if minAgents and minSpareAgents are set.
                        provisionForMinSpareAgents = (requiredMinSpareAgents + currentBuildsWaitingForTemplate) - (currentNumberOfSpareAgentsForTemplate +
                                provisionForMinAgents + currentNumberOfProvisioningAgentsForTemplate);
                        if (provisionForMinSpareAgents < 0) {
                            provisionForMinSpareAgents = 0;
                        }

                        int numberToProvision = provisionForMinAgents + provisionForMinSpareAgents;
                        if (numberToProvision > 0) {
                            cloud.provision(agentTemplate, numberToProvision);
                        }
                    });
                });
    }

}
