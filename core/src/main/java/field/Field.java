package field;

import lombok.Data;

@Data
public abstract class Field{

    public Enum<Field.Tokenized> isTokenized;

    public Enum<Field.Stored> isStored;

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

    public abstract byte[] serialize();

    public abstract byte[] serializeName();

}
