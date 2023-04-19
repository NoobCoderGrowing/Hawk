package hawk.index.core.search;

import lombok.Data;

import java.util.Objects;

@Data
public class ScoreDoc {
    public float score;

    public int docID;

    public ScoreDoc(float score, int docID) {
        this.score = score;
        this.docID = docID;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScoreDoc scoreDoc = (ScoreDoc) o;
        return docID == scoreDoc.docID;
    }

    @Override
    public int hashCode() {
        return Objects.hash(docID);
    }
}
