package demo;

import io.github.noobcodergrowing.JFST.FST;
import io.github.noobcodergrowing.JFST.fstNode;
import io.github.noobcodergrowing.JFST.fstPair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class FSTBuild {

    public static String[] str2Array(String str) {
        int len = str.length();
        String[] ret = new String[len];
        for (int i = 0; i < len; i++) {
            ret[i] = String.valueOf(str.charAt(i));
        }
        return ret;
    }

    public static void main(String[] args) {
        String[] inputValues = {"cat", "da", "daa", "daaa", "daaaa", "daaaaa", "daaaaaaaa", "daaaaaaaaa",
                "daaaaaaaaaa", "dog", "dogs"};
        long[] outputValues = {1, 2, 3, 9, 5, 6, 7, 8, 13, 10, 11};
        HashMap<String, Long> map = new HashMap<>();
        for (int i = 0; i < inputValues.length; i++) {
            map.put(inputValues[i], outputValues[i]);
        }
        Arrays.sort(inputValues);
        ArrayList<fstPair<String[], Long>> inputs = new ArrayList<>();
        for (String inputValue : inputValues) {
            inputs.add(new fstPair<>(str2Array(inputValue), map.get(inputValue)));
        }

        FST fst = new FST();
        fst.build(inputs);

        fstPair<ArrayList<fstNode>, Long> searchRet = fst.search(str2Array("dogs"));
        System.out.println(searchRet.getValue());
        System.out.println(fst.search(str2Array("dogs")));
    }
}
