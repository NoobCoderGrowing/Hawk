package hawk.benchmark;

import hawk.segment.core.Term;
import hawk.segment.core.anlyzer.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;

final class HawkTokenizer extends Tokenizer {

    private final Analyzer hawkAnalyzer;

    private final String fieldName;

    private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);

    private final PositionIncrementAttribute positionIncrementAttribute =
            addAttribute(PositionIncrementAttribute.class);

    private Iterator<Term> tokenIterator;

    private int lastPosition = -1;

    HawkTokenizer(Analyzer hawkAnalyzer, String fieldName) {
        this.hawkAnalyzer = hawkAnalyzer;
        this.fieldName = fieldName;
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (tokenIterator == null) {
            tokenIterator = analyzeInput().iterator();
            lastPosition = -1;
        }
        if (!tokenIterator.hasNext()) {
            return false;
        }

        clearAttributes();
        Term term = tokenIterator.next();
        termAttribute.append(term.getValue());

        int positionIncrement = lastPosition < 0
                ? term.getPos() + 1
                : term.getPos() - lastPosition;
        if (positionIncrement < 0) {
            positionIncrement = 0;
        }
        positionIncrementAttribute.setPositionIncrement(positionIncrement);
        lastPosition = term.getPos();
        return true;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        tokenIterator = null;
        lastPosition = -1;
    }

    private ArrayList<Term> analyzeInput() throws IOException {
        String text = readAllInput();
        HashSet<Term> terms = hawkAnalyzer.anlyze(text, fieldName);
        ArrayList<Term> sortedTerms = new ArrayList<>(terms);
        sortedTerms.sort(Comparator.comparingInt(Term::getPos).thenComparing(Term::getValue));
        return sortedTerms;
    }

    private String readAllInput() throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[512];
        int read;
        while ((read = input.read(buffer)) != -1) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }
}
