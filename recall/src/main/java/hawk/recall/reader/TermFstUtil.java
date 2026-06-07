package hawk.recall.reader;

public final class TermFstUtil {

    private TermFstUtil() {
    }

    public static String termKey(String field, String term) {
        return field.concat(":").concat(term);
    }

    public static String[] toCharArray(String value) {
        int len = value.length();
        String[] ret = new String[len];
        for (int i = 0; i < len; i++) {
            ret[i] = String.valueOf(value.charAt(i));
        }
        return ret;
    }
}
