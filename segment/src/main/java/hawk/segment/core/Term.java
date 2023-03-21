package hawk.segment.core;

import lombok.Data;

import java.util.Objects;

@Data
public class Term {

    private String value;

    private int pos;

    private String fieldName;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Term term = (Term) o;
        return pos == term.pos && Objects.equals(value, term.value) && Objects.equals(fieldName,
                term.fieldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, pos, fieldName);
    }
}
