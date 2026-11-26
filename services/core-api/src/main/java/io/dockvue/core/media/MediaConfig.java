package io.dockvue.core.media;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@EnableScheduling
class MediaConfig {
    private final String endpoint,browser,region;
    private final boolean dev;
    MediaConfig(@Value("${dockvue.media.endpoint:}") String endpoint,
                @Value("${dockvue.media.browser-endpoint:}") String browser,
                @Value("${dockvue.media.region:ap-northeast-2}") String region,
                @Value("${dockvue.auth.dev:false}") boolean dev) {
        this.endpoint=endpoint;this.browser=browser;this.region=region;this.dev=dev;
        if(!dev&&(!endpoint.isBlank()||!browser.isBlank()))throw new IllegalStateException("Custom AWS endpoint is local-only");
    }
    private AwsCredentialsProvider credentials(){return dev?StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test")):DefaultCredentialsProvider.create();}
    private ClientOverrideConfiguration timeout(){return ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(15)).build();}
    @Bean S3Client mediaS3(){
        var b=S3Client.builder().region(Region.of(region)).credentialsProvider(credentials()).httpClientBuilder(UrlConnectionHttpClient.builder()).overrideConfiguration(timeout()).forcePathStyle(dev);
        if(!endpoint.isBlank())b.endpointOverride(URI.create(endpoint));return b.build();
    }
    @Bean S3Presigner mediaPresigner(){
        var b=S3Presigner.builder().region(Region.of(region)).credentialsProvider(credentials()).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(dev).build());
        if(!browser.isBlank())b.endpointOverride(URI.create(browser));else if(!endpoint.isBlank())b.endpointOverride(URI.create(endpoint));return b.build();
    }
    @Bean SqsClient mediaSqs(){
        var b=SqsClient.builder().region(Region.of(region)).credentialsProvider(credentials()).httpClientBuilder(UrlConnectionHttpClient.builder()).overrideConfiguration(timeout());
        if(!endpoint.isBlank())b.endpointOverride(URI.create(endpoint));return b.build();
    }
}
