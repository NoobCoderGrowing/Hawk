package hawk.index.core.document;
import hawk.index.core.field.Field;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Document {
    private float score;
    private List<Field> fields = new ArrayList<>();

    public Document() {}

    public Document(float score) {
        this.score = score;
    }

    public void add(Field field){
        this.fields.add(field);
    }
}
