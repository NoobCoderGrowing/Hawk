import lombok.Data;
import java.util.Arrays;


@Data
public class PostingList {

    private int[] posting;

    public PostingList() {
    }

    public PostingList(int[] posting) {
        this.posting = posting;
    }

    //inplace binary search
    public boolean binarySearch(int[] arr, int first, int last, int key){
        int mid = (first + last) / 2;
        while (first <= last) {
            if (arr[mid] < key) {
                first = mid + 1;
            } else if (arr[mid] == key) {
                return true;
            } else if (arr[mid] > key) {
                last = mid - 1;
            }
            mid = (first + last) / 2;
        }
        return false;
    }

    public PostingList getIntersection(PostingList postingList){
        int[] longer = null;
        int[] shorter = null;
        int curLength = getPosting().length;
        int inputLength = postingList.getPosting().length;
        if(curLength >= inputLength){
            longer = this.getPosting();
            shorter = postingList.getPosting();
        }else{
            longer = postingList.getPosting();
            shorter = this.getPosting();
        }
        int[] result = new int[shorter.length];
        int endPos = -1;
        for (int i = 0; i < shorter.length; i++) {
            if(binarySearch(longer, 0, longer.length - 1, shorter[i])){
                endPos ++;
                result[endPos] = shorter[i];
            }
        }
        PostingList retVal = new PostingList();
        if (endPos >= 0) {
            result = Arrays.copyOfRange(result, 0, endPos + 1);
        } else {
            result = null;
        }
        retVal.setPosting(result);
        return retVal;
    }

    public static void main(String[] args) {
        int[] listA = new int[]{1,2,3,4};
        int[] listB = new int[]{};
        PostingList a = new PostingList(listA);
        PostingList b = new PostingList(listB);
        PostingList ret = a.getIntersection(b);
        System.out.println(ret);
    }
}
