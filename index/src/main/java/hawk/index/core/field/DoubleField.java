package hawk.index.core.field;

import lombok.Data;

import java.nio.charset.StandardCharsets;

@Data
public class DoubleField extends Field{

    private String name;

    private double value;

    @Override
    public byte[] getBytes() {
        return null;
    }

    public byte[] getNameBytes(){
        return this.name.getBytes(StandardCharsets.UTF_8);
    }
}
