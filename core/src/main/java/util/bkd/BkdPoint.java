package util.bkd;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BkdPoint {

    private int docId;

    private long sortableValue;
}
