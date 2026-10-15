package hawk.segment.core.anlyzer;

import hawk.segment.core.Term;

import java.util.HashSet;

public interface Tokenizer {
    public HashSet<Term> tokenize(String value, String fieldName);
}
