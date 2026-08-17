package com.hackerton.ai;

import lombok.*;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRequestDto {

    private String model;
    private List<Message> messages;
    private double temperature;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }

    public static AiRequestDto createExtractPrompt(String model, String systemPrompt, String userContent) {
        return  AiRequestDto.builder()
                .model(model)
                .messages(List.of(
                        new Message("system",systemPrompt),
                        new Message("user", userContent)
                ))
                .temperature(0.1)
                .build();
    }
}
