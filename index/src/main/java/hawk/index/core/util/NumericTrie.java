package hawk.index.core.util;

import hawk.index.core.reader.DataInput;
import hawk.index.core.writer.DataOutput;
import hawk.index.core.writer.PrefixedNumber;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

public class NumericTrie {

    class Node{
        long key;
        byte[] keyBytes;
        byte[] offset;
        Node left;
        Node right;
        Node parent;
        Node[] children;

        public Node() {
        }

        public Node(long key, byte[] keyBytes, byte[] offset) {
            this.key = key;
            this.keyBytes = keyBytes;
            this.offset = offset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node node = (Node) o;
            return Arrays.equals(keyBytes, node.keyBytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(keyBytes);
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
        if(length == 64){
            add64(number,offset);
        }else{
            add32(number, offset);
        }
    }
    public void add32(byte[] number, byte[] offset){}


    public void addChild(Node parent, Node child, long key){
        if(parent.children!=null){ // already has children list
            parent.children = ArrayUtil.growNumericNodeArray(parent.children);
            parent.children[parent.children.length-1] = child;
            Node left = parent.children[parent.children.length-2];
            left.right = child;
            child.left = left;
        }else{
            parent.children = new Node[1];
            parent.children[0] = child;
        }
        child.parent = parent;
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
        Node newNode = new Node(longNum, number, offset);
        if(length - lowestSetBit < precisionStep){
            addChild(root, newNode, longNum);
        }else{
//            int maskLength = (int)( (length - lowestSetBit + 1) % precisionStep == 0? length - lowestSetBit -
//                    precisionStep + 1 : length - lowestSetBit + 1 - (lowestSetBit % precisionStep + 1));

            int maskLength = (int)(length - lowestSetBit + 1 - (length - lowestSetBit + 1) % precisionStep -precisionStep);
            long mask = get64Mask(maskLength);
            long parentKey = longNum & mask;
            Node parent = map.get(parentKey);
            addChild(parent,newNode,longNum);
        }
    }

    //find the first larger or equal node
    public Node find64Lower(byte[] lower){
        if(this.nodes.length == 0){
            return null;
        } // if first node is larger, return first node
        if(NumberUtil.compareSortableBytes(this.nodes[0].keyBytes, lower) == 1){
            return this.nodes[0];
        }
        int left = 0;
        int right = this.nodes.length - 1;
        int mid = (left + right) / 2;
        while(left <= right){//binary search
            if(NumberUtil.compareSortableBytes(this.nodes[mid].keyBytes, lower) == -1){
                // if mid is smaller, and mid + 1 is larger, return mid + 1
                if(mid + 1 < this.nodes.length && NumberUtil.compareSortableBytes(this.nodes[mid + 1].keyBytes, lower) >= 0){
                    return this.nodes[mid + 1];
                }
                left = mid + 1;
            } else if (NumberUtil.compareSortableBytes(this.nodes[mid].keyBytes, lower) == 0) {
                return this.nodes[mid];
            } else if (NumberUtil.compareSortableBytes(this.nodes[mid].keyBytes, lower) == 1) {
                // if mid is larger, and mid - 1 is smaller, return mid
                if(mid - 1 >= 0 && NumberUtil.compareSortableBytes(this.nodes[mid - 1].keyBytes, lower) == -1){
                    return this.nodes[mid];
                }
                right = mid - 1;
            }
            mid = (left + right) / 2;
        }
        return null;
    }

    public Node find64Upper(byte[] upper){
        if(this.nodes.length == 0){
            return null;
        } // if last node is smaller, return last node
        if(NumberUtil.compareSortableBytes(this.nodes[this.nodes.length - 1].keyBytes, upper) <= 0){
            return this.nodes[this.nodes.length - 1];
        }
        int left = 0;
        int right = this.nodes.length - 1;
        int mid = (left + right) / 2;
        while(left <= right){//binary search
            if(NumberUtil.compareSortableBytes(this.nodes[mid].keyBytes, upper) == -1){
                // if mid is smaller, and mid + 1 is larger, return mid
                if(mid + 1 < this.nodes.length && NumberUtil.compareSortableBytes(this.nodes[mid + 1].keyBytes, upper) == 1){
                    return this.nodes[mid];
                }
                left = mid + 1;
            } else if (NumberUtil.compareSortableBytes(this.nodes[mid].keyBytes, upper) == 0) {
                return this.nodes[mid];
            } else if (NumberUtil.compareSortableBytes(this.nodes[mid].keyBytes, upper) == 1) {
                // if mid is larger, and mid - 1 is smaller, return mid - 1
                if(mid - 1 >= 0 && NumberUtil.compareSortableBytes(this.nodes[mid - 1].keyBytes, upper) <= 0){
                    return this.nodes[mid - 1];
                }
                right = mid - 1;
            }
            mid = (left + right) / 2;
        }
        return null;
    }

    public HashSet<Node> rangeSearch64(double lower, double upper){
        if(lower > upper){
            return null;
        }
        long lowerLong = NumberUtil.double2SortableLong(lower);
        long upperLong = NumberUtil.double2SortableLong(upper);
        byte[] lowerBytes = NumberUtil.long2Bytes(lowerLong);
        byte[] upperBytes = NumberUtil.long2Bytes(upperLong);
        //  if largest is smaller than lower, return null
        if(NumberUtil.compareSortableBytes(this.nodes[this.nodes.length - 1].keyBytes, lowerBytes) == -1){
            return null;
        }
        // if smallest is larger than upper, return null;
        if(NumberUtil.compareSortableBytes(this.nodes[0].keyBytes, upperBytes) == 1){
            return null;
        }
        Node lowerNode = find64Lower(lowerBytes);
        Node upperNode = find64Upper(upperBytes);
        HashSet<Node> set = new HashSet<>();
        set.add(lowerNode);
        set.add(upperNode);
        findLargerRecursive(set, lowerNode, upperNode);
        findSmallerRecursive(set, upperNode, lowerNode);
        return set;
    }

    public void findLargerRecursive(HashSet<Node> set, Node cur, Node upper){
        while (cur.right != null && NumberUtil.compareSortableBytes(cur.right.keyBytes, upper.keyBytes) == -1){
            cur = cur.right;
            set.add(cur);
        }
        if(cur.parent.right != null && NumberUtil.compareSortableBytes(cur.parent.right.keyBytes, upper.keyBytes) == -1){
            cur = cur.parent.right;
            set.add(cur);
            findLargerRecursive(set,cur,upper);
        }
    }

    public void findSmallerRecursive(HashSet<Node> set, Node cur, Node lower){
        while (cur.left != null && NumberUtil.compareSortableBytes(cur.left.keyBytes, lower.keyBytes) == 1){
            cur = cur.left;
            set.add(cur);
            long longbits = ByteBuffer.wrap(cur.keyBytes).getLong();
            System.out.println(NumberUtil.sortableLong2Double(longbits));
        }
        if(cur.parent.left != null && NumberUtil.compareSortableBytes(cur.parent.left.keyBytes, lower.keyBytes) == 1){
            cur = cur.parent.left;
            set.add(cur);
            long longbits = ByteBuffer.wrap(cur.keyBytes).getLong();
            System.out.println(NumberUtil.sortableLong2Double(longbits));
            findSmallerRecursive(set,cur,lower);
        }
    }

    public HashSet<Node> rangeSearch(Number lower, Number uppper){
        if(lower instanceof Double){
            return rangeSearch64((double)lower, (double) uppper);
        }
        return null;
    }

    public static void main(String[] args) {
        NumericTrie trie = new NumericTrie(64, 4);
        HashSet<PrefixedNumber> prefixes = new HashSet<>();
        Random random = new Random();
        for (int i = 0; i < 10000; i++) {
            int randInt = random.nextInt(10000);
            double first = randInt + 0.5;
            long sortable = NumberUtil.double2SortableLong(first);
            prefixes.addAll(NumberUtil.long2PrefixFormat(sortable,4)) ;
        }

        double first = 100001;
        long sortable = NumberUtil.double2SortableLong(first);
        prefixes.addAll(NumberUtil.long2PrefixFormat(sortable,4)) ;
        first = 2023;
        sortable = NumberUtil.double2SortableLong(first);
        prefixes.addAll(NumberUtil.long2PrefixFormat(sortable,4)) ;


        ArrayList<PrefixedNumber> prefixedNumbers = new ArrayList<>(prefixes);
        Collections.sort(prefixedNumbers,(a,b)->{
            byte[] aBytes = a.getValue();
            byte[] bytes = b.getValue();
            for (int j = 0; j < aBytes.length && j < bytes.length; j++) {
                int aInt = aBytes[j] & 0xff;
                int bInt = bytes[j] & 0xff;
                if(aInt > bInt){
                    return 1;
                } else if (aInt<bInt) {
                    return -1;
                }
            }
            return aBytes.length - bytes.length;
        });
        for (int j = 0; j < prefixedNumbers.size(); j++) {
            byte[] number = prefixedNumbers.get(j).getValue();
            System.out.println(Long.toBinaryString(ByteBuffer.wrap(number).getLong()));
            trie.add(prefixedNumbers.get(j).getValue(),null);
        }





        HashSet<Node> set = trie.rangeSearch((double) 0, (double)10000);
        System.out.println("---------");
        System.out.println(set.size());
        for (Node node: set
             ) {
            long longbits = ByteBuffer.wrap(node.keyBytes).getLong();
            System.out.println(NumberUtil.sortableLong2Double(longbits));
//            System.out.println(Long.toBinaryString(longbits));

        }
    }


}
