package hawk.segment.core.anlyzer;

import hawk.segment.core.Term;

import java.util.HashSet;

public interface Analyzer {
    public HashSet<Term> anlyze(String value, String fieldName);
}
