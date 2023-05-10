package field;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeInfo(use=JsonTypeInfo.Id.CLASS, include=JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DoubleField.class, name = "DoubleField"),
        @JsonSubTypes.Type(value = StringField.class, name = "StringField"),
})
public interface Field{
    public enum Stored{
        YES("Yes"),

        NO("No");
        Stored(String label) {
        }
    }

    public enum Tokenized{
        YES("Yes"),

        NO("No");
        Tokenized(String label) {
        }
    }

    public Enum<Field.Tokenized> isTokenized();

    public Enum<Field.Stored> isStored();

    public byte[] customSerialize();

    public byte[] serializeName();

    public String getName();

}
