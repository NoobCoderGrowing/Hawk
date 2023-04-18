package hawk.index.core.util;

public class AVLTree<T> {

    private Node root;

    class Node<T> {
        int key;
        int height;
        T value;
        Node left;
        Node right;

        public Node(int key, T value) {
            this.key = key;
            this.value = value;
        }
    }

    public void updateHeight(Node node){
        node.height = 1 + Math.max(height(node.left), height(node.right));
    }

    public int height(Node node){
        return node == null ? -1 : node.height;
    }

    public int getBalance(Node node){
        return (node == null) ? 0 : height(node.left) - height(node.right);
    }

    public Node rotateRight(Node y){
        Node x = y.left;
        Node z = x.right;
        x.right = y;
        y.left = z;
        updateHeight(y);
        updateHeight(x);
        return x;
    }

    public Node rotateLeft(Node y){
        Node x = y.right;
        Node z = x.left;
        x.left = y;
        y.right = z;
        return x;
    }

    public Node rebalance(Node z){
        updateHeight(z);
        int balance = getBalance(z);
        if(balance>1){
            if(height(z.right.right) > height(z.right.left)){
                z = rotateLeft(z);
            } else {
                z.right = rotateRight(z.right);
                z = rotateLeft(z);
            }
        } else if (balance < -1) {
            if(height(z.left.left) > height(z.left.right)){
                z = rotateRight(z);
            }else{
                z.left = rotateLeft(z.left);
                z = rotateRight(z);
            }
        }
        return z;
    }

    public Node insertRecursive(Node curNode, Node newNode){
        if (curNode == null){
            return newNode;
        } else if (curNode.key > newNode.key) {
            curNode.left = insertRecursive(curNode.left, newNode);
        } else if (curNode.key < newNode.key) {
            curNode.right = insertRecursive(curNode.right, newNode);
        } else {
            return null;
        }
        return rebalance(curNode);
    }

    public Node insert(int key, T value){
        Node newNode = new Node(key, value);
        if(root == null){
            root = newNode;
            return root;
        }
        return insertRecursive(root, newNode);
    }

    public Node mostLeftChild(Node curNode){
        if(curNode.left == null){
            return curNode;
        }
        return mostLeftChild(curNode.left);
    }

    public Node deleteRecursive(Node curNode, int key){
        if(curNode == null){
            return null;
        }else if(curNode.key > key){
            curNode.left = deleteRecursive(curNode.left, key);
        } else if (curNode.key < key) {
            curNode.right = deleteRecursive(curNode.right, key);
        } else {
            if(curNode.left == null || curNode.right == null){
                curNode = (curNode.left == null) ? curNode.right : curNode.left;
            }else {
                Node mostLeftChild = mostLeftChild(curNode.right);
                curNode.key = mostLeftChild.key;
                curNode.value = mostLeftChild.value;
                curNode.right = deleteRecursive(curNode.right, mostLeftChild.key);
            }
        }
        if(curNode != null){
            curNode = rebalance(curNode);
        }
        return curNode;
    }

    public void delete(int key){
        deleteRecursive(root, key);
    }

    public T find(int key){
        Node curNode = root;
        Node preNode;
        while(curNode != null){
            if(curNode.key == key){
                break;
            }
            preNode = curNode;
            curNode = curNode.key < key? curNode.right : curNode.left;
        }
        return curNode == null? null : (T) curNode.value;
    }


}
