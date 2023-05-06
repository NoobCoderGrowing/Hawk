package document;

import field.Field;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Data
public class Document {
    private float score;

    private HashMap<String, Field> fieldMap = new HashMap<>();

    public Document() {}

    public Document(float score) {
        this.score = score;
    }

    public void add(Field field){
        String fieldName = field.getName();
        fieldMap.put(fieldName, field);
    }
}
