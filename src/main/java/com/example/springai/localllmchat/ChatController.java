package com.example.springai.localllmchat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Spring AI 経由で OVMS に質問を送り、回答を返す API。
 * 会話履歴は保持せず、リクエストごとに独立した質問として扱う。
 */
@RequestMapping("/api/chat")
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
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ChatResponse chat(@RequestBody ChatRequest request) {
		return answer(request.message(), null);
	}

	/**
	 * 5MB 以下の PNG または JPEG 画像と質問を受け取り、画像についての回答を返す。
	 * 質問が空の場合は、画像の説明を依頼する。
	 * @param message 画像についての質問。空の場合は既定の依頼文を使用する
	 * @param image 解析する PNG または JPEG 画像（multipart フィールド名: files）
	 * @return モデルが生成した回答
	 * @throws ResponseStatusException 画像が空または PNG/JPEG 以外の場合
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ChatResponse chatWithImage(@RequestParam(name = "message", defaultValue = "") String message,
			@RequestPart("files") MultipartFile image) {
		if (image.isEmpty() || !(MediaType.IMAGE_PNG_VALUE.equals(image.getContentType())
				|| MediaType.IMAGE_JPEG_VALUE.equals(image.getContentType()))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PNG or JPEG image is required");
		}
		return answer(message.isBlank() ? "この画像を説明してください。" : message, image);
	}

	/**
	 * 質問と任意の画像をモデルに送り、生成された回答を返す。
	 * @param message モデルに送る質問
	 * @param image 添付する画像。テキストのみの場合は null
	 * @return モデルが生成した回答
	 * @throws ResponseStatusException 質問が空白の場合は HTTP 400、OVMS から回答がない場合は HTTP 502
	 */
	private ChatResponse answer(String message, MultipartFile image) {
		if (message == null || message.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
		}

		var response = this.chatClient.prompt()
			.user(user -> {
				user.text(message);
				if (image != null) {
					user.media(MimeTypeUtils.parseMimeType(image.getContentType()), image.getResource());
				}
			})
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
