package hawk.index.core.search;

import lombok.Data;

@Data
public class ScoreDoc {
    public float score;

    public int docID;


    public ScoreDoc(float score, int docID) {
        this.score = score;
        this.docID = docID;
    }
}
