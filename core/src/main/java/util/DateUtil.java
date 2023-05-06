package util;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DateUtil {

    public static String getDateStr(){
        Date date = new Date();
        Format formatter = new SimpleDateFormat("yyyyMMdd");
        String dateStr = formatter.format(date);
        return dateStr;
    }
}
