package writer;

import lombok.Data;

@Data
public class FdmRecord {

    private byte[] field;

    private byte type;

    private int fieldLengthSum;

    private int docCount;

    public FdmRecord(byte[] field, byte type, int fieldLengthSum, int docCount) {
        this.field = field;
        this.type = type;
        this.fieldLengthSum = fieldLengthSum;
        this.docCount = docCount;
    }

    public int compareTo(FdmRecord b){
        byte[] bField = b.getField();
        for (int i = 0; i < field.length && i < bField.length; i++) {
            int aFieldByte = (field[i] & 0xff);
            int bFieldByte = (bField[i] & 0xff);
            if(aFieldByte != bFieldByte) {
                return aFieldByte - bFieldByte;
            }
        }
        return field.length - bField.length;
    }
}
