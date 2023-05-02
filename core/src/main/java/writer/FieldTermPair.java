package writer;

import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;


@Data
public class FieldTermPair {

    private byte[] field;

    private byte[] term;

    public FieldTermPair(byte[] field, byte[] term) {
        this.field = field;
        this.term = term;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FieldTermPair that = (FieldTermPair) o;
        return Arrays.equals(field, that.field) && Arrays.equals(term, that.term);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(field);
        result = 31 * result + Arrays.hashCode(term);
        return result;
    }

    public int compareTo(FieldTermPair b){
        byte[] bField = b.getField();
        byte[] bTerm = b.getTerm();
        for (int i = 0; i < field.length && i < bField.length; i++) {
            int aFieldByte = (field[i] & 0xff);
            int bFieldByte = (bField[i] & 0xff);
            if(aFieldByte != bFieldByte) {
                return aFieldByte - bFieldByte;
            }
        }
        if(field.length != bField.length){
            return field.length - bField.length;
        }else{
            for (int i = 0; i < term.length && i < bTerm.length; i++) {
                int aTermByte = (term[i] & 0xff);
                int bTermByte = (bTerm[i] & 0xff);
                if(aTermByte != bTermByte) {
                    return aTermByte - bTermByte;
                }
            }
            return term.length - bTerm.length;
        }
    }

    public static void main(String[] args) {
        FieldTermPair a = new FieldTermPair("今天".getBytes(StandardCharsets.UTF_8), "天气1".getBytes(StandardCharsets.UTF_8));
        FieldTermPair b = new FieldTermPair("今天".getBytes(StandardCharsets.UTF_8), "天气1".getBytes(StandardCharsets.UTF_8));
        System.out.println(a.compareTo(b));
    }
}
