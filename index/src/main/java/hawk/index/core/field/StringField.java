package hawk.index.core.field;

import lombok.Data;

@Data
public class StringField extends Field{
    private String name;

    private String value;

    private FieldType fieldType;

    public StringField(String name, String value, FieldType fieldType) {
        this.name = name;
        this.value = value;
        this.fieldType = fieldType;
    }
}
