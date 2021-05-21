package io.jenkins.plugins.huaweicloud.credentials;

import hudson.util.Secret;

public interface AccessKeyCredentials {
    Secret getAccessKey();

    Secret getSecretKey();
}
