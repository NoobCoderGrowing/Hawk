package hawk.segment.core;

import lombok.Data;

@Data
public class FieldProperties {

    private short id;

    private String name;

    private boolean present;

    private short beIndex;

    private float weight;

    private short type;

    public FieldProperties(short id, String name, boolean present, short beIndex, float weight, short type) {
        this.id = id;
        this.name = name;
        this.present = present;
        this.beIndex = beIndex;
        this.weight = weight;
        this.type = type;
    }
}
