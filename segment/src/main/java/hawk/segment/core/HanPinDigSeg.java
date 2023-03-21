package hawk.segment.core;

import lombok.Data;

import java.util.ArrayList;

@Data
public class HanPinDigSeg {

    private ArrayList<Phrase> hanZiList;

    private ArrayList<Phrase> pinYinList;

    private ArrayList<Phrase> digitList;

    public HanPinDigSeg(){
        this.hanZiList = new ArrayList<Phrase>();
        this.pinYinList = new ArrayList<Phrase>();
        this.digitList = new ArrayList<Phrase>();
    }
}
