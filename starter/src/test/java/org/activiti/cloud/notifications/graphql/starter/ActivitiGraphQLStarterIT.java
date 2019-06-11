/*
 * Copyright 2018 Alfresco, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.activiti.cloud.notifications.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import org.activiti.cloud.api.process.model.events.CloudProcessCreatedEvent;
import org.activiti.cloud.api.process.model.events.CloudProcessStartedEvent;
import org.activiti.cloud.api.process.model.impl.events.CloudProcessCreatedEventImpl;
import org.activiti.cloud.api.process.model.impl.events.CloudProcessStartedEventImpl;
import org.activiti.cloud.notifications.graphql.test.EngineEventsMessageProducer;
import org.activiti.cloud.notifications.graphql.test.EngineEventsMessageProducer.EngineEvents;
import org.activiti.cloud.services.graphql.web.ActivitiGraphQLController.GraphQLQueryRequest;
import org.activiti.cloud.services.notifications.graphql.ws.api.GraphQLMessage;
import org.activiti.cloud.services.notifications.graphql.ws.api.GraphQLMessageType;
import org.activiti.cloud.services.test.identity.keycloak.interceptor.KeycloakTokenProducer;
import org.assertj.core.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClient.WebsocketSender;
import reactor.test.StepVerifier;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ActivitiGraphQLStarterIT {

    private static final String WS_GRAPHQL_URI = "/ws/graphql";
    private static final String GRAPHQL_WS = "graphql-ws";
    private static final String HRUSER = "hruser";
    private static final String AUTHORIZATION = "Authorization";
    private static final String TESTADMIN = "testadmin";
    private static final String TASK_NAME = "task1";
    private static final String GRAPHQL_URL = "/graphql";
    private static final Duration TIMEOUT = Duration.ofMillis(20000);
    
    @LocalServerPort
    private String port;
    
    @Autowired
    private KeycloakTokenProducer keycloakTokenProducer;
    
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EngineEvents producerChannel;

    @Autowired
    private ObjectMapper objectMapper;
    
    private HttpHeaders authHeaders;
    
    @SpringBootApplication
    @ComponentScan({"org.activiti.cloud.starters.test",
                    "org.activiti.cloud.services.test.identity.keycloak.interceptor"})
    @EnableBinding(EngineEventsMessageProducer.EngineEvents.class)
    static class Application {
        // Nothing
    }

    
    /**
     * 
     */
    @Before
    public void setUp() {
        keycloakTokenProducer.setKeycloakTestUser(TESTADMIN);
        authHeaders = keycloakTokenProducer.authorizationHeaders();
    }
    
    protected URI getUrl(String path) throws URISyntaxException {
        return new URI("ws://localhost:" + this.port + path);
    }    
    
    @Test
    public void testGraphqlWsSubprotocolConnectionInitXAuthorizationSupported() throws JsonProcessingException {
        ReplayProcessor<String> output = ReplayProcessor.create();
        
        keycloakTokenProducer.setKeycloakTestUser(TESTADMIN);
        final String accessToken = keycloakTokenProducer.authorizationHeaders().getFirst(AUTHORIZATION);
        
        
        Map<String, Object> payload = new StringObjectMapBuilder().put("kaInterval", 1000)
                                                                  .put("X-Authorization", accessToken)
                                                                  .get();
        
        String initMessage = objectMapper.writeValueAsString(GraphQLMessage.builder()
                                                                           .type(GraphQLMessageType.CONNECTION_INIT)
                                                                           .payload(payload)
                                                                           .build());
        
        HttpClient.create()
                  .baseUrl("ws://localhost:"+port)
                  .wiretap(true)
                  .websocket(GRAPHQL_WS)
                  .uri(WS_GRAPHQL_URI)
                  .handle((i, o) -> {
                      o.options(NettyPipeline.SendOptions::flushOnEach)
                       .sendString(Mono.just(initMessage))
                       .then()
                       .log("client-send")
                       .subscribe();
                      
                      return i.receive().asString();
                  })
                  .log("client-received")
                  .take(2)
                  .subscribeWith(output)
                  .collectList()
                  .subscribe();

        String ackMessage = objectMapper.writeValueAsString(GraphQLMessage.builder()
                                                                          .type(GraphQLMessageType.CONNECTION_ACK)
                                                                          .build());

        String kaMessage = objectMapper.writeValueAsString(GraphQLMessage.builder()
                                                                         .type(GraphQLMessageType.KA)
                                                                         .build());
        
        StepVerifier.create(output)
                    .expectNext(ackMessage)
                    .expectNext(kaMessage)
                    .expectComplete()
                    .verify(TIMEOUT);
    }
    
    
    @Test
    public void testGraphqlWsSubprotocolServerStartStopSubscription() throws JsonProcessingException {
        ReplayProcessor<String> connect = ReplayProcessor.create();
        ReplayProcessor<String> data = ReplayProcessor.create();
        ReplayProcessor<String> complete = ReplayProcessor.create();
        
        keycloakTokenProducer.setKeycloakTestUser(TESTADMIN);
        final String auth = keycloakTokenProducer.authorizationHeaders().getFirst(AUTHORIZATION);
        
        Map<String, Object> variables = new StringObjectMapBuilder().put("appName", "default-app")
                                                                    .get();
         
        Map<String, Object> payload = new StringObjectMapBuilder().put("query", "subscription($appName: String!) { engineEvents(appName: $appName) { appName serviceName PROCESS_CREATED { processInstanceId } PROCESS_STARTED { processInstanceId } } }")
                                                                  .put("variables", variables)
                                                                  .get();        
        GraphQLMessage start = GraphQLMessage.builder()
                                             .type(GraphQLMessageType.START)
                                             .id("1")
                                             .payload(payload)
                                             .build();
        
        String startMessage = objectMapper.writeValueAsString(start);
        
        // given 
        CloudProcessCreatedEvent event1 = new CloudProcessCreatedEventImpl() {
            {
                setAppName("default-app");
                setServiceName("rb-my-app");
                setServiceFullName("serviceFullName");
                setServiceType("runtime-bundle");
                setServiceVersion("");
                setProcessInstanceId("processInstanceId");
                setProcessDefinitionId("processDefinitionId");
                setProcessDefinitionKey("processDefinitionKey");
                setProcessDefinitionVersion(1);
            }
        };

        CloudProcessStartedEvent event2 = new CloudProcessStartedEventImpl() {
            {
                setAppName("default-app");
                setServiceName("rb-my-app");
                setServiceType("runtime-bundle");
                setServiceFullName("serviceFullName");
                setServiceType("runtime-bundle");
                setServiceVersion("");
                setProcessInstanceId("processInstanceId");
                setProcessDefinitionId("processDefinitionId");
                setProcessDefinitionKey("processDefinitionKey");
                setProcessDefinitionVersion(1);
            }
        };        
        
        WebsocketSender client = HttpClient.create()
                                           .baseUrl("ws://localhost:" + port)
                                           .wiretap(true)
                                           .headers(h -> h.add(AUTHORIZATION, auth))
                                           .websocket(GRAPHQL_WS)
                                           .uri(WS_GRAPHQL_URI);
        
       // start subscription
       client.handle((i, o) -> {
              o.options(NettyPipeline.SendOptions::flushOnEach)
               .sendString(Mono.just(startMessage))
               .then()
               .log("start")
               .subscribe();
             
            return i.receive()
                    .asString()
                    .log("data")
                    .take(1)
                    .doOnSubscribe(s -> producerChannel.output()
                                                       .send(MessageBuilder.withPayload(Arrays.array(event1, event2))
                                                                           .setHeader("routingKey", "eventProducer")
                                                                           .build()))
                    .delaySubscription(Duration.ofSeconds(1))
                    .subscribeWith(data);
        }) // stop subscription
        .collectList()
        .subscribe();
        
        // then        
        Map<String, Object> message = Collections.singletonMap("data",
                                         Collections.singletonMap("engineEvents",
                                              MapBuilder.builder().put("appName", "default-app")
                                                                  .put("serviceName", "rb-my-app")
                                                                  .put("PROCESS_CREATED", Arrays.array(Collections.singletonMap("processInstanceId", "processInstanceId")))
                                                                  .put("PROCESS_STARTED", Arrays.array(Collections.singletonMap("processInstanceId", "processInstanceId")))
                                              .get())
                                      );
        
        String dataMessage = objectMapper.writeValueAsString(GraphQLMessage.builder()
                                                                           .type(GraphQLMessageType.DATA)
                                                                           .id("1")
                                                                           .payload(message)
                                                                           .build());
        StepVerifier.create(data)
                    .expectNext(dataMessage)
                    .expectComplete()
                    .verify(TIMEOUT);
    }    
    
    @Test
    public void testGraphqlWsSubprotocolServerWithUserRoleNotAuthorized() {
        ReplayProcessor<String> output = ReplayProcessor.create();

        keycloakTokenProducer.setKeycloakTestUser(HRUSER);
        
        final String accessToken =  keycloakTokenProducer.authorizationHeaders().getFirst(AUTHORIZATION);
        
        String initMessage = "{\"type\":\"connection_init\",\"payload\":{\"X-Authorization\":\""+accessToken+"\"}}";
        
        HttpClient.create()
                  .baseUrl("ws://localhost:"+port)
                  .wiretap(true)
                  .websocket(GRAPHQL_WS)
                  .uri(WS_GRAPHQL_URI)
                  .handle((i, o) -> {
                      o.options(NettyPipeline.SendOptions::flushOnEach)
                       .sendString(Mono.just(initMessage))
                       .then()
                       .log("client-send")
                       .subscribe();
                      
                      return i.receive().asString();
                  })
                  .log("client-received")
                  .take(1)
                  .subscribeWith(output)
                  .collectList()
                  .doOnError(i -> System.err.println("Failed requesting server: " + i))
                  .subscribe();
        
        String expected = "{\"payload\":{},\"id\":null,\"type\":\"connection_error\"}";
        
        StepVerifier.create(output)
                    .expectNext(expected)
                    .verifyComplete();
    }    

    @Test
    public void testGraphqlWsSubprotocolServerUnauthorized() {
        ReplayProcessor<String> output = ReplayProcessor.create();

        String initMessage = "{\"type\":\"connection_init\",\"payload\":{}}";
        
        HttpClient.create()
                  .baseUrl("ws://localhost:"+port)
                  .wiretap(true)
                  //.headers(h -> h.add(AUTHORIZATION, auth)) // Anonymous request
                  .websocket(GRAPHQL_WS)
                  .uri(WS_GRAPHQL_URI)
                  .handle((i, o) -> {
                      o.options(NettyPipeline.SendOptions::flushOnEach)
                       .sendString(Mono.just(initMessage))
                       .then()
                       .log("client-send")
                       .subscribe();
                      
                      return i.receive().asString();
                  })
                  .log("client-received")
                  .take(1)
                  .subscribeWith(output)
                  .collectList()
                  .doOnError(i -> System.err.println("Failed requesting server: " + i))
                  .subscribe();
        
        String expected = "{\"payload\":{},\"id\":null,\"type\":\"connection_error\"}";
        
        StepVerifier.create(output)
                    .expectNext(expected)
                    .verifyComplete();
    }    
    
    @Test
    public void testGraphql() {
        GraphQLQueryRequest query = new GraphQLQueryRequest("{Tasks(where:{name:{EQ: \"" + TASK_NAME + "\"}}){select{id assignee priority}}}");
        
        ResponseEntity<Result> entity = rest.postForEntity(GRAPHQL_URL, new HttpEntity<>(query,authHeaders), Result.class);

        assertThat(HttpStatus.OK)
            .describedAs(entity.toString())
            .isEqualTo(entity.getStatusCode());

        Result result = entity.getBody();

        assertThat(result).isNotNull();
        assertThat(result.getErrors())
            .isNull();

        assertThat("{Tasks={select=[{id=1, assignee=assignee, priority=5}]}}")
            .isEqualTo(result.getData().toString());

    }

    @Test
    public void testGraphqlUnauthorized() {
        GraphQLQueryRequest query = new GraphQLQueryRequest("{Tasks(where:{name:{EQ: \"" + TASK_NAME + "\"}}){select{id assignee priority}}}");
        
        keycloakTokenProducer.setKeycloakTestUser(HRUSER);
        authHeaders = keycloakTokenProducer.authorizationHeaders();
        
        ResponseEntity<Result> entity = rest.postForEntity(GRAPHQL_URL, new HttpEntity<>(query,authHeaders), Result.class);

        assertThat(HttpStatus.FORBIDDEN)
            .describedAs(entity.toString())
            .isEqualTo(entity.getStatusCode());

    }
    
    @Test
    public void testGraphqlWhere() {
        // @formatter:off
        GraphQLQueryRequest query = new GraphQLQueryRequest(
        	    "query {" +
        	    "	  ProcessInstances(page: {start: 1, limit: 10}," + 
        	    "	    where: {status : {EQ: COMPLETED }}) {" +
        	    "	    pages" +
        	    "	    total" +
        	    "	    select {" +
        	    "	      id" +
        	    "	      processDefinitionId" +
        	    "	      processDefinitionKey" +
        	    "	      status" +
        	    "	      tasks {" +
        	    "	        name" +
        	    "	        status" +
        	    "	      }" +
        	    "	    }" +
        	    "	  }" +
        	    "	}");
       // @formatter:on

        ResponseEntity<Result> entity = rest.postForEntity(GRAPHQL_URL, new HttpEntity<>(query, authHeaders), Result.class);

        assertThat(HttpStatus.OK)
            .describedAs(entity.toString())
            .isEqualTo(entity.getStatusCode());

        Result result = entity.getBody();

        assertThat(result).isNotNull();
        assertThat(result.getErrors())
            .isNull();
        
        assertThat(((Map<String, Object>) result.getData()).get("ProcessInstances")).isNotNull();
    }

    @Test
    public void testGraphqlNesting() {
        // @formatter:off
        GraphQLQueryRequest query = new GraphQLQueryRequest(
                "query {"
                + "ProcessInstances {"
                + "    select {"
                + "      id"
                + "      tasks {"
                + "        id"
                + "        name"
                + "        variables {"
                + "          name"
                + "          value"
                + "        }"
                + "        taskCandidateUsers {"
                + "           taskId"
                + "           userId"
                + "        }"
                + "        taskCandidateGroups {"
                + "           taskId"
                + "           groupId"
                + "        }"
                + "      }"
                + "      variables {"
                + "        name"
                + "        value"
                + "      }"
                + "    }"
                + "  }"
                + "}");
       // @formatter:on

        ResponseEntity<Result> entity = rest.postForEntity(GRAPHQL_URL, new HttpEntity<>(query, authHeaders), Result.class);

        assertThat(HttpStatus.OK)
            .describedAs(entity.toString())
            .isEqualTo(entity.getStatusCode());

        Result result = entity.getBody();

        assertThat(result).isNotNull();
        assertThat(result.getErrors())
            .isNull();
        
        assertThat(((Map<String, Object>) result.getData()).get("ProcessInstances")).isNotNull();
    }

    @Test
    public void testGraphqlReverse() {
        // @formatter:off
        GraphQLQueryRequest query = new GraphQLQueryRequest(
        		" query {"
        	    + " ProcessVariables {"
        	    + "    select {"
        	    + "      id"
        	    + "      name"
        	    + "      value"
        	    + "      processInstance(where: {status: {EQ: RUNNING}}) {"
        	    + "        id"
        	    + "      }"
        	    + "    }"
        	    + "  }"
        	    + "}"
        		);
       // @formatter:on

        ResponseEntity<Result> entity = rest.postForEntity(GRAPHQL_URL, new HttpEntity<>(query, authHeaders), Result.class);

        assertThat(HttpStatus.OK)
            .describedAs(entity.toString())
            .isEqualTo(entity.getStatusCode());

        Result result = entity.getBody();

        assertThat(result).isNotNull();
        assertThat(result.getErrors())
            .isNull();
        
        assertThat(((Map<String, Object>) result.getData()).get("ProcessVariables")).isNotNull();
    }
    
    @Test
    public void testGraphqlArguments() throws JsonParseException, JsonMappingException, IOException {
        GraphQLQueryRequest query = new GraphQLQueryRequest("query TasksQuery($name: String!) {Tasks(where:{name:{EQ: $name}}) {select{id assignee priority}}}");

        HashMap<String, Object> variables = new HashMap<>();
        variables.put("name", TASK_NAME);

        query.setVariables(variables);

        ResponseEntity<Result> entity = rest.postForEntity(GRAPHQL_URL, new HttpEntity<>(query, authHeaders), Result.class);

        assertThat(HttpStatus.OK)
            .describedAs(entity.toString())
            .isEqualTo(entity.getStatusCode());

        Result result = entity.getBody();

        assertThat(result).isNotNull();
        assertThat(result.getErrors())
            .isNull();

        assertThat("{Tasks={select=[{id=1, assignee=assignee, priority=5}]}}")
            .isEqualTo(result.getData().toString());
    }
}


/**
 * A map builder creating a map with String keys and values.
 */
class StringObjectMapBuilder extends MapBuilder<StringObjectMapBuilder, String, Object> {

}

class MapBuilder<B extends MapBuilder<B, K, V>, K, V> {
    
    public static StringObjectMapBuilder builder() {
        return new StringObjectMapBuilder();
    }

    protected static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private final Map<K, V> map = new LinkedHashMap<>();

    public B put(K key, V value) {
        this.map.put(key, value);
        return _this();
    }

    public Map<K, V> get() {
        return this.map;
    }

    @SuppressWarnings("unchecked")
    protected final B _this() {
        return (B) this;
    }

}


class Result implements ExecutionResult {

    private Map<String, Object> data;
    private List<GraphQLError> errors;
    private Map<Object, Object> extensions;

    /**
     * Default
     */
    Result() {
    }

    /**
     * @param data the data to set
     */
    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    /**
     * @param errors the errors to set
     */
    public void setErrors(List<GraphQLError> errors) {
        this.errors = errors;
    }

    /**
     * @param extensions the extensions to set
     */
    public void setExtensions(Map<Object, Object> extensions) {
        this.extensions = extensions;
    }

    @Override
    public <T> T getData() {
        return (T) data;
    }

    @Override
    public List<GraphQLError> getErrors() {
        return errors;
    }

    @Override
    public Map<Object, Object> getExtensions() {
        return extensions;
    }

    @Override
    public Map<String, Object> toSpecification() {
        return null;
    }
}
