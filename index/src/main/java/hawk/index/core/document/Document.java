package hawk.index.core.document;
import hawk.index.core.field.Field;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Document {
    private int docID;
    private float score;
    private List<Field> fields = new ArrayList<>();

    public Document() {}

    public void add(Field field){
        this.fields.add(field);
    }
}
