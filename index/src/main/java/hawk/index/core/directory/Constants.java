package hawk.index.core.directory;

public class Constants {

    private Constants() {}//can't construct

    /** The value of <tt>System.getProperty("java.version")</tt>. **/
    public static final String JAVA_VERSION = System.getProperty("java.version");

    /** The value of <tt>System.getProperty("os.name")</tt>. **/
    public static final String OS_NAME = System.getProperty("os.name");

    /** True iff running on a 64bit JVM */
    public static final boolean JRE_IS_64BIT;

    /** architecture, 64 or 32 */
    public static final String OS_ARCH = System.getProperty("os.arch");

    static {
        boolean is64Bit = false;
        String datamodel = null;
        try {
            datamodel = System.getProperty("sun.arch.data.model");
            if (datamodel != null) {
                is64Bit = datamodel.contains("64");
            }
        } catch (SecurityException ex) {}
        if (datamodel == null) {
            if (OS_ARCH != null && OS_ARCH.contains("64")) {
                is64Bit = true;
            } else {
                is64Bit = false;
            }
        }
        JRE_IS_64BIT = is64Bit;
    }

}
