package io.jenkins.plugins.huaweicloud;

import hudson.model.AbstractDescribableImpl;

public abstract class ImageTypeData extends AbstractDescribableImpl<ImageTypeData> {
    public abstract boolean isDynamicType();
}
