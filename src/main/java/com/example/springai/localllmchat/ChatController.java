package com.example.springai.localllmchat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Spring AI 経由で OVMS に質問を送り、回答を返す API。
 * 会話履歴は保持せず、リクエストごとに独立した質問として扱う。
 */
@RestController
public class ChatController {

	private final ChatClient chatClient;

	/**
	 * 質問の送信に使うクライアントを作成する。
	 * @param builder Spring AI が自動設定するクライアントビルダー
	 * @param connectionProperties OpenAI クライアントの共通接続設定
	 */
	public ChatController(ChatClient.Builder builder, OpenAiCommonProperties connectionProperties) {
		this.chatClient = builder
			.defaultOptions(OpenAiChatOptions.builder().timeout(connectionProperties.getTimeout()))
			.build();
	}

	/**
	 * 質問に対する回答を同期的に取得する。未指定・空白のみの質問は HTTP 400 を返す。
	 * @param request 質問を含むリクエスト
	 * @return モデルが生成した回答
	 * @throws ResponseStatusException 質問が未指定または空白のみの場合
	 */
	@PostMapping("/api/chat")
	public ChatResponse chat(@RequestBody ChatRequest request) {
		if (request.message() == null || request.message().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
		}

		var response = this.chatClient.prompt()
			.user(request.message())
			.call()
			.chatResponse();
		if (response == null || response.getResult() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OVMS returned no answer");
		}

		return new ChatResponse(response.getResult().getOutput().getText());
	}

	/**
	 * 質問のリクエスト。
	 * @param message 空白以外の文字を含む質問の本文
	 */
	public record ChatRequest(String message) {
	}

	/**
	 * 質問に対する応答。
	 * @param content モデルが生成した回答の本文。SDK が本文を返さない場合は null
	 */
	public record ChatResponse(String content) {
	}
}
