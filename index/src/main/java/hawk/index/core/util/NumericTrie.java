package hawk.index.core.util;

import hawk.index.core.reader.DataInput;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class NumericTrie {

    class Node {
        String key;
        byte[] offset;
        Node left;
        Node right;
        Node parent;
        Node[] children;
        int shift;

        public Node() {
        }

        public Node(String key, byte[] offset, int shift) {
            this.key = key;
            this.offset = offset;
            this.shift = shift;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node node = (Node) o;
            return Objects.equals(key, node.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key);
        }
    }

    private int length;
    private int precisionStep;
    private Node root;
    private Node[] lastLayer;
    private HashMap<String, Node> nodeMap;
    private int lastLayerShift;

    public NumericTrie(int length, int precisionStep) {
        this.length = length;
        this.precisionStep = precisionStep;
        this.root = new Node();
        this.nodeMap = new HashMap<>();
        this.lastLayerShift = length % precisionStep == 0 ? (length / precisionStep) - 1 : length / precisionStep;
        this.lastLayer = new Node[0];
    }

    public void addChild(Node parent, Node child, int shift){
        Node[] children = parent.children;
        if(children != null){
            children = ArrayUtil.growNumericNodeArray2(children);
            children[children.length - 1] = child;
            child.left = children[children.length - 2];
            children[children.length - 2].right = child;
        }else{
            parent.children = new Node[1];
            parent.children[0] = child;
        }
        child.parent = parent;
        nodeMap.put(child.key, child);
        if(shift == lastLayerShift){
            lastLayer = ArrayUtil.growNumericNodeArray2(lastLayer);
            lastLayer[lastLayer.length - 1] = child;
        }
    }

    public void add(String key, byte[] offset){
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int shift = keyBytes[0] & 0xff;
        Node newNode = new Node(key,offset, shift);
        if(shift == 0){
            addChild(root, newNode, shift);
        }else{
            byte parentShift = (byte) ((shift - 1) & 0xff);
            long parentValue = DataInput.read7bitBytes2Long(keyBytes, 1);
            long mask = NumberUtil.getLongMask(precisionStep * shift);
            parentValue &= mask;
            String parentKey = NumberUtil.long2StringWithShift(parentShift, parentValue);;
            Node parent = nodeMap.get(parentKey);
            addChild(parent, newNode, shift);
        }
    }

    public Node searchLower(String lower){
        // if first node is larger, return first node
        if(lastLayer[0].key.compareTo(lower) >= 0) return lastLayer[0];

        int left = 0;
        int right = lastLayer.length - 1;
        int mid = (left + right) / 2;
        while(left <= right){//binary search
            if(lastLayer[mid].key.compareTo(lower) < 0){
                // if mid is smaller, and mid + 1 is larger, return mid + 1
                if(mid + 1 < lastLayer.length && lastLayer[mid + 1].key.compareTo(lower) >= 0) return lastLayer[mid + 1];
                left = mid + 1;
            } else if (lastLayer[mid].key.compareTo(lower) == 0) {
                return lastLayer[mid];
            } else if (lastLayer[mid].key.compareTo(lower) > 0) {
                // if mid is larger, and mid - 1 is smaller, return mid
                if(mid - 1 >= 0 && lastLayer[mid-1].key.compareTo(lower) < 0) return this.lastLayer[mid];
                right = mid - 1;
            }
            mid = (left + right) / 2;
        }
        return null;
    }

    public Node searchUpper(String upper){
        // if last node is smaller, return last node
        if(lastLayer[lastLayer.length -1].key.compareTo(upper) <= 0) return lastLayer[lastLayer.length - 1];

        int left = 0;
        int right = lastLayer.length - 1;
        int mid = (left + right) / 2;
        while(left <= right){//binary search
            if(lastLayer[mid].key.compareTo(upper) < 0){
                // if mid is smaller, and mid + 1 is larger, return mid
                if(mid + 1 < lastLayer.length && lastLayer[mid + 1].key.compareTo(upper) > 0) return lastLayer[mid];
                left = mid + 1;
            } else if (lastLayer[mid].key.compareTo(upper) == 0) {
                return this.lastLayer[mid];
            } else if (lastLayer[mid].key.compareTo(upper) > 0) {
                // if mid is larger, and mid - 1 is smaller, return mid - 1
                if(mid - 1 >= 0 && lastLayer[mid - 1].key.compareTo(upper) <= 0) return lastLayer[mid -1];
                right = mid - 1;
            }
            mid = (left + right) / 2;
        }
        return null;
    }

    public HashSet<Node> rangeSearch(double lower, double upper){
        HashSet<Node> result = new HashSet<>();
        if(lower > upper) return result;
        if(lastLayer.length == 0) return result;
        long lowerBits = NumberUtil.double2SortableLong(lower);
        long upperBits = NumberUtil.double2SortableLong(upper);
        String lowerString = NumberUtil.long2StringWithShift(lastLayerShift, lowerBits);
        String upperString = NumberUtil.long2StringWithShift(lastLayerShift, upperBits);
        if(lowerString.compareTo(lastLayer[lastLayer.length-1].key) > 0) return result;
        if(upperString.compareTo(lastLayer[0].key) < 0 ) return result;
        Node lowerNode = searchLower(lowerString);
        Node upperNode = searchUpper(upperString);
        //start search
        result.add(lowerNode);
        result.add(upperNode);
        while(lowerNode.parent != upperNode.parent){
            while(lowerNode.right != null){
                lowerNode = lowerNode.right;
                result.add(lowerNode);
            }
            while(upperNode.left != null){
                upperNode = upperNode.left;
                result.add(upperNode);
            }
            lowerNode = lowerNode.parent;
            upperNode = upperNode.parent;
        }
        while(lowerNode.right != upperNode){
            lowerNode = lowerNode.right;
            result.add(lowerNode);
        }
        return result;
    }

    public static void main(String[] args) {
        NumericTrie numericTrie = new NumericTrie(64, 4);
        HashSet<String> set = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            double value = i + 0.5;
            long sortable = NumberUtil.double2SortableLong(value);
            String[] strings = NumberUtil.long2PrefixString(sortable,4);
            set.addAll(Arrays.asList(strings));
        }
        ArrayList<String> list = new ArrayList<>(set);
        Collections.sort(list);
        for (int i = 0; i < list.size(); i++) {
            numericTrie.add(list.get(i),null);
        }

        HashSet<Node> set2 = numericTrie.rangeSearch(0.5 , 10000.5);
        List<Node> nodes = new ArrayList<>(set2);
        Collections.sort(nodes,(a,b)->{
            byte[] abytes  = a.key.getBytes();
            byte[] bytes = b.key.getBytes();
            int aShift = abytes[0] & 0xff;
            int bShift = bytes[0] & 0xff;
            if(aShift != bShift){
                return aShift - bShift;
            }
            long aLong = DataInput.read7bitBytes2Long(abytes,1);
            long bLong = DataInput.read7bitBytes2Long(bytes, 1);
            double aDouble = NumberUtil.sortableLong2Double(aLong);
            double bDouble = NumberUtil.sortableLong2Double(bLong);
            if(aDouble > bDouble){
                return 1;
            } else if (aDouble < bDouble) {
                return -1;
            }
            return 0;
        });

        for (int i = 0; i < nodes.size(); i++) {
            String key = nodes.get(i).key;
            byte[] bytes = key.getBytes();
            System.out.println(bytes[0]&0xff);
            long k = DataInput.read7bitBytes2Long(bytes,1);
            System.out.println(Long.toUnsignedString(k,2));
            Double j = NumberUtil.sortableLong2Double(k);
            System.out.println(j);
        }

    }
}
