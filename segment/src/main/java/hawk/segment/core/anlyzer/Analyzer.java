package hawk.segment.core.anlyzer;

import hawk.segment.core.Term;

import java.util.HashSet;
import java.util.List;

public interface Analyzer {
    public HashSet<Term> anlyze(String value, String fieldName);
}
