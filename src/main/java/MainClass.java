import edu.wayne.cs.severe.ir4se.processor.controllers.RetrievalEvaluator;
import edu.wayne.cs.severe.ir4se.processor.controllers.impl.DefaultRetrievalEvaluator;
import edu.wayne.cs.severe.ir4se.processor.controllers.impl.RAMRetrievalIndexer;
import edu.wayne.cs.severe.ir4se.processor.controllers.impl.lucene.LuceneRetrievalSearcher;
import edu.wayne.cs.severe.ir4se.processor.entity.Query;
import edu.wayne.cs.severe.ir4se.processor.entity.RelJudgment;
import edu.wayne.cs.severe.ir4se.processor.entity.RetrievalDoc;
import edu.wayne.cs.severe.ir4se.processor.entity.RetrievalStats;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.store.Directory;
import seers.textanalyzer.PreprocessingOptionsParser;
import seers.textanalyzer.TextProcessor;
import seers.textanalyzer.entity.Sentence;
import seers.textanalyzer.entity.Token;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MainClass {

    private static String preprocessText(String text, List<String> stopWords) {

        String[] preprocessingOptions = {PreprocessingOptionsParser.CAMEL_CASE_SPLITTING,
                PreprocessingOptionsParser.NUMBERS_REMOVAL, PreprocessingOptionsParser.PUNCTUATION_REMOVAL,
                PreprocessingOptionsParser.SHORT_TOKENS_REMOVAL + " 3",
                PreprocessingOptionsParser.SPECIAL_CHARS_REMOVAL};

        if (StringUtils.isBlank(text)) return null;
        List<Sentence> prepSentences = TextProcessor.preprocessText(text, stopWords, preprocessingOptions);
        List<Token> allTokens = TextProcessor.getAllTokens(prepSentences);
        return allTokens.stream().map(Token::getLemma).collect(Collectors.joining(" "));
    }

    //run this class/method using project directory as the working directory for the program
    public static void main(String[] args) throws Exception {

        //read stop words
        String stopWordsPath = "src/main/resources/java-keywords-bugs.txt";
        List<String> stopWords = TextProcessor.readStopWords(stopWordsPath);

        //source code files/classes
        List<String> codeFileContent = Arrays.asList(
                //one class/file
                "public class MainClass {\n" +
                        "\n" +
                        "    public static void main(String[] args) throws Exception{" +
                        "//this is the functionality of the method" +
                        "}" +
                        "}",

                //another class file
                "public class FeatureQueryExecutor implements Supplier<MutableTriple<String, String, LinkedHashMap>> " +
                        "{\n" +
                        "    private final String system;\n" +
                        "    private final List<HashMap> bugReports;\n" +
                        "    private Map<Integer, List<HashMap>> bugReportsForPrediction;\n" +
                        "    private final int queryIndex;\n" +
                        "    private final List<QueryFormulator> formulators;\n" +
                        "    private final Path commitFolderPath;" +
                        "}"
        );

        //bug report
        String bugReportContent = "I got a crash when trying to get the features";

        //--------------------------------------------------------------

        //preprocess files
        List<String> preprocessedCodeDocuments =
                codeFileContent.stream().map(text -> preprocessText(text, stopWords))
                        .collect(Collectors.toList());
        //build corpus
        List<RetrievalDoc> corpus = IntStream.range(0, preprocessedCodeDocuments.size())
                .mapToObj(i -> {
                    String docText = preprocessedCodeDocuments.get(i);
                    if (StringUtils.isBlank(docText)) return null;
                    int docId = i;
                    String docName = "file_name.java";
                    return new RetrievalDoc(docId, docText, docName);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        //-------------------------------------
        //build query

        String preprocessedBugReport = preprocessText(bugReportContent, stopWords);
        Query query = new Query(1, preprocessedBugReport);

        //-------------------------------------

        System.out.print("Corpus = ");
        System.out.println(corpus);

        //evaluator creation, for computing effectiveness metrics
        RetrievalEvaluator evaluator = new DefaultRetrievalEvaluator();

        //this map will store the evaluation metric values for all the queries
        Map<Query, List<Double>> allQueryResults = new HashMap<>();

        //index the corpus and store it in RAM
        //Use the class DefaultRetrievalIndexer to store the index in disk
        try (Directory index = new RAMRetrievalIndexer().buildIndex(corpus, null)) {

            //the searcher
            LuceneRetrievalSearcher searcher = new LuceneRetrievalSearcher(index, null);

            //search
            List<RetrievalDoc> searchResults = searcher.searchQuery(query);

            //get results from the corpus so that we can print their content
            List<RetrievalDoc> resultsFromCorpus =
                    corpus.stream().filter(searchResults::contains).collect(Collectors.toList());

            resultsFromCorpus.forEach(d ->{
                System.out.print("Search result = ");
                System.out.print(d.getDocId());
                System.out.print(": ");
                System.out.println(d.getDocText());
            } );

            //expected results for the query
            RelJudgment expectedSearchResults = new RelJudgment();
            expectedSearchResults.setRelevantDocs(List.of(corpus.get(1)));

            //compute metrics (see what each position means in the resulting list)
            List<Double> evalResults = evaluator.evaluateRelJudgment(expectedSearchResults, searchResults);
            System.out.print("Query evaluation results = ");
            System.out.println(evalResults);

            // INDEX 0: rank of the first relevant and retrieved doc
            // INDEX 1: reciprocal rank
            // INDEX 2: # of true positives
            // INDEX 3: precision
            // INDEX 4: # of false negatives
            // INDEX 5: recall
            // INDEX 6: f1 score
            // INDEX 7: average precision
            // INDEX 8: any relevant doc in top 1?
            // INDEX 9: any relevant doc in top 5?
            // INDEX 10:any relevant doc in top 10?

            // --------------------------------------------------

            //add the metrics to the map
            allQueryResults.put(query, evalResults);

        }

        //evaluate the overall effectiveness
        RetrievalStats overallEvalResults = evaluator.evaluateModel(allQueryResults);

        System.out.print("MRR = ");
        System.out.println(overallEvalResults.getMeanRecipRank());
    }

}
