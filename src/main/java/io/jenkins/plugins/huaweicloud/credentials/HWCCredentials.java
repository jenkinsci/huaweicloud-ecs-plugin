package io.jenkins.plugins.huaweicloud.credentials;

import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.NameWith;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.Util;
import org.jetbrains.annotations.NotNull;

@NameWith(HWCCredentials.NameProvider.class)
public interface HWCCredentials extends StandardCredentials {
    String getMessage();

    public static class NameProvider extends CredentialsNameProvider<HWCCredentials> {
        public NameProvider() {
        }

        public @NotNull String getName(HWCCredentials c) {
            String description = Util.fixEmptyAndTrim(c.getDescription());
            return c.getMessage() + (description != null ? " (" + description + ")" : "");
        }
    }
}
