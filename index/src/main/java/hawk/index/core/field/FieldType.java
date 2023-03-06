package hawk.index.core.field;

import lombok.Data;

@Data
public class FieldType{

    private boolean stored = false;
    private boolean tokenized = false;

    public FieldType(Enum<Field.Stored> stored, Enum<Field.Tokenized> tokenizedEnum) {
        this.stored = stored == Field.Stored.YES? true:false;
        this.tokenized = tokenizedEnum == Field.Tokenized.YES?true:false;
    }

}
