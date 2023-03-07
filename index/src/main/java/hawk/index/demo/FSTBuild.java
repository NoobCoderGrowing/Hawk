package hawk.index.demo;

import org.apache.lucene.util.*;
import org.apache.lucene.util.fst.Builder;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.PositiveIntOutputs;
import org.apache.lucene.util.fst.Util;

import java.io.IOException;
import java.util.HashMap;


public class FSTBuild {

    public static void main(String[] args) throws IOException {
        //构造FST
        // Input values (keys). These must be provided to Builder in Unicode sorted order!
        String inputValues[] = {"cat","da","daa", "daaa","daaaa","daaaaa","daaaaaaaa","daaaaaaaaa",
                "daaaaaaaaaa","dog", "dogs",};
        long outputValues[] = {1,2,3,9,5,6,7,8, 13,10,11};
        HashMap<String, Long> map = new HashMap<>();

        PositiveIntOutputs outputs = PositiveIntOutputs.getSingleton();
        Builder<Long> builder = new Builder<Long>(FST.INPUT_TYPE.BYTE1, outputs);
        BytesRefBuilder scratchBytes = new BytesRefBuilder();
        IntsRefBuilder scratchInts = new IntsRefBuilder();
        for (int i = 0; i < inputValues.length; i++) {
            map.put(inputValues[i], outputValues[i]);
            scratchBytes.copyChars(inputValues[i]);
            builder.add(Util.toIntsRef(scratchBytes.get(), scratchInts), outputValues[i]);
        }
        FST<Long> fst = builder.finish();

        //根据key查询value
        Long value = Util.get(fst, new BytesRef("dogs"));
        System.out.println(value);
        IntsRef key = Util.getByOutput(fst, value);
        System.out.println(Util.toBytesRef(key,scratchBytes).utf8ToString());

        System.out.println(RamUsageEstimator.shallowSizeOf(fst));;
        System.out.println(RamUsageEstimator.shallowSizeOf(map));
    }
}
