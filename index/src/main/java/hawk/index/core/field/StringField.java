package hawk.index.core.field;

import lombok.Data;

@Data
public class StringField extends Field{
    private String name;

    private String value;

    public Enum<Field.Tokenized> isTokenized;

    private Enum<Field.Stored> isStored;

    public StringField(String name, String value, Enum<Field.Tokenized> isTokenized,
                       Enum<Field.Stored> isStored) {
        this.name = name;
        this.value = value;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }
}
