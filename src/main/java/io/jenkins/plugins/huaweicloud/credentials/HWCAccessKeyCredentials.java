package io.jenkins.plugins.huaweicloud.credentials;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.cli.shaded.org.apache.commons.lang.StringUtils;
import io.jenkins.plugins.huaweicloud.Messages;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.logging.Logger;

public class HWCAccessKeyCredentials extends BaseStandardCredentials implements HWCCredentials, AccessKeyCredentials {
    private static final Logger LOGGER = Logger.getLogger(HWCAccessKeyCredentials.class.getName());
    private static final long serialVersionUID = -8124346392874829884L;
    private final Secret accessKey;
    private final Secret secretKey;

    @DataBoundConstructor
    public HWCAccessKeyCredentials(@CheckForNull CredentialsScope scope, @CheckForNull String id, @CheckForNull String accessKey, @CheckForNull String secretKey, @CheckForNull String description) {
        super(scope, id, description);
        this.accessKey = Secret.fromString(accessKey);
        this.secretKey = Secret.fromString(secretKey);
    }

    @Override
    public Secret getAccessKey() {
        return this.accessKey;
    }

    @Override
    public Secret getSecretKey() {
        return this.secretKey;
    }

    @Override
    public String getMessage() {
        return Messages.HuaweiECSCloud_Credentials_Name();
    }

    public String getDisplayName() {
        return getId();
    }

    @Extension
    public static class DescriptorImpl extends CredentialsDescriptor {

        @Override
        public @NotNull String getDisplayName() {
            return Messages.HuaweiECSCloud_Credentials_Name();
        }

        public FormValidation doCheckAccessKey(@QueryParameter final String accessKey) {
            if (StringUtils.isBlank(accessKey)) {
                return FormValidation.error(Messages.HuaweiECSCloud_ErrorConfig());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckSecretKey(@QueryParameter final String secretKey) {
            if (StringUtils.isBlank(secretKey)) {
                return FormValidation.error(Messages.HuaweiECSCloud_ErrorConfig());
            }
            return FormValidation.ok();
        }

    }
}
