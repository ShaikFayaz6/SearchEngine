//Author: FayazShaik
//Date: 02/26/2025
import java.io.*;
import java.util.*;

public class TextParser {
    private Map<String, Integer> wordDictionary = new HashMap<>();
    private Map<String, Integer> fileDictionary = new HashMap<>();
    private int wordIdCounter = 1;
    private int fileIdCounter = 1;

    // Tokenize the text into words
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>(Arrays.asList(text.split("[^a-zA-Z]+")));
        tokens.removeIf(String::isEmpty); // Remove empty strings
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

    // Stem tokens using the Porter Stemmer
    private List<String> stemTokens(List<String> tokens) {
        Porter stemmer = new Porter();
        List<String> stemmedTokens = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue; // ✅ Skip empty strings
            String stemmedToken = stemmer.stripAffixes(token);
            System.out.println("Stemming token: " + token + " -> " + stemmedToken); // Debugging
            stemmedTokens.add(stemmedToken);
        }
        return stemmedTokens;
    }
    

    // Update the word dictionary with new tokens
    private void updateWordDictionary(List<String> tokens) {
        for (String token : tokens) {
            if (!wordDictionary.containsKey(token)) {
                wordDictionary.put(token, wordIdCounter++);
                System.out.println("Added to word dictionary: " + token + " -> " + (wordIdCounter - 1));
            }
        }
    }

    // Parse a single TREC file
    private void parseTrecFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            StringBuilder docText = new StringBuilder();
            String docNo = "";
            Set<String> stopwords = loadStopwords("stopwordlist.txt"); // Load stopwords once

            while ((line = reader.readLine()) != null) {
                if (line.contains("<DOCNO>")) {
                    docNo = line.replace("<DOCNO>", "").replace("</DOCNO>", "").trim();
                    fileDictionary.put(docNo, fileIdCounter++);
                    System.out.println("Processing document: " + docNo);
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
                    System.out.println("Processing file: " + file.getName());
                    parseTrecFile(file);
                }
            }
        } else {
            System.out.println("The folder is empty or does not exist.");
        }
    }

    // Save the output
    public void saveOutput(String outputFilePath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
            for (Map.Entry<String, Integer> entry : wordDictionary.entrySet()) {
                writer.write(entry.getKey() + "\t" + entry.getValue() + "\n");
            }
            for (Map.Entry<String, Integer> entry : fileDictionary.entrySet()) {
                writer.write(entry.getKey() + "\t" + entry.getValue() + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        TextParser parser = new TextParser();
        parser.parseTrecFolder("ft911");
        parser.saveOutput("parser_output.txt");
        System.out.println("Parsing complete. Output saved to parser_output.txt.");
    }
}
