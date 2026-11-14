package ru.it_spectrum.ai.rag.mcp.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.tartarus.snowball.ext.RussianStemmer;

import java.io.Reader;
import java.util.Map;

/**
 * Shared indexing, query and highlighting analysis.
 *
 * <p>Every text is analyzed twice: with Russian stemming in the main field and with English
 * stemming in its {@code _en} twin, so word forms match in both languages without declaring a
 * language per document. The Russian chain maps {@code ё} to {@code е} before tokenizing.
 *
 * <p>Modeled on mail-mcp-server {@code index/MailAnalyzers} @ {@code 0.1.1}; user word-form rules
 * are not ported.
 */
public final class Analyzers {

    private static final Analyzer ANALYZER = create();

    private Analyzers() {
    }

    public static Analyzer analyzer() {
        return ANALYZER;
    }

    private static Analyzer create() {
        var stops = CharArraySet.copy(EnglishAnalyzer.getDefaultStopSet());
        stops.addAll(RussianAnalyzer.getDefaultStopSet());
        var english = new EnglishAnalyzer(stops);

        var yo = new NormalizeCharMap.Builder();
        yo.add("ё", "е");
        yo.add("Ё", "Е");
        NormalizeCharMap yoMap = yo.build();
        Analyzer russian = new Analyzer() {
            @Override
            protected Reader initReader(String fieldName, Reader reader) {
                return new MappingCharFilter(yoMap, reader);
            }

            @Override
            protected Reader initReaderForNormalization(String fieldName, Reader reader) {
                return new MappingCharFilter(yoMap, reader);
            }

            @Override
            protected TokenStreamComponents createComponents(String field) {
                var tokenizer = new StandardTokenizer();
                TokenStream stream = new StopFilter(new LowerCaseFilter(tokenizer),
                        RussianAnalyzer.getDefaultStopSet());
                return new TokenStreamComponents(tokenizer, new SnowballFilter(stream, new RussianStemmer()));
            }

            @Override
            protected TokenStream normalize(String field, TokenStream input) {
                return new LowerCaseFilter(input);
            }
        };
        return new PerFieldAnalyzerWrapper(russian, Map.of(
                IndexFields.TEXT_EN, english,
                IndexFields.TITLE_EN, english));
    }
}
