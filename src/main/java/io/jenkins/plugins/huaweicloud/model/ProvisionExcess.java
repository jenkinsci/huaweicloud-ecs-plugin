package io.jenkins.plugins.huaweicloud.model;

import hudson.model.Label;
import org.apache.commons.lang.StringUtils;


public class ProvisionExcess {
    Label label;
    int excessWorkLoad;

    public ProvisionExcess(Label label, int excessWorkLoad) {
        this.label = label;
        this.excessWorkLoad = excessWorkLoad;
    }

    public Label getLabel() {
        return label;
    }

    public void setLabel(Label label) {
        this.label = label;
    }

    public int getExcessWorkLoad() {
        return excessWorkLoad;
    }

    public void setExcessWorkLoad(int excessWorkLoad) {
        this.excessWorkLoad = excessWorkLoad;

    }

    public boolean equals(ProvisionExcess o) {
        if (o == null) {
            return false;
        }
        if (o.label == null && this.label == null) {
            return this.excessWorkLoad == o.excessWorkLoad;
        }
        if (o.label != null && this.label != null) {
            String oln = StringUtils.trimToEmpty(o.label.toString());
            String tln = StringUtils.trimToEmpty(this.label.toString());
            return oln.equals(tln) && this.excessWorkLoad == o.excessWorkLoad;
        }
        return false;
    }

    @Override
    public String toString() {
        String lbString = "";
        if (label != null) {
            lbString = label.toString();
        }
        return lbString + "-" + excessWorkLoad;
    }

}
