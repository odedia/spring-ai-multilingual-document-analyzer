package com.odedia.analyzer.query;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Sits inside the Spring AI 2.0 tool loop and publishes tool-start / usage
 * events onto the current {@link QueryTurnContext} SSE channel.
 */
public class ToolProgressAdvisor implements CallAdvisor, StreamAdvisor {

	public static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 10;

	@Override
	public String getName() {
		return getClass().getSimpleName();
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		observe(request, null);
		ChatClientResponse response = chain.nextCall(request);
		observe(request, response);
		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return chain.nextStream(request).doOnNext(response -> observe(request, response));
	}

	private static void observe(ChatClientRequest request, ChatClientResponse response) {
		if (response == null || response.chatResponse() == null) {
			return;
		}
		Object rawId = request.context().get(ChatMemory.CONVERSATION_ID);
		QueryTurnContext ctx = QueryTurnContext.get(rawId == null ? null : rawId.toString());
		if (ctx == null) {
			return;
		}
		ChatResponse cr = response.chatResponse();
		if (cr.hasToolCalls()) {
			Generation gen = cr.getResult();
			if (gen != null && gen.getOutput() instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
					ctx.emit("status", Map.of("phase", "tool", "name", tc.name()));
					ctx.emitToolStart(tc.name(), extractQuery(tc.arguments()));
				}
			}
		}
		if (ctx.isObservability() && cr.getMetadata() != null && cr.getMetadata().getUsage() != null) {
			var usage = cr.getMetadata().getUsage();
			ctx.obsEvent(Map.of(
					"kind", "tokens",
					"prompt", n(usage.getPromptTokens()),
					"completion", n(usage.getCompletionTokens()),
					"total", n(usage.getTotalTokens())));
		}
	}

	private static String extractQuery(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return argumentsJson;
		}
		int q = argumentsJson.indexOf("\"query\"");
		if (q < 0) {
			q = argumentsJson.indexOf("\"filename\"");
		}
		if (q < 0) {
			return argumentsJson.length() > 200 ? argumentsJson.substring(0, 200) : argumentsJson;
		}
		int colon = argumentsJson.indexOf(':', q);
		int firstQuote = argumentsJson.indexOf('"', colon + 1);
		int secondQuote = firstQuote < 0 ? -1 : argumentsJson.indexOf('"', firstQuote + 1);
		if (firstQuote >= 0 && secondQuote > firstQuote) {
			return argumentsJson.substring(firstQuote + 1, secondQuote);
		}
		return argumentsJson;
	}

	private static long n(Integer v) {
		return v == null ? 0 : v.longValue();
	}
}
