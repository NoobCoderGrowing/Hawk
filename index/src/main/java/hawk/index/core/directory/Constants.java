package hawk.index.core.directory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

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

    public static final int PROCESSOR_NUM = getProcessorNum();

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

    public static int getProcessorNum(){
        String command = "";
        if(OS_NAME.contains("mac")){//mac
            command = "sysctl -n machdep.cpu.core_count";
        }else if(OS_NAME.contains("nix") || OS_NAME.contains("nux") || OS_NAME.contains("aix")){//unix
            command = "lscpu";
        }else if(OS_NAME.contains("win")){//windows
            command = "cmd /C WMIC CPU Get /Format:List";
        }
        Process process = null;
        int numberOfCores = 0;
        int sockets = 0;
        try {
            if(OS_NAME.contains("mac")){
                String[] cmd = { "/bin/sh", "-c", command};
                process = Runtime.getRuntime().exec(cmd);
            }else{
                process = Runtime.getRuntime().exec(command);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
        String line;

        try {
            while ((line = reader.readLine()) != null) {
                if(OS_NAME.contains("mac")){
                    numberOfCores = line.length() > 0 ? Integer.parseInt(line) : 0;
                }else if (OS_NAME.contains("nix") || OS_NAME.contains("nux") || OS_NAME.contains("aix")) {
                    if (line.contains("Core(s) per socket:")) {
                        numberOfCores = Integer.parseInt(line.split("\\s+")[line.split("\\s+").length - 1]);
                    }
                    if(line.contains("Socket(s):")){
                        sockets = Integer.parseInt(line.split("\\s+")[line.split("\\s+").length - 1]);
                    }
                } else if (OS_NAME.contains("win")) {
                    if (line.contains("NumberOfCores")) {
                        numberOfCores = Integer.parseInt(line.split("=")[1]);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(OS_NAME.contains("nix") || OS_NAME.contains("nux") || OS_NAME.contains("aix")){
            return numberOfCores * sockets;
        } else {
            return numberOfCores;
        }
    }

}
