package com.physicalchange.service;

import com.physicalchange.domain.StoredFile;
import com.physicalchange.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;
import java.time.Duration;

/**
 * Contenido de los archivos en un bucket privado de Cloudflare R2 (ADR-005). Claves: ver key().
 * Content-Type y Content-Disposition se fijan al subir porque R2 los devuelve tal cual al descargar.
 */
@Component
@ConditionalOnExpression("!'${app.storage.r2.bucket:}'.isBlank()")
public class R2FileStorage implements FileStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public R2FileStorage(S3Client s3, S3Presigner presigner, @Value("${app.storage.r2.bucket}") String bucket) {
        this.s3 = s3; this.presigner = presigner; this.bucket = bucket.trim();
    }

    @Override public String kind() { return "R2"; }
    @Override public boolean direct() { return true; }

    @Override public void save(StoredFile f, byte[] data) {
        s3.putObject(put(f), RequestBody.fromBytes(data));
    }

    @Override public byte[] read(StoredFile f) {
        try { return s3.getObjectAsBytes(get(f).build()).asByteArray(); }
        catch (NoSuchKeyException e) { throw new NotFoundException("Archivo no encontrado"); }
    }

    @Override public void delete(StoredFile f) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(f)).build());
    }

    @Override public String uploadUrl(StoredFile f, Duration ttl) {
        return presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put(f)).build())
                .url().toString();
    }

    @Override public String downloadUrl(StoredFile f, Duration ttl) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get(f).build()).build())
                .url().toString();
    }

    @Override public long size(StoredFile f) {
        try { return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(f)).build()).contentLength(); }
        catch (NoSuchKeyException e) { return -1; }
        catch (S3Exception e) { if (e.statusCode() == 404) return -1; throw e; }
    }

    @Override public byte[] head(StoredFile f, int bytes) {
        return s3.getObjectAsBytes(get(f).range("bytes=0-" + (bytes - 1)).build()).asByteArray();
    }

    /** Pasa el objeto validado de uploads/ a files/; la URL PUT, aún vigente, ya no alcanza la copia definitiva. */
    @Override public void promote(StoredFile f) {
        String from = uploadKey(f), to = fileKey(f);
        s3.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(from).destinationBucket(bucket).destinationKey(to).build());
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(from).build());
    }

    /** PENDING: uploads/{ownerId}/{fileId} (temporal, con regla de ciclo de vida de 1 día); READY: files/{ownerId}/{fileId}. */
    static String key(StoredFile f) { return "PENDING".equals(f.getStatus()) ? uploadKey(f) : fileKey(f); }
    static String fileKey(StoredFile f) { return "files/" + f.getOwnerId() + "/" + f.getId(); }
    static String uploadKey(StoredFile f) { return "uploads/" + f.getOwnerId() + "/" + f.getId(); }

    /** El tamaño va firmado: un PUT con otro Content-Length no casa con la firma. */
    private PutObjectRequest put(StoredFile f) {
        return PutObjectRequest.builder().bucket(bucket).key(key(f)).contentLength(f.getSizeBytes())
                .contentType(f.getContentType()).contentDisposition(FileService.disposition(f)).build();
    }

    private GetObjectRequest.Builder get(StoredFile f) { return GetObjectRequest.builder().bucket(bucket).key(key(f)); }
}
