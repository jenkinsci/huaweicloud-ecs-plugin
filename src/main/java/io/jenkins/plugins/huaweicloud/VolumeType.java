package io.jenkins.plugins.huaweicloud;

public enum VolumeType {
    SATA("SATA"), SAS("SAS"), SSD("SSD"), GPSSD("GPSSD"), co_p1("co-p1"), uh_l1("uh-l1");

    private final String value;

    VolumeType(String value) {
        this.value = value;
    }

    public String toString() {
        return this.value;
    }

    public static VolumeType fromValue(String value) {
        if (value != null && !"".equals(value)) {
            VolumeType[] var1 = values();
            int var2 = var1.length;

            for (int var3 = 0; var3 < var2; ++var3) {
                VolumeType enumEntry = var1[var3];
                if (enumEntry.toString().equals(value)) {
                    return enumEntry;
                }
            }

            throw new IllegalArgumentException("Cannot create enum from " + value + " value!");
        } else {
            throw new IllegalArgumentException("Value cannot be null or empty!");
        }
    }


}
