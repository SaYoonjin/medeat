package com.medeat.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChatRequest {

    @NotBlank(message = "질문을 입력해 주세요.")
    @Size(max = 1000, message = "질문은 1000자 이내로 입력해 주세요.")
    private String message;
    
    public ChatRequest() {
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
    
    
}
