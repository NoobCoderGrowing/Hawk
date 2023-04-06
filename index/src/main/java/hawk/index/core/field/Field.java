package hawk.index.core.field;

import lombok.Data;

@Data
public abstract class Field{

    public Enum<Field.Tokenized> isTokenized;

    public Enum<Field.Stored> isStored;

    public enum Stored{
        YES,

        NO
    }

    public enum Tokenized{
        YES,

        NO
    }

    public abstract byte[] getBytes();

    public abstract byte[] getNameBytes();

}
