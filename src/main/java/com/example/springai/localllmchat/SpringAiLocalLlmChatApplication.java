package com.example.springai.localllmchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ローカルの OVMS を利用するチャット API の起動クラス。 */
@SpringBootApplication
public class SpringAiLocalLlmChatApplication {

	/**
	 * Spring Boot アプリケーションを起動する。
	 * @param args Spring Boot に渡す起動引数
	 */
	public static void main(String[] args) {
		SpringApplication.run(SpringAiLocalLlmChatApplication.class, args);
	}

}
