package com.example.springai.localllmchat;

import java.io.IOException;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OVMS の応答を OpenAI SDK が扱える形式に補正する設定。
 */
@Configuration(proxyBeanMethods = false)
class OvmsCompatibilityConfiguration {

	/**
	 * チャット応答の ID を補完するインターセプターを登録する。
	 * @return OpenAI HTTP クライアントの設定処理
	 */
	@Bean
	OpenAiHttpClientBuilderCustomizer ovmsResponseIdCustomizer() {
		return builder -> builder.interceptor(new ResponseIdInterceptor("/v1/chat/completions"));
	}

	/**
	 * OVMS が省略することのある id を、SDK が応答を読み取る前に補完する。
	 * 通常の JSON 応答のみを対象とし、ストリーミング応答は変更しない。
	 */
	static final class ResponseIdInterceptor implements Interceptor {

		private final ObjectMapper mapper = new ObjectMapper();

		private final String completionsPath;

		/**
		 * 補正する API のパスを指定する。
		 * @param completionsPath チャット API の絶対パス
		 */
		ResponseIdInterceptor(String completionsPath) {
			this.completionsPath = completionsPath;
		}

		/**
		 * 成功した JSON のチャット応答に限り、不足する ID を補完する。
		 * 既存の ID、エラー応答、ストリーミング応答は変更しない。
		 * @param chain HTTP リクエストの実行チェーン
		 * @return SDK が読み取る HTTP レスポンス
		 * @throws IOException HTTP 通信または本文の読み書きに失敗した場合
		 */
		@Override
		public Response intercept(Chain chain) throws IOException {
			Request request = chain.request();
			Response response = chain.proceed(request);
			ResponseBody body = response.body();
			if (!isChatCompletion(request) || !response.isSuccessful()
					|| body == null || !isJson(body.contentType())) {
				return response;
			}

			// 本文は一度しか読めないため、補正の有無にかかわらず読み取り後に再構築する。
			byte[] content = supplementResponseId(body.bytes());
			return response.newBuilder()
				// 補正で本文の長さが変わるため、元の Content-Length は引き継がない。
				.removeHeader("Content-Length")
				.body(ResponseBody.create(content, body.contentType()))
				.build();
		}

		private boolean isChatCompletion(Request request) {
			return "POST".equals(request.method())
					&& this.completionsPath.equals(request.url().encodedPath());
		}

		private boolean isJson(MediaType contentType) {
			return contentType != null
					&& "application".equals(contentType.type())
					&& "json".equals(contentType.subtype());
		}

		private byte[] supplementResponseId(byte[] original) throws IOException {
			try {
				var json = this.mapper.readTree(original);
				if (json instanceof ObjectNode completion && completion.path("choices").isArray()
						&& needsResponseId(completion.path("id"))) {
					completion.put("id", "chatcmpl-" + UUID.randomUUID());
					return this.mapper.writeValueAsBytes(completion);
				}
			}
			catch (JsonProcessingException ignored) {
				// 不正な JSON はそのまま返し、SDK の通常のエラー処理に任せる。
			}
			// 既存の id や補正対象外の応答は、元のバイト列を保持する。
			return original;
		}

		private boolean needsResponseId(JsonNode id) {
			return id.isMissingNode() || id.isNull() || (id.isTextual() && id.asText().isBlank());
		}
	}
}
