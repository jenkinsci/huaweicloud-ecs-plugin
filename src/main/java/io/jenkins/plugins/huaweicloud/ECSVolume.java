package io.jenkins.plugins.huaweicloud;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ECSVolume extends AbstractDescribableImpl<ECSVolume> {
    public final VolumeType volumeType;
    public final String volumeSize;
    public int size;

    @DataBoundConstructor
    public ECSVolume(VolumeType volumeType, String volumeSize) {
        this.volumeType = volumeType != null ? volumeType : VolumeType.SATA;
        this.volumeSize = volumeSize;
        if (null != volumeSize && !volumeSize.isEmpty()) {
            this.size = Integer.parseInt(volumeSize);
        }

    }

    public int getSize() {
        return size;
    }

    public VolumeType getVolumeType() {
        return volumeType;
    }

    public String getSizeStr() {
        return volumeSize;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ECSVolume> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "";
        }

        public FormValidation doCheckVolumeSize(@QueryParameter String value) {
            if (StringUtils.isEmpty(value)) {
                return FormValidation.error(Messages.TPL_NoVolumeSize());
            }
            try {
                int size = Integer.parseInt(value);
                if (size < 10 || size > 32768) {
                    return FormValidation.error("Illegal input");
                }
            } catch (Exception e) {
                return FormValidation.error("Illegal input");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public ListBoxModel doFillVolumeTypeItems(@QueryParameter String volumeType) {
            return Stream.of(VolumeType.values())
                    .map(v -> {
                        if (v.name().equals(volumeType)) {
                            return new ListBoxModel.Option(v.name(), v.name(), true);
                        } else {
                            return new ListBoxModel.Option(v.name(), v.name(), false);
                        }
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }
    }

}
