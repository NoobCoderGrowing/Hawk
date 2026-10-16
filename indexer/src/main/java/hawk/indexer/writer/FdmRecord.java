package hawk.indexer.writer;

import lombok.Data;

import java.util.List;

@Data
public class FdmRecord {

    private byte[] field;

    private byte type;

    private int fieldLengthSum;

    private int docCount;

    /**
     * 这个字段当初用了哪些 tokenizer（{@code TokenizerRegistry} 的名字），
     * **只有真被分词的字段才非空** —— 数值字段、只存原文的字段都是空列表。
     *
     * <p>段合并时要逐条搬过去，而且两侧同名记录的这一项必须**相等**，
     * 见 {@link IndexMerger#writeFdmRecord(FdmRecord, FdmRecord, java.nio.channels.FileChannel)}。
     */
    private List<String> tokenizers;

    public FdmRecord(byte[] field, byte type, int fieldLengthSum, int docCount, List<String> tokenizers) {
        this.field = field;
        this.type = type;
        this.fieldLengthSum = fieldLengthSum;
        this.docCount = docCount;
        this.tokenizers = tokenizers;
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
