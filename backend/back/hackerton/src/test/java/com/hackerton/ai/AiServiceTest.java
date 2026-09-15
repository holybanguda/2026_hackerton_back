package com.hackerton.ai;

import com.hackerton.common.ApiException;
import com.hackerton.recommendation.RecommendationDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AiServiceTest {
    HttpServer server;
    AiService service;
    AtomicReference<String> captured=new AtomicReference<>();
    String response;
    int status=200;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service=new AiService("http://127.0.0.1:"+server.getAddress().getPort(),1000,1000);
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void httpStubPreservesDetailedItemsAndLegacyFields() {
        response="{\"recommendedItems\":[{\"menuName\":\"A\",\"price\":100,\"count\":2,\"totalItemPrice\":200,\"category\":\"메인\"}],\"recommendedMenus\":[\"A x 2\"],\"totalPrice\":200,\"profile\":\"balance\"}";
        var out=service.getAiRecommendation(new RecommendationDto.Request());
        assertEquals("A x 2",out.getRecommendedMenus().getFirst());
        assertEquals(2,out.getRecommendedItems().getFirst().getCount());
        assertEquals("메인",out.getRecommendedItems().getFirst().getCategory());
    }
    @Test void noAlternativeFromPythonIsNotConvertedToFallback() {
        status=409; response="{\"detail\":{\"code\":\"NO_ALTERNATIVE_COMBINATION\"}}";
        ApiException error=assertThrows(ApiException.class,() -> service.getAiReRecommendation(new RecommendationDto.ReRecommendRequest()));
        assertEquals(409,error.getStatus());
        assertEquals("NO_ALTERNATIVE_COMBINATION",error.getCode());
    }
    @Test void fractionalQuantityIsRejectedInsteadOfTruncated() {
        response="{\"recommendedItems\":[{\"menuName\":\"A\",\"price\":100,\"count\":1.5,\"totalItemPrice\":150}]}";
        assertThrows(ApiException.class,() -> service.getAiRecommendation(new RecommendationDto.Request()));
    }
}

