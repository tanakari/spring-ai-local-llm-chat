package com.example.springai.localllmchat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringAiLocalLlmChatApplicationTests {

	private static final AtomicReference<String> ovmsRequest = new AtomicReference<>();

	private static final HttpServer ovms = startOvmsStub();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void configureOvms(DynamicPropertyRegistry properties) {
		// 実際の OVMS ではなくテスト用スタブへ接続する。
		properties.add("spring.ai.openai.chat.base-url",
				() -> "http://127.0.0.1:" + ovms.getAddress().getPort() + "/v1");
	}

	@AfterAll
	static void stopOvms() {
		ovms.stop(0);
	}

	@Test
	void returnsAnswerFromOvmsEvenWhenResponseIdIsMissing() throws Exception {
		var response = post("{\"message\":\"質問です\"}");

		assertEquals(200, response.statusCode());
		var mapper = new ObjectMapper();
		assertEquals("こんにちは", mapper.readTree(response.body()).path("content").asText());
		// コントローラーから今回の質問が OVMS に渡ったことを確認する。
		var messages = mapper.readTree(ovmsRequest.get()).path("messages");
		assertEquals(1, messages.size());
		assertEquals("user", messages.get(0).path("role").asText());
		assertEquals("質問です", messages.get(0).path("content").asText());
	}

	private HttpResponse<String> post(String body) throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/chat"))
				.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private static HttpServer startOvmsStub() {
		try {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/v1/chat/completions", exchange -> {
				try (exchange) {
					ovmsRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
					// OVMS と同様に id を省略し、互換性補正を通す。
					byte[] body = """
							{"object":"chat.completion","created":0,"model":"gemma4-e4b",
							 "choices":[{"index":0,"message":{"role":"assistant","content":"こんにちは"},"finish_reason":"stop"}]}
							""".getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(200, body.length);
					exchange.getResponseBody().write(body);
				}
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot start OVMS stub", ex);
		}
	}

}
