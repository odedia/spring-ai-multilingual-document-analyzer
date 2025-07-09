package com.odedia.analyzer.services;

import static org.springframework.ai.chat.memory.ChatMemory.DEFAULT_CONVERSATION_ID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.odedia.analyzer.file.MultipartInputStreamFileResource;
import com.odedia.analyzer.rtl.HebrewPdfPerPageExtractor;

import reactor.core.publisher.Flux;;

@RestController
@RequestMapping("/document")
public class DocumentAnalyzerService {
	private final Logger logger = LoggerFactory.getLogger(DocumentAnalyzerService.class);

	private final ChatClient chatClient;
	private final ChatMemory chatMemory;
	private int totalChunks = 0;
	private int processedChunks = 0;

	private VectorStore vectorStore;

	private JdbcService jdbcService;

	public DocumentAnalyzerService(  VectorStore vectorStore, 
								ChatClient.Builder chatClientBuilder, 
								ChatMemoryRepository chatMemoryRepository,
								JdbcService jdbcService,
								@Value("${app.ai.topk}") Integer topK,
								@Value("${app.ai.maxMessages}") Integer maxMessages) throws IOException {

		this.chatMemory = MessageWindowChatMemory.builder()
			    .chatMemoryRepository(chatMemoryRepository)
			    .maxMessages(maxMessages)
			    .build();
;
		this.vectorStore = vectorStore;
		this.jdbcService = jdbcService;

		this.chatClient = chatClientBuilder.build();
	}

	@PostMapping("/clearPDFs")
	public void clearPDFs() {
		logger.info("Clearing vector store before new PDF embedding.");
		
		this.jdbcService.clearVectorStore();		
		
		logger.info("Done clearing vector store before new PDF embedding.");
	}

	
	@PostMapping("analyze")
	public ResponseEntity<Map<String, String>> analyze( @RequestParam("files") MultipartFile[] files,
														@RequestHeader("X-PDF-Language") String pdfLanguage) throws IOException {
		int totalDocuments = 0;
		List<Document> documents = new ArrayList<Document>();
		
		for (MultipartFile file : files) {
			logger.info("File is {}", file.getOriginalFilename());
			if (isPDF(file)) {
		
				 
				List<String> pages = HebrewPdfPerPageExtractor.extractPages(file, "he".equals(pdfLanguage));
			    //      Or, to use Python alternative: 
				//	    List<String> pages = sendToPythonAndGetParagraphs(file);
				
					    for (int i=0; i < pages.size(); i++) {
				            String visual = pages.get(i);
				            if (!(visual.trim().isBlank())) {
				            	documents.add(new Document(visual));
				            }
					    }
				
		
			} else if (isWordDoc(file)) {
		        logger.info("reading a docx file {}", file.getOriginalFilename());
				TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(file.getResource());
				
		        documents.addAll(tikaDocumentReader.read());
			}
			logger.info("Embedding {} chunks to vector store.", documents.size());

			this.vectorStore.accept(documents);
			totalDocuments += documents.size();
		}
		Map<String, String> response = new HashMap<>();
		response.put("status", "success");
		response.put("message", "File uploaded successfully");
		response.put("result", "Processed " + totalDocuments + " chunks. ");
		return ResponseEntity.ok(response);
	}

	private boolean isPDF(MultipartFile file) {
		return "pdf".equals(extension(file));
	}
	
	private boolean isWordDoc(MultipartFile file) {
		return "doc".equals(extension(file)) || "docx".equals(extension(file));
	}
	
	

	private String extension(MultipartFile file) {
		String filename = file.getOriginalFilename();
		String extension = "";

		int dotIndex = filename.lastIndexOf('.');
		if (dotIndex >= 0 && dotIndex < filename.length() - 1) {
		    extension = filename.substring(dotIndex + 1);
		}
		return extension;
	}

	/**
	 * This is a potential alternative to PDFBox if nothing else works as expected.
	 * Python seems to have a better handling of RTL PDF documents.
	 * Code for reference is under src/main/resources/python.
	 * @param file
	 * @return
	 * @throws IOException
	 */
	private List<String> sendToPythonAndGetParagraphs(MultipartFile file) throws IOException {
	    RestTemplate restTemplate = new RestTemplate();

	    HttpHeaders headers = new HttpHeaders();
	    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

	    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
	    body.add("file", new MultipartInputStreamFileResource(file.getInputStream(), file.getOriginalFilename()));

	    HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

	    String url = "http://127.0.0.1:5000/extract";

	    ResponseEntity<List<String>> response = restTemplate.exchange(
	    	    url,
	    	    HttpMethod.POST,
	    	    requestEntity,
	    	    new ParameterizedTypeReference<List<String>>() {}
	    	);

	    if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
	        throw new IOException("Failed to extract paragraphs from Python service");
	    }

	    Object paragraphObj = response.getBody();
	    if (!(paragraphObj instanceof List<?>)) {
	        throw new IOException("Invalid response: 'paragraphs' field missing or not a list");
	    }

	    @SuppressWarnings("unchecked")
	    List<String> paragraphs = ((List<String>) paragraphObj).stream().collect(Collectors.toList());

	    return paragraphs;
	}


	@PostMapping("/query")
	public Flux<String> queryPdf(@RequestBody String question, 
								 @RequestHeader("X-Conversation-ID") String conversationId,
								 @RequestHeader("X-Chat-Language") String chatLanguage,
								 @Value("${app.ai.topk}") Integer topK,
								 @Value("${app.ai.maxMessages}") Integer maxMessages) {
		
		logger.info("Received question: {}", question);
		logger.info("Chat Language is set to {}", "he".equals(chatLanguage) ? "Hebrew" : "English");
		String systemText = """
		        You are a friendly, helpful assistant that manages a document archive.
		        Never ask the user questions back.
		        Avoid meta‑phrases like "Based on the context…".
		        If you don’t know the answer from the provided context, say you don't know.
			    """ + ("he".equals(chatLanguage) ? "You must answer in Hebrew." : "You must answer in English.");
		
		logger.info("System text: {}", systemText);
		
		if (conversationId == null || conversationId.isBlank()) conversationId = DEFAULT_CONVERSATION_ID;
		
		PromptTemplate customPromptTemplate = PromptTemplate.builder()
			    .renderer(
			      StTemplateRenderer.builder()
			        .startDelimiterToken('<')
			        .endDelimiterToken('>')
			        .build()
			    )
			    .template("""
			<query>

			Context information is below:

			---------------------
			<question_answer_context>
			---------------------

			Answer the user’s question directly, using only the context above.
			""" + ("he".equals(chatLanguage) ? "You must answer in Hebrew." : "You must answer in English."))
			    .build();

		// 3) Wire it all together, plus logging & memory for debug
		return chatClient
		    .prompt(question)
		    .system(systemText)
		    .advisors(
		        SimpleLoggerAdvisor.builder().build(),                       // logs full, interpolated prompt
//		        MessageChatMemoryAdvisor.builder(this.chatMemory)           // preserves conversation
//		            .conversationId(conversationId)
//		            .build(),
		        QuestionAnswerAdvisor.builder(vectorStore)
		            .searchRequest(SearchRequest.builder().topK(topK).build())
		            .promptTemplate(customPromptTemplate)
		            .build()
		    )
		    .stream()
		    .content();
	}

	@GetMapping("progress")
	public ResponseEntity<Progress> getProgress() {
		Progress progress = new Progress(totalChunks, processedChunks);
		return ResponseEntity.ok(progress);
	}

	public static class Progress {
		private int totalChunks;
		private int processedChunks;

		public Progress(int totalChunks, int processedChunks) {
			this.totalChunks = totalChunks;
			this.processedChunks = processedChunks;
		}

		public int getTotalChunks() {
			return totalChunks;
		}

		public int getProcessedChunks() {
			return processedChunks;
		}
	}
}
