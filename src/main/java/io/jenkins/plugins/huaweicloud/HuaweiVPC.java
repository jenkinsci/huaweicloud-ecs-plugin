package io.jenkins.plugins.huaweicloud;

import hudson.Extension;
import hudson.model.Failure;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class HuaweiVPC extends VPC {

    public static final String CLOUD_ID_PREFIX = "ecs-";
    private boolean noDelayProvisioning;

    @DataBoundConstructor
    public HuaweiVPC(String cloudName, String credentialsId, String sshKeysCredentialsId, String instanceCapStr,
                     String vpcID, List<? extends ECSTemplate> templates) {
        super(createCloudId(cloudName), credentialsId, sshKeysCredentialsId, instanceCapStr, vpcID, templates);
    }

    public boolean isNoDelayProvisioning() {
        return noDelayProvisioning;
    }

    @DataBoundSetter
    public void setNoDelayProvisioning(boolean noDelayProvisioning) {
        this.noDelayProvisioning = noDelayProvisioning;
    }

    @Override
    public URL getEc2EndpointUrl() throws IOException {
        return null;
    }

    private static String createCloudId(String cloudName) {
        return CLOUD_ID_PREFIX + cloudName.trim();
    }

    public String getCloudName() {
        return this.name.substring(CLOUD_ID_PREFIX.length());
    }

    @Override
    public String getDisplayName() {
        return getCloudName();
    }

    @Extension
    public static class DescriptorImpl extends VPC.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return "Huawei VPC";
        }

        public FormValidation doCheckCloudName(@QueryParameter String value) {
            try {
                Jenkins.checkGoodName(value);
            } catch (Failure e) {
                return FormValidation.error(e.getMessage());
            }

            String cloudId = createCloudId(value);
            int found = 0;
            for (Cloud c : Jenkins.get().clouds) {
                if (c.name.equals(cloudId)) {
                    found++;
                }
            }
            if (found > 1) {
                return FormValidation.error(Messages.HuaweiECSCloud_NonUniqName());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRegion(@QueryParameter String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.error(Messages.HuaweiECSCloud_MalformedRegion());
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public ListBoxModel doFillRegionItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Messages.UI_NoSelect(), "");
            model.add("Africa-Johannesburg(非洲-约翰内斯堡)", "af-south-1");
            model.add("Asia Pacific-Hong Kong(亚太-香港)", "af-south-1");
            model.add("Asia Pacific-Bangkok(亚太-曼谷)", "ap-southeast-2");
            model.add("Asia Pacific-Singapore(亚太-新加坡)", "ap-southeast-3");
            model.add("East China-Shanghai II(华东-上海二)", "cn-east-2");
            model.add("East China-Shanghai I(华东-上海一)", "cn-east-3");
            model.add("North China-Beijing I(华北-北京一)", "cn-north-1");
            model.add("North China-Beijing IIII(华北-北京四)", "cn-north-4");
            model.add("South China-Guangzhou(华南-广州)", "cn-south-1");
            model.add("Southwest-Guiyang I(西南-贵阳一)", "cn-southwest-2");
            model.add("Russia-Moscow II(俄罗斯-莫斯科二)", "ru-northwest-2");
            return model;
        }

        @RequirePOST
        public FormValidation doTestConnection(@QueryParameter String region, @QueryParameter String credentialsId, @QueryParameter String sshKeysCredentialsId) {
            if (StringUtils.isBlank(region) || StringUtils.isBlank(credentialsId) || StringUtils.isBlank(sshKeysCredentialsId)) {
                return FormValidation.error(Messages.HuaweiECSCloud_ErrorConfig());
            }
            return super.doTestConnection(region, credentialsId, sshKeysCredentialsId);
        }
    }
}
