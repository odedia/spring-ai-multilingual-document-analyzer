package com.odedia.analyzer.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.Message;

import com.odedia.analyzer.dto.Citation;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Per-request scratchpad shared by tools, the tool-loop advisor, and the query
 * SSE publisher. Keyed by conversation id so tool threads can find it.
 */
public final class QueryTurnContext {

	private static final ConcurrentHashMap<String, QueryTurnContext> ACTIVE = new ConcurrentHashMap<>();
	private static final Pattern INLINE_CITE = Pattern.compile(
			"\\((?:source|מקור):\\s*([^,]+),\\s*(?:page|עמוד)\\s*(\\d+)",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private final String conversationId;
	private final boolean observability;
	private final boolean rewrite;
	private final boolean crossLingual;
	private final String language;
	private final String modelName;
	private final List<Message> history;
	private final int topK;
	private final int historyBudget;
	private final String userQuestion;
	private final Set<Citation> citations = ConcurrentHashMap.newKeySet();
	private final LinkedHashSet<String> citationOrder = new LinkedHashSet<>();
	private final Sinks.Many<Map<String, Object>> events = Sinks.many().unicast().onBackpressureBuffer();
	private final Set<String> startedTools = ConcurrentHashMap.newKeySet();
	private final Set<String> toolsCalled = ConcurrentHashMap.newKeySet();

	private QueryTurnContext(String conversationId, boolean observability, boolean rewrite, boolean crossLingual,
			String language, String modelName, List<Message> history, int topK, int historyBudget, String userQuestion) {
		this.conversationId = conversationId;
		this.observability = observability;
		this.rewrite = rewrite;
		this.crossLingual = crossLingual;
		this.language = language;
		this.modelName = modelName;
		this.history = history == null ? List.of() : List.copyOf(history);
		this.topK = topK;
		this.historyBudget = historyBudget;
		this.userQuestion = userQuestion == null ? "" : userQuestion;
	}

	public static QueryTurnContext open(String conversationId, boolean observability, boolean rewrite,
			boolean crossLingual, String language, String modelName, List<Message> history, int topK,
			int historyBudget, String userQuestion) {
		QueryTurnContext ctx = new QueryTurnContext(conversationId, observability, rewrite, crossLingual, language,
				modelName, history, topK, historyBudget, userQuestion);
		ACTIVE.put(conversationId, ctx);
		return ctx;
	}

	public static QueryTurnContext get(String conversationId) {
		return conversationId == null ? null : ACTIVE.get(conversationId);
	}

	public void close() {
		ACTIVE.remove(conversationId, this);
		events.tryEmitComplete();
	}

	public void completeEvents() {
		events.tryEmitComplete();
	}

	public Flux<Map<String, Object>> eventFlux() {
		return events.asFlux();
	}

	public void emit(String event, Map<String, Object> data) {
		java.util.HashMap<String, Object> payload = new java.util.HashMap<>(data);
		payload.put("event", event);
		events.tryEmitNext(payload);
	}

	public void noteTool(String name) {
		if (name != null && !name.isBlank()) {
			toolsCalled.add(name);
		}
	}

	public void emitToolStart(String name, String queryOrArgs) {
		noteTool(name);
		if (name == null || !startedTools.add(name + ":" + queryOrArgs)) {
			return;
		}
		java.util.HashMap<String, Object> data = new java.util.HashMap<>();
		data.put("name", name);
		data.put("phase", "start");
		if (queryOrArgs != null && !queryOrArgs.isBlank()) {
			data.put("query", queryOrArgs.length() > 240 ? queryOrArgs.substring(0, 240) : queryOrArgs);
		}
		emit("tool", data);
	}

	public void emitToolEnd(String name, long durationMs) {
		emit("tool", Map.of("name", name, "phase", "end", "durationMs", durationMs));
	}

	public void obs(String line) {
		if (!observability || line == null || line.isBlank()) {
			return;
		}
		obsEvent(Map.of("kind", "log", "line", line));
	}

	public void obsEvent(Map<String, Object> data) {
		if (!observability || data == null || data.isEmpty()) {
			return;
		}
		emit("obs", data);
	}

	public void addCitation(String filename, int page) {
		if (filename == null || filename.isBlank() || page < 0) {
			return;
		}
		Citation c = new Citation(filename.trim(), page);
		if (citations.add(c)) {
			synchronized (citationOrder) {
				citationOrder.add(c.filename() + "\0" + c.page());
			}
		}
	}

	public List<Map<String, Object>> citationList() {
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (citationOrder) {
			for (String key : citationOrder) {
				int split = key.indexOf('\0');
				if (split < 0) {
					continue;
				}
				out.add(Map.of("filename", key.substring(0, split), "page", Integer.parseInt(key.substring(split + 1))));
			}
		}
		return out;
	}

	/**
	 * Chips for this turn: retrieved [SOURCE] tags that the question or answer
	 * actually names. Inventory tools (listDocuments / documentStats) are not
	 * content citations — leftover search hits from a previous topic are dropped.
	 */
	public List<Map<String, Object>> citationListForAnswer(String answer) {
		List<Map<String, Object>> all = citationList();
		if (all.isEmpty()) {
			return all;
		}
		Set<String> mentioned = mentionedFilenames(userQuestion, answer, all);
		if (!mentioned.isEmpty()) {
			List<Map<String, Object>> filtered = new ArrayList<>();
			for (Map<String, Object> c : all) {
				String fn = String.valueOf(c.getOrDefault("filename", "")).trim();
				if (mentioned.contains(fn.toLowerCase())) {
					filtered.add(c);
				}
			}
			return filtered;
		}
		boolean inventory = toolsCalled.contains("listDocuments") || toolsCalled.contains("documentStats");
		boolean scoped = toolsCalled.contains("searchInDocument") || toolsCalled.contains("getPage");
		if (inventory && !scoped) {
			return List.of();
		}
		return all;
	}

	private static Set<String> mentionedFilenames(String question, String answer,
			List<Map<String, Object>> retrieved) {
		String hay = ((question == null ? "" : question) + "\n" + (answer == null ? "" : answer)).toLowerCase();
		Set<String> out = new LinkedHashSet<>();
		Matcher inline = INLINE_CITE.matcher(answer == null ? "" : answer);
		while (inline.find()) {
			out.add(inline.group(1).trim().toLowerCase());
		}
		for (Map<String, Object> c : retrieved) {
			String fn = String.valueOf(c.getOrDefault("filename", "")).trim();
			if (!fn.isEmpty() && mentionsFilename(hay, fn)) {
				out.add(fn.toLowerCase());
			}
		}
		return out;
	}

	static boolean mentionsFilename(String hayLower, String filename) {
		String fn = filename.toLowerCase();
		if (hayLower.contains(fn)) {
			return true;
		}
		int slash = Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\'));
		String base = slash >= 0 ? fn.substring(slash + 1) : fn;
		if (!base.equals(fn) && hayLower.contains(base)) {
			return true;
		}
		int dot = base.lastIndexOf('.');
		String stem = dot > 0 ? base.substring(0, dot) : base;
		return stem.length() >= 3 && hayLower.contains(stem);
	}

	public boolean isObservability() {
		return observability;
	}

	public boolean isRewrite() {
		return rewrite;
	}

	public boolean isCrossLingual() {
		return crossLingual;
	}

	public String getLanguage() {
		return language;
	}

	public String getModelName() {
		return modelName;
	}

	public List<Message> getHistory() {
		return history;
	}

	public int getTopK() {
		return topK;
	}

	public int getHistoryBudget() {
		return historyBudget;
	}

	public String getConversationId() {
		return conversationId;
	}
}
