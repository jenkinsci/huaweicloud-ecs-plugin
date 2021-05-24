package io.jenkins.plugins.huaweicloud.util;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class AssociateEIP {
    private String eipType;
    private String sizeStr;
    private int size;

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getSizeStr() {
        return sizeStr;
    }

    @DataBoundSetter
    public void setSizeStr(String sizeStr) {
        this.sizeStr = sizeStr;
        try {
            this.size = Integer.parseInt(sizeStr);
        } catch (Exception e) {
            this.size = 1;
        }
    }

    @DataBoundConstructor
    public AssociateEIP() {
    }

    public String getEipType() {
        return eipType;
    }

    @DataBoundSetter
    public void setEipType(String eipType) {
        this.eipType = eipType;
    }
}
