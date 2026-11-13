package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import org.fitznet.fitznetapi.dto.requests.UploadAttachmentRequestDto;
import org.fitznet.fitznetapi.dto.responses.AttachmentContentDto;
import org.fitznet.fitznetapi.dto.responses.AttachmentDto;
import org.fitznet.fitznetapi.service.ai.AttachmentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Image attachments (contract section 10): upload first, then reference the id in a turn's
 * {@code attachmentIds}. Owner-only. Every endpoint needs {@code AI_USER} or {@code ADMIN}; the
 * gRPC {@code AiChatService} calls the byte-oriented overloads of the same methods.
 */
@RestController
@RequestMapping("/ai/attachments")
@PreAuthorize("hasAnyAuthority('PERM_AI_USER','PERM_ADMIN')")
public class AiAttachmentController {

  /** Attachments never change, so the browser may keep them (in its private cache) for a year. */
  static final String CACHE_CONTROL = "private, max-age=31536000, immutable";

  private final AttachmentService attachmentService;

  public AiAttachmentController(AttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @PostMapping
  public AttachmentDto uploadAttachment(@RequestBody @Valid UploadAttachmentRequestDto request) {
    byte[] data = attachmentService.decodeBase64(request.getDataBase64());
    return attachmentService.upload(
        currentUsername(), request.getName(), request.getMimeType(), data);
  }

  /** gRPC {@code UploadAttachment}: the same checks on raw bytes. */
  public AttachmentDto uploadAttachment(String name, String mimeType, byte[] data) {
    return attachmentService.upload(currentUsername(), name, mimeType, data);
  }

  /** The raw bytes with the attachment's {@code Content-Type}. */
  @GetMapping("/{id}")
  public ResponseEntity<byte[]> getAttachment(@PathVariable String id) {
    AttachmentContentDto content = readAttachment(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.mimeType()))
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(content.data());
  }

  /** gRPC {@code GetAttachment}: the mime type and bytes. */
  public AttachmentContentDto readAttachment(String id) {
    return attachmentService.read(currentUsername(), id);
  }

  private static String currentUsername() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
