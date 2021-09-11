package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.ims.v2.ImsClient;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.util.FormValidation;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.model.Jenkins;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.logging.Logger;

public class DynamicImageData extends ImageTypeData {
    private final static Logger LOGGER = Logger.getLogger(DynamicImageData.class.getName());
    private final String imgTag;

    public String getImgTag() {
        return imgTag;
    }

    @Override
    public boolean isDynamicType() {
        return true;
    }

    @DataBoundConstructor
    public DynamicImageData(String imgTag) {
        this.imgTag = imgTag;
        this.readResolve();
    }

    protected Object readResolve() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ImageTypeData> {
        @NotNull
        @Override
        public String getDisplayName() {
            return "dynamic way";
        }

        public FormValidation doCheckImgTag(@QueryParameter String imgTag, @RelativePath("../..") @QueryParameter String region,
                                            @RelativePath("../..") @QueryParameter String credentialsId) {
            Jenkins.get().hasPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(imgTag) == null) {
                return FormValidation.error(Messages.TPL_CanNotGetImageByTag());
            }
            ImsClient client = VPC.createImsClient(region, credentialsId);
            try {
                String imgID = VPCHelper.getImageIDByTag(client, imgTag);
                LOGGER.info(imgID);
            } catch (Exception e) {
                LOGGER.info(e.getMessage());
                return FormValidation.error(Messages.TPL_CanNotGetImageByTag());
            }
            return FormValidation.ok();
        }
    }
}
