package hawk.index.core.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Data
@Slf4j
public class NumericTrie {

    @Data
    public class Node {
        String key;
        byte[][] offsets;
        Node left;
        Node right;
        Node parent;
        Node[] children;


        public Node() {
        }

        public Node(String key, byte[] offset) {
            this.key = key;
            this.offsets = new byte[1][];
            this.offsets[0] = offset;
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
            children = ArrayUtil.growNumericNodeArray(children);
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
            lastLayer = ArrayUtil.growNumericNodeArray(lastLayer);
            lastLayer[lastLayer.length - 1] = child;
        }
    }

    public void add(String key, byte[] offset){
        if(nodeMap.containsKey(key)){ // if already contains the node, concatenate old frq offsets with the new one
            Node old  = nodeMap.get(key);
            byte[][] oldOffsets = old.getOffsets();
            oldOffsets = ArrayUtil.bytePoolGrow(oldOffsets);
            oldOffsets[oldOffsets.length - 1] = offset;
            old.setOffsets(oldOffsets);
            return;
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int shift = keyBytes[0] & 0xff;
        Node newNode = new Node(key,offset);

        //debug info
        long sortableLong = DataInput.read7bitBytes2Long(keyBytes, 1);
        double doubelValue = NumberUtil.sortableLong2Double(sortableLong);
        log.info("NumericTrie Construction ===> " + "shift is " + shift + ", value is " + doubelValue);


        if(shift == 0){
            addChild(root, newNode, shift);
        }else{//calculate parent shift, mask out last precision step bits, and lastly assemble them to get parentKey
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

    public List<Node> rangeSearch(double lower, double upper){
        List<Node> result = new ArrayList<>();
        if(lower > upper) return result;
        if(lastLayer.length == 0) return result;
        long lowerBits = NumberUtil.double2SortableLong(lower);
        long upperBits = NumberUtil.double2SortableLong(upper);
        String lowerString = NumberUtil.long2StringWithShift(lastLayerShift, lowerBits);
        String upperString = NumberUtil.long2StringWithShift(lastLayerShift, upperBits);
        if(lowerString.compareTo(lastLayer[lastLayer.length-1].key) > 0) return result;
        if(upperString.compareTo(lastLayer[0].key) < 0 ) return result;

        Node lowerNode;
        Node upperNode;
        if(lowerString.compareTo(lastLayer[0].key) <= 0){
            lowerNode = lastLayer[0];
        }else {lowerNode = searchLower(lowerString);}
        if(upperString.compareTo(lastLayer[lastLayer.length-1].key) >= 0){
            upperNode = lastLayer[lastLayer.length-1];
        }else{
            upperNode = searchUpper(upperString);
        }
        if(lowerNode == upperNode){
            result.add(lowerNode);
            return result;
        }
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
        long val = NumberUtil.double2SortableLong(2.0);
        System.out.println(Long.toBinaryString(val));

        val = NumberUtil.double2SortableLong(8.0);
        System.out.println(Long.toBinaryString(val));

        val = NumberUtil.double2SortableLong(11.5);
        System.out.println(Long.toBinaryString(val));

        val = NumberUtil.double2SortableLong(12.5);
        System.out.println(Long.toBinaryString(val));
    }
}
