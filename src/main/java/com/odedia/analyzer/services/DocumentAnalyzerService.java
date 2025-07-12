package com.odedia.analyzer.services;

import static org.springframework.ai.chat.memory.ChatMemory.DEFAULT_CONVERSATION_ID;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.http.codec.ServerSentEvent;
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

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;;

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
			@Value("${app.ai.maxChatHistory}") Integer maxChatHistory) throws IOException {

		this.chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(chatMemoryRepository)
				.maxMessages(maxChatHistory)
				.build();
		;
		this.vectorStore = vectorStore;
		this.jdbcService = jdbcService;

		this.chatClient = chatClientBuilder.build();
	}

	@PostMapping("/clearDocuments")
	public void clearDocuments() {
		logger.info("Clearing vector store before new PDF embedding.");

		this.jdbcService.clearVectorStore();		

		logger.info("Done clearing vector store before new PDF embedding.");
	}


	@PostMapping(path = "analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, Object>>> analyze(
	        @RequestParam("files") MultipartFile[] files,
	        @RequestHeader("X-PDF-Language") String pdfLanguage) {

	    Instant start = Instant.now();

	    Flux<ServerSentEvent<Map<String, Object>>> progressFlux = Flux.<ServerSentEvent<Map<String, Object>>>create(emitter -> {
	        int totalChunks = 0;
	        int processedFiles = 0;

	        for (MultipartFile file : files) {
	            try {
	                List<Document> documents = new ArrayList<>();
	                logger.info("File is {}", file.getOriginalFilename());

	                if (isPDF(file)) {
	                    List<String> pages = HebrewPdfPerPageExtractor.extractPages(file, "he".equals(pdfLanguage));
	                    for (String visual : pages) {
	                        if (!visual.trim().isBlank()) {
	                            Document doc = new Document(visual);
	                            doc.getMetadata().put("filename", file.getOriginalFilename());
	                            doc.getMetadata().put("language", pdfLanguage);
	                            documents.add(doc);
	                        }
	                    }
	                } else if (isWordDoc(file)) {
	                    logger.info("Reading DOCX: {}", file.getOriginalFilename());
	                    TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
	                    List<Document> docs = reader.read();
	                    for (Document doc : docs) {
	                        doc.getMetadata().put("filename", file.getOriginalFilename());
	                        doc.getMetadata().put("language", pdfLanguage);
	                        documents.add(doc);
	                    }
	                }

	                this.vectorStore.accept(documents);
	                totalChunks += documents.size();
	                processedFiles++;

	                emitter.next(ServerSentEvent.<Map<String, Object>>builder()
	                        .event("fileDone")
	                        .data(Map.of(
	                                "file", file.getOriginalFilename(),
	                                "progressPercent", (int) ((processedFiles * 100.0) / files.length),
	                                "chunks", documents.size()
	                        ))
	                        .build());

	            } catch (Exception e) {
	                logger.error("Failed to process file {}", file.getOriginalFilename(), e);
	                emitter.next(ServerSentEvent.<Map<String, Object>>builder()
	                        .event("error")
	                        .data(Map.of(
	                                "message", "Failed to process " + file.getOriginalFilename()
	                        ))
	                        .build());
	            }
	        }

	        emitter.next(ServerSentEvent.<Map<String, Object>>builder()
	                .event("jobComplete")
	                .data(Map.of(
	                        "status", "success",
	                        "totalChunks", totalChunks,
	                        "elapsed", Duration.between(start, Instant.now()).toSeconds()
	                ))
	                .build());

	        emitter.complete();
	    }).subscribeOn(Schedulers.boundedElastic());

	    Flux<ServerSentEvent<Map<String, Object>>> heartbeatFlux = Flux.interval(Duration.ofSeconds(15))
	        .map(tick -> ServerSentEvent.<Map<String, Object>>builder()
	            .comment("heartbeat")
	            .build());

	    return progressFlux.mergeWith(heartbeatFlux);
	}



    private List<Document> extract(MultipartFile file, String pdfLang) throws IOException {
        if (isPDF(file)) {
            List<String> pages = HebrewPdfPerPageExtractor.extractPages(
                                     file, "he".equalsIgnoreCase(pdfLang));
            return pages.stream()
                        .filter(p -> !p.isBlank())
                        .map(Document::new)
                        .toList();
        } else if (isWordDoc(file)) {
            return new TikaDocumentReader(file.getResource()).read();
        }
        return List.of();
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
			@Value("${app.ai.beChatty}") String beChatty,
			@Value("${app.ai.promptTemplate}") String promptTemplate,
			@Value("${app.ai.systemText}") String systemText) {
		logger.info(" the prompt template is: " + promptTemplate);
		logger.info("Received question: {}", question);
		logger.info("Chat Language is set to {}", "he".equals(chatLanguage) ? "Hebrew" : "English");
		systemText += ("he".equals(chatLanguage) ? 
				"You must answer in Hebrew." : 
					"You must answer in English."
					+ ("yes".equals(beChatty) ? 
							"Try to engage in conversation and invoke a dialog." : 
							"Never ask the user questions back."));

		logger.info("System text: {}", systemText);

		if (conversationId == null || conversationId.isBlank()) conversationId = DEFAULT_CONVERSATION_ID;

		PromptTemplate customPromptTemplate = PromptTemplate.builder()
				.renderer(
						StTemplateRenderer.builder()
						.startDelimiterToken('<')
						.endDelimiterToken('>')
						.build()
						)
				.template(promptTemplate)
				//		+ 
				//			    		    ("he".equals(chatLanguage) ? 
				//			    		    		"You must answer in Hebrew." : 
				//			    		    		"You must answer in English.")
				//			    		    )
				.build();

		// 3) Wire it all together, plus logging & memory for debug
		return chatClient
				.prompt(question)
				.system(systemText)
				.advisors(
						SimpleLoggerAdvisor.builder().build(),                       // logs full, interpolated prompt
						//MessageChatMemoryAdvisor.builder(this.chatMemory)           // preserves conversation
						//.conversationId(conversationId)
						//.build(),
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
