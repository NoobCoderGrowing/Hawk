package hawk.index.core.util;

public class NumericTrie2 {

    class Node {
        String key;
        byte[] offset;
        NumericTrie.Node left;
        NumericTrie.Node right;
        NumericTrie.Node parent;
        NumericTrie.Node[] children;

        public Node() {
        }

        public Node(String key, byte[] offset) {
            this.key = key;
            this.offset = offset;
        }
    }

    private int length;
    private int precisionStep;
    private Node root;
    private Node[][] trieNodes;

}
