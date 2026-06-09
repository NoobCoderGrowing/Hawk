package document;

import field.Field;
import lombok.Data;

import java.util.HashMap;


@Data
public class Document {

    private int uniqueID;

    private float score;

    private HashMap<String, Field> fieldMap = new HashMap<>();

    public Document() {
    }

    public Document(int uniqueID) {
        this.uniqueID = uniqueID;
    }

    public Document(float score) {
        this.score = score;
    }

    public void add(Field field){
        String fieldName = field.getName();
        fieldMap.put(fieldName, field);
    }
}
