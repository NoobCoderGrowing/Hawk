package util;

public class ArrayUtil {
    public static NumericTrie.Node[] growNumericNodeArray(NumericTrie.Node[] nodes){
        NumericTrie.Node[] newArray = new NumericTrie.Node[nodes.length+1];
        System.arraycopy(nodes,0,newArray,0,nodes.length);
        return newArray;
    }

    //not inplace grow
    public static int[][] grow2DIntArray(int[][] array){
        int[][] newArray = new int[array.length + 1][];
        System.arraycopy(array,0, newArray, 0, array.length);
        return newArray;
    }


    public static byte[] bytesConcatenation(byte[] bytes1, byte[] bytes2){
        byte[] temp = new byte[bytes1.length + bytes2.length];
        System.arraycopy(bytes1, 0, temp, 0, bytes1.length);
        System.arraycopy(bytes2, 0, temp, bytes1.length, bytes2.length);
        return temp;
    }

    public static int[] intsConcatenation(int[] a, int[] b){
        int[] temp = new int[a.length + b.length];
        System.arraycopy(a, 0, temp, 0, a.length);
        System.arraycopy(b, 0, temp, a.length, b.length);
        return temp;
    }

    public static byte[] growByteArray(byte[] input){
        byte[] newArray = new byte[input.length+1];
        System.arraycopy(input,0,newArray,0,input.length);
        return newArray;
    }

    public static byte[][] bytePoolGrow(byte[][] old){
        byte[][] ret = new byte[old.length+1][];
        for (int i = 0; i < old.length; i++) {
            ret[i] = old[i];
        }
        return ret;
    }

    public static void main(String[] args) {
        double a = 9999.5;
        System.out.println(Long.toBinaryString(NumberUtil.double2SortableLong(a)));

        long parent = Long.parseUnsignedLong("1100000011000011100001110000000000000000000000000000000000000000", 2);
        System.out.println(NumberUtil.sortableLong2Double(parent));
    }
}
