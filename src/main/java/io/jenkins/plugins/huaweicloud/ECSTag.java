package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.ecs.v2.model.ServerTag;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class ECSTag extends AbstractDescribableImpl<ECSTag> {
    private final String name;
    private final String value;

    public static final String TAG_NAME_JENKINS_SLAVE_TYPE = "jenkins_slave_type";
    public static final String TAG_NAME_JENKINS_SERVER_URL = "jenkins_server_url";

    @DataBoundConstructor
    public ECSTag(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public static List<ECSTag> formInstanceTags(List<ServerTag> serverTags) {
        if (null == serverTags)
            return null;
        LinkedList<ECSTag> result = new LinkedList<>();
        for (ServerTag tag : serverTags) {
            result.add(new ECSTag(tag.getKey(), tag.getValue()));
        }
        return result;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "ECSTag: " + name + "->" + value;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null)
            return false;
        if (this.getClass() != o.getClass())
            return false;

        ECSTag other = (ECSTag) o;
        if ((name == null && other.name != null) || (name != null && !name.equals(other.name)))
            return false;
        if ((value == null && other.value != null) || (value != null && !value.equals(other.value)))
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ECSTag> {
        @Override
        public String getDisplayName() {
            return "";
        }

    }
}
