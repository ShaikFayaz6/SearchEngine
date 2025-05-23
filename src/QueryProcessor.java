import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class QueryProcessor {
    private final Indexer indexer;
    private final Map<Integer, Map<String, Integer>> relevanceJudgments;
    
    public QueryProcessor(Indexer indexer, String qrelsFile) throws IOException {
        this.indexer = indexer;
        this.relevanceJudgments = loadRelevanceJudgments(qrelsFile);
    }
    
    private Map<Integer, Map<String, Integer>> loadRelevanceJudgments(String qrelsFile) throws IOException {
        Map<Integer, Map<String, Integer>> judgments = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(qrelsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                
                int topic = Integer.parseInt(parts[0]);
                String docno = parts[2];
                int relevance = Integer.parseInt(parts[3]);
                
                judgments.computeIfAbsent(topic, k -> new HashMap<>())
                         .put(docno, relevance);
            }
        }
        return judgments;
    }
    
    public void processAllQueries(String topicsFile, String outputFile) throws IOException {
        List<Query> queries = parseQueries(topicsFile);
        List<QueryResult> titleResults = new ArrayList<>();
        List<QueryResult> titleDescResults = new ArrayList<>();
        List<QueryResult> titleNarrResults = new ArrayList<>();
        
        for (Query query : queries) {
            // Process with title only for main output
            List<DocumentScore> scores = processQuery(query.title, query.number);
            titleResults.addAll(createQueryResults(query.number, scores, "title"));
            
            // Process with title + description
            String titleDesc = query.title + " " + query.description;
            List<DocumentScore> titleDescScores = processQuery(titleDesc, query.number);
            titleDescResults.addAll(createQueryResults(query.number, titleDescScores, "title+desc"));
            
            // Process with title + narrative
            String titleNarr = query.title + " " + query.narrative;
            List<DocumentScore> titleNarrScores = processQuery(titleNarr, query.number);
            titleNarrResults.addAll(createQueryResults(query.number, titleNarrScores, "title+narr"));
        }
        
        // Save main output (title only)
        saveResults(titleResults, outputFile);
        //generatePerformanceReport(titleResults, "performance_report.txt");
        // Generate comprehensive performance report
        generateCombinedPerformanceReport(
            titleResults, 
            titleDescResults, 
            titleNarrResults, 
            "performance_report.txt"
        );

        System.out.println("Found " + queries.size() + " queries to process");
        for (Query q : queries) {
            System.out.println("Processing query #" + q.number + ": " + q.title);
        }
    }
    
    private List<QueryResult> createQueryResults(int topicNumber, List<DocumentScore> scores, 
                                               String queryType) {
        List<QueryResult> results = new ArrayList<>();
        int rank = 1;
        for (DocumentScore score : scores) {
            results.add(new QueryResult(
                topicNumber,
                score.docno,
                rank++,
                score.score,
                queryType,
                relevanceJudgments.getOrDefault(topicNumber, Collections.emptyMap())
                                 .getOrDefault(score.docno, 0)
            ));
        }
        return results;
    }
    
    public List<DocumentScore> processQuery(String queryText, int queryNumber) {
        if (queryText == null || queryText.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        // Tokenize and process query same as documents
        List<String> tokens = tokenize(queryText);
        Set<String> stopwords = loadStopwords("stopwordlist.txt");
        removeStopwords(tokens, stopwords);
        Porter stemmer = new Porter();
        tokens = stemTokens(tokens, stemmer);
        
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Calculate query vector (TF-IDF)
        Map<Integer, Double> queryVector = calculateQueryVector(tokens);
        
        if (queryVector.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Calculate cosine similarity with all documents
        return calculateDocumentScores(queryVector);
    }
    
    private Map<Integer, Double> calculateQueryVector(List<String> tokens) {
        Map<Integer, Double> queryVector = new HashMap<>();
        Map<String, Integer> queryTermCounts = new HashMap<>();
        
        // Count term frequencies in query
        for (String token : tokens) {
            queryTermCounts.put(token, queryTermCounts.getOrDefault(token, 0) + 1);
        }
        
        int queryLength = tokens.size();
        for (Map.Entry<String, Integer> entry : queryTermCounts.entrySet()) {
            Integer wordId = indexer.getWordDictionary().get(entry.getKey());
            if (wordId != null) {
                double tf = 0.5 + 0.5 * ((double)entry.getValue() / queryLength);
                double idf = indexer.getIdf(wordId);
                queryVector.put(wordId, tf * idf);
            }
        }
        return queryVector;
    }
    
    private List<DocumentScore> calculateDocumentScores(Map<Integer, Double> queryVector) {
        List<DocumentScore> scores = new ArrayList<>();
        double queryNorm = calculateVectorNorm(queryVector);
        
        if (queryNorm == 0) {
            return scores;
        }
        
        for (Map.Entry<Integer, String> docEntry : indexer.getIdToFileMap().entrySet()) {
            int docId = docEntry.getKey();
            String docno = docEntry.getValue();
            
            Map<Integer, Double> docVector = indexer.getDocumentVector(docId);
            double docNorm = calculateVectorNorm(docVector);
            
            if (docNorm == 0) {
                continue;
            }
            
            double cosine = cosineSimilarity(queryVector, docVector, queryNorm, docNorm);
            if (cosine > 0) {
                scores.add(new DocumentScore(docno, cosine));
            }
        }
        
        // Sort by score descending
        scores.sort((a, b) -> Double.compare(b.score, a.score));
        return scores;
    }
    
    // private Map<Integer, Double> calculateDocumentVector(int docId) {
    //     Map<Integer, Double> vector = new HashMap<>();
    //     Map<Integer, Integer> wordCounts = indexer.getForwardIndex().getOrDefault(docId, Collections.emptyMap());
    //     int docLength = wordCounts.values().stream().mapToInt(Integer::intValue).sum();
        
    //     for (Map.Entry<Integer, Integer> entry : wordCounts.entrySet()) {
    //         int wordId = entry.getKey();
    //         double tf = (double)entry.getValue() / docLength;
    //         double idf = indexer.getIdf(wordId);
    //         vector.put(wordId, tf * idf);
    //     }
        
    //     return vector;
    // }
    
    private double cosineSimilarity(Map<Integer, Double> v1, Map<Integer, Double> v2, 
                                  double norm1, double norm2) {
        if (norm1 == 0 || norm2 == 0) return 0.0;
        
        double dotProduct = 0.0;
        for (Integer term : v1.keySet()) {
            if (v2.containsKey(term)) {
                dotProduct += v1.get(term) * v2.get(term);
            }
        }
        
        return dotProduct / (norm1 * norm2);
    }
    
    private double calculateVectorNorm(Map<Integer, Double> vector) {
        double sum = 0.0;
        for (double value : vector.values()) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }
    
    public static class Query {
        int number;
        String title;
        String description;
        String narrative;
        
        public Query(int number, String title, String description, String narrative) {
            this.number = number;
            this.title = title;
            this.description = description;
            this.narrative = narrative;
        }
    }
    
    private static class DocumentScore {
        String docno;
        double score;
        
        public DocumentScore(String docno, double score) {
            this.docno = docno;
            this.score = score;
        }
    }
    
    public static class QueryResult {
        int topic;
        String docno;
        int rank;
        double score;
        String queryType;
        int relevance;
        
        public QueryResult(int topic, String docno, int rank, double score, 
                         String queryType, int relevance) {
            this.topic = topic;
            this.docno = docno;
            this.rank = rank;
            this.score = score;
            this.queryType = queryType;
            this.relevance = relevance;
        }
        
        public String toOutputFormat() {
            return String.format("%-8d%-20s%-8d%-10.6f", 
                               topic, docno, rank, score);
        }
    }
    
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.split("[^a-zA-Z]+")) {
            if (!token.isEmpty()) {
                tokens.add(token.toLowerCase());
            }
        }
        return tokens;
    }
    
    private void removeStopwords(List<String> words, Set<String> stopwords) {
        words.removeIf(stopwords::contains);
    }
    
    private Set<String> loadStopwords(String filePath) {
        Set<String> stopwords = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stopwords.add(line.trim());
            }
        } catch (IOException e) {
            System.err.println("Error loading stopwords: " + e.getMessage());
        }
        return stopwords;
    }
    
    private List<String> stemTokens(List<String> tokens, Porter stemmer) {
        List<String> stemmedTokens = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            String stemmedToken = stemmer.stripAffixes(token);
            stemmedTokens.add(stemmedToken);
        }
        return stemmedTokens;
    }
    
    private List<Query> parseQueries(String topicsFile) throws IOException {
        List<Query> queries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(topicsFile))) {
            String line;
            int currentNumber = 0;
            String currentTitle = "";
            StringBuilder currentDesc = new StringBuilder();
            StringBuilder currentNarr = new StringBuilder();
            boolean inDesc = false;
            boolean inNarr = false;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                if (line.startsWith("<num>")) {
                    String numStr = line.replaceAll("<num>|</num>", "").trim();
                    currentNumber = Integer.parseInt(numStr.split(":")[1].trim());
                } 
                else if (line.startsWith("<title>")) {
                    currentTitle = line.replaceAll("<title>|</title>", "").trim();
                } 
                else if (line.startsWith("<desc>")) {
                    inDesc = true;
                    currentDesc.setLength(0);
                } 
                else if (line.startsWith("<narr>")) {
                    inNarr = true;
                    currentNarr.setLength(0);
                } 
                else if (line.startsWith("</top>")) {
                    queries.add(new Query(
                        currentNumber,
                        currentTitle,
                        currentDesc.toString().trim(),
                        currentNarr.toString().trim()
                    ));
                    // Reset for next query
                    currentTitle = "";
                    currentDesc.setLength(0);
                    currentNarr.setLength(0);
                    inDesc = false;
                    inNarr = false;
                }
                else if (inDesc) {
                    currentDesc.append(line).append(" ");
                }
                else if (inNarr) {
                    currentNarr.append(line).append(" ");
                }
            }
        }
        return queries;
    }   
    public void saveResults(List<QueryResult> results, String outputFile) throws IOException {
        // Create the file if it doesn't exist
        File output = new File(outputFile);
        if (!output.exists()) {
            output.createNewFile();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
            // Group by topic
            Map<Integer, List<QueryResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(r -> r.topic));

            // Sort by topic number and then by rank
            grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    entry.getValue().stream()
                        .sorted(Comparator.comparingInt(r -> r.rank))
                        .forEach(result -> {
                            try {
                                writer.write(result.toOutputFormat());
                                writer.newLine();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                });
        }

        System.out.println("Saving " + results.size() + " results to " + outputFile);
if (results.isEmpty()) {
    System.out.println("Warning: No results found for any queries");
}
    }

    private void generateCombinedPerformanceReport(
        List<QueryResult> titleResults,
        List<QueryResult> titleDescResults,
        List<QueryResult> titleNarrResults,
        String reportFile) throws IOException {
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(reportFile))) {
            writer.write("Query Performance Comparison Report\n");
            writer.write("==================================\n\n");
            
            // Create a map to organize results by topic
            Map<Integer, Map<String, List<QueryResult>>> topicMap = new TreeMap<>();
            
            // Process each query type
            processQueryResults(topicMap, titleResults, "title");
            processQueryResults(topicMap, titleDescResults, "title+desc");
            processQueryResults(topicMap, titleNarrResults, "title+narr");
            
            // Write report for each topic
            for (Map.Entry<Integer, Map<String, List<QueryResult>>> topicEntry : topicMap.entrySet()) {
                int topic = topicEntry.getKey();
                writer.write(String.format("Topic %d:\n", topic));
                
                // Write metrics for each query type
                writeQueryTypeMetrics(writer, topicEntry.getValue(), "title", topic);
                writeQueryTypeMetrics(writer, topicEntry.getValue(), "title+desc", topic);
                writeQueryTypeMetrics(writer, topicEntry.getValue(), "title+narr", topic);
                
                writer.write("\n");
            }
        }
    }
    
    // Helper method to organize results by topic and query type
    private void processQueryResults(
        Map<Integer, Map<String, List<QueryResult>>> topicMap,
        List<QueryResult> results,
        String queryType) {
        
        for (QueryResult result : results) {
            topicMap
                .computeIfAbsent(result.topic, k -> new HashMap<>())
                .computeIfAbsent(queryType, k -> new ArrayList<>())
                .add(result);
        }
    }
    
    // Helper method to write metrics for a specific query type
    private void writeQueryTypeMetrics(
        BufferedWriter writer,
        Map<String, List<QueryResult>> queryTypeMap,
        String queryType,
        int topic) throws IOException {
        
        writer.write("  " + queryType + ":\n");
        List<QueryResult> results = queryTypeMap.getOrDefault(queryType, Collections.emptyList());
        
        if (results.isEmpty()) {
            writer.write("    No results found\n\n");
            return;
        }
        
        int relevantRetrieved = (int) results.stream()
            .filter(r -> r.relevance == 1)
            .count();
        
        int totalRetrieved = results.size();
        
        int totalRelevant = relevanceJudgments.getOrDefault(topic, Collections.emptyMap())
            .values().stream()
            .mapToInt(Integer::intValue)
            .sum();
        
        double precision = totalRetrieved > 0 ? 
            (double) relevantRetrieved / totalRetrieved : 0;
        double recall = totalRelevant > 0 ?
            (double) relevantRetrieved / totalRelevant : 0;
        
        writer.write(String.format("    Precision: %.4f\n", precision));
        writer.write(String.format("    Recall:    %.4f\n", recall));
        writer.write(String.format("    Relevant Retrieved: %d/%d\n\n", 
            relevantRetrieved, totalRelevant));
    }

    

    /////------------------------------------------------------------------------------
//     private void generateCombinedPerformanceReport(List<QueryResult> titleResults, List<QueryResult> titleDescResults, List<QueryResult> titleNarrResults, String reportFile) throws IOException {
//     try (BufferedWriter writer = new BufferedWriter(new FileWriter(reportFile))) {
//         writer.write("Query Performance Comparison Report\n");
//         writer.write("==================================\n\n");
        
//         // Group all results by topic
//         Map<Integer, List<QueryResult>> titleMap = titleResults.stream().collect(Collectors.groupingBy(r -> r.topic));
//         Map<Integer, List<QueryResult>> titleDescMap = titleDescResults.stream().collect(Collectors.groupingBy(r -> r.topic));    
//         Map<Integer, List<QueryResult>> titleNarrMap = titleNarrResults.stream().collect(Collectors.groupingBy(r -> r.topic));
        
//         // Calculate and compare metrics for each query
//         for (int topic : titleMap.keySet()) {
//             writer.write(String.format("Topic %d:\n", topic));
            
//             // Title only
//             writer.write("  Title Only:\n");
//             writeMetrics(writer, titleMap.get(topic), topic);
            
//             // Title + Description
//             writer.write("  Title + Description:\n");
//             writeMetrics(writer, titleDescMap.get(topic), topic);
            
//             // Title + Narrative
//             writer.write("  Title + Narrative:\n");
//             writeMetrics(writer, titleNarrMap.get(topic), topic);
            
//             writer.write("\n");
//         }
//     }
// }

// private void writeMetrics(BufferedWriter writer, List<QueryResult> results, int topic) throws IOException {
//     int relevantRetrieved = (int)results.stream()
//         .filter(r -> r.relevance == 1)
//         .count();
    
//     int totalRetrieved = results.size();
    
//     int totalRelevant = relevanceJudgments.getOrDefault(topic, Collections.emptyMap())
//         .values()
//         .stream()
//         .mapToInt(Integer::intValue)
//         .sum();
    
//     double precision = totalRetrieved > 0 ? 
//         (double)relevantRetrieved / totalRetrieved : 0;
//     double recall = totalRelevant > 0 ?
//         (double)relevantRetrieved / totalRelevant : 0;
//     writer.write(String.format("Topic %d:\n", topic));
//     writer.write(String.format("    Precision: %.4f\n", precision));
//     writer.write(String.format("    Recall:    %.4f\n", recall));
//     writer.write(String.format("    Relevant Retrieved: %d/%d\n\n", relevantRetrieved, totalRelevant));
// }

/////------------------------------------------------------------------------------
    
    // public void generatePerformanceReport(List<QueryResult> results, String reportFile) throws IOException {
    //     try (BufferedWriter writer = new BufferedWriter(new FileWriter(reportFile))) {
    //         // Group by query type and topic
    //         Map<String, List<QueryResult>> grouped = results.stream()
    //             .collect(Collectors.groupingBy(r -> r.queryType + "_" + r.topic));
            
    //         writer.write("Query Performance Report\n");
    //         writer.write("=======================\n\n");
            
    //         for (Map.Entry<String, List<QueryResult>> entry : grouped.entrySet()) {
    //             String[] parts = entry.getKey().split("_");
    //             String queryType = parts[0];
    //             int topic = Integer.parseInt(parts[1]);
                
    //             List<QueryResult> topicResults = entry.getValue();
                
    //             // Calculate precision and recall
    //             int relevantRetrieved = (int)topicResults.stream()
    //                 .filter(r -> r.relevance == 1)
    //                 .count();
                
    //             int totalRetrieved = topicResults.size();
                
    //             int totalRelevant = relevanceJudgments.getOrDefault(topic, Collections.emptyMap())
    //                 .values()
    //                 .stream()
    //                 .mapToInt(Integer::intValue)
    //                 .sum();
                
    //             double precision = totalRetrieved > 0 ? 
    //                 (double)relevantRetrieved / totalRetrieved : 0;
    //             double recall = totalRelevant > 0 ?
    //                 (double)relevantRetrieved / totalRelevant : 0;
                
    //             writer.write(String.format("Topic %d (%s):\n", topic, queryType));
    //             writer.write(String.format("  Precision: %.4f\n", precision));
    //             writer.write(String.format("  Recall:    %.4f\n", recall));
    //             writer.write(String.format("  Relevant Retrieved: %d/%d\n", relevantRetrieved, totalRelevant));
    //             writer.write("\n");
    //         }
    //     }
    // }
}