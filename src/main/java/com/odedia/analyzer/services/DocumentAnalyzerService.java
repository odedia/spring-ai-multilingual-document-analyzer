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
import com.odedia.analyzer.memory.SummarizingTokenWindowChatMemory;
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

	private final ChatModelRegistry chatModelRegistry;
	private final ChatMemory chatMemory;

	private VectorStore vectorStore;
	private final DocumentRepository documentRepo;
	private final Sinks.Many<Map<String, Object>> conversationEvents = Sinks.many().multicast().onBackpressureBuffer();

	private JdbcService jdbcService;

	private JdbcChatMemoryRepository chatMemoryRepository;

	private ConversationRepository conversationRepo;

	private MessageSummaryCacheRepository summaryCacheRepository;

	private QueryRewriterService queryRewriter;

	private final ModelContextService modelContextService;

	private final DocumentTools documentTools;

	private final com.odedia.repo.jpa.AnswerModelRepository answerModelRepo;

	public DocumentAnalyzerService(VectorStore vectorStore,
			ChatModelRegistry chatModelRegistry,
			JdbcService jdbcService,
			@Value("${app.ai.topk}") Integer topK,
			@Value("${app.ai.maxChatHistory}") Integer maxChatHistory,
			DocumentRepository documentRepo,
			JdbcChatMemoryRepository chatMemoryRepository,
			ConversationRepository conversationRepo,
			MessageSummaryCacheRepository summaryCacheRepository,
			QueryRewriterService queryRewriter,
			ModelContextService modelContextService,
			DocumentTools documentTools,
			com.odedia.repo.jpa.AnswerModelRepository answerModelRepo,
			ChatMemory chatMemory) throws IOException {

		this.chatMemory = chatMemory;
		this.vectorStore = vectorStore;
		this.jdbcService = jdbcService;

		this.chatModelRegistry = chatModelRegistry;
		this.documentRepo = documentRepo;
		this.chatMemoryRepository = chatMemoryRepository;
		this.conversationRepo = conversationRepo;
		this.summaryCacheRepository = summaryCacheRepository;
		this.queryRewriter = queryRewriter;
		this.modelContextService = modelContextService;
		this.documentTools = documentTools;
		this.answerModelRepo = answerModelRepo;
	}

	/** Rough, deliberately conservative token estimate (over-counts to avoid overflow). */
	private static int estimateTokens(String text) {
		return text == null ? 0 : (int) Math.ceil(text.length() / 3.0);
	}

	@GetMapping("/models")
	public Map<String, Object> listModels() {
		return Map.of(
				"models", chatModelRegistry.listModels(),
				"default", Optional.ofNullable(chatModelRegistry.getDefaultModelName()).orElse(""));
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

	/** Ordered list of the model that answered each assistant turn (index = answer ordinal). */
	@GetMapping("/conversations/{id}/answer-models")
	public List<String> getAnswerModels(@PathVariable String id) {
		return answerModelRepo.findByConversationIdOrderBySeqAsc(id).stream()
				.map(com.odedia.repo.model.AnswerModel::getModel)
				.toList();
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

	@DeleteMapping("/documents")
	public ResponseEntity<Void> deleteDocument(@RequestParam("filename") String filename) {
		if (filename == null || filename.isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		int removed = jdbcService.deleteDocumentByFilename(filename);
		logger.info("Deleted document '{}' ({} chunks removed)", filename, removed);
		return removed > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
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

		// Drop per-conversation memory overrides (no unbounded map growth).
		if (chatMemory instanceof SummarizingTokenWindowChatMemory stw) {
			stw.forget(id);
		}

		// Delete the per-answer model records for this conversation.
		answerModelRepo.deleteByConversationId(id);

		// Emit SSE event so front-end can remove the item if it is listening.
		Map<String, Object> payload = Map.of(
				"event", "conversationDeleted",
				"conversationId", id);
		conversationEvents.tryEmitNext(payload);

		logger.info("Deleted conversation {}", id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping(path = "analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, Object>>> analyze(
			@RequestParam("files") MultipartFile[] files) {

		Instant start = Instant.now();

		Flux<ServerSentEvent<Map<String, Object>>> progressFlux = Flux
				.<ServerSentEvent<Map<String, Object>>>create(emitter -> {
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
										file.getOriginalFilename());
								documents.addAll(chunkedDocs);

							} else if (isWordDoc(file)) {
								logger.info("Reading DOCX: {}", file.getOriginalFilename());
								TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
								List<Document> docs = reader.read();
								int pageNum = 1;
								for (Document doc : docs) {
									// Clean extracted text to remove binary/image data that causes embedding
									// failures
									String cleanedText = cleanExtractedText(doc.getText());
									if (cleanedText.isEmpty()) {
										logger.warn(
												"Page {} of {} was empty after cleaning (likely only contained images)",
												pageNum, file.getOriginalFilename());
										pageNum++;
										continue;
									}

									pdfLanguage = HebrewEnglishPdfPerPageExtractor
											.detectDominantLanguage(cleanedText);

									// Prepend source info to content for LLM citation (matching PDF chunker format)
									String contentWithSource = String.format(
											"[SOURCE: %s, PAGE: %d]\n\n%s",
											file.getOriginalFilename(), pageNum, cleanedText);

									Document enrichedDoc = new Document(contentWithSource);
									enrichedDoc.getMetadata().put("filename", file.getOriginalFilename());
									enrichedDoc.getMetadata().put("language", pdfLanguage);
									enrichedDoc.getMetadata().put("page_number", pageNum);
									documents.add(enrichedDoc);
									pageNum++;
								}
							}

							// Process documents one at a time to handle EOF errors gracefully
							// Each document is tried individually, with fallback to truncated content
							int successCount = 0;
							for (int i = 0; i < documents.size(); i++) {
								Document doc = documents.get(i);
								try {
									this.vectorStore.accept(List.of(doc));
									successCount++;
									logger.debug("Embedded document {}/{}", i + 1, documents.size());
								} catch (Exception e) {
									// If embedding fails, try with truncated content
									String content = doc.getText();
									if (content != null && content.length() > 2000) {
										logger.warn(
												"Document {} embedding failed, retrying with truncated content (original: {} chars)",
												i + 1, content.length());
										try {
											// Truncate to ~2000 chars (safe for most embedding models)
											String truncated = content.substring(0, 2000) + "...";
											Document truncatedDoc = new Document(truncated);
											truncatedDoc.getMetadata().putAll(doc.getMetadata());
											this.vectorStore.accept(List.of(truncatedDoc));
											successCount++;
											logger.info("Successfully embedded truncated document {}", i + 1);
										} catch (Exception retryEx) {
											logger.error(
													"Failed to embed document {} even after truncation, skipping: {}",
													i + 1, retryEx.getMessage());
										}
									} else {
										logger.error("Failed to embed document {} (content: {} chars), skipping: {}",
												i + 1, content != null ? content.length() : 0, e.getMessage());
									}
								}
							}
							logger.info("Embedded {}/{} documents successfully", successCount, documents.size());
							totalChunks += documents.size();
							processedFiles++;

							emitter.next(ServerSentEvent.<Map<String, Object>>builder()
									.event("fileDone")
									.data(Map.of(
											"file", file.getOriginalFilename(),
											"language", pdfLanguage,
											"progressPercent", (int) ((processedFiles * 100.0) / files.length),
											"chunks", documents.size()))
									.build());

						} catch (Exception e) {
							logger.error("Failed to process file {}", file.getOriginalFilename(), e);
							emitter.next(ServerSentEvent.<Map<String, Object>>builder()
									.event("error")
									.data(Map.of(
											"message", "Failed to process " + file.getOriginalFilename()))
									.build());
						}
					}

					emitter.next(ServerSentEvent.<Map<String, Object>>builder()
							.event("jobComplete")
							.data(Map.of(
									"status", "success",
									"totalChunks", totalChunks,
									"elapsed", Duration.between(start, Instant.now()).toSeconds()))
							.build());

					emitter.complete();
				}).subscribeOn(Schedulers.boundedElastic());

		Flux<ServerSentEvent<Map<String, Object>>> heartbeatFlux = Flux.interval(Duration.ofSeconds(15))
				.map(tick -> ServerSentEvent.<Map<String, Object>>builder()
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
	 * Cleans extracted text from Word documents by removing binary/image data
	 * that Tika may include. This prevents embedding API failures (EOF errors)
	 * when documents contain embedded images.
	 * 
	 * @param text The raw extracted text from Tika
	 * @return Cleaned text suitable for embedding
	 */
	private String cleanExtractedText(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}

		// Remove common binary/base64 patterns that Tika may extract from images
		// These patterns cause embedding API failures
		String cleaned = text;

		// Remove base64 encoded image data (common in Office documents)
		// Pattern matches: data:image/..., or long base64 strings
		cleaned = cleaned.replaceAll("data:image/[^;]+;base64,[A-Za-z0-9+/=\\s]+", "[IMAGE]");

		// Remove very long sequences of non-printable or binary-like characters
		// These are typically embedded binary data from images
		cleaned = cleaned.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]+", " ");

		// Remove long sequences of repeated characters (often from binary corruption)
		cleaned = cleaned.replaceAll("(.)(\\1{50,})", " ");

		// Remove lines that look like binary data (contain mostly non-ASCII characters)
		String[] lines = cleaned.split("\\n");
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			// Skip lines where more than 30% of characters are non-printable/binary
			int nonPrintable = 0;
			for (char c : line.toCharArray()) {
				if (c < 32 && c != '\t' && c != '\r' && c != '\n') {
					nonPrintable++;
				}
			}
			if (line.isEmpty() || (double) nonPrintable / line.length() < 0.3) {
				sb.append(line).append("\n");
			}
		}
		cleaned = sb.toString();

		// Normalize whitespace
		cleaned = cleaned.replaceAll("\\s+", " ").trim();

		// Log if significant content was removed
		int originalLength = text.length();
		int cleanedLength = cleaned.length();
		if (originalLength > 0 && cleanedLength < originalLength * 0.5) {
			logger.info("Cleaned Word document text: removed {}% of content (likely binary/image data)",
					(int) ((1.0 - (double) cleanedLength / originalLength) * 100));
		}

		return cleaned;
	}

	/**
	 * This is a potential alternative to PDFBox if nothing else works as expected.
	 * Python seems to have a better handling of RTL PDF documents.
	 * Code for reference is under src/main/resources/python.
	 * 
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
				new ParameterizedTypeReference<List<String>>() {
				});

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
			@RequestHeader(value = "X-Enable-CoT", defaultValue = "false") boolean enableCoT,
			@RequestHeader(value = "X-Enable-Query-Rewrite", defaultValue = "true") boolean enableQueryRewrite,
			@RequestHeader(value = "X-Cross-Lingual", defaultValue = "false") boolean crossLingual,
			@RequestHeader(value = "X-Chat-Model", required = false) String chatModelName,
			@RequestHeader(value = "X-Top-K", required = false) Integer topKHeader,
			@RequestHeader(value = "X-Max-Chat-Tokens", required = false) Integer maxChatTokensHeader,
			@RequestHeader(value = "X-Recent-Messages", required = false) Integer recentMessagesHeader,
			@RequestHeader(value = "X-Similarity-Threshold", required = false) Double similarityThresholdHeader,
			@RequestHeader(value = "X-Temperature", required = false) Double temperatureHeader,
			@RequestHeader(value = "X-Max-Context", required = false) Integer maxContextHeader,
			@Value("${app.ai.topk}") Integer topK,
			@Value("${app.ai.maxChatTokens}") Integer defaultMaxChatTokens,
			@Value("${app.ai.beChatty}") String beChatty,
			@Value("${app.ai.similarityThreshold}") Double similarityThreshold,
			@Value("${app.ai.outputReserveTokens}") Integer outputReserveTokens,
			@Value("${app.ai.estTokensPerChunk}") Integer estTokensPerChunk,
			@Value("${app.ai.promptTemplate}") String promptTemplate,
			@Value("${app.ai.promptTemplateWithCoT}") String promptTemplateWithCoT,
			@Value("${app.ai.systemText}") String systemText) {

		ChatClient chatClient = chatModelRegistry.clientFor(chatModelName);
		String resolvedModel = chatModelRegistry.resolve(chatModelName).orElse("default");
		logger.info("Chat model for this request: {}", resolvedModel);

		// What the user asked for (caps; the budget below may shrink these to fit the model).
		int requestedTopK = (topKHeader != null && topKHeader > 0) ? Math.min(topKHeader, 50) : topK;
		int requestedHistory = (maxChatTokensHeader != null && maxChatTokensHeader > 0)
				? maxChatTokensHeader
				: defaultMaxChatTokens;

		// === Context-window budgeting (H3): never let prompt exceed the selected model ===
		String selectedPromptTemplate = enableCoT ? promptTemplateWithCoT : promptTemplate;
		// Context window: the user's per-model setting wins; otherwise whatever the server
		// can detect (operator config / probe / default). We don't guess per model.
		int modelCtx = (maxContextHeader != null && maxContextHeader > 1024)
				? maxContextHeader
				: modelContextService.maxContextTokens(resolvedModel);
		int promptOverhead = estimateTokens(systemText) + estimateTokens(selectedPromptTemplate)
				+ estimateTokens(question) + 512; // 512 = formatting/role slack
		// On small windows a flat 4096-token output reserve eats half the budget and starves the
		// history budget to ~1k tokens — smaller than a single long answer, so we'd re-summarize on
		// EVERY turn. Cap the reserve to a quarter of the window (floor 1024) so small windows keep
		// a usable history budget; large windows are unaffected (min keeps the configured 4096).
		int reserve = Math.min(outputReserveTokens, Math.max(1024, modelCtx / 4));
		int usable = modelCtx - reserve - promptOverhead;
		if (usable < 1000) {
			usable = Math.max(1000, modelCtx / 2); // tiny-model safety net
		}
		int docCeil = (int) (usable * 0.6); // documents get up to 60% of the usable budget
		int histCeil = usable - docCeil; // history gets the rest
		int maxChunks = Math.max(1, docCeil / Math.max(1, estTokensPerChunk));
		int effectiveTopK = Math.min(requestedTopK, maxChunks);
		int effectiveHistory = Math.min(requestedHistory, histCeil);
		logger.info("Budget: modelCtx={}, usable={}, topK {}->{}, history {}->{}",
				modelCtx, usable, requestedTopK, effectiveTopK, requestedHistory, effectiveHistory);

		// Apply the (possibly reduced) history budget + selected model to this conversation's memory.
		if (chatMemory instanceof SummarizingTokenWindowChatMemory stw) {
			stw.setMaxTokensFor(conversationId, effectiveHistory);
			stw.setModelFor(conversationId, resolvedModel);
			if (recentMessagesHeader != null && recentMessagesHeader > 0) {
				stw.setRecentFor(conversationId, Math.min(recentMessagesHeader, 50));
			}
		}

		// Relevance cutoff + temperature are user-tunable per request (settings modal).
		double effectiveSimilarity = (similarityThresholdHeader != null)
				? Math.max(0.0, Math.min(1.0, similarityThresholdHeader))
				: similarityThreshold;
		Double effectiveTemperature = (temperatureHeader != null)
				? Math.max(0.0, Math.min(2.0, temperatureHeader))
				: null;

		UUID conversationUuid;
		try {
			conversationUuid = UUID.fromString(conversationId);
		} catch (IllegalArgumentException e) {
			throw new org.springframework.web.server.ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Invalid conversation ID");
		}

		Conversation conv = conversationRepo.findById(conversationUuid)
				.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
						HttpStatus.NOT_FOUND, "Conversation not found"));

		conv.setLastActive(Instant.now());
		conversationRepo.save(conv);

		logger.info("Received question: {}", question);

		// The model decides which tool to use (searchDocuments / documentStats / listDocuments)
		// or none for small talk — no keyword routing here.
		List<org.springframework.ai.chat.messages.Message> recentMessages = chatMemoryRepository
				.findByConversationId(conversationId);

		// Title strategy:
		// 1. First turn: quick provisional title from the opening message (so the sidebar isn't "...").
		//    But openers are often "שלום"/"thanks" → silly titles like "ברכת שלום".
		// 2. Third turn (once): re-generate from the actual conversation so the title reflects what the
		//    chat is really about, then leave it alone. We count USER messages (exactly one per turn) —
		//    NOT assistant messages, whose count is inflated/unreliable with tool calling. The current
		//    question is not yet in memory, so priorUserTurns == 2 means this is the 3rd question.
		int priorUserTurns = (int) recentMessages.stream()
				.filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER)
				.count();
		boolean titleIsPlaceholder = conv.getTitle() == null
				|| conv.getTitle().startsWith("New Chat") || conv.getTitle().startsWith("...");
		logger.info("Title check for {}: priorUserTurns={}, currentTitle='{}'",
				conversationId, priorUserTurns, conv.getTitle());
		if (priorUserTurns == 2) {
			generateAndSaveConversationTitle(conversationUuid, buildTitleTranscript(recentMessages, question), chatLanguage)
					.doOnError(e -> logger.warn("Title re-generation failed for {}: {}", conversationId, e.getMessage()))
					.subscribe();
		} else if (titleIsPlaceholder) {
			generateAndSaveConversationTitle(conversationUuid, question, chatLanguage)
					.doOnError(e -> logger.warn("Title generation failed for {}: {}", conversationId, e.getMessage()))
					.subscribe();
		}

		// Record which model is answering this turn so the "answered by" badge survives a refresh.
		// seq = number of prior assistant answers (this answer's 0-based ordinal).
		try {
			int answerSeq = (int) recentMessages.stream()
					.filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.ASSISTANT)
					.count();
			com.odedia.repo.model.AnswerModel row = answerModelRepo
					.findByConversationIdAndSeq(conversationId, answerSeq)
					.orElseGet(() -> new com.odedia.repo.model.AnswerModel(conversationId, answerSeq, resolvedModel));
			row.setModel(resolvedModel);
			answerModelRepo.save(row);
		} catch (Exception e) {
			logger.warn("Could not record answer model for {}: {}", conversationId, e.getMessage());
		}

		if ("yes".equals(beChatty)) {
			systemText += " Try to engage in conversation and invoke a dialog.";
		}

		var requestSpec = chatClient
				.prompt(question)
				.system(systemText);

		// Lower temperature → less improvisation. Applied only when the user set one.
		if (effectiveTemperature != null) {
			requestSpec = requestSpec.options(org.springframework.ai.chat.prompt.ChatOptions.builder()
					.temperature(effectiveTemperature)
					.build());
		}

		List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors = new ArrayList<>();
		advisors.add(SimpleLoggerAdvisor.builder().build());
		advisors.add(MessageChatMemoryAdvisor.builder(this.chatMemory).build());

		// ALWAYS register the tools. The model decides which (if any) to call - per the system
		// prompt it calls none for small talk. We must NOT withhold tools based on keyword
		// detection: a follow-up like "yes" (to "shall I search?") makes the model emit a
		// searchDocuments call, and if the tool isn't registered Spring AI throws
		// "No ToolCallback found". Per-request search settings travel via the tool context.
		requestSpec = requestSpec
				.tools(documentTools)
				.toolContext(Map.of("topK", effectiveTopK, "threshold", effectiveSimilarity));

		return requestSpec
				.advisors(advisors)
				.advisors(a -> a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, conversationId))
				.stream()
				.content();
	}

	/** Exposes the discovered max context window for a model (settings modal display). */
	@GetMapping("/context")
	public Map<String, Object> contextWindow(@RequestParam(value = "model", required = false) String model) {
		String resolved = chatModelRegistry.resolve(model).orElse(model);
		ModelContextService.ContextInfo info = modelContextService.describe(resolved);
		return Map.of(
				"model", resolved == null ? "" : resolved,
				"maxContextTokens", info.tokens(),
				"source", info.source()); // configured | probed | default
	}

	/**
	 * Build a compact transcript (prior messages + the current question) for re-titling a
	 * conversation from its real content. Each message is truncated so the title call stays cheap.
	 */
	private String buildTitleTranscript(
			List<org.springframework.ai.chat.messages.Message> history, String currentQuestion) {
		StringBuilder sb = new StringBuilder();
		for (org.springframework.ai.chat.messages.Message m : history) {
			String role;
			if (m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
				role = "User";
			} else if (m.getMessageType() == org.springframework.ai.chat.messages.MessageType.ASSISTANT) {
				role = "Assistant";
			} else {
				continue; // skip system/summary messages
			}
			String text = m.getText() == null ? "" : m.getText().strip();
			if (text.isEmpty()) {
				continue;
			}
			if (text.length() > 500) {
				text = text.substring(0, 500);
			}
			sb.append(role).append(": ").append(text).append('\n');
		}
		if (currentQuestion != null && !currentQuestion.isBlank()) {
			sb.append("User: ").append(currentQuestion.strip()).append('\n');
		}
		return sb.toString();
	}

	/**
	 * Generate a short title (model must return <= 5 words).
	 * We *ask* the model to return at most five words and only the title text.
	 * If the model ignores that, we retry up to 2 times with a targeted "shorten"
	 * prompt.
	 * As a last-resort safety net (should rarely happen) we fallback to an ID-based
	 * short title.
	 */
	public Mono<Void> generateAndSaveConversationTitle(
			UUID conversationId,
			String firstUserMessage,
			String lang) {
		logger.info("Received request to generate title for UUID {} and message {}", conversationId, firstUserMessage);

		if (conversationId == null) {
			return Mono.empty();
		}

		final String systemInstruction = ""
				+ "You are a concise title generator. Produce a single short title that captures the MAIN TOPIC "
				+ "of the conversation. Ignore greetings, thanks and small talk — title the substance, not the "
				+ "pleasantries. IMPORTANT: The title must be AT MOST FIVE WORDS "
				+ "and must contain only the title text — no explanation, no extra lines, and do NOT wrap the "
				+ "whole title in quotation marks (quotes WITHIN the title, e.g. an abbreviation like יו\"ר, "
				+ "are fine). Return exactly the title text in plain text."
				+ (lang.equals("en") ? " The title must be in English." : " הכותרת חייבת להיות בעברית.");

		String userPrompt = "Conversation:\n\n" + firstUserMessage + "\n\nTitle:";

		final Duration singleCallTimeout = Duration.ofSeconds(120);
		final ChatClient titleChatClient = chatModelRegistry.defaultClient();

		return Mono
				.fromCallable(() -> titleChatClient
						.prompt(userPrompt)
						.system(systemInstruction)
						.call()
						.content() // blocking call returning String :contentReference[oaicite:1]{index=1}
				)
				.subscribeOn(Schedulers.boundedElastic())
				.timeout(singleCallTimeout)
				.onErrorResume(throwable -> {
					logger.warn("Title generation timed out or failed for {}: {}", conversationId,
							throwable.toString());
					return Mono.just("");
				})
				.map(raw -> raw == null ? "" : raw.trim())
				.map(candidateRaw -> {
					// Normalize whitespace only. Keep INTERNAL quotes/punctuation so titles like
					// 'תהליך בחירת יו"ר' render correctly - we only strip quotes the model wrapped
					// around the WHOLE title (handled just below), not legitimate ones inside it.
					String candidate = candidateRaw == null ? "" : candidateRaw;
					candidate = candidate.replaceAll("[\\r\\n]", " ").replaceAll("\\s+", " ").trim();
					// Strip leading/trailing wrapping quotes (straight or curly) if the model added them.
					candidate = candidate.replaceAll("^[\"'`\u201C\u201D\u2018\u2019]+", "")
						.replaceAll("[\"'`\u201C\u201D\u2018\u2019]+$", "").trim();
					int wordCount = candidate.isEmpty() ? 0 : candidate.split("\\s+").length;
					return (wordCount == 0 || wordCount > 5) ? "" : candidate;
				})
				.flatMap(candidate -> {
					final String finalCandidate = candidate == null ? "" : candidate;
					return Mono.fromCallable(() -> {
						Optional<Conversation> oc = conversationRepo.findById(conversationId);
						String existing = oc.map(Conversation::getTitle).orElse(null);
						boolean existingIsReal = existing != null && !existing.isBlank()
								&& !existing.startsWith("New Chat") && !existing.startsWith("...")
								&& !existing.startsWith("שיחה חדשה");
						String toSave = finalCandidate;
						if (toSave.isBlank()) {
							// Don't clobber a good title (e.g. the turn-3 re-gen came back empty).
							if (existingIsReal) {
								logger.warn("Title generation empty; keeping existing title '{}'", existing);
								return Void.TYPE;
							}
							toSave = lang.equals("en") ? "New Chat" : "שיחה חדשה";
							logger.warn("Title generation empty; using fallback '{}'", toSave);
						}
						if ("en".equals(lang)) {
							toSave = toSave.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "").trim();
						}

						if (oc.isPresent()) {
							Conversation conv = oc.get();
							conv.setTitle(toSave);
							conversationRepo.save(conv);

							Map<String, Object> payload = Map.of(
									"event", "conversationTitleUpdated",
									"conversationId", conv.getId().toString(),
									"title", conv.getTitle());
							conversationEvents.tryEmitNext(payload);

							logger.info("Generated and saved title '{}' for conversation {}", toSave, conversationId);
						} else {
							logger.warn("Conversation {} not found when trying to save title '{}'", conversationId,
									toSave);
						}
						return Void.TYPE;
					}).subscribeOn(Schedulers.boundedElastic()).then();
				})
				.then();
	}

	/**
	 * Forwards summarization start/stop events to the front-end over the existing
	 * conversation SSE channel, so the UI can show a "summarizing" indicator while
	 * the (blocking) summarization LLM call delays the answer.
	 */
	@org.springframework.context.event.EventListener
	public void onSummarization(MessageSummarizationService.SummarizationEvent event) {
		conversationEvents.tryEmitNext(Map.of(
				"event", event.active() ? "summarizing" : "summarizingDone",
				"conversationId", event.conversationId()));
	}

	@GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, Object>>> streamConversationEvents() {
		return conversationEvents.asFlux()
				.map(payload -> ServerSentEvent.<Map<String, Object>>builder()
						.event((String) payload.get("event"))
						.data(payload)
						.build());
	}
}
