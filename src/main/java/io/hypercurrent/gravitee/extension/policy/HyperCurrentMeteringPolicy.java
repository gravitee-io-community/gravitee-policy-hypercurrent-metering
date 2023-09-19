package io.hypercurrent.gravitee.extension.policy;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.MediaType;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.OnResponse;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava.circuitbreaker.CircuitBreaker;
import okhttp3.OkHttpClient;
import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@SuppressWarnings("unused")
public class HyperCurrentMeteringPolicy {

    private final HyperCurrentMeteringPolicyConfiguration configuration;
    private static final Logger logger = LoggerFactory.getLogger(HyperCurrentMeteringPolicy.class);
    private static final String METERING_HTTP_ERROR = "METERING_HTTP_ERROR";
    private static final String HTTPS_SCHEME = "https";
    private static final String HYPERCURRENT_PRODUCT_KEY_HEADER = "x-hypercurrent-product-key";
    private static final String GRAVITEE_API_KEY_HEADER = "X-Gravitee-Api-Key";
    private static final String CLIENT_ID = "client_id";
    private static final String REQUEST_PHASE = "request";
    private static final String RESPONSE_PHASE = "response";
    private static final String PAYMENT_REQUIRED_MESSAGE = "{\"message\": \"Payment required\"}";
    private static final int PAYMENT_REQUIRED_STATUS_CODE = 402;

    ObjectMapper mapper = new ObjectMapper();

    Map<String, String> cachedProductKeys;

    WebClient asyncClient = null;
    OkHttpClient syncClient = new OkHttpClient();

    HttpRequest<Buffer> meteringRequest;

    JsonFactory jsonFactory;

    CircuitBreaker breaker;

    /**
     * Create a new HyperCurrentMetering Policy instance based on its associated configuration
     *
     * @param configuration the associated configuration to the new HyperCurrentMetering Policy instance
     */
    public HyperCurrentMeteringPolicy(HyperCurrentMeteringPolicyConfiguration configuration) {
        this.configuration = configuration;
        cachedProductKeys = Collections.synchronizedMap(new PassiveExpiringMap<>(
                configuration.getProductKeyCacheTTL() * 60000L));
        jsonFactory = new JsonFactory();
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext context, PolicyChain policyChain) {

        boolean resolvedConsumer;

        if (configuration.getActiveMode()) {

            String consumer = extractConsumer(request, context);
            if (consumer == null) {
                logger.error("Couldn't resolve consumer from headers: {}", request.headers().names());
                policyChain.failWith(PolicyResult.build(PAYMENT_REQUIRED_STATUS_CODE,
                        "hypercurrent-metering", PAYMENT_REQUIRED_MESSAGE,
                        Collections.emptyMap(), MediaType.APPLICATION_JSON));
                return;
            } else {
                if (!validateConsumerKey(consumer)) {
                    policyChain.failWith(PolicyResult.build(PAYMENT_REQUIRED_STATUS_CODE,
                            "hypercurrent-metering", PAYMENT_REQUIRED_MESSAGE,
                            Collections.emptyMap(), MediaType.APPLICATION_JSON));
                    return;
                } else {
                    resolvedConsumer = true;
                }
            }
        } else {
            resolvedConsumer = isResolvedConsumer(request);
        }

        if (resolvedConsumer) {
            String requestId = request.id();
            logger.info("Posting request: {}", requestId);
            policyChain.doNext(request, response);
            try {
                postMetering(context, request, response, REQUEST_PHASE);
            } catch (IOException e) {
                logger.error("Couldn't post metering request", e);
            }
        } else {
            logger.warn("Couldn't resolve Product Key or Application from request: {}", request.headers().names());
            policyChain.doNext(request, response);
        }
    }

    @OnResponse
    public void onResponse(Request request, Response response, ExecutionContext context, PolicyChain policyChain) {

        if (isASuccessfulResponse(request, response, context)) {
            policyChain.doNext(request, response);
            try {
                postMetering(context, request, response, RESPONSE_PHASE);
            } catch (IOException e) {
                logger.error("Couldn't post metering request", e);
            }
        } else {
            policyChain.doNext(request, response);
        }
    }

    private void postMetering(ExecutionContext context,
                              Request request,
                              Response response,
                              String phase) throws IOException {

        initialize(context);

        byte[] body;

        if (phase.equals(REQUEST_PHASE)) {
            body = buildRequestEvent(request).toByteArray();
        } else {
            body = buildResponseEvent(request, response, context).toByteArray();
        }

        breaker.executeWithFallback(circuit -> meteringRequest.sendBuffer(Buffer.buffer(body))
                        .onSuccess(
                                r -> {
                                    if (!isASuccessfulResponse(request, response, context)) {
                                        String errorMessage =
                                                String.format("Error response from Metering API: [%d]: %s",
                                                        r.statusCode(), r.statusMessage());
                                        logger.error(errorMessage);
                                        circuit.fail(errorMessage);
                                    } else {
                                        circuit.complete();
                                    }
                                }
                        )
                        .onFailure(ex -> {
                                    logger.error("Metering failure", ex);
                                    circuit.fail(ex);
                                }
                        ),
                v -> {
                    String meteringPayload = new String(body);
                    logger.info("Backed up Metering event: {}", meteringPayload);
                    return meteringPayload;
                });
    }

    private boolean validateConsumerKey(String key) {
        if (cachedProductKeys.containsKey(key)) {
            return true;
        }
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(configuration.getMeteringUrl() + "/meter/product-key?productKey=" + key)
                .header("x-api-key", configuration.getApiKey())
                .get()
                .build();
        try (okhttp3.Response response = syncClient.newCall(request).execute()) {
            switch (response.code()) {
                case 200:
                    cachedProductKeys.put(key, key);
                    return true;
                case 404:
                    return false;
                default:
                    logger.error("Unknown response code from Metering API: {}", response.code());
                    return false;
            }
        } catch (Exception e) {
            logger.error("Couldn't validate consumer key", e);
            return false;
        }
    }

    private synchronized void initialize(ExecutionContext context) {
        if (asyncClient == null) {
            breaker = CircuitBreaker.create("hypercurrent-circuit-breaker", new
                                    io.vertx.rxjava.core.Vertx(context.getComponent(Vertx.class)),
                            new CircuitBreakerOptions()
                                    .setTimeout(Long.MAX_VALUE) // not seeing a way to disable timeouts
                                    .setMaxRetries(5)
                                    .setMaxFailures(5)
                                    .setFallbackOnFailure(true)
                                    .setResetTimeout(10000)
                    ).retryPolicy(retryCount -> retryCount * 1000L)
                    .openHandler(v -> logger.error("Circuit opened"))
                    .halfOpenHandler(v -> logger.error("Circuit half-opened"))
                    .closeHandler(v -> logger.error("Circuit closed"));

            WebClientOptions options = new WebClientOptions();
            options.setKeepAlive(true);
            options.setMaxPoolSize(100);//
            asyncClient = WebClient.create(context.getComponent(Vertx.class), options);
            meteringRequest = asyncClient.postAbs(configuration.getMeteringUrl() + "/event");
            meteringRequest.putHeader("x-api-key", configuration.getApiKey());
            meteringRequest.putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        }
    }

    private String extractConsumer(Request request, ExecutionContext context) {
        if (StringUtils.isNotBlank(configuration.getMeteringExpression())) {
            logger.info("Evaluating metering expression: " + configuration.getMeteringExpression());
            TemplateEngine templateEngine = context.getTemplateEngine();
            templateEngine.getTemplateContext().setVariable("request", request);
            return templateEngine.getValue(configuration.getSuccessExpression(), String.class);
        }

        if (request.headers().contains(HYPERCURRENT_PRODUCT_KEY_HEADER)) {
            return request.headers().get(HYPERCURRENT_PRODUCT_KEY_HEADER);
        } else if (request.headers().contains(GRAVITEE_API_KEY_HEADER)) {
            return request.headers().get((GRAVITEE_API_KEY_HEADER));
        } else if (request.headers().contains(CLIENT_ID)) {
            return request.headers().get(CLIENT_ID);
        } else {
            return null;
        }
    }

    private boolean isResolvedConsumer(Request request) {
        return request.headers().contains(HYPERCURRENT_PRODUCT_KEY_HEADER) ||
                request.headers().contains(GRAVITEE_API_KEY_HEADER) ||
                request.headers().contains(CLIENT_ID);
    }

    private boolean isASuccessfulResponse(Request request, Response response, ExecutionContext context) {
        if (configuration.getSuccessExpression() != null
                && StringUtils.isNotBlank(configuration.getSuccessExpression())) {
            TemplateEngine templateEngine = context.getTemplateEngine();
            templateEngine.getTemplateContext().setVariable("request", request);
            templateEngine.getTemplateContext().setVariable("response", response);
            return templateEngine.getValue(configuration.getSuccessExpression(), Boolean.class);
        } else {
            switch (response.status() / 100) {
                case 1:
                case 2:
                case 3:
                    return true;
                default:
                    return false;
            }
        }
    }

    private void enrichConsumer(Request request, JsonGenerator generator) throws IOException {
        if (request.headers().contains(HYPERCURRENT_PRODUCT_KEY_HEADER)) {
            generator.writeStringField("productKey", request.headers().get(HYPERCURRENT_PRODUCT_KEY_HEADER));
        } else if (request.headers().contains(GRAVITEE_API_KEY_HEADER)) {
            generator.writeStringField("application", request.headers().get((GRAVITEE_API_KEY_HEADER)));
        } else if (request.headers().contains(CLIENT_ID)) {
            generator.writeStringField("application", request.headers().get(CLIENT_ID));
        } else {
            logger.warn("Couldn't infer consumer (productKey or application) from request");
        }
    }

    private ByteArrayOutputStream buildRequestEvent(Request request) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try (JsonGenerator generator = jsonFactory.createGenerator(stream, JsonEncoding.UTF8)) {
            generator.writeStartObject();
            generator.writeStringField("requestId", request.id());
            generator.writeStringField("eventType", "REQUEST");
            generator.writeStringField("method", request.method().name());
            generator.writeNumberField("currentMillis", System.currentTimeMillis());
            generator.writeStringField("uri", request.uri());
            enrichConsumer(request, generator);
            generator.writeEndObject();
        }
        return stream;
    }

    private ByteArrayOutputStream buildResponseEvent(Request request, Response response, ExecutionContext context)
            throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try (JsonGenerator generator = jsonFactory.createGenerator(stream, JsonEncoding.UTF8)) {

            generator.writeStartObject();
            generator.writeStringField("requestId", request.id());
            generator.writeStringField("eventType", "RESPONSE");
            generator.writeNumberField("currentMillis", System.currentTimeMillis());
            generator.writeStringField("uri", request.uri());
            generator.writeStringField("method", request.method().name());
            generator.writeNumberField("responseCode", response.status());
            generator.writeNumberField("requestMessageSize", request.metrics().getRequestContentLength());
            generator.writeNumberField("responseMessageSize", request.metrics().getResponseContentLength());
            if (request.headers().contains(HttpHeaders.CONTENT_TYPE)) {
                generator.writeStringField("contentType", request.headers().get(HttpHeaders.CONTENT_TYPE));
            }

            generator.writeStringField("remoteHost", request.remoteAddress());

            if (request.headers().size() > 0) {
                generator.writeArrayFieldStart("requestHeaders");
                String[] headerNames = request.headers().names().toArray(new String[0]);
                generator.writeArray(headerNames, 0, headerNames.length);
                generator.writeEndArray();
            }
            if (request.headers().size() > 0) {
                generator.writeArrayFieldStart("responseHeaders");
                String[] headerNames = response.headers().names().toArray(new String[0]);
                generator.writeArray(headerNames, 0, headerNames.length);
                generator.writeEndArray();
            }
            generator.writeStringField("userAgent", request.metrics().getUserAgent());
            generator.writeStringField("correlationId", request.transactionId());
            generator.writeNumberField("backendLatency", request.metrics().getApiResponseTimeMs());

            if (StringUtils.isNotBlank(configuration.getMetadataExpression())) {
                TemplateEngine templateEngine = context.getTemplateEngine();
                templateEngine.getTemplateContext().setVariable("request", request);
                templateEngine.getTemplateContext().setVariable("response", response);
                try {
                    generator.writeStringField("metadata",
                            templateEngine.getValue(configuration.getMetadataExpression(), String.class));
                } catch (Throwable t) {
                    logger.error("Couldn't evaluate metadata expression: " + configuration.getMetadataExpression(), t);
                }
            }
            enrichConsumer(request, generator);
            generator.writeEndObject();
        }
        return stream;
    }
}
