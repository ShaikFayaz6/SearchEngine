//Author: FayazShaik
//Date: 02/26/2025
import java.io.*;
import java.util.*;

public class TextParser {
    public Map<String, Integer> getWordDictionary() {
        return wordDictionary;
    }
    
    public Map<String, Integer> getFileDictionary() {
        return fileDictionary;
    }
    private Map<String, Integer> wordDictionary = new HashMap<>();
    private Map<String, Integer> fileDictionary = new HashMap<>();
    private Set<String> allWords = new HashSet<>();

    // Tokenize the text into words with strict empty string handling
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String[] splitTokens = text.split("[^a-zA-Z]+");
        for (String token : splitTokens) {
            if (token != null && !token.trim().isEmpty()) {
                tokens.add(token.toLowerCase());
            }
        }
        return tokens;
    }

    // Remove stopwords from the token list
    public void removeStopwords(List<String> words, Set<String> stopwords) {
        words.removeIf(stopwords::contains);
    }

    // Load stopwords from the file
    private Set<String> loadStopwords(String filePath) {
        Set<String> stopwords = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stopwords.add(line.trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return stopwords;
    }

    // Stem tokens with validation against empty strings
    private List<String> stemTokens(List<String> tokens) {
        Porter stemmer = new Porter();
        List<String> stemmedTokens = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            String stemmedToken = stemmer.stripAffixes(token);
            if (stemmedToken != null && !stemmedToken.trim().isEmpty()) {
                stemmedTokens.add(stemmedToken);
            }
        }
        return stemmedTokens;
    }

    // Update word dictionary with validation
    private void updateWordDictionary(List<String> tokens) {
        for (String token : tokens) {
            if (token != null && !token.trim().isEmpty() && !allWords.contains(token)) {
                allWords.add(token);
            }
        }
    }

    // Assign word IDs in alphabetical order
    private void assignWordIds() {
        List<String> sortedWords = new ArrayList<>(allWords);
        sortedWords.sort(String.CASE_INSENSITIVE_ORDER);
        
        int id = 1;
        for (String word : sortedWords) {
            if (word != null && !word.trim().isEmpty()) {
                wordDictionary.put(word, id++);
            }
        }
    }

    // Extract document number from DOCNO (e.g., "FT911-3323" → 3323)
    private int extractDocNumber(String docNo) {
        try {
            String[] parts = docNo.split("-");
            if (parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        return -1; // fallback if format is wrong
    }

    // Parse a single TREC file
    private void parseTrecFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            StringBuilder docText = new StringBuilder();
            String docNo = "";
            Set<String> stopwords = loadStopwords("stopwordlist.txt");

            while ((line = reader.readLine()) != null) {
                if (line.contains("<DOCNO>")) {
                    docNo = line.replace("<DOCNO>", "").replace("</DOCNO>", "").trim();
                    if (!fileDictionary.containsKey(docNo)) {
                        int docNumber = extractDocNumber(docNo);
                        if (docNumber != -1) {
                            fileDictionary.put(docNo, docNumber);
                        }
                    }
                } else if (line.contains("<TEXT>")) {
                    while (!(line = reader.readLine()).contains("</TEXT>")) {
                        docText.append(line).append(" ");
                    }

                    if (docText.length() > 0) {
                        List<String> tokens = tokenize(docText.toString());
                        removeStopwords(tokens, stopwords);
                        tokens = stemTokens(tokens);
                        updateWordDictionary(tokens);
                    }
                    docText.setLength(0);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Parse all TREC files in a folder
    public void parseTrecFolder(String folderPath) {
        File folder = new File(folderPath);
        File[] files = folder.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".txt")) {
                    parseTrecFile(file);
                }
            }
            assignWordIds();
        } else {
            System.out.println("The folder is empty or does not exist.");
        }
    }

    // Save the output with proper sorting
    public void saveOutput(String outputFilePath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
            // Save words alphabetically
            wordDictionary.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    try {
                        writer.write(entry.getKey() + "\t" + entry.getValue() + "\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            
            // Save documents by ID
            fileDictionary.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> {
                    try {
                        writer.write(entry.getKey() + "\t" + entry.getValue() + "\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Document parsing and indexing
        long parsingStartTime = System.currentTimeMillis();
        TextParser parser = new TextParser();
        parser.parseTrecFolder("ft911");
        parser.saveOutput("parser_output.txt");
        System.out.println("Parsing complete. Output saved to parser_output.txt.");
        long parsingEndTime = System.currentTimeMillis();
       
        // Index building
        long indexingStartTime = System.currentTimeMillis();
        Indexer indexer = new Indexer(parser.getWordDictionary(), parser.getFileDictionary());
        indexer.buildIndexes("ft911");
        try {
            indexer.saveForwardIndex("forward_index.txt");
            indexer.saveInvertedIndex("inverted_index.txt");
            indexer.saveWordIdMappings("word_ids.txt");
            indexer.saveDocIdMappings("doc_ids.txt");
        } catch (IOException e) {
            e.printStackTrace();
        }
        long indexingEndTime = System.currentTimeMillis();
        
        System.out.println("Time taken to complete Parsing is: " + 
            (parsingEndTime - parsingStartTime) / 1000.0 + " seconds");
        System.out.println("Time taken to complete the Indexing is: " + 
            (indexingEndTime - indexingStartTime) / 1000.0 + " seconds");
        System.out.println("\nTotal time: " + 
            (indexingEndTime - parsingStartTime) / 1000.0 + " seconds");

        // Query processing
        long queryStartTime = System.currentTimeMillis();
        try {
            System.out.println("\nStarting query processing...");
            System.out.println("Current directory: " + System.getProperty("user.dir"));
            System.out.println("main.qrels exists: " + new File("main.qrels").exists());
            System.out.println("topics.txt exists: " + new File("topics.txt").exists());
            
            QueryProcessor queryProcessor = new QueryProcessor(indexer, "main.qrels");
            queryProcessor.processAllQueries("topics.txt", "vsm_output.txt");
            System.out.println("Query processing completed successfully.");
        } catch (IOException e) {
            System.err.println("Error during query processing:");
            e.printStackTrace();
        }
        long queryEndTime = System.currentTimeMillis();
        System.out.println("Time taken for query processing: " + 
            (queryEndTime - queryStartTime) / 1000.0 + " seconds");

        // Interactive search
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\nEnter a term to search (or 'quit' to exit): ");
            String term = scanner.nextLine().trim();
            if (term.equalsIgnoreCase("quit")) {
                break;
            }
            indexer.searchTerm(term);
        }
        scanner.close();
    }
}