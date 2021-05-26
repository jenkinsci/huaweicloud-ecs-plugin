package io.jenkins.plugins.huaweicloud;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class ECSNic extends AbstractDescribableImpl<ECSNic> {
    public final String subnetId;
    public final boolean ipv6;

    @DataBoundConstructor
    public ECSNic(String subnetId, boolean ipv6) {
        this.subnetId = subnetId;
        this.ipv6 = ipv6;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public boolean isIpv6() {
        return ipv6;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ECSNic> {
        @Override
        public @NotNull String getDisplayName() {
            return "";
        }

        public FormValidation doCheckSubnetId(@QueryParameter String subnetId) {
            if (StringUtils.isEmpty(subnetId)) {
                return FormValidation.error(Messages.TPL_NoSetSubnetID());
            }
            return FormValidation.ok();
        }
    }


}
