package io.jenkins.plugins.huaweicloud.util;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class TimeUtils {
    public static long dateStrToLong(String dateStr) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        long lDate = 0;
        try {
            Date date = sdf.parse(dateStr);
            lDate = date.getTime();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return lDate;
    }
}
