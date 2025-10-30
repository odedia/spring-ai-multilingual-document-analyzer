package com.odedia.analyzer.services;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.odedia.analyzer.chunking.AdaptiveSemanticChunker;
import com.odedia.analyzer.dto.DocumentInfo;
import com.odedia.analyzer.dto.PDFData;
import com.odedia.analyzer.file.MultipartInputStreamFileResource;
import com.odedia.analyzer.rtl.HebrewEnglishPdfPerPageExtractor;
import com.odedia.repo.jpa.ConversationRepository;
import com.odedia.repo.jpa.MessageSummaryCacheRepository;
import com.odedia.repo.model.Conversation;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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
    private final DocumentRepository documentRepo;
    private final Sinks.Many<Map<String,Object>> conversationEvents = Sinks.many().multicast().onBackpressureBuffer();

	private JdbcService jdbcService;

	private JdbcChatMemoryRepository chatMemoryRepository;

	private ConversationRepository conversationRepo;

	private MessageSummaryCacheRepository summaryCacheRepository;

	private QueryRewriterService queryRewriter;

	public DocumentAnalyzerService(  VectorStore vectorStore,
			ChatClient.Builder chatClientBuilder,
			JdbcService jdbcService,
			@Value("${app.ai.topk}") Integer topK,
			@Value("${app.ai.maxChatHistory}") Integer maxChatHistory,
			DocumentRepository documentRepo,
			JdbcChatMemoryRepository chatMemoryRepository,
			ConversationRepository conversationRepo,
			MessageSummaryCacheRepository summaryCacheRepository,
			QueryRewriterService queryRewriter,
			ChatMemory chatMemory) throws IOException {

		this.chatMemory = chatMemory;
		this.vectorStore = vectorStore;
		this.jdbcService = jdbcService;

		this.chatClient = chatClientBuilder.build();
        this.documentRepo = documentRepo;
        this.chatMemoryRepository = chatMemoryRepository;
        this.conversationRepo = conversationRepo;
        this.summaryCacheRepository = summaryCacheRepository;
        this.queryRewriter = queryRewriter;
	}
	
	@PostMapping("/conversations")
	public ResponseEntity<String> createConversation() {
	    UUID conversationId = UUID.randomUUID();
	    Conversation conv = new Conversation();
	    conv.setId(conversationId);
	    conv.setCreatedAt(Instant.now());
	    conv.setLastActive(Instant.now());
	    conv.setTitle("...");
	    conversationRepo.save(conv);
	    return ResponseEntity.ok(conversationId.toString());
	}

	@GetMapping("/conversations")
	public List<Conversation> listConversations() {
	    return conversationRepo.findAllByOrderByLastActiveDesc();
	}

	@GetMapping("/conversations/{id}/messages")
	public List<Message> getConversationMessages(@PathVariable String id) {
	    return chatMemoryRepository.findByConversationId(id);
	}

	@PostMapping("/clearDocuments")
	public void clearDocuments() {
		logger.info("Clearing vector store before new PDF embedding.");

		this.jdbcService.clearVectorStore();		

		logger.info("Done clearing vector store before new PDF embedding.");
	}

    @GetMapping(path = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<DocumentInfo> listDocuments() {
        return documentRepo.findDistinctDocuments();
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        if (!conversationRepo.existsById(uuid)) {
            return ResponseEntity.notFound().build();
        }

        // Delete conversation metadata
        conversationRepo.deleteById(uuid);

        // Delete cached summaries for this conversation
        summaryCacheRepository.deleteByConversationId(id);
        logger.info("Deleted cached summaries for conversation {}", id);

        // Emit SSE event so front-end can remove the item if it is listening.
        Map<String, Object> payload = Map.of(
            "event", "conversationDeleted",
            "conversationId", id
        );
        conversationEvents.tryEmitNext(payload);

        logger.info("Deleted conversation {}", id);
        return ResponseEntity.noContent().build();
    }

    
	@PostMapping(path = "analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, Object>>> analyze(
	        @RequestParam("files") MultipartFile[] files) {

	    Instant start = Instant.now();

	    Flux<ServerSentEvent<Map<String, Object>>> progressFlux = Flux.<ServerSentEvent<Map<String, Object>>>create(emitter -> {
	        int totalChunks = 0;
	        int processedFiles = 0;
	        String pdfLanguage = "";
	        for (MultipartFile file : files) {
	            
	        	try {
	                List<Document> documents = new ArrayList<>();
	                logger.info("File is {}", file.getOriginalFilename());

	                if (isPDF(file)) {
	                	// Extract PDF pages with Hebrew support
	                	PDFData pdfData = HebrewEnglishPdfPerPageExtractor.extractPages(file);
	                	pdfLanguage = pdfData.getLanguage();

	                	// Use adaptive semantic chunking for optimal retrieval
	                	List<Document> chunkedDocs = AdaptiveSemanticChunker.chunkDocument(
	                	    pdfData,
	                	    file.getOriginalFilename()
	                	);
	                	documents.addAll(chunkedDocs);

	                } else if (isWordDoc(file)) {
	                    logger.info("Reading DOCX: {}", file.getOriginalFilename());
	                    TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
	                    List<Document> docs = reader.read();
	                    for (Document doc : docs) {
	                    	pdfLanguage = HebrewEnglishPdfPerPageExtractor.detectDominantLanguage(doc.getText());
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
	                                "language", pdfLanguage,
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

	    Flux<ServerSentEvent<Map<String,Object>>> heartbeatFlux =
	            Flux.interval(Duration.ofSeconds(15))
	                .map(tick -> ServerSentEvent.<Map<String,Object>>builder()
	                    .comment("heartbeat")
	                    .build());


	    return Flux
	    	      .merge(progressFlux, heartbeatFlux)
	    	      .takeUntil(sse -> "jobComplete".equals(sse.event()));
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
		return extension.toLowerCase();
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
			@RequestHeader(value = "X-Filter-Language", defaultValue = "all") String filterLanguage,
			@RequestHeader(value = "X-Enable-CoT", defaultValue = "false") boolean enableCoT,
			@RequestHeader(value = "X-Enable-Query-Rewrite", defaultValue = "true") boolean enableQueryRewrite,
			@Value("${app.ai.topk}") Integer topK,
			@Value("${app.ai.beChatty}") String beChatty,
			@Value("${app.ai.promptTemplate}") String promptTemplate,
			@Value("${app.ai.promptTemplateWithCoT}") String promptTemplateWithCoT,
			@Value("${app.ai.systemText}") String systemText) {

		Conversation conv = conversationRepo.findById(UUID.fromString(conversationId))
			    .orElseThrow();

			conv.setLastActive(Instant.now());
			conversationRepo.save(conv);

			// check if title is still placeholder
			if (conv.getTitle() == null || conv.getTitle().startsWith("New Chat") || conv.getTitle().startsWith("...")) {
				generateAndSaveConversationTitle(UUID.fromString(conversationId), question, chatLanguage)
			    .doOnError(e -> logger.warn("Title generation failed for {}: {}", conversationId, e.getMessage()))
			    .subscribe();
			}

		logger.info("Received question: {}", question);
		logger.info("Chat Language: {}, Chain-of-Thought: {}, Query Rewrite: {}",
		           "he".equals(chatLanguage) ? "Hebrew" : "English",
		           enableCoT ? "ENABLED" : "DISABLED",
		           enableQueryRewrite ? "ENABLED" : "DISABLED");

		// Step 1: Query Rewriting for better retrieval (if enabled)
		String searchQuery = question;  // Default to original
		List<org.springframework.ai.chat.messages.Message> recentMessages =
		    chatMemoryRepository.findByConversationId(conversationId);

		if (enableQueryRewrite && queryRewriter.shouldRewrite(question)) {
			searchQuery = queryRewriter.rewriteQuery(question, recentMessages, chatLanguage);
		}

		// Step 2: Choose prompt template based on CoT toggle
		String selectedPromptTemplate = enableCoT ? promptTemplateWithCoT : promptTemplate;
		logger.info("Using {} prompt template", enableCoT ? "Chain-of-Thought" : "standard");

		// Step 3: Build system text
		systemText += ("yes".equals(beChatty) ?
				"Try to engage in conversation and invoke a dialog." :
				"Never ask the user questions back.");

		systemText += ("he".equals(chatLanguage) ?
				    " You must respond in Hebrew." :
					" You must respond in English.");

		logger.info("System text configured");
		logger.info("Using prompt template: {}", enableCoT ? "Chain-of-Thought" : "Standard");
		logger.info("Selected template content: {}", selectedPromptTemplate);

		PromptTemplate customPromptTemplate = PromptTemplate.builder()
				.renderer(
						StTemplateRenderer.builder()
						.startDelimiterToken('<')
						.endDelimiterToken('>')
						.build()
						)
				.template(selectedPromptTemplate)
				.build();

		// Build search request with rewritten query and optional language filtering
		SearchRequest.Builder searchRequestBuilder = SearchRequest.builder()
				.query(searchQuery)  // Use rewritten query for better retrieval
				.topK(topK);

		if (!"all".equals(filterLanguage)) {
			// Filter by language metadata
			searchRequestBuilder.filterExpression("language == '" + filterLanguage + "'");
			logger.info("Filtering documents by language: {}", filterLanguage);
		} else {
			logger.info("Searching all documents (no language filter)");
		}

		logger.info("=== RAG Query Debug ===");
		logger.info("Original question: {}", question);
		logger.info("Search query (after rewrite): {}", searchQuery);
		logger.info("TopK: {}", topK);
		logger.info("Filter language: {}", filterLanguage);
		logger.info("System text: {}", systemText);

		// 3) Wire it all together, plus logging & memory for debug
		return chatClient
				.prompt(question)
				.system(systemText)
				.advisors(
						SimpleLoggerAdvisor.builder().build(),                       // logs full, interpolated prompt
						MessageChatMemoryAdvisor.builder(this.chatMemory)           // preserves conversation
						.conversationId(conversationId)
						.build(),
						QuestionAnswerAdvisor.builder(vectorStore)
						.searchRequest(searchRequestBuilder.build())
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
	
	/**
	 * Generate a short title (model must return <= 5 words).
	 * We *ask* the model to return at most five words and only the title text.
	 * If the model ignores that, we retry up to 2 times with a targeted "shorten" prompt.
	 * As a last-resort safety net (should rarely happen) we fallback to an ID-based short title.
	 */
	public Mono<Void> generateAndSaveConversationTitle(
	        UUID conversationId,
	        String firstUserMessage,
	        String lang
	) {
	    logger.info("Received request to generate title for UUID {} and message {}", conversationId, firstUserMessage);

	    if (conversationId == null) {
	        return Mono.empty();
	    }

	    final String systemInstruction = ""
	        + "You are a concise title generator. Produce a single short title that summarizes the conversation "
	        + "based only on the user's first message. IMPORTANT: The title must be AT MOST FIVE WORDS "
	        + "and must contain only the title text — no explanation, no punctuation at start/end, no quotes, "
	        + "no extra lines. Return exactly the title text in plain text."
	        + (lang.equals("en") ? " The title must be in English." : " הכותרת חייבת להיות בעברית.");

	    String userPrompt = "User's message:\n\n" + firstUserMessage + "\n\nTitle:";

	    final Duration singleCallTimeout = Duration.ofSeconds(120);

	    return Mono
	        .fromCallable(() -> chatClient
	            .prompt(userPrompt)
	            .system(systemInstruction)
	            .call()
	            .content()  // blocking call returning String :contentReference[oaicite:1]{index=1}
	        )
	        .subscribeOn(Schedulers.boundedElastic())
	        .timeout(singleCallTimeout)
	        .onErrorResume(throwable -> {
	            logger.warn("Title generation timed out or failed for {}: {}", conversationId, throwable.toString());
	            return Mono.just("");
	        })
	        .map(raw -> raw == null ? "" : raw.trim())
	        .map(candidateRaw -> {
	            String candidate = candidateRaw;
	            if ("en".equals(lang)) {
	                candidate = candidate.replaceAll("[\\r\\n\"'`]", " ").trim();
	            }
	            candidate = candidate.replaceAll("[\\r\\n\"'`]", " ").trim().replaceAll("\\s+", " ").trim();
	            int wordCount = candidate.isEmpty() ? 0 : candidate.split("\\s+").length;
	            return (wordCount == 0 || wordCount > 5) ? "" : candidate;
	        })
	        .flatMap(candidate -> {
	            final String finalCandidate = candidate == null ? "" : candidate;
	            return Mono.fromCallable(() -> {
	                String toSave = finalCandidate;
	                if (toSave.isBlank()) {
	                    toSave = lang.equals("en") ? "New Chat" : "שיחה חדשה";
	                    logger.warn("Title generation empty; using fallback '{}'", toSave);
	                }
	                if ("en".equals(lang)) {
	                    toSave = toSave.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "").trim();
	                }

	                Optional<Conversation> oc = conversationRepo.findById(conversationId);
	                if (oc.isPresent()) {
	                    Conversation conv = oc.get();
	                    conv.setTitle(toSave);
	                    conversationRepo.save(conv);

	                    Map<String, Object> payload = Map.of(
	                        "event", "conversationTitleUpdated",
	                        "conversationId", conv.getId().toString(),
	                        "title", conv.getTitle()
	                    );
	                    conversationEvents.tryEmitNext(payload);

	                    logger.info("Generated and saved title '{}' for conversation {}", toSave, conversationId);
	                } else {
	                    logger.warn("Conversation {} not found when trying to save title '{}'", conversationId, toSave);
	                }
	                return Void.TYPE;
	            }).subscribeOn(Schedulers.boundedElastic()).then();
	        })
	        .then();
	}




	@GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String,Object>>> streamConversationEvents() {
	    return conversationEvents.asFlux()
	        .map(payload -> ServerSentEvent.<Map<String,Object>>builder()
	            .event((String) payload.get("event"))
	            .data(payload)
	            .build()
	        );
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
