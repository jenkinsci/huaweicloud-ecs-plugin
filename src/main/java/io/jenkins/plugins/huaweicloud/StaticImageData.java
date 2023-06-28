package io.jenkins.plugins.huaweicloud;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class StaticImageData extends ImageTypeData {
    private final String imageId;


    @Override
    public boolean isDynamicType() {
        return false;
    }

    @DataBoundConstructor
    public StaticImageData(String imageId) {
        this.imageId = imageId;
        this.readResolve();
    }

    protected Object readResolve() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    public String getImageId() {
        return imageId;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ImageTypeData> {
        @NotNull
        @Override
        public String getDisplayName() {
            return "static way";
        }

        public FormValidation doCheckImageId(@QueryParameter String imageId) {
            Jenkins.get().hasPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(imageId) == null) {
                return FormValidation.error(Messages.TPL_NoSetImageID());
            }
            return FormValidation.ok();
        }

    }
}
