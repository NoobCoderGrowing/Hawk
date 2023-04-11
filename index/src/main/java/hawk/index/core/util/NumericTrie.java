package hawk.index.core.util;

import hawk.index.core.reader.DataInput;

import java.util.HashMap;

public class NumericTrie {

    class Node{
        long key;
        byte[] value;
        Node left;
        Node right;
        Node parent;
        Node[] children;

        public Node() {
        }

        public Node(long key, byte[] value) {
            this.key = key;
            this.value = value;
        }
    }
    private int length;

    private int precisionStep;
    private Node root;

    private HashMap<Long, Node> map;

    private Node[] nodes;

    public NumericTrie(int length, int precisionStep) {
        this.length = length;
        this.precisionStep = precisionStep;
        this.root = new Node();
        this.map = new HashMap<>();
        this.nodes = new Node[0];
    }

    public long get64Mask(int maskLength){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maskLength; i++) {
            sb.append("1");
        }
        for (int i = 0; i < 64 - maskLength; i++) {
            sb.append("0");
        }
        String maskString = sb.toString();
        return Long.parseUnsignedLong(maskString, 2);
    }

    public void add(byte[] number, byte[] offset){
        if(length == 32){
            add32(number, offset);
        }else{
            add64(number,offset);
        }
    }
    public void add32(byte[] number, byte[] offset){}


    public void addChild(Node parent, Node child, long key){
        if(parent.children!=null){
            parent.children = ArrayUtil.growNumericNodeArray(parent.children);
            parent.children[parent.children.length-1] = child;
            Node left = parent.children[parent.children.length-2];
            left.right = child;
            child.left = left;
            child.parent = parent;
        }else{
            parent.children = new Node[1];
            parent.children[0] = child;
        }
        this.nodes = ArrayUtil.growNumericNodeArray(this.nodes);
        this.nodes[this.nodes.length-1] = child;
        this.map.put(key, child);
    }

    public int get64LowestSetBit(long number){
        long setBit = number & -number;
        if(setBit != 0x8000000000000000L){
            return (int) (Math.round(Math.log(setBit)/Math.log(2)) + 1);
        }
        return 64;
    }
    public void add64(byte[] number, byte[] offset){
        long longNum = DataInput.readUnsignedLong(number);
        //get the lowest set bit
        long lowestSetBit = get64LowestSetBit(longNum);
        Node newNode = new Node(longNum, offset);
        if(length - lowestSetBit < precisionStep){
            addChild(root, newNode, longNum);
        }else{
            int maskLength = (int)( (length - lowestSetBit + 1) % precisionStep == 0? length - lowestSetBit -
                    precisionStep + 1 : length - lowestSetBit + 1 - (lowestSetBit % precisionStep + 1));
            long mask = get64Mask(maskLength);
            long parentKey = longNum & mask;
            Node parent = map.get(parentKey);
            addChild(parent,newNode,longNum);
        }
    }

    public Node find64Lower(long lower){
        return null;
    }

    public Node find64Upper(long upper){
        return null;
    }

    public Node[] rangeSearch64(double lower, double upper){
        long lowerLong = Double.doubleToLongBits(lower);
        long upperLong = Double.doubleToLongBits(upper);
        Node lowerNode = find64Lower(lowerLong);
        Node upperNode = find64Upper(upperLong);
        return null;
    }

    public Node[] rangeSearch(Number lower, Number uppper){
        if(lower instanceof Double){
            return rangeSearch64((double)lower, (double) uppper);
        }
        return null;
    }

    public static void main(String[] args) {
//        int a = 0b10000000000000000000000000000000;
//        int b = a & -a;
//        long lowestBit = Math.round(Math.log(b)/Math.log(2)) + 1;
//        System.out.println(lowestBit);
//        System.out.println(Integer.toBinaryString(b));
    }


}
