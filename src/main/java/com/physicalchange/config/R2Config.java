package com.physicalchange.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.net.URI;

/**
 * Cliente S3 de Cloudflare R2 (ADR-005). Se crea si hay R2_BUCKET, aunque STORAGE_TYPE sea database, para seguir
 * leyendo los archivos ya subidos a R2. Si falta un dato, la aplicación no arranca y dice cuál.
 * Región "auto" (la exige el SDK; R2 no la usa).
 */
@Configuration
@ConditionalOnExpression("!'${app.storage.r2.bucket:}'.isBlank()")
public class R2Config {
    private final String endpoint;
    private final StaticCredentialsProvider credentials;

    public R2Config(@Value("${app.storage.r2.account-id:}") String accountId,
                    @Value("${app.storage.r2.access-key-id:}") String accessKey,
                    @Value("${app.storage.r2.secret-access-key:}") String secretKey,
                    @Value("${app.storage.r2.bucket:}") String bucket,
                    @Value("${app.storage.r2.endpoint:}") String endpointOverride) {
        require(accessKey, "R2_ACCESS_KEY_ID");
        require(secretKey, "R2_SECRET_ACCESS_KEY");
        require(bucket, "R2_BUCKET");
        if (endpointOverride.isBlank()) require(accountId, "R2_ACCOUNT_ID (o R2_ENDPOINT)");
        endpoint = endpointOverride.isBlank() ? "https://" + accountId.trim() + ".r2.cloudflarestorage.com" : endpointOverride.trim();
        credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.trim(), secretKey.trim()));
    }

    @Bean
    public S3Client r2Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // R2 no necesita las sumas de comprobación que el SDK añade por defecto.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner r2Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Cloudflare R2 necesita la variable " + name);
    }
}
