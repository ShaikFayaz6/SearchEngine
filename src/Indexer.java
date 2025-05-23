    import java.io.*;
    import java.util.*;
    import java.util.zip.GZIPOutputStream;

    public class Indexer {
        // Stores words and their unique IDs (word -> ID)
        private Map<String, Integer> wordDictionary;
        // Stores document names and their unique IDs (filename -> ID)
        private Map<String, Integer> fileDictionary;
        // Reverse mapping to get word from ID (ID -> word)
        private Map<Integer, String> idToWordMap;
        // Reverse mapping to get filename from ID (ID -> filename)
        private Map<Integer, String> idToFileMap;
        
        // Forward index stores documents and their word frequencies (docID -> (wordID -> count))
        private Map<Integer, Map<Integer, Integer>> forwardIndex;
        
        // Inverted index stores words and which documents contain them (wordID -> (docID -> count))
        private Map<Integer, Map<Integer, Integer>> invertedIndex;

        
    public Map<String, Integer> getWordDictionary() {
        return this.wordDictionary;
    }

    public Map<Integer, String> getIdToFileMap() {
        return this.idToFileMap;
    }

    public Map<Integer, Map<Integer, Integer>> getForwardIndex() {
        return this.forwardIndex;
    }

    public Map<Integer, Map<Integer, Integer>> getInvertedIndex() {
        return this.invertedIndex;
    }


        // Constructor takes existing word and file dictionaries
        public Indexer(Map<String, Integer> wordDict, Map<String, Integer> fileDict) {
            this.wordDictionary = wordDict;
            this.fileDictionary = fileDict;
            this.forwardIndex = new HashMap<>();
            this.invertedIndex = new HashMap<>();
            
            // Create reverse lookup maps for easier debugging and searching
            this.idToWordMap = new HashMap<>();
            for (Map.Entry<String, Integer> entry : wordDict.entrySet()) {
                idToWordMap.put(entry.getValue(), entry.getKey());
            }
            
            this.idToFileMap = new HashMap<>();
            for (Map.Entry<String, Integer> entry : fileDict.entrySet()) {
                idToFileMap.put(entry.getValue(), entry.getKey());
            }
        }

        // Main method to process all files in a folder and build indexes
        public void buildIndexes(String folderPath) {
            File folder = new File(folderPath);
            File[] files = folder.listFiles();

            if (files != null) {
                System.out.println("Processing " + files.length + " files...");
                int count = 0;
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".txt")) {
                        processDocument(file);
                        count++;
                        // Print progress every 100 files
                        if (count % 100 == 0) {
                            System.out.println("Processed " + count + " files");
                        }
                    }
                }
                System.out.println("Finished processing " + count + " files");
            }
            // Double check that both indexes are consistent
            validateIndexConsistency();
        }

        // Makes sure forward and inverted indexes match each other
        private void validateIndexConsistency() {
            System.out.println("\nValidating index consistency...");
            int errors = 0;
            
            // Check every document in forward index exists in inverted index
            for (Map.Entry<Integer, Map<Integer, Integer>> docEntry : forwardIndex.entrySet()) {
                int docId = docEntry.getKey();
                for (Map.Entry<Integer, Integer> wordEntry : docEntry.getValue().entrySet()) {
                    int wordId = wordEntry.getKey();
                    int freq = wordEntry.getValue();
                    
                    if (!invertedIndex.containsKey(wordId)) {
                        System.err.printf("ERROR: WordID %d exists in doc %d but missing in inverted index%n", 
                                        wordId, docId);
                        errors++;
                    } else if (!invertedIndex.get(wordId).containsKey(docId)) {
                        System.err.printf("ERROR: WordID %d missing doc %d entry in inverted index%n", 
                                        wordId, docId);
                        errors++;
                    } else if (!invertedIndex.get(wordId).get(docId).equals(freq)) {
                        System.err.printf("ERROR: Frequency mismatch for word %d in doc %d (fwd:%d vs inv:%d)%n",
                                        wordId, docId, freq, invertedIndex.get(wordId).get(docId));
                        errors++;
                    }
                }
            }
            
            // Check every word in inverted index exists in forward index
            for (Map.Entry<Integer, Map<Integer, Integer>> wordEntry : invertedIndex.entrySet()) {
                int wordId = wordEntry.getKey();
                for (Map.Entry<Integer, Integer> docEntry : wordEntry.getValue().entrySet()) {
                    int docId = docEntry.getKey();
                    if (!forwardIndex.containsKey(docId)) {
                        System.err.printf("ERROR: DocID %d exists for word %d but missing in forward index%n",
                                        docId, wordId);
                        errors++;
                    } else if (!forwardIndex.get(docId).containsKey(wordId)) {
                        System.err.printf("ERROR: WordID %d missing in doc %d's forward index%n",
                                        wordId, docId);
                        errors++;
                    }
                }
            }
            
            System.out.printf("Validation complete. Found %d consistency issues.%n", errors);
        }

        // Processes a single document file
        private void processDocument(File file) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                StringBuilder docText = new StringBuilder();
                String docNo = "";
                // Load common words we want to ignore
                Set<String> stopwords = loadStopwords("stopwordlist.txt");
                // Create stemmer to reduce words to root form
                Porter stemmer = new Porter();

                while ((line = reader.readLine()) != null) {
                    // Extract document number from special tag
                    if (line.contains("<DOCNO>")) {
                        docNo = line.replace("<DOCNO>", "").replace("</DOCNO>", "").trim();
                    } else if (line.contains("<TEXT>")) {
                        // Collect all text between TEXT tags
                        while (!(line = reader.readLine()).contains("</TEXT>")) {
                            docText.append(line).append(" ");
                        }

                        if (docText.length() > 0) {
                            // Break text into individual words
                            List<String> tokens = tokenize(docText.toString());
                            // Remove common words like "the", "and", etc.
                            removeStopwords(tokens, stopwords);
                            // Reduce words to their root form (e.g., "running" -> "run")
                            tokens = stemTokens(tokens, stemmer);
                            // Add words to our indexes
                            updateIndexes(docNo, tokens);
                        }

                        docText.setLength(0); // Reset for next document
                    }
                }
            } catch (IOException e) {
                System.err.println("Error processing file: " + file.getName());
                e.printStackTrace();
            }
        }

        // Updates both forward and inverted indexes with document words
        private void updateIndexes(String docNo, List<String> tokens) {
            // Get the ID for this document
            Integer docId = fileDictionary.get(docNo);
            if (docId == null) return;

            // Count how often each word appears in this document
            Map<String, Integer> wordFrequencies = new HashMap<>();
            for (String token : tokens) {
                wordFrequencies.put(token, wordFrequencies.getOrDefault(token, 0) + 1);
            }

            // Update forward index (document -> words with counts)
            Map<Integer, Integer> docWordFreqs = new HashMap<>();
            for (Map.Entry<String, Integer> entry : wordFrequencies.entrySet()) {
                Integer wordId = wordDictionary.get(entry.getKey());
                if (wordId != null) {
                    docWordFreqs.put(wordId, entry.getValue());
                }
            }
            forwardIndex.put(docId, docWordFreqs);

            // Update inverted index (word -> documents it appears in)
            for (Map.Entry<String, Integer> entry : wordFrequencies.entrySet()) {
                Integer wordId = wordDictionary.get(entry.getKey());
                if (wordId != null) {
                    if (!invertedIndex.containsKey(wordId)) {
                        invertedIndex.put(wordId, new HashMap<>());
                    }
                    invertedIndex.get(wordId).put(docId, entry.getValue());
                }
            }
        }

        // Saves forward index to a text file
        public void saveForwardIndex(String outputPath) throws IOException {
            validateIndexConsistency();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
                for (Map.Entry<Integer, Map<Integer, Integer>> docEntry : forwardIndex.entrySet()) {
                    writer.write(docEntry.getKey() + ": ");
                    List<String> wordEntries = new ArrayList<>();
                    for (Map.Entry<Integer, Integer> wordEntry : docEntry.getValue().entrySet()) {
                        wordEntries.add(wordEntry.getKey() + ":" + wordEntry.getValue());
                    }
                    writer.write(String.join("; ", wordEntries));
                    writer.newLine();
                }
            }
        }

        // Saves inverted index to a text file
        public void saveInvertedIndex(String outputPath) throws IOException {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
                for (Map.Entry<Integer, Map<Integer, Integer>> wordEntry : invertedIndex.entrySet()) {
                    writer.write(wordEntry.getKey() + ": ");
                    List<String> docEntries = new ArrayList<>();
                    for (Map.Entry<Integer, Integer> docEntry : wordEntry.getValue().entrySet()) {
                        docEntries.add(docEntry.getKey() + ":" + docEntry.getValue());
                    }
                    writer.write(String.join("; ", docEntries));
                    writer.newLine();
                }
            }
        }

        // Searches for a term in the index
        public void searchTerm(String term) {
            // Process the search term same way we processed documents
            Porter stemmer = new Porter();
            String stemmedTerm = stemmer.stripAffixes(term.toLowerCase());
            Integer wordId = wordDictionary.get(stemmedTerm);
            
            if (wordId == null) {
                System.out.println("Term '" + term + "' not found in the index.");
                return;
            }
            
            Map<Integer, Integer> docFrequencies = invertedIndex.get(wordId);
            if (docFrequencies == null || docFrequencies.isEmpty()) {
                System.out.println("Term '" + term + "' found but appears in no documents.");
                return;
            }
            
            // Print all documents containing this term
            System.out.println("Term: " + stemmedTerm + " (ID: " + wordId + ")");
            System.out.println("Appears in the following documents:");
            for (Map.Entry<Integer, Integer> entry : docFrequencies.entrySet()) {
                String docName = idToFileMap.get(entry.getKey());
                System.out.println("  Document: " + docName + " (ID: " + entry.getKey() + 
                                "), Frequency: " + entry.getValue());
            }
        }

        // Helper method to split text into words
        private List<String> tokenize(String text) {
            List<String> tokens = new ArrayList<>();
            // Split on anything that's not a letter
            for (String token : text.split("[^a-zA-Z]+")) {
                if (!token.isEmpty()) {
                    tokens.add(token.toLowerCase());
                }
            }
            return tokens;
        }

        // Removes common words from the token list
        private void removeStopwords(List<String> words, Set<String> stopwords) {
            words.removeIf(stopwords::contains);
        }

        // Loads common words to ignore from file
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

        // Reduces words to their root form
        private List<String> stemTokens(List<String> tokens, Porter stemmer) {
            List<String> stemmedTokens = new ArrayList<>();
            for (String token : tokens) {
                if (token.isEmpty()) continue;
                String stemmedToken = stemmer.stripAffixes(token);
                stemmedTokens.add(stemmedToken);
            }
            return stemmedTokens;
        }

        // Saves inverted index in compressed binary format
        public void saveCompressedInvertedIndex(String outputPath) throws IOException {
            try (ObjectOutputStream oos = new ObjectOutputStream(
                new GZIPOutputStream(new FileOutputStream(outputPath)))) {
                oos.writeObject(invertedIndex); 
            }
        }

        // Enhanced search with better formatting
        public void searchTermWithContext(String term) {
            Integer wordId = wordDictionary.get(term);
            if (wordId == null) {
                System.out.println("Term not found");
                return;
            }
            
            System.out.println("Documents containing '" + term + "' (ID: " + wordId + "):");
            invertedIndex.get(wordId).forEach((docId, freq) -> {
                String docName = idToFileMap.get(docId);
                System.out.printf(" - %s (ID: %d) appears %d time%s%n",
                    docName, docId, freq, freq > 1 ? "s" : "");
            });
        }

        //This method is optional which prints the words and its mapped Ids in word_ids.txt file.
    public void saveWordIdMappings(String outputPath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            wordDictionary.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> {
                    try {
                        writer.write(entry.getValue() + ": " + entry.getKey() + "\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        }
    }
    //This method is optional which prints the documents and its mapped Ids in doc_ids.txt file.
    public void saveDocIdMappings(String outputPath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            fileDictionary.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> {
                    try {
                        writer.write(entry.getValue() + ": " + entry.getKey() + "\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        }
    }
    // TF-IDF calculations

    public int getTotalDocumentCount() {
        return fileDictionary.size();
    }

    public int getDocumentFrequency(int wordId) {
        return invertedIndex.getOrDefault(wordId, Collections.emptyMap()).size();
    }

    public double getIdf(int wordId) {
        int df = getDocumentFrequency(wordId);
        return df > 0 ? Math.log((double)getTotalDocumentCount() / df) : 0;
    }

    public Map<Integer, Double> getDocumentVector(int docId) {
        Map<Integer, Double> vector = new HashMap<>();
        if (!forwardIndex.containsKey(docId)) return vector;
        
        Map<Integer, Integer> wordCounts = forwardIndex.get(docId);
        int docLength = wordCounts.values().stream().mapToInt(Integer::intValue).sum();
        
        for (Map.Entry<Integer, Integer> entry : wordCounts.entrySet()) {
            int wordId = entry.getKey();
            double tf = (double)entry.getValue() / docLength;
            double idf = getIdf(wordId);
            vector.put(wordId, tf * idf);
        }
        
        return vector;
    }

    //// TF-IDF calculations end...................

    }