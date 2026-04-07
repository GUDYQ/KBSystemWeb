package org.example.kbsystemproject.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final ResourcePatternResolver resourcePatternResolver;

    @Autowired
    public FileService(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * Test method to load all txt, and pdf files from classpath.
     */
    public Flux<String> loadAllTestDocuments() {
        return Flux.just("classpath:document/*.txt", "classpath:document/*.pdf")
                .flatMap(pattern -> Mono.fromCallable(() -> resourcePatternResolver.getResources(pattern))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(Flux::fromArray)
                .flatMap(this::readResourceContent)
                .onErrorResume(e -> {
                    log.error("Failed to load generic documents", e);
                    return Flux.empty();
                });
    }

    /**
     * Reads text content from a resource based on its type asynchronously.
     * Supports .txt and .pdf files.
     *
     * @param resource The resource to read
     * @return Mono emitting the extracted text content
     */
    public Mono<String> readResourceContent(Resource resource) {
        return Mono.fromCallable(() -> {
            if (resource == null || !resource.exists()) {
                throw new IllegalArgumentException("Invalid resource or resource does not exist.");
            }

            String fileName = resource.getFilename();
            if (fileName == null) {
                fileName = "";
            }
            fileName = fileName.toLowerCase();

            if (fileName.endsWith(".txt")) {
                return readTextResource(resource);
            } else if (fileName.endsWith(".pdf")) {
                return readPdfResource(resource);
            } else {
                throw new IllegalArgumentException("Unsupported file type: " + fileName);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String readTextResource(Resource resource) throws IOException {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Error reading text/md resource: {}", resource.getFilename(), e);
            throw new IOException("Failed to read text/md resource", e);
        }
    }

    private String readPdfResource(Resource resource) throws IOException {
        try (InputStream is = resource.getInputStream();
             PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(is))) {

            if (document.isEncrypted()) {
                log.warn("PDF resource is encrypted: {}", resource.getFilename());
                // Depending on requirements, we could attempt to decrypt or throw
                // throw new IOException("Cannot read encrypted PDF file");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (IOException e) {
            log.error("Error reading pdf resource: {}", resource.getFilename(), e);
            throw new IOException("Failed to read pdf resource", e);
        }
    }
}