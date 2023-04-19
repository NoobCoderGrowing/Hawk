package hawk.index.core.search;

public class Similarity {

    public static float BM25(int totalDoc, int termFrequency, int docFrequency, int docFieldLength,
                             float averageFieldLength){

        float idf = (float) Math.log(1 + (totalDoc - termFrequency + 0.5f) / (termFrequency + 0.5f));
        float bm25 = idf * ((docFrequency * 2.2f) / ((float) docFrequency + 1.2f * (0.25f + 0.75f *
                         ((float) docFieldLength / averageFieldLength))));
        return bm25;
    }

}
