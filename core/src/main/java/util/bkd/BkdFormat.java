package util.bkd;

public final class BkdFormat {

    public static final int MAGIC = 0x424B4431;

    public static final int FORMAT_VERSION = 1;

    public static final byte NODE_INNER = 0;

    public static final byte NODE_LEAF = 1;

    public static final int HEADER_SIZE = 4 + 4 + 4 + 8;

    private BkdFormat() {
    }
}
