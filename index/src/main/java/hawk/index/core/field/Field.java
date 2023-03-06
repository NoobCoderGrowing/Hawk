package hawk.index.core.field;

import lombok.Data;

@Data
public class Field{

    public enum Stored{
        YES,

        NO
    }

    public enum Tokenized{
        YES,

        NO
    }

}
